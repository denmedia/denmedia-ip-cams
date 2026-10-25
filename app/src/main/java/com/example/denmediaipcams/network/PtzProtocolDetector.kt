package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.CameraProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class PtzDetectionResult {
    data class Detected(val protocol: CameraProtocol, val message: String) : PtzDetectionResult()
    data class NotDetected(val message: String) : PtzDetectionResult()
}

/**
 * Автоопределение протокола PTZ:
 * 1) ONVIF — камера сообщает PTZ-сервис в GetCapabilities и PTZ-конфигурацию в профилях;
 * 2) Hikvision ISAPI — /ISAPI/System/deviceInfo;
 * 3) Dahua CGI — /cgi-bin/magicBox.cgi?action=getDeviceType.
 *
 * На каждый протокол делается не больше одной попытки входа, чтобы не спровоцировать
 * блокировку учётной записи при неверном пароле.
 */
object PtzProtocolDetector {

    private const val TAG = "PtzProtocolDetector"

    suspend fun detect(
        hostInput: String,
        httpPort: Int,
        onvifPort: Int,
        user: String,
        pass: String,
        onProgress: (String) -> Unit
    ): PtzDetectionResult {
        val host = hostInput.removePrefix("rtsp://").removePrefix("http://").removePrefix("https://")
            .substringAfter("@").substringBefore("/").substringBefore(":").trim()
        if (host.isBlank()) return PtzDetectionResult.NotDetected("Не указан IP-адрес камеры")

        var onvifWithoutPtz = false
        var authRejected = false

        // 1. ONVIF
        onProgress("ONVIF (порт $onvifPort)...")
        val onvif = withContext(Dispatchers.IO) {
            runCatching {
                val endpoints = OnvifClient.discover(host, onvifPort, user, pass) ?: return@runCatching null
                endpoints to OnvifClient.getProfiles(endpoints, user, pass)
            }.getOrNull()
        }
        if (onvif != null) {
            val (endpoints, profiles) = onvif
            val ptzProfile = profiles.firstOrNull { it.hasPtz }
            if (endpoints.ptzUrl != null && ptzProfile != null) {
                return PtzDetectionResult.Detected(
                    CameraProtocol.ONVIF,
                    "ONVIF: найден PTZ-сервис (профиль ${ptzProfile.token})"
                )
            }
            onvifWithoutPtz = true
        }

        // Камера ответила по ONVIF, но без PTZ: Hikvision/Dahua API всё равно проверяем ниже,
        // и только если они не ответят — считаем камеру неповоротной

        // 2. Hikvision ISAPI
        onProgress("Hikvision ISAPI (порт $httpPort)...")
        val hikvision = httpProbe("http://$host:$httpPort/ISAPI/System/deviceInfo", user, pass)
        if (hikvision != null) {
            if (hikvision.isSuccess && hikvision.body.contains("<DeviceInfo", ignoreCase = true)) {
                val model = Regex("""<model>([^<]+)</model>""").find(hikvision.body)?.groupValues?.get(1)
                return PtzDetectionResult.Detected(
                    CameraProtocol.HIKVISION,
                    "Hikvision ISAPI" + (model?.let { ": модель $it" } ?: "")
                )
            }
            if (hikvision.code == 401 && hikvision.authChallenged) authRejected = true
        }

        // 3. Dahua CGI
        onProgress("Dahua CGI (порт $httpPort)...")
        val dahua = httpProbe("http://$host:$httpPort/cgi-bin/magicBox.cgi?action=getDeviceType", user, pass)
        if (dahua != null) {
            if (dahua.isSuccess && dahua.body.trimStart().startsWith("type=")) {
                val model = dahua.body.trim().removePrefix("type=")
                return PtzDetectionResult.Detected(CameraProtocol.DAHUA, "Dahua CGI: модель $model")
            }
            if (dahua.code == 401 && dahua.authChallenged) authRejected = true
        }

        if (onvifWithoutPtz) {
            return PtzDetectionResult.Detected(
                CameraProtocol.NONE,
                "Камера отвечает по ONVIF, но не сообщает о поддержке PTZ — похоже, она без поворотного механизма."
            )
        }

        val message = when {
            authRejected -> "Камера отклонила логин/пароль по HTTP. Проверьте учётные данные и HTTP порт."
            else -> "Не удалось определить протокол PTZ: камера не ответила ни по ONVIF (порт $onvifPort), " +
                "ни по Hikvision/Dahua HTTP API (порт $httpPort). Проверьте порты и логин/пароль."
        }
        return PtzDetectionResult.NotDetected(message)
    }

    private suspend fun httpProbe(url: String, user: String, pass: String): CameraHttp.Response? =
        withContext(Dispatchers.IO) {
            try {
                CameraHttp.request(url, user = user, pass = pass)
            } catch (e: Exception) {
                Log.w(TAG, "HTTP probe $url failed: ${e.message}")
                null
            }
        }
}
