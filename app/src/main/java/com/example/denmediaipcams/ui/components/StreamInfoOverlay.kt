package com.example.denmediaipcams.ui.components

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.example.denmediaipcams.network.CameraDetails
import com.example.denmediaipcams.network.OnvifDetails
import com.example.denmediaipcams.network.OnvifStreamInfo
import com.example.denmediaipcams.player.StreamSession
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Техническая информация о камере и потоке поверх видео: белый текст на полупрозрачном чёрном фоне.
 * Скорость потока и частота кадров обновляются раз в секунду, пинг — раз в 5 секунд (в сессии).
 */
@OptIn(UnstableApi::class)
@Composable
fun StreamInfoOverlay(
    session: StreamSession,
    modifier: Modifier = Modifier
) {
    val camera = session.camera
    val exoPlayer = session.player
    val diagnostics = session.diagnostics
    val pingMs = session.pingMs
    val pingFailed = session.pingFailed

    // Сведения об устройстве и заявленные камерой параметры потока (ONVIF): запрашиваются один раз при открытии панели
    val onvif by produceState<OnvifDetails?>(null, camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password) {
        value = CameraDetails.onvifDetails(camera)
    }

    var tick by remember { mutableIntStateOf(0) }
    var rxKbps by remember { mutableStateOf<Double?>(null) }
    var fps by remember { mutableStateOf<Double?>(null) }

    val rtspUri = remember(camera.rtspUrlWithoutCredentials) { android.net.Uri.parse(camera.rtspUrlWithoutCredentials) }
    val host = rtspUri.host ?: camera.ipAddress
    val port = if (rtspUri.port > 0) rtspUri.port else camera.effectiveRtspPort

    // Скорость входящего трафика и реальная частота кадров
    LaunchedEffect(exoPlayer) {
        val uid = Process.myUid()
        var lastBytes = TrafficStats.getUidRxBytes(uid)
        var lastFrames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val seconds = (now - lastTime) / 1000.0
            val bytes = TrafficStats.getUidRxBytes(uid)
            if (bytes != TrafficStats.UNSUPPORTED.toLong() && lastBytes != TrafficStats.UNSUPPORTED.toLong()) {
                rxKbps = (bytes - lastBytes) * 8 / 1000.0 / seconds
            }
            val frames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            fps = ((frames - lastFrames).coerceAtLeast(0)) / seconds
            lastBytes = bytes
            lastFrames = frames
            lastTime = now
            tick++
        }
    }

    // Чтение tick подписывает панель на ежесекундную перерисовку — состояние плеера перечитывается
    val videoFormat = tick.let { exoPlayer.videoFormat }
    val audioFormat = exoPlayer.audioFormat
    val videoSize = exoPlayer.videoSize

    val lines = buildList {
        add(Section("КАМЕРА"))
        add(Line("Название", camera.name))
        add(Line("Адрес", "$host:$port"))
        add(Line("RTSP", camera.rtspUrlWithoutCredentials))
        add(Line("PTZ", camera.protocol.displayName))
        add(Line("Пинг (TCP)", when {
            pingFailed -> "нет ответа"
            pingMs != null -> "$pingMs мс"
            else -> "…"
        }))
        onvif?.device?.let { device ->
            val model = listOfNotNull(device.manufacturer, device.model).joinToString(" ")
            if (model.isNotBlank()) add(Line("Модель", model))
            device.firmware?.let { add(Line("Прошивка", it)) }
            device.serial?.let { add(Line("Серийный №", it)) }
            device.mac?.let { add(Line("MAC", it)) }
            device.dhcp?.let { add(Line("Адрес выдан", if (it) "DHCP" else "вручную")) }
        }

        add(Section("ПОТОК"))
        add(Line("Транспорт", "RTP/RTSP по ${diagnostics.transport}"))
        add(Line("Скорость", rxKbps?.let { formatBitrate(it) } ?: "…"))
        add(Line("Буфер", "${exoPlayer.totalBufferedDuration} мс"))
        add(Line("Задержка", "≈ ${session.latencyEstimateMs()} мс (оценка)"))
        add(Line("Подключено", diagnostics.connectedAtMs?.let { formatDuration(SystemClock.elapsedRealtime() - it) } ?: "—"))

        onvifStreamFor(camera.activeStreamProfile?.rtspPath ?: camera.rtspPath, onvif)?.let { stream ->
            add(Section("ПОТОК ПО ДАННЫМ КАМЕРЫ (ONVIF)"))
            stream.name?.let { add(Line("Профиль", it)) }
            val size = if (stream.width > 0 && stream.height > 0) "${stream.width}×${stream.height}" else null
            add(Line("Заявлено", listOfNotNull(stream.codec, size, stream.fps?.let { "$it к/с" }).joinToString(", ").ifBlank { "нет данных" }))
            stream.bitrateKbps?.let { add(Line("Битрейт до", formatBitrate(it.toDouble()))) }
            stream.gop?.let { add(Line("Интервал I-кадров", "$it кадров")) }
        }

        add(Section("ВИДЕО"))
        if (videoFormat != null) {
            add(Line("Кодек", codecName(videoFormat)))
            val width = if (videoSize.width > 0) videoSize.width else videoFormat.width
            val height = if (videoSize.height > 0) videoSize.height else videoFormat.height
            if (width > 0 && height > 0) add(Line("Разрешение", "${width}×$height (${resolutionLabel(height)})"))
            add(Line("Кадров/с", buildString {
                append(fps?.let { String.format(Locale.US, "%.1f", it) } ?: "…")
                if (videoFormat.frameRate > 0) append(String.format(Locale.US, " (заявлено %.0f)", videoFormat.frameRate))
            }))
            if (videoFormat.bitrate > 0) add(Line("Битрейт (SDP)", formatBitrate(videoFormat.bitrate / 1000.0)))
            add(Line("Пропущено кадров", diagnostics.droppedFrames.toString()))
            diagnostics.videoDecoderName?.let { add(Line("Декодер", "$it (${decoderKind(it)})")) }
        } else {
            add(Line("Кодек", "нет данных"))
        }

        add(Section("АУДИО"))
        if (audioFormat != null) {
            add(Line("Кодек", codecName(audioFormat)))
            if (audioFormat.sampleRate > 0) add(Line("Дискретизация", "${audioFormat.sampleRate} Гц"))
            if (audioFormat.channelCount > 0) add(Line("Каналы", if (audioFormat.channelCount == 1) "моно" else "${audioFormat.channelCount}"))
            diagnostics.audioDecoderName?.let { add(Line("Декодер", it)) }
        } else {
            add(Line("Звук", "нет аудиодорожки"))
        }
    }

    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.widthIn(max = 340.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScrollWithBar()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            lines.forEachIndexed { index, item ->
                when (item) {
                    is Section -> {
                        if (index > 0) Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.title,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    is Line -> Text(
                        text = "${item.label}: ${item.value}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

/** Профиль ONVIF, чей RTSP-адрес совпадает с играющим потоком (сравниваются путь и параметры) */
private fun onvifStreamFor(activePath: String, details: OnvifDetails?): OnvifStreamInfo? {
    val active = activePath.trim().trimStart('/')
    return details?.streams?.firstOrNull { it.rtspPath?.trimStart('/') == active }
}

private sealed interface InfoItem
private data class Section(val title: String) : InfoItem
private data class Line(val label: String, val value: String) : InfoItem

@OptIn(UnstableApi::class)
private fun codecName(format: Format): String {
    val name = when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264 (AVC)"
        MimeTypes.VIDEO_H265 -> "H.265 (HEVC)"
        MimeTypes.VIDEO_MJPEG -> "MJPEG"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.AUDIO_ALAW -> "G.711 A-law (PCMA)"
        MimeTypes.AUDIO_MLAW -> "G.711 μ-law (PCMU)"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_AMR_NB -> "AMR-NB"
        MimeTypes.AUDIO_AMR_WB -> "AMR-WB"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> format.sampleMimeType ?: "неизвестно"
    }
    return format.codecs?.let { "$name, $it" } ?: name
}

private fun resolutionLabel(height: Int) = when {
    height >= 2160 -> "4K"
    height >= 1520 -> "4 Мп"
    height >= 1440 -> "2K"
    height >= 1080 -> "Full HD"
    height >= 720 -> "HD"
    else -> "SD"
}

/** Аппаратные декодеры Android называются по производителю чипа, программные — c2.android / OMX.google */
private fun decoderKind(name: String) =
    if (name.startsWith("c2.android.") || name.startsWith("OMX.google.")) "программный" else "аппаратный"

private fun formatBitrate(kbps: Double): String =
    if (kbps >= 1000) String.format(Locale.US, "%.2f Мбит/с", kbps / 1000) else String.format(Locale.US, "%.0f кбит/с", kbps)

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
