package com.example.denmediaipcams.ui.components

import androidx.compose.runtime.Composable
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HikvisionRecording
import com.example.denmediaipcams.player.HikvisionPlayback
import com.example.denmediaipcams.player.HikvisionThumbnails
import java.time.LocalDateTime

/**
 * Архив SD-карты камеры Hikvision (ISAPI): запись воспроизводится по RTSP-адресу из поиска (см. [HikvisionPlayer]).
 * Скачивать и удалять записи приложение у такой камеры не умеет, поэтому этих кнопок в окне нет.
 * Само окно общее для всех камер ([ArchiveDialog]).
 */
@Composable
fun HikvisionArchiveDialog(camera: IpCamera, initialStart: LocalDateTime? = null, onDismiss: () -> Unit) {
    ArchiveDialog(camera = camera, initialStart = initialStart, protocol = hikvisionProtocol, onDismiss = onDismiss)
}

private val hikvisionProtocol = ArchiveProtocol<HikvisionRecording>(
    cacheProtocol = "hikvision",
    listDays = { HikvisionArchive.listDays(it) },
    listRecordings = { camera, day -> HikvisionArchive.listRecordings(camera, day) },
    start = { it.start },
    end = { it.end },
    same = { a, b -> a.startUtc == b.startUtc },
    details = { recording ->
        buildList {
            if (recording.sizeBytes > 0) add(formatArchiveSize(recording.sizeBytes))
            recording.kind?.takeIf { it != "timing" }?.let { add("тип $it") }
        }.joinToString(" · ")
    },
    cachedThumbnail = { context, camera, recording -> HikvisionThumbnails.cached(context, camera, recording) },
    loadThumbnail = { context, camera, recording -> HikvisionThumbnails.load(context, camera, recording) },
    openPlayback = { context, camera, recording, onEnded ->
        val player = HikvisionPlayback.createPlayer(context)
        val session = Media3ArchiveSession(player, onEnded)
        player.open(camera, recording)
        session
    },
    // Превью — отдельные RTSP-сеансы, а камера держит их немного: пока идёт просмотр, новые не строятся
    thumbnailsWhilePlaying = false
)
