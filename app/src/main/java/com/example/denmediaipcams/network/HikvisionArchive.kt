package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Запись на SD-карте камеры Hikvision (ISAPI). Время [start] и [end] — по часам камеры: ISAPI отдаёт его в UTC,
 * а приложение показывает так же, как для остальных камер, — местным временем камеры (см. [HikvisionArchive.cameraOffset]).
 */
data class HikvisionRecording(
    val day: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val startUtc: Instant,
    val endUtc: Instant,
    val trackId: Int,
    /** Путь и параметры записи на камере для RTSP: /Streaming/tracks/101/?starttime=…&endtime=…&name=…&size=… */
    val playbackPath: String,
    val sizeBytes: Long,
    /** Причина записи по метаданным камеры: timing (по расписанию), motion, alarm… */
    val kind: String?
) {
    val durationMs: Long get() = Duration.between(startUtc, endUtc).toMillis()
}

/** Одна страница ответа на поиск записей */
internal data class HikvisionSearchPage(val items: List<HikvisionRecording>, val matches: Int, val hasMore: Boolean)

/**
 * Архив SD-карты камер Hikvision и совместимых по ISAPI: поиск записей — POST /ISAPI/ContentMgmt/search
 * (CMSearchDescription), воспроизведение — RTSP-адрес из ответа поиска (/Streaming/tracks/101/?starttime=…).
 * Авторизация Digest (ходит [CameraHttp]); камеры слабые, поэтому запросы небольшие и по одному.
 * Камера не умеет отдавать список дней, поэтому дни ищутся прыжками: «первая запись не раньше такого-то времени».
 */
object HikvisionArchive {

    private const val SEARCH_PATH = "/ISAPI/ContentMgmt/search"
    private const val TIME_PATH = "/ISAPI/System/time"
    private const val INFO_PATH = "/ISAPI/System/deviceInfo"
    private const val REQUEST_TIMEOUT_MS = 8_000
    private const val PAGE_SIZE = 100
    private const val MAX_DAY_RECORDINGS = 2_000
    private const val LOOKBACK_DAYS = 60L
    private const val MAX_DAY_JUMPS = 200
    private const val LATEST_MAX_DAYS = 6
    private const val DEFAULT_TRACK = 101
    private const val OFFSET_TTL_MS = 10L * 60 * 1000

    private val SEARCH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val RTSP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private val ITEM = Regex("""<searchMatchItem>(.*?)</searchMatchItem>""", RegexOption.DOT_MATCHES_ALL)
    private val NUM_MATCHES = Regex("""<numOfMatches>\s*(\d+)\s*</numOfMatches>""")
    private val RESPONSE_STATUS = Regex("""<responseStatusStrg>\s*([^<]*?)\s*</responseStatusStrg>""")
    private val LOCAL_TIME_OFFSET = Regex("""<localTime>[^<]*?([+-]\d{2}:\d{2}|Z)\s*</localTime>""")

    private val offsets = ConcurrentHashMap<String, Pair<Long, ZoneOffset>>()

    private fun baseUrl(camera: IpCamera) = "http://${camera.ipAddress}:${camera.effectiveHttpPort}"

    /** Камера отвечает на ISAPI (/ISAPI/System/deviceInfo) — значит, архив доступен; null — камера не ответила, проверку надо повторить позже */
    suspend fun isSupported(camera: IpCamera): Boolean? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext false
        val response = CameraHttp.request(baseUrl(camera) + INFO_PATH, user = camera.username, pass = camera.password, timeoutMs = REQUEST_TIMEOUT_MS)
        if (response.code == 0) null else response.isSuccess && response.body.contains("<DeviceInfo")
    }

    /**
     * Смещение часового пояса камеры (по её /ISAPI/System/time); не ответила — часовой пояс этого устройства.
     * Запоминается на несколько минут: смещение нужно при каждом поиске и перемотке.
     */
    suspend fun cameraOffset(camera: IpCamera): ZoneOffset = withContext(Dispatchers.IO) {
        val key = "${camera.ipAddress}:${camera.effectiveHttpPort}"
        offsets[key]?.let { (savedAt, offset) -> if (System.currentTimeMillis() - savedAt < OFFSET_TTL_MS) return@withContext offset }
        val response = CameraHttp.request(baseUrl(camera) + TIME_PATH, user = camera.username, pass = camera.password, timeoutMs = REQUEST_TIMEOUT_MS)
        val offset = if (response.isSuccess) parseOffset(response.body) else null
        offset?.also { offsets[key] = System.currentTimeMillis() to it }
            ?: ZoneOffset.ofTotalSeconds(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
    }

    /** Дни, за которые на карте есть записи, по возрастанию */
    suspend fun listDays(camera: IpCamera): List<LocalDate> = withContext(Dispatchers.IO) {
        val offset = cameraOffset(camera)
        val days = sortedSetOf<LocalDate>()
        val upper = LocalDate.now(offset).plusDays(2).atStartOfDay().toInstant(offset)
        var from = LocalDate.now(offset).minusDays(LOOKBACK_DAYS).atStartOfDay().toInstant(offset)
        repeat(MAX_DAY_JUMPS) {
            val first = search(camera, from, upper, offset, limit = 1).firstOrNull() ?: return@withContext days.toList()
            if (first.startUtc >= from) {
                days += first.day
                from = first.day.plusDays(1).atStartOfDay().toInstant(offset)
            } else {
                // Запись начата до [from] и захватывает его (перешла через полночь): ищем дальше, после её конца
                from = first.endUtc.plusSeconds(1)
            }
        }
        days.toList()
    }

    /** Записи дня по возрастанию времени начала */
    suspend fun listRecordings(camera: IpCamera, day: LocalDate): List<HikvisionRecording> = withContext(Dispatchers.IO) {
        val offset = cameraOffset(camera)
        search(camera, day.atStartOfDay().toInstant(offset), day.plusDays(1).atStartOfDay().toInstant(offset), offset, MAX_DAY_RECORDINGS)
            .filter { it.day == day }
            .sortedBy { it.startUtc }
    }

    /** Новейшие записи на карте (до [limit]), от новых к старым, для блока «Архив видео» на главном экране */
    suspend fun latest(camera: IpCamera, limit: Int): List<HikvisionRecording> = withContext(Dispatchers.IO) {
        val found = mutableListOf<HikvisionRecording>()
        for (day in listDays(camera).asReversed().take(LATEST_MAX_DAYS)) {
            found += listRecordings(camera, day)
            if (found.size >= limit) break
        }
        found.sortedByDescending { it.startUtc }.take(limit)
    }

    /**
     * Адрес воспроизведения записи с позиции [offsetMs] от её начала. С нуля — адрес из поиска как есть (с именем и размером
     * файла); с середины — время начала сдвигается, а конец остаётся концом записи: так камера начинает показ с этой секунды.
     */
    fun playbackUrl(camera: IpCamera, recording: HikvisionRecording, offsetMs: Long = 0): String {
        val path = if (offsetMs < 1_000) recording.playbackPath else {
            val from = recording.startUtc.plusMillis(offsetMs).coerceAtMost(recording.endUtc.minusSeconds(1))
            "/Streaming/tracks/${recording.trackId}/?starttime=${RTSP_TIME.format(from)}&endtime=${RTSP_TIME.format(recording.endUtc)}"
        }
        val credentials = if (camera.username.isBlank() && camera.password.isBlank()) "" else "${encode(camera.username)}:${encode(camera.password)}@"
        return "rtsp://$credentials${camera.ipAddress}:${camera.effectiveRtspPort}$path"
    }

    // Спецсимволы логина и пароля (@ : # / ? %) экранируются, иначе адрес разбирается неверно
    private fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private suspend fun search(camera: IpCamera, fromUtc: Instant, toUtc: Instant, offset: ZoneOffset, limit: Int): List<HikvisionRecording> {
        val found = mutableListOf<HikvisionRecording>()
        var position = 0
        while (found.size < limit) {
            val page = searchPage(camera, fromUtc, toUtc, offset, position, minOf(PAGE_SIZE, limit - found.size))
            found += page.items
            if (!page.hasMore || page.matches <= 0) break
            position += page.matches
        }
        return found
    }

    private fun searchPage(camera: IpCamera, fromUtc: Instant, toUtc: Instant, offset: ZoneOffset, position: Int, count: Int): HikvisionSearchPage {
        val response = CameraHttp.request(
            baseUrl(camera) + SEARCH_PATH,
            method = "POST",
            body = searchRequest(fromUtc.truncatedSeconds(), toUtc.truncatedSeconds(), position, count),
            contentType = "application/xml",
            user = camera.username,
            pass = camera.password,
            timeoutMs = REQUEST_TIMEOUT_MS
        )
        when {
            response.code == 401 -> throw ArchiveException(ArchiveException.Kind.AUTH, "Камера не приняла логин и пароль")
            response.code == 0 -> throw ArchiveException(ArchiveException.Kind.NETWORK, response.body)
            !response.isSuccess -> throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера не отдаёт записи (код ${response.code})")
        }
        return parseSearch(response.body, offset)
    }

    private fun Instant.truncatedSeconds(): Instant = Instant.ofEpochSecond(epochSecond)

    internal fun searchRequest(fromUtc: Instant, toUtc: Instant, position: Int, count: Int, track: Int = DEFAULT_TRACK): String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            "<CMSearchDescription><searchID>${UUID.randomUUID().toString().uppercase()}</searchID>" +
            "<trackList><trackID>$track</trackID></trackList>" +
            "<timeSpanList><timeSpan><startTime>${SEARCH_TIME.format(fromUtc)}</startTime><endTime>${SEARCH_TIME.format(toUtc)}</endTime></timeSpan></timeSpanList>" +
            "<maxResults>$count</maxResults><searchResultPostion>$position</searchResultPostion>" +
            "<metadataList><metadataDescriptor>//recordType.meta.std-cgi.com</metadataDescriptor></metadataList>" +
            "</CMSearchDescription>"

    /** Разбор ответа поиска: записи (время — по часам [offset]) и признак «есть ещё» */
    internal fun parseSearch(xml: String, offset: ZoneOffset): HikvisionSearchPage {
        val status = RESPONSE_STATUS.find(xml)?.groupValues?.get(1).orEmpty()
        val items = ITEM.findAll(xml).mapNotNull { parseItem(it.groupValues[1], offset) }.toList()
        val matches = NUM_MATCHES.find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: items.size
        return HikvisionSearchPage(items, matches, hasMore = status.equals("MORE", ignoreCase = true))
    }

    private fun parseItem(xml: String, offset: ZoneOffset): HikvisionRecording? {
        fun tag(name: String) = Regex("""<$name>\s*([^<]*?)\s*</$name>""").find(xml)?.groupValues?.get(1)
        val startUtc = runCatching { Instant.parse(tag("startTime")) }.getOrNull() ?: return null
        val endUtc = runCatching { Instant.parse(tag("endTime")) }.getOrNull() ?: return null
        val uri = tag("playbackURI")?.replace("&amp;", "&") ?: return null
        // rtsp://192.168.1.203/Streaming/tracks/101/?starttime=… → /Streaming/tracks/101/?starttime=…
        val path = "/" + uri.substringAfter("://").substringAfter('/', "")
        if (path == "/") return null
        val track = tag("trackID")?.toIntOrNull() ?: Regex("""/tracks/(\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_TRACK
        val size = Regex("""[?&]size=(\d+)""").find(path)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val kind = tag("metadataDescriptor")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val start = startUtc.atOffset(offset).toLocalDateTime()
        return HikvisionRecording(
            day = start.toLocalDate(),
            start = start,
            end = endUtc.atOffset(offset).toLocalDateTime(),
            startUtc = startUtc,
            endUtc = endUtc,
            trackId = track,
            playbackPath = path,
            sizeBytes = size,
            kind = kind
        )
    }

    /** Смещение из `<localTime>2026-10-22T20:42:14+03:00</localTime>`; null — в ответе его нет */
    internal fun parseOffset(xml: String): ZoneOffset? {
        val text = LOCAL_TIME_OFFSET.find(xml)?.groupValues?.get(1) ?: return null
        return runCatching { ZoneOffset.of(text) }.getOrNull()
    }
}
