package com.example.denmediaipcams.data

import com.example.denmediaipcams.model.IpCamera
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Настройки камеры одной строкой для QR кода: «DMIPCAM1:» + Base64 (URL-safe) сжатого JSON из [CameraJson].
 * id камеры не передаётся: на другом устройстве камера добавляется как новая.
 */
object CameraShareCode {

    private const val PREFIX = "DMIPCAM1:"
    private const val KEY_ACTIVE_STREAM = "activeStream"

    /** [includeCredentials] = false — логин и пароль в код не попадают, их вводят при добавлении */
    fun encode(camera: IpCamera, includeCredentials: Boolean): String {
        val json = CameraJson.toJson(camera).apply {
            remove("id")
            if (!includeCredentials) {
                remove("username")
                remove("password")
            }
            // Длинные id потоков не передаём: выбранный поток — номером в списке, id создаются заново при чтении
            camera.streamProfiles.indexOfFirst { it.id == camera.activeStreamProfileId }
                .takeIf { it >= 0 }?.let { put(KEY_ACTIVE_STREAM, it) }
            remove("activeStreamProfileId")
            optJSONArray("streamProfiles")?.let { profiles ->
                for (i in 0 until profiles.length()) profiles.getJSONObject(i).remove("id")
            }
            // Значения по умолчанию при чтении восстанавливаются сами: чем короче текст, тем реже QR код
            // и тем легче его отсканировать с экрана
            val defaults = CameraJson.toJson(IpCamera(id = "", name = "", ipAddress = ""))
            defaults.keys().forEach { key ->
                if (key != "name" && key != "ipAddress" && has(key) && get(key).toString() == defaults.get(key).toString()) remove(key)
            }
        }
        val packed = deflate(json.toString().toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
    }

    /** Камера из кода (с пустым id, как новая) или null, если это не код камеры этого приложения */
    fun decode(text: String): IpCamera? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return runCatching {
            val packed = Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
            val obj = JSONObject(String(inflate(packed), Charsets.UTF_8))
            val camera = CameraJson.fromJson(obj)
            val active = camera.streamProfiles.getOrNull(obj.optInt(KEY_ACTIVE_STREAM, -1))
            camera.copy(id = "", activeStreamProfileId = active?.id)
        }.getOrNull()?.takeIf { it.ipAddress.isNotBlank() }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("Код повреждён")
                out.write(buffer, 0, count)
                if (out.size() > MAX_JSON_BYTES) error("Слишком большой код")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private const val MAX_JSON_BYTES = 64 * 1024
}
