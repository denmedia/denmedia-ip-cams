package com.example.denmediaipcams.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraHttp
import com.example.denmediaipcams.network.NetworkTester
import com.example.denmediaipcams.network.OnvifClient
import com.example.denmediaipcams.network.SdpFixingSocketFactory
import com.example.denmediaipcams.player.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**Результат получения кадра для виджета */
sealed class FrameGrabResult {
    data class Success(val bitmap: Bitmap) : FrameGrabResult()
    data class Error(val message: String) : FrameGrabResult()
}

/**
 * Кадры камеры для виджетов (без экрана приложения):
 * 1) JPEG-снимок по HTTP — ONVIF GetSnapshotUri, Hikvision ISAPI, Dahua CGI (дёшево по трафику);
 * 2) если камера снимки не отдаёт — кадр из RTSP-потока (декодирование в SurfaceTexture).
 */
object CameraFrameGrabber {

    private const val TAG = "CameraFrameGrabber"
    private const val NO_SNAPSHOT = ""

    /** Найденный адрес снимка по камере; NO_SNAPSHOT — снимки не поддерживаются */
    private val snapshotUrls = ConcurrentHashMap<String, String>()
    private val noSnapshotAt = ConcurrentHashMap<String, Long>()
    private const val NO_SNAPSHOT_RETRY_MS = 5 * 60 * 1_000L

    private fun cacheKey(camera: IpCamera) = "${camera.id}|${camera.ipAddress}|${camera.username}|${camera.protocol}"

    /** Однократный кадр: снимок по HTTP, иначе — из RTSP-потока */
    suspend fun grab(context: Context, camera: IpCamera, maxWidth: Int): Bitmap? =
        grabSnapshot(camera, maxWidth) ?: RtspFrameSource(context, camera).use { it.awaitFrame(maxWidth) }

    /** Кадр по HTTP; null — камера снимки не отдаёт (тогда нужен RtspFrameSource) */
    suspend fun grabSnapshot(camera: IpCamera, maxWidth: Int): Bitmap? =
        (grabSnapshotResult(camera, maxWidth) as? FrameGrabResult.Success)?.bitmap

    suspend fun grabSnapshotResult(camera: IpCamera, maxWidth: Int): FrameGrabResult = withContext(Dispatchers.IO) {
        val key = cacheKey(camera)
        val url = snapshotUrls[key] ?: resolveSnapshotUrl(camera).also {
            snapshotUrls[key] = it ?: NO_SNAPSHOT
            if (it == null) noSnapshotAt[key] = System.currentTimeMillis()
        } ?: NO_SNAPSHOT
        if (url == NO_SNAPSHOT) return@withContext FrameGrabResult.Error("HTTP снимки не поддерживаются")
        // Порт снимка недоступен — снимки не используем, превью берётся из трансляции (повторная проверка позже)
        val parsed = android.net.Uri.parse(url)
        val host = parsed.host ?: camera.ipAddress
        val port = if (parsed.port > 0) parsed.port else 80
        if (NetworkTester.tcpPingMs(host, port) == null) {
            snapshotUrls[key] = NO_SNAPSHOT
            noSnapshotAt[key] = System.currentTimeMillis()
            return@withContext FrameGrabResult.Error("HTTP порт $port недоступен")
        }
        fetchJpegWithResult(url, camera, maxWidth)
    }

    /**
     * true/false — известно, отдаёт ли камера снимки; null — неизвестно. Отрицательный ответ со временем
     * забывается: камера могла быть просто недоступна в момент проверки, и вечно кадры из RTSP брать не нужно.
     */
    fun supportsSnapshot(camera: IpCamera): Boolean? {
        val key = cacheKey(camera)
        val url = snapshotUrls[key] ?: return null
        if (url == NO_SNAPSHOT) {
            val checkedAt = noSnapshotAt[key] ?: 0L
            if (System.currentTimeMillis() - checkedAt > NO_SNAPSHOT_RETRY_MS) {
                snapshotUrls.remove(key)
                return null
            }
            return false
        }
        return true
    }

    /**
     * Ставит снимок камеры (если она отдаёт его по HTTP) в превью камеры.
     * false — снимка нет, превью не тронуто.
     */
    suspend fun refreshThumbnail(context: Context, camera: IpCamera): Boolean {
        val bitmap = grabSnapshot(camera, ThumbnailStore.THUMBNAIL_WIDTH) ?: return false
        return try {
            ThumbnailStore.save(context, ThumbnailStore.cameraKey(camera.id), bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun resolveSnapshotUrl(camera: IpCamera): String? {
        if (camera.ipAddress.contains("://")) return null
        val candidates = buildList {
            if (camera.protocol == CameraProtocol.ONVIF || camera.protocol == CameraProtocol.NONE) {
                onvifSnapshotUrl(camera)?.let { add(it) }
            }
            val base = "http://${camera.ipAddress}:${camera.effectiveHttpPort}"
            when (camera.protocol) {
                CameraProtocol.HIKVISION -> add("$base/ISAPI/Streaming/channels/101/picture")
                CameraProtocol.DAHUA -> add("$base/cgi-bin/snapshot.cgi?channel=1")
                else -> {}
            }
        }
        return candidates.firstOrNull { url ->
            fetchJpeg(url, camera, 64)?.also { it.recycle() } != null
        }.also { Log.i(TAG, "Snapshot URL for ${camera.name}: ${it ?: "not supported"}") }
    }

    private fun onvifSnapshotUrl(camera: IpCamera): String? = try {
        val endpoints = OnvifClient.discover(camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password)
        val profile = endpoints?.let { OnvifClient.getProfiles(it, camera.username, camera.password).firstOrNull() }
        val uri = if (endpoints != null && profile != null) {
            OnvifClient.getSnapshotUri(endpoints, profile.token, camera.username, camera.password)
        } else null
        // Адрес из ответа может содержать внутренний IP — оставляем путь, хост берём из настроек
        uri?.let {
            val parsed = android.net.Uri.parse(it)
            val port = if (parsed.port > 0) parsed.port else 80
            "http://${camera.ipAddress}:$port${parsed.encodedPath ?: "/"}" + (parsed.encodedQuery?.let { q -> "?$q" } ?: "")
        }
    } catch (e: Exception) {
        Log.w(TAG, "ONVIF snapshot URI failed: ${e.message}")
        null
    }

    private fun fetchJpeg(url: String, camera: IpCamera, maxWidth: Int): Bitmap? =
        (fetchJpegWithResult(url, camera, maxWidth) as? FrameGrabResult.Success)?.bitmap

    private fun fetchJpegWithResult(url: String, camera: IpCamera, maxWidth: Int): FrameGrabResult = try {
        val response = CameraHttp.request(url, method = "GET", user = camera.username, pass = camera.password, timeoutMs = 4000)
        when {
            response.code in 200..299 -> {
                val bytes = CameraHttp.requestBytes(url, user = camera.username, pass = camera.password, timeoutMs = 4000)
                val bmp = bytes?.let { decodeScaled(it, maxWidth) }
                if (bmp != null) FrameGrabResult.Success(bmp)
                else FrameGrabResult.Error("Не удалось декодировать кадр HTTP")
            }
            response.code == 401 -> FrameGrabResult.Error("HTTP 401: Неверный логин или пароль")
            response.code == 404 -> FrameGrabResult.Error("HTTP 404: Снимок не найден")
            response.code == 0 -> FrameGrabResult.Error("HTTP порт ${camera.effectiveHttpPort} недоступен")
            else -> FrameGrabResult.Error("Ошибка HTTP ${response.code}")
        }
    } catch (e: Exception) {
        FrameGrabResult.Error("Ошибка HTTP: ${e.message ?: "Сбой соединения"}")
    }

    private fun decodeScaled(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Кадры из RTSP-потока без экрана: ExoPlayer декодирует видео в SurfaceTexture (GlFrameReceiver),
 * последний кадр переводится в Bitmap раз в секунду. Для камер, которые не отдают снимки по HTTP.
 */
@OptIn(UnstableApi::class)
class RtspFrameSource(context: Context, private val camera: IpCamera) : AutoCloseable {

    private val handlerThread = HandlerThread("RtspFrameSource").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var glReceiver: GlFrameReceiver? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var targetWidth = 640
    @Volatile private var closed = false
    private var lastConvertedAt = 0L

    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastFrameTimeMs = 0L

    private companion object {
        const val CONVERT_INTERVAL_MS = 1_000L
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLooper(handlerThread.looper)
        .setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 250, 500).build()
        )
        .build()

    init {
        handler.post {
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    attachSurface(videoSize.width, videoSize.height)
                }

                // Без поверхности ExoPlayer не рисует видео и размер не сообщает (onVideoSizeChanged не придёт),
                // поэтому поверхность создаём по формату видеотрека — он известен сразу после разбора SDP
                override fun onTracksChanged(tracks: Tracks) {
                    val format = tracks.groups
                        .filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                        .map { it.getTrackFormat(0) }
                        .firstOrNull()
                    if (format != null) attachSurface(format.width, format.height)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val cause = error.cause
                    val causeMsg = cause?.message ?: error.message ?: ""
                    val msg = when {
                        causeMsg.contains("401", ignoreCase = true) || causeMsg.contains("Unauthorized", ignoreCase = true) ->
                            "RTSP 401: Неверный логин или пароль"
                        causeMsg.contains("404", ignoreCase = true) ->
                            "RTSP 404: Поток не найден"
                        causeMsg.contains("ConnectException", ignoreCase = true) || causeMsg.contains("refused", ignoreCase = true) ->
                            "Камера недоступна (порт RTSP ${camera.effectiveRtspPort})"
                        causeMsg.contains("Timeout", ignoreCase = true) ->
                            "Превышено время ожидания RTSP"
                        else -> "Ошибка RTSP: ${error.message ?: "Не удалось открыть поток"}"
                    }
                    Log.w("RtspFrameSource", "RTSP error for ${camera.name}: $msg")
                    lastError = msg
                    latest = null
                }
            })
            val url = camera.fullRtspUrl
            if (url.startsWith("rtsp://")) {
                player.setMediaSource(
                    RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .setSocketFactory(SdpFixingSocketFactory())
                        .createMediaSource(MediaItem.fromUri(url))
                )
            } else {
                player.setMediaItem(MediaItem.fromUri(url))
            }
            player.playWhenReady = true
            player.prepare()
        }
    }

    /** Поверхность для декодера; вызывается из потока плеера */
    private fun attachSurface(videoWidth: Int, videoHeight: Int) {
        if (closed || videoWidth <= 0 || videoHeight <= 0) return
        val existing = glReceiver
        if (existing != null) {
            existing.videoWidth = videoWidth
            existing.videoHeight = videoHeight
            return
        }
        val receiver = try {
            GlFrameReceiver(handler, ::onDecodedFrame).apply { start() }
        } catch (e: Exception) {
            Log.w("RtspFrameSource", "GL receiver init failed: ${e.message}")
            lastError = "Не удалось подготовить декодер кадров"
            return
        }
        receiver.videoWidth = videoWidth
        receiver.videoHeight = videoHeight
        glReceiver = receiver
        player.setVideoSurface(receiver.surface)
    }

    private fun onDecodedFrame() {
        val receiver = glReceiver ?: return
        // Декодер выдаёт 25 кадров/с, виджету достаточно одного в секунду, но каждый кадр надо забрать
        val now = System.currentTimeMillis()
        val convert = !closed && now - lastConvertedAt >= CONVERT_INTERVAL_MS
        try {
            val bmp = receiver.consume(if (convert) targetWidth else 0)
            if (bmp != null) {
                lastConvertedAt = now
                lastFrameTimeMs = now
                lastError = null
                latest = bmp
            }
        } catch (e: Exception) {
            Log.w("RtspFrameSource", "Frame convert failed: ${e.message}")
        }
    }

    /** Последний декодированный кадр (null — кадров ещё не было или ошибка) */
    fun latestFrame(maxWidth: Int): Bitmap? {
        targetWidth = maxWidth
        if (lastError != null) return null
        val now = System.currentTimeMillis()
        if (lastFrameTimeMs > 0 && now - lastFrameTimeMs > 10_000L) {
            lastError = "Превышено время ожидания видеопотока"
            return null
        }
        val current = latest ?: return null
        if (current.isRecycled) return null
        // Возвращаем копию кадра, чтобы вызов frame.recycle() в вызывающем коде
        // не приводил к инвалидации кадра в RtspFrameSource
        return current.copy(current.config ?: Bitmap.Config.ARGB_8888, false)
    }

    /** Ждёт первый кадр (до 12 секунд) */
    suspend fun awaitFrame(maxWidth: Int): Bitmap? {
        targetWidth = maxWidth
        return withTimeoutOrNull(12_000) {
            while (latest == null && lastError == null) delay(200)
            latest
        }?.let { if (!it.isRecycled) it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) else null }
    }

    override fun close() {
        closed = true
        handler.post {
            player.release()
            glReceiver?.release()
            glReceiver = null
            handlerThread.quitSafely()
        }
    }
}
