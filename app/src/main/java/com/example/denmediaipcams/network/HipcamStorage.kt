package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SD-карта камер Hipcam (HiSilicon): сведения и форматирование тем же способом, что и в веб-интерфейсе камеры.
 * Состояние отдаёт param.cgi?cmd=getserverinfo (sdstatus, sdfreespace, sdtotalspace в килобайтах),
 * форматирование в FAT32 запускает sdfrmt.cgi.
 */
object HipcamStorage {

    private const val CGI = "/cgi-bin/hi3510/"
    private const val INFO_TIMEOUT_MS = 8_000

    // Форматирование карты в 125 ГБ идёт долго; камера может вообще не ответить, пока работает
    private const val FORMAT_TIMEOUT_MS = 60_000

    private fun baseUrl(camera: IpCamera) = "http://${camera.ipAddress}:${camera.effectiveHipcamPort}"

    /** Сведения о карте; пустой список томов — карты в камере нет */
    suspend fun info(camera: IpCamera): StorageInfo = withContext(Dispatchers.IO) {
        val response = CameraHttp.request(
            "${baseUrl(camera)}${CGI}param.cgi?cmd=getserverinfo",
            user = camera.username, pass = camera.password, timeoutMs = INFO_TIMEOUT_MS
        )
        when {
            response.code == 401 -> throw ArchiveException(ArchiveException.Kind.AUTH, "Камера не приняла логин и пароль")
            response.code == 0 -> throw ArchiveException(ArchiveException.Kind.NETWORK, response.body)
            !response.isSuccess -> throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера не отдаёт сведения о карте (код ${response.code})")
        }
        parseServerInfo(response.body)
    }

    /**
     * Запускает форматирование карты. Камера может оборвать соединение, пока форматирует, —
     * это ArchiveException вида NETWORK, вызывающий код проверяет результат по состоянию карты.
     */
    suspend fun format(camera: IpCamera) = withContext(Dispatchers.IO) {
        val response = CameraHttp.request(
            "${baseUrl(camera)}${CGI}sdfrmt.cgi",
            user = camera.username, pass = camera.password, timeoutMs = FORMAT_TIMEOUT_MS
        )
        when {
            response.code == 401 -> throw ArchiveException(ArchiveException.Kind.AUTH, "Камера не приняла логин и пароль")
            response.code == 0 -> throw ArchiveException(ArchiveException.Kind.NETWORK, response.body)
            !response.isSuccess -> throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера отклонила форматирование (код ${response.code})")
        }
    }

    /** Разбор ответа getserverinfo: строки вида var sdstatus="Ready"; */
    internal fun parseServerInfo(body: String): StorageInfo {
        fun value(name: String) = Regex("""var\s+$name\s*=\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
        val status = value("sdstatus") ?: throw ArchiveException(ArchiveException.Kind.UNSUPPORTED, "Камера не сообщила состояние карты")
        // «out» — карты в камере нет
        if (status.equals("out", ignoreCase = true)) return StorageInfo(emptyList(), null)
        val totalBytes = value("sdtotalspace")?.toLongOrNull()?.times(1024)
        val freeBytes = value("sdfreespace")?.toLongOrNull()?.times(1024)
        val usedBytes = if (totalBytes != null && freeBytes != null) (totalBytes - freeBytes).coerceAtLeast(0) else null
        val readOnly = status.equals("Readonly", ignoreCase = true)
        val healthy = readOnly || status.equals("Ready", ignoreCase = true)
        return StorageInfo(
            volumes = listOf(
                StorageVolume(
                    path = "/sd",
                    totalBytes = totalBytes,
                    usedBytes = usedBytes,
                    device = "sd",
                    // Экран показывает «Исправна» для Success, остальное — как сообщила камера
                    state = if (healthy) "Success" else status,
                    type = if (readOnly) "ReadOnly" else if (healthy) "ReadWrite" else null
                )
            ),
            fileSystem = null
        )
    }
}
