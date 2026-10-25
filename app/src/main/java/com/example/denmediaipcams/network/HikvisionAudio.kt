package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Звук в потоке камеры Hikvision. По умолчанию (DS-I114W) звук в потоках выключен: микрофон у камеры работает, но
 * в RTSP-поток он не попадает (в SDP только видео). Включается в настройках потока —
 * `/ISAPI/Streaming/channels/{id}`, блок `Audio`, `enabled` (101 — основной поток, 102 — дополнительный).
 * Со звуком в потоке он попадает и в записи на SD-карту.
 */
object HikvisionAudio {

    private const val TIMEOUT_MS = 8_000
    private val AUDIO_ENABLED = Regex("""(<Audio>\s*<enabled>)\s*(true|false)\s*(</enabled>)""")
    // Путь в настройках камеры может быть записан и без начальной «/»
    private val NEW_PATH = Regex("""(?i)(?:^|/)streaming/channels/(\d+)""")
    private val OLD_PATH = Regex("""(?i)(?:^|/)h264/ch(\d+)/(main|sub)""")

    /** Номер потока камеры по RTSP-пути: /Streaming/Channels/101 → 101, /h264/ch1/sub/av_stream → 102; null — путь незнакомый */
    fun streamChannel(rtspPath: String): String? {
        NEW_PATH.find(rtspPath)?.let { return it.groupValues[1] }
        OLD_PATH.find(rtspPath)?.let { return "${it.groupValues[1]}0${if (it.groupValues[2].equals("main", ignoreCase = true)) 1 else 2}" }
        return null
    }

    /** Включён ли звук в XML настроек потока; null — блока Audio нет (у потока нет звука) */
    internal fun audioEnabled(xml: String): Boolean? = AUDIO_ENABLED.find(xml)?.groupValues?.get(2)?.toBooleanStrictOrNull()

    /** XML настроек потока с включённым звуком; null — блока Audio нет */
    internal fun withAudioEnabled(xml: String): String? =
        if (AUDIO_ENABLED.containsMatchIn(xml)) AUDIO_ENABLED.replace(xml) { "${it.groupValues[1]}true${it.groupValues[3]}" } else null

    /** Включает звук в потоке, который играет по пути [rtspPath]; уже включённый не трогает */
    suspend fun enable(camera: IpCamera, rtspPath: String): CameraStreamAudio.Result = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext CameraStreamAudio.Result.FAILED
        val channel = streamChannel(rtspPath) ?: return@withContext CameraStreamAudio.Result.FAILED
        val url = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/ISAPI/Streaming/channels/$channel"
        fun request(method: String, body: String? = null) = CameraHttp.request(
            url, method = method, body = body, contentType = body?.let { "application/xml" },
            user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS
        )

        val current = request("GET")
        if (!current.isSuccess) return@withContext CameraStreamAudio.Result.FAILED
        when (audioEnabled(current.body)) {
            true -> return@withContext CameraStreamAudio.Result.ALREADY_ON
            null -> return@withContext CameraStreamAudio.Result.FAILED
            false -> Unit
        }
        val changed = withAudioEnabled(current.body) ?: return@withContext CameraStreamAudio.Result.FAILED
        val saved = request("PUT", changed)
        if (!saved.isSuccess || !saved.body.contains("<statusString>OK</statusString>")) return@withContext CameraStreamAudio.Result.FAILED
        // Камера могла принять запрос и не применить: проверяем чтением обратно
        val after = request("GET")
        if (after.isSuccess && audioEnabled(after.body) == true) CameraStreamAudio.Result.ENABLED else CameraStreamAudio.Result.FAILED
    }
}
