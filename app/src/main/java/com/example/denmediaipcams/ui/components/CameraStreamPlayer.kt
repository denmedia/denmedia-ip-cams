package com.example.denmediaipcams.ui.components

import android.os.SystemClock
import com.example.denmediaipcams.R
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.rotate
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.denmediaipcams.model.ClockPosition
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.VideoRotation
import com.example.denmediaipcams.network.CameraEventState
import com.example.denmediaipcams.network.RecordingIndicator
import com.example.denmediaipcams.network.RecordingIndicatorState
import com.example.denmediaipcams.player.LocalStreamSessionManager
import com.example.denmediaipcams.player.StreamSession
import com.example.denmediaipcams.ui.layouts.SignalLevel
import com.example.denmediaipcams.ui.layouts.iconAndTint
import com.example.denmediaipcams.ui.layouts.rememberStreamLagMs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LatencyBarColor = Color(0xFF4FC3F7)

/** Высота кнопки «три точки» / кнопок плеера (минимальная область нажатия IconButton) */
private val ActionButtonSize = 48.dp

/**
 * Видео камеры.
 * @param showOverlayControls кнопки (инфо, звук, снимок, полный экран) и название поверх видео —
 *   там, где нет отдельной шапки камеры (полноэкранный режим, плитки сетки 2x2)
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraStreamPlayer(
    camera: IpCamera?,
    modifier: Modifier = Modifier,
    onToggleFullscreen: () -> Unit = {},
    isFullscreen: Boolean = false,
    showOverlayControls: Boolean = true,
    // PTZ-жесты по видео; если не заданы (например, в сетке 2x2), жесты отключены
    onPtzMove: ((pan: Float, tilt: Float) -> Unit)? = null,
    onPtzZoom: ((level: Float) -> Unit)? = null,
    onPtzStop: (() -> Unit)? = null,
    // Время последнего PTZ-действия (SystemClock.elapsedRealtime); 0 — действий не было
    lastPtzActionAtMs: Long = 0L,
    // Цифровое увеличение кадра (камеры без оптического зума): 1 — без увеличения
    digitalZoom: Float = 1f,
    // Выбор потока (качества), если у камеры их несколько; null — переключатель не показывается
    onSelectStreamProfile: ((profileId: String) -> Unit)? = null,
    onEditCamera: ((camera: IpCamera) -> Unit)? = null,
    onReconnectCamera: ((cameraId: String) -> Unit)? = null,
    // Вместо ряда кнопок и выбора потока — одна кнопка «три точки» с общим меню (смартфон)
    useMenuControls: Boolean = false,
    /** Плитка сетки 2×2: если часы справа сверху, кнопка «три точки» уходит в правый нижний угол (как на смартфоне) */
    isGridTile: Boolean = false,
    // Плитка сетки: одно нажатие выбирает камеру (для пресетов и шапки), выбранная плитка обведена
    onSelectTile: (() -> Unit)? = null,
    // Плитка сетки: двойное нажатие вместо полноэкранного режима (переход к этой камере в режиме «1 камера»)
    onOpenTile: (() -> Unit)? = null,
    isSelectedTile: Boolean = false,
    // Все элементы интерфейса поверх видео скрыты и не показываются (плитки сетки на смартфоне в портрете)
    overlayHidden: Boolean = false,
    // Одно нажатие по видео скрывает / показывает все элементы интерфейса поверх него;
    // экран, у которого есть свои элементы поверх плеера, узнаёт об этом здесь
    onOverlayVisibilityChange: (visible: Boolean) -> Unit = {},
    // Место справа внизу, занятое кнопкой самого экрана поверх плеера (кнопка пресетов в полноэкранном режиме):
    // часы в правом нижнем углу встают левее неё
    reservedBottomEnd: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (isFullscreen) 0.dp else 14.dp))
            .background(Color.Black)
            .then(
                if (isSelectedTile) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (camera == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Камера не выбрана", color = Color.White)
            }
        } else {
            // Сессия потока живёт в менеджере: при переходе в полноэкранный режим и обратно
            // камера не переподключается — новый экран подхватывает то же соединение
            val manager = LocalStreamSessionManager.current
            val session = remember(camera.id) { manager.acquire(camera) }
            DisposableEffect(session) {
                onDispose { manager.release(session) }
            }
            LaunchedEffect(session, camera) { session.updateCamera(camera) }

            StreamContent(
                session = session,
                isFullscreen = isFullscreen,
                showOverlayControls = showOverlayControls,
                onToggleFullscreen = onToggleFullscreen,
                onPtzMove = onPtzMove,
                onPtzZoom = onPtzZoom,
                onPtzStop = onPtzStop,
                lastPtzActionAtMs = lastPtzActionAtMs,
                digitalZoom = digitalZoom,
                onSelectStreamProfile = onSelectStreamProfile,
                onEditCamera = onEditCamera,
                onReconnectCamera = onReconnectCamera,
                useMenuControls = useMenuControls,
                isGridTile = isGridTile,
                onSelectTile = onSelectTile,
                onOpenTile = onOpenTile,
                overlayHidden = overlayHidden,
                onOverlayVisibilityChange = onOverlayVisibilityChange,
                reservedBottomEnd = reservedBottomEnd
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun StreamContent(
    session: StreamSession,
    isFullscreen: Boolean,
    showOverlayControls: Boolean,
    onToggleFullscreen: () -> Unit,
    onPtzMove: ((pan: Float, tilt: Float) -> Unit)?,
    onPtzZoom: ((level: Float) -> Unit)?,
    onPtzStop: (() -> Unit)?,
    lastPtzActionAtMs: Long,
    digitalZoom: Float,
    onSelectStreamProfile: ((String) -> Unit)?,
    onEditCamera: ((camera: IpCamera) -> Unit)?,
    onReconnectCamera: ((cameraId: String) -> Unit)?,
    useMenuControls: Boolean,
    isGridTile: Boolean,
    onSelectTile: (() -> Unit)?,
    onOpenTile: (() -> Unit)?,
    overlayHidden: Boolean,
    onOverlayVisibilityChange: (Boolean) -> Unit,
    reservedBottomEnd: Dp
) {
    val camera = session.camera

    // Одно нажатие по видео скрывает / показывает кнопки, плашки, часы и прочее поверх него
    var overlayVisible by remember { mutableStateOf(true) }
    val currentOnOverlayVisibilityChange by rememberUpdatedState(onOverlayVisibilityChange)
    LaunchedEffect(overlayVisible) { currentOnOverlayVisibilityChange(overlayVisible) }

    val configuration = LocalConfiguration.current
    val isPhone = minOf(configuration.screenWidthDp, configuration.screenHeightDp) < 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    // Если часы сверху слева (в полноэкранном режиме на смартфоне в портрете — и сверху по центру),
    // плашки качества и названия камеры не помещаются рядом с ними и уходят вниз слева
    val badgesAtBottom = camera.showDateTime && (
        camera.clockPosition == ClockPosition.TOP_START ||
            (camera.clockPosition == ClockPosition.TOP_CENTER && isFullscreen && isPhone && !isLandscape)
        )
    // Левый нижний угол занят: плашками или часами — метку цифрового зума поднимаем выше
    val bottomStartBusy = badgesAtBottom || (camera.showDateTime && camera.clockPosition == ClockPosition.BOTTOM_START)

    // Полоска задержки: стартует с последнего PTZ-действия и заполняется ровно за то время,
    // на которое трансляция отстаёт от реальности — когда она дойдёт до конца, на видео
    // будет виден результат действия
    val latencyProgress = remember { Animatable(0f) }
    var isLatencyBarVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastPtzActionAtMs) {
        if (lastPtzActionAtMs <= 0L) return@LaunchedEffect
        val latencyMs = session.latencyEstimateMs().coerceAtLeast(1L)
        val elapsedMs = SystemClock.elapsedRealtime() - lastPtzActionAtMs
        if (elapsedMs >= latencyMs) return@LaunchedEffect
        isLatencyBarVisible = true
        latencyProgress.snapTo(elapsedMs.toFloat() / latencyMs)
        latencyProgress.animateTo(1f, tween((latencyMs - elapsedMs).toInt(), easing = LinearEasing))
        isLatencyBarVisible = false
    }

    // Двойное нажатие по видео — войти в полноэкранный режим или выйти из него (на плитке сетки — открыть камеру одну).
    // Жесты поворота/зума этому не мешают: перетаскивание и щипок отменяют распознавание нажатия
    val currentOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val currentOnOpenTile by rememberUpdatedState(onOpenTile)
    // Долгое нажатие по видео — настройки камеры (перетаскивание для поворота его отменяет)
    val currentOnEditCamera by rememberUpdatedState(onEditCamera)
    val currentOnSelectTile by rememberUpdatedState(onSelectTile)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    // На плитке сетки нажатие выбирает камеру, а кнопки остаются на виду
                    onTap = { currentOnSelectTile?.invoke() ?: run { overlayVisible = !overlayVisible } },
                    onDoubleTap = { currentOnOpenTile?.invoke() ?: currentOnToggleFullscreen() },
                    onLongPress = { currentOnEditCamera?.invoke(session.camera) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Программно поворачивается только то, что не сделала сама камера (см. CameraRotation)
        val rotationDegrees = camera?.softwareRotationDegrees?.toFloat() ?: 0f

        // Логирование для отладки
        LaunchedEffect(camera?.id, rotationDegrees) {
            android.util.Log.d("CameraStreamPlayer", "Camera: ${camera?.name}, VideoRotation: ${camera?.videoRotation}, hardware: ${camera?.hardwareRotation}, software: $rotationDegrees")
        }

        // Цифровой зум: видео-поверхность делается крупнее области показа и обрезается по центру
        // (изменение размера, а не масштаб слоя — SurfaceView масштабируется только так)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val zoom = digitalZoom.coerceAtLeast(1f)
            val sideways = rotationDegrees == 90f || rotationDegrees == 270f

            AndroidView(
                factory = { ctx ->
                    // TextureView вместо SurfaceView: только он поддерживает поворот видео
                    (android.view.LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                        player = session.player
                        useController = false
                        keepScreenOn = true
                        // Поверхность нужна сессии для снимков кадра и превью
                        session.attachSurfaceView(videoSurfaceView)
                        tag = session
                    }
                },
                update = { view ->
                    // При смене камеры вид не пересоздаётся, а переходит к другой сессии. Прежняя (она остаётся
                    // открытой) должна его отпустить, иначе снимет кадр новой камеры себе в превью
                    (view.tag as? StreamSession)?.takeIf { it !== session }?.detachSurfaceView(view.videoSurfaceView)
                    view.tag = session
                    view.player = session.player
                    session.attachSurfaceView(view.videoSurfaceView)
                },
                // Отвязываем плеер: новый экран (например, полноэкранный) подключит его к своей поверхности
                onRelease = { view ->
                    val owner = view.tag as? StreamSession ?: session
                    // При повороте экрана раскладка меняется: новый вид уже взял плеер,
                    // и старый не должен отвязывать его от новой поверхности (чёрный экран)
                    val takenByOther = owner.isSurfaceTakenByOther(view.videoSurfaceView)
                    owner.detachSurfaceView(view.videoSurfaceView)
                    if (!takenByOther) view.player = null
                },
                modifier = Modifier
                    // При повороте на 90° стороны меняются местами, чтобы видео после поворота заполнило область
                    .requiredSize(
                        (if (sideways) maxHeight else maxWidth) * zoom,
                        (if (sideways) maxWidth else maxHeight) * zoom
                    )
                    .rotate(rotationDegrees)
            )
        }

        if (digitalZoom > 1.01f && overlayVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Выше плашек качества и названия, если они внизу слева
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = if (bottomStartBusy) 10.dp + ActionButtonSize else 10.dp)
            ) {
                Text(
                    text = "×" + String.format(Locale.US, "%.1f", digitalZoom),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // Жесты по видео: перетаскивание — поворот камеры, щипок — зум
        if (onPtzMove != null && onPtzZoom != null && onPtzStop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .ptzGestures(onMove = onPtzMove, onZoom = onPtzZoom, onStop = onPtzStop)
            )
        }

        // Индикатор загрузки / буферизации
        val connecting = session.isBuffering && !session.hasError
        // Картинка идёт — уведомление небольшое и внизу, чтобы не закрывать видео;
        // картинки нет или она зависла — крупное по центру
        val connectingOnLiveStream = connecting && session.hasLivePicture
        if (connecting && !connectingOnLiveStream) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Подключение к камере ${camera.name}...",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Небольшие уведомления внизу: переподключение при идущей трансляции и временное понижение качества
        val qualityReduced = session.isQualityReduced && !session.hasError
        if (connectingOnLiveStream || qualityReduced) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
            ) {
                if (connectingOnLiveStream) {
                    Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Подключение к камере ${camera.name}...",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (qualityReduced) {
                    Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = "Качество временно понижено",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // Экран ошибки с детальным сообщением и кнопкой "Переподключиться"
        if (session.hasError) {
            Surface(
                color = Color.Black.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .verticalScrollWithBar()
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = session.errorMessage ?: "Не удалось подключиться к камере",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { session.reconnect() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Переподключиться")
                        }

                        if (onSelectStreamProfile != null) {
                            val otherProfile = camera.streamProfiles.firstOrNull { it.id != camera.activeStreamProfile?.id }
                            if (otherProfile != null) {
                                FilledTonalButton(onClick = { onSelectStreamProfile(otherProfile.id) }) {
                                    Icon(Icons.Default.Videocam, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(otherProfile.name)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Оверлей: качество, название, часы и кнопки (скрывается одним нажатием по видео)
        AnimatedVisibility(
            visible = overlayVisible && !overlayHidden,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            val actionsShown = showOverlayControls || useMenuControls
            // Смартфон, не полноэкранный режим: если часы справа сверху, кнопка «три точки» уходит
            // в правый нижний угол. В полноэкранном режиме она всегда сверху справа
            val menuAtBottom = actionsShown && !isFullscreen && camera.menuAtBottomCorner(isPhone || isGridTile)
            // Кнопка «три точки» вверху: плашки качества, названия и выбора потока стоят с ней на одной линии
            // (по центру её высоты), а не прижаты к верхнему краю
            val alignWithActions = actionsShown && !menuAtBottom && !badgesAtBottom
            val badges: @Composable () -> Unit = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (alignWithActions || (badgesAtBottom && isFullscreen)) Modifier.height(ActionButtonSize) else Modifier
                ) {
                    StreamQualityBadge(
                        camera = camera,
                        videoHeight = session.videoHeight,
                        // Выбор потока перенесён в меню «три точки»; на плашке остаётся только метка качества
                        onSelectStreamProfile = if (useMenuControls || (isFullscreen && isPhone)) null else onSelectStreamProfile
                    )
                    if (showOverlayControls) {
                        Spacer(modifier = Modifier.width(6.dp))
                        CameraNameBadge(session)
                    }
                    // Запись на SD-карту: красная — пишет, зелёная — ждёт движения, серая — не пишет, три точки — нет связи
                    Spacer(modifier = Modifier.width(6.dp))
                    RecordingIndicatorDot(RecordingIndicatorState.states[camera.id] ?: RecordingIndicator.LOST)
                    // Событие камеры (движение и др.), пока оно идёт
                    CameraEventState.active[camera.id]?.let { title ->
                        Spacer(modifier = Modifier.width(6.dp))
                        CameraEventBadge(title)
                    }
                }
            }
            // Техническая информация о камере и потоке — слева
            val info: @Composable () -> Unit = {
                if (session.showInfo) StreamInfoOverlay(session = session)
            }
            if (badgesAtBottom) {
                Column(modifier = Modifier.align(Alignment.BottomStart)) {
                    if (session.showInfo) {
                        info()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    badges()
                }
            } else {
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    badges()
                    if (session.showInfo) {
                        Spacer(modifier = Modifier.height(8.dp))
                        info()
                    }
                }
            }

            // Часы в правом верхнем углу делят место с кнопками: в полноэкранном режиме встают
            // слева от кнопки «три точки», иначе — под кнопками. Остальные положения — сами по себе
            val clockWithActions = !menuAtBottom && camera.showDateTime && actionsShown &&
                camera.clockPosition == ClockPosition.TOP_END

            val actions: @Composable () -> Unit = {
                PlayerActions(
                    cameraId = camera.id,
                    style = if (useMenuControls) PlayerActionsStyle.Menu else PlayerActionsStyle.Overlay,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    onEditCamera = onEditCamera?.let { { it(camera) } },
                    onReconnectCamera = onReconnectCamera?.let { { it(camera.id) } },
                    onSelectStreamProfile = onSelectStreamProfile
                )
            }

            if (menuAtBottom) {
                Box(modifier = Modifier.align(Alignment.BottomEnd)) { actions() }
            } else {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    if (actionsShown) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (clockWithActions && isFullscreen) {
                                LiveClock()
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            actions()
                        }
                    }
                    if (clockWithActions && !isFullscreen) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LiveClock()
                    }
                }
            }

            if (camera.showDateTime && !clockWithActions) {
                val position = camera.clockPosition
                // Верхний ряд с кнопкой «три точки» и нижний ряд полноэкранного режима (кнопка пресетов):
                // часы по центру высоты кнопок (48dp) — на одной линии с ними;
                // внизу справа — левее кнопки, которую экран ставит туда сам
                val sharesRowWithButton = (position.row == 0 && actionsShown && !menuAtBottom) ||
                    (position.row == 2 && isFullscreen)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(position.alignment)
                        .then(if (sharesRowWithButton) Modifier.height(ActionButtonSize) else Modifier)
                        .then(if (position == ClockPosition.BOTTOM_END) Modifier.padding(end = reservedBottomEnd) else Modifier)
                ) { LiveClock() }
            }
        }
        }
        // Полоска задержки трансляции после PTZ-действия
        if (isLatencyBarVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(latencyProgress.value)
                        .height(3.dp)
                        .background(LatencyBarColor)
                )
            }
        }
    }
}

private val ClockPosition.alignment: Alignment
    get() = when (this) {
        ClockPosition.TOP_START -> Alignment.TopStart
        ClockPosition.TOP_CENTER -> Alignment.TopCenter
        ClockPosition.TOP_END -> Alignment.TopEnd
        ClockPosition.CENTER_START -> Alignment.CenterStart
        ClockPosition.CENTER -> Alignment.Center
        ClockPosition.CENTER_END -> Alignment.CenterEnd
        ClockPosition.BOTTOM_START -> Alignment.BottomStart
        ClockPosition.BOTTOM_CENTER -> Alignment.BottomCenter
        ClockPosition.BOTTOM_END -> Alignment.BottomEnd
    }

/** Метка качества по высоте кадра: SD / HD / FHD / 2K / 4K */
private fun resolutionLabel(videoHeight: Int): String? = when {
    videoHeight <= 0 -> null
    videoHeight >= 2160 -> "4K"
    videoHeight >= 1440 -> "2K"
    videoHeight >= 1080 -> "FHD"
    videoHeight >= 720 -> "HD"
    else -> "SD"
}

/**
 * Слева над видео: если у камеры настроено несколько потоков — название текущего
 * и выпадающий список для переключения; иначе просто метка разрешения (SD/HD/FHD...).
 */
@Composable
private fun StreamQualityBadge(
    camera: IpCamera,
    videoHeight: Int,
    onSelectStreamProfile: ((String) -> Unit)?
) {
    val profiles = camera.streamProfiles.ifEmpty {
        listOf(com.example.denmediaipcams.model.StreamProfile(name = "Основной (RTSP)", rtspPath = camera.rtspPath))
    }

    if (onSelectStreamProfile == null) {
        val label = resolutionLabel(videoHeight) ?: return
        Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(6.dp)) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val activeId = camera.activeStreamProfile?.id ?: profiles.first().id
    Box {
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.clickable { expanded = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = camera.activeStreamProfile?.name ?: profiles.first().name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Выбрать поток",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    leadingIcon = if (profile.id == activeId) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelectStreamProfile(profile.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraEventBadge(title: String) {
    Surface(
        color = Color(0xFFC62828).copy(alpha = 0.9f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "● $title",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Задержка ответа камер, мс (id камеры → значение), для цвета кружка у названия камеры на плеере */
val LocalCameraLatency = androidx.compose.runtime.compositionLocalOf<Map<String, Long>> { emptyMap() }

@Composable
private fun CameraNameBadge(session: StreamSession) {
    val latencies = LocalCameraLatency.current
    val streamLagMs = rememberStreamLagMs(session)
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            // До первой проверки связи данных нет — показываем «хороший», как раньше
            val level = if (latencies.isEmpty()) SignalLevel.GOOD else SignalLevel.of(latencies[session.camera.id], streamLagMs)
            val (levelIcon, levelTint) = level.iconAndTint(Color.Gray)
            val (icon, tint) = when {
                session.hasError -> SignalLevel.NONE.iconAndTint(MaterialTheme.colorScheme.error)
                session.isBuffering -> SignalLevel.FAIR.iconAndTint(Color.Gray).first to Color(0xFFF59E0B)
                else -> levelIcon to levelTint
            }
            Icon(
                imageVector = icon,
                contentDescription = level.description,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = session.camera.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** Текущие дата и время поверх видео (обновляются каждую секунду) */

private fun suggestSecondaryRtspPath(currentPath: String): String? {
    return when {
        currentPath.contains("101") -> currentPath.replace("101", "102")
        currentPath.contains("subtype=0") -> currentPath.replace("subtype=0", "subtype=1")
        currentPath.contains("ch1/main") -> currentPath.replace("ch1/main", "ch1/sub")
        currentPath.contains("/11") -> currentPath.replace("/11", "/12")
        currentPath.contains("stream1") -> currentPath.replace("stream1", "stream2")
        currentPath.contains("main") -> currentPath.replace("main", "sub")
        currentPath.contains("ch0") -> currentPath.replace("ch0", "ch1")
        currentPath.contains("/0") -> currentPath.replace("/0", "/1")
        else -> null
    }
}
@Composable
private fun LiveClock(modifier: Modifier = Modifier) {
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", Locale.getDefault()) }
    val text by produceState(initialValue = formatter.format(Date())) {
        while (true) {
            value = formatter.format(Date())
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
