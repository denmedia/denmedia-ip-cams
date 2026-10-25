package com.example.denmediaipcams.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.denmediaipcams.network.NetworkTester
import com.example.denmediaipcams.player.ThumbnailStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Фоновое обновление виджетов: статусы камер и кадры виджетов трансляции.
 * Раз в 15 минут (минимальный период фоновых задач Android) и по запросу (кнопка обновления).
 * Пока работает WidgetLiveService, кадры обновляет он.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val context = applicationContext
        if (!WidgetRenderer.hasAnyWidget(context)) return@coroutineScope Result.success()
        val cameras = WidgetRenderer.loadCameras(context)

        // Статусы камер: отвечает ли RTSP-порт
        val statuses = cameras.map { camera ->
            async {
                val uri = android.net.Uri.parse(camera.rtspUrlWithoutCredentials)
                val host = uri.host ?: camera.ipAddress
                val port = if (uri.port > 0) uri.port else camera.effectiveRtspPort
                camera.id to (NetworkTester.tcpPingMs(host, port) != null)
            }
        }.awaitAll().toMap()
        WidgetPrefs.saveOnline(context, statuses)

        // Превью-снимки камер с включённой опцией (до отрисовки списка — он показывает превью)
        cameras.filter { it.snapshotThumbnail && statuses[it.id] == true }.forEach { camera ->
            try {
                CameraFrameGrabber.refreshThumbnail(context, camera)
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot thumbnail failed for ${camera.name}: ${e.message}")
            }
        }
        WidgetRenderer.renderAllLists(context)

        // Кадры для виджетов трансляции (если живое обновление сейчас не идёт)
        run {
            val liveCameraIds = WidgetRenderer.liveWidgetIds(context).toList()
                .mapNotNull { widgetId -> WidgetPrefs.liveCameraId(context, widgetId) }
                .filterNot { WidgetLiveService.isLive(it) }
                .toSet()
            cameras.filter { it.id in liveCameraIds }.forEach { camera ->
                if (statuses[camera.id] != true) {
                    WidgetPrefs.setLiveError(context, camera.id, "Камера не в сети (порт RTSP недоступен)")
                    return@forEach
                }
                val result = try {
                    val frame = CameraFrameGrabber.grab(context, camera, WidgetRenderer.LIVE_FRAME_WIDTH)
                    if (frame != null) FrameGrabResult.Success(frame) else FrameGrabResult.Error("Не удалось получить кадр с камеры")
                } catch (e: Exception) {
                    Log.w(TAG, "Frame grab failed for ${camera.name}: ${e.message}")
                    FrameGrabResult.Error("Ошибка: ${e.message}")
                }
                when (result) {
                    is FrameGrabResult.Success -> {
                        val frame = result.bitmap
                        ThumbnailStore.save(context, WidgetRenderer.liveFrameKey(camera.id), frame)
                        WidgetPrefs.setLiveFrameAt(context, camera.id, System.currentTimeMillis())
                        WidgetPrefs.setLiveError(context, camera.id, null)
                        frame.recycle()
                    }
                    is FrameGrabResult.Error -> {
                        WidgetPrefs.setLiveError(context, camera.id, result.message)
                    }
                }
            }
            WidgetRenderer.renderAllLiveFromCache(context)
        }
        Result.success()
    }

    companion object {
        private const val TAG = "WidgetRefreshWorker"
        private const val PERIODIC_NAME = "widget_refresh_periodic"
        private const val ONCE_NAME = "widget_refresh_once"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
