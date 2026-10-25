package com.example.denmediaipcams.ui.layouts

import com.example.denmediaipcams.ui.components.verticalScrollWithBar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.example.denmediaipcams.ui.components.LazyColumnWithBar
import com.example.denmediaipcams.ui.components.ReorderableLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.denmediaipcams.model.FocusDirection
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.player.ThumbnailStore
import com.example.denmediaipcams.ui.components.CameraStreamPlayer
import com.example.denmediaipcams.ui.components.DayNightControl
import com.example.denmediaipcams.ui.components.FocusControl
import com.example.denmediaipcams.ui.components.NetworkScanButton
import com.example.denmediaipcams.ui.components.QrAddButton
import com.example.denmediaipcams.ui.components.PlayerActions
import com.example.denmediaipcams.ui.components.TooltipIconButton
import com.example.denmediaipcams.ui.components.PlayerActionsStyle
import com.example.denmediaipcams.ui.components.PresetStrip
import com.example.denmediaipcams.ui.components.ThumbnailImage
import com.example.denmediaipcams.ui.components.ZoomControl
import com.example.denmediaipcams.ui.components.rememberDisplayCornerInset

private enum class PhoneSection(val label: String) {
    Cameras("Камеры"),
    List("Список"),
    Info("Инфо")
}

/**
 * Смартфон и вертикальный планшет: шапка, выбор камеры, видео с панелью кнопок,
 * вкладки «Поворот / Зум / Фокус», пресеты; нижнее меню «Камеры / Список / Инфо».
 */
@Composable
fun PhoneLayout(state: CameraScreenState, actions: CameraScreenActions) {
    var section by rememberSaveable { mutableStateOf(PhoneSection.Cameras) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // Горизонтально меню уходит в узкую колонку слева — вся высота экрана остаётся под трансляцию
    if (maxWidth > maxHeight) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Узкая колонка из одних значков: широкая NavigationRail отняла бы у видео ~80dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(52.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                PhoneSection.entries.forEach { item ->
                    val selected = section == item
                    TooltipIconButton(
                        tooltip = item.label,
                        onClick = { section = item },
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                    ) {
                        Icon(
                            imageVector = item.icon(),
                            contentDescription = item.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (section) {
                    PhoneSection.Cameras -> CamerasSection(state, actions)
                    PhoneSection.List -> CameraListSection(state, actions)
                    PhoneSection.Info -> CameraInfoSection(state.selectedCamera)
                }
            }
        }
        return@BoxWithConstraints
    }

    // Вертикально: та же компоновка, что и горизонтально на планшете и смартфоне
    PortraitScreen(state, actions)
    }
}

private fun PhoneSection.icon() = when (this) {
    PhoneSection.Cameras -> Icons.Outlined.Videocam
    PhoneSection.List -> Icons.AutoMirrored.Outlined.ViewList
    PhoneSection.Info -> Icons.Outlined.Info
}

@Composable
private fun CamerasSection(state: CameraScreenState, actions: CameraScreenActions) {
    // Углы экрана скруглены: элементы в углах отодвигаем
    val corner = rememberDisplayCornerInset()
    if (state.cameras.isEmpty()) {
        EmptyCamerasView(
            onAddCamera = actions.onAddCamera,
            onScanNetwork = actions.onScanNetwork,
            onAddFromQr = actions.onAddFromQr
        )
        return
    }

    val camera = state.selectedCamera

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Смартфон в горизонтальной ориентации: видео слева, выбор камеры и управление справа
        if (maxWidth > maxHeight) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                // Видео на всю высоту; действия и выбор потока — в меню «три точки» поверх видео
                CameraStreamPlayer(
                    camera = camera,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    showOverlayControls = false,
                    useMenuControls = true,
                    onToggleFullscreen = { camera?.let(actions.onFullscreen) },
                    onPtzMove = actions.onMove,
                    onPtzZoom = actions.onZoom,
                    onPtzStop = actions.onStop,
                    lastPtzActionAtMs = state.lastPtzActionAtMs,
                    digitalZoom = state.digitalZoomOf(camera),
                    onSelectStreamProfile = { profileId ->
                        camera?.let { actions.onSelectStreamProfile(it.id, profileId) }
                    },
                    onEditCamera = actions.onEditCamera,
                    onReconnectCamera = actions.onReconnectCamera
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .verticalScrollWithBar()
                        // Полоса прокрутки рисуется по краю колонки и не должна наезжать на карточки
                        .padding(end = 6.dp)
                ) {
                    CameraSelector(state = state, actions = actions, compact = true)
                    if (camera != null) CameraControlPanels(camera, state, actions, compact = true)
                }
            }
            return@BoxWithConstraints
        }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScrollWithBar()
            .padding(horizontal = maxOf(14.dp, corner), vertical = maxOf(10.dp, corner))
    ) {
        // Шапка
        ScreenTitle(
            title = "Камеры",
            subtitle = camerasSummary(state.cameras.size, state.onlineCount)
        )

        // Выбор камеры
        CameraSelector(state = state, actions = actions)

        // Видео; действия и выбор потока — в меню «три точки» поверх видео
        CameraStreamPlayer(
            camera = camera,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            showOverlayControls = false,
            useMenuControls = true,
            onToggleFullscreen = { camera?.let(actions.onFullscreen) },
            onPtzMove = actions.onMove,
            onPtzZoom = actions.onZoom,
            onPtzStop = actions.onStop,
            lastPtzActionAtMs = state.lastPtzActionAtMs,
            digitalZoom = state.digitalZoomOf(camera),
            onSelectStreamProfile = { profileId ->
                camera?.let { actions.onSelectStreamProfile(it.id, profileId) }
            },
            onEditCamera = actions.onEditCamera,
            onReconnectCamera = actions.onReconnectCamera
        )

        // Пресеты идут ниже управления камерой
        if (camera != null) CameraControlPanels(camera, state, actions)
    }
    }
}

/** Панели управления выбранной камерой (как на планшете): поворот с зумом, день / ночь с фокусом и пресеты */
@Composable
internal fun CameraControlPanels(
    camera: IpCamera,
    state: CameraScreenState,
    actions: CameraScreenActions,
    compact: Boolean = false,
    presetsFirst: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        // Без PTZ поворот, зум, фокус и пресеты скрыты
        val showPresets = state.hasPtz(camera)
        if (showPresets && presetsFirst) PresetsCard(camera, actions)
        TabletControlPanels(camera, state, actions, joystickSize = if (compact) 150.dp else 200.dp, focusButtonsOnly = compact)
        if (showPresets && !presetsFirst) PresetsCard(camera, actions)
        ArchivePreviewCard(camera)
    }
}

@Composable
internal fun PresetsCard(camera: IpCamera, actions: CameraScreenActions) {
    PanelCard(title = "Пресеты", actionLabel = "Управление", onAction = actions.onManagePresets) {
        PresetStrip(
            cameraId = camera.id,
            presets = camera.presets,
            onGoToPreset = actions.onGoToPreset,
            onSavePreset = actions.onSavePreset,
            onAddPreset = actions.onAddPreset,
            tileWidth = 88.dp
        )
    }
}

/** Карточка выбранной камеры; нажатие открывает список для быстрого переключения */
@Composable
private fun CameraSelector(state: CameraScreenState, actions: CameraScreenActions, compact: Boolean = false) {
    val camera = state.selectedCamera
    var expanded by remember { mutableStateOf(false) }

    Box {
        PanelCard(
            title = null,
            contentPadding = if (compact) 8.dp else 10.dp,
            modifier = Modifier.clickable { expanded = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    CameraBadgeIcon()
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera?.name ?: "Камера не выбрана",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (camera != null) {
                        if (!compact) {
                            Text(
                                text = cameraSubtitle(camera),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        CameraOnlineStatus(isOnline = state.isOnline(camera), isChecked = state.isChecked(camera))
                    }
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Выбрать камеру")
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp)
        ) {
            state.cameras.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CameraOnlineStatus(isOnline = state.isOnline(item), isChecked = state.isChecked(item))
                        }
                    },
                    leadingIcon = {
                        ThumbnailImage(
                            key = ThumbnailStore.cameraKey(item.id),
                            modifier = Modifier
                                .size(width = 56.dp, height = 34.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    },
                    onClick = {
                        expanded = false
                        actions.onShowSingleCamera(item.id)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Добавить камеру", color = MaterialTheme.colorScheme.primary) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    expanded = false
                    actions.onAddCamera()
                }
            )
            DropdownMenuItem(
                text = { Text("Добавить по QR коду", color = MaterialTheme.colorScheme.primary) },
                leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    expanded = false
                    actions.onAddFromQr()
                }
            )
        }
    }
}

/** Управление списком камер: выбор, редактирование, удаление, добавление и поиск в сети */
@Composable
private fun CameraListSection(state: CameraScreenState, actions: CameraScreenActions) {
    // Углы экрана скруглены: элементы в углах отодвигаем
    val corner = rememberDisplayCornerInset()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTitle(
            title = "Список камер",
            subtitle = camerasSummary(state.cameras.size, state.onlineCount),
            modifier = Modifier.padding(start = maxOf(14.dp, corner), end = 14.dp, top = maxOf(10.dp, corner), bottom = 12.dp)
        )

        ReorderableLazyColumn(
            items = state.cameras,
            key = { it.id },
            onMove = actions.onMoveCamera,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) { item, itemModifier ->
            CameraListItem(
                camera = item,
                isSelected = item.id == state.selectedCamera?.id,
                isOnline = state.isOnline(item),
                isChecked = state.isChecked(item),
                latencyMs = state.latencyOf(item),
                onClick = { actions.onShowSingleCamera(item.id) },
                onDoubleClick = { actions.onEditCamera(item) },
                modifier = itemModifier,
                trailing = { ListItemActions(item, actions) }
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = maxOf(14.dp, corner), end = maxOf(14.dp, corner), top = 14.dp, bottom = maxOf(14.dp, corner))
        ) {
            FilledTonalButton(
                onClick = actions.onAddCamera,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить камеру")
            }
            Spacer(modifier = Modifier.width(8.dp))
            NetworkScanButton(onClick = actions.onScanNetwork)
            QrAddButton(onClick = actions.onAddFromQr)
        }
    }
}

/** Действия камеры в списке: на узком экране — одним меню «⋮», иначе отдельными кнопками */
@Composable
internal fun ListItemActions(item: IpCamera, actions: CameraScreenActions, forceMenu: Boolean = false) {
    val narrow = forceMenu || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 480
    if (narrow) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            TooltipIconButton(tooltip = "Действия", onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Действия")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Переподключиться") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = { expanded = false; actions.onReconnectCamera(item.id) }
                )
                DropdownMenuItem(
                    text = { Text("Настройка") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = { expanded = false; actions.onEditCamera(item) }
                )
                DropdownMenuItem(
                    text = { Text("Поделиться") },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    onClick = { expanded = false; actions.onShareCamera(item) }
                )
                DropdownMenuItem(
                    text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { expanded = false; actions.onDeleteCamera(item.id) }
                )
            }
        }
    } else {
        Row {
            TooltipIconButton(tooltip = "Переподключиться", onClick = { actions.onReconnectCamera(item.id) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Переподключиться")
            }
            TooltipIconButton(tooltip = "Настройка", onClick = { actions.onEditCamera(item) }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Настройка")
            }
            TooltipIconButton(tooltip = "Поделиться", onClick = { actions.onShareCamera(item) }) {
                Icon(Icons.Outlined.Share, contentDescription = "Поделиться")
            }
            TooltipIconButton(tooltip = "Удалить", onClick = { actions.onDeleteCamera(item.id) }) {
                Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Параметры выбранной камеры */
@Composable
private fun CameraInfoSection(camera: IpCamera?) {
    // Углы экрана скруглены: элементы в углах отодвигаем
    val corner = rememberDisplayCornerInset()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScrollWithBar()
            .padding(start = maxOf(14.dp, corner), end = maxOf(14.dp, corner), top = maxOf(14.dp, corner), bottom = 14.dp)
    ) {
        ScreenTitle(
            title = "Информация",
            subtitle = camera?.name ?: "Камера не выбрана",
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (camera == null) return@Column
        PanelCard(title = "Параметры камеры") {
            InfoRow("Название", camera.name)
            InfoRow("IP / хост", camera.ipAddress)
            InfoRow("RTSP порт", camera.effectiveRtspPort.toString())
            InfoRow("HTTP порт", camera.effectiveHttpPort.toString())
            InfoRow("ONVIF порт", camera.effectiveOnvifPort.toString())
            InfoRow("Протокол PTZ", camera.protocol.displayName)
            InfoRow("Скорость PTZ", "${(camera.ptzSpeed * 100).toInt()}%")
            InfoRow("Сохранено положений", camera.presets.count { it.isSaved }.toString())
            Spacer(modifier = Modifier.height(8.dp))
            Text("RTSP URL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                camera.rtspUrlWithoutCredentials,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 150.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
