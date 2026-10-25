package com.example.denmediaipcams

import com.example.denmediaipcams.ui.components.verticalScrollWithBar

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import com.example.denmediaipcams.ui.components.isPhoneScreen
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.player.LocalStreamSessionManager
import com.example.denmediaipcams.ui.MainViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import com.example.denmediaipcams.ui.components.ArchiveHost
import com.example.denmediaipcams.ui.components.PresetStrip
import com.example.denmediaipcams.ui.components.TooltipIconButton
import com.example.denmediaipcams.ui.components.CameraEditDialog
import com.example.denmediaipcams.ui.components.CameraShareDialog
import com.example.denmediaipcams.ui.components.QrScanDialog
import com.example.denmediaipcams.ui.components.CameraStreamPlayer
import com.example.denmediaipcams.ui.components.NETWORK_SCAN_PROMPT
import com.example.denmediaipcams.ui.components.NetworkScanDialog
import com.example.denmediaipcams.ui.NetworkScanState
import com.example.denmediaipcams.ui.layouts.PhoneLayout
import com.example.denmediaipcams.ui.layouts.TabletLayout
import com.example.denmediaipcams.ui.layouts.CameraScreenActions
import com.example.denmediaipcams.ui.layouts.CameraScreenState
import com.example.denmediaipcams.ui.components.PresetManageDialog
import com.example.denmediaipcams.ui.theme.DenMediaIPCamsTheme
import com.example.denmediaipcams.widget.WidgetLiveService
import com.example.denmediaipcams.widget.WidgetPrefs
import com.example.denmediaipcams.widget.WidgetRenderer

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Интент, с которым открылась/была возвращена на передний план Activity (клик по виджету) —
    // Compose реагирует на него через LaunchedEffect и просит открыть нужную камеру на весь экран
    private val pendingIntentState = mutableStateOf<Intent?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Светлые значки статус-бара на тёмном фоне приложения независимо от системной темы
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Без этого разрешения служба обновления виджета по-прежнему работает,
        // но её уведомление (обязательное для службы переднего плана) не показывается
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        pendingIntentState.value = intent

        applyImmersiveMode()

        setContent {
            DenMediaIPCamsTheme {
                val cameraLatency by viewModel.cameraLatency.collectAsState()
                CompositionLocalProvider(
                    LocalStreamSessionManager provides viewModel.streamSessions,
                    com.example.denmediaipcams.ui.components.LocalCameraLatency provides cameraLatency
                ) {
                    ArchiveHost {
                        MainScreen(viewModel = viewModel, pendingIntent = pendingIntentState)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Системные панели возвращаются после диалогов, клавиатуры и свайпа от края — прячем снова
        if (hasFocus) applyImmersiveMode()
    }

    /** Смартфон: приложение на весь экран, без строки состояния и навигационной панели (появляются по свайпу от края) */
    private fun applyImmersiveMode() {
        if (!resources.isPhoneScreen()) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentState.value = intent
    }

    override fun onPause() {
        // Приложение сворачивают или закрывают: сохраняем последний кадр идущих трансляций в превью камер.
        // В onPause видео ещё на экране — позже поверхность уже уничтожена и кадр не снять
        viewModel.streamSessions.saveThumbnailsOfPlaying()

        // Если свёрнутая камера открыта на весь экран и у неё есть виджет трансляции —
        // включаем обновление кадров виджета, чтобы оно продолжилось и без приложения на экране
        val cameraId = WidgetLiveService.fullscreenCameraId
        if (cameraId != null && WidgetRenderer.liveWidgetIds(this).any { WidgetPrefs.liveCameraId(this, it) == cameraId }) {
            WidgetLiveService.ensureStarted(this, cameraId)
        }
        super.onPause()
    }

    override fun onStop() {
        // Приложение действительно ушло из поля зрения (не просто мигнул диалог/разрешение) —
        // останавливаем трансляции камер, открытые в самом приложении. Виджет трансляции не затрагивается:
        // у него отдельная служба со своим декодером (WidgetLiveService), независимая от этих сессий
        viewModel.streamSessions.pauseAllForBackground()
        viewModel.isAppInForeground = false
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        viewModel.isAppInForeground = true
        viewModel.streamSessions.resumeAllFromBackground()
    }

    companion object {
        const val EXTRA_CAMERA_ID = "com.example.denmediaipcams.extra.CAMERA_ID"
        const val EXTRA_FULLSCREEN = "com.example.denmediaipcams.extra.FULLSCREEN"
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, pendingIntent: MutableState<Intent?>) {
    val cameras by viewModel.cameras.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()
    val isMultiGrid by viewModel.isMultiGrid.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val lastPtzActionAtMs by viewModel.lastPtzActionAtMs.collectAsState()
    val cameraOnline by viewModel.cameraOnline.collectAsState()
    val cameraLatency by viewModel.cameraLatency.collectAsState()
    val digitalZoom by viewModel.digitalZoom.collectAsState()
    val dayNightMode by viewModel.dayNightMode.collectAsState()
    val dayNightSupported by viewModel.dayNightSupported.collectAsState()
    val lensCaps by viewModel.lensCaps.collectAsState()
    val ptzCaps by viewModel.ptzCaps.collectAsState()
    val isPresetTourRunning by viewModel.isPresetTourRunning.collectAsState()
    val presetTourCurrentId by viewModel.presetTourCurrentId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val appContext = androidx.compose.ui.platform.LocalContext.current
    var lastCrash by remember { mutableStateOf(CrashReporter.takeLastCrash(appContext)) }

    var cameraToEdit by remember { mutableStateOf<IpCamera?>(null) }
    var isAddOrEditDialogShown by remember { mutableStateOf(false) }

    var cameraToDeleteId by remember { mutableStateOf<String?>(null) }


    // Поиск камер в сети: вопрос-подтверждение, ход поиска и заготовка для формы добавления
    val scanState by viewModel.scanState.collectAsState()
    var isScanPromptShown by remember { mutableStateOf(false) }
    var cameraDraft by remember { mutableStateOf<IpCamera?>(null) }
    // Заготовка из QR кода уже настроена на другом устройстве: в форме ничего не определяется заново
    var cameraDraftConfigured by remember { mutableStateOf(false) }

    // «Поделиться» камерой (окно с QR кодом) и добавление камеры по QR коду
    var cameraToShare by remember { mutableStateOf<IpCamera?>(null) }
    var isQrScanShown by remember { mutableStateOf(false) }

    // Окно «Управление» сохранёнными положениями
    var isPresetManagerShown by remember { mutableStateOf(false) }

    // Полноэкранный режим показывает выбранную камеру; переживает поворот экрана
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    val onFullscreenClick: (IpCamera) -> Unit = { camera ->
        viewModel.selectCamera(camera.id)
        isFullscreen = true
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusMessage()
        }
    }

    // При выборе камеры подтягиваем из неё список сохранённых положений
    LaunchedEffect(selectedCamera?.id, selectedCamera?.protocol) {
        viewModel.syncSavedPresets()
    }

    // Клик по виджету (новый или повторно присланный интент): открыть указанную камеру на весь экран
    LaunchedEffect(pendingIntent.value) {
        val intent = pendingIntent.value ?: return@LaunchedEffect
        val cameraId = intent.getStringExtra(MainActivity.EXTRA_CAMERA_ID)
        val wantsFullscreen = intent.getBooleanExtra(MainActivity.EXTRA_FULLSCREEN, false)
        if (cameraId != null) viewModel.selectCamera(cameraId)
        if (wantsFullscreen) isFullscreen = true
        pendingIntent.value = null
    }

    // Запоминаем камеру, открытую на весь экран, — по ней WidgetLiveService решает,
    // включать ли обновление виджета при сворачивании приложения
    LaunchedEffect(isFullscreen, selectedCamera?.id) {
        WidgetLiveService.fullscreenCameraId = if (isFullscreen) selectedCamera?.id else null
    }

    // Локальная копия: делегированное свойство нельзя smart-cast'ить после проверки на null
    val fullscreenCamera = selectedCamera
    if (isFullscreen && fullscreenCamera != null) {
        FullscreenPlayer(
            camera = fullscreenCamera,
            hasPtz = fullscreenCamera.protocol.supportsPtz && ptzCaps[fullscreenCamera.id]?.none != true,
            snackbarHostState = snackbarHostState,
            lastPtzActionAtMs = lastPtzActionAtMs,
            digitalZoom = digitalZoom[fullscreenCamera.id] ?: 1f,
            onExit = { isFullscreen = false },
            onMove = { pan, tilt -> viewModel.sendMove(pan, tilt) },
            onZoom = { level -> viewModel.sendZoom(level) },
            onStop = { viewModel.sendStop() },
            onGoToPreset = { presetId -> viewModel.goToPreset(presetId) },
            onSavePreset = { presetId -> viewModel.savePreset(presetId) },
            onManagePresets = { isPresetManagerShown = true },
            onEditCamera = { camera ->
                cameraToEdit = camera
                isAddOrEditDialogShown = true
            },
            onReconnectCamera = { cameraId -> viewModel.reconnectCamera(cameraId) },
            onSelectStreamProfile = { profileId -> viewModel.selectStreamProfile(fullscreenCamera.id, profileId) }
        )
    } else {
    // Смартфон: приложение на весь экран, системные панели скрыты — сверху отступа нет совсем
    // (вырез камеры по центру не мешает: слева от него только заголовок). Боковые вырезы и нижние панели учитываем
    val contentInsets = if (isPhoneScreen()) {
        // Вырез фронтальной камеры не отодвигает содержимое: приложение рисуется на весь экран
        // (окно с layoutInDisplayCutoutMode=always от enableEdgeToEdge). Учитываем только системные панели и клавиатуру
        WindowInsets.systemBars
            .union(WindowInsets.ime)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    } else {
        ScaffoldDefaults.contentWindowInsets
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = contentInsets,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Раскладка планшета (три колонки: список, видео, управление) — в горизонтальной ориентации на любом
            // достаточно широком экране, в том числе на смартфоне (там она компактная: высота ~384dp).
            // В вертикальной ориентации колонкам тесно — там раскладка смартфона
            val isTablet = maxWidth > maxHeight && maxWidth >= 640.dp && maxHeight >= 340.dp

            val screenState = CameraScreenState(
                cameras = cameras,
                selectedCamera = selectedCamera,
                isMultiGrid = isMultiGrid,
                lastPtzActionAtMs = lastPtzActionAtMs,
                cameraOnline = cameraOnline,
                digitalZoom = digitalZoom,
                dayNightMode = dayNightMode,
                dayNightSupported = dayNightSupported,
                lensCaps = lensCaps,
                ptzCaps = ptzCaps,
                cameraLatency = cameraLatency
            )
            val screenActions = remember(viewModel) {
                CameraScreenActions(
                    onSelectCamera = { viewModel.selectCamera(it) },
                    onShowSingleCamera = { viewModel.showSingleCamera(it) },
                    onAddCamera = {
                        cameraToEdit = null
                        cameraDraftConfigured = false
                        cameraDraft = null
                        isAddOrEditDialogShown = true
                    },
                    onScanNetwork = { isScanPromptShown = true },
                    onAddFromQr = { isQrScanShown = true },
                    onEditCamera = { camera ->
                        cameraToEdit = camera
                        isAddOrEditDialogShown = true
                    },
                    onShareCamera = { camera -> cameraToShare = camera },
                    onDeleteCamera = { id -> cameraToDeleteId = id },
                    onMoveCamera = { from, to -> viewModel.moveCamera(from, to) },
                    onReconnectCamera = { id -> viewModel.reconnectCamera(id) },
                    onToggleMultiGrid = { viewModel.toggleMultiGrid() },
                    onFullscreen = onFullscreenClick,
                    onMove = { pan, tilt -> viewModel.sendMove(pan, tilt) },
                    onZoom = { level -> viewModel.sendZoom(level) },
                    onStop = { viewModel.sendStop() },
                    onFocus = { direction -> viewModel.sendFocus(direction) },
                    onFocusMove = { speed -> viewModel.sendFocusMove(speed) },
                    onFocusStop = { viewModel.sendFocusStop() },
                    onSetDayNightMode = { mode -> viewModel.sendDayNightMode(mode) },
                    onGoToPreset = { presetId -> viewModel.goToPreset(presetId) },
                    onSavePreset = { presetId -> viewModel.savePreset(presetId) },
                    onAddPreset = { viewModel.addPreset() },
                    onManagePresets = { isPresetManagerShown = true },
                    onSelectStreamProfile = { cameraId, profileId -> viewModel.selectStreamProfile(cameraId, profileId) },
                    onSelectProtocol = { cameraId, protocol -> viewModel.selectProtocol(cameraId, protocol) }
                )
            }

            if (isTablet) {
                // Планшет в горизонтальной ориентации (Samsung Galaxy Tab S9 Ultra)
                TabletLayout(state = screenState, actions = screenActions)
            } else {
                // Смартфон (Samsung Galaxy S23 Plus) и вертикальный планшет
                PhoneLayout(state = screenState, actions = screenActions)
            }
        }
    }
    }

    // Диалоговое окно добавления / редактирования камеры
    if (isAddOrEditDialogShown) {
        CameraEditDialog(
            cameraToEdit = cameraToEdit,
            draft = cameraDraft,
            draftConfigured = cameraDraftConfigured,
            onDismiss = { isAddOrEditDialogShown = false },
            onSave = { camera ->
                if (cameraToEdit == null) {
                    viewModel.addCamera(camera)
                } else {
                    viewModel.updateCamera(camera)
                }
                isAddOrEditDialogShown = false
            },
            onSaveMultiple = { newCameras ->
                newCameras.forEach { viewModel.addCamera(it) }
                isAddOrEditDialogShown = false
            },
            onDelete = { cameraId ->
                viewModel.deleteCamera(cameraId)
                isAddOrEditDialogShown = false
            }
        )
    }

    // QR код с настройками камеры для другого устройства
    cameraToShare?.let { camera ->
        CameraShareDialog(camera = camera, onDismiss = { cameraToShare = null })
    }

    // Сканирование QR кода: камера открывается в форме добавления, где её можно проверить и сохранить
    if (isQrScanShown) {
        QrScanDialog(
            onScanned = { camera ->
                isQrScanShown = false
                cameraToEdit = null
                cameraDraft = camera
                cameraDraftConfigured = true
                isAddOrEditDialogShown = true
            },
            onDismiss = { isQrScanShown = false }
        )
    }

    // Управление сохранёнными положениями выбранной камеры
    val presetCamera = selectedCamera
    if (isPresetManagerShown && presetCamera != null) {
        PresetManageDialog(
            cameraId = presetCamera.id,
            presets = presetCamera.presets,
            onRename = { presetId, name -> viewModel.renamePreset(presetId, name) },
            onSave = { presetId -> viewModel.savePreset(presetId) },
            onGoTo = { presetId -> viewModel.goToPreset(presetId) },
            onClear = { presetId -> viewModel.clearPreset(presetId) },
            isCapturingThumbnails = isPresetTourRunning,
            capturingPresetId = presetTourCurrentId,
            onCaptureThumbnails = { viewModel.capturePresetThumbnails() },
            onCancelCapture = { viewModel.cancelPresetTour() },
            onDismiss = { isPresetManagerShown = false }
        )
    }

    // Причина прошлого вылета приложения (сохранена CrashReporter)
    lastCrash?.let { report ->
        AlertDialog(
            onDismissRequest = { lastCrash = null },
            title = { Text("Приложение закрылось с ошибкой") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScrollWithBar()
                )
            },
            confirmButton = { TextButton(onClick = { lastCrash = null }) { Text("Закрыть") } }
        )
    }

    // Вопрос перед поиском камер в сети
    if (isScanPromptShown) {
        AlertDialog(
            onDismissRequest = { isScanPromptShown = false },
            title = { Text("Поиск камер") },
            text = {
                Text(
                    "$NETWORK_SCAN_PROMPT\n\nБудут опрошены устройства ONVIF и проверены RTSP-порты " +
                        "всех адресов вашей подсети. Это займёт 5–10 секунд."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    isScanPromptShown = false
                    viewModel.startNetworkScan()
                }) { Text("Найти") }
            },
            dismissButton = {
                TextButton(onClick = { isScanPromptShown = false }) { Text("Отмена") }
            }
        )
    }

    // Ход и результат поиска
    if (scanState != NetworkScanState.Idle) {
        NetworkScanDialog(
            state = scanState,
            existingHosts = cameras.map { it.ipAddress }.toSet(),
            onCameraPick = { found ->
                viewModel.dismissNetworkScan()
                cameraToEdit = null
                cameraDraftConfigured = false
                cameraDraft = IpCamera(
                    id = "",
                    name = found.name ?: "Камера ${found.host}",
                    ipAddress = found.host,
                    // Не найденные порты остаются пустыми (0), а не подставляются по умолчанию
                    rtspPort = found.rtspPort ?: 0,
                    httpPort = 0,
                    onvifPort = found.onvifPort ?: 0,
                    dahuaPort = 0
                )
                isAddOrEditDialogShown = true
            },
            onAddSelected = { found ->
                if (found.size == 1) {
                    // Одна камера — сразу открываем её настройки, как при нажатии на камеру в списке
                    val camera = found.first()
                    viewModel.dismissNetworkScan()
                    cameraToEdit = null
                    cameraDraftConfigured = false
                    cameraDraft = IpCamera(
                        id = "",
                        name = camera.name ?: "Камера ${camera.host}",
                        ipAddress = camera.host,
                        rtspPort = camera.rtspPort ?: 0,
                        httpPort = 0,
                        onvifPort = camera.onvifPort ?: 0,
                        dahuaPort = 0
                    )
                    isAddOrEditDialogShown = true
                } else {
                    // Несколько камер: логин и пароль по умолчанию, порты определяются и проверяются у каждой
                    viewModel.addDiscoveredCameras(found)
                    viewModel.dismissNetworkScan()
                }
            },
            onRescan = { viewModel.startNetworkScan() },
            onDismiss = { viewModel.dismissNetworkScan() }
        )
    }

    // Диалоговое окно подтверждения удаления камеры
    if (cameraToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { cameraToDeleteId = null },
            title = { Text("Удаление камеры") },
            text = { Text("Вы действительно хотите удалить эту камеру из списка?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        cameraToDeleteId?.let { id -> viewModel.deleteCamera(id) }
                        cameraToDeleteId = null
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { cameraToDeleteId = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

/**
 * Видео выбранной камеры на весь экран: системные панели скрыты (появляются по свайпу от края),
 * PTZ-жесты работают, внизу справа — кнопки сохранённых положений (если у камеры есть PTZ),
 * выход — кнопкой на плеере или системным «Назад».
 */
@Composable
private fun FullscreenPlayer(
    camera: IpCamera,
    /** Выбран протокол PTZ, и камера не сообщила, что поворота и зума у неё нет */
    hasPtz: Boolean,
    snackbarHostState: SnackbarHostState,
    lastPtzActionAtMs: Long,
    digitalZoom: Float,
    onExit: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onStop: () -> Unit,
    onGoToPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit,
    onManagePresets: () -> Unit,
    onEditCamera: (IpCamera) -> Unit,
    onReconnectCamera: (String) -> Unit,
    onSelectStreamProfile: (String) -> Unit
) {
    val view = LocalView.current
    val isPhone = LocalConfiguration.current.let { minOf(it.screenWidthDp, it.screenHeightDp) < 600 }

    // Одно нажатие по видео прячет и кнопку сохранённых положений вместе с остальным интерфейсом плеера
    var overlayVisible by remember { mutableStateOf(true) }

    BackHandler(onBack = onExit)

    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // На смартфоне приложение всегда на весь экран — панели не возвращаем
            if (!isPhone) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraStreamPlayer(
            camera = camera,
            modifier = Modifier.fillMaxSize(),
            isFullscreen = true,
            onToggleFullscreen = onExit,
            onPtzMove = onMove,
            onPtzZoom = onZoom,
            onPtzStop = onStop,
            lastPtzActionAtMs = lastPtzActionAtMs,
            digitalZoom = digitalZoom,
            onSelectStreamProfile = onSelectStreamProfile,
            onEditCamera = onEditCamera,
            onReconnectCamera = onReconnectCamera,
            onOverlayVisibilityChange = { overlayVisible = it },
            // Справа внизу стоит кнопка пресетов (48dp + отступ) — часы в этом углу встают левее неё
            reservedBottomEnd = if (hasPtz) 58.dp else 0.dp
        )

        if (hasPtz && overlayVisible) {
            var isPresetsOpen by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // Тот же отступ от края (10dp), что у остальных элементов поверх плеера
                    .padding(10.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isPresetsOpen) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .widthIn(max = 480.dp)
                                .padding(bottom = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp, start = 4.dp)
                                ) {
                                    Text(
                                        text = "Сохранённые положения",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            isPresetsOpen = false
                                            onManagePresets()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Settings,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Настройка", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                PresetStrip(
                                    cameraId = camera.id,
                                    presets = camera.presets,
                                    onGoToPreset = { presetId -> onGoToPreset(presetId) },
                                    onSavePreset = { presetId -> onSavePreset(presetId) },
                                    onAddPreset = {
                                        val freeSlot = camera.presets.firstOrNull { !it.isSaved }?.id ?: 1
                                        onSavePreset(freeSlot)
                                    },
                                    tileWidth = 88.dp
                                )
                            }
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        TooltipIconButton(tooltip = "Сохранённые положения", onClick = { isPresetsOpen = !isPresetsOpen }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Сохранённые положения",
                                tint = if (isPresetsOpen) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** Смартфон — меньшая сторона экрана меньше 600dp */
private fun android.content.res.Resources.isPhoneScreen(): Boolean =
    configuration.let { minOf(it.screenWidthDp, it.screenHeightDp) < 600 }