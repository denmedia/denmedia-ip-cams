package com.example.denmediaipcams.player

import android.content.Context
import android.graphics.Bitmap
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DahuaRecording
import com.example.denmediaipcams.network.H265ParamSets
import com.example.denmediaipcams.widget.RtspFrameSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Превью записей Dahua: первый кадр RTSP-воспроизведения записи. У Dahua нет запроса «картинка записи»,
 * поэтому каждое превью — короткое подключение к камере (около секунды-двух). Строятся строго по очереди:
 * камера не терпит параллельных запросов. Готовые превью лежат в дисковом кэше и повторно не запрашиваются.
 */
object DahuaThumbnails {

    private const val THUMBNAIL_WIDTH = 320

    private val queue = Mutex()

    // Записи, превью которых только что не получилось: повторять при каждой прокрутке списка не нужно
    private val failures = ThumbnailFailures()

    private fun key(camera: IpCamera, recording: DahuaRecording) = "dahua_${camera.id}_${recording.start}"

    /** Превью из кэша, без обращения к камере */
    suspend fun cached(context: Context, camera: IpCamera, recording: DahuaRecording): Bitmap? =
        ArchiveThumbnailCache.get(context.applicationContext, key(camera, recording))

    /** Превью записи: из кэша или с камеры; null — построить не удалось */
    suspend fun load(context: Context, camera: IpCamera, recording: DahuaRecording): Bitmap? {
        val appContext = context.applicationContext
        val key = key(camera, recording)
        ArchiveThumbnailCache.get(appContext, key)?.let { return it }
        if (failures.recently(key)) return null
        return queue.withLock {
            ArchiveThumbnailCache.get(appContext, key)?.let { return@withLock it }
            if (failures.recently(key)) return@withLock null
            val bitmap = grab(appContext, camera, recording)
            if (bitmap == null) {
                failures.mark(key)
                return@withLock null
            }
            failures.clear(key)
            ArchiveThumbnailCache.put(appContext, key, bitmap)
            bitmap
        }
    }

    private suspend fun grab(context: Context, camera: IpCamera, recording: DahuaRecording): Bitmap? {
        // Запись — тот же RTSP-порт, но другой путь; потоки живой трансляции в нём не участвуют
        val playbackCamera = camera.copy(rtspPath = recording.playbackPath, streamProfiles = emptyList(), activeStreamProfileId = null)
        // Запись хранится в H.265, а в SDP камера параметры кодека не присылает: берём их из потока заранее
        H265ParamSets.prepare(playbackCamera)
        return RtspFrameSource(context, playbackCamera).use { it.awaitFrame(THUMBNAIL_WIDTH) }
    }
}
