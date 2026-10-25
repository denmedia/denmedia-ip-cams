package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Накопитель камеры (SD-карта и т.п.) */
data class StorageVolume(
    val path: String,
    val totalBytes: Long?,
    val usedBytes: Long?,
    /** Имя устройства, например /dev/mmc0 */
    val device: String? = null,
    /** Состояние, которое сообщает камера (Success — карта исправна) */
    val state: String? = null,
    /** Режим: ReadWrite, ReadOnly... */
    val type: String? = null
)

/** Сведения о накопителях камеры и файловая система карты */
data class StorageInfo(val volumes: List<StorageVolume>, val fileSystem: String?)

/** Ошибка архива: по виду понятно, что делать — перейти на другой способ, показать сообщение или повторить */
class ArchiveException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** Камера не умеет отдавать записи этим способом или не нашла накопитель */
        UNSUPPORTED,

        /** Камера не приняла логин и пароль */
        AUTH,

        /** Камера не ответила или оборвала соединение */
        NETWORK
    }
}

/**
 * Архив записей камер Dahua через JSON-RPC (/RPC2_Login и /RPC2) — так же, как работает веб-интерфейс камеры.
 *
 * Камера плохо переносит одновременные запросы (при четырёх параллельных потоках она зависала на минуты),
 * поэтому у сессии один вход и очередь: запросы выполняются строго по одному, в порядке поступления.
 * Поиск обязательно получает путь накопителя (Dirs): без него камера отвечает ошибкой на любой запрос.
 * Условие поиска всегда с временем начала и конца: запрос без них подвешивает веб-сервер камеры.
 */
class DahuaArchiveSession(private val camera: IpCamera) {

    private val mutex = Mutex()
    private var client: RpcClient? = null
    private var volumeCache: List<StorageVolume>? = null

    // Выход из камеры не должен зависеть от закрытой корутины экрана
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Накопители камеры */
    suspend fun volumes(): List<StorageVolume> = locked { rpc -> volumes(rpc) }

    /**
     * Самый свежий день с записями в интервале [lower, upper] — один запрос. Повторяя его с границей
     * «начало найденного дня», получаем дни с записями от новых к старым, пропуская пустые промежутки.
     * null — в интервале записей нет.
     */
    suspend fun newestDay(lower: LocalDateTime, upper: LocalDateTime): LocalDate? = locked { rpc ->
        rpc.search(dirs(rpc), lower, upper, limit = 1, newestFirst = true).firstOrNull()?.start?.toLocalDate()
    }

    /** Записи за сутки со всех накопителей, по времени */
    suspend fun recordings(day: LocalDate): List<DahuaRecording> = locked { rpc ->
        rpc.search(dirs(rpc), day.atStartOfDay(), day.atTime(23, 59, 59), MAX_RECORDINGS).sortedBy { it.start }
    }

    /** Свежие сведения о накопителях: состояние, объём, занятое место, файловая система */
    suspend fun storageInfo(): StorageInfo = locked { rpc ->
        val volumes = rpc.storageVolumes()
        volumeCache = volumes
        StorageInfo(volumes, rpc.fileSystem())
    }

    /**
     * Форматирует накопитель: ВСЕ записи на нём удаляются. Вызывающий обязан сначала получить подтверждение
     * пользователя. Камера может надолго занять запрос и перезагрузиться — тогда придёт ArchiveException.NETWORK,
     * и состояние нужно перепроверить через [storageInfo].
     */
    suspend fun format(volume: StorageVolume) = locked { rpc ->
        val device = volume.device ?: throw RpcFailure(Failure.UNSUPPORTED, "Камера не сообщила имя накопителя")
        volumeCache = null
        rpc.format(device)
    }

    /** Выход из камеры (в фоне); сессией после этого пользоваться нельзя */
    fun close() {
        keepAliveJob.cancel()
        ioScope.launch {
            mutex.withLock {
                runCatching { client?.logout() }
                client = null
            }
        }
    }

    private fun volumes(rpc: RpcClient): List<StorageVolume> = volumeCache ?: rpc.storageVolumes().also { volumeCache = it }

    private fun dirs(rpc: RpcClient): List<String> {
        val all = volumes(rpc)
        if (all.isEmpty()) throw RpcFailure(Failure.UNSUPPORTED, "В камере не найден накопитель: SD-карта не установлена или не определилась.")
        return all.map { it.path }
    }

    /**
     * Выполняет запрос на сессии, при необходимости входя заново. Камера закрывает сессию, если с ней долго
     * не работать («session is out of date!»), — тогда входим снова и повторяем запрос один раз.
     */
    private suspend fun <T> locked(block: (RpcClient) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                attempt(block)
            } catch (e: SessionExpired) {
                client = null
                try {
                    attempt(block)
                } catch (again: SessionExpired) {
                    client = null
                    throw ArchiveException(ArchiveException.Kind.NETWORK, "Камера закрыла сессию сразу после входа")
                }
            }
        }
    }

    /** Одна попытка: вход, если сессии нет, затем запрос; ошибки камеры переводятся в ArchiveException */
    private fun <T> attempt(block: (RpcClient) -> T): T = try {
        val rpc = client ?: RpcClient(camera).also {
            it.login()
            client = it
        }
        block(rpc)
    } catch (e: RpcFailure) {
        throw ArchiveException(
            if (e.kind == Failure.AUTH) ArchiveException.Kind.AUTH else ArchiveException.Kind.UNSUPPORTED,
            e.message.orEmpty()
        )
    } catch (e: IOException) {
        // Соединение с камерой могло оборваться вместе с сессией — следующий запрос войдёт заново
        client = null
        throw ArchiveException(ArchiveException.Kind.NETWORK, e.message ?: "Камера не отвечает")
    }

    // Камера сообщает при входе keepAliveInterval = 30 с: без обращений дольше сессия закрывается.
    // Пока окно открыто, периодически отправляем keepAlive, чтобы не входить заново после паузы
    private val keepAliveJob = ioScope.launch {
        while (true) {
            delay(KEEP_ALIVE_MS)
            mutex.withLock {
                try {
                    client?.keepAlive()
                } catch (e: Exception) {
                    client = null
                }
            }
        }
    }

    private companion object {
        const val MAX_RECORDINGS = 500
        const val KEEP_ALIVE_MS = 25_000L
    }
}

/** Камера закрыла RPC-сессию из-за простоя */
private class SessionExpired : Exception("session is out of date")

// Код ответа камеры на запрос по закрытой сессии
private const val SESSION_EXPIRED_ERROR = 287637504

private enum class Failure { UNSUPPORTED, AUTH }

private class RpcFailure(val kind: Failure, message: String) : Exception(message)

private val SEARCH_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// Код ответа камеры на поиск, под который не попал ни один файл (0x11030004)
private const val NO_FILES_ERROR = 285409284

// Камера отвечает отказом на пачку из 100 записей (проверено: 50 принимает), поэтому читаем по 50
private const val FIND_BATCH = 50

private const val FALLBACK_STORAGE_PATH = "/mnt/sd"

// Форматирование карты на 100+ ГБ занимает минуты
private const val FORMAT_TIMEOUT_MS = 180_000

private class RpcClient(camera: IpCamera) {
    private val base = DahuaApi.baseUrl(camera)
    private val user = camera.username
    private val password = camera.password
    private var session = ""

    // Объект поиска на камере. Переиспользуется, пока поиск не исчерпан и не закрыт: после close() и после
    // исчерпания следующий findFile на нём отвечает отказом без кода, а после «файлов нет» объект непригоден
    private var finder: Long? = null

    private fun post(url: String, body: JSONObject, timeoutMs: Int = 10_000): JSONObject? {
        val response = CameraHttp.request(url, method = "POST", body = body.toString(), contentType = "application/json", timeoutMs = timeoutMs)
        if (response.code == 0) throw IOException(response.body)
        return if (response.isSuccess) runCatching { JSONObject(response.body) }.getOrNull() else null
    }

    /** Двухшаговый вход: камера отдаёт realm и random, пароль уходит как MD5(логин:random:MD5(логин:realm:пароль)) */
    fun login() {
        val unsupported = RpcFailure(Failure.UNSUPPORTED, "Камера не отдаёт список записей ни по CGI, ни по JSON-RPC.")
        val challenge = post(
            "$base/RPC2_Login",
            JSONObject().put("method", "global.login").put("id", 1)
                .put("params", JSONObject().put("userName", user).put("password", "").put("clientType", "Web3.0"))
        ) ?: throw unsupported
        val params = challenge.optJSONObject("params")
        val realm = params?.optString("realm").orEmpty()
        val random = params?.optString("random").orEmpty()
        if (realm.isEmpty() || random.isEmpty()) throw unsupported

        val hash = md5Upper("$user:$random:${md5Upper("$user:$realm:$password")}")
        val login = post(
            "$base/RPC2_Login",
            JSONObject().put("method", "global.login").put("id", 2).put("session", challenge.optString("session"))
                .put(
                    "params",
                    JSONObject().put("userName", user).put("password", hash)
                        .put("clientType", "Web3.0").put("authorityType", "Default")
                )
        )
        if (login?.optBoolean("result") != true) throw RpcFailure(Failure.AUTH, "Камера не приняла логин или пароль")
        session = login.optString("session")
    }

    fun logout() {
        if (session.isEmpty()) return
        dropFinder()
        runCatching { post("$base/RPC2", JSONObject().put("method", "global.logout").put("id", 99).put("params", JSONObject()).put("session", session)) }
        session = ""
    }

    private fun call(method: String, params: JSONObject = JSONObject(), objectId: Long? = null, timeoutMs: Int = 10_000): JSONObject? {
        val body = JSONObject().put("method", method).put("params", params).put("id", 10).put("session", session)
        objectId?.let { body.put("object", it) }
        val response = post("$base/RPC2?session=$session", body, timeoutMs)
        if (response?.opt("result") == false && response.optJSONObject("error")?.optInt("code") == SESSION_EXPIRED_ERROR) {
            throw SessionExpired()
        }
        return response
    }

    /** Подтверждает, что сессия нужна: без этого камера закрывает её через ~100 с простоя */
    fun keepAlive() {
        call("global.keepAlive", JSONObject().put("timeout", 300).put("active", false))
    }

    /** Накопители камеры; пустой список — накопителя нет. Если камера не умеет их перечислять — стандартный /mnt/sd */
    fun storageVolumes(): List<StorageVolume> {
        val fallback = listOf(StorageVolume(FALLBACK_STORAGE_PATH, null, null))
        val id = (call("storage.factory.instance")?.opt("result") as? Number)?.toLong() ?: return fallback
        try {
            val info = call("storage.getDeviceAllInfo", objectId = id)?.optJSONObject("params")?.optJSONArray("info")
                ?: return fallback
            return parseVolumes(info)
        } finally {
            runCatching { call("storage.destroy", objectId = id) }
        }
    }

    /** Файловая система карты (FAT32...) из возможностей хранилища; null — камера не сообщает */
    fun fileSystem(): String? {
        val id = (call("storage.factory.instance")?.opt("result") as? Number)?.toLong() ?: return null
        try {
            return call("storage.getCaps", objectId = id)?.optJSONObject("params")?.optJSONObject("caps")
                ?.optJSONArray("FileSystem")?.optString(0)?.takeIf { it.isNotEmpty() }
        } finally {
            runCatching { call("storage.destroy", objectId = id) }
        }
    }

    /**
     * Форматирование накопителя так же, как это делает веб-интерфейс камеры: запись останавливается, у объекта
     * devStorage (создаётся по имени устройства) вызывается formatPatition (так метод назван в прошивке Dahua)
     * с параметрами part — имя устройства и fs — файловая система его раздела (обе строкой: число или
     * пропущенный параметр роняют камеру и она перезагружается, ничего не форматируя), после чего запись
     * включается снова. Камера отвечает после окончания операции, поэтому ждём ответ долго.
     */
    fun format(device: String) {
        val id = (call("devStorage.factory.instance", JSONObject().put("name", device))?.opt("result") as? Number)?.toLong()
            ?: throw RpcFailure(Failure.UNSUPPORTED, "Камера не поддерживает форматирование карты по сети.")
        try {
            val fileSystem = call("devStorage.getDeviceInfo", objectId = id)?.optJSONObject("params")?.optJSONObject("device")
                ?.optJSONArray("Partitions")?.optJSONObject(0)?.optString("FileSystem").orEmpty()
            // Не у всех камер есть recordManager (SD-MT1H2000HI-A отвечает InterfaceNotFound): запускаем запись
            // обратно только там, где её удалось остановить
            val stopped = call("recordManager.stop")?.optBoolean("result") == true
            try {
                val result = call(
                    "devStorage.formatPatition",
                    JSONObject().put("part", device).put("fs", fileSystem),
                    id,
                    timeoutMs = FORMAT_TIMEOUT_MS
                )
                if (result?.optBoolean("result") != true) {
                    val error = result?.opt("error")
                    throw RpcFailure(Failure.UNSUPPORTED, "Камера отклонила форматирование${error?.let { " ($it)" }.orEmpty()}.")
                }
            } finally {
                if (stopped) runCatching { call("recordManager.start") }
            }
        } finally {
            runCatching { call("devStorage.destroy", objectId = id) }
        }
    }

    private fun finderId(): Long = finder ?: (
        (call("mediaFileFind.factory.create")?.opt("result") as? Number)?.toLong()
            ?: throw RpcFailure(Failure.UNSUPPORTED, "Камера не поддерживает поиск записей.")
        ).also { finder = it }

    private fun dropFinder() {
        val id = finder ?: return
        finder = null
        // destroy закрывает поиск сам: один запрос меньше
        runCatching { call("mediaFileFind.destroy", objectId = id) }
    }

    /**
     * Поиск файлов на накопителях. Если файлов за интервал нет, камера отвечает не пустым списком,
     * а ошибкой NO_FILES_ERROR — это «записей нет», а не сбой.
     */
    fun search(dirs: List<String>, start: LocalDateTime, end: LocalDateTime, limit: Int, newestFirst: Boolean = false): List<DahuaRecording> {
        val id = finderId()
        val condition = JSONObject()
            .put("Channel", 0)
            .put("Dirs", JSONArray(dirs))
            .put("Types", JSONArray().put("dav"))
            .put("StartTime", start.format(SEARCH_TIME))
            .put("EndTime", end.format(SEARCH_TIME))
            .put("Order", if (newestFirst) "Descent" else "Ascent")
        val find = call("mediaFileFind.findFile", JSONObject().put("condition", condition), id)
        if (find?.optBoolean("result") != true) {
            val code = find?.optJSONObject("error")?.optInt("code")
            // После отказа объект поиска на камере непригоден — следующий поиск создаст новый
            dropFinder()
            if (code == NO_FILES_ERROR) return emptyList()
            throw RpcFailure(Failure.UNSUPPORTED, "Камера не смогла открыть архив (код ошибки ${code ?: "нет ответа"}).")
        }

        val recordings = mutableListOf<DahuaRecording>()
        var exhausted = false
        while (recordings.size < limit) {
            val count = minOf(FIND_BATCH, limit - recordings.size)
            val next = call("mediaFileFind.findNextFile", JSONObject().put("count", count), id)
            val infos = next?.optJSONObject("params")?.optJSONArray("infos")
            if (infos == null) {
                exhausted = true
                break
            }
            recordings += parseInfos(infos)
            if (infos.length() < count) {
                exhausted = true
                break
            }
        }
        // Исчерпанный поиск повторно не годится; недочитанный (нужен был один файл) можно использовать снова
        if (exhausted) dropFinder()
        return recordings
    }
}

internal fun parseVolumes(info: JSONArray): List<StorageVolume> {
    val volumes = mutableListOf<StorageVolume>()
    for (i in 0 until info.length()) {
        val device = info.optJSONObject(i) ?: continue
        val details = device.optJSONArray("Detail") ?: continue
        for (j in 0 until details.length()) {
            val detail = details.optJSONObject(j) ?: continue
            val path = detail.optString("Path")
            if (path.isEmpty() || detail.optBoolean("IsError")) continue
            volumes += StorageVolume(
                path = path,
                totalBytes = detail.optLong("TotalBytes", -1).takeIf { it >= 0 },
                usedBytes = detail.optLong("UsedBytes", -1).takeIf { it >= 0 },
                device = device.optString("Name").takeIf { it.isNotEmpty() },
                state = device.optString("State").takeIf { it.isNotEmpty() },
                type = detail.optString("Type").takeIf { it.isNotEmpty() }
            )
        }
    }
    return volumes
}

internal fun parseInfos(infos: JSONArray): List<DahuaRecording> =
    (0 until infos.length()).mapNotNull { index ->
        val info = infos.optJSONObject(index) ?: return@mapNotNull null
        val events = info.optJSONArray("Events")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        DahuaApi.recordingFrom(
            mapOf(
                "StartTime" to info.optString("StartTime"),
                "EndTime" to info.optString("EndTime"),
                "Type" to info.optString("Type"),
                "Length" to info.optString("Length")
            )
        )?.copy(filePath = info.optString("FilePath").takeIf { it.isNotEmpty() }, events = events)
    }

private fun md5Upper(value: String): String =
    java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(it) }

/** Разовые обращения к архиву камеры: вход, запрос, выход */
object DahuaArchive {

    /**
     * Скачивает файл записи с камеры в out (формат камеры — DHAV/.dav, проигрывается плеерами Dahua и VLC).
     * Возвращает число байт. onProgress получает скачанное число байт. Пока корутина жива, чтение идёт;
     * при отмене соединение закрывается.
     */
    suspend fun download(camera: IpCamera, recording: DahuaRecording, out: java.io.OutputStream, onProgress: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val path = recording.filePath ?: throw IOException("Камера не сообщила путь файла")
            // Путь файла — часть адреса: скобки, @ и пробелы в имени экранируются, слэши остаются
            val url = "${DahuaApi.baseUrl(camera)}/cgi-bin/RPC_Loadfile${android.net.Uri.encode(path, "/")}"
            val requestUri = java.net.URL(url).file

            fun open(auth: String?): java.net.HttpURLConnection =
                (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 20_000
                    auth?.let { setRequestProperty("Authorization", it) }
                }

            var connection = open(null)
            try {
                if (connection.responseCode == java.net.HttpURLConnection.HTTP_UNAUTHORIZED) {
                    val challenges = connection.headerFields.entries
                        .filter { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                        .flatMap { it.value }
                    val challenge = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) } ?: challenges.firstOrNull()
                        ?: throw IOException("Камера требует логин и пароль")
                    connection.disconnect()
                    connection = open(NetworkTester.buildAuthHeader(challenge, camera.username, camera.password, "GET", requestUri))
                }
                when (val code = connection.responseCode) {
                    200 -> Unit
                    401 -> throw IOException("Камера не приняла логин или пароль")
                    404 -> throw IOException("Файл не найден на камере — возможно, его уже перезаписали")
                    else -> throw IOException("Камера ответила HTTP $code")
                }
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
                val expected = recording.lengthBytes
                if (expected != null && total < expected) throw IOException("Файл скачан не полностью ($total из $expected байт)")
                total
            } finally {
                connection.disconnect()
            }
        }

    /** Результат удаления файла с карты */
    sealed class DeleteResult {
        object Deleted : DeleteResult()
        data class NotSupported(val message: String) : DeleteResult()
        data class Failed(val message: String) : DeleteResult()
    }

    /**
     * Удаляет один файл записи (документированный вызов Dahua FileManager.cgi?action=removeFile).
     * Часть камер (в том числе DH-SD-H2C) такой вызов не принимает: ответ 400 — отдельный результат,
     * чтобы показать пользователю, что удалить можно только форматированием карты.
     */
    suspend fun delete(camera: IpCamera, recording: DahuaRecording): DeleteResult = withContext(Dispatchers.IO) {
        val path = recording.filePath ?: return@withContext DeleteResult.Failed("Камера не сообщила путь файла")
        val response = CameraHttp.request(
            "${DahuaApi.baseUrl(camera)}/cgi-bin/FileManager.cgi?action=removeFile&fileName=${android.net.Uri.encode(path, "/")}",
            user = camera.username,
            pass = camera.password,
            timeoutMs = 15_000
        )
        when {
            response.code == 0 -> DeleteResult.Failed(response.body)
            response.code == 401 || response.code == 403 -> DeleteResult.Failed("Камера не приняла логин или пароль")
            response.isSuccess && response.body.contains("OK", ignoreCase = true) -> DeleteResult.Deleted
            response.code == 400 || response.code == 404 -> DeleteResult.NotSupported(
                "Эта камера не умеет удалять отдельные записи. Освободить место можно только форматированием карты (вкладка «Архив видео» в настройках камеры)."
            )
            else -> DeleteResult.Failed("Камера не удалила файл (HTTP ${response.code})")
        }
    }


    /** Камера отдаёт сведения о накопителе по JSON-RPC (значит, есть смысл показывать вкладку «Архив видео») */
    suspend fun supportsStorage(camera: IpCamera): Boolean {
        val session = DahuaArchiveSession(camera)
        return try {
            session.storageInfo()
            true
        } catch (e: ArchiveException) {
            false
        } finally {
            session.close()
        }
    }

    suspend fun findDay(camera: IpCamera, day: LocalDate): ArchiveResult {
        val session = DahuaArchiveSession(camera)
        return try {
            ArchiveResult.Found(session.recordings(day))
        } catch (e: ArchiveException) {
            if (e.kind == ArchiveException.Kind.UNSUPPORTED) ArchiveResult.Unsupported(e.message.orEmpty())
            else ArchiveResult.Failed(e.message.orEmpty())
        } finally {
            session.close()
        }
    }
}
