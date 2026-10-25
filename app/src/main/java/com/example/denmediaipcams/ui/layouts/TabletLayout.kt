package com.example.denmediaipcams.ui.layouts

import com.example.denmediaipcams.ui.components.verticalScrollWithBar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.ui.components.TooltipIconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.example.denmediaipcams.ui.components.LazyColumnWithBar
import com.example.denmediaipcams.ui.components.ReorderableLazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import com.example.denmediaipcams.ui.components.rememberDisplayCornerInset
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.ui.components.CameraStreamPlayer
import com.example.denmediaipcams.ui.components.DayNightControl
import com.example.denmediaipcams.ui.components.DvripNightLightControl
import com.example.denmediaipcams.ui.components.FocusControl
import com.example.denmediaipcams.ui.components.NetworkScanButton
import com.example.denmediaipcams.ui.components.QrAddButton
import com.example.denmediaipcams.ui.components.PresetStrip
import com.example.denmediaipcams.ui.components.ZoomControl

/**
 * Планшет (горизонтально): слева список камер с превью, в центре шапка камеры, видео
 * и сохранённые положения, справа поворот, зум и фокус.
 */
@Composable
fun TabletLayout(state: CameraScreenState, actions: CameraScreenActions) {
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
    // Невысокий экран (смартфон по горизонтали): те же три колонки, но компактные
    if (maxHeight < 560.dp) {
        CompactTabletLayout(state, actions)
        return@BoxWithConstraints
    }
    // Ширина боковых колонок подстраивается под экран: на узком планшете видео не сжимается до полоски
    val listWidth = (maxWidth * 0.24f).coerceIn(210.dp, 300.dp)
    val controlsWidth = (maxWidth * 0.28f).coerceIn(250.dp, 330.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        // Левая колонка: камеры
        Column(
            modifier = Modifier
                .width(listWidth)
                .fillMaxHeight()
        ) {
            ScreenTitle(
                title = "Камеры",
                subtitle = camerasSummary(state.cameras.size, state.onlineCount),
                modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 16.dp)
            )

            ReorderableLazyColumn(
                items = state.cameras,
                key = { it.id },
                onMove = actions.onMoveCamera,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) { item, itemModifier ->
                CameraListItem(
                    camera = item,
                    isSelected = item.id == camera?.id,
                    isOnline = state.isOnline(item),
                    isChecked = state.isChecked(item),
                    latencyMs = state.latencyOf(item),
                    onClick = { actions.onShowSingleCamera(item.id) },
                    onDoubleClick = { actions.onEditCamera(item) },
                    modifier = itemModifier
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
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

        // Центральная колонка: камера, видео, сохранённые положения
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            CameraHeader(camera = camera, state = state, actions = actions)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.isMultiGrid) {
                    // Сетка 2x2: кнопки на самих плитках, PTZ-жесты отключены (непонятно, какой камерой управлять)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.cameras, key = { it.id }) { tile ->
                            CameraStreamPlayer(
                                camera = tile,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(4f / 3f),
                                useMenuControls = true,
                                isGridTile = true,
                                onSelectTile = { actions.onSelectCamera(tile.id) },
                                onOpenTile = { actions.onShowSingleCamera(tile.id) },
                                isSelectedTile = tile.id == camera?.id,
                                onToggleFullscreen = { actions.onFullscreen(tile) },
                                onSelectStreamProfile = { profileId -> actions.onSelectStreamProfile(tile.id, profileId) },
                                onEditCamera = actions.onEditCamera,
                                onReconnectCamera = actions.onReconnectCamera
                            )
                        }
                    }
                } else {
                    CameraStreamPlayer(
                        camera = camera,
                        modifier = Modifier.fillMaxSize(),
                        showOverlayControls = false,
                        onToggleFullscreen = { camera?.let(actions.onFullscreen) },
                        onPtzMove = actions.onMove,
                        onPtzZoom = actions.onZoom,
                        onPtzStop = actions.onStop,
                        lastPtzActionAtMs = state.lastPtzActionAtMs,
                        digitalZoom = state.digitalZoomOf(camera),
                        onSelectStreamProfile = { profileId ->
                            camera?.let { actions.onSelectStreamProfile(it.id, profileId) }
                        },
                        onEditCamera = actions.onEditCamera
                    )
                }
            }

            if (camera != null && state.hasPtz(camera)) {
                PanelCard(
                    title = "Сохранённые положения",
                    actionLabel = "Управление",
                    onAction = actions.onManagePresets
                ) {
                    PresetStrip(
                        cameraId = camera.id,
                        presets = camera.presets,
                        onGoToPreset = actions.onGoToPreset,
                        onSavePreset = actions.onSavePreset,
                        onAddPreset = actions.onAddPreset
                    )
                }
            }
        }

        // Правая колонка: поворот, зум, фокус
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .width(controlsWidth)
                .fillMaxHeight()
                .verticalScrollWithBar()
                .padding(end = 6.dp)
        ) {
            TabletControlPanels(camera, state, actions, joystickSize = 190.dp)
            ArchivePreviewCard(camera)
        }
    }
    }
}

/** Панели управления камерой как на планшете: поворот, зум, фокус, день / ночь (каждая в своей карточке) */
@Composable
internal fun TabletControlPanels(
    camera: IpCamera?,
    state: CameraScreenState,
    actions: CameraScreenActions,
    joystickSize: Dp,
    /** Поворот и «день / ночь + фокус» в одном ряду, карточки одной высоты (широкий портретный экран) */
    sideBySide: Boolean = false,
    /** Фокус только кнопками «ближе / дальше» (смартфон в альбомной ориентации) */
    focusButtonsOnly: Boolean = false
) {
    // Камера не выбрана — панели на месте, но полупрозрачны и неактивны.
    // Камера без PTZ (протокол «без PTZ» или камера сама сообщила, что нет ни поворота, ни зума) — поворот и зум
    // скрыты. Камера только с зумом — скрыт один джойстик. Фокус проверяется отдельно: без него остаётся «день / ночь»
    val ptzUnavailable = camera == null || !camera.protocol.supportsPtz
    val ptzHidden = camera != null && !state.hasPtz(camera)
    val focusHidden = camera != null && !state.isFocusSupported(camera)
    if (ptzHidden) {
        NoPtzMessage(reportedByCamera = camera.protocol.supportsPtz, modifier = Modifier.padding(horizontal = 4.dp))
    }
    val rotation: @Composable (Modifier) -> Unit = { m ->
        // Блокировку регулирует панель: джойстик, а рядом с ним зум (у зума своя доступность)
        PanelCard(title = null, modifier = m, centerContent = sideBySide) {
            PtzRotationPanel(
                actions = actions,
                joystickSize = joystickSize,
                camera = camera,
                panTiltHidden = camera != null && !state.hasPanTilt(camera),
                zoomUnavailable = ptzUnavailable || !state.isZoomSupported(camera),
                fixedSize = sideBySide
            )
        }
    }
    // День / ночь выше фокуса, в одной карточке
    val dayNightFocus: @Composable (Modifier) -> Unit = { m ->
        PanelCard(title = null, modifier = m, centerContent = sideBySide) {
            DayNightControl(
                currentMode = state.dayNightModeOf(camera),
                isSupported = state.isDayNightSupported(camera),
                onModeSelected = actions.onSetDayNightMode
                // День/ночь не зависит от PTZ: панель доступна, если камера отдаёт режим (isSupported)
            )
            if (camera != null) {
                DvripNightLightControl(camera, refreshKey = state.dayNightModeOf(camera), modifier = Modifier.padding(top = 8.dp))
            }
            if (!focusHidden) {
                Spacer(modifier = Modifier.height(12.dp))
                FocusControl(
                    onFocusMove = actions.onFocusMove,
                    onFocusStop = actions.onFocusStop,
                    onAutoFocus = { actions.onFocus(com.example.denmediaipcams.model.FocusDirection.AUTO) },
                    buttonsOnly = focusButtonsOnly,
                    // Камера не выбрана — фокус на месте, но неактивен
                    modifier = Modifier.ptzUnavailable(camera == null)
                )
            }
        }
    }
    if (ptzHidden) {
        dayNightFocus(Modifier)
    } else if (sideBySide) {
        // Одна полоса: карточки одинаковой высоты — по джойстику с отступами карточки (измерение по содержимому
        // здесь недопустимо: в качельках фокуса BoxWithConstraints)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(joystickSize + 28.dp)
        ) {
            rotation(Modifier.weight(1f).fillMaxHeight())
            dayNightFocus(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        rotation(Modifier)
        dayNightFocus(Modifier)
    }
}

/**
 * Раскладка планшета для невысокого экрана (смартфон по горизонтали): видео занимает 2/3 ширины,
 * справа — управление положением и под ним пресеты. Список камер скрыт слева: открывается нажатием
 * на значок камеры слева сверху или свайпом от левого края и ложится поверх экрана.
 */
@Composable
private fun CompactTabletLayout(state: CameraScreenState, actions: CameraScreenActions) {
    val camera = state.selectedCamera
    var listOpen by rememberSaveable { mutableStateOf(false) }
    // Список скрыт слева — при входе на экран он выглядывает и качается, подсказывая, что он там
    val listPeek = rememberListPeek()
    // Углы экрана скруглены: элементы в углах отодвигаем, чтобы их не срезало дугой
    val corner = rememberDisplayCornerInset()

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(start = corner, end = maxOf(8.dp, corner), top = maxOf(6.dp, corner), bottom = maxOf(6.dp, corner))
        ) {
            // Узкая полоса слева: значок камеры; свайп вправо по ней (и по краю экрана) открывает список
            var dragged by remember { mutableFloatStateOf(0f) }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .systemGestureExclusion()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onHorizontalDrag = { _, delta ->
                                dragged += delta
                                if (dragged > 40.dp.toPx()) {
                                    listOpen = true
                                    dragged = 0f
                                }
                            }
                        )
                    }
            ) {
                TooltipIconButton(
                    tooltip = "Список камер",
                    onClick = { listOpen = true },
                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Videocam,
                        contentDescription = "Список камер",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Сетка 2×2: все камеры, при нехватке места сетка прокручивается
                TooltipIconButton(
                    tooltip = if (state.isMultiGrid) "Одна камера" else "Показать камеры сеткой 2×2",
                    onClick = actions.onToggleMultiGrid,
                    modifier = Modifier.background(
                        if (state.isMultiGrid) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "Сетка 2×2",
                        tint = if (state.isMultiGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Внизу: добавить камеру и автоматический поиск в сети
                TooltipIconButton(tooltip = "Добавить камеру", onClick = actions.onAddCamera) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить камеру", tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                NetworkScanButton(onClick = actions.onScanNetwork)
                Spacer(modifier = Modifier.height(4.dp))
                QrAddButton(onClick = actions.onAddFromQr)
            }

            if (state.isMultiGrid) {
                // Сетка 2×2: кнопки на плитках, PTZ-жесты отключены (непонятно, какой камерой управлять)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(2f).fillMaxHeight()
                ) {
                    items(state.cameras, key = { it.id }) { tile ->
                        CameraStreamPlayer(
                            camera = tile,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            useMenuControls = true,
                            isGridTile = true,
                            onSelectTile = { actions.onSelectCamera(tile.id) },
                            onOpenTile = { actions.onShowSingleCamera(tile.id) },
                            isSelectedTile = tile.id == camera?.id,
                            onToggleFullscreen = { actions.onFullscreen(tile) },
                            onSelectStreamProfile = { profileId -> actions.onSelectStreamProfile(tile.id, profileId) },
                            onEditCamera = actions.onEditCamera,
                            onReconnectCamera = actions.onReconnectCamera
                        )
                    }
                }
            } else {
                CameraStreamPlayer(
                    camera = camera,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
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
            }

            // Справа: управление положением, под ним — пресеты
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScrollWithBar()
                    .padding(end = 6.dp)
            ) {
                TabletControlPanels(camera, state, actions, joystickSize = 150.dp, focusButtonsOnly = true)
                if (camera != null && state.hasPtz(camera)) PresetsCard(camera, actions)
                ArchivePreviewCard(camera)
            }
        }

        // Список камер поверх экрана: тап по затемнению или свайп влево закрывает
        CameraListOverlay(
            visible = listOpen,
            onClose = { listOpen = false },
            state = state,
            actions = actions,
            corner = corner,
            peek = if (listOpen) 0.dp else listPeek
        )
    }
}
