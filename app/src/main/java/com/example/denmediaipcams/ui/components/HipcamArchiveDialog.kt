package com.example.denmediaipcams.ui.components

import androidx.compose.runtime.Composable
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.HipcamRecording
import com.example.denmediaipcams.player.HipcamPlayback
import com.example.denmediaipcams.player.HipcamThumbnails
import com.example.denmediaipcams.player.HxvtMp4Converter
import java.time.LocalDateTime

/**
 * Архив SD-карты камеры Hipcam: .265 читается с камеры по HTTP (см. [HipcamPlayback]); скачивание — в MP4.
 * Само окно общее для всех камер ([ArchiveDialog]).
 */
@Composable
fun HipcamArchiveDialog(camera: IpCamera, initialStart: LocalDateTime? = null, onDismiss: () -> Unit) {
    ArchiveDialog(camera = camera, initialStart = initialStart, protocol = hipcamProtocol, onDismiss = onDismiss)
}

private val hipcamProtocol = ArchiveProtocol<HipcamRecording>(
    cacheProtocol = "hipcam",
    listDays = { HipcamArchive.listDays(it) },
    listRecordings = { camera, day -> HipcamArchive.listRecordings(camera, day) },
    start = { it.start },
    end = { it.end },
    same = { a, b -> a.path == b.path },
    details = { recording ->
        buildList {
            add(formatArchiveSize(recording.sizeBytes))
            if (recording.kind != 'P') add("тип ${recording.kind}")
        }.joinToString(" · ")
    },
    cachedThumbnail = { context, camera, recording -> HipcamThumbnails.cached(context, camera, recording) },
    loadThumbnail = { context, camera, recording -> HipcamThumbnails.load(context, camera, recording) },
    openPlayback = { context, camera, recording, onEnded ->
        val player = HipcamPlayback.createPlayer(context)
        val session = Media3ArchiveSession(player, onEnded)
        player.setMediaSource(HipcamPlayback.mediaSource(camera, recording))
        player.prepare()
        player.play()
        session
    },
    // Запись читается с камеры и сразу собирается в MP4
    download = ArchiveDownloader(
        extension = "mp4",
        mimeType = "video/mp4",
        label = "Скачать запись в MP4",
        write = { context, camera, recording, out, onProgress ->
            HxvtMp4Converter.convert(camera, recording, context.cacheDir, out, onProgress)
        }
    )
)
