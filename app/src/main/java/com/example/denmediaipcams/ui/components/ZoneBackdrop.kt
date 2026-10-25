package com.example.denmediaipcams.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraHttp
import com.example.denmediaipcams.network.DahuaApi
import com.example.denmediaipcams.player.ThumbnailStore
import com.example.denmediaipcams.widget.CameraFrameGrabber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Кадр камеры под сеткой зон детекции движения: зоны выбирают, глядя на ту часть картинки, которую они закрывают.
 * Кадр берётся в том положении, в каком его выдаёт камера (с её собственным переворотом), — в нём же камера
 * считает зоны, поэтому программный поворот видео в приложении сюда не применяется.
 */
internal object ZoneBackdrop {

    private const val TAG = "ZoneBackdrop"
    private const val MAX_WIDTH = 960
    private const val HTTP_TIMEOUT_MS = 6_000
    private const val RTSP_FALLBACK_TIMEOUT_MS = 12_000L

    /** Последнее превью камеры из списка: есть сразу, но может быть устаревшим */
    suspend fun cached(context: Context, camera: IpCamera): Bitmap? =
        ThumbnailStore.load(context, ThumbnailStore.cameraKey(camera.id))

    /**
     * Свежий кадр: HTTP-снимок камеры (Dahua, Hipcam), иначе то, что умеет [CameraFrameGrabber] (снимок ONVIF
     * или кадр из RTSP-потока). null — кадр получить не удалось.
     */
    suspend fun fresh(context: Context, camera: IpCamera): Bitmap? = withContext(Dispatchers.IO) {
        httpSnapshot(camera)?.let { return@withContext it }
        try {
            withTimeoutOrNull(RTSP_FALLBACK_TIMEOUT_MS) { CameraFrameGrabber.grab(context, camera, MAX_WIDTH) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${camera.name}: кадр не получен: ${e.message}")
            null
        }
    }

    private fun httpSnapshot(camera: IpCamera): Bitmap? {
        val url = when {
            camera.ipAddress.contains("://") -> return null
            camera.hasDahuaApi -> "${DahuaApi.baseUrl(camera)}/cgi-bin/snapshot.cgi?channel=1"
            camera.sdHttpArchive -> "http://${camera.ipAddress}:${camera.effectiveHipcamPort}/tmpfs/auto.jpg"
            else -> return null
        }
        val bytes = CameraHttp.requestBytes(url, camera.username, camera.password, HTTP_TIMEOUT_MS) ?: return null
        return decode(bytes)
    }

    /** JPEG → Bitmap не шире [MAX_WIDTH]: кадр 2560×1920 целиком в память для подложки не нужен */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
