package com.example.denmediaipcams.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.HipcamRecording

/** Воспроизведение записей с SD-карты Hipcam: файл .265 читается по HTTP с поддержкой Range */
@OptIn(UnstableApi::class)
object HipcamPlayback {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    fun createPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context.applicationContext)
            // Запись большая (до 100 МБ), а читается она быстрее, чем играется: держим в памяти только ближайшие секунды
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 5_000,
                        /* maxBufferMs = */ 15_000,
                        /* bufferForPlaybackMs = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 2_000
                    )
                    .build()
            )
            .build()
            .apply { playWhenReady = true }

    fun mediaSource(camera: IpCamera, recording: HipcamRecording): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        HipcamArchive.authorization(camera)?.let { http.setDefaultRequestProperties(mapOf("Authorization" to it)) }
        return ProgressiveMediaSource.Factory(http, HxvtExtractorsFactory(recording.durationMs))
            .createMediaSource(MediaItem.fromUri(HipcamArchive.fileUrl(camera, recording)))
    }
}
