package com.example.denmediaipcams.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.player.LocalStreamSessionManager
import com.example.denmediaipcams.player.StreamSession
import com.example.denmediaipcams.ui.theme.ErrorRed
import kotlinx.coroutines.delay
import com.example.denmediaipcams.ui.theme.OnlineGreen
import com.example.denmediaipcams.ui.theme.SignalYellow

/**
 * Статус камеры: зелёная точка «Онлайн» или серая «Не в сети».
 * @param isChecked проверка уже выполнялась (до первой проверки статус неизвестен)
 */
@Composable
internal fun CameraOnlineStatus(isOnline: Boolean, isChecked: Boolean, modifier: Modifier = Modifier) {
    val color = if (isOnline) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = when {
                !isChecked -> "Проверка..."
                isOnline -> "Онлайн"
                else -> "Не в сети"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * Уровень сигнала камеры: чем меньше задержка, тем больше полосок. Учитываются две задержки:
 * отклик порта камеры (пинг) и отставание видео от реального времени (накопившийся буфер плеера) —
 * у камеры с быстрым откликом картинка всё равно может отставать на минуту.
 */
internal enum class SignalLevel(val description: String) {
    NONE("Нет сигнала"),
    POOR("Сигнал слабый"),
    FAIR("Сигнал средний"),
    GOOD("Сигнал хороший");

    companion object {
        private const val PING_GOOD_MAX_MS = 150L
        private const val PING_FAIR_MAX_MS = 400L

        // В норме плеер держит в буфере до ~2 с (см. lowLatencyLoadControl)
        private const val LAG_GOOD_MAX_MS = 2_500L
        private const val LAG_FAIR_MAX_MS = 6_000L

        /** [pingMs] — null, если камера не отвечает; [streamLagMs] — null, если видео сейчас не идёт */
        fun of(pingMs: Long?, streamLagMs: Long? = null): SignalLevel {
            if (pingMs == null) return NONE
            val byPing = when {
                pingMs <= PING_GOOD_MAX_MS -> GOOD
                pingMs <= PING_FAIR_MAX_MS -> FAIR
                else -> POOR
            }
            val byLag = when {
                streamLagMs == null || streamLagMs <= LAG_GOOD_MAX_MS -> GOOD
                streamLagMs <= LAG_FAIR_MAX_MS -> FAIR
                else -> POOR
            }
            return if (byLag.ordinal < byPing.ordinal) byLag else byPing
        }
    }
}

/** Отставание видео камеры от реального времени, мс; null — видео сейчас не идёт. Обновляется раз в секунду */
@Composable
internal fun rememberStreamLagMs(session: StreamSession?): Long? {
    val lag by produceState<Long?>(null, session) {
        while (true) {
            value = session?.takeIf { it.isPlaying }?.bufferLagMs()
            delay(1_000)
        }
    }
    return lag
}

/** Значок и цвет уровня сигнала: красная 1 полоска, жёлтые 2, зелёные 3; [noSignalTint] — цвет значка «нет сигнала» */
internal fun SignalLevel.iconAndTint(noSignalTint: Color): Pair<ImageVector, Color> = when (this) {
    SignalLevel.NONE -> Icons.Default.SignalCellularAlt to noSignalTint
    SignalLevel.POOR -> Icons.Default.SignalCellularAlt1Bar to ErrorRed
    SignalLevel.FAIR -> Icons.Default.SignalCellularAlt2Bar to SignalYellow
    SignalLevel.GOOD -> Icons.Default.SignalCellularAlt to OnlineGreen
}

/** Значок уровня сигнала: серый — нет сигнала, красная 1 полоска, жёлтые 2, зелёные 3 */
@Composable
internal fun SignalLevelIcon(cameraId: String, pingMs: Long?, modifier: Modifier = Modifier) {
    val session = LocalStreamSessionManager.current.sessionFor(cameraId)
    val streamLagMs = rememberStreamLagMs(session)
    val level = SignalLevel.of(pingMs, streamLagMs)
    val latencyMs = streamLagMs ?: pingMs
    val (icon, tint) = level.iconAndTint(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    Icon(
        imageVector = icon,
        contentDescription = level.description + (latencyMs?.let { " ($it мс)" } ?: ""),
        tint = tint,
        modifier = modifier
    )
}

/** «4 камеры · 3 онлайн» */
internal fun camerasSummary(total: Int, online: Int): String {
    val word = when {
        total % 100 in 11..14 -> "камер"
        total % 10 == 1 -> "камера"
        total % 10 in 2..4 -> "камеры"
        else -> "камер"
    }
    return if (total == 0) "Нет камер" else "$total $word · $online онлайн"
}
