package com.example.denmediaipcams.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.example.denmediaipcams.data.SnapshotSaver
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.StreamProfile
import com.example.denmediaipcams.network.CameraStreamAudio
import com.example.denmediaipcams.network.CameraTalkback
import com.example.denmediaipcams.network.NetworkTester
import com.example.denmediaipcams.network.RtspCheckStatus
import com.example.denmediaipcams.network.SdpFixingSocketFactory
import com.example.denmediaipcams.network.TalkbackRefusedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Разговор с камерой через микрофон планшета */
enum class TalkState { IDLE, CONNECTING, TALKING }

/** Данные о потоке, которые собирает сессия (из AnalyticsListener) */
data class StreamDiagnostics(
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val droppedFrames: Int = 0,
    val connectedAtMs: Long? = null,
    val transport: String = "TCP"
)

// Камеры с редкими ключевыми кадрами (Yoosee и др.) показывают первый кадр лишь через несколько секунд
private const val CONNECTION_TIMEOUT_MS = 12_000L
private const val PING_INTERVAL_MS = 5_000L
// Сколько ждать после включения звука в камере, прежде чем открыть поток заново: камере нужно время применить настройку
private const val AUDIO_APPLY_DELAY_MS = 1_000L

// Сторож соединения: проверка раз в WATCHDOG_INTERVAL_MS; картинка стоит дольше STALL_TIMEOUT_MS — переподключаемся
private const val WATCHDOG_INTERVAL_MS = 500L
private const val STALL_TIMEOUT_MS = 4_000L
// Позиция стоит дольше этого — считаем, что картинка зависла (лоадер по центру вместо мелкого внизу)
private const val PICTURE_FREEZE_MS = 1_500L

// Повторные попытки после ошибки: интервал растёт от MIN до MAX
private const val AUTO_RECONNECT_MIN_MS = 3_000L
private const val AUTO_RECONNECT_MAX_MS = 30_000L

// Временное понижение качества: проблемы связи (буферизация — вес 1, зависание/ошибка — вес 2) на сумму
// DEGRADE_TROUBLE_WEIGHT за DEGRADE_WINDOW_MS переключают на поток с меньшим разрешением. Назад возвращаемся,
// как только связь стабилизировалась: проблем не было RECOVER_MIN_MS, а отклик камеры снова хороший (не больше
// RECOVER_PING_MAX_MS); если проблемы повторились сразу после возврата — ждём вдвое дольше (до RECOVER_MAX_MS)
private const val DEGRADE_WINDOW_MS = 60_000L
private const val DEGRADE_TROUBLE_WEIGHT = 3
private const val RECOVER_MIN_MS = 8_000L
private const val RECOVER_MAX_MS = 120_000L
private const val RECOVER_PROBE_MS = 30_000L
private const val RECOVER_PING_MAX_MS = 150L

private val RESOLUTION_IN_NAME = Regex("""\d{3,5}\s*[×xX]\s*(\d{3,5})""")

// Догон реального времени: если плеер накопил больше CATCH_UP_START_MS видео, воспроизведение
// ускоряется (до CATCH_UP_FAST_MS — сильнее), пока отставание не упадёт ниже CATCH_UP_STOP_MS
private const val CATCH_UP_START_MS = 600L
private const val CATCH_UP_FAST_MS = 1_500L
private const val CATCH_UP_STOP_MS = 250L
private const val CATCH_UP_SPEED = 1.1f
private const val CATCH_UP_FAST_SPEED = 1.25f

// Настройки плеера по камерам (звук выключен/включён)
private const val PLAYER_PREFS = "denmedia_player_prefs"
private const val KEY_MUTED_PREFIX = "muted_"

// Пауза после первого кадра перед снимком превью: первые кадры после подключения бывают серыми/размытыми
private const val FIRST_THUMBNAIL_DELAY_MS = 1_500L

/**
 * Задержка, которую плеер не видит: кодирование в камере + декодирование и вывод кадра.
 * Типичное значение для IP-камер; добавляется к измеряемым буферу и сетевой задержке.
 */
private const val PIPELINE_LATENCY_MS = 200L

/**
 * Соединение с видеопотоком одной камеры. Живёт дольше экрана, который его показывает:
 * при переходе в полноэкранный режим и обратно сессия не пересоздаётся — переключается
 * только PlayerView, поэтому камера не переподключается.
 */
@OptIn(UnstableApi::class)
class StreamSession internal constructor(
    private val appContext: Context,
    initialCamera: IpCamera
) {
    var camera by mutableStateOf(initialCamera)
        private set
    var isBuffering by mutableStateOf(true)
        private set
    var hasError by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var diagnostics by mutableStateOf(StreamDiagnostics())
        private set

    /** Время TCP-подключения к RTSP-порту камеры; обновляется каждые 5 секунд */
    var pingMs by mutableStateOf<Long?>(null)
        private set
    var pingFailed by mutableStateOf(false)
        private set

    var isMuted by mutableStateOf(false)
        private set
    var showInfo by mutableStateOf(false)

    /** Идёт ли разговор: звук с микрофона планшета передаётся на динамик камеры (см. [startTalk]) */
    var talkState by mutableStateOf(TalkState.IDLE)
        private set
    private var talkJob: Job? = null

    /** Камера не приняла звук (закрыла соединение): кнопка разговора для неё скрыта, пока не изменятся адрес или учётные данные */
    var talkRefused by mutableStateOf(false)
        private set
    private var audioSuspended = false
    private var released = false

    /** Разрешение видео (0 — ещё неизвестно); для метки качества HD/FHD/4K */
    var videoHeight by mutableStateOf(0)
        private set

    /** Видео идёт: есть первый показанный кадр и нет ошибки */
    val isPlaying: Boolean get() = hasFirstFrame && !hasError && !isBuffering
    private var hasFirstFrame by mutableStateOf(false)
    private var isPictureFrozen by mutableStateOf(false)

    /** Из-за плохой связи временно играет поток с меньшим разрешением, чем выбран у камеры */
    var isQualityReduced by mutableStateOf(false)
        private set

    // Временный поток вместо выбранного (не сохраняется в настройках камеры)
    private var degradedProfile: StreamProfile? = null
    private val troubleEvents = ArrayDeque<Pair<Long, Int>>()
    private var lastTroubleAtMs = 0L
    private var recoverDelayMs = RECOVER_MIN_MS
    private var restoredAtMs = 0L
    private var lowerStreamFailed = false

    // Поток, который сейчас запрошен, и разрешения потоков, увиденные при воспроизведении
    private var playingProfileId: String? = null
    private val learnedHeights = mutableMapOf<String, Int>()

    /** На экране есть живая картинка: кадр уже показан и она не зависла (буферизация — лишь короткая пауза) */
    val hasLivePicture: Boolean get() = hasFirstFrame && !isPictureFrozen

    // Поверхность, на которой сейчас показывается видео (из неё снимаются кадры)
    private var attachedSurfaceView: View? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null

    // Сначала RTP по TCP; если камера не отдаёт поток — повтор по UDP (с автоматическим fallback ExoPlayer на TCP)
    private var useRtpTcp = true

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(lowLatencyLoadControl())
        .build()
        .apply { playWhenReady = true }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // Остановка посреди показа (после первого кадра) — признак плохой связи
                    if (hasFirstFrame && !isBuffering) recordTrouble(1)
                    isBuffering = true
                    hasError = false
                }
                Player.STATE_READY -> {
                    if (isBuffering) {
                        isBuffering = false
                        hasError = false
                        timeoutJob?.cancel()
                        if (diagnostics.connectedAtMs == null) {
                            diagnostics = diagnostics.copy(connectedAtMs = SystemClock.elapsedRealtime())
                        }
                        autoReconnectAttempts = 0
                    }
                }
                Player.STATE_ENDED -> isBuffering = false
                Player.STATE_IDLE -> {}
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                videoHeight = videoSize.height
                playingProfileId?.let { learnedHeights[it] = videoSize.height }
            }
        }

        override fun onRenderedFirstFrame() {
            hasFirstFrame = true
            isPictureFrozen = false
            // Превью камеры создаётся при первом подключении, если его ещё нет
            if (!ThumbnailStore.exists(appContext, ThumbnailStore.cameraKey(camera.id))) {
                scope.launch {
                    delay(FIRST_THUMBNAIL_DELAY_MS)
                    saveCameraThumbnail()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            timeoutJob?.cancel()
            val errText = error.localizedMessage ?: "Ошибка передачи RTSP потока"
            // Если переключились на поток попроще, ошибку не показываем — подключение уже перезапущено
            if (recordTrouble(2)) return
            showFailureWithDiagnostics("Ошибка подключения к камере ${camera.name}: $errText")
        }
    }

    // Декодеры и пропущенные кадры — для панели «Инфо»
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            diagnostics = diagnostics.copy(videoDecoderName = decoderName)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            diagnostics = diagnostics.copy(audioDecoderName = decoderName)
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            diagnostics = diagnostics.copy(droppedFrames = diagnostics.droppedFrames + droppedFrames)
        }
    }

    // Поток, для которого звук в камере уже проверен (камера, порт, учётные данные, путь потока): повторно не спрашиваем.
    // Объявлено до init: init уже запускает проверку
    private var cameraAudioChecked: String? = null

    init {
        isMuted = appContext.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUTED_PREFIX + initialCamera.id, false)
        player.volume = if (isMuted) 0f else 1f
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        connect()
        startPingLoop()
        startWatchdog()
        ensureCameraAudio()
    }

    /**
     * Звук включён в приложении, а в потоке камеры его может не быть (у многих камер он по умолчанию выключен): если в настройках
     * камеры включена опция [IpCamera.cameraAudioWithApp], включает звук в настройках потока камеры и переоткрывает
     * подключение, чтобы в нём появилась звуковая дорожка. [force] — проверить заново, даже если поток уже проверяли
     * (звук в приложении включили кнопкой).
     */
    private fun ensureCameraAudio(force: Boolean = false) {
        val cam = camera
        if (isMuted || !cam.cameraAudioWithApp || cam.username.isBlank()) return
        val path = (degradedProfile ?: cam.activeStreamProfile)?.rtspPath ?: cam.rtspPath
        val key = listOf(
            cam.ipAddress, cam.effectiveHttpPort, cam.username, cam.password.hashCode(), path, cam.hikvisionArchive, cam.hasDahuaApi
        ).joinToString("|")
        if (!force && key == cameraAudioChecked) return
        cameraAudioChecked = key
        scope.launch {
            when (CameraStreamAudio.enable(cam, path)) {
                CameraStreamAudio.Result.ENABLED -> {
                    toast("Звук включён в потоке камеры ${cam.name}")
                    // Камере нужна секунда, чтобы применить настройку; потом поток открывается заново — уже со звуком
                    delay(AUDIO_APPLY_DELAY_MS)
                    switchStream()
                }
                CameraStreamAudio.Result.ALREADY_ON, CameraStreamAudio.Result.UNSUPPORTED -> Unit
                CameraStreamAudio.Result.FAILED -> android.util.Log.w("CamNet", "StreamSession: не удалось включить звук в потоке ${cam.ipAddress}")
            }
        }
    }

    /** Обновляет параметры камеры; при смене адреса потока переподключается */
    fun updateCamera(newCamera: IpCamera) {
        // Разговор шёл с прежней камерой (другой адрес или учётные данные): прекращаем
        if (newCamera.ipAddress != camera.ipAddress || newCamera.username != camera.username || newCamera.password != camera.password) {
            stopTalk()
            talkRefused = false
        }
        val urlChanged = newCamera.fullRtspUrl != camera.fullRtspUrl
        camera = newCamera
        if (urlChanged) {
            // Поток выбран заново (или камера изменена) — прежнее временное понижение не нужно
            resetQualityReduction()
            reconnect()
        } else if (degradedProfile != null && !newCamera.autoLowerQuality) {
            // Понижение качества отключили в настройках камеры — сразу возвращаем выбранный поток
            resetQualityReduction()
            switchStream()
        }
        // Другой поток, другие учётные данные или опция включена в настройках: звук в камере проверяется заново
        ensureCameraAudio()
    }

    private fun resetQualityReduction() {
        degradedProfile = null
        isQualityReduced = false
        troubleEvents.clear()
        recoverDelayMs = RECOVER_MIN_MS
        lowerStreamFailed = false
    }

    private fun profileHeight(profile: StreamProfile): Int? =
        learnedHeights[profile.id] ?: RESOLUTION_IN_NAME.find(profile.name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Поток с меньшим разрешением, чем выбран у камеры: сначала по известным разрешениям
     * (увиденным при показе или указанным в названии), иначе — следующий по порядку в списке
     * (основной поток обычно идёт первым).
     */
    private fun lowerProfile(): StreamProfile? {
        val profiles = camera.streamProfiles
        val selected = camera.activeStreamProfile ?: return null
        if (profiles.size < 2) return null
        val selectedHeight = profileHeight(selected)
        if (selectedHeight != null) {
            profiles.filter { it.id != selected.id }
                .mapNotNull { p -> profileHeight(p)?.takeIf { it < selectedHeight }?.let { p to it } }
                .maxByOrNull { it.second }
                ?.let { return it.first }
        }
        val selectedIndex = profiles.indexOfFirst { it.id == selected.id }
        return profiles.drop(selectedIndex + 1).firstOrNull { profileHeight(it) == null }
    }

    /** Адрес потока, который нужно играть сейчас: временный (пониженного качества) или выбранный */
    private fun currentStreamUrl(): String =
        degradedProfile?.let { camera.copy(activeStreamProfileId = it.id).fullRtspUrl } ?: camera.fullRtspUrl

    /**
     * Учитывает проблему связи; если их набралось много, а у камеры есть поток попроще — переключается
     * на него. Возвращает true, если подключение перезапущено на другом потоке.
     */
    private fun recordTrouble(weight: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        lastTroubleAtMs = now
        troubleEvents.addLast(now to weight)
        while (troubleEvents.isNotEmpty() && now - troubleEvents.first().first > DEGRADE_WINDOW_MS) troubleEvents.removeFirst()
        if (pausedForBackground) return false
        if (troubleEvents.sumOf { it.second } < DEGRADE_TROUBLE_WEIGHT) return false
        if (degradedProfile != null) {
            // Ошибки и зависания не прекратились и на потоке попроще — дело не в качестве: возвращаем выбранный
            // поток и больше не понижаем, пока камеру или поток не изменят
            // (только если поток попроще так и не подключился — нестабильная связь на нём это не отменяет)
            if (weight < 2 || diagnostics.connectedAtMs != null) return false
            troubleEvents.clear()
            lowerStreamFailed = true
            restoreQuality()
            return true
        }
        if (lowerStreamFailed || !camera.autoLowerQuality) return false
        val lower = lowerProfile() ?: return false

        // Проблемы вернулись вскоре после возврата на хорошее качество — следующая попытка вернуться будет позже
        recoverDelayMs = if (now - restoredAtMs < RECOVER_PROBE_MS) (recoverDelayMs * 2).coerceAtMost(RECOVER_MAX_MS) else RECOVER_MIN_MS
        android.util.Log.i("CamNet", "StreamSession: связь с ${camera.ipAddress} нестабильна, временно понижаем качество до \"${lower.name}\"")
        troubleEvents.clear()
        degradedProfile = lower
        isQualityReduced = true
        switchStream()
        return true
    }

    /** Возврат на выбранный поток после того, как связь некоторое время была стабильной */
    private fun restoreQuality() {
        android.util.Log.i("CamNet", "StreamSession: связь с ${camera.ipAddress} стабильна, возвращаем выбранное качество")
        degradedProfile = null
        isQualityReduced = false
        restoredAtMs = SystemClock.elapsedRealtime()
        switchStream()
    }

    private fun switchStream() {
        player.stop()
        useRtpTcp = true
        autoReconnectAttempts = 0
        connect()
    }

    fun reconnect() {
        useRtpTcp = true
        autoReconnectAttempts = 0
        connect()
    }

    // Автоматические переподключения: без всплывающих сообщений, интервал растёт после неудач
    private var autoReconnectAttempts = 0
    private var lastAutoReconnectAt = 0L

    private fun autoReconnect(reason: String) {
        android.util.Log.i("CamNet", "StreamSession.autoReconnect ${camera.ipAddress}: $reason")
        lastAutoReconnectAt = SystemClock.elapsedRealtime()
        autoReconnectAttempts++
        useRtpTcp = true
        connect()
    }

    private fun autoReconnectDelayMs(): Long =
        (AUTO_RECONNECT_MIN_MS shl (autoReconnectAttempts - 1).coerceIn(0, 4)).coerceAtMost(AUTO_RECONNECT_MAX_MS)

    private var pausedForBackground = false

    /**
     * Останавливает воспроизведение и сетевое соединение (приложение свёрнуто).
     * Не влияет на виджет трансляции — у него отдельный плеер/декодер, не связанный с сессией.
     */
    fun pauseForBackground() {
        if (!player.isPlaying && !isBuffering) return
        pausedForBackground = true
        timeoutJob?.cancel()
        player.stop()
        isBuffering = false
        hasFirstFrame = false
    }

    /** Переподключается после возврата приложения на передний план, если было приостановлено */
    fun resumeFromBackground() {
        if (!pausedForBackground) return
        pausedForBackground = false
        reconnect()
    }

    fun attachSurfaceView(view: View?) {
        if (view != null) attachedSurfaceView = view
    }

    /** Отвязка поверхности: только если это та же поверхность (новый экран мог уже подключить свою) */
    fun detachSurfaceView(view: View?) {
        if (attachedSurfaceView === view) attachedSurfaceView = null
    }

    /** Занята ли поверхность другим видом (при смене раскладки новый вид подключается раньше, чем уходит старый) */
    fun isSurfaceTakenByOther(view: View?): Boolean {
        val current = attachedSurfaceView
        return current != null && current !== view
    }

    /**
     * Снимок текущего кадра. Без параметров — в полном разрешении видео,
     * с maxWidth — уменьшенный с сохранением пропорций.
     */
    suspend fun captureFrame(maxWidth: Int? = null): Bitmap? {
        val surfaceView = attachedSurfaceView ?: return null
        if (!isPlaying) return null
        val size = player.videoSize
        val sourceWidth = if (size.width > 0) size.width else surfaceView.width
        val sourceHeight = if (size.height > 0) size.height else surfaceView.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val width = maxWidth?.coerceAtMost(sourceWidth) ?: sourceWidth
        val height = (sourceHeight.toLong() * width / sourceWidth).toInt()
        return SnapshotSaver.capture(surfaceView, width, height)
    }

    /** Сохраняет последний кадр в превью камеры (если трансляция идёт) */
    suspend fun saveCameraThumbnail(): Boolean {
        // Превью этой камеры ведётся по её HTTP-снимкам — кадр трансляции его не затирает
        if (camera.snapshotThumbnail &&
            com.example.denmediaipcams.widget.CameraFrameGrabber.supportsSnapshot(camera) == true &&
            ThumbnailStore.exists(appContext, ThumbnailStore.cameraKey(camera.id))
        ) return false
        val bitmap = captureFrame(ThumbnailStore.THUMBNAIL_WIDTH) ?: return false
        return try {
            val saved = ThumbnailStore.save(appContext, ThumbnailStore.cameraKey(camera.id), bitmap)
            // Виджет-список показывает это же превью камеры
            if (saved) com.example.denmediaipcams.widget.WidgetRenderer.requestUpdate(appContext)
            saved
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Начинает разговор: микрофон планшета → динамик камеры, пока не вызван [stopTalk]. Вызывающий уже получил разрешение
     * на микрофон. Звук камеры на время разговора выключается, иначе он снова попадёт в микрофон (эхо).
     */
    fun startTalk() {
        if (talkState != TalkState.IDLE) return
        val cam = camera
        if (!CameraTalkback.isSupported(cam)) return
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Нет разрешения на использование микрофона")
            return
        }
        talkState = TalkState.CONNECTING
        applyVolume()
        talkJob = scope.launch {
            try {
                TalkbackRecorder.run(cam) {
                    scope.launch {
                        if (talkState == TalkState.CONNECTING) talkState = TalkState.TALKING
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TalkbackRefusedException) {
                talkRefused = true
                toast("${e.message}. Кнопка разговора скрыта", long = true)
            } catch (e: Exception) {
                android.util.Log.w("CamNet", "StreamSession: разговор с ${cam.ipAddress} прерван: ${e.message}")
                toast(e.message ?: "Не удалось начать разговор", long = true)
            } finally {
                talkState = TalkState.IDLE
                talkJob = null
                applyVolume()
            }
        }
    }

    fun stopTalk() {
        talkJob?.cancel()
    }

    private fun applyVolume() {
        if (released) return
        player.volume = if (audioSuspended || isMuted || talkState != TalkState.IDLE) 0f else 1f
    }

    fun toggleMute() {
        isMuted = !isMuted
        applyVolume()
        // Звук запоминается для каждой камеры и восстанавливается при следующем открытии приложения
        appContext.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MUTED_PREFIX + camera.id, isMuted)
            .apply()
        // Звук включили кнопкой: если в потоке камеры его нет, камера включает его (при включённой опции камеры)
        if (!isMuted) ensureCameraAudio(force = true)
    }

    /** Звук гаснет сразу, как только камеру перестали показывать (сессия живёт ещё некоторое время), и возвращается при повторном показе */
    internal fun setAudioSuspended(suspended: Boolean) {
        audioSuspended = suspended
        applyVolume()
        // Камеру не показывают — разговор с ней не продолжается
        if (suspended) stopTalk()
    }

    /**
     * Оценка отставания показанной картинки от реального времени:
     * буфер плеера + сетевая задержка в одну сторону + кодирование/декодирование.
     */
    /** Сколько секунд видео плеер накопил и ещё не показал — реальное отставание картинки от камеры */
    fun bufferLagMs(): Long = player.totalBufferedDuration

    fun latencyEstimateMs(): Long =
        player.totalBufferedDuration + (pingMs ?: 0L) / 2 + PIPELINE_LATENCY_MS

    private fun connect() {
        android.util.Log.i("CamNet", "StreamSession.connect ${camera.ipAddress} path=${camera.rtspPath}")
        hasError = false
        errorMessage = null
        isBuffering = true
        timeoutJob?.cancel()
        lastProgressAtMs = SystemClock.elapsedRealtime()
        setCatchUpSpeed(1f)

        val transport = if (useRtpTcp) "TCP" else "UDP"
        diagnostics = diagnostics.copy(transport = transport, connectedAtMs = null)
        hasFirstFrame = false

        playingProfileId = (degradedProfile ?: camera.activeStreamProfile)?.id
        val streamUrl = currentStreamUrl()
        if (streamUrl.isBlank()) {
            showFailureWithDiagnostics("Не указан URL потока камеры ${camera.name}")
            return
        }

        try {
            if (streamUrl.startsWith("rtsp://")) {
                val factory = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(useRtpTcp)
                    // Исправляет SDP камер, передающих sprop-sps/pps со стартовым кодом (иначе ExoPlayer падает)
                    // Если камера прислала настоящие SPS/PPS, не совпадающие с SDP, переподключаемся: уже с исправленным SDP
                    .setSocketFactory(SdpFixingSocketFactory(onParamSetsLearned = { scope.launch { reconnect() } }))
                    .setDebugLoggingEnabled(true)
                player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(streamUrl)))
            } else {
                // Только RTSP поддерживается
                showFailureWithDiagnostics("Неподдерживаемый протокол для камеры ${camera.name}. Используйте RTSP URL (rtsp://...)")
                return
            }
            player.prepare()
            player.play()
            player.prepare()
            player.play()

            // Таймаут соединения: после TCP пробуем UDP, затем показываем диагностику
            timeoutJob = scope.launch {
                delay(CONNECTION_TIMEOUT_MS)
                if (isBuffering && !hasError) {
                    player.stop()
                    if (useRtpTcp) {
                        useRtpTcp = false
                        timeoutJob = null
                        connect()
                    } else if (!recordTrouble(2)) {
                        showFailureWithDiagnostics("Таймаут подключения к камере ${camera.name}")
                    }
                }
            }
        } catch (e: Exception) {
            timeoutJob?.cancel()
            showFailureWithDiagnostics("Ошибка инициализации плеера для камеры ${camera.name}: ${e.localizedMessage ?: e.message ?: "Неизвестная ошибка"}")
        }
    }

    // Показ ошибки с результатом RTSP-диагностики (DESCRIBE), чтобы было понятно, в чём причина
    private fun showFailureWithDiagnostics(baseMessage: String) {
        isBuffering = false
        hasError = true
        errorMessage = "$baseMessage\n\nДиагностика RTSP..."

        val failedCamera = camera
        scope.launch {
            val probe = NetworkTester.probeRtsp(failedCamera.fullRtspUrl, failedCamera.username, failedCamera.password)
            val details = when (probe) {
                is RtspCheckStatus.Success -> "${probe.message}\n\nКамера отвечает, но видеоданные H.265 не поступают по транспорту. Используйте кнопку «Доп. поток (H.264)» ниже на экране плеера для переключения на рабочий поток."
                is RtspCheckStatus.Error -> probe.message
                else -> ""
            }
            if (hasError) {
                errorMessage = "$baseMessage\n\n$details\n\nURL: ${failedCamera.rtspUrlWithoutCredentials}"
            }
        }
    }

    private fun startPingLoop() {
        scope.launch {
            while (true) {
                val uri = android.net.Uri.parse(camera.rtspUrlWithoutCredentials)
                val host = uri.host ?: camera.ipAddress
                val port = if (uri.port > 0) uri.port else camera.effectiveRtspPort
                val wasFailed = pingFailed
                val result = NetworkTester.tcpPingMs(host, port)
                pingMs = result ?: pingMs
                pingFailed = result == null
                // Камера снова появилась в сети, а картинка не идёт — подключаемся сразу, не дожидаясь таймаутов
                if (wasFailed && result != null && !pausedForBackground && (hasError || isBuffering || !isStreamProgressing())) {
                    autoReconnectAttempts = 0
                    autoReconnect("камера снова отвечает")
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    // Время последнего изменения позиции воспроизведения; позиция стоит — картинка не обновляется
    private var lastProgressAtMs = SystemClock.elapsedRealtime()
    private var lastPositionMs = -1L
    private var catchUpSpeed = 1f

    private fun isStreamProgressing(): Boolean =
        SystemClock.elapsedRealtime() - lastProgressAtMs < STALL_TIMEOUT_MS

    /**
     * Сторож: (1) если после успешного подключения картинка замерла (обрыв сети без закрытия сокета),
     * переподключается; (2) после ошибки повторяет попытки с нарастающим интервалом, пока камера
     * отвечает на проверку связи; (3) ускоряет воспроизведение, если в буфере накопилось отставание.
     */
    private fun startWatchdog() {
        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (pausedForBackground) continue
                val now = SystemClock.elapsedRealtime()
                val position = player.currentPosition
                if (position != lastPositionMs) {
                    lastPositionMs = position
                    lastProgressAtMs = now
                }
                isPictureFrozen = now - lastProgressAtMs >= PICTURE_FREEZE_MS
                when {
                    hasError -> {
                        if (!pingFailed && camera.fullRtspUrl.startsWith("rtsp://") &&
                            now - lastAutoReconnectAt >= autoReconnectDelayMs()
                        ) autoReconnect("повтор после ошибки")
                    }
                    // До первого подключения ждёт timeoutJob; stall отслеживаем только у состоявшегося соединения
                    diagnostics.connectedAtMs != null && now - lastProgressAtMs >= STALL_TIMEOUT_MS -> {
                        player.stop()
                        val reason = "картинка не обновляется ${(now - lastProgressAtMs) / 1000} с"
                        if (recordTrouble(2)) {
                            android.util.Log.i("CamNet", "StreamSession.switchStream ${camera.ipAddress}: $reason")
                        } else {
                            autoReconnect(reason)
                        }
                    }
                    // Связь стабилизировалась — сразу возвращаем выбранное качество
                    degradedProfile != null && isPlaying && now - lastTroubleAtMs >= recoverDelayMs &&
                        !pingFailed && (pingMs ?: Long.MAX_VALUE) <= RECOVER_PING_MAX_MS -> restoreQuality()
                    else -> updateCatchUp()
                }
            }
        }
    }

    private fun updateCatchUp() {
        if (!isPlaying) {
            setCatchUpSpeed(1f)
            return
        }
        val lag = bufferLagMs()
        val target = when {
            lag >= CATCH_UP_FAST_MS -> CATCH_UP_FAST_SPEED
            lag >= CATCH_UP_START_MS -> CATCH_UP_SPEED
            lag <= CATCH_UP_STOP_MS -> 1f
            else -> catchUpSpeed // гистерезис: между порогами скорость не меняем
        }
        setCatchUpSpeed(target)
    }

    private fun setCatchUpSpeed(speed: Float) {
        if (speed == catchUpSpeed) return
        catchUpSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(appContext, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    internal fun release() {
        released = true
        scope.cancel()
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        player.release()
    }

    private fun lowLatencyLoadControl() = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 400,
                /* maxBufferMs = */ 1_200,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 400
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
}

/**
 * Хранит сессии потоков по id камеры. Экран берёт сессию (acquire) и отдаёт (release);
 * освобождение откладывается, чтобы при смене экрана (полноэкранный режим, поворот)
 * соединение подхватилось без переподключения.
 */
class StreamSessionManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Entry(val session: StreamSession) {
        var refCount = 0
        var releaseJob: Job? = null
    }

    private val entries = mutableMapOf<String, Entry>()

    // Наблюдаемая копия: кнопки управления вне плеера (звук, снимок, инфо) перерисовываются,
    // когда сессия камеры появляется или закрывается
    private val activeSessions = mutableStateMapOf<String, StreamSession>()

    /** Сессия камеры, если её видео сейчас открыто */
    fun sessionFor(cameraId: String): StreamSession? = activeSessions[cameraId]

    /**
     * Обновляет превью всех камер, у которых сейчас идёт трансляция
     * (вызывается при сворачивании/закрытии приложения).
     */
    fun saveThumbnailsOfPlaying() {
        val playing = activeSessions.values.filter { it.isPlaying }
        // Отдельная область: сохранение должно завершиться, даже если экран уже закрывается
        thumbnailScope.launch {
            playing.forEach { it.saveCameraThumbnail() }
        }
    }

    /** Приложение свёрнуто: останавливаем воспроизведение всех открытых камер (экономия трафика/батареи) */
    fun pauseAllForBackground() {
        activeSessions.values.forEach { it.pauseForBackground() }
    }

    /** Приложение вернулось на передний план: переподключаем то, что было приостановлено */
    fun resumeAllFromBackground() {
        activeSessions.values.forEach { it.resumeFromBackground() }
    }

    private val thumbnailScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun acquire(camera: IpCamera): StreamSession {
        val entry = entries.getOrPut(camera.id) {
            Entry(StreamSession(appContext, camera)).also { activeSessions[camera.id] = it.session }
        }
        entry.releaseJob?.cancel()
        entry.releaseJob = null
        entry.refCount++
        entry.session.setAudioSuspended(false)
        entry.session.updateCamera(camera)
        return entry.session
    }

    fun release(session: StreamSession) {
        val (id, entry) = entries.entries.firstOrNull { it.value.session === session }?.toPair() ?: return
        entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
        if (entry.refCount == 0) {
            session.setAudioSuspended(true)
            entry.releaseJob = scope.launch {
                delay(RELEASE_DELAY_MS)
                if (entry.refCount == 0) {
                    entries.remove(id)
                    activeSessions.remove(id)
                    entry.session.release()
                }
            }
        }
    }

    fun releaseAll() {
        scope.cancel()
        entries.values.forEach { it.session.release() }
        entries.clear()
        activeSessions.clear()
    }

    private companion object {
        // Закрытие соединения откладывается: при смене экрана, вкладки или ориентации
        // видео подхватывается без переподключения к камере
        const val RELEASE_DELAY_MS = 10_000L
    }
}

val LocalStreamSessionManager = staticCompositionLocalOf<StreamSessionManager> {
    error("StreamSessionManager не предоставлен")
}
