package com.example.denmediaipcams.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Единая тёмная тема приложения (не зависит от системной темы и цветов обоев):
 * тёмно-синий фон, карточки на тон светлее, синий акцент.
 */
private val AppColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextPrimary,
    primaryContainer = AccentBlueContainer,
    onPrimaryContainer = OnAccentBlueContainer,
    secondary = AccentBlue,
    onSecondary = TextPrimary,
    secondaryContainer = NavySurfaceHighest,
    onSecondaryContainer = TextPrimary,
    tertiary = OnlineGreen,
    background = NavyBackground,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = NavyBackground,
    surfaceContainerLow = NavySurface,
    surfaceContainer = NavySurface,
    surfaceContainerHigh = NavySurfaceHigh,
    surfaceContainerHighest = NavySurfaceHighest,
    outline = NavyOutline,
    outlineVariant = NavyOutline,
    error = ErrorRed
)

@Composable
fun DenMediaIPCamsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}
