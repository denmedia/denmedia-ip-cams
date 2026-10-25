package com.example.denmediaipcams.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.denmediaipcams.data.SnapshotSaver
import com.example.denmediaipcams.network.CameraTalkback
import com.example.denmediaipcams.player.LocalStreamSessionManager
import com.example.denmediaipcams.player.StreamSession
import com.example.denmediaipcams.player.TalkState
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider

import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings

/** Где показываются кнопки плеера */
enum class PlayerActionsStyle {
    /** Поверх видео (полноэкранный режим, плитки сетки) */
    Overlay,

    /** Панель под видео на всю ширину (смартфон) */
    Bar,

    /** Иконки в шапке камеры (планшет) */
    Header,

    /** Одна кнопка «три точки» поверх видео с меню всех действий (смартфон) */
    Menu
}

/**
 * Кнопки плеера: «Инфо», звук, снимок кадра, полноэкранный режим.
 * Работают с сессией потока камеры — где бы ни были расположены относительно самого видео.
 */
@Composable
fun PlayerActions(
    cameraId: String?,
    style: PlayerActionsStyle,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onEditCamera: (() -> Unit)? = null,
    onReconnectCamera: (() -> Unit)? = null,
    // Выбор потока в меню «три точки»; null — пункты выбора не показываются
    onSelectStreamProfile: ((profileId: String) -> Unit)? = null,
    // Style.Header: дополнительные действия после кнопок плеера и доступная ширина под все кнопки
    extraActions: List<PlayerAction> = emptyList(),
    maxWidth: Dp = Dp.Unspecified
) {
    val session = cameraId?.let { LocalStreamSessionManager.current.sessionFor(it) }
    val snapshot = rememberSnapshotAction(session)
    var showOnvif by remember { mutableStateOf(false) }
    var showImaging by remember { mutableStateOf(false) }
    val archiveHost = LocalArchiveHost.current
    var menuExpanded by remember { mutableStateOf(false) }
    val hasDahuaApi = session?.camera?.hasDahuaApi == true
    val hasArchive = hasDahuaApi || session?.camera?.sdHttpArchive == true || session?.camera?.hikvisionArchive == true ||
        session?.camera?.dvripArchive == true

    // Обратная связь: кнопка есть только у камер, динамик которых приложение умеет задействовать
    val context = LocalContext.current
    val canTalk = session?.camera?.let { CameraTalkback.isSupported(it) } == true && session?.talkRefused != true
    val isTalking = session != null && session.talkState != TalkState.IDLE
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session?.startTalk() else Toast.makeText(context, "Без доступа к микрофону разговор с камерой невозможен", Toast.LENGTH_LONG).show()
    }
    val toggleTalk = {
        when {
            session == null -> Unit
            session.talkState != TalkState.IDLE -> session.stopTalk()
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> session.startTalk()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val talkDescription = when (session?.talkState) {
        TalkState.CONNECTING -> "Подключение… Нажмите, чтобы отменить"
        TalkState.TALKING -> "Говорите… Нажмите, чтобы закончить разговор"
        else -> "Говорить в камеру (микрофон планшета → динамик камеры)"
    }

    val buttons = listOfNotNull(
        if (hasArchive) PlayerAction(
            icon = Icons.Outlined.VideoLibrary,
            description = "Архив записей",
            priority = 50,
            onClick = { session?.camera?.let { archiveHost?.open(it.id) } }
        ) else null,
        PlayerAction(
            icon = Icons.Outlined.Videocam,
            label = "ONVIF",
            priority = 80,
            description = "Информация о камере по ONVIF",
            menuLabel = "Инфо о камере (ONVIF)",
            enabled = session != null,
            onClick = { showOnvif = true }
        ),
        PlayerAction(
            icon = Icons.Outlined.Tune,
            description = "Яркость, контраст, насыщенность и резкость",
            menuLabel = "Изображение",
            enabled = session != null,
            priority = 75,
            onClick = { showImaging = true }
        ),
        PlayerAction(
            icon = Icons.Outlined.Info,
            description = if (session?.showInfo == true) "Скрыть информацию" else "Информация о потоке",
            active = session?.showInfo == true,
            enabled = session != null,
            priority = 70,
            onClick = { session?.let { it.showInfo = !it.showInfo } }
        ),
        if (canTalk) PlayerAction(
            icon = if (isTalking) Icons.Filled.Mic else Icons.Outlined.Mic,
            description = talkDescription,
            menuLabel = if (isTalking) "Закончить разговор" else "Говорить в камеру",
            active = isTalking,
            priority = 40,
            onClick = toggleTalk
        ) else null,
        PlayerAction(
            icon = if (session?.isMuted == true) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            description = if (session?.isMuted == true) "Включить звук" else "Выключить звук",
            active = session?.isMuted == true,
            enabled = session != null,
            priority = 30,
            onClick = { session?.toggleMute() }
        ),
        PlayerAction(
            icon = Icons.Outlined.CameraAlt,
            description = "Сделать снимок",
            enabled = session != null && !snapshot.isSaving,
            isLoading = snapshot.isSaving,
            priority = 20,
            onClick = snapshot.run
        ),
        PlayerAction(
            icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            description = if (isFullscreen) "Выйти из полноэкранного режима" else "Полноэкранный режим",
            priority = 10,
            onClick = onToggleFullscreen
        )
    )

    if (isFullscreen || style == PlayerActionsStyle.Menu) {
        // В полноэкранном режиме и на смартфоне все кнопки прячутся в меню "три точки" справа сверху
        Box(modifier = modifier) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp)
            ) {
                TooltipIconButton(tooltip = "Меню плеера", onClick = { menuExpanded = true }, position = TooltipSide.Below) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Меню настроек плеера",
                        tint = Color.White
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                // Выбор потока (качества), если у камеры их несколько
                val camera = session?.camera
                if (onSelectStreamProfile != null && camera != null && camera.streamProfiles.size > 1) {
                    val activeId = camera.activeStreamProfile?.id
                    Text(
                        text = "Поток",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    camera.streamProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            leadingIcon = {
                                if (profile.id == activeId) Icon(Icons.Default.Check, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onSelectStreamProfile(profile.id)
                            }
                        )
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text("Инфо о камере (ONVIF)") },
                    leadingIcon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                    enabled = session != null,
                    onClick = {
                        menuExpanded = false
                        showOnvif = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Изображение") },
                    leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    enabled = session != null,
                    onClick = {
                        menuExpanded = false
                        showImaging = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (session?.showInfo == true) "Скрыть инфо о потоке" else "Информация о потоке") },
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    trailingIcon = if (session?.showInfo == true) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    enabled = session != null,
                    onClick = {
                        menuExpanded = false
                        session?.let { it.showInfo = !it.showInfo }
                    }
                )
                if (canTalk) {
                    DropdownMenuItem(
                        text = { Text(if (isTalking) "Закончить разговор" else "Говорить в камеру") },
                        leadingIcon = { Icon(if (isTalking) Icons.Filled.Mic else Icons.Outlined.Mic, contentDescription = null) },
                        trailingIcon = if (isTalking) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            menuExpanded = false
                            toggleTalk()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (session?.isMuted == true) "Включить звук" else "Выключить звук") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (session?.isMuted == true) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null
                        )
                    },
                    enabled = session != null,
                    onClick = {
                        menuExpanded = false
                        session?.toggleMute()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Сделать снимок") },
                    leadingIcon = {
                        if (snapshot.isSaving) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        }
                    },
                    enabled = session != null && !snapshot.isSaving,
                    onClick = {
                        menuExpanded = false
                        snapshot.run()
                    }
                )
                if (hasArchive) {
                    DropdownMenuItem(
                        text = { Text("Архив записей") },
                        leadingIcon = { Icon(Icons.Outlined.VideoLibrary, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            session?.camera?.let { archiveHost?.open(it.id) }
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Переподключиться") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        if (onReconnectCamera != null) {
                            onReconnectCamera()
                        } else {
                            session?.reconnect()
                        }
                    }
                )
                if (onEditCamera != null) {
                    DropdownMenuItem(
                        text = { Text("Настройка камеры") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEditCamera()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (isFullscreen) "Выйти из полноэкранного режима" else "Полноэкранный режим") },
                    leadingIcon = {
                        Icon(
                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleFullscreen()
                    }
                )
            }
        }
    } else {
        when (style) {
            PlayerActionsStyle.Overlay -> Row(modifier = modifier) {
                buttons.forEachIndexed { index, action ->
                    if (index > 0) Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (action.active) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        ActionIconButton(action, tint = Color.White)
                    }
                }
            }
            PlayerActionsStyle.Bar -> Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    buttons.forEach { action ->
                        Surface(
                            color = if (action.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            ActionIconButton(
                                action,
                                tint = if (action.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                        }
                    }
                }
            }
            // Обрабатывается выше вместе с полноэкранным режимом
            PlayerActionsStyle.Menu -> Unit
            PlayerActionsStyle.Header -> HeaderActions((buttons + extraActions).sortedBy { it.priority }, maxWidth, modifier)
        }
    }

    if (showOnvif) {
        session?.camera?.let { OnvifInfoDialog(camera = it, onDismiss = { showOnvif = false }) }
    }
    if (showImaging) {
        session?.camera?.let { ImagingDialog(camera = it, onDismiss = { showImaging = false }) }
    }
}

class PlayerAction(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
    /** Текст вместо иконки (кнопка «ONVIF») */
    val label: String? = null,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val isLoading: Boolean = false,
    /** Подпись пункта, когда кнопка спрятана в меню «три точки» */
    val menuLabel: String = description,
    /** Опасное действие (красный цвет) */
    val destructive: Boolean = false,
    /** Порядок в шапке планшета: меньше — левее и позже прячется в «три точки» */
    val priority: Int = 100
)

/** Ширина одной кнопки в шапке (IconButton по умолчанию) */
private val HeaderButtonWidth = 48.dp

/**
 * Кнопки шапки: сколько помещается в [maxWidth] — показываются иконками,
 * остальные прячутся в меню «три точки». Если помещаются все — меню не нужно.
 */
@Composable
private fun HeaderActions(actions: List<PlayerAction>, maxWidth: Dp, modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }
    val capacity = if (maxWidth == Dp.Unspecified) Int.MAX_VALUE else (maxWidth / HeaderButtonWidth).toInt()
    val visibleCount = if (actions.size <= capacity) actions.size else (capacity - 1).coerceAtLeast(0)
    val hidden = actions.drop(visibleCount)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        actions.take(visibleCount).forEach { action ->
            ActionIconButton(
                action,
                tint = when {
                    action.destructive -> MaterialTheme.colorScheme.error
                    action.active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (hidden.isNotEmpty()) {
            Box {
                TooltipIconButton(tooltip = "Ещё", onClick = { menuExpanded = true }, position = TooltipSide.Below) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    hidden.forEach { action ->
                        val color = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified
                        DropdownMenuItem(
                            text = { Text(action.menuLabel, color = color) },
                            leadingIcon = {
                                if (action.isLoading) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                } else {
                                    Icon(action.icon, contentDescription = null, tint = if (action.destructive) color else LocalContentColor.current)
                                }
                            },
                            trailingIcon = if (action.active) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            enabled = action.enabled,
                            onClick = {
                                menuExpanded = false
                                action.onClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIconButton(action: PlayerAction, tint: Color, modifier: Modifier = Modifier) {
    TooltipIconButton(tooltip = action.description, onClick = action.onClick, enabled = action.enabled, modifier = modifier, position = TooltipSide.Below) {
        if (action.label != null) {
            Text(
                text = action.label,
                color = if (action.enabled) tint else tint.copy(alpha = 0.35f),
                style = MaterialTheme.typography.labelMedium
            )
        } else if (action.isLoading) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                imageVector = action.icon,
                contentDescription = action.description,
                tint = if (action.enabled) tint else tint.copy(alpha = 0.35f)
            )
        }
    }
}

class SnapshotAction(val isSaving: Boolean, val run: () -> Unit)

/** Снимок кадра в полном разрешении в «Загрузки/DenIPCam» */
@Composable
fun rememberSnapshotAction(session: StreamSession?): SnapshotAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    return SnapshotAction(isSaving) {
        if (session == null || isSaving) return@SnapshotAction
        if (!session.isPlaying) {
            Toast.makeText(context, "Нет изображения для снимка", Toast.LENGTH_SHORT).show()
            return@SnapshotAction
        }
        isSaving = true
        scope.launch {
            val bitmap = session.captureFrame()
            val fileName = bitmap?.let { SnapshotSaver.saveToDownloads(context, it, session.camera.name) }
            bitmap?.recycle()
            isSaving = false
            val message = if (fileName != null) {
                "Снимок сохранён: Загрузки/${SnapshotSaver.FOLDER_NAME}/$fileName"
            } else {
                "Не удалось сохранить снимок"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
