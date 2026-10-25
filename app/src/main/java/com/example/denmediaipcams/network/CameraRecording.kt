package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Итог записи настроек в камеру */
sealed class RecordingWriteResult {
    /** Камера приняла настройки, [settings] — то, что она вернула при проверке */
    data class Saved(val settings: RecordingSettings) : RecordingWriteResult()

    /** Камера не приняла или не подтвердила; [current] — что в ней сейчас (null — камера не ответила) */
    data class Failed(val message: String, val current: RecordingSettings?) : RecordingWriteResult()
}

/**
 * Управление записью на SD-карту камеры, расписанием, детекцией движения и людей — средствами самой камеры.
 * Поддерживается: Dahua (HTTP CGI), Hipcam (hi3510 CGI), Hikvision (ISAPI) и XMEye/DVRIP
 * (только если для камеры включён этот протокол, он слабо защищён — см. [DvripClient]).
 * Каждая запись проверяется чтением обратно; если камера не приняла часть настроек, прежние возвращаются.
 */
object CameraRecording {

    private const val TAG = "CameraRecording"
    private const val CONFIRM_ATTEMPTS = 3
    private const val CONFIRM_DELAY_MS = 1_000L

    /** Есть ли у камеры способ управлять записью (без обращения к сети) */
    fun canTry(camera: IpCamera): Boolean = camera.username.isNotBlank() && driversFor(camera).isNotEmpty()

    private fun driversFor(camera: IpCamera): List<RecordingDriver> {
        if (camera.ipAddress.contains("://")) return emptyList()
        return listOfNotNull(
            DahuaRecordingDriver(camera).takeIf { camera.hasDahuaApi },
            HipcamRecordingDriver(camera).takeIf { camera.sdHttpArchive },
            HikvisionRecordingDriver(camera).takeIf { camera.hikvisionArchive },
            DvripRecordingDriver(camera).takeIf { camera.dvripArchive }
        )
    }

    /** Текущие настройки камеры; null — камера не ответила ни одним из способов */
    suspend fun read(camera: IpCamera): RecordingSettings? = withContext(Dispatchers.IO) {
        val drivers = driversFor(camera)
        try {
            for (driver in drivers) {
                driver.read()?.let { return@withContext it }
            }
            null
        } finally {
            drivers.forEach { it.close() }
        }
    }

    /** Что камера сама сообщает о текущей записи; [LiveRecording.NOT_REPORTED] — ничего (судить придётся по настройкам) */
    internal suspend fun liveState(camera: IpCamera): LiveRecording = withContext(Dispatchers.IO) {
        val drivers = driversFor(camera)
        try {
            for (driver in drivers) {
                val state = driver.liveRecording()
                if (state != LiveRecording.NOT_REPORTED) return@withContext state
            }
            LiveRecording.NOT_REPORTED
        } finally {
            drivers.forEach { it.close() }
        }
    }

    /** Исправна ли SD-карта камеры; null — камера не ответила или карту проверить нечем */
    internal suspend fun cardUsable(camera: IpCamera): Boolean? = withContext(Dispatchers.IO) {
        if (camera.ipAddress.contains("://")) return@withContext null
        try {
            val info = when {
                camera.hasDahuaApi -> DahuaArchiveSession(camera).let { session ->
                    try { session.storageInfo() } finally { session.close() }
                }
                camera.sdHttpArchive -> HipcamStorage.info(camera)
                camera.hikvisionArchive -> HikvisionStorage.info(camera)
                camera.dvripArchive -> DvripClient(camera).let { client ->
                    try {
                        client.login()
                        client.storageInfo()
                    } finally {
                        client.close()
                    }
                }
                else -> return@withContext null
            }
            RecordingActivity.cardUsable(info)
        } catch (e: ArchiveException) {
            Log.w(TAG, "${camera.name}: состояние карты неизвестно: ${e.message}")
            null
        }
    }

    /** Записывает [wanted] в камеру (поля-возможности игнорируются) и проверяет чтением обратно */
    suspend fun write(camera: IpCamera, requested: RecordingSettings): RecordingWriteResult = withContext(Dispatchers.IO) {
        // Расписание, совпадающее с простым режимом, камера вернёт именно как этот режим
        val wanted = requested.normalized()
        val drivers = driversFor(camera)
        try {
            for (driver in drivers) {
                val now = driver.read() ?: continue
                val expected = driver.expectedAfterWrite(wanted)
                if (matches(now, expected)) return@withContext RecordingWriteResult.Saved(now)
                if (!driver.write(now, wanted)) {
                    Log.w(TAG, "${camera.name}: камера отклонила запись настроек")
                    driver.write(wanted, now)
                    return@withContext RecordingWriteResult.Failed("Камера не приняла настройки", driver.read() ?: now)
                }
                var actual: RecordingSettings? = null
                repeat(CONFIRM_ATTEMPTS) { attempt ->
                    if (matches(actual, expected)) return@repeat
                    if (attempt > 0) delay(CONFIRM_DELAY_MS)
                    actual = driver.read()
                }
                actual?.takeIf { matches(it, expected) }?.let { return@withContext RecordingWriteResult.Saved(it) }
                Log.w(TAG, "${camera.name}: камера не подтвердила настройки записи")
                driver.write(actual ?: wanted, now)
                return@withContext RecordingWriteResult.Failed("Камера не подтвердила настройки, прежние возвращены", driver.read() ?: now)
            }
            RecordingWriteResult.Failed("Камера не отвечает или не поддерживает управление записью", null)
        } finally {
            drivers.forEach { it.close() }
            // Чем бы ни закончилось, точка записи на плеере должна перечитать настройки камеры
            RecordingIndicatorState.settingsChanged(camera.id)
        }
    }

    /**
     * Совпадают ли настройки в камере с ожидаемыми. Сравниваются только те поля, которые ожидаются (не null);
     * интервалы расписания — независимо от порядка.
     */
    internal fun matches(actual: RecordingSettings?, expected: RecordingSettings): Boolean {
        if (actual == null) return false
        if (actual.mode != expected.mode) return false
        if (expected.mode == RecordMode.CUSTOM && expected.schedule != null) {
            fun norm(s: WeekSchedule?) = s?.days?.map { day -> day.sortedWith(compareBy({ it.startMinute }, { it.endMinute })) }
            if (norm(actual.schedule) != norm(expected.schedule)) return false
        }
        if (expected.preRecordSec != null && actual.preRecordSec != expected.preRecordSec) return false
        if (expected.overwrite != null && actual.overwrite != expected.overwrite) return false
        if (expected.person != null && actual.person != expected.person) return false
        expected.motion?.let { m ->
            val a = actual.motion ?: return false
            if (a.enabled != m.enabled || a.sensitivity != m.sensitivity) return false
            if (m.zones != null && a.zones?.cells != m.zones.cells) return false
        }
        return true
    }
}
