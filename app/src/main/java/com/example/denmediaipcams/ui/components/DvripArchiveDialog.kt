package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.denmediaipcams.SecurityNoticeNotifier
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.network.DvripRecording
import com.example.denmediaipcams.player.DvripMp4Converter
import com.example.denmediaipcams.player.DvripStreamPlayer
import com.example.denmediaipcams.player.DvripThumbnails
import com.example.denmediaipcams.ui.theme.SignalYellow
import java.time.LocalDateTime

/** Текст предупреждения о протоколе — показывается баннером в диалоге и уведомлением (см. SecurityNoticeNotifier) */
private const val DVRIP_WARNING_TEXT = "⚠ Протокол XMEye/DVRIP (порт 34567) — устаревший и небезопасный: " +
    "пароль передаётся в слабо хэшированном виде, известны уязвимости и случаи зависания/перезагрузки камеры " +
    "от некорректных запросов. Используется на ваш риск."

/**
 * Архив записей камеры по протоколу XMEye/DVRIP: запись идёт потоком с камеры и играет почти сразу
 * (см. [DvripStreamPlayer]); скачивание — в MP4. Само окно общее для всех камер ([ArchiveDialog]),
 * здесь только баннер о риске протокола.
 */
@Composable
fun DvripArchiveDialog(camera: IpCamera, initialStart: LocalDateTime? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Баннер показывается один раз за всё время работы приложения (см. SecurityNoticeNotifier.takeBannerTurn)
    var showWarning by rememberSaveable { mutableStateOf(SecurityNoticeNotifier.takeBannerTurn(context.applicationContext)) }
    ArchiveDialog(
        camera = camera,
        initialStart = initialStart,
        protocol = dvripProtocol,
        onDismiss = onDismiss,
        topContent = { if (showWarning) DvripWarningBanner(onDismiss = { showWarning = false }) }
    )
}

private val dvripProtocol = ArchiveProtocol<DvripRecording>(
    cacheProtocol = "dvrip",
    listDays = { DvripArchive.listDays(it) },
    listRecordings = { camera, day -> DvripArchive.listRecordings(camera, day) },
    start = { it.start },
    end = { it.end },
    same = { a, b -> a.fileName == b.fileName },
    details = { formatArchiveSize(it.sizeBytes) },
    cachedThumbnail = { context, camera, recording -> DvripThumbnails.cached(context, camera, recording) },
    loadThumbnail = { context, camera, recording -> DvripThumbnails.load(context, camera, recording) },
    openPlayback = { context, camera, recording, onEnded ->
        // Запись идёт потоком с камеры и играет почти сразу; перемотка перезапускает поток с нужной секунды
        val stream = DvripStreamPlayer(context, camera, recording)
        stream.onEnded = onEnded
        val session = DvripArchiveSession(stream)
        stream.start(0)
        session
    },
    download = ArchiveDownloader(
        extension = "mp4",
        mimeType = "video/mp4",
        label = "Скачать запись в MP4",
        write = { context, camera, recording, out, onProgress ->
            DvripMp4Converter.convert(camera, recording, context.cacheDir, out, onProgress)
        }
    ),
    // Успешный вход — повод показать разовое уведомление о риске протокола (только если оно ещё ни разу не показывалось)
    onDaysListed = { SecurityNoticeNotifier.noticeFirstSuccess(it) }
)

/** Запись XMEye/DVRIP как сеанс окна архива: картинка из ExoPlayer потока, управление — свои методы [DvripStreamPlayer] */
private class DvripArchiveSession(private val stream: DvripStreamPlayer) : ArchiveSession {

    override val player: Player get() = stream.exo

    override val playback: ArchivePlayback = object : ArchivePlayback {
        override val isPlaying: Boolean get() = stream.isPlaying
        override val isBuffering: Boolean get() = stream.isBuffering
        override val error: String? get() = stream.error
        override val durationMs: Long get() = stream.durationMs
        override fun positionMs(): Long = stream.positionMs()
        override fun play() = stream.play()
        override fun pause() = stream.pause()
        override fun seekTo(positionMs: Long) = stream.seekTo(positionMs)
    }

    override fun setVolume(volume: Float) = stream.setVolume(volume)

    override fun release() = stream.release()
}

/** Баннер риска протокола: показывается один раз; виден, пока открыт диалог (или пока не скрыт кнопкой) */
@Composable
private fun DvripWarningBanner(onDismiss: () -> Unit) {
    Surface(color = SignalYellow.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = SignalYellow)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                DVRIP_WARNING_TEXT,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Скрыть предупреждение", modifier = Modifier.size(18.dp))
            }
        }
    }
}
