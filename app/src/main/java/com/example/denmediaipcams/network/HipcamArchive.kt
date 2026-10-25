package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Запись на SD-карте камеры Hipcam (HiSilicon): файл вида /sd/20261010/record000/P261010_020112_021118.265.
 * Время в имени — время камеры; один файл — около 10 минут.
 */
data class HipcamRecording(
    val day: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Путь файла на камере, например /sd/20261010/record000/P261010_020112_021118.265 */
    val path: String,
    /** Размер из листинга камеры (округлён до десятых долей мегабайта) */
    val sizeBytes: Long,
    /** Первая буква имени файла: P — запись по расписанию */
    val kind: Char
) {
    val durationMs: Long get() = java.time.Duration.between(start, end).toMillis()
}

/** Разобранная строка листинга каталога камеры */
internal data class HipcamEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

/**
 * Архив SD-карты камер Hipcam. Камера отдаёт карту обычным HTTP-каталогом /sd/ (порт веб-интерфейса,
 * авторизация Basic): папки по дням, в них record000 с файлами .265 и поддержкой Range.
 * ONVIF этих камер архива не имеет (нет Profile G), поэтому ходим в каталог напрямую.
 */
object HipcamArchive {

    private const val ROOT = "/sd/"
    private const val LIST_TIMEOUT_MS = 8_000
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DAY_DIR = Regex("""^(\d{8})/$""")
    private val FILE_NAME = Regex("""^([A-Za-z])(\d{6})_(\d{6})_(\d{6})\.265$""")
    private val ROW = Regex(
        """<tr><td><a href="([^"]+)">([^<]*)</a></td><td>&nbsp;[^<]*</td><td>&nbsp;&nbsp;([^<]*)</td></tr>"""
    )

    private fun baseUrl(camera: IpCamera) = "http://${camera.ipAddress}:${camera.effectiveHipcamPort}"

    /** Адрес файла записи без логина и пароля (они передаются заголовком) */
    fun fileUrl(camera: IpCamera, recording: HipcamRecording): String = baseUrl(camera) + recording.path

    /** Заголовок Authorization для проигрывателя: веб-сервер камеры принимает Basic */
    fun authorization(camera: IpCamera): String? {
        if (camera.username.isBlank() && camera.password.isBlank()) return null
        val token = java.util.Base64.getEncoder()
            .encodeToString("${camera.username}:${camera.password}".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    /** Камера отдаёт каталог /sd/ — значит, архив доступен; null — камера не ответила, проверку надо повторить позже */
    suspend fun isSupported(camera: IpCamera): Boolean? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext false
        val response = CameraHttp.request(baseUrl(camera) + ROOT, user = camera.username, pass = camera.password, timeoutMs = LIST_TIMEOUT_MS)
        if (response.code == 0) null else response.isSuccess && response.body.contains("Index of /sd/")
    }

    /** Дни, за которые на карте есть папки, по возрастанию */
    suspend fun listDays(camera: IpCamera): List<LocalDate> = withContext(Dispatchers.IO) {
        parseDays(fetch(camera, ROOT)).sorted()
    }

    /** Записи дня из всех папок record*, по возрастанию времени */
    suspend fun listRecordings(camera: IpCamera, day: LocalDate): List<HipcamRecording> = withContext(Dispatchers.IO) {
        val dayPath = "$ROOT${day.format(DAY_FORMAT)}/"
        val folders = parseEntries(fetch(camera, dayPath)).filter { it.isDirectory && it.name.startsWith("record") }
        folders.flatMap { folder ->
            val folderPath = "$dayPath${folder.name}"
            parseRecordings(parseEntries(fetch(camera, folderPath)), day, folderPath)
        }.sortedBy { it.start }
    }

    /** Первые [length] байт файла записи (Range); меньше, если файл короче. null — камера не отдала файл */
    suspend fun readStart(camera: IpCamera, recording: HipcamRecording, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = java.net.URL(fileUrl(camera, recording)).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = LIST_TIMEOUT_MS
            connection.readTimeout = LIST_TIMEOUT_MS
            connection.setRequestProperty("Range", "bytes=0-${length - 1}")
            authorization(camera)?.let { connection.setRequestProperty("Authorization", it) }
            if (connection.responseCode !in 200..299) return@withContext null
            connection.inputStream.use { stream ->
                val buffer = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val read = stream.read(buffer, filled, length - filled)
                    if (read < 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        } catch (e: java.io.IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetch(camera: IpCamera, path: String): String {
        val response = CameraHttp.request(baseUrl(camera) + path, user = camera.username, pass = camera.password, timeoutMs = LIST_TIMEOUT_MS)
        if (response.code == 401) throw ArchiveException(ArchiveException.Kind.AUTH, "Камера не приняла логин и пароль")
        if (response.code == 404) return ""
        if (response.code == 0) throw ArchiveException(ArchiveException.Kind.NETWORK, response.body)
        if (!response.isSuccess) throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера ответила кодом ${response.code}")
        return response.body
    }

    internal fun parseEntries(html: String): List<HipcamEntry> =
        ROW.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1]
            if (href.endsWith("/..")) return@mapNotNull null
            val name = match.groupValues[2]
            val isDirectory = name.endsWith("/")
            HipcamEntry(name, isDirectory, if (isDirectory) 0L else parseSize(match.groupValues[3]))
        }.toList()

    internal fun parseDays(html: String): List<LocalDate> =
        parseEntries(html).filter { it.isDirectory }.mapNotNull { entry ->
            val digits = DAY_DIR.matchEntire(entry.name)?.groupValues?.get(1) ?: return@mapNotNull null
            runCatching { LocalDate.parse(digits, DAY_FORMAT) }.getOrNull()
        }

    internal fun parseRecordings(entries: List<HipcamEntry>, day: LocalDate, folderPath: String): List<HipcamRecording> =
        entries.filter { !it.isDirectory }.mapNotNull { entry ->
            val match = FILE_NAME.matchEntire(entry.name) ?: return@mapNotNull null
            val (kind, _, from, to) = match.destructured
            val timeFormat = DateTimeFormatter.ofPattern("HHmmss")
            val startTime = runCatching { LocalTime.parse(from, timeFormat) }.getOrNull() ?: return@mapNotNull null
            val endTime = runCatching { LocalTime.parse(to, timeFormat) }.getOrNull() ?: return@mapNotNull null
            val start = day.atTime(startTime)
            // Файл, начатый до полуночи, заканчивается уже на следующих сутках
            val end = if (endTime.isBefore(startTime)) day.plusDays(1).atTime(endTime) else day.atTime(endTime)
            HipcamRecording(day, start, end, "$folderPath${entry.name}", entry.sizeBytes, kind[0])
        }

    /** «77.0M», «13.1k», «512» из листинга камеры в байты */
    internal fun parseSize(text: String): Long {
        val value = text.trim()
        val number = value.dropLastWhile { !it.isDigit() }.toDoubleOrNull() ?: return 0L
        return when (value.lastOrNull()?.lowercaseChar()) {
            'k' -> (number * 1024).toLong()
            'm' -> (number * 1024 * 1024).toLong()
            'g' -> (number * 1024 * 1024 * 1024).toLong()
            else -> number.toLong()
        }
    }
}
