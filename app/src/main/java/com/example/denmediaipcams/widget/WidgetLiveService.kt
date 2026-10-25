package com.example.denmediaipcams.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.denmediaipcams.MainActivity
import com.example.denmediaipcams.R
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.player.ThumbnailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log

/**
 * Служба переднего плана: пока она запущена, кадры камер в виджетах трансляции
 * обновляются каждые 2 секунды. Включается кнопкой ▶ на виджете (или автоматически
 * при сворачивании приложения, если на экране была открыта камера с таким виджетом)
 * и останавливается кнопкой ⏸, отсутствием виджетов трансляции или по истечении лимита работы.
 */
class WidgetLiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    // Для камер без HTTP-снимков кадр берётся из RTSP-потока — декодер держим открытым между тиками
    private val frameSources = mutableMapOf<String, RtspFrameSource>()
    private val sourceCreatedAt = mutableMapOf<String, Long>()
    private val lastFrameAt = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cameraId = intent?.getStringExtra(EXTRA_CAMERA_ID)
        if (cameraId != null) {
            if (intent.getBooleanExtra(EXTRA_ENSURE, false)) {
                activeCameraIds = activeCameraIds + cameraId
            } else {
                // Кнопка ▶/⏸ переключает только камеру своего виджета
                activeCameraIds = if (cameraId in activeCameraIds) activeCameraIds - cameraId else activeCameraIds + cameraId
            }
        }
        if (activeCameraIds.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob == null) startLoop()
        // Состояние кнопок ▶/⏸ сразу, не дожидаясь очередного тика
        scope.launch {
            try {
                WidgetRenderer.renderAllLiveFromCache(this@WidgetLiveService)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "render failed: ${e.message}")
            }
        }
        return START_STICKY
    }

    private fun startLoop() {
        loopJob = scope.launch {
            var elapsedMs = 0L
            while (elapsedMs < MAX_RUNTIME_MS) {
                val cameras = WidgetRenderer.loadCameras(this@WidgetLiveService).associateBy { it.id }
                val active = activeCameraIds
                val liveCameraIds = WidgetRenderer.liveWidgetIds(this@WidgetLiveService).toList()
                    .mapNotNull { widgetId -> WidgetPrefs.liveCameraId(this@WidgetLiveService, widgetId) }
                    .filter { it in active }
                    .distinct()

                if (liveCameraIds.isEmpty()) break

                // Декодеры камер, которых больше нет ни в одном виджете, можно закрыть
                frameSources.keys.filter { it !in liveCameraIds }.forEach { id -> frameSources.remove(id)?.close() }

                liveCameraIds.forEach { cameraId ->
                    val camera = cameras[cameraId] ?: return@forEach
                    try {
                        Log.i(TAG, "grab start ${camera.name}")
                        val grabbed = withTimeoutOrNull(GRAB_TIMEOUT_MS) { grabFrame(camera) }
                        Log.i(TAG, "grab result ${camera.name}: ${grabbed ?: "TIMEOUT"}")
                        if (grabbed == null) {
                            // Зависший захват (камера не отвечает) — источник пересоздаём
                            frameSources.remove(cameraId)?.close()
                        }
                        when (val result = grabbed ?: FrameGrabResult.Error("Камера не отвечает (таймаут)")) {
                            is FrameGrabResult.Success -> {
                                val frame = result.bitmap
                                ThumbnailStore.save(this@WidgetLiveService, WidgetRenderer.liveFrameKey(cameraId), frame)
                                WidgetPrefs.setLiveFrameAt(this@WidgetLiveService, cameraId, System.currentTimeMillis())
                                WidgetPrefs.setLiveError(this@WidgetLiveService, cameraId, null)
                                frame.recycle()
                            }
                            is FrameGrabResult.Error -> {
                                WidgetPrefs.setLiveError(this@WidgetLiveService, cameraId, result.message)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        WidgetPrefs.setLiveError(this@WidgetLiveService, cameraId, "Ошибка: ${e.message}")
                    }
                }
                try {
                    WidgetRenderer.renderAllLiveFromCache(this@WidgetLiveService)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Один сбой отрисовки не должен останавливать обновление
                }

                delay(UPDATE_INTERVAL_MS)
                elapsedMs += UPDATE_INTERVAL_MS
            }
            stopSelf()
        }
    }

    /** Снимок по HTTP — лёгкий путь; если камера его не поддерживает, читаем кадр из открытого RTSP-декодера */
    private suspend fun grabFrame(camera: IpCamera): FrameGrabResult {
        if (CameraFrameGrabber.supportsSnapshot(camera) != false) {
            val snapshotResult = CameraFrameGrabber.grabSnapshotResult(camera, WidgetRenderer.LIVE_FRAME_WIDTH)
            if (snapshotResult is FrameGrabResult.Success) return snapshotResult
        }
        val source = frameSources.getOrPut(camera.id) {
            sourceCreatedAt[camera.id] = System.currentTimeMillis()
            RtspFrameSource(applicationContext, camera)
        }
        val frame = source.latestFrame(WidgetRenderer.LIVE_FRAME_WIDTH)
        if (frame != null) {
            lastFrameAt[camera.id] = System.currentTimeMillis()
            return FrameGrabResult.Success(frame)
        }
        val errorMsg = source.lastError ?: "Не удалось получить кадр с камеры"
        // После ошибки плеер сам не восстанавливается (обрыв сети, перезагрузка камеры) —
        // закрываем источник, на следующем тике откроется новый. То же, если кадров нет слишком долго
        val lastAlive = maxOf(lastFrameAt[camera.id] ?: 0L, sourceCreatedAt[camera.id] ?: 0L)
        if (source.lastError != null || System.currentTimeMillis() - lastAlive > SOURCE_STALL_MS) {
            frameSources.remove(camera.id)?.close()
        }
        return FrameGrabResult.Error(errorMsg)
    }

    override fun onDestroy() {
        isRunning = false
        activeCameraIds = emptySet()
        loopJob?.cancel()
        frameSources.values.forEach { it.close() }
        frameSources.clear()
        scope.cancel()
        super.onDestroy()
        // Кнопка на виджете должна вернуться в состояние ▶
        CoroutineScope(Dispatchers.Default).launch {
            WidgetRenderer.renderAllLiveFromCache(applicationContext)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Обновление виджета камеры", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Показывается, пока виджет трансляции обновляет кадры"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Трансляция в виджете обновляется")
            .setContentText("Нажмите ⏸ на виджете, чтобы остановить")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "widget_live_updates"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "WidgetLiveService"
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val GRAB_TIMEOUT_MS = 15_000L

        // Источник кадров без единого кадра дольше этого времени пересоздаётся
        private const val SOURCE_STALL_MS = 20_000L

        // Защита от забытого включённого виджета: автостоп через 3 часа непрерывной работы
        private const val MAX_RUNTIME_MS = 3 * 60 * 60 * 1_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Камера, которая сейчас открыта на весь экран в приложении (null — приложение свёрнуто/не на весь экран) */
        @Volatile
        var fullscreenCameraId: String? = null

        private const val EXTRA_CAMERA_ID = "camera_id"
        private const val EXTRA_ENSURE = "ensure"

        /** Камеры, у которых сейчас включено живое обновление (по кнопке ▶ своего виджета) */
        @Volatile
        var activeCameraIds: Set<String> = emptySet()
            private set

        fun isLive(cameraId: String?): Boolean = cameraId != null && cameraId in activeCameraIds

        /** Переключает обновление только для одной камеры; requestCode уникален для камеры, иначе интенты разных виджетов склеятся */
        fun togglePendingIntent(context: Context, cameraId: String): PendingIntent {
            val intent = Intent(context, WidgetLiveService::class.java).putExtra(EXTRA_CAMERA_ID, cameraId)
            return PendingIntent.getForegroundService(
                context, cameraId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Запуск без переключения: не останавливает уже идущее обновление (используется при сворачивании приложения) */
        fun ensureStarted(context: Context, cameraId: String) {
            if (isLive(cameraId)) return
            val intent = Intent(context, WidgetLiveService::class.java)
                .putExtra(EXTRA_CAMERA_ID, cameraId)
                .putExtra(EXTRA_ENSURE, true)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WidgetLiveService::class.java))
        }
    }
}
