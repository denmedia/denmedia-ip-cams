package com.example.denmediaipcams.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Шаг квантования скорости: команда уходит только при смене ступени, а не на каждый пиксель */
private const val PTZ_LEVEL_STEP = 0.25f

internal fun quantizePtzLevel(value: Float): Float =
    ((value.coerceIn(-1f, 1f) / PTZ_LEVEL_STEP).roundToInt() * PTZ_LEVEL_STEP)

/** Зум: качелька влево — отдалить, вправо — приблизить; кнопки по краям — «нажал и держи» */
@Composable
fun ZoomControl(
    onZoomChange: (zoomLevel: Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    RockerRow(
        title = null,
        rockerDescription = "Качелька зума",
        leftIcon = Icons.Default.ZoomOut,
        leftDescription = "Отдалить (удерживайте)",
        rightIcon = Icons.Default.ZoomIn,
        rightDescription = "Приблизить (удерживайте)",
        leftLabel = "−",
        rightLabel = "+",
        onChange = onZoomChange,
        onStop = onStop,
        modifier = modifier
    )
}

/** Зум кнопками «нажал и держи»: «+» над «−», рядом с джойстиком поворота */
@Composable
fun ZoomButtons(
    onZoomChange: (zoomLevel: Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        HoldButton(
            icon = { Icon(Icons.Default.ZoomIn, contentDescription = "Приблизить (удерживайте)") },
            onPress = { onZoomChange(1f) },
            onRelease = onStop
        )
        HoldButton(
            icon = { Icon(Icons.Default.ZoomOut, contentDescription = "Отдалить (удерживайте)") },
            onPress = { onZoomChange(-1f) },
            onRelease = onStop
        )
    }
}

/** Фокус: влево — ближе, вправо — дальше, короткий свайп ползунка вниз — автофокус */
@Composable
fun FocusControl(
    onFocusMove: (speed: Float) -> Unit,
    onFocusStop: () -> Unit,
    onAutoFocus: () -> Unit,
    modifier: Modifier = Modifier,
    /** Только кнопки «ближе / дальше», без ползунка и подсказки (смартфон в альбомной ориентации) */
    buttonsOnly: Boolean = false
) {
    if (buttonsOnly) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.fillMaxWidth()
        ) {
            HoldButton(
                icon = { Icon(Icons.Default.CenterFocusWeak, contentDescription = "Фокус ближе (удерживайте)") },
                onPress = { onFocusMove(-1f) },
                onRelease = onFocusStop
            )
            Text("ближе", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("дальше", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HoldButton(
                icon = { Icon(Icons.Default.CenterFocusStrong, contentDescription = "Фокус дальше (удерживайте)") },
                onPress = { onFocusMove(1f) },
                onRelease = onFocusStop
            )
        }
        return
    }
    RockerRow(
        title = null,
        rockerDescription = "Качелька фокуса",
        leftIcon = Icons.Default.CenterFocusWeak,
        leftDescription = "Фокус ближе (удерживайте)",
        rightIcon = Icons.Default.CenterFocusStrong,
        rightDescription = "Фокус дальше (удерживайте)",
        leftLabel = "ближе",
        rightLabel = "дальше",
        onChange = onFocusMove,
        onStop = onFocusStop,
        onSwipeDown = onAutoFocus,
        swipeDownLabel = "▼ свайп вниз — автофокус",
        modifier = modifier
    )
}

/**
 * Ряд управления: кнопка «удержание» — качелька — кнопка «удержание».
 * @param title заголовок над рядом; null — без заголовка (его даёт карточка)
 */
@Composable
private fun RockerRow(
    title: String?,
    rockerDescription: String,
    leftIcon: ImageVector,
    leftDescription: String,
    rightIcon: ImageVector,
    rightDescription: String,
    leftLabel: String,
    rightLabel: String,
    onChange: (Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onSwipeDown: (() -> Unit)? = null,
    swipeDownLabel: String? = null
) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HoldButton(
                icon = { Icon(leftIcon, contentDescription = leftDescription) },
                onPress = { onChange(-1f) },
                onRelease = onStop
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                PtzRocker(
                    leftLabel = leftLabel,
                    rightLabel = rightLabel,
                    onChange = onChange,
                    onStop = onStop,
                    onSwipeDown = onSwipeDown,
                    contentDescription = rockerDescription,
                    modifier = Modifier.fillMaxWidth()
                )
                // Подсказка свайпа вниз — под качелькой, чтобы её не закрывал ползунок
                if (swipeDownLabel != null) {
                    Text(
                        text = swipeDownLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            HoldButton(
                icon = { Icon(rightIcon, contentDescription = rightDescription) },
                onPress = { onChange(1f) },
                onRelease = onStop
            )
        }
    }
}

/**
 * Качелька: ползунок пружинит к центру. Отклонение влево — отрицательная скорость, вправо — положительная,
 * скорость пропорциональна отклонению. При отпускании пальца камера сразу получает Stop.
 * Если задан onSwipeDown — короткий сдвиг ползунка вниз (без бокового отклонения) вызывает его один раз.
 */
@Composable
private fun PtzRocker(
    leftLabel: String,
    rightLabel: String,
    onChange: (Float) -> Unit,
    onStop: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onSwipeDown: (() -> Unit)? = null
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val knobSize = 44.dp
    val knobSizePx = with(density) { knobSize.toPx() }
    // Насколько ползунок визуально уходит вниз и какой свайп вниз считается командой
    val maxDownPx = with(density) { 8.dp.toPx() }
    val swipeDownThresholdPx = with(density) { 24.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var returnJobs by remember { mutableStateOf<List<Job>>(emptyList()) }

    BoxWithConstraints(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val maxOffset = ((constraints.maxWidth - knobSizePx) / 2f - with(density) { 4.dp.toPx() })
            .coerceAtLeast(1f)
        var lastSentLevel by remember { mutableFloatStateOf(0f) }
        var rawDownPx by remember { mutableFloatStateOf(0f) }
        var swipeDownFired by remember { mutableStateOf(false) }

        fun release() {
            isDragging = false
            if (lastSentLevel != 0f) currentOnStop()
            lastSentLevel = 0f
            // Плавный возврат ползунка в центр
            val springSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy)
            returnJobs = listOf(
                scope.launch { animate(offsetX, 0f, animationSpec = springSpec) { value, _ -> offsetX = value } },
                scope.launch { animate(offsetY, 0f, animationSpec = springSpec) { value, _ -> offsetY = value } }
            )
        }

        // Подписи направлений
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            val labelStyle = if (leftLabel.length > 1) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleLarge
            Text(leftLabel, style = labelStyle, color = labelColor)
            Text(rightLabel, style = labelStyle, color = labelColor)
        }

        // Ползунок качельки
        Surface(
            shape = CircleShape,
            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 3.dp,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(knobSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.UnfoldMore,
                    contentDescription = contentDescription,
                    tint = if (isDragging) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Сенсорная область на всю качельку
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(maxOffset) {
                    detectDragGestures(
                        onDragStart = {
                            returnJobs.forEach { it.cancel() }
                            isDragging = true
                            rawDownPx = 0f
                            swipeDownFired = false
                        },
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceIn(-maxOffset, maxOffset)

                            val swipeDown = currentOnSwipeDown
                            if (swipeDown != null) {
                                rawDownPx = (rawDownPx + dragAmount.y).coerceAtLeast(0f)
                                offsetY = rawDownPx.coerceAtMost(maxDownPx)
                                // Свайп вниз засчитывается, только если ползунок почти не сдвинут вбок
                                val nearCenter = abs(offsetX) < maxOffset * 0.15f
                                if (!swipeDownFired && nearCenter && rawDownPx > swipeDownThresholdPx) {
                                    swipeDownFired = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    swipeDown()
                                }
                            }

                            val level = quantizePtzLevel(offsetX / maxOffset)
                            if (level != lastSentLevel) {
                                lastSentLevel = level
                                if (level == 0f) currentOnStop() else currentOnChange(level)
                            }
                        }
                    )
                }
        )
    }
}

/**
 * Кнопка «нажал и держи»: действие идёт, пока палец на кнопке, при отпускании — Stop.
 */
@Composable
private fun HoldButton(
    icon: @Composable () -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var pressed by remember { mutableStateOf(false) }

    Surface(
        shape = CircleShape,
        color = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .size(44.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentOnPress()
                        tryAwaitRelease()
                        pressed = false
                        currentOnRelease()
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}
