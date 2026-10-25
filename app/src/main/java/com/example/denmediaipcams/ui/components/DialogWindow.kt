package com.example.denmediaipcams.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Смартфон — меньшая сторона экрана меньше 600dp: окна настроек открываются на весь экран */
@Composable
fun isPhoneScreen(): Boolean =
    LocalConfiguration.current.let { minOf(it.screenWidthDp, it.screenHeightDp) < 600 }

/**
 * Полноэкранный диалог смартфона: у него своё окно, а приложение на смартфоне работает
 * без системных панелей — прячем их и здесь (появляются по свайпу от края).
 * Вызывается внутри содержимого Dialog.
 */
@Composable
fun HideSystemBarsInDialog() {
    val dialogView = LocalView.current
    SideEffect {
        (dialogView.parent as? DialogWindowProvider)?.window?.let { dialogWindow ->
            WindowCompat.getInsetsController(dialogWindow, dialogView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
