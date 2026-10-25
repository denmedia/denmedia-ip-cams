package com.example.denmediaipcams.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.network.RecordingIndicator
import com.example.denmediaipcams.ui.theme.ErrorRed
import com.example.denmediaipcams.ui.theme.OnlineGreen

private val DotSize = 10.dp
private val LoaderDotSize = 5.dp
private val LoaderGap = 3.dp
private val IdleGrey = Color(0xFF9AA4B8)

private const val PULSE_MS = 1_400
private const val LOADER_CYCLE_MS = 1_200

// Задержка каждой следующей точки загрузчика, доля цикла: точки гаснут и загораются по очереди
private const val LOADER_STAGGER = 0.2f

/**
 * Точка записи на SD-карту поверх видео: красная — камера пишет сейчас, зелёная — не пишет, но включена детекция
 * движения или людей, серая — остальное. Красная и зелёная едва заметно пульсируют, как «REC»; когда связи с камерой
 * нет — три серые точки, прозрачность которых меняется по очереди, как у индикатора загрузки.
 */
@Composable
fun RecordingIndicatorDot(indicator: RecordingIndicator, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(24.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = indicator.description }
    ) {
        // Один размер для всех состояний: плашка не прыгает, когда точка превращается в три
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = LoaderDotSize * 3 + LoaderGap * 2, height = DotSize)
        ) {
            when (indicator) {
                RecordingIndicator.RECORDING -> PulsingDot(ErrorRed)
                RecordingIndicator.STANDBY -> PulsingDot(OnlineGreen)
                RecordingIndicator.IDLE -> Box(Modifier.size(DotSize).background(IdleGrey, CircleShape))
                RecordingIndicator.LOST -> LoaderDots()
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "recordingPulse")
    // Малозаметно: прозрачность и размер меняются всего на четверть и на десятую часть
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "recordingPulsePhase"
    )
    Box(
        Modifier
            .size(DotSize)
            .scale(1f - 0.1f * phase)
            .alpha(1f - 0.25f * phase)
            .background(color, CircleShape)
    )
}

@Composable
private fun LoaderDots() {
    val transition = rememberInfiniteTransition(label = "recordingLoader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOADER_CYCLE_MS, easing = LinearEasing)),
        label = "recordingLoaderPhase"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(LoaderGap), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            Box(
                Modifier
                    .size(LoaderDotSize)
                    .alpha(loaderAlpha(phase, index))
                    .background(IdleGrey, CircleShape)
            )
        }
    }
}

/** Прозрачность точки [index] загрузчика при положении цикла [phase] (0..1): плавно туда и обратно, со сдвигом по очереди */
internal fun loaderAlpha(phase: Float, index: Int): Float {
    val t = ((phase - index * LOADER_STAGGER) % 1f + 1f) % 1f
    val wave = if (t < 0.5f) t * 2f else (1f - t) * 2f
    return 0.2f + 0.8f * wave
}

private val RecordingIndicator.description: String
    get() = when (this) {
        RecordingIndicator.RECORDING -> "Идёт запись на SD-карту"
        RecordingIndicator.STANDBY -> "Запись по детекции движения или людей"
        RecordingIndicator.IDLE -> "Запись не ведётся"
        RecordingIndicator.LOST -> "Нет связи с камерой"
    }
