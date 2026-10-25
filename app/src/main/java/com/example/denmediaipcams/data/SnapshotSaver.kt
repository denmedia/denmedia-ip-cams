package com.example.denmediaipcams.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Снимок кадра видеопотока: копирует содержимое SurfaceView плеера (PixelCopy)
 * и сохраняет JPEG в «Загрузки/DenIPCam» через MediaStore (разрешения не нужны).
 */
object SnapshotSaver {

    const val FOLDER_NAME = "DenIPCam"

    /**
     * Копирует текущий кадр с SurfaceView плеера. Для полного разрешения передайте размер видео.
     * @return кадр или null, если поверхность ещё не готова / копирование не удалось
     */
    suspend fun capture(view: View, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        // TextureView (плеер с поворотом видео) копируется напрямую; кадр — без поворота
        if (view is TextureView) {
            if (!view.isAvailable) return null
            return withContext(Dispatchers.Main) { view.getBitmap(width, height) }
        }
        val surfaceView = view as? SurfaceView ?: return null
        if (!surfaceView.holder.surface.isValid) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val thread = HandlerThread("SnapshotPixelCopy").apply { start() }
        return try {
            val result = suspendCancellableCoroutine { cont ->
                PixelCopy.request(surfaceView, bitmap, { code -> cont.resume(code) }, Handler(thread.looper))
            }
            if (result == PixelCopy.SUCCESS) bitmap else null
        } finally {
            thread.quitSafely()
        }
    }

    /** @return имя сохранённого файла или null при ошибке */
    suspend fun saveToDownloads(context: Context, bitmap: Bitmap, cameraName: String): String? =
        withContext(Dispatchers.IO) {
            val safeName = cameraName.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').ifBlank { "camera" }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${safeName}_$timestamp.jpg"

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: throw java.io.IOException("Не удалось открыть файл для записи")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                fileName
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }
}
