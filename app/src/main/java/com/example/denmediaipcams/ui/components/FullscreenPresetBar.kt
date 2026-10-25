package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import com.example.denmediaipcams.player.ThumbnailStore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.denmediaipcams.model.Preset

private val PresetGlowColor = Color(0xFFFFC107)

/**
 * Кнопки сохранённых положений камеры для полноэкранного режима (2 ряда по 4).
 * Сохранённые положения «горят» янтарным со свечением, пустые ячейки — тусклые контурные.
 * Нажатие — перейти к положению, долгое нажатие — сохранить текущее положение в ячейку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenPresetBar(
    cameraId: String,
    presets: List<Preset>,
    onGoToPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit,
    onEmptyPresetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(10.dp)
    ) {
        presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = 72.dp, height = 44.dp)
                            .then(
                                if (preset.isSaved) {
                                    Modifier.shadow(
                                        elevation = 10.dp,
                                        shape = shape,
                                        ambientColor = PresetGlowColor,
                                        spotColor = PresetGlowColor
                                    )
                                } else Modifier
                            )
                            .clip(shape)
                            .background(Color.Black.copy(alpha = 0.4f))
                            .border(
                                width = if (preset.isSaved) 2.dp else 1.dp,
                                color = if (preset.isSaved) PresetGlowColor else Color.White.copy(alpha = 0.3f),
                                shape = shape
                            )
                            .combinedClickable(
                                onClick = {
                                    if (preset.isSaved) onGoToPreset(preset.id) else onEmptyPresetClick()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSavePreset(preset.id)
                                },
                                onClickLabel = "Перейти к положению ${preset.id}",
                                onLongClickLabel = "Сохранить текущее положение в ячейку ${preset.id}"
                            )
                    ) {
                        // Превью положения (если снято), поверх — номер ячейки
                        if (preset.isSaved) {
                            val thumbnail = rememberThumbnail(ThumbnailStore.presetKey(cameraId, preset.id))
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Text(
                            text = preset.id.toString(),
                            color = if (preset.isSaved) Color.Black else Color.White.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                            fontWeight = if (preset.isSaved) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(3.dp)
                                .background(
                                    if (preset.isSaved) PresetGlowColor else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
