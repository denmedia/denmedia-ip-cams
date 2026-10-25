package com.example.denmediaipcams.ui.layouts

import com.example.denmediaipcams.ui.components.TooltipIconButton

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.FocusAvailability
import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.network.DahuaApi
import com.example.denmediaipcams.network.PtzController
import com.example.denmediaipcams.model.FocusDirection
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.player.ThumbnailStore
import com.example.denmediaipcams.ui.components.PlayerAction
import com.example.denmediaipcams.ui.components.PlayerActions
import com.example.denmediaipcams.ui.components.PlayerActionsStyle
import com.example.denmediaipcams.ui.components.PtzJoystick
import com.example.denmediaipcams.ui.components.ThumbnailImage
import com.example.denmediaipcams.ui.components.ZoomButtons

/** Данные экрана камер */
class CameraScreenState(
    val cameras: List<IpCamera>,
    val selectedCamera: IpCamera?,
    val isMultiGrid: Boolean,
    val lastPtzActionAtMs: Long,
    val cameraOnline: Map<String, Boolean>,
    /** Цифровое увеличение кадра по камерам (для камер без оптического зума): id камеры → кратность */
    val digitalZoom: Map<String, Float> = emptyMap(),
    /** Режим день/ночь: id камеры → DayNightMode */
    val dayNightMode: Map<String, DayNightMode> = emptyMap(),
    /** Поддержка переключения день/ночь: id камеры → поддерживается ли */
    val dayNightSupported: Map<String, Boolean> = emptyMap(),
    /** Возможности объектива, которые камера сообщила сама (Dahua): id камеры → зум и фокус */
    val lensCaps: Map<String, DahuaApi.LensCaps> = emptyMap(),
    /** Поворот, зум и фокус, которые камера сообщила сама (ONVIF, Hikvision): id камеры → возможности */
    val ptzCaps: Map<String, PtzController.PtzCaps> = emptyMap(),
    /** Задержка ответа камеры, мс: id камеры → значение; нет записи — камера не отвечает */
    val cameraLatency: Map<String, Long> = emptyMap()
) {
    fun latencyOf(camera: IpCamera): Long? = cameraLatency[camera.id]
    val onlineCount: Int get() = cameras.count { cameraOnline[it.id] == true }
    fun isOnline(camera: IpCamera) = cameraOnline[camera.id] == true
    fun digitalZoomOf(camera: IpCamera?): Float = camera?.let { digitalZoom[it.id] } ?: 1f
    fun isChecked(camera: IpCamera) = cameraOnline.containsKey(camera.id)
    fun dayNightModeOf(camera: IpCamera?): DayNightMode? = camera?.let { dayNightMode[it.id] }
    /** У камеры есть PTZ: выбран протокол PTZ, и камера не сообщила, что у неё нет ни поворота, ни зума */
    fun hasPtz(camera: IpCamera?): Boolean =
        camera != null && camera.protocol.supportsPtz && ptzCaps[camera.id]?.none != true
    /** Поворот (джойстик) скрывается, только если камера явно сообщила, что его нет */
    fun hasPanTilt(camera: IpCamera?): Boolean = hasPtz(camera) && ptzCaps[camera!!.id]?.panTilt != false
    /** Зум недоступен, только если камера явно сообщила, что его нет */
    fun isZoomSupported(camera: IpCamera?): Boolean =
        camera?.let { lensCaps[it.id]?.zoom != false && ptzCaps[it.id]?.zoom != false } ?: false
    /**
     * Управляемый фокус: команды фокуса идут по протоколу PTZ, поэтому нужен протокол PTZ (не режим повтора —
     * у таких камер фокуса нет). Дальше решает выбор в настройках камеры, а при «авто» — ответ камеры
     * (Dahua — возможности объектива, ONVIF и Hikvision — [ptzCaps]); пока камера не ответила, фокус показан
     */
    fun isFocusSupported(camera: IpCamera?): Boolean {
        if (camera == null || !camera.protocol.supportsPtz || camera.ptzRepeatMode) return false
        return when (camera.focusAvailability) {
            FocusAvailability.YES -> true
            FocusAvailability.NO -> false
            FocusAvailability.AUTO -> lensCaps[camera.id]?.focus != false && ptzCaps[camera.id]?.focus != false
        }
    }
    fun isDayNightSupported(camera: IpCamera?): Boolean = camera?.let { dayNightSupported[it.id] ?: true } ?: false
}

/** Все действия экрана камер — общие для раскладок планшета и смартфона */
class CameraScreenActions(
    val onSelectCamera: (String) -> Unit,
    /** Выбрать камеру и перейти в режим «1 камера» (нажатие в списке, двойное нажатие по плитке сетки) */
    val onShowSingleCamera: (String) -> Unit,
    val onAddCamera: () -> Unit,
    val onScanNetwork: () -> Unit,
    /** Добавить камеру по QR коду из «Поделиться» другого устройства */
    val onAddFromQr: () -> Unit,
    val onEditCamera: (IpCamera) -> Unit,
    /** Показать QR код с настройками камеры, чтобы добавить её на другом устройстве */
    val onShareCamera: (IpCamera) -> Unit,
    val onDeleteCamera: (String) -> Unit,
    val onMoveCamera: (from: Int, to: Int) -> Unit,
    val onReconnectCamera: (String) -> Unit,
    val onToggleMultiGrid: () -> Unit,
    val onFullscreen: (IpCamera) -> Unit,
    val onMove: (Float, Float) -> Unit,
    val onZoom: (Float) -> Unit,
    val onStop: () -> Unit,
    val onFocus: (FocusDirection) -> Unit,
    val onFocusMove: (Float) -> Unit,
    val onFocusStop: () -> Unit,
    val onSetDayNightMode: (DayNightMode) -> Unit,
    val onGoToPreset: (Int) -> Unit,
    val onSavePreset: (Int) -> Unit,
    val onAddPreset: () -> Unit,
    val onManagePresets: () -> Unit,
    val onSelectStreamProfile: (cameraId: String, profileId: String) -> Unit,
    val onSelectProtocol: (cameraId: String, protocol: CameraProtocol) -> Unit
)

/** Карточка-панель с заголовком и необязательной ссылкой справа («Управление») */
@Composable
internal fun PanelCard(
    title: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    headerTrailing: @Composable (() -> Unit)? = null,
    contentPadding: Dp = 14.dp,
    /** Содержимое по центру высоты карточки — для карточек одной высоты в ряду */
    centerContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        Column(
            verticalArrangement = if (centerContent) Arrangement.Center else Arrangement.Top,
            modifier = (if (centerContent) Modifier.fillMaxHeight() else Modifier).padding(
                start = contentPadding,
                end = contentPadding,
                bottom = contentPadding,
                // Над заголовком отступ меньше: кнопка справа и так задаёт высоту строки
                top = if (title != null || headerTrailing != null) (contentPadding - 6.dp).coerceAtLeast(0.dp) else contentPadding
            )
        ) {
            if (title != null || headerTrailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (headerTrailing != null) {
                        headerTrailing()
                    }
                    if (actionLabel != null) {
                        // Без обязательной зоны нажатия 48 dp, иначе строка заголовка раздувается и отступ сверху кажется большим
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            TextButton(
                                onClick = onAction,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            content()
        }
    }
}

/**
 * Камера в списке: превью, название, адрес, статус и индикатор сигнала.
 * Двойное нажатие открывает настройки камеры; долгое нажатие и перетаскивание меняют порядок
 * (его обрабатывает [modifier] из списка).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CameraListItem(
    camera: IpCamera,
    isSelected: Boolean,
    isOnline: Boolean,
    isChecked: Boolean,
    latencyMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDoubleClick: (() -> Unit)? = null,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = shape
            )
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                }
            )
            .padding(if (compact) 6.dp else 8.dp)
    ) {
        ThumbnailImage(
            key = ThumbnailStore.cameraKey(camera.id),
            modifier = Modifier
                .size(width = if (compact) 64.dp else 92.dp, height = if (compact) 40.dp else 58.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = camera.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!compact) {
                Text(
                    text = cameraSubtitle(camera),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            CameraOnlineStatus(isOnline = isOnline, isChecked = isChecked)
        }
        if (trailing != null) {
            trailing()
        } else {
            SignalLevelIcon(
                cameraId = camera.id,
                pingMs = if (isOnline) latencyMs else null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(18.dp)
            )
        }
    }
}

/** Подзаголовок камеры: адрес и протокол PTZ */
internal fun cameraSubtitle(camera: IpCamera): String {
    val address = android.net.Uri.parse(camera.rtspUrlWithoutCredentials).host ?: camera.ipAddress
    return "$address · ${camera.protocol.displayName}"
}

/** Круглая иконка камеры в шапках */
@Composable
internal fun CameraBadgeIcon(icon: ImageVector = Icons.Outlined.Videocam, size: Dp = 44.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Шапка выбранной камеры (планшет): название, адрес, статус, кнопки плеера
 * и меню (сетка 2x2, редактирование, удаление).
 */
@Composable
internal fun CameraHeader(
    camera: IpCamera?,
    state: CameraScreenState,
    actions: CameraScreenActions,
    modifier: Modifier = Modifier
) {
    // Действия камеры (раньше в меню ⋮) идут в один ряд с кнопками плеера:
    // что не помещается по ширине — уходит в общую кнопку «три точки»
    val cameraActions = buildList {
        add(
            PlayerAction(
                icon = Icons.Default.GridView,
                description = if (state.isMultiGrid) "Одна камера" else "Сетка 2×2",
                priority = 60,
                onClick = actions.onToggleMultiGrid
            )
        )
        if (camera != null) {
            add(
                PlayerAction(
                    icon = Icons.Outlined.Refresh,
                    description = "Переподключиться",
                    priority = 90,
                    onClick = { actions.onReconnectCamera(camera.id) }
                )
            )
            add(
                PlayerAction(
                    icon = Icons.Outlined.Settings,
                    description = "Настройка",
                    priority = 100,
                    onClick = { actions.onEditCamera(camera) }
                )
            )
            add(
                PlayerAction(
                    icon = Icons.Outlined.Share,
                    description = "Поделиться",
                    priority = 105,
                    onClick = { actions.onShareCamera(camera) }
                )
            )
            add(
                PlayerAction(
                    icon = Icons.Outlined.Delete,
                    description = "Удалить камеру",
                    destructive = true,
                    priority = 110,
                    onClick = { actions.onDeleteCamera(camera.id) }
                )
            )
        }
    }

    PanelCard(title = null, modifier = modifier, contentPadding = 10.dp) {
        BoxWithConstraints {
        // Под кнопки отдаём всё, кроме значка камеры, отступов и минимальной ширины названия
        val actionsMaxWidth = (maxWidth - 40.dp - 12.dp - 160.dp).coerceAtLeast(0.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CameraBadgeIcon()
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = camera?.name ?: "Камера не выбрана",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (camera != null) {
                    Text(
                        text = cameraSubtitle(camera),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    CameraOnlineStatus(isOnline = state.isOnline(camera), isChecked = state.isChecked(camera))
                }
            }

            PlayerActions(
                cameraId = camera?.id,
                style = PlayerActionsStyle.Header,
                isFullscreen = false,
                onToggleFullscreen = { camera?.let(actions.onFullscreen) },
                extraActions = cameraActions,
                maxWidth = actionsMaxWidth
            )
        }
        }
    }
}

/** Меню ⋮: сетка 2x2, редактирование и удаление камеры */
@Composable
internal fun CameraMenu(camera: IpCamera?, isMultiGrid: Boolean, actions: CameraScreenActions, showGridToggle: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(tooltip = "Меню камеры", onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Меню камеры")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showGridToggle) {
                DropdownMenuItem(
                    text = { Text(if (isMultiGrid) "Одна камера" else "Сетка 2×2") },
                    leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onToggleMultiGrid()
                    }
                )
            }
            if (camera != null) {
                DropdownMenuItem(
                    text = { Text("Переподключиться") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onReconnectCamera(camera.id)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Настройка") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onEditCamera(camera)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Поделиться") },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onShareCamera(camera)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Удалить камеру", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        expanded = false
                        actions.onDeleteCamera(camera.id)
                    }
                )
            }
        }
    }
}

/** Поворот камеры: джойстик, справа от него зум «+» и «−» (вся группа по центру блока) */
@Composable
internal fun PtzRotationPanel(
    actions: CameraScreenActions,
    joystickSize: Dp,
    camera: IpCamera?,
    zoomUnavailable: Boolean,
    /** Камера сообщила, что поворота нет (только зум): джойстик не выводится */
    panTiltHidden: Boolean = false,
    modifier: Modifier = Modifier,
    fixedSize: Boolean = false
) {
    val content: @Composable (Dp) -> Unit = { size ->
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!panTiltHidden) {
                Box(modifier = Modifier.ptzUnavailable(camera == null || !camera.protocol.supportsPtz)) {
                    PtzJoystick(size = size, onMove = actions.onMove, onStop = actions.onStop)
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            ZoomButtons(
                onZoomChange = actions.onZoom,
                onStop = actions.onStop,
                modifier = Modifier.ptzUnavailable(zoomUnavailable)
            )
        }
    }
    if (fixedSize) {
        // Размер задан снаружи (BoxWithConstraints не терпит измерения по содержимому)
        Box(modifier = modifier) { content(joystickSize) }
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            // В узкой колонке джойстик уменьшается, чтобы кнопки зума поместились рядом (44 dp кнопка + 16 dp зазор)
            content(minOf(joystickSize, maxWidth - 60.dp))
        }
    }
}

/** Пояснение над неактивными панелями управления камеры без PTZ */
@Composable
internal fun NoPtzMessage(
    /** Протокол PTZ выбран, но камера сама сообщила, что поворота и зума нет */
    reportedByCamera: Boolean = false,
    modifier: Modifier = Modifier
) {
    Text(
        text = if (reportedByCamera) {
            "Камера сообщила, что поворота и зума у неё нет, поэтому управление скрыто."
        } else {
            "Управление поворотом и зумом для этой камеры недоступно. " +
                "Если камера поворотная, выберите протокол PTZ в её настройках или нажмите «Определить автоматически»."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Панель управления, недоступная для камеры: полупрозрачная и не реагирует на нажатия.
 * События гасятся на проходе Initial, то есть раньше, чем их увидят вложенные кнопки и джойстик.
 */
internal fun Modifier.ptzUnavailable(unavailable: Boolean): Modifier =
    if (!unavailable) this else this
        .alpha(0.38f)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }

/** Заголовок раздела с иконкой приложения: «Камеры» и сводка */
@Composable
internal fun ScreenTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Экран на чёрном фоне при отсутствии добавленных камер */
@Composable
fun EmptyCamerasView(
    onAddCamera: () -> Unit,
    onScanNetwork: () -> Unit,
    onAddFromQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Нет добавленных камер",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Добавьте камеру вручную по IP-адресу, найдите доступные устройства в локальной сети или отсканируйте QR код камеры с другого устройства.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onAddCamera,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить камеру")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onScanNetwork,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Найти камеры в локальной сети", textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onAddFromQr,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить по QR коду", textAlign = TextAlign.Center)
            }
        }
    }
}
