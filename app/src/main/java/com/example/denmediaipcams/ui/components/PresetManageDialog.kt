package com.example.denmediaipcams.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.denmediaipcams.model.Preset
import com.example.denmediaipcams.player.ThumbnailStore

/**
 * Управление сохранёнными положениями: переименование, сохранение текущего положения
 * в любую ячейку, переход и очистка ячейки.
 * Смартфон — на весь экран (как настройки камеры), планшет — обычный диалог.
 */
@Composable
fun PresetManageDialog(
    cameraId: String,
    presets: List<Preset>,
    onRename: (presetId: Int, name: String) -> Unit,
    onSave: (presetId: Int) -> Unit,
    onGoTo: (presetId: Int) -> Unit,
    onClear: (presetId: Int) -> Unit,
    isCapturingThumbnails: Boolean,
    /** Положение, превью которого снимается сейчас: его картинка размыта, поверх неё индикатор загрузки */
    capturingPresetId: Int?,
    onCaptureThumbnails: () -> Unit,
    onCancelCapture: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isPhone = isPhoneScreen()
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    // Узкий экран: кнопки действий не помещаются в строку с названием и уходят под него
    val compact = configuration.screenWidthDp < 500

    val list: LazyListScope.() -> Unit = {
        item {
            // Камера объезжает все сохранённые положения и снимает превью каждого
            if (isCapturingThumbnails) {
                OutlinedButton(onClick = onCancelCapture, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Снимаю превью… Остановить")
                }
            } else {
                FilledTonalButton(
                    onClick = onCaptureThumbnails,
                    enabled = presets.any { it.isSaved },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Снять превью всех положений")
                }
            }
            Text(
                text = "Камера по очереди повернётся к каждому сохранённому положению и сфотографирует его",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(presets, key = { it.id }) { preset ->
            PresetManageRow(
                cameraId = cameraId,
                preset = preset,
                compact = compact,
                capturing = preset.id == capturingPresetId,
                onRename = { onRename(preset.id, it) },
                onSave = { onSave(preset.id) },
                onGoTo = { onGoTo(preset.id) },
                onClear = { onClear(preset.id) }
            )
        }
    }

    val doneButton: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onDismiss, modifier = modifier) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Готово")
        }
    }

    if (isPhone) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            HideSystemBarsInDialog()
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                if (isLandscape) {
                    // Горизонтально: без заголовка — значок и кнопка столбиком справа, список на всю высоту
                    Row(modifier = Modifier.fillMaxSize().imePadding()) {
                        LazyColumnWithBar(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            content = list
                        )
                        VerticalDivider()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                            modifier = Modifier
                                .width(150.dp)
                                .fillMaxHeight()
                                .padding(8.dp)
                        ) {
                            doneButton(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().imePadding()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Сохранённые положения", style = MaterialTheme.typography.titleLarge)
                        }
                        LazyColumnWithBar(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                            content = list
                        )
                        HorizontalDivider()
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            doneButton(Modifier)
                        }
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Сохранённые положения")
                }
            },
            text = {
                LazyColumnWithBar(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // На планшете высота списка растёт вместе с экраном
                    modifier = Modifier.heightIn(max = (configuration.screenHeightDp - 330).coerceIn(480, 720).dp),
                    content = list
                )
            },
            confirmButton = { doneButton(Modifier) }
        )
    }
}

@Composable
private fun PresetManageRow(
    cameraId: String,
    preset: Preset,
    compact: Boolean,
    capturing: Boolean,
    onRename: (String) -> Unit,
    onSave: () -> Unit,
    onGoTo: () -> Unit,
    onClear: () -> Unit
) {
    var name by remember(preset.id, preset.name) { mutableStateOf(preset.name) }

    val nameField: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onRename(it.ifBlank { "Пресет ${preset.id}" })
            },
            label = { Text("${preset.id}" + if (preset.isSaved) "" else " · пусто") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = fieldModifier
        )
    }

    if (compact) {
        // Узкий экран: карточка — превью и название сверху, три действия с подписями снизу
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThumbnailImage(
                        key = ThumbnailStore.presetKey(cameraId, preset.id),
                        placeholderIcon = Icons.Outlined.BookmarkBorder,
                        loading = capturing,
                        modifier = Modifier
                            .size(width = 96.dp, height = 60.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    nameField(Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Крупный шрифт: подписи не помещаются в треть ширины и обрезаются — оставляем одни значки
                if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        TooltipIconButton(tooltip = "Запомнить текущее положение", onClick = onSave) {
                            Icon(Icons.Outlined.Save, contentDescription = "Запомнить")
                        }
                        TooltipIconButton(tooltip = "Перейти к положению", onClick = onGoTo, enabled = preset.isSaved) {
                            Icon(Icons.Outlined.NearMe, contentDescription = "Перейти")
                        }
                        TooltipIconButton(tooltip = "Очистить ячейку", onClick = onClear, enabled = preset.isSaved) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Очистить")
                        }
                    }
                } else
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onSave, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Запомнить", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onGoTo, enabled = preset.isSaved, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.NearMe, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Перейти", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onClear, enabled = preset.isSaved, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Очистить", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ThumbnailImage(
            key = ThumbnailStore.presetKey(cameraId, preset.id),
            placeholderIcon = Icons.Outlined.BookmarkBorder,
            loading = capturing,
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            nameField(Modifier.fillMaxWidth())
        }
        TooltipIconButton(tooltip = "Запомнить текущее положение", onClick = onSave) {
            Icon(Icons.Outlined.Save, contentDescription = "Сохранить текущее положение в ячейку ${preset.id}")
        }
        TooltipIconButton(tooltip = "Перейти к положению", onClick = onGoTo, enabled = preset.isSaved) {
            Icon(Icons.Outlined.NearMe, contentDescription = "Перейти к положению ${preset.id}")
        }
        TooltipIconButton(tooltip = "Очистить ячейку", onClick = onClear, enabled = preset.isSaved) {
            Icon(Icons.Outlined.Delete, contentDescription = "Очистить ячейку ${preset.id}")
        }
    }
}
