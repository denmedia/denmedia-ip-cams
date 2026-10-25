package com.example.denmediaipcams.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Канал видеорегистратора (NVR) с его потоками: основной, дополнительный и т.д. */
data class NvrChannel(val number: Int, val streams: List<DiscoveredStream>)

sealed class NvrSearchResult {
    data class Found(val channels: List<NvrChannel>, val source: String) : NvrSearchResult()
    data class NotFound(val message: String) : NvrSearchResult()
}

/**
 * Поиск каналов видеорегистратора (NVR): сначала через ONVIF (каждый профиль — поток канала),
 * затем перебором типовых путей Hikvision (/Streaming/Channels/N01) и Dahua (channel=N&subtype=M).
 * Перебор останавливается при отказе авторизации и после нескольких подряд отсутствующих каналов.
 */
object NvrChannelFinder {

    private const val TAG = "NvrChannelFinder"
    private const val MAX_CHANNELS = 32
    private const val MAX_STREAMS_PER_CHANNEL = 2
    private const val MISSES_TO_STOP = 2
    private const val BOGUS_CHANNEL = 99

    private data class Family(val name: String, val path: (channel: Int, stream: Int) -> String)

    private val FAMILIES = listOf(
        Family("Hikvision") { channel, stream -> "/Streaming/Channels/${channel * 100 + 1 + stream}" },
        Family("Dahua") { channel, stream -> "/cam/realmonitor?channel=$channel&subtype=$stream" },
    )

    suspend fun find(
        hostInput: String,
        rtspPort: Int,
        onvifPort: Int,
        user: String,
        pass: String,
        onProgress: (String) -> Unit
    ): NvrSearchResult {
        if (hostInput.contains("://")) {
            return NvrSearchResult.NotFound("Для поиска каналов укажите в поле адреса только IP или хост регистратора, без rtsp://")
        }
        val host = hostInput.substringBefore("/").substringBefore(":").trim()
        if (host.isBlank()) return NvrSearchResult.NotFound("Не указан IP-адрес регистратора")

        onProgress("ONVIF: запрос каналов у регистратора...")
        val viaOnvif = withContext(Dispatchers.IO) {
            try {
                findViaOnvif(host, onvifPort, rtspPort, user, pass, onProgress)
            } catch (e: Exception) {
                Log.w(TAG, "ONVIF channel search failed: ${e.message}")
                null
            }
        }
        if (viaOnvif != null) return viaOnvif

        return findByPaths(host, rtspPort, user, pass, onProgress)
    }

    // ---------------- Перебор путей ----------------

    private suspend fun findByPaths(
        host: String,
        rtspPort: Int,
        user: String,
        pass: String,
        onProgress: (String) -> Unit
    ): NvrSearchResult {
        // Семейство определяем по первому каналу; дальше перебираем только его
        for ((familyIndex, family) in FAMILIES.withIndex()) {
            val first = probeChannel(host, rtspPort, user, pass, family, 1, onProgress, pause = familyIndex > 0)
            when (first) {
                is ChannelProbe.Failure -> return NvrSearchResult.NotFound(first.message)
                is ChannelProbe.Missing -> {
                    if (first.unreachable) {
                        return NvrSearchResult.NotFound("Регистратор не отвечает по RTSP на $host:$rtspPort. Проверьте RTSP порт.")
                    }
                    continue
                }
                is ChannelProbe.Present -> {
                    // Заведомо несуществующий канал: если и он «отвечает», устройство игнорирует номер канала —
                    // это одиночная камера, а не регистратор
                    val bogus = probeChannel(host, rtspPort, user, pass, family, BOGUS_CHANNEL, onProgress, pause = true)
                    if (bogus is ChannelProbe.Present) {
                        return NvrSearchResult.NotFound(
                            "Устройство $host отдаёт поток при любом номере канала — это одиночная камера, а не регистратор. " +
                                "Добавьте её как обычную камеру (кнопка «Найти потоки автоматически»)."
                        )
                    }
                    val channels = mutableListOf(first.channel)
                    var misses = 0
                    var number = 2
                    while (number <= MAX_CHANNELS && misses < MISSES_TO_STOP) {
                        when (val probe = probeChannel(host, rtspPort, user, pass, family, number, onProgress, pause = true)) {
                            is ChannelProbe.Failure -> return NvrSearchResult.NotFound(probe.message)
                            is ChannelProbe.Missing -> misses++
                            is ChannelProbe.Present -> {
                                misses = 0
                                channels += probe.channel
                            }
                        }
                        number++
                    }
                    return NvrSearchResult.Found(channels, source = "перебор путей (${family.name})")
                }
            }
        }
        return NvrSearchResult.NotFound(
            "Каналы не найдены: регистратор не сообщил их по ONVIF, типовые пути Hikvision и Dahua не подошли. " +
                "Добавьте камеры вручную (пути — в документации регистратора)."
        )
    }

    private sealed class ChannelProbe {
        data class Present(val channel: NvrChannel) : ChannelProbe()
        data class Missing(val unreachable: Boolean = false) : ChannelProbe()
        data class Failure(val message: String) : ChannelProbe()
    }

    private suspend fun probeChannel(
        host: String,
        rtspPort: Int,
        user: String,
        pass: String,
        family: Family,
        number: Int,
        onProgress: (String) -> Unit,
        pause: Boolean
    ): ChannelProbe {
        val streams = mutableListOf<DiscoveredStream>()
        for (stream in 0 until MAX_STREAMS_PER_CHANNEL) {
            val path = family.path(number, stream)
            if (pause || stream > 0) delay(RtspPathFinder.PROBE_DELAY_MS)
            onProgress("Канал $number: $path")
            val response = withContext(Dispatchers.IO) {
                try {
                    NetworkTester.describeWithAuth(host, rtspPort, "rtsp://$host:$rtspPort$path", user, pass, RtspPathFinder.PROBE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "DESCRIBE $path failed: ${e.message}")
                    null
                }
            }
            when {
                response == null -> return ChannelProbe.Missing(unreachable = number == 1 && stream == 0)
                RtspPathFinder.noCredentialsRejected(response.code, user, pass) ->
                    return ChannelProbe.Failure(RtspPathFinder.NO_CREDENTIALS_MESSAGE)
                response.code == 401 -> return ChannelProbe.Failure(
                    if (user.isBlank() && pass.isBlank()) "Регистратор требует авторизацию RTSP — укажите пароль (и логин, если есть)."
                    else "Регистратор отклонил логин/пароль (401). Поиск остановлен, чтобы не заблокировать учётную запись."
                )
                response.code == 200 -> {
                    val size = RtspPathFinder.sdpResolution(response.body)
                    streams += DiscoveredStream(
                        name = RtspPathFinder.streamName(stream, size?.first ?: 0, size?.second ?: 0),
                        path = path,
                        tracks = NetworkTester.describeSdp(response.body)
                    )
                }
                // Основной поток канала не отдаётся — канала нет, дополнительный не проверяем
                stream == 0 -> return ChannelProbe.Missing()
            }
        }
        return if (streams.isEmpty()) ChannelProbe.Missing() else ChannelProbe.Present(NvrChannel(number, streams))
    }

    // ---------------- ONVIF ----------------

    private suspend fun findViaOnvif(
        host: String,
        onvifPort: Int,
        rtspPort: Int,
        user: String,
        pass: String,
        onProgress: (String) -> Unit
    ): NvrSearchResult? {
        val endpoints = OnvifClient.discover(host, onvifPort, user, pass) ?: return null
        val profiles = OnvifClient.getProfiles(endpoints, user, pass)
        if (profiles.isEmpty()) return null

        // Путь потока каждого профиля, сгруппированный по каналу (если номер канала виден в пути)
        val byChannel = linkedMapOf<Int, MutableList<DiscoveredStream>>()
        var fallbackChannel = 0
        for ((index, profile) in profiles.withIndex()) {
            val streamUri = OnvifClient.getStreamUri(endpoints, profile.token, user, pass) ?: continue
            val parsed = android.net.Uri.parse(streamUri)
            val path = (parsed.encodedPath ?: "/") + (parsed.encodedQuery?.let { "?$it" } ?: "")
            if ((parsed.port > 0 && parsed.port != rtspPort) || byChannel.values.any { list -> list.any { it.path == path } }) continue

            if (index > 0) delay(RtspPathFinder.PROBE_DELAY_MS)
            onProgress("Проверка профиля ${index + 1}/${profiles.size}: $path")
            val check = try {
                NetworkTester.describeWithAuth(host, rtspPort, "rtsp://$host:$rtspPort$path", user, pass, RtspPathFinder.PROBE_TIMEOUT_MS)
            } catch (_: Exception) {
                null
            }
            when (check?.code) {
                200 -> {
                    val sdp = check?.body.orEmpty()
                    val size = RtspPathFinder.sdpResolution(sdp)
                    val channel = channelOf(path) ?: (--fallbackChannel)
                    val list = byChannel.getOrPut(channel) { mutableListOf() }
                    list += DiscoveredStream(
                        name = RtspPathFinder.streamName(list.size, size?.first ?: profile.width, size?.second ?: profile.height),
                        path = path,
                        tracks = NetworkTester.describeSdp(sdp)
                    )
                }
                401 -> return NvrSearchResult.NotFound(
                    "ONVIF сообщил каналы, но RTSP отклонил логин/пароль (401). Проверьте учётные данные."
                )
                else -> Log.w(TAG, "ONVIF stream $streamUri not playable (code ${check?.code})")
            }
        }
        if (byChannel.isEmpty()) return null

        // Каналы с распознанным номером — по возрастанию; остальные нумеруются по порядку профилей
        val ordered = byChannel.entries.sortedBy { (number, _) -> if (number > 0) number else 1000 - number }
        var nextFallback = (byChannel.keys.filter { it > 0 }.maxOrNull() ?: 0)
        val channels = ordered.map { (number, streams) ->
            NvrChannel(if (number > 0) number else ++nextFallback, streams)
        }
        return NvrSearchResult.Found(channels, source = "ONVIF")
    }

    /** Номер канала из пути: Hikvision /Streaming/Channels/N0M, Dahua channel=N, /chN, /Channel_N */
    private fun channelOf(path: String): Int? {
        Regex("""/Streaming/Channels/(\d+)""", RegexOption.IGNORE_CASE).find(path)?.let {
            val id = it.groupValues[1].toInt()
            return if (id >= 100) id / 100 else id
        }
        Regex("""[?&]channel=(\d+)""", RegexOption.IGNORE_CASE).find(path)?.let { return it.groupValues[1].toInt() }
        Regex("""/ch(?:annel)?[_-]?(\d+)""", RegexOption.IGNORE_CASE).find(path)?.let { return it.groupValues[1].toInt() }
        return null
    }
}
