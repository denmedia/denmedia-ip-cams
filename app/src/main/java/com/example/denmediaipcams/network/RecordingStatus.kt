package com.example.denmediaipcams.network

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.example.denmediaipcams.model.IpCamera
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/** Что показывает точка записи на плеере */
enum class RecordingIndicator {
    /** Камера пишет видео на SD-карту прямо сейчас — красная точка */
    RECORDING,

    /** Не пишет, но включена детекция движения или людей — зелёная точка */
    STANDBY,

    /** Не пишет и детекции нет — серая точка */
    IDLE,

    /** Состояние неизвестно: камера не отвечает или ещё не ответила — три мигающие точки */
    LOST
}

/** Точки записи по камерам (id камеры → состояние); обновляет [RecordingStatusTracker], читает плеер */
object RecordingIndicatorState {
    val states = mutableStateMapOf<String, RecordingIndicator>()

    private val changed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Настройки записи камеры изменились (или их пытались изменить): кэш настроек пора перечитать */
    internal fun settingsChanged(cameraId: String) {
        changed.add(cameraId)
    }

    /** true один раз после [settingsChanged] */
    internal fun consumeChanged(cameraId: String): Boolean = changed.remove(cameraId)
}

/** Что камера сама говорит о том, пишет ли она в эту секунду */
internal enum class LiveRecording {
    RECORDING,
    NOT_RECORDING,

    /** Камера не ответила */
    UNREACHABLE,

    /** Эта прошивка (или протокол) состояния записи не отдаёт — остаётся судить по настройкам */
    NOT_REPORTED
}

/** Правила, по которым настройки камеры и сведения о ней сводятся к одной точке (без обращений к сети) */
internal object RecordingActivity {

    /** Должна ли камера по своим настройкам писать постоянно в момент [now] (запись по движению сюда не входит) */
    fun scheduledNow(settings: RecordingSettings, now: LocalDateTime): Boolean = when (settings.mode) {
        RecordMode.ALWAYS -> true
        RecordMode.CUSTOM -> {
            val minute = now.hour * 60 + now.minute
            // В модели 0 — понедельник, как у DayOfWeek.value - 1
            settings.schedule?.days?.getOrNull(now.dayOfWeek.value - 1)
                ?.any { it.kind == RecordKind.ALWAYS && minute >= it.startMinute && minute < it.endMinute } == true
        }
        RecordMode.OFF, RecordMode.MOTION -> false
    }

    /** Включена ли детекция движения или людей */
    fun detectionOn(settings: RecordingSettings): Boolean = settings.motion?.enabled == true || settings.person == true

    /**
     * Итоговая точка. [live] — то, что камера сообщила сама (если умеет): ему верим больше, чем расписанию.
     * Иначе запись считается идущей, если по расписанию сейчас должна идти и карта исправна ([cardUsable]; null —
     * проверить не удалось, карта считается исправной).
     */
    fun indicator(settings: RecordingSettings, live: LiveRecording, cardUsable: Boolean?, now: LocalDateTime): RecordingIndicator {
        val recording = when (live) {
            LiveRecording.RECORDING -> true
            LiveRecording.NOT_RECORDING -> false
            LiveRecording.UNREACHABLE -> return RecordingIndicator.LOST
            LiveRecording.NOT_REPORTED -> cardUsable != false && scheduledNow(settings, now)
        }
        return when {
            recording -> RecordingIndicator.RECORDING
            detectionOn(settings) -> RecordingIndicator.STANDBY
            else -> RecordingIndicator.IDLE
        }
    }

    /** Исправна ли карта, судя по ответу камеры: есть накопитель, он в порядке и не только для чтения */
    fun cardUsable(info: StorageInfo): Boolean = info.volumes.any {
        (it.state == null || it.state.equals("Success", ignoreCase = true)) && !it.type.equals("ReadOnly", ignoreCase = true)
    }
}

/**
 * Следит за записью камер: что камера пишет сейчас (если сама сообщает — Hipcam) или должна писать по расписанию
 * (Dahua, XMEye), включена ли детекция. Настройки и состояние карты читаются редко и запоминаются — прошивки у
 * камер слабые, а настройки меняются нечасто; «пишет ли сейчас» спрашивается при каждом обновлении, если камера
 * умеет это сообщать. После неудачного чтения повтор не раньше чем через [RETRY_AFTER_MS]: частые неверные
 * входы камеры не любят.
 */
internal class RecordingStatusTracker(
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val uptimeMs: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private class Entry(val connection: String) {
        var settings: RecordingSettings? = null
        var cardUsable: Boolean? = null
        var readAt = 0L
        var failedAt = 0L
    }

    private val entries = HashMap<String, Entry>()

    /** Точка камеры; [online] — отвечает ли камера по сети (null — ещё не проверялось) */
    suspend fun indicator(camera: IpCamera, online: Boolean?): RecordingIndicator {
        // Узнать о записи нечем (нет логина или протокола управления записью) — точка серая, а не «связь потеряна»
        if (!CameraRecording.canTry(camera)) return RecordingIndicator.IDLE
        if (online != true) return RecordingIndicator.LOST

        val live = CameraRecording.liveState(camera)
        if (live == LiveRecording.UNREACHABLE) return RecordingIndicator.LOST

        if (RecordingIndicatorState.consumeChanged(camera.id)) entries.remove(camera.id)
        val entry = entries[camera.id]?.takeIf { it.connection == connectionOf(camera) }
            ?: Entry(connectionOf(camera)).also { entries[camera.id] = it }
        val now = uptimeMs()
        val stale = entry.settings == null || now - entry.readAt >= SETTINGS_TTL_MS
        val retryAllowed = entry.failedAt == 0L || now - entry.failedAt >= RETRY_AFTER_MS
        if (stale && retryAllowed) refresh(camera, entry, now, needCard = live == LiveRecording.NOT_REPORTED)
        val settings = entry.settings ?: return RecordingIndicator.LOST

        return RecordingActivity.indicator(settings, live, entry.cardUsable, clock())
    }

    /** Карта нужна ([needCard]) только тем, кто не говорит о записи сам: у остальных запись «по расписанию» — догадка */
    private suspend fun refresh(camera: IpCamera, entry: Entry, now: Long, needCard: Boolean) {
        try {
            val settings = CameraRecording.read(camera)
            if (settings == null) {
                entry.failedAt = now
                return
            }
            entry.settings = settings
            entry.readAt = now
            entry.failedAt = 0L
            if (needCard) entry.cardUsable = CameraRecording.cardUsable(camera)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${camera.name}: ${e.message}")
            entry.failedAt = now
        }
    }

    /** Забыть камеры, которых больше нет */
    fun forget(keep: Set<String>) {
        entries.keys.retainAll(keep)
    }

    private fun connectionOf(camera: IpCamera) = listOf(
        camera.ipAddress, camera.effectiveHttpPort, camera.effectiveHipcamPort, camera.effectiveDvripPort,
        camera.username, camera.password.hashCode(), camera.dahuaApi, camera.protocol, camera.sdHttpArchive, camera.hikvisionArchive, camera.dvripArchive
    ).joinToString("|")

    private companion object {
        const val TAG = "RecordingStatus"
        const val SETTINGS_TTL_MS = 5 * 60_000L
        const val RETRY_AFTER_MS = 60_000L
    }
}
