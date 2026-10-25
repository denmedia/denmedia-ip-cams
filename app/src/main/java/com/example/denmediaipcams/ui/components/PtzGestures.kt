package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Смещение пальца (доля стороны видео), при котором скорость поворота максимальна */
private const val FULL_SPEED_DRAG_FRACTION = 0.3f

/** Изменение расстояния между пальцами (в разах), при котором скорость зума максимальна */
private const val FULL_SPEED_PINCH_DELTA = 0.5f

private enum class GestureMode { NONE, DRAG, PINCH }

/**
 * PTZ-жесты по видео:
 * - перетаскивание одним пальцем поворачивает камеру в сторону движения пальца,
 *   скорость пропорциональна смещению от точки касания;
 * - щипок двумя пальцами: развести — приблизить, свести — отдалить;
 * - когда все пальцы убраны с экрана, камера получает Stop.
 */
@Composable
fun Modifier.ptzGestures(
    onMove: (pan: Float, tilt: Float) -> Unit,
    onZoom: (level: Float) -> Unit,
    onStop: () -> Unit
): Modifier {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnStop by rememberUpdatedState(onStop)

    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var mode = GestureMode.NONE
            var pinchStartDistance = 0f
            var lastPan = 0f
            var lastTilt = 0f
            var lastZoom = 0f

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size >= 2) {
                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                    if (mode != GestureMode.PINCH) {
                        // Переход от поворота к зуму — сначала останавливаем поворот
                        if (mode == GestureMode.DRAG && (lastPan != 0f || lastTilt != 0f)) currentOnStop()
                        mode = GestureMode.PINCH
                        pinchStartDistance = distance.coerceAtLeast(1f)
                    } else {
                        val scaleDelta = distance / pinchStartDistance - 1f
                        val level = quantizePtzLevel(scaleDelta / FULL_SPEED_PINCH_DELTA)
                        if (level != lastZoom) {
                            lastZoom = level
                            if (level == 0f) currentOnStop() else currentOnZoom(level)
                        }
                    }
                    event.changes.forEach { it.consume() }
                } else if (mode != GestureMode.PINCH) {
                    // После щипка один оставшийся палец не поворачивает камеру — до полного отпускания
                    val delta = pressed[0].position - start
                    if (mode == GestureMode.NONE && delta.getDistance() > viewConfiguration.touchSlop) {
                        mode = GestureMode.DRAG
                    }
                    if (mode == GestureMode.DRAG) {
                        val pan = quantizePtzLevel(delta.x / (size.width * FULL_SPEED_DRAG_FRACTION))
                        // Ось Y экрана направлена вниз: палец вверх — наклон вверх
                        val tilt = quantizePtzLevel(-delta.y / (size.height * FULL_SPEED_DRAG_FRACTION))
                        if (pan != lastPan || tilt != lastTilt) {
                            lastPan = pan
                            lastTilt = tilt
                            if (pan == 0f && tilt == 0f) currentOnStop() else currentOnMove(pan, tilt)
                        }
                        pressed[0].consume()
                    }
                }
            }

            if (mode != GestureMode.NONE) currentOnStop()
        }
    }
}
