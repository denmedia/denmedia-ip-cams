package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * SD-карта камер Hikvision по ISAPI: состояние и объём — GET /ISAPI/ContentMgmt/Storage, форматирование —
 * PUT /ISAPI/ContentMgmt/Storage/hdd/{id}/format (ход — formatStatus). Объёмы камера отдаёт в мегабайтах.
 */
object HikvisionStorage {

    private const val STORAGE_PATH = "/ISAPI/ContentMgmt/Storage"
    private const val REQUEST_TIMEOUT_MS = 8_000
    private const val FORMAT_POLL_MS = 2_000L
    private const val FORMAT_MAX_POLLS = 150
    private const val MEGABYTE = 1024L * 1024

    private val HDD = Regex("""<hdd\b[^>]*>(.*?)</hdd>""", RegexOption.DOT_MATCHES_ALL)

    private fun baseUrl(camera: IpCamera) = "http://${camera.ipAddress}:${camera.effectiveHttpPort}"

    /** Сведения о карте; пустой список томов — карты в камере нет */
    suspend fun info(camera: IpCamera): StorageInfo = withContext(Dispatchers.IO) {
        parseStorage(get(camera, STORAGE_PATH))
    }

    /**
     * Запускает форматирование и ждёт его окончания (камера сообщает ход сама). Не дождавшись, возвращается без ошибки:
     * вызывающий код всё равно проверяет состояние карты.
     */
    suspend fun format(camera: IpCamera, volume: StorageVolume) = withContext(Dispatchers.IO) {
        val id = volume.device ?: "1"
        val start = CameraHttp.request(
            "${baseUrl(camera)}$STORAGE_PATH/hdd/$id/format",
            method = "PUT", body = "",
            user = camera.username, pass = camera.password, timeoutMs = REQUEST_TIMEOUT_MS
        )
        when {
            start.code == 401 -> throw ArchiveException(ArchiveException.Kind.AUTH, "Камера не приняла логин и пароль")
            start.code == 0 -> throw ArchiveException(ArchiveException.Kind.NETWORK, start.body)
            !start.isSuccess -> throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера отклонила форматирование (код ${start.code})")
        }
        repeat(FORMAT_MAX_POLLS) {
            delay(FORMAT_POLL_MS)
            val status = CameraHttp.request(
                "${baseUrl(camera)}$STORAGE_PATH/hdd/$id/formatStatus",
                user = camera.username, pass = camera.password, timeoutMs = REQUEST_TIMEOUT_MS
            )
            if (status.isSuccess && status.body.contains("<formating>false</formating>")) return@withContext
        }
    }

    private fun get(camera: IpCamera, path: String): String {
        val response = CameraHttp.request(baseUrl(camera) + path, user = camera.username, pass = camera.password, timeoutMs = REQUEST_TIMEOUT_MS)
        when {
            response.code == 401 -> throw ArchiveException(ArchiveException.Kind.AUTH, "Камера не приняла логин и пароль")
            response.code == 0 -> throw ArchiveException(ArchiveException.Kind.NETWORK, response.body)
            !response.isSuccess -> throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера не отдаёт сведения о карте (код ${response.code})")
        }
        return response.body
    }

    /**
     * Разбор /ISAPI/ContentMgmt/Storage. Состояние: ok — исправна (Success), остальное (unformatted, formating,
     * error…) — как сообщила камера. «Не отформатирована» камера показывает с нулём свободного места.
     */
    internal fun parseStorage(xml: String): StorageInfo {
        fun HddText.tag(name: String) = Regex("""<$name>\s*([^<]*?)\s*</$name>""").find(text)?.groupValues?.get(1)
        val volumes = HDD.findAll(xml).map { HddText(it.groupValues[1]) }.mapNotNull { hdd ->
            val id = hdd.tag("id") ?: return@mapNotNull null
            val status = hdd.tag("status").orEmpty()
            val totalBytes = hdd.tag("capacity")?.toLongOrNull()?.times(MEGABYTE)
            val freeBytes = hdd.tag("freeSpace")?.toLongOrNull()?.times(MEGABYTE)
            val usedBytes = if (totalBytes != null && freeBytes != null) (totalBytes - freeBytes).coerceAtLeast(0) else null
            val readOnly = Regex("""<property>\s*R\s*</property>""").containsMatchIn(hdd.text)
            StorageVolume(
                path = hdd.tag("hddName")?.takeIf { it.isNotBlank() } ?: "sd",
                totalBytes = totalBytes,
                usedBytes = usedBytes,
                device = id,
                state = if (status.equals("ok", ignoreCase = true)) "Success" else status.ifBlank { null },
                type = if (readOnly) "ReadOnly" else "ReadWrite"
            )
        }.toList()
        return StorageInfo(volumes, fileSystem = null)
    }

    private class HddText(val text: String)
}
