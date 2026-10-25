package com.example.denmediaipcams.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Круглый джойстик поворота камеры:
 * - центральную ручку можно тянуть в любую сторону — скорость пропорциональна отклонению;
 * - стрелки по краям работают по принципу «нажал и держи»;
 * - при отпускании камера получает Stop, ручка пружинит в центр.
 */
@Composable
fun PtzJoystick(
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    onMove: (pan: Float, tilt: Float) -> Unit,
    onStop: () -> Unit
) {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStop by rememberUpdatedState(onStop)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val knobSize = size * 0.34f
    val maxRadiusPx = with(density) { (size / 2 - knobSize / 2 - 6.dp).toPx() }

    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var lastPan by remember { mutableStateOf(0f) }
    var lastTilt by remember { mutableStateOf(0f) }

    fun releaseKnob() {
        isDragging = false
        if (lastPan != 0f || lastTilt != 0f) currentOnStop()
        lastPan = 0f
        lastTilt = 0f
        val start = knobOffset
        scope.launch {
            animate(1f, 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { fraction, _ ->
                knobOffset = start * fraction
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .pointerInput(maxRadiusPx) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDragEnd = { releaseKnob() },
                    onDragCancel = { releaseKnob() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val next = knobOffset + dragAmount
                        val distance = sqrt(next.x * next.x + next.y * next.y)
                        knobOffset = if (distance > maxRadiusPx) {
                            val angle = atan2(next.y, next.x)
                            Offset(cos(angle) * maxRadiusPx, sin(angle) * maxRadiusPx)
                        } else next

                        val pan = quantizePtzLevel(knobOffset.x / maxRadiusPx)
                        // Ось Y экрана направлена вниз: ручка вверх — наклон вверх
                        val tilt = quantizePtzLevel(-knobOffset.y / maxRadiusPx)
                        if (pan != lastPan || tilt != lastTilt) {
                            lastPan = pan
                            lastTilt = tilt
                            if (pan == 0f && tilt == 0f) currentOnStop() else currentOnMove(pan, tilt)
                        }
                    }
                )
            }
    ) {
        // Стрелки по краям: удержание — непрерывный поворот
        JoystickArrow(Icons.Default.KeyboardArrowUp, "Наклон вверх", Alignment.TopCenter, 0f, 1f, currentOnMove, currentOnStop)
        JoystickArrow(Icons.Default.KeyboardArrowDown, "Наклон вниз", Alignment.BottomCenter, 0f, -1f, currentOnMove, currentOnStop)
        JoystickArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Поворот влево", Alignment.CenterStart, -1f, 0f, currentOnMove, currentOnStop)
        JoystickArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Поворот вправо", Alignment.CenterEnd, 1f, 0f, currentOnMove, currentOnStop)

        // Центральная ручка
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(knobSize)
                .shadow(if (isDragging) 10.dp else 6.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        if (isDragging) {
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            listOf(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerHighest)
                        }
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.JoystickArrow(
    icon: ImageVector,
    description: String,
    alignment: Alignment,
    pan: Float,
    tilt: Float,
    onMove: (Float, Float) -> Unit,
    onStop: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(alignment)
            .padding(4.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent)
            .pointerInput(pan, tilt) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onMove(pan, tilt)
                        tryAwaitRelease()
                        pressed = false
                        onStop()
                    }
                )
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize(0.75f)
        )
    }
}
