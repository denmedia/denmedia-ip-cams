package com.example.denmediaipcams.ui.layouts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.ui.components.CameraStreamPlayer
import com.example.denmediaipcams.ui.components.LazyColumnWithBar
import com.example.denmediaipcams.ui.components.ReorderableLazyColumn
import com.example.denmediaipcams.ui.components.NetworkScanButton
import com.example.denmediaipcams.ui.components.QrAddButton
import com.example.denmediaipcams.ui.components.TooltipIconButton
import com.example.denmediaipcams.ui.components.rememberDisplayCornerInset
import com.example.denmediaipcams.ui.components.verticalScrollWithBar

/**
 * Смартфон по вертикали — то же устройство, что и по горизонтали: значок камеры слева сверху открывает
 * список камер поверх экрана (или свайп от левого края), рядом — кнопка сетки 2×2. Ниже на всю ширину
 * видео, затем управление положением карточками, под ним пресеты. Внизу — «Добавить» и «Искать».
 */
@Composable
fun PortraitScreen(state: CameraScreenState, actions: CameraScreenActions) {
    if (state.cameras.isEmpty()) {
        EmptyCamerasView(
            onAddCamera = actions.onAddCamera,
            onScanNetwork = actions.onScanNetwork,
            onAddFromQr = actions.onAddFromQr
        )
        return
    }

    val camera = state.selectedCamera
    // Углы экрана скруглены: элементы в углах отодвигаем
    val corner = rememberDisplayCornerInset()
    var listOpen by rememberSaveable { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }

    val peek = rememberListPeek()
    // Смартфон: в сетке 2×2 весь интерфейс на плеерах скрыт и не показывается
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val hideTileUi = minOf(configuration.screenWidthDp, configuration.screenHeightDp) < 600

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Верхняя строка: список камер, название и статус выбранной камеры, сетка 2×2
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = maxOf(8.dp, corner), end = maxOf(8.dp, corner), top = maxOf(4.dp, corner))
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
                Spacer(modifier = Modifier.width(10.dp))
                // Название и статус камеры открывают список так же, как значок слева
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { listOpen = true }
                ) {
                    Text(
                        text = camera?.name ?: "Камера не выбрана",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (camera != null) {
                        CameraOnlineStatus(isOnline = state.isOnline(camera), isChecked = state.isChecked(camera))
                    }
                }
                // Добавление и поиск камер (в выезжающем списке, как на смартфоне по горизонтали, их нет)
                TooltipIconButton(tooltip = "Добавить камеру", onClick = actions.onAddCamera) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить камеру", tint = MaterialTheme.colorScheme.primary)
                }
                NetworkScanButton(onClick = actions.onScanNetwork)
                QrAddButton(onClick = actions.onAddFromQr)
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
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScrollWithBar()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (state.isMultiGrid) {
                    // Сетка 2×2 вручную (внутри прокручиваемой колонки ленивая сетка недопустима);
                    // кнопки на плитках, PTZ-жесты отключены — непонятно, какой камерой управлять
                    val tiles = state.cameras
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { tile ->
                                CameraStreamPlayer(
                                    camera = tile,
                                    modifier = Modifier.weight(1f).aspectRatio(4f / 3f),
                                    useMenuControls = true,
                                    isGridTile = true,
                                    onSelectTile = { actions.onSelectCamera(tile.id) },
                                    onOpenTile = { actions.onShowSingleCamera(tile.id) },
                                    isSelectedTile = tile.id == camera?.id,
                                    overlayHidden = hideTileUi,
                                    onToggleFullscreen = { actions.onFullscreen(tile) },
                                    onSelectStreamProfile = { profileId -> actions.onSelectStreamProfile(tile.id, profileId) },
                                    onEditCamera = actions.onEditCamera,
                                    onReconnectCamera = actions.onReconnectCamera
                                )
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    // Видео на всю ширину; действия и выбор потока — в меню «три точки» поверх видео
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
                }

                // Управление положением (как на планшете), под ним — пресеты
                // На широком экране (планшет) поворот и «день / ночь + фокус» — в одном ряду одной высоты
                val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                val sideBySide = screenWidth >= 600.dp
                // Ширина половины ряда минус поля колонки, зазор, отступы карточки, кнопки зума
                val joystick = if (sideBySide) minOf(190.dp, (screenWidth - 28.dp - 12.dp) / 2 - 28.dp - 60.dp) else 190.dp
                // Смартфон в сетке 2×2: блок поворота, зума и фокуса скрыт
                if (!(state.isMultiGrid && hideTileUi)) {
                    TabletControlPanels(camera, state, actions, joystickSize = joystick, sideBySide = sideBySide)
                }
                if (camera != null && state.hasPtz(camera)) PresetsCard(camera, actions)
                ArchivePreviewCard(camera)
            }
        }

        // Свайп вправо от левого края открывает список камер
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(16.dp)
                .fillMaxHeight(0.6f)
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
        )

        CameraListOverlay(
            visible = listOpen,
            onClose = { listOpen = false },
            state = state,
            actions = actions,
            corner = corner,
            peek = if (listOpen) 0.dp else peek
        )
    }
}

/**
 * Подсказка, что слева спрятан список камер: при входе на экран он выглядывает из-за края, один раз качается
 * вправо-влево и уезжает обратно (~3 с). На планшете выглядывает на 15% больше, чем на смартфоне.
 * Возвращает, насколько панель сейчас выдвинута.
 */
@Composable
internal fun rememberListPeek(): androidx.compose.ui.unit.Dp {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTablet = minOf(configuration.screenWidthDp, configuration.screenHeightDp) >= 600
    val out = if (isTablet) 56f * 1.15f else 56f
    val back = if (isTablet) 24f * 1.15f else 24f
    val peek = remember { Animatable(0f) }
    val prefs = androidx.compose.ui.platform.LocalContext.current.applicationContext
        .getSharedPreferences("denmedia_ui_hints", android.content.Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        // Показываем только при самом первом открытии приложения; повороты и повторные запуски — без анимации
        if (prefs.getBoolean("list_peek_shown", false)) return@LaunchedEffect
        prefs.edit().putBoolean("list_peek_shown", true).apply()
        delay(400)
        peek.animateTo(out, tween(500))
        repeat(1) {
            peek.animateTo(back, tween(400))
            peek.animateTo(out, tween(400))
        }
        peek.animateTo(0f, tween(600))
    }
    return peek.value.dp
}

/**
 * Список камер, выезжающий слева поверх экрана: тап по затемнению или свайп влево закрывает.
 * @param itemActions показывать у камеры меню «⋮» (переподключить, настройка, удалить)
 * @param addAndScan показывать внизу панели кнопки «Добавить» и «Искать»
 */
@Composable
internal fun CameraListOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    state: CameraScreenState,
    actions: CameraScreenActions,
    corner: androidx.compose.ui.unit.Dp,
    itemActions: Boolean = false,
    addAndScan: Boolean = false,
    peek: androidx.compose.ui.unit.Dp = 0.dp
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
        )
    }
    val panelWidth = 300.dp
    val slide by animateDpAsState(if (visible) 0.dp else -panelWidth, tween(300), label = "cameraListSlide")
    // Подсказка «здесь есть список»: панель чуть выглядывает из-за левого края
    val panelOffset = slide + peek
    if (panelOffset > -panelWidth) {
        var dragged by remember { mutableFloatStateOf(0f) }
        Column(
            modifier = Modifier
                .offset(x = panelOffset)
                .width(panelWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onHorizontalDrag = { _, delta ->
                            dragged += delta
                            if (dragged < -40.dp.toPx()) {
                                onClose()
                                dragged = 0f
                            }
                        }
                    )
                }
                .padding(start = maxOf(10.dp, corner), end = 10.dp, top = maxOf(6.dp, corner), bottom = maxOf(6.dp, corner))
        ) {
            ScreenTitle(
                title = "Камеры",
                subtitle = camerasSummary(state.cameras.size, state.onlineCount),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            ReorderableLazyColumn(
                items = state.cameras,
                key = { it.id },
                onMove = actions.onMoveCamera,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) { item, itemModifier ->
                CameraListItem(
                    camera = item,
                    isSelected = item.id == state.selectedCamera?.id,
                    isOnline = state.isOnline(item),
                    isChecked = state.isChecked(item),
                    latencyMs = state.latencyOf(item),
                    onClick = {
                        actions.onShowSingleCamera(item.id)
                        onClose()
                    },
                    onDoubleClick = { actions.onEditCamera(item) },
                    modifier = itemModifier,
                    compact = true,
                    trailing = if (itemActions) {
                        { ListItemActions(item, actions, forceMenu = true) }
                    } else null
                )
            }
            if (addAndScan) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    val buttonColors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                    FilledTonalButton(
                        onClick = {
                            onClose()
                            actions.onAddCamera()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = buttonColors,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Добавить", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            onClose()
                            actions.onScanNetwork()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = buttonColors,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Искать", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            onClose()
                            actions.onAddFromQr()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = buttonColors,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("QR код", maxLines = 1)
                    }
                }
            }
        }
    }
}
