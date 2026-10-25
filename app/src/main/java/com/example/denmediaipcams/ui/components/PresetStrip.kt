package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.model.Preset
import com.example.denmediaipcams.player.ThumbnailStore

/**
 * Сохранённые положения камеры: плитки с превью кадра, номером и названием.
 * Нажатие — перейти к положению, долгое нажатие — пересохранить текущее положение в эту ячейку,
 * плитка «Добавить» — сохранить текущее положение в первую свободную ячейку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetStrip(
    cameraId: String,
    presets: List<Preset>,
    onGoToPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit,
    onAddPreset: () -> Unit,
    modifier: Modifier = Modifier,
    tileWidth: Dp = 104.dp
) {
    val haptic = LocalHapticFeedback.current
    var activePresetId by remember(cameraId) { mutableStateOf<Int?>(null) }
    val saved = presets.filter { it.isSaved }
    val hasFreeSlot = presets.any { !it.isSaved }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScrollWithBar()
    ) {
        saved.forEach { preset ->
            val isActive = preset.id == activePresetId
            PresetTile(
                width = tileWidth,
                isActive = isActive,
                modifier = Modifier.combinedClickable(
                    onClick = {
                        activePresetId = preset.id
                        onGoToPreset(preset.id)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        activePresetId = preset.id
                        onSavePreset(preset.id)
                    },
                    onClickLabel = "Перейти к положению ${preset.id}",
                    onLongClickLabel = "Сохранить текущее положение в ячейку ${preset.id}"
                )
            ) {
                ThumbnailImage(
                    key = ThumbnailStore.presetKey(cameraId, preset.id),
                    placeholderIcon = Icons.Outlined.BookmarkBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileWidth * 0.56f)
                )
                PresetCaption(number = preset.id, name = presetDisplayName(preset), isActive = isActive)
            }
        }

        if (hasFreeSlot) {
            PresetTile(
                width = tileWidth,
                isActive = false,
                modifier = Modifier.combinedClickable(
                    onClick = onAddPreset,
                    onClickLabel = "Сохранить текущее положение камеры"
                )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileWidth * 0.56f)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = "Добавить",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

/** Название положения без дублирования номера: «Пресет 3» → пусто (номер и так виден) */
fun presetDisplayName(preset: Preset): String =
    if (preset.name == "Пресет ${preset.id}") "" else preset.name

@Composable
private fun PresetTile(
    width: Dp,
    isActive: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(width)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = shape
            )
            .then(modifier)
    ) {
        content()
    }
}

@Composable
private fun PresetCaption(number: Int, name: String, isActive: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 5.dp, horizontal = 4.dp)
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
        if (name.isNotEmpty()) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
