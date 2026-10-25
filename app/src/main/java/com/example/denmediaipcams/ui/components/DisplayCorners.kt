package com.example.denmediaipcams.ui.components

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Отступ от краёв экрана, чтобы элементы в углах не обрезались скруглением дисплея.
 * Берётся радиус самого большого скругления (Android 12+) и отступ по диагонали угла:
 * элемент, сдвинутый на ~0.3 радиуса по обеим осям, уже не попадает под дугу. Без данных о скруглении — 0.
 */
@Composable
fun rememberDisplayCornerInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    // Радиус зависит от поворота экрана
    val rotationKey = LocalConfiguration.current.orientation
    return remember(view, density, rotationKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember 0.dp
        val insets = view.rootWindowInsets ?: return@remember 0.dp
        val radiusPx = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT,
            RoundedCorner.POSITION_BOTTOM_RIGHT
        ).maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }
        with(density) { (radiusPx * 0.3f).toDp() }
    }
}
