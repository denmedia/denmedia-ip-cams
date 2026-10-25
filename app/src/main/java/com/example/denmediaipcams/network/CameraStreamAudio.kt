package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Звук в потоке самой камеры. Многие камеры по умолчанию не передают звук в RTSP-потоке, хотя микрофон работает:
 * включается он в настройках кодирования камеры. Способ зависит от камеры, поэтому приложение пробует те, что
 * определил для неё: Hikvision (ISAPI, см. [HikvisionAudio]) и Dahua (CGI, см. [DahuaStreamAudio]).
 * У остальных камер звук включается только в их собственных настройках — приложение их не трогает.
 */
object CameraStreamAudio {

    enum class Result {
        /** В потоке звук уже был включён */
        ALREADY_ON,

        /** Звук включён сейчас: действующие подключения к потоку надо переоткрыть, чтобы в них появилась звуковая дорожка */
        ENABLED,

        /** Включить не удалось (камера не ответила, отклонила, звука у потока нет) */
        FAILED,

        /** Для этой камеры приложение не умеет включать звук в потоке */
        UNSUPPORTED
    }

    /** Включает звук в потоке, который играет по пути [rtspPath]; уже включённый не трогает */
    suspend fun enable(camera: IpCamera, rtspPath: String): Result = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://") || camera.username.isBlank()) return@withContext Result.UNSUPPORTED
        when {
            camera.hikvisionArchive -> HikvisionAudio.enable(camera, rtspPath)
            camera.hasDahuaApi -> DahuaStreamAudio.enable(camera, rtspPath)
            else -> Result.UNSUPPORTED
        }
    }
}

/**
 * Звук в потоке камер Dahua: `Encode[0].MainFormat[0].AudioEnable` (основной поток, subtype=0) и
 * `Encode[0].ExtraFormat[0].AudioEnable` (дополнительный, subtype=1) через `configManager.cgi`.
 * Не проверялось на настоящей камере: запись подтверждается чтением обратно, при расхождении — отказ.
 */
object DahuaStreamAudio {

    private val SUBTYPE = Regex("""[?&]subtype=(\d+)""")

    /** Имя настройки звука потока по RTSP-пути камеры (…realmonitor?channel=1&subtype=0 → основной поток) */
    internal fun audioKey(rtspPath: String): String {
        val subtype = SUBTYPE.find(rtspPath)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (subtype == 0) "Encode[0].MainFormat[0].AudioEnable" else "Encode[0].ExtraFormat[${subtype - 1}].AudioEnable"
    }

    /** Значение настройки из ответа getConfig (`table.Encode[0].MainFormat[0].AudioEnable=false`); null — настройки нет */
    internal fun readFlag(body: String, key: String): Boolean? =
        Regex("""(?m)^(?:table\.)?${Regex.escape(key)}=(true|false)\s*$""").find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    suspend fun enable(camera: IpCamera, rtspPath: String): CameraStreamAudio.Result = withContext(Dispatchers.IO) {
        val key = audioKey(rtspPath)
        val base = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/configManager.cgi?action="
        fun get(query: String) = CameraHttp.request(base + query, user = camera.username, pass = camera.password, timeoutMs = 8_000)

        val current = get("getConfig&name=Encode")
        if (!current.isSuccess) return@withContext CameraStreamAudio.Result.FAILED
        when (readFlag(current.body, key)) {
            true -> return@withContext CameraStreamAudio.Result.ALREADY_ON
            null -> return@withContext CameraStreamAudio.Result.FAILED
            false -> Unit
        }
        val saved = get("setConfig&" + key.replace("[", "%5B").replace("]", "%5D") + "=true")
        if (!saved.isSuccess || !saved.body.trim().equals("OK", ignoreCase = true)) return@withContext CameraStreamAudio.Result.FAILED
        val after = get("getConfig&name=Encode")
        if (after.isSuccess && readFlag(after.body, key) == true) CameraStreamAudio.Result.ENABLED else CameraStreamAudio.Result.FAILED
    }
}
