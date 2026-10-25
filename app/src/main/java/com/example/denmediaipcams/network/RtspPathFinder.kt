package com.example.denmediaipcams.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed class RtspPathSearchResult {
    data class Found(
        val path: String,
        val port: Int,
        val source: String,
        val tracks: String,
        // Логин/пароль, которые камера сама выдала открытым текстом в пути RTSP-потока (см. extractLeakedCredentials)
        val leakedCredentials: Pair<String, String>? = null
    ) : RtspPathSearchResult()
    data class NotFound(val message: String) : RtspPathSearchResult()
}

/**
 * Автоподбор RTSP пути камеры:
 * 1) через ONVIF (GetStreamUri) — камера сама сообщает точный адрес потока;
 * 2) перебором известных путей популярных производителей (проверка запросом DESCRIBE).
 *
 * Перебор прерывается при первом ответе «неверный логин/пароль», чтобы камера
 * не заблокировала учётную запись после серии неудачных попыток входа.
 */
object RtspPathFinder {

    private const val TAG = "RtspPathFinder"
    const val PROBE_TIMEOUT_MS = 3000

    const val NO_CREDENTIALS_MESSAGE =
        "Камера отвечает отказом без авторизации (400/403/407) — вероятно, нужен пароль. " +
            "Укажите пароль (логин можно оставить пустым) и повторите поиск."

    /** Камеры без пароля часто отвечают 400/403/407 вместо 401 — перебирать пути дальше бессмысленно */
    fun noCredentialsRejected(code: Int, user: String, pass: String): Boolean =
        user.isBlank() && pass.isBlank() && (code == 400 || code == 403 || code == 407)

    const val WRONG_CREDENTIALS_MESSAGE =
        "Камера ответила 400 на запрос с логином и паролем — вероятно, они неверны (так отвечают некоторые камеры вместо 401). " +
            "Проверьте логин и пароль и повторите поиск."

    /**
     * Камеры вроде HiSilicon «HIipCamera» на неверный пароль отвечают 400, а не 401, поэтому перебор путей шёл бы дальше
     * и дошёл до пути с «?», на который их RTSP-сервер падает. Проверяется только первый запрос: дальше 400 может значить «нет такого пути».
     */
    internal fun wrongCredentialsRejected(response: NetworkTester.RtspResponse, user: String, pass: String): Boolean =
        response.authRetried && response.code == 400 && (user.isNotBlank() || pass.isNotBlank())

    /** RTSP-сервер этой камеры падает на запросе с «?» в пути — такие пути ей не отправляются */
    internal fun crashesOnQueryPaths(response: NetworkTester.RtspResponse?): Boolean =
        response?.challenge?.contains("HIipCamera", ignoreCase = true) == true

    /** Пауза между проверками путей: слабые камеры перезагружаются от частых запросов */
    const val PROBE_DELAY_MS = 100L

    /** Админ-пароль, который некоторые дешёвые камеры (HiSilicon/XMEye ONVIF) по ошибке вписывают открытым
     *  текстом в путь RTSP-потока, который сами же возвращают по ONVIF (GetStreamUri), например:
     *  "/user=admin_password=AbCd1234_channel=0_stream=0&onvif=0.sdp?real_stream". Если такой паттерн есть —
     *  это настоящие учётные данные камеры, отличные от того, что мог ввести пользователь. */
    internal fun extractLeakedCredentials(uri: String): Pair<String, String>? {
        val match = Regex("""user=([^_&?/]+)[_&]password=([^_&?/]*)""").find(uri) ?: return null
        val user = match.groupValues[1]
        if (user.isBlank()) return null
        return user to match.groupValues[2]
    }

    private val KNOWN_PATHS = listOf(
        "/Streaming/Channels/101",               // Hikvision, HiWatch (основной поток)
        "/h264/ch1/main/av_stream",              // Hikvision (старые прошивки)
        "/Streaming/Channels/1",                 // Hikvision (альтернативный)
        "/stream1",                              // TP-Link Tapo / VIGI
        "/h264Preview_01_main",                  // Reolink
        "/live/ch0",
        "/live/ch00_0",
        "/live/main",
        "/live",
        "/live.sdp",
        "/videoMain",                            // Foscam
        "/axis-media/media.amp",                 // Axis
        "/onvif1",                               // Yoosee и аналоги
        "/av0_0",
        "/ch0_0.h264",
        "/11",
        "/1",
        "/0",
        "/h264",
        "/mpeg4",
        "/media/video1",
        "/av_stream",
        // Пути с параметрами — в конце: RTSP-сервер части камер (HiSilicon «HIipCamera») падает на запросе с «?»
        // и камера перезагружается, поэтому такой путь пробуется, только когда остальные не подошли
        "/cam/realmonitor?channel=1&subtype=0",  // Dahua, IMOU, многие OEM
        "/"
    )

    /** Блокирующая проверка: камера запускает поток по этому пути (SETUP первой дорожки отвечает 200) */
    private suspend fun isPlayable(host: String, port: Int, path: String, sdp: String, user: String, pass: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                NetworkTester.setupFirstTrack(host, port, "rtsp://$host:$port$path", sdp, user, pass, PROBE_TIMEOUT_MS).code == 200
            } catch (e: Exception) {
                Log.w(TAG, "SETUP $path failed: ${e.message}")
                false
            }
        }

    suspend fun find(
        hostInput: String,
        rtspPort: Int,
        onvifPort: Int,
        user: String,
        pass: String,
        preferredPath: String?,
        onProgress: (String) -> Unit
    ): RtspPathSearchResult {
        if (hostInput.startsWith("rtsp://") || hostInput.startsWith("http://") || hostInput.startsWith("https://")) {
            return RtspPathSearchResult.NotFound("Для автоподбора укажите в поле адреса только IP или хост камеры, без rtsp://")
        }
        val host = hostInput.substringBefore("/").substringBefore(":").trim()
        if (host.isBlank()) return RtspPathSearchResult.NotFound("Не указан IP-адрес камеры")

        // Этап 1: ONVIF
        onProgress("ONVIF: запрос адреса потока у камеры...")
        val onvifResult = withContext(Dispatchers.IO) {
            try {
                findViaOnvif(host, onvifPort, rtspPort, user, pass)
            } catch (e: Exception) {
                Log.w(TAG, "ONVIF search failed: ${e.message}")
                null
            }
        }
        if (onvifResult != null) return onvifResult

        // Этап 2: перебор известных путей
        val candidates = (listOfNotNull(preferredPath?.takeIf { it.isNotBlank() }) + KNOWN_PATHS).distinct()
        var skipQueryPaths = false
        candidates.forEachIndexed { index, path ->
            if (skipQueryPaths && '?' in path) return@forEachIndexed
            if (index > 0) delay(PROBE_DELAY_MS)
            onProgress("Проверка ${index + 1}/${candidates.size}: $path")
            val response = withContext(Dispatchers.IO) {
                try {
                    NetworkTester.describeWithAuth(host, rtspPort, "rtsp://$host:$rtspPort$path", user, pass, PROBE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "DESCRIBE $path failed: ${e.message}")
                    null
                }
            }

            if (crashesOnQueryPaths(response)) skipQueryPaths = true
            when {
                // Сеть недоступна уже на первом пути — дальше перебирать бессмысленно
                response == null && index == 0 -> return RtspPathSearchResult.NotFound(
                    "Камера не отвечает по RTSP на $host:$rtspPort. Проверьте RTSP порт."
                )
                response == null -> {}
                index == 0 && wrongCredentialsRejected(response, user, pass) -> return RtspPathSearchResult.NotFound(WRONG_CREDENTIALS_MESSAGE)
                // Часть камер отвечает на DESCRIBE при любом пути, поэтому путь засчитывается только если поток запускается (SETUP)
                response.code == 200 && isPlayable(host, rtspPort, path, response.body, user, pass) -> return RtspPathSearchResult.Found(
                    path = path,
                    port = rtspPort,
                    source = "перебор известных путей",
                    tracks = NetworkTester.describeSdp(response.body)
                )
                noCredentialsRejected(response.code, user, pass) -> return RtspPathSearchResult.NotFound(NO_CREDENTIALS_MESSAGE)
                response.code == 401 -> return RtspPathSearchResult.NotFound(
                    if (user.isBlank() && pass.isBlank()) "Камера требует авторизацию RTSP — укажите пароль (и логин, если есть)."
                    else "Камера отклонила логин/пароль (401). Перебор остановлен, чтобы камера не заблокировала учётную запись."
                )
            }
        }

        return RtspPathSearchResult.NotFound(
            "Не удалось подобрать путь: ни ONVIF, ни ${candidates.size} известных путей не подошли. " +
                "Уточните RTSP путь в документации камеры или проверьте его в VLC."
        )
    }

    // ---------------- ONVIF ----------------

    private fun findViaOnvif(host: String, onvifPort: Int, rtspPort: Int, user: String, pass: String): RtspPathSearchResult? {
        val endpoints = OnvifClient.discover(host, onvifPort, user, pass) ?: return null
        val tokens = OnvifClient.getProfiles(endpoints, user, pass).map { it.token }

        for (token in tokens) {
            val streamUri = OnvifClient.getStreamUri(endpoints, token, user, pass) ?: continue

            val parsed = android.net.Uri.parse(streamUri)
            val path = (parsed.encodedPath ?: "/") + (parsed.encodedQuery?.let { "?$it" } ?: "")
            val port = if (parsed.port > 0) parsed.port else rtspPort
            val leaked = extractLeakedCredentials(streamUri)

            // Проверяем, что поток действительно отдаётся с нашими логином/паролем
            val check = try {
                NetworkTester.describeWithAuth(host, port, "rtsp://$host:$port$path", user, pass, PROBE_TIMEOUT_MS)
            } catch (_: Exception) {
                null
            }
            when (check?.code) {
                200 -> return RtspPathSearchResult.Found(
                    path, port, "ONVIF (профиль $token)", NetworkTester.describeSdp(check?.body.orEmpty()), leaked
                )
                401 -> return RtspPathSearchResult.NotFound(
                    "ONVIF сообщил путь $path, но RTSP отклонил логин/пароль (401). Проверьте учётные данные."
                )
            }
            Log.w(TAG, "ONVIF stream $streamUri not playable (code ${check?.code})")
        }
        return null
    }

    // ---------------- Все потоки камеры (разное качество) ----------------

    /**
     * Семейства путей одного производителя: основной, дополнительный и (если есть) третий поток.
     * Перебор семейства начинается только если отвечает его первый (основной) путь.
     */
    private val STREAM_FAMILIES = listOf(
        listOf("/Streaming/Channels/101", "/Streaming/Channels/102", "/Streaming/Channels/103"),       // Hikvision
        listOf("/h264/ch1/main/av_stream", "/h264/ch1/sub/av_stream"),                                   // Hikvision (старые)
        listOf("/11", "/12", "/13"),                                                                     // XMEye / HiSilicon
        listOf("/stream1", "/stream2"),                                                                  // TP-Link Tapo / VIGI
        listOf("/h264Preview_01_main", "/h264Preview_01_sub"),                                           // Reolink
        listOf("/live/ch0", "/live/ch1"),
        listOf("/live/ch00_0", "/live/ch00_1"),
        listOf("/live/main", "/live/sub"),
        listOf("/av0_0", "/av0_1"),
        listOf("/ch0_0.h264", "/ch0_1.h264"),
        listOf("/videoMain", "/videoSub"),                                                               // Foscam
        listOf("/onvif1", "/onvif2"),                                                                    // Yoosee
        listOf("/0", "/1"),
        listOf("/av_stream"),
        // Dahua — последним: пути с «?» роняют RTSP-сервер части камер (см. KNOWN_PATHS)
        listOf("/cam/realmonitor?channel=1&subtype=0", "/cam/realmonitor?channel=1&subtype=1",
            "/cam/realmonitor?channel=1&subtype=2"),
    )

    private val ORDINAL_NAMES = listOf("Основной", "Дополнительный", "Третий")

    /**
     * Ищет все потоки камеры: сначала через ONVIF (каждый медиапрофиль — свой поток с разрешением),
     * затем перебором известных пар «основной / дополнительный» путей.
     * Перебор останавливается при ответе «неверный логин/пароль», чтобы не заблокировать учётную запись.
     */
    suspend fun findAllStreams(
        hostInput: String,
        rtspPort: Int,
        onvifPort: Int,
        user: String,
        pass: String,
        currentPath: String?,
        onProgress: (String) -> Unit
    ): StreamSearchResult {
        if (hostInput.contains("://")) {
            return StreamSearchResult.NotFound("Для поиска потоков укажите в поле адреса только IP или хост камеры, без rtsp://")
        }
        val host = hostInput.substringBefore("/").substringBefore(":").trim()
        if (host.isBlank()) return StreamSearchResult.NotFound("Не указан IP-адрес камеры")

        // Этап 1: ONVIF — камера сама перечисляет свои профили
        onProgress("ONVIF: запрос списка потоков у камеры...")
        val onvif = withContext(Dispatchers.IO) {
            try {
                findAllViaOnvif(host, onvifPort, rtspPort, user, pass)
            } catch (e: Exception) {
                Log.w(TAG, "ONVIF stream list failed: ${e.message}")
                null
            }
        }
        if (onvif is StreamSearchResult.NotFound && onvif.authRejected) return onvif
        if (onvif is StreamSearchResult.Found) return onvif

        // Этап 2: известные пары путей; семейство текущего пути проверяем первым
        val families = STREAM_FAMILIES.sortedByDescending { family -> currentPath != null && currentPath in family }
        var skipQueryPaths = false
        families.forEachIndexed { familyIndex, family ->
            val found = mutableListOf<DiscoveredStream>()
            for ((index, path) in family.withIndex()) {
                if (skipQueryPaths && '?' in path) break
                if (familyIndex > 0 || index > 0) delay(PROBE_DELAY_MS)
                onProgress("Проверка ${familyIndex + 1}/${families.size}: $path")
                val response = withContext(Dispatchers.IO) {
                    try {
                        NetworkTester.describeWithAuth(host, rtspPort, "rtsp://$host:$rtspPort$path", user, pass, PROBE_TIMEOUT_MS)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (crashesOnQueryPaths(response)) skipQueryPaths = true
                when {
                    response != null && familyIndex == 0 && index == 0 && wrongCredentialsRejected(response, user, pass) ->
                        return StreamSearchResult.NotFound(WRONG_CREDENTIALS_MESSAGE, authRejected = true)
                    response == null && familyIndex == 0 && index == 0 -> return StreamSearchResult.NotFound(
                        "Камера не отвечает по RTSP на $host:$rtspPort. Проверьте RTSP порт."
                    )
                    response != null && noCredentialsRejected(response.code, user, pass) ->
                        return StreamSearchResult.NotFound(NO_CREDENTIALS_MESSAGE, authRejected = true)
                    response?.code == 401 -> return StreamSearchResult.NotFound(
                        if (user.isBlank() && pass.isBlank()) "Камера требует авторизацию RTSP — укажите пароль (и логин, если есть)."
                        else "Камера отклонила логин/пароль (401). Поиск остановлен, чтобы камера не заблокировала учётную запись.",
                        authRejected = true
                    )
                    // Часть камер отвечает на DESCRIBE при любом пути, поэтому путь засчитывается только если поток запускается (SETUP)
                    response?.code == 200 && isPlayable(host, rtspPort, path, response.body, user, pass) -> {
                        val sdp = response.body
                        val size = sdpResolution(sdp)
                        found += DiscoveredStream(
                            name = streamName(index, size?.first ?: 0, size?.second ?: 0),
                            path = path,
                            tracks = NetworkTester.describeSdp(sdp)
                        )
                    }
                }
                // Основной путь семейства не отвечает — это не та камера, остальные не проверяем
                if (index == 0 && found.isEmpty()) break
            }
            if (found.isNotEmpty()) return StreamSearchResult.Found(found, source = "перебор известных путей")
        }

        return StreamSearchResult.NotFound(
            "Не удалось найти потоки автоматически: камера не сообщила их по ONVIF, известные пути не подошли. " +
                "Добавьте потоки вручную (пути — в документации камеры)."
        )
    }

    private fun findAllViaOnvif(host: String, onvifPort: Int, rtspPort: Int, user: String, pass: String): StreamSearchResult? {
        val endpoints = OnvifClient.discover(host, onvifPort, user, pass) ?: return null
        val profiles = OnvifClient.getProfiles(endpoints, user, pass)
        if (profiles.isEmpty()) return null

        data class Candidate(
            val path: String,
            val width: Int,
            val height: Int,
            val profileName: String?,
            val tracks: String,
            val leaked: Pair<String, String>?
        )

        val candidates = mutableListOf<Candidate>()
        for (profile in profiles) {
            val streamUri = OnvifClient.getStreamUri(endpoints, profile.token, user, pass) ?: continue
            val parsed = android.net.Uri.parse(streamUri)
            val path = (parsed.encodedPath ?: "/") + (parsed.encodedQuery?.let { "?$it" } ?: "")
            val port = if (parsed.port > 0) parsed.port else rtspPort
            // Потоки на другом RTSP-порту не поддерживаются (порт задаётся на всю камеру)
            if (port != rtspPort || candidates.any { it.path == path }) continue

            val check = try {
                NetworkTester.describeWithAuth(host, port, "rtsp://$host:$port$path", user, pass, PROBE_TIMEOUT_MS)
            } catch (_: Exception) {
                null
            }
            when (check?.code) {
                200 -> candidates += Candidate(
                    path, profile.width, profile.height, profile.name,
                    NetworkTester.describeSdp(check?.body.orEmpty()), extractLeakedCredentials(streamUri)
                )
                401 -> return StreamSearchResult.NotFound(
                    "ONVIF сообщил потоки, но RTSP отклонил логин/пароль (401). Проверьте учётные данные.",
                    authRejected = true
                )
                else -> Log.w(TAG, "ONVIF stream $streamUri not playable (code ${check?.code})")
            }
        }
        if (candidates.isEmpty()) return null

        // От лучшего качества к худшему: первый — основной
        val sorted = candidates.sortedByDescending { it.width.toLong() * it.height }
        return StreamSearchResult.Found(
            sorted.mapIndexed { index, c ->
                DiscoveredStream(name = streamName(index, c.width, c.height), path = c.path, tracks = c.tracks)
            },
            source = "ONVIF",
            leakedCredentials = sorted.firstNotNullOfOrNull { it.leaked }
        )
    }

    /** «Основной · 1920×1080», «Дополнительный · 640×360», «Поток 4» */
    fun streamName(index: Int, width: Int, height: Int): String {
        val base = ORDINAL_NAMES.getOrElse(index) { "Поток ${index + 1}" }
        return if (width > 0 && height > 0) "$base · $width×$height" else base
    }

    /** Разрешение из SDP, если камера его указывает (a=framesize / a=x-dimensions / a=cliprect) */
    fun sdpResolution(sdp: String): Pair<Int, Int>? {
        Regex("""a=framesize:\d+\s+(\d+)-(\d+)""").find(sdp)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("""a=x-dimensions:\s*(\d+),\s*(\d+)""").find(sdp)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("""a=cliprect:\s*0,0,(\d+),(\d+)""").find(sdp)?.let {
            return it.groupValues[2].toInt() to it.groupValues[1].toInt()
        }
        return null
    }
}

/** Поток камеры, найденный автоматически */
data class DiscoveredStream(val name: String, val path: String, val tracks: String)

sealed class StreamSearchResult {
    data class Found(
        val streams: List<DiscoveredStream>,
        val source: String,
        // Логин/пароль, которые камера сама выдала открытым текстом в пути RTSP-потока (см. extractLeakedCredentials)
        val leakedCredentials: Pair<String, String>? = null
    ) : StreamSearchResult()
    data class NotFound(val message: String, val authRejected: Boolean = false) : StreamSearchResult()
}
