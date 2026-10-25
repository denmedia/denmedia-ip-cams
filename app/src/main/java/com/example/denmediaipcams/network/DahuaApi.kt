package com.example.denmediaipcams.network

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/** Событие камеры Dahua из потока eventManager (например, Code=VideoMotion;action=Start;index=0) */
data class DahuaEvent(val code: String, val action: String, val index: Int) {
    val isStart: Boolean get() = action.equals("Start", ignoreCase = true)
    val isStop: Boolean get() = action.equals("Stop", ignoreCase = true)

    /** Название для пользователя; null — событие, о котором не сообщаем */
    val title: String? get() = DahuaApi.eventTitle(code)
}

/** События, активные у камер прямо сейчас (id камеры → название): плеер показывает по ним плашку */
object CameraEventState {
    val active = mutableStateMapOf<String, String>()
}

/** Запись на SD-карте камеры или регистраторе */
data class DahuaRecording(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val type: String,
    val lengthBytes: Long?,
    /** Путь файла на накопителе, например /mnt/sd/2026-10-09/001/dav/21/21.05.05-21.05.32[M][0@0][0].dav */
    val filePath: String? = null,
    /** События, по которым сделана запись (VideoMotion и др.); пусто — запись по расписанию */
    val events: List<String> = emptyList()
) {
    /** Папка файла на накопителе (для просмотра «по папкам»); null — камера путь не сообщила */
    val directory: String?
        get() = filePath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

    /** Название файла без папки */
    val fileName: String?
        get() = filePath?.substringAfterLast('/')

    /** Путь RTSP для воспроизведения этой записи */
    val playbackPath: String
        get() = "/cam/playback?channel=1&subtype=0&starttime=${start.format(PLAYBACK_TIME)}&endtime=${end.format(PLAYBACK_TIME)}"

    private companion object {
        val PLAYBACK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")
    }
}

sealed class ArchiveResult {
    data class Found(val recordings: List<DahuaRecording>) : ArchiveResult()

    /** Камера не отдаёт список записей по HTTP (нет SD-карты, упрощённая прошивка) */
    data class Unsupported(val message: String) : ArchiveResult()
    data class Failed(val message: String) : ArchiveResult()
}

/**
 * HTTP API камер Dahua: проверка, поток событий и поиск записей. Работает через те же логин и пароль,
 * что и остальные запросы к камере, и не требует закрытого порта 37777.
 * Блокирующие вызовы выполняются на Dispatchers.IO внутри suspend-функций.
 */
object DahuaApi {

    private const val TAG = "DahuaApi"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val MAX_RECORDINGS = 500
    private const val FIND_BATCH = 100
    private val SEARCH_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val EVENT_TITLES = mapOf(
        "VideoMotion" to "Движение",
        "SmartMotionHuman" to "Человек",
        "SmartMotionVehicle" to "Транспорт",
        // Так события названы в записях на карте (поле Events у файла)
        "smdTypeHuman" to "Человек",
        "smdTypeVehicle" to "Транспорт",
        "CrossLineDetection" to "Пересечение линии",
        "CrossRegionDetection" to "Вторжение в зону",
        "FaceDetection" to "Лицо",
        "VideoBlind" to "Перекрытие камеры",
        "VideoAbnormalDetection" to "Сбой видео",
        "AlarmLocal" to "Тревожный вход",
        "AudioMutation" to "Резкий звук"
    )

    fun eventTitle(code: String): String? = EVENT_TITLES[code]

    internal fun baseUrl(camera: IpCamera) = "http://${camera.ipAddress}:${camera.effectiveHttpPort}"

    // ---------------- Проверка ----------------

    /**
     * model — модель, если это Dahua; authRejected — камера ответила, но не приняла логин и пароль;
     * reachable — HTTP-порт камеры вообще ответил (иначе проверку стоит повторить позже)
     */
    data class Probe(val model: String?, val authRejected: Boolean, val reachable: Boolean = true)

    suspend fun probe(host: String, httpPort: Int, user: String, pass: String): Probe = withContext(Dispatchers.IO) {
        val cleanHost = host.removePrefix("rtsp://").removePrefix("http://").removePrefix("https://")
            .substringAfter("@").substringBefore("/").substringBefore(":").trim()
        if (cleanHost.isBlank()) return@withContext Probe(null, false)
        val response = CameraHttp.request(
            "http://$cleanHost:$httpPort/cgi-bin/magicBox.cgi?action=getDeviceType",
            user = user,
            pass = pass
        )
        when {
            response.code == 0 -> Probe(null, false, reachable = false)
            response.isSuccess && response.body.trimStart().startsWith("type=") ->
                Probe(response.body.trim().removePrefix("type="), false)
            response.code == 401 && response.authChallenged -> Probe(null, true)
            else -> Probe(null, false)
        }
    }

    // ---------------- Возможности объектива ----------------

    /** Есть ли у камеры управляемые зум и фокус; null — камера не сообщила, считаем «возможно есть» */
    data class LensCaps(val zoom: Boolean?, val focus: Boolean?)

    /**
     * Спрашивает у камеры, есть ли моторизованные зум и фокус. Источники: возможности PTZ-протокола,
     * возможности видеовхода (ElectricFocus) и состояние фокуса. Явный отказ даёт false,
     * отсутствие ответа — null (кнопки остаются доступными).
     */
    suspend fun fetchLensCaps(camera: IpCamera): LensCaps = withContext(Dispatchers.IO) {
        fun get(path: String) = CameraHttp.request(
            "${baseUrl(camera)}/cgi-bin/$path", user = camera.username, pass = camera.password, timeoutMs = 5_000
        )
        val ptz = get("ptz.cgi?action=getCurrentProtocolCaps&channel=0")
        val input = get("devVideoInput.cgi?action=getCaps&channel=1")
        val status = get("devVideoInput.cgi?action=getFocusStatus&channel=1")
        parseLensCaps(
            ptz.takeIf { it.code != 0 }?.let { if (it.isSuccess) it.body else "" },
            input.takeIf { it.code != 0 }?.let { if (it.isSuccess) it.body else "" },
            status.takeIf { it.code != 0 }?.isSuccess
        )
    }

    /**
     * @param ptzCaps ответ getCurrentProtocolCaps (пустая строка — камера отказала, null — не ответила)
     * @param inputCaps ответ devVideoInput getCaps (так же)
     * @param focusStatusOk getFocusStatus выполнен успешно (null — камера не ответила)
     */
    internal fun parseLensCaps(ptzCaps: String?, inputCaps: String?, focusStatusOk: Boolean?): LensCaps {
        fun flags(body: String?, key: String): List<Boolean> = body.orEmpty().lineSequence().mapNotNull { line ->
            val match = Regex("""caps\.\w*$key\w*\s*=\s*(true|false)\s*$""", RegexOption.IGNORE_CASE).find(line.trim())
            match?.groupValues?.get(1)?.toBoolean()
        }.toList()

        fun decide(key: String, extraPositive: Boolean): Boolean? {
            val found = flags(ptzCaps, key) + flags(inputCaps, key)
            return when {
                extraPositive || true in found -> true
                false in found -> false
                // Все проверки получили отказ — у камеры нет ни PTZ, ни моторизованного объектива
                ptzCaps == "" && inputCaps == "" && focusStatusOk == false -> false
                else -> null
            }
        }

        val focusStatus = focusStatusOk == true
        return LensCaps(
            zoom = decide("Zoom", focusStatus),
            focus = decide("Focus", focusStatus)
        )
    }

    // ---------------- События ----------------

    /**
     * Подписывается на события камеры и передаёт их в onEvent, пока корутина не отменена.
     * При обрыве соединения или отказе камеры бросает IOException — вызывающий решает, когда переподключиться.
     */
    suspend fun listenEvents(camera: IpCamera, onEvent: (DahuaEvent) -> Unit) {
        withContext(Dispatchers.IO) {
            val path = "/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D&heartbeat=5"
            val port = camera.effectiveHttpPort
            val authorization = authorizationFor(camera, "GET", path)

            // Ответ камеры — бесконечный multipart без длины (keep-alive): HttpURLConnection отдаёт по нему
            // пустое тело, поэтому читаем сокет сами
            val socket = Socket()
            try {
                closeOnCancel(socket) {
                    socket.connect(InetSocketAddress(camera.ipAddress, port), CONNECT_TIMEOUT_MS)
                    // Камера шлёт «пульс» каждые 5 секунд: тишина дольше 20 секунд — соединение мёртвое
                    socket.soTimeout = 20_000
                    socket.getOutputStream().apply {
                        write(
                            buildString {
                                append("GET $path HTTP/1.1\r\n")
                                append("Host: ${camera.ipAddress}:$port\r\n")
                                append("User-Agent: DenMediaIPCams\r\n")
                                authorization?.let { append("Authorization: $it\r\n") }
                                append("Connection: keep-alive\r\n\r\n")
                            }.toByteArray(Charsets.ISO_8859_1)
                        )
                        flush()
                    }
                    val input = socket.getInputStream()
                    val statusLine = readHeaders(input).substringBefore("\r\n")
                    if (statusLine.split(" ").getOrNull(1) != "200") {
                        throw IOException("Камера отклонила подписку на события: ${statusLine.ifBlank { "нет ответа" }}")
                    }
                    val reader = input.bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: throw IOException("Камера закрыла поток событий")
                        parseEvent(line)?.let(onEvent)
                    }
                }
            } catch (e: IOException) {
                // Сокет закрыт нами при отмене — это не ошибка камеры
                coroutineContext.ensureActive()
                throw e
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Закрывает сокет сразу при отмене корутины: блокирующие чтение и запись иначе не прервать
     * (Job.invokeOnCompletion сработал бы только после завершения блока).
     */
    private suspend fun <T> closeOnCancel(socket: Socket, block: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    internal fun parseEvent(line: String): DahuaEvent? {
        if (!line.startsWith("Code=")) return null
        val fields = line.split(';', limit = 4).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val code = fields["Code"]?.takeIf { it.isNotBlank() } ?: return null
        return DahuaEvent(code, fields["action"].orEmpty(), fields["index"]?.toIntOrNull() ?: 0)
    }

    // ---------------- Архив ----------------

    /**
     * Записи камеры за сутки (канал 1). Сначала CGI (mediaFileFind.cgi); если прошивка его не знает —
     * JSON-RPC, как у веб-интерфейса камеры: у новых и упрощённых моделей CGI-поиска нет, а RPC есть.
     */
    suspend fun findRecordings(camera: IpCamera, day: LocalDate): ArchiveResult = withContext(Dispatchers.IO) {
        val viaCgi = findViaCgi(camera, day)
        if (viaCgi is ArchiveResult.Unsupported) DahuaArchive.findDay(camera, day) else viaCgi
    }

    private fun findViaCgi(camera: IpCamera, day: LocalDate): ArchiveResult {
        fun get(path: String) = CameraHttp.request(
            "${baseUrl(camera)}/cgi-bin/$path",
            user = camera.username,
            pass = camera.password,
            timeoutMs = 8_000
        )

        val create = get("mediaFileFind.cgi?action=factory.create")
        when {
            create.code == 0 -> return ArchiveResult.Failed("Камера не отвечает по HTTP-порту ${camera.effectiveHttpPort}")
            create.code == 401 || create.code == 403 -> return ArchiveResult.Failed("Камера не приняла логин или пароль")
        }
        val objectId = create.body.lineSequence().firstOrNull { it.startsWith("result=") }
            ?.substringAfter("=")?.trim()
        if (!create.isSuccess || objectId.isNullOrBlank()) {
            return ArchiveResult.Unsupported("В прошивке камеры нет CGI-поиска записей (HTTP ${create.code}).")
        }

        try {
            val start = Uri.encode(day.atStartOfDay().format(SEARCH_TIME))
            val end = Uri.encode(day.atTime(23, 59, 59).format(SEARCH_TIME))
            val find = get(
                "mediaFileFind.cgi?action=findFile&object=$objectId&condition.Channel=1" +
                    "&condition.StartTime=$start&condition.EndTime=$end&condition.Types%5B0%5D=dav"
            )
            if (!find.isSuccess) {
                return ArchiveResult.Failed("Камера отклонила поиск записей (HTTP ${find.code})")
            }

            val recordings = mutableListOf<DahuaRecording>()
            while (recordings.size < MAX_RECORDINGS) {
                val next = get("mediaFileFind.cgi?action=findNextFile&object=$objectId&count=$FIND_BATCH")
                if (!next.isSuccess) break
                val batch = parseRecordings(next.body)
                recordings += batch
                if (batch.size < FIND_BATCH) break
            }
            return ArchiveResult.Found(recordings.sortedBy { it.start })
        } finally {
            runCatching { get("mediaFileFind.cgi?action=close&object=$objectId") }
            runCatching { get("mediaFileFind.cgi?action=destroy&object=$objectId") }
        }
    }

    /** Ответ findNextFile: строки вида items[0].StartTime=2026-10-10 12:00:00 */
    internal fun parseRecordings(body: String): List<DahuaRecording> {
        val itemField = Regex("""items\[(\d+)]\.(\w+)=(.*)""")
        val items = sortedMapOf<Int, MutableMap<String, String>>()
        body.lineSequence().forEach { line ->
            val match = itemField.find(line.trim()) ?: return@forEach
            val (index, name, value) = match.destructured
            items.getOrPut(index.toInt()) { mutableMapOf() }[name] = value.trim()
        }
        return items.values.mapNotNull { recordingFrom(it) }
    }

    /** Запись из полей StartTime / EndTime / Type / Length; null — время не разобрать или конец не позже начала */
    internal fun recordingFrom(fields: Map<String, String>): DahuaRecording? {
        val start = runCatching { LocalDateTime.parse(fields["StartTime"], SEARCH_TIME) }.getOrNull()
        val end = runCatching { LocalDateTime.parse(fields["EndTime"], SEARCH_TIME) }.getOrNull()
        if (start == null || end == null || !end.isAfter(start)) return null
        return DahuaRecording(start, end, fields["Type"].orEmpty(), fields["Length"]?.toLongOrNull())
    }

    // ---------------- Сокеты с Digest-авторизацией ----------------

    /**
     * Заголовок Authorization для запроса: сначала запрос без авторизации получает вызов Digest,
     * по нему считается ответ. null — камера авторизации не требует.
     */
    private fun authorizationFor(camera: IpCamera, method: String, path: String): String? {
        val challenge = fetchChallenge(camera.ipAddress, camera.effectiveHttpPort, method, path) ?: return null
        if (camera.username.isBlank()) throw IOException("Камера требует логин и пароль")
        return NetworkTester.buildAuthHeader(challenge, camera.username, camera.password, method, path)
    }

    /** Запрос без авторизации, чтобы получить вызов Digest из ответа 401; null — вызова нет */
    private fun fetchChallenge(host: String, port: Int, method: String, path: String): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(
                    ("$method $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1)
                )
                flush()
            }
            // Читаем только заголовки: камера не всегда закрывает соединение сразу после ответа
            val lines = readHeaders(socket.getInputStream()).lineSequence()
                .filter { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
                .map { it.substringAfter(":").trim() }
                .toList()
            lines.firstOrNull { it.startsWith("Digest", ignoreCase = true) } ?: lines.firstOrNull()
        }
    } catch (e: IOException) {
        throw IOException("Камера не отвечает по HTTP-порту $port (${e.message})", e)
    }

    /** Читает ответ до пустой строки после заголовков (побайтно, чтобы не забрать лишнего из потока) */
    private fun readHeaders(input: java.io.InputStream): String {
        val head = StringBuilder()
        while (head.length < 8 * 1024) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n")) break
        }
        return head.toString()
    }

}
