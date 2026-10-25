package com.example.denmediaipcams.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.common.ForwardingPlayer
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HikvisionRecording
import com.example.denmediaipcams.network.SdpFixingSocketFactory

/** Воспроизведение записей с SD-карты Hikvision: RTSP-адрес записи из поиска ISAPI, перемотка перезапуском потока */
@OptIn(UnstableApi::class)
object HikvisionPlayback {

    /** [builder] подменяется в тестах (например, чтобы исключить негодный декодер эмулятора) */
    fun createPlayer(
        context: Context,
        builder: ExoPlayer.Builder = ExoPlayer.Builder(context.applicationContext)
    ): HikvisionPlayer = HikvisionPlayer(builder.build())
}

/**
 * Плеер записи Hikvision. Камера в ответе на PLAY не сообщает длительность (заголовка Range нет), поэтому Media3
 * считает поток «прямым эфиром» без перемотки. Длительность записи известна из поиска, а камера начинает показ с любой
 * секунды записи (параметр starttime адреса) — поэтому плеер выдаёт наружу время внутри записи и сам перезапускает
 * поток при перемотке; стандартные элементы управления Media3 работают как у остальных камер.
 * Все методы вызываются с главного потока.
 */
@OptIn(UnstableApi::class)
class HikvisionPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {

    private var camera: IpCamera? = null
    private var recording: HikvisionRecording? = null

    // Позиция внутри записи, с которой начат текущий поток: положение плеера отсчитывается от неё
    private var baseMs = 0L
    private var durationMs = C.TIME_UNSET
    private var timeline: Timeline = Timeline.EMPTY

    init {
        exo.playWhenReady = true
    }

    /** Открывает запись с начала */
    fun open(camera: IpCamera, recording: HikvisionRecording) {
        this.camera = camera
        this.recording = recording
        durationMs = recording.durationMs.coerceAtLeast(1)
        timeline = SinglePeriodTimeline(
            /* durationUs = */ durationMs * 1_000,
            /* isSeekable = */ true,
            /* isDynamic = */ false,
            /* useLiveConfiguration = */ false,
            /* manifest = */ null,
            MediaItem.fromUri(recording.playbackPath)
        )
        startAt(0)
    }

    private fun startAt(positionMs: Long) {
        val camera = camera ?: return
        val recording = recording ?: return
        baseMs = positionMs.coerceIn(0, durationMs)
        val factory = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            // Исправляет RTP-Info и SDP камеры (см. SdpFixingSocketFactory): без этого кадры приходят «из будущего»
            .setSocketFactory(SdpFixingSocketFactory())
        exo.setMediaSource(factory.createMediaSource(MediaItem.fromUri(HikvisionArchive.playbackUrl(camera, recording, baseMs))))
        exo.prepare()
        exo.play()
    }

    override fun getDuration(): Long = durationMs

    override fun getContentDuration(): Long = durationMs

    override fun getCurrentPosition(): Long = (baseMs + exo.currentPosition).coerceAtMost(durationMs)

    override fun getContentPosition(): Long = currentPosition

    override fun getBufferedPosition(): Long = (baseMs + exo.bufferedPosition).coerceAtMost(durationMs)

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun getCurrentTimeline(): Timeline = timeline

    override fun isCurrentMediaItemLive(): Boolean = false

    override fun isCurrentMediaItemDynamic(): Boolean = false

    override fun isCurrentMediaItemSeekable(): Boolean = true

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addAll(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_TIMELINE
        )
        .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekTo(positionMs: Long) = startAt(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = startAt(positionMs)

    override fun seekToDefaultPosition() = startAt(0)

    override fun seekToDefaultPosition(mediaItemIndex: Int) = startAt(0)

    override fun seekBack() = startAt(currentPosition - seekBackIncrement)

    override fun seekForward() = startAt(currentPosition + seekForwardIncrement)

    /** После конца записи камера новый PLAY в той же сессии не принимает: «воспроизвести» начинает запись заново */
    override fun play() {
        if (exo.playbackState == Player.STATE_ENDED) startAt(0) else exo.play()
    }
}
