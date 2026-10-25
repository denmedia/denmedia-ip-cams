package com.example.denmediaipcams.ui.components

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.denmediaipcams.model.ClockPosition
import com.example.denmediaipcams.model.FocusAvailability
import com.example.denmediaipcams.network.CameraRecording
import com.example.denmediaipcams.model.VideoRotation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.KnownPort
import com.example.denmediaipcams.model.PortKind
import com.example.denmediaipcams.model.StreamProfile
import com.example.denmediaipcams.network.AuthCheckStatus
import com.example.denmediaipcams.network.CameraTimeSync
import com.example.denmediaipcams.network.DahuaApi
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.NetworkTester
import com.example.denmediaipcams.network.NvrChannel
import com.example.denmediaipcams.network.NvrChannelFinder
import com.example.denmediaipcams.network.NvrSearchResult
import com.example.denmediaipcams.network.PortDetector
import com.example.denmediaipcams.network.PortPingStatus
import com.example.denmediaipcams.network.PtzDetectionResult
import com.example.denmediaipcams.network.PtzProtocolDetector
import com.example.denmediaipcams.network.RtspCheckStatus
import com.example.denmediaipcams.network.RtspPathFinder
import com.example.denmediaipcams.network.RtspPathSearchResult
import com.example.denmediaipcams.network.StreamSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SuccessGreen = Color(0xFF2E7D32)

private val IPV4_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
private val HOSTNAME_REGEX = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$""")

/** Адрес уже можно проверять по сети: полный IPv4 или имя хоста с доменом */
private fun looksLikeHost(host: String): Boolean = IPV4_REGEX.matches(host) || HOSTNAME_REGEX.matches(host)

private enum class EditTab(val title: String) {
    Main("Основные"),
    Ports("Порты"),
    Rtsp("RTSP"),

    // Скрыта у камер без поворотного механизма (протокол «Без PTZ»)
    Ptz("PTZ"),

    // Показывается всегда; настройки архива активны только после определения его формата
    SdCard("Архив видео"),
    Advanced("Дополнительно")
}

/**
 * Настройки камеры, вкладки:
 * «Основные» — название, адрес, логин и пароль; «Порты» — порты с проверкой;
 * «RTSP» — потоки камеры (поиск и ручное добавление);
 * «PTZ» — протокол поворотного управления и направления осей (у камеры без поворота вкладки нет);
 * «Дополнительно» — превью снимком камеры и вывод даты и времени поверх видео.
 */
@Composable
fun CameraEditDialog(
    cameraToEdit: IpCamera? = null,
    // Заготовка новой камеры (например, найденной поиском в сети): поля заполнены, но это добавление
    draft: IpCamera? = null,
    // Заготовка уже настроена (камера из QR кода другого устройства): порты, потоки и PTZ сами не определяются
    draftConfigured: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (IpCamera) -> Unit,
    // Добавление нескольких камер сразу (каналы видеорегистратора); null — поиск каналов недоступен
    onSaveMultiple: ((List<IpCamera>) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    // Открыть сразу вкладку «Настройки SD» (например, из окна архива)
    openSdTab: Boolean = false,
    // Карта отформатирована из вкладки «SD карта»: записи на ней изменились
    onSdFormatted: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val isEditing = cameraToEdit != null
    val initial = cameraToEdit ?: draft

    var selectedTab by remember { mutableIntStateOf((if (openSdTab) EditTab.SdCard else EditTab.Main).ordinal) }
    // Настройки записи самой камеры: правятся на вкладке «SD карта», в камеру записываются кнопкой «Сохранить»
    val recordingState = remember { RecordingSettingsState() }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var ipAddress by remember { mutableStateOf(initial?.ipAddress ?: "") }
    var rtspPortStr by remember { mutableStateOf(initial?.rtspPort?.takeIf { it > 0 }?.toString() ?: "") }
    var httpPortStr by remember { mutableStateOf(initial?.httpPort?.takeIf { it > 0 }?.toString() ?: "") }
    var onvifPortStr by remember { mutableStateOf(initial?.onvifPort?.takeIf { it > 0 }?.toString() ?: "") }
    var dahuaPortStr by remember { mutableStateOf(initial?.dahuaPort?.takeIf { it > 0 }?.toString() ?: "") }
    var dvripPortStr by remember { mutableStateOf(initial?.dvripPort?.takeIf { it > 0 }?.toString() ?: "") }
    var hipcamPortStr by remember { mutableStateOf(initial?.hipcamPort?.takeIf { it > 0 }?.toString() ?: "") }
    // Стандарт камеры (Dahua, XMEye/DVRIP, Hipcam): у каждого свой порт, показывается только порт выбранного.
    // Выставляется сам по найденным портам и формату архива, пока пользователь не выбрал его вручную
    val initialStandard = remember { initialCameraStandard(initial) }
    var standard by remember { mutableStateOf(initialStandard) }
    var standardManual by remember { mutableStateOf(false) }
    // Новой камере (ручное добавление или заготовка из сетевого поиска) подставляется типовой логин
    // "admin" — это просто отправная точка для редактирования, а не правильные учётные данные камеры.
    // Пароль по умолчанию всегда пуст: угадать его как "admin" намного вероятнее ошибиться, чем с логином
    var username by remember { mutableStateOf(initial?.username?.takeIf { it.isNotBlank() } ?: if (cameraToEdit == null) "admin" else "") }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var selectedProtocol by remember { mutableStateOf(initial?.protocol ?: CameraProtocol.ONVIF) }
    var ptzRepeatMode by remember { mutableStateOf(initial?.ptzRepeatMode ?: false) }
    var ptzInvertPan by remember { mutableStateOf(initial?.ptzInvertPan ?: false) }
    var ptzInvertTilt by remember { mutableStateOf(initial?.ptzInvertTilt ?: false) }
    var focusAvailability by remember { mutableStateOf(initial?.focusAvailability ?: FocusAvailability.AUTO) }
    var autoLowerQuality by remember { mutableStateOf(initial?.autoLowerQuality ?: true) }
    var snapshotThumbnail by remember { mutableStateOf(initial?.snapshotThumbnail ?: false) }
    // Признак архива сохраняется только у камеры того стандарта, к которому он относится
    var dahuaApi by remember { mutableStateOf((initial?.dahuaApi ?: false) && initialStandard == CameraStandard.Dahua) }
    var sdHttpArchive by remember { mutableStateOf((initial?.sdHttpArchive ?: false) && initialStandard == CameraStandard.Hipcam) }
    var hikvisionArchive by remember { mutableStateOf(initial?.hikvisionArchive ?: false) }
    var cameraAudioWithApp by remember { mutableStateOf(initial?.cameraAudioWithApp ?: true) }
    var dvripArchive by remember { mutableStateOf((initial?.dvripArchive ?: false) && initialStandard == CameraStandard.XMEye) }
    var motionAlerts by remember { mutableStateOf(initial?.motionAlerts ?: false) }
    var archiveBlockEnabled by remember { mutableStateOf(initial?.archiveBlockEnabled ?: true) }
    var archiveBlockCountStr by remember { mutableStateOf((initial?.archiveBlockCount ?: IpCamera.MAX_ARCHIVE_BLOCK_COUNT).toString()) }
    var archiveBlockHideOnMetered by remember { mutableStateOf(initial?.archiveBlockHideOnMetered ?: false) }
    // Проверка «это камера Dahua»: идёт ли сейчас и её результат
    var dahuaProbing by remember { mutableStateOf(false) }
    var dahuaProbeStatus by remember { mutableStateOf<AuthCheckStatus>(AuthCheckStatus.Idle) }
    var autoSyncTime by remember { mutableStateOf(initial?.autoSyncTime ?: true) }
    // Ручная установка времени камеры: идёт ли сейчас и её результат
    var timeSyncRunning by remember { mutableStateOf(false) }
    var timeSyncStatus by remember { mutableStateOf<AuthCheckStatus>(AuthCheckStatus.Idle) }
    var showDateTime by remember { mutableStateOf(initial?.showDateTime ?: true) }
    var clockPosition by remember { mutableStateOf(initial?.clockPosition ?: ClockPosition.TOP_END) }
    var videoRotation by remember { mutableStateOf(initial?.videoRotation ?: com.example.denmediaipcams.model.VideoRotation.NONE) }

    // Потоки камеры — единственное место настройки RTSP-путей. У уже сохранённой камеры
    // без списка потоков её прежний путь становится потоком «Основной»
    val streamProfiles = remember {
        mutableStateListOf<StreamProfile>().apply {
            val saved = initial?.streamProfiles.orEmpty()
            if (saved.isNotEmpty()) {
                addAll(saved)
            } else if (cameraToEdit != null && cameraToEdit.rtspPath.isNotBlank()) {
                add(StreamProfile(name = "Основной", rtspPath = cameraToEdit.rtspPath))
            }
        }
    }
    // Результат проверки каждого потока (по id потока)
    val streamCheckStatus = remember { mutableStateMapOf<String, RtspCheckStatus>() }
    // Текущий шаг автопоиска потоков (null — поиск не идёт)
    var streamSearchProgress by remember { mutableStateOf<String?>(null) }

    // Поиск каналов видеорегистратора: шаг поиска, найденные каналы и выбранные номера
    var nvrSearchProgress by remember { mutableStateOf<String?>(null) }
    var nvrChannels by remember { mutableStateOf<List<NvrChannel>>(emptyList()) }
    var nvrSelected by remember { mutableStateOf<Set<Int>>(emptySet()) }


    // Статусы пинга портов
    var rtspPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }
    var httpPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }
    var onvifPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }
    var dahuaPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }
    var dvripPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }
    var hipcamPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }

    // Статус проверки авторизации
    var authCheckStatus by remember { mutableStateOf<AuthCheckStatus>(AuthCheckStatus.Idle) }

    // Автоопределение протокола PTZ: текущий шаг (null — не идёт) и результат
    var ptzDetectProgress by remember { mutableStateOf<String?>(null) }
    var ptzDetectStatus by remember { mutableStateOf<AuthCheckStatus>(AuthCheckStatus.Idle) }

    // Диалог с текстом ошибки для клика по иконке "i"
    var errorPopupText by remember { mutableStateOf<String?>(null) }

    // Диалог подтверждения удаления камеры
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Статус определения портов (null — не идёт) и открытые порты этой камеры с подтверждённым протоколом
    var portScanProgress by remember { mutableStateOf<String?>(null) }
    val knownPorts = remember { mutableStateListOf<KnownPort>().apply { addAll(initial?.knownPorts.orEmpty()) } }
    // Адрес, для которого порты уже определялись автоматически (повторно для него не ищем)
    var lastAutoDetectedHost by remember { mutableStateOf<String?>(null) }
    // Текущая проверка портов: при новой проверке (другой адрес, повторный запуск) прежняя отменяется
    var portDetectJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Выбор стандарта камеры. Признаки архива других стандартов сбрасываются: камера, которая отвечает как Dahua,
     * не отдаёт архив по XMEye/DVRIP и наоборот. Выбор вручную ([manual]) автоопределение уже не меняет;
     * пустое поле порта выбранного стандарта заполняется найденным у камеры портом.
     */
    fun selectStandard(new: CameraStandard, manual: Boolean = false) {
        standard = new
        if (manual) standardManual = true
        if (new != CameraStandard.Dahua) dahuaApi = false
        if (new != CameraStandard.Hipcam) sdHttpArchive = false
        if (new != CameraStandard.XMEye) dvripArchive = false
        if (manual) {
            val detected = PortDetector.select(knownPorts.toList())
            if (new == CameraStandard.Dahua && dahuaPortStr.isBlank()) detected.dahua?.let { dahuaPortStr = it.toString() }
            if (new == CameraStandard.XMEye && dvripPortStr.isBlank()) detected.dvrip?.let { dvripPortStr = it.toString() }
        }
    }

    /** Порт для сохранения и проверок: у камеры остаётся только порт её стандарта (0 — не задан) */
    fun standardPort(kind: CameraStandard, text: String): Int = if (standard == kind) text.toIntOrNull() ?: 0 else 0

    // Статус пинга IP-адреса
    var ipPingStatus by remember { mutableStateOf<PortPingStatus>(PortPingStatus.Idle) }

    fun pingIpAddress() {
        if (ipAddress.isBlank()) {
            Toast.makeText(context, "Сначала укажите IP-адрес", Toast.LENGTH_SHORT).show()
            return
        }
        ipPingStatus = PortPingStatus.Loading
        coroutineScope.launch {
            val result = NetworkTester.pingIp(ipAddress)
            ipPingStatus = result
            when (result) {
                is PortPingStatus.Success -> Toast.makeText(context, "IP-адрес $ipAddress доступен!", Toast.LENGTH_SHORT).show()
                is PortPingStatus.Error -> Toast.makeText(context, "IP-адрес недоступен: ${result.message}", Toast.LENGTH_LONG).show()
                else -> {}
            }
        }
    }

    // Автонастройка новой камеры (найденной поиском или введённой вручную): порты определяются сами
    // после ввода адреса, потоки и протокол PTZ — после ввода логина и пароля
    // Только для камеры, найденной поиском в сети (есть заготовка): при ручном добавлении кнопкой «Добавить камеру»
    // ничего не определяется и не отправляется на камеру само — только по нажатию кнопок в форме
    val isAutoSetup = cameraToEdit == null && draft != null && !draftConfigured
    // Логин/пароль, с которыми уже искали потоки: не повторяем попытку с теми же данными,
    // чтобы не спровоцировать блокировку учётной записи на камере
    var lastAutoDetectCredentials by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun pingSinglePort(
        portName: String,
        portStr: String,
        defaultPort: Int,
        expected: PortKind,
        showToast: Boolean = true,
        updateStatus: (PortPingStatus) -> Unit
    ) {
        val port = portStr.toIntOrNull() ?: defaultPort
        updateStatus(PortPingStatus.Loading)

        coroutineScope.launch {
            // Недостаточно, что порт открыт: он должен отвечать нужным протоколом
            val result = PortDetector.verify(ipAddress, port, expected)
            updateStatus(result)

            if (showToast) when (result) {
                is PortPingStatus.Success -> {
                    Toast.makeText(context, "$portName ($port): Порт доступен!", Toast.LENGTH_SHORT).show()
                }
                is PortPingStatus.Error -> {
                    Toast.makeText(context, "$portName ($port): ${result.message}", Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }
    }

    fun checkCameraAuth() {
        val httpPort = httpPortStr.toIntOrNull() ?: IpCamera.DEFAULT_HTTP_PORT
        authCheckStatus = AuthCheckStatus.Loading

        coroutineScope.launch {
            val result = NetworkTester.checkAuth(ipAddress, httpPort, username, password)
            authCheckStatus = result

            when (result) {
                is AuthCheckStatus.Success -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                is AuthCheckStatus.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                else -> {}
            }
        }
    }

    /**
     * Определяет, каким способом камера отдаёт архив записей: HTTP API Dahua или каталог /sd/ (Hipcam).
     * Найденное включает нужную галочку; результат — для строки состояния.
     */
    suspend fun detectArchive(): AuthCheckStatus {
        val host = ipAddress.trim()
        val httpPort = httpPortStr.toIntOrNull() ?: IpCamera.DEFAULT_HTTP_PORT
        val dahua = DahuaApi.probe(host, httpPort, username, password)
        if (dahua.model != null) {
            selectStandard(CameraStandard.Dahua)
            dahuaApi = true
            return AuthCheckStatus.Success("Архив: Dahua (${dahua.model})")
        }
        val probeCamera = IpCamera(
            id = "probe", name = "", ipAddress = host, httpPort = httpPort,
            hipcamPort = standardPort(CameraStandard.Hipcam, hipcamPortStr), username = username, password = password
        )
        if (HikvisionArchive.isSupported(probeCamera) == true) {
            hikvisionArchive = true
            return AuthCheckStatus.Success("Архив: Hikvision (ISAPI)")
        }
        if (HipcamArchive.isSupported(probeCamera) == true) {
            selectStandard(CameraStandard.Hipcam)
            sdHttpArchive = true
            return AuthCheckStatus.Success("Архив: SD-карта по HTTP (каталог /sd/, Hipcam)")
        }
        // XMEye/DVRIP — последний вариант, только если больше нечем (протокол слабо защищён, см. DvripArchive)
        val dvripPort = standardPort(CameraStandard.XMEye, dvripPortStr)
        if (!dahua.authRejected && DvripArchive.isSupported(probeCamera.copy(dvripPort = dvripPort)) == true) {
            selectStandard(CameraStandard.XMEye)
            dvripArchive = true
            return AuthCheckStatus.Success("Архив: XMEye/DVRIP (порт ${probeCamera.copy(dvripPort = dvripPort).effectiveDvripPort})")
        }
        return if (dahua.authRejected) {
            AuthCheckStatus.Error("Камера отклонила логин или пароль. Проверьте учётные данные на вкладке «Основные».")
        } else {
            AuthCheckStatus.Error("Архив записей не найден: камера не ответила ни как Dahua, ни каталогом /sd/ на HTTP-порту $httpPort. Проверьте HTTP порт.")
        }
    }

    /**
     * ONVIF-порт, не подтверждённый проверкой портов (камера отвечает на него только с логином и паролем), ищется среди
     * неопознанных открытых портов — иначе поиск потоков и PTZ не заработают, пока порт не выбран вручную.
     */
    suspend fun resolveUnconfirmedOnvifPort(onProgress: (String) -> Unit) {
        val currentOnvif = onvifPortStr.toIntOrNull()
        if (knownPorts.any { it.port == currentOnvif && PortKind.ONVIF in it.kinds }) return
        val unidentified = knownPorts.filter { it.kinds.isEmpty() }.map { it.port }
        if (unidentified.isEmpty()) return
        onProgress("ONVIF: проверка неопознанных портов...")
        val onvifPort = PortDetector.findAuthenticatedOnvifPort(ipAddress, unidentified, username, password) ?: return
        val index = knownPorts.indexOfFirst { it.port == onvifPort }
        if (index >= 0) knownPorts[index] = knownPorts[index].copy(kinds = knownPorts[index].kinds + PortKind.ONVIF)
        onvifPortStr = onvifPort.toString()
        onvifPingStatus = PortPingStatus.Success
    }

    fun autoDetectPtzProtocol(silent: Boolean = false) {
        ptzDetectProgress = "Поиск..."
        ptzDetectStatus = AuthCheckStatus.Idle

        coroutineScope.launch {
            resolveUnconfirmedOnvifPort { ptzDetectProgress = it }
            val result = PtzProtocolDetector.detect(
                hostInput = ipAddress,
                httpPort = httpPortStr.toIntOrNull() ?: IpCamera.DEFAULT_HTTP_PORT,
                onvifPort = onvifPortStr.toIntOrNull() ?: IpCamera.DEFAULT_ONVIF_PORT,
                user = username,
                pass = password,
                onProgress = { ptzDetectProgress = it }
            )
            ptzDetectProgress = null

            when (result) {
                is PtzDetectionResult.Detected -> {
                    selectedProtocol = result.protocol
                    ptzDetectStatus = AuthCheckStatus.Success(result.message)
                    Toast.makeText(context, "Протокол PTZ: ${result.message}", Toast.LENGTH_LONG).show()
                }
                is PtzDetectionResult.NotDetected -> {
                    ptzDetectStatus = AuthCheckStatus.Error(result.message)
                    if (!silent) errorPopupText = result.message
                }
            }

            // Dahua с PTZ по ONVIF определяется как ONVIF, но события, архив и звук у неё есть, а Hipcam отдаёт архив
            // каталогом /sd/ — формат архива определяем отдельно; сообщение показывается только при успехе
            val archive = detectArchive()
            if (archive is AuthCheckStatus.Success) dahuaProbeStatus = archive
        }
    }

    /**
     * Тихая проверка наличия поворотного механизма — от неё зависит, показана ли вкладка PTZ. Выбранный вручную
     * протокол с PTZ не заменяется другим, а «Без PTZ» ставится только поверх автоопределяемых протоколов:
     * дешёвые камеры (Yoosee и аналоги) отвечают по ONVIF без PTZ, хотя поворачиваются по своему протоколу.
     */
    suspend fun refreshPtzAvailability() {
        val result = PtzProtocolDetector.detect(
            hostInput = ipAddress,
            httpPort = httpPortStr.toIntOrNull() ?: IpCamera.DEFAULT_HTTP_PORT,
            onvifPort = onvifPortStr.toIntOrNull() ?: IpCamera.DEFAULT_ONVIF_PORT,
            user = username,
            pass = password,
            onProgress = {}
        )
        if (result !is PtzDetectionResult.Detected) return
        val detectable = selectedProtocol in setOf(CameraProtocol.ONVIF, CameraProtocol.HIKVISION, CameraProtocol.DAHUA)
        when {
            result.protocol.supportsPtz -> if (!selectedProtocol.supportsPtz) selectedProtocol = result.protocol
            detectable -> selectedProtocol = CameraProtocol.NONE
        }
    }

    // Вкладка PTZ показывается и скрывается сама: после изменения адреса, портов, логина или пароля
    // (через паузу) камера опрашивается снова. Форма открыта для камеры с протоколом — её не трогаем, пока
    // данные не изменили; у камеры «Без PTZ» проверка идёт сразу. Новая камера из поиска настраивается автонастройкой
    var skipFirstPtzCheck by remember { mutableStateOf(initial?.protocol?.supportsPtz != false) }
    LaunchedEffect(ipAddress, httpPortStr, onvifPortStr, username, password) {
        if (isAutoSetup) return@LaunchedEffect
        if (skipFirstPtzCheck) {
            skipFirstPtzCheck = false
            return@LaunchedEffect
        }
        if (!looksLikeHost(ipAddress.trim()) || (username.isBlank() && password.isBlank())) return@LaunchedEffect
        delay(1_200L)
        // Идёт ручное определение — оно же и выставит протокол
        if (ptzDetectProgress == null) refreshPtzAvailability()
    }

    /** Проверка по HTTP: если камера отвечает как Dahua или отдаёт каталог /sd/, включается архив записей (у Dahua ещё события и разговор) */
    fun probeDahua() {
        dahuaProbing = true
        dahuaProbeStatus = AuthCheckStatus.Idle
        coroutineScope.launch {
            dahuaProbeStatus = detectArchive()
            dahuaProbing = false
        }
    }

    // Уведомления о событиях на Android 13+ требуют разрешения
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Без разрешения на уведомления сообщения о движении показываться не будут", Toast.LENGTH_LONG).show()
    }

    /**
     * Автопоиск потоков камеры: ONVIF и известные пары «основной / дополнительный»,
     * а если они не подошли — перебор одиночных известных путей.
     * Найденные потоки добавляются к списку, уже имеющиеся пути не дублируются.
     * @param silent не показывать окно с ошибкой (автоматический запуск без участия пользователя)
     */
    fun autoDetectStreams(silent: Boolean = false, onFound: () -> Unit = {}) {
        streamSearchProgress = "Поиск..."
        lastAutoDetectCredentials = username to password
        val rtspPort = rtspPortStr.toIntOrNull() ?: IpCamera.DEFAULT_RTSP_PORT

        coroutineScope.launch {
            resolveUnconfirmedOnvifPort { streamSearchProgress = it }
            val onvifPort = onvifPortStr.toIntOrNull() ?: IpCamera.DEFAULT_ONVIF_PORT
            var result = RtspPathFinder.findAllStreams(
                hostInput = ipAddress,
                rtspPort = rtspPort,
                onvifPort = onvifPort,
                user = username,
                pass = password,
                currentPath = streamProfiles.firstOrNull()?.rtspPath,
                onProgress = { streamSearchProgress = it }
            )

            // Пары путей не подошли — пробуем найти хотя бы один поток по полному списку путей
            if (result is StreamSearchResult.NotFound && !result.authRejected) {
                val single = RtspPathFinder.find(
                    hostInput = ipAddress,
                    rtspPort = rtspPort,
                    onvifPort = onvifPort,
                    user = username,
                    pass = password,
                    preferredPath = selectedProtocol.defaultRtspPath,
                    onProgress = { streamSearchProgress = it }
                )
                if (single is RtspPathSearchResult.Found && single.port == rtspPort) {
                    result = StreamSearchResult.Found(
                        listOf(com.example.denmediaipcams.network.DiscoveredStream("Основной", single.path, single.tracks)),
                        source = single.source,
                        leakedCredentials = single.leakedCredentials
                    )
                }
            }
            streamSearchProgress = null

            when (val found = result) {
                is StreamSearchResult.Found -> {
                    val newStreams = found.streams.filter { stream -> streamProfiles.none { it.rtspPath == stream.path } }
                    newStreams.forEach { stream ->
                        val profile = StreamProfile(name = stream.name, rtspPath = stream.path)
                        streamProfiles.add(profile)
                        streamCheckStatus[profile.id] = RtspCheckStatus.Success(stream.tracks)
                    }

                    val message = if (newStreams.isEmpty()) {
                        "Новых потоков не найдено — все ${found.streams.size} уже в списке"
                    } else {
                        "Найдено потоков (${found.source}): ${found.streams.size}, добавлено: ${newStreams.size}"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()

                    // Некоторые дешёвые камеры (HiSilicon/XMEye) по ошибке отдают свой настоящий логин и пароль
                    // открытым текстом в пути RTSP-потока по ONVIF — подставляем их, только если поля ещё пустые
                    // (не затираем то, что уже ввёл пользователь)
                    val leaked = found.leakedCredentials
                    if (leaked != null && username.isBlank() && password.isBlank()) {
                        username = leaked.first
                        password = leaked.second
                        Toast.makeText(
                            context,
                            "Камера сама сообщила логин и пароль в ответе ONVIF — они подставлены в поля входа.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onFound()
                }
                is StreamSearchResult.NotFound -> {
                    if (!silent) errorPopupText = found.message
                }
            }
        }
    }

    /** Поиск каналов видеорегистратора (NVR): результат показывается списком для выбора */
    fun findNvrChannels() {
        nvrSearchProgress = "Поиск..."
        coroutineScope.launch {
            val result = NvrChannelFinder.find(
                hostInput = ipAddress,
                rtspPort = rtspPortStr.toIntOrNull() ?: IpCamera.DEFAULT_RTSP_PORT,
                onvifPort = onvifPortStr.toIntOrNull() ?: IpCamera.DEFAULT_ONVIF_PORT,
                user = username,
                pass = password,
                onProgress = { nvrSearchProgress = it }
            )
            nvrSearchProgress = null
            when (result) {
                is NvrSearchResult.Found -> {
                    nvrChannels = result.channels
                    nvrSelected = result.channels.map { it.number }.toSet()
                    Toast.makeText(context, "Найдено каналов (${result.source}): ${result.channels.size}", Toast.LENGTH_LONG).show()
                }
                is NvrSearchResult.NotFound -> errorPopupText = result.message
            }
        }
    }

    // Автонастройка: потоки найдены — сразу определяем протокол PTZ (те же логин и пароль подошли)
    fun autoSetup(silent: Boolean) {
        autoDetectStreams(silent = silent, onFound = { autoDetectPtzProtocol(silent = true) })
    }

    /** Повторный поиск потоков после ввода логина и пароля (один раз на каждую новую пару) */
    fun autoSetupAfterCredentials() {
        if (!isAutoSetup) return
        if (streamProfiles.isNotEmpty() || streamSearchProgress != null) return
        if (username.isBlank() || password.isBlank()) return
        if (lastAutoDetectCredentials == (username to password)) return
        autoSetup(silent = false)
    }

    /**
     * Применяет найденные порты к полям: порт, уже подтверждённый для своего назначения, остаётся,
     * иначе подставляется лучший из найденных (или поле очищается, если подходящего нет).
     * Заполненное поле сразу получает зелёный статус — порт проверен по ответу протокола.
     */
    fun applyDetectedPorts(ports: List<KnownPort>) {
        val selection = PortDetector.select(ports)
        fun resolve(current: String, kind: PortKind, picked: Int?): String {
            val value = current.toIntOrNull()
            val confirmed = value != null && ports.any { it.port == value && kind in it.kinds }
            return if (confirmed) current else picked?.toString() ?: ""
        }
        rtspPortStr = resolve(rtspPortStr, PortKind.RTSP, selection.rtsp)
        httpPortStr = resolve(httpPortStr, PortKind.HTTP, selection.http)
        onvifPortStr = resolve(onvifPortStr, PortKind.ONVIF, selection.onvif)
        dahuaPortStr = resolve(dahuaPortStr, PortKind.DAHUA_SDK, selection.dahua)
        dvripPortStr = resolve(dvripPortStr, PortKind.DVRIP, selection.dvrip)
        rtspPingStatus = if (rtspPortStr.isBlank()) PortPingStatus.Idle else PortPingStatus.Success
        httpPingStatus = if (httpPortStr.isBlank()) PortPingStatus.Idle else PortPingStatus.Success
        onvifPingStatus = if (onvifPortStr.isBlank()) PortPingStatus.Idle else PortPingStatus.Success
        dahuaPingStatus = if (dahuaPortStr.isBlank()) PortPingStatus.Idle else PortPingStatus.Success
        dvripPingStatus = if (dvripPortStr.isBlank()) PortPingStatus.Idle else PortPingStatus.Success
        // Стандарт по найденным портам: только пока он не определён и пользователь не выбрал его сам
        if (!standardManual && standard == CameraStandard.None) {
            when {
                selection.dahua != null -> selectStandard(CameraStandard.Dahua)
                selection.dvrip != null -> selectStandard(CameraStandard.XMEye)
            }
        }
    }

    fun reportDetectedPorts(ports: List<KnownPort>) {
        val summary = listOf(
            "RTSP" to rtspPortStr, "HTTP" to httpPortStr, "ONVIF" to onvifPortStr, "Dahua" to dahuaPortStr, "DVRIP" to dvripPortStr
        ).joinToString(", ") { (title, value) -> "$title ${value.ifBlank { "—" }}" }
        Toast.makeText(context, "Открытых портов: ${ports.size}. $summary", Toast.LENGTH_LONG).show()
    }

    /**
     * Быстрое определение портов: проверяются порты, которые используют камеры, плюс уже введённые вручную.
     * Каждый открытый порт опрашивается RTSP-, HTTP- и ONVIF-запросом, поэтому в поле попадает
     * только тот порт, который действительно отвечает нужным протоколом.
     * @param onDone true — порты найдены и применены
     */
    fun detectPorts(onDone: (Boolean) -> Unit = {}) {
        val host = ipAddress
        if (host.isBlank()) {
            Toast.makeText(context, "Сначала укажите IP-адрес камеры", Toast.LENGTH_SHORT).show()
            return
        }
        portDetectJob?.cancel()
        portScanProgress = "Проверка портов..."
        portDetectJob = coroutineScope.launch {
            val manual = listOf(rtspPortStr, httpPortStr, onvifPortStr, dahuaPortStr, dvripPortStr).mapNotNull { it.toIntOrNull() }
            val found = PortDetector.detect(
                host = host,
                extraPorts = manual + knownPorts.map { it.port },
                onProgress = { portScanProgress = it }
            )
            portScanProgress = null
            // Адрес изменили, пока шла проверка — результат относится к другой камере
            if (host != ipAddress) return@launch
            if (found.isEmpty()) {
                Toast.makeText(context, "Открытых портов не найдено — проверьте адрес и сеть", Toast.LENGTH_LONG).show()
                onDone(false)
                return@launch
            }
            knownPorts.clear()
            knownPorts.addAll(found)
            applyDetectedPorts(found)
            reportDetectedPorts(found)
            onDone(true)
        }
    }

    /** Полное сканирование всех портов (1-65535) с определением протокола на каждом открытом */
    fun fullPortScan() {
        val host = ipAddress
        if (host.isBlank()) {
            Toast.makeText(context, "Сначала укажите IP-адрес камеры", Toast.LENGTH_SHORT).show()
            return
        }
        portDetectJob?.cancel()
        portScanProgress = "Сканирование всех портов (1-65535)..."
        portDetectJob = coroutineScope.launch {
            val open = NetworkTester.scanAllPorts(host = host, onProgress = { portScanProgress = it })
            portScanProgress = "Определение протоколов..."
            val found = PortDetector.classifyAll(host, open)
            portScanProgress = null
            if (host != ipAddress) return@launch
            if (found.isEmpty()) {
                Toast.makeText(context, "Не найдено открытых портов", Toast.LENGTH_LONG).show()
                return@launch
            }
            knownPorts.clear()
            knownPorts.addAll(found)
            applyDetectedPorts(found)
            reportDetectedPorts(found)
        }
    }

    // Новая камера: порты определяются сами через паузу после ввода адреса (для найденной поиском —
    // сразу при открытии формы), затем — потоки и протокол PTZ, если логин и пароль уже есть
    LaunchedEffect(ipAddress) {
        if (!isAutoSetup) return@LaunchedEffect
        val host = ipAddress.trim()
        if (!looksLikeHost(host) || host == lastAutoDetectedHost) return@LaunchedEffect
        delay(if (draft != null && lastAutoDetectedHost == null) 0L else 900L)
        lastAutoDetectedHost = host
        detectPorts { found -> if (found && draft != null) autoSetup(silent = true) }
    }

    /** Проверка одного потока: камера должна отдать его описание с текущими логином и паролем */
    fun checkStream(profile: StreamProfile) {
        val testCamera = IpCamera(
            id = "",
            name = name,
            ipAddress = ipAddress,
            rtspPort = rtspPortStr.toIntOrNull() ?: IpCamera.DEFAULT_RTSP_PORT,
            rtspPath = profile.rtspPath,
            username = username,
            password = password
        )
        streamCheckStatus[profile.id] = RtspCheckStatus.Loading
        coroutineScope.launch {
            val result = NetworkTester.probeRtsp(testCamera.fullRtspUrl, username, password)
            streamCheckStatus[profile.id] = result
            when (result) {
                is RtspCheckStatus.Success -> Toast.makeText(context, "${profile.name}: ${result.message}", Toast.LENGTH_LONG).show()
                is RtspCheckStatus.Error -> errorPopupText = "${profile.name}: ${result.message}"
                else -> {}
            }
        }
    }

    // Смартфон (меньшая сторона экрана < 600dp) — форма на весь экран, планшет — обычный диалог
    val configuration = LocalConfiguration.current
    val isPhone = minOf(configuration.screenWidthDp, configuration.screenHeightDp) < 600
    // Узкий экран: поля логина/пароля и потока не помещаются в одну строку и идут друг под другом
    val compactWidth = configuration.screenWidthDp < 500
    // Смартфон горизонтально: заголовка нет, шестерёнка стоит слева от вкладок, кнопки — справа столбиком
    val isPhoneLandscape = isPhone && configuration.screenWidthDp > configuration.screenHeightDp

    // Вкладка PTZ нужна, только если у камеры есть поворотный механизм; если она пропала (протокол сменился
    // на «Без PTZ» или автоопределение не нашло PTZ), открытая вкладка заменяется на «RTSP»
    val ptzTabVisible = selectedProtocol.supportsPtz
    val activeTab = EditTab.entries[selectedTab].let { if (it == EditTab.Ptz && !ptzTabVisible) EditTab.Rtsp else it }

    val body: @Composable (Boolean) -> Unit = { fullScreen ->
            Column(modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPhoneLandscape) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Настройки камеры",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                    // Вкладки не помещаются в узкий экран смартфона — строка прокручивается
                    val visibleTabs = EditTab.entries.filter { it != EditTab.Ptz || ptzTabVisible }
                    SecondaryScrollableTabRow(
                        selectedTabIndex = visibleTabs.indexOf(activeTab),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        edgePadding = 0.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        visibleTabs.forEach { tab ->
                            Tab(
                                selected = activeTab == tab,
                                onClick = { selectedTab = tab.ordinal },
                                text = { Text(tab.title, maxLines = 1) },
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isPhoneLandscape) 8.dp else 12.dp))

                Column(
                    // Диалог (планшет): фиксированная высота — он не меняет размер при переключении вкладок,
                    // и кнопки «Сохранить» / «Отмена» и табы не прыгают из-под пальца.
                    // Полный экран (смартфон): форма занимает всё место между вкладками и кнопками
                    // На планшете высота растёт вместе с экраном (за вычетом заголовка, вкладок, кнопок и полей)
                    modifier = (if (fullScreen) Modifier.weight(1f) else Modifier.height((configuration.screenHeightDp - 330).coerceIn(380, 720).dp))
                        .fillMaxWidth()
                        .verticalScrollWithBar()
                        // Тонкая полоса прокрутки рисуется по краю окна — не даём ей наезжать на кнопки и текст
                        .padding(end = 6.dp)
                ) {
                    when (activeTab) {
                        EditTab.Main -> MainTab(
                            name = name,
                            onNameChange = { name = it },
                            ipAddress = ipAddress,
                            onIpAddressChange = {
                                ipAddress = it
                                ipPingStatus = PortPingStatus.Idle
                            },
                            ipPingStatus = ipPingStatus,
                            onPingIp = { pingIpAddress() },
                            username = username,
                            onUsernameChange = {
                                username = it
                                authCheckStatus = AuthCheckStatus.Idle
                            },
                            password = password,
                            onPasswordChange = {
                                password = it
                                authCheckStatus = AuthCheckStatus.Idle
                            },
                            onPasswordEntered = {
                                focusManager.clearFocus()
                                autoSetupAfterCredentials()
                            },
                            onPasswordFocusLost = { autoSetupAfterCredentials() },
                            authCheckStatus = authCheckStatus,
                            onCheckAuth = { checkCameraAuth() },
                            onShowError = { errorPopupText = it },
                            compact = compactWidth
                        )

                        EditTab.Ports -> {
                            SectionTitle("Настройка и проверка портов")

                            val scanning = portScanProgress != null
                            // Быстрое определение: порты камер + проверка протокола на каждом открытом
                            FilledTonalButton(
                                onClick = { detectPorts() },
                                enabled = !scanning && ipAddress.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (scanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(portScanProgress ?: "", maxLines = 1)
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Определить порты автоматически")
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { fullPortScan() },
                                enabled = !scanning && ipAddress.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Полное сканирование (1–65535)")
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            PortDropdownField(
                                label = "RTSP порт",
                                value = rtspPortStr,
                                defaultPort = IpCamera.DEFAULT_RTSP_PORT,
                                expected = PortKind.RTSP,
                                knownPorts = knownPorts,
                                hint = "Видеопоток камеры. Без него видео не работает.",
                                onValueChange = {
                                    rtspPortStr = it
                                    rtspPingStatus = PortPingStatus.Idle
                                },
                                status = rtspPingStatus,
                                onPingClick = {
                                    pingSinglePort("RTSP порт", rtspPortStr, IpCamera.DEFAULT_RTSP_PORT, PortKind.RTSP) { rtspPingStatus = it }
                                },
                                onErrorInfoClick = { errorMsg -> errorPopupText = "Ошибка RTSP порта: $errorMsg" }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val httpRequired = selectedProtocol == CameraProtocol.HIKVISION ||
                                selectedProtocol == CameraProtocol.DAHUA ||
                                selectedProtocol == CameraProtocol.GENERIC_HTTP
                            PortDropdownField(
                                label = "HTTP порт",
                                value = httpPortStr,
                                defaultPort = IpCamera.DEFAULT_HTTP_PORT,
                                expected = PortKind.HTTP,
                                knownPorts = knownPorts,
                                hint = if (httpRequired) {
                                    "Нужен для выбранного протокола: PTZ, пресеты, день/ночь и снимки идут через него."
                                } else {
                                    "Не обязателен: видео идёт по RTSP, PTZ — по ONVIF. " +
                                        "Используется при проверке авторизации и определении протокола PTZ."
                                },
                                onValueChange = {
                                    httpPortStr = it
                                    httpPingStatus = PortPingStatus.Idle
                                },
                                status = httpPingStatus,
                                onPingClick = {
                                    pingSinglePort("HTTP порт", httpPortStr, IpCamera.DEFAULT_HTTP_PORT, PortKind.HTTP) { httpPingStatus = it }
                                },
                                onErrorInfoClick = { errorMsg -> errorPopupText = "Ошибка HTTP порта: $errorMsg" }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            PortDropdownField(
                                label = "ONVIF порт",
                                value = onvifPortStr,
                                defaultPort = IpCamera.DEFAULT_ONVIF_PORT,
                                expected = PortKind.ONVIF,
                                knownPorts = knownPorts,
                                hint = "PTZ по ONVIF, поиск потоков и снимки. Если камера не поддерживает ONVIF — оставьте пустым.",
                                onValueChange = {
                                    onvifPortStr = it
                                    onvifPingStatus = PortPingStatus.Idle
                                },
                                status = onvifPingStatus,
                                onPingClick = {
                                    pingSinglePort("ONVIF порт", onvifPortStr, IpCamera.DEFAULT_ONVIF_PORT, PortKind.ONVIF) { onvifPingStatus = it }
                                },
                                onErrorInfoClick = { errorMsg -> errorPopupText = "Ошибка ONVIF порта: $errorMsg" }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            CameraStandardDropdown(
                                selected = standard,
                                onSelect = { selectStandard(it, manual = true) }
                            )
                            if (standard == CameraStandard.Dahua) {
                                Spacer(modifier = Modifier.height(6.dp))
                                PortDropdownField(
                                    label = "Dahua порт",
                                    value = dahuaPortStr,
                                    defaultPort = IpCamera.DEFAULT_DAHUA_PORT,
                                    expected = PortKind.DAHUA_SDK,
                                    knownPorts = knownPorts,
                                    hint = "Проприетарный порт Dahua (обычно 37777). Если оставить пустым, используется 37777.",
                                    onValueChange = {
                                        dahuaPortStr = it
                                        dahuaPingStatus = PortPingStatus.Idle
                                    },
                                    status = dahuaPingStatus,
                                    onPingClick = {
                                        pingSinglePort("Dahua порт", dahuaPortStr, IpCamera.DEFAULT_DAHUA_PORT, PortKind.DAHUA_SDK) { dahuaPingStatus = it }
                                    },
                                    onErrorInfoClick = { errorMsg -> errorPopupText = "Ошибка Dahua порта: $errorMsg" }
                                )
                            }
                            if (standard == CameraStandard.XMEye) {
                                Spacer(modifier = Modifier.height(6.dp))
                                PortDropdownField(
                                    label = "XMEye/DVRIP порт",
                                    value = dvripPortStr,
                                    defaultPort = IpCamera.DEFAULT_DVRIP_PORT,
                                    expected = PortKind.DVRIP,
                                    knownPorts = knownPorts,
                                    hint = "Проприетарный порт XMEye/DVRIP (обычно 34567). Нужен только для архива по " +
                                        "этому протоколу (вкладка «Архив видео»); проверка лишь подтверждает, что порт " +
                                        "открыт, не что камера на нём отвечает — протокол сознательно не прощупывается.",
                                    onValueChange = {
                                        dvripPortStr = it
                                        dvripPingStatus = PortPingStatus.Idle
                                    },
                                    status = dvripPingStatus,
                                    onPingClick = {
                                        pingSinglePort("XMEye/DVRIP порт", dvripPortStr, IpCamera.DEFAULT_DVRIP_PORT, PortKind.DVRIP) { dvripPingStatus = it }
                                    },
                                    onErrorInfoClick = { errorMsg -> errorPopupText = "Ошибка XMEye/DVRIP порта: $errorMsg" }
                                )
                            }
                            if (standard == CameraStandard.Hipcam) {
                                Spacer(modifier = Modifier.height(6.dp))
                                PortDropdownField(
                                    label = "Hipcam порт",
                                    value = hipcamPortStr,
                                    defaultPort = httpPortStr.toIntOrNull() ?: IpCamera.DEFAULT_HTTP_PORT,
                                    expected = PortKind.HTTP,
                                    knownPorts = knownPorts,
                                    hint = "HTTP-порт камер Hipcam: архив на SD-карте (каталог /sd/) и настройки изображения. " +
                                        "Если оставить пустым, используется HTTP порт.",
                                    onValueChange = {
                                        hipcamPortStr = it
                                        hipcamPingStatus = PortPingStatus.Idle
                                    },
                                    status = hipcamPingStatus,
                                    onPingClick = {
                                        pingSinglePort(
                                            "Hipcam порт",
                                            hipcamPortStr,
                                            httpPortStr.toIntOrNull() ?: IpCamera.DEFAULT_HTTP_PORT,
                                            PortKind.HTTP
                                        ) { hipcamPingStatus = it }
                                    },
                                    onErrorInfoClick = { errorMsg -> errorPopupText = "Ошибка Hipcam порта: $errorMsg" }
                                )
                            }

                            // Открытые порты этой камеры с подтверждённым протоколом
                            if (knownPorts.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                SectionTitle("Открытые порты камеры (нажмите, чтобы скопировать)")
                                val clipboardManager = LocalClipboardManager.current
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    knownPorts.forEach { known ->
                                        InputChip(
                                            selected = false,
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(known.port.toString()))
                                                Toast.makeText(context, "Порт ${known.port} скопирован в буфер", Toast.LENGTH_SHORT).show()
                                            },
                                            label = { Text(known.label) }
                                        )
                                    }
                                }
                            }
                        }

                        EditTab.Rtsp -> {
                            SectionTitle("Потоки камеры (RTSP)")
                            val awaitingCredentials = isAutoSetup && streamProfiles.isEmpty() &&
                                streamSearchProgress == null && (username.isBlank() || password.isBlank())
                            Text(
                                text = if (awaitingCredentials) {
                                    "Введите логин и пароль на вкладке «Основные» — потоки найдутся автоматически"
                                } else {
                                    "Первый поток — основной. Если потоков несколько (разное качество), " +
                                        "переключать их можно прямо в плеере"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (awaitingCredentials) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            streamProfiles.forEachIndexed { index, profile ->
                                StreamProfileRow(
                                    profile = profile,
                                    status = streamCheckStatus[profile.id] ?: RtspCheckStatus.Idle,
                                    onNameChange = { streamProfiles[index] = profile.copy(name = it) },
                                    onPathChange = {
                                        streamProfiles[index] = profile.copy(rtspPath = it)
                                        streamCheckStatus.remove(profile.id)
                                    },
                                    onCheck = { checkStream(profile) },
                                    onShowError = { errorPopupText = "${profile.name}: $it" },
                                    onDelete = {
                                        streamProfiles.removeAt(index)
                                        streamCheckStatus.remove(profile.id)
                                    },
                                    compact = compactWidth
                                )
                            }

                            // Понижать качество есть смысл, только если есть поток попроще
                            if (streamProfiles.size >= 2) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { autoLowerQuality = !autoLowerQuality }
                                ) {
                                    Checkbox(
                                        checked = autoLowerQuality,
                                        onCheckedChange = { autoLowerQuality = it }
                                    )
                                    Column {
                                        Text("Временно понижать качество видео, если связь с камерой не стабильна")
                                        Text(
                                            "Пока связь плохая, плеер переключится на поток с меньшим разрешением " +
                                                "и вернёт выбранный, когда связь наладится.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Автопоиск потоков (ONVIF + известные пути производителей)
                            FilledTonalButton(
                                onClick = { autoDetectStreams() },
                                enabled = streamSearchProgress == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (streamSearchProgress != null) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(streamSearchProgress ?: "", maxLines = 1)
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Найти потоки автоматически")
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedButton(
                                onClick = {
                                    streamProfiles.add(
                                        StreamProfile(
                                            name = if (streamProfiles.isEmpty()) "Основной" else "Поток ${streamProfiles.size + 1}",
                                            rtspPath = if (streamProfiles.isEmpty()) selectedProtocol.defaultRtspPath else ""
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Добавить поток вручную")
                            }

                            // Видеорегистратор (NVR): каналы добавляются отдельными камерами
                            if (!isEditing && onSaveMultiple != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = { findNvrChannels() },
                                    enabled = nvrSearchProgress == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (nvrSearchProgress != null) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(nvrSearchProgress ?: "", maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Это регистратор (NVR): найти каналы")
                                    }
                                }
                            }

                        }

                        EditTab.Ptz -> {
                            SectionTitle("Протокол PTZ управления")
                            // Варианты всегда видны чипами: без popup и без скрытого раскрывающегося списка.
                            // «Без PTZ» среди них нет: выбор этого варианта убрал бы саму вкладку из-под пальца
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CameraProtocol.entries.filter { it.supportsPtz }.forEach { protocol ->
                                    FilterChip(
                                        selected = protocol == selectedProtocol,
                                        onClick = {
                                            selectedProtocol = protocol
                                            ptzDetectStatus = AuthCheckStatus.Idle
                                        },
                                        label = { Text(protocol.displayName) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Автоопределение протокола PTZ (ONVIF → Hikvision → Dahua)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalButton(
                                    onClick = { autoDetectPtzProtocol() },
                                    enabled = ptzDetectProgress == null,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (ptzDetectProgress != null) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(ptzDetectProgress ?: "", maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Определить автоматически")
                                    }
                                }
                                StatusIndicator(
                                    status = ptzDetectStatus,
                                    errorDescription = "Протокол не определён (нажмите для подробностей)",
                                    onShowError = { errorPopupText = it }
                                )
                            }

                            // Режим управления для дешёвых поворотных камер (Yoosee и аналоги)
                            if (selectedProtocol.supportsPtz) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { ptzRepeatMode = !ptzRepeatMode }
                                ) {
                                    Checkbox(checked = ptzRepeatMode, onCheckedChange = { ptzRepeatMode = it })
                                    Column {
                                        Text("Повторять команду поворота при удержании")
                                        Text(
                                            "Для камер, которые поворачиваются ограниченным шагом (например Yoosee). " +
                                                "Зум и фокус в этом режиме отключаются.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { ptzInvertPan = !ptzInvertPan }
                                ) {
                                    Checkbox(checked = ptzInvertPan, onCheckedChange = { ptzInvertPan = it })
                                    Column {
                                        Text("Зеркалить поворот по горизонтали")
                                        Text(
                                            "Если камера поворачивается влево, когда нужно вправо. Поворот видео " +
                                                "(90°/180°) учитывается отдельно, автоматически.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { ptzInvertTilt = !ptzInvertTilt }
                                ) {
                                    Checkbox(checked = ptzInvertTilt, onCheckedChange = { ptzInvertTilt = it })
                                    Column {
                                        Text("Зеркалить наклон по вертикали")
                                        Text(
                                            "Если камера наклоняется вниз, когда нужно вверх.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Камеры бывают «с фокусом» только на словах: тогда фокус скрывается вручную
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Фокус")
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FocusAvailability.entries.forEach { option ->
                                        FilterChip(
                                            selected = option == focusAvailability,
                                            onClick = { focusAvailability = option },
                                            label = { Text(option.displayName) }
                                        )
                                    }
                                }
                                Text(
                                    "«Авто» — по ответу камеры. Если камера сообщает о фокусе, которого у неё нет " +
                                        "(или наоборот), выберите «Есть» или «Нет».",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }                        }

                        EditTab.SdCard -> {
                            SectionTitle("Архив записей")
                            Text(
                                "Формат архива определяется автоматически: HTTP API Dahua (архив, события и разговор через камеру), " +
                                    "каталог /sd/ на веб-порту (Hipcam) или XMEye/DVRIP. Протокол PTZ при этом может быть любым (например, ONVIF).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalButton(
                                    onClick = { probeDahua() },
                                    enabled = !dahuaProbing && ipAddress.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (dahuaProbing) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Проверка...", maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Определить формат архива")
                                    }
                                }
                                StatusIndicator(
                                    status = dahuaProbeStatus,
                                    errorDescription = "Формат архива не определён (нажмите для подробностей)",
                                    onShowError = { errorPopupText = it }
                                )
                            }
                            // Не Dahua — галочка не нужна
                            if (standard == CameraStandard.Dahua) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { dahuaApi = !dahuaApi }
                                ) {
                                    Checkbox(checked = dahuaApi, onCheckedChange = { dahuaApi = it })
                                    Column {
                                        Text("Камера Dahua")
                                        Text(
                                            "Показывает в плеере «Архив записей» и «Говорить через камеру».",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            // Камера с текущими значениями формы: адрес, порт, учётные данные и найденный
                            // формат архива могли измениться, пока окно открыто
                            val formCamera = (cameraToEdit ?: IpCamera(id = "", name = "", ipAddress = "")).copy(
                                ipAddress = ipAddress.trim(),
                                httpPort = httpPortStr.toIntOrNull() ?: 0,
                                hipcamPort = standardPort(CameraStandard.Hipcam, hipcamPortStr),
                                dvripPort = standardPort(CameraStandard.XMEye, dvripPortStr),
                                username = username,
                                password = password,
                                protocol = selectedProtocol,
                                dahuaApi = dahuaApi,
                                sdHttpArchive = sdHttpArchive,
                                hikvisionArchive = hikvisionArchive,
                                dvripArchive = dvripArchive
                            )
                            // Пока формат архива не определён, настройки архива недоступны
                            val archiveDetected = formCamera.hasDahuaApi || sdHttpArchive || hikvisionArchive || dvripArchive
                            // Сведения о карте и форматирование — только там, где у камеры есть такая команда
                            // (Dahua JSON-RPC, Hikvision ISAPI, Hipcam CGI); у DVRIP такой безопасно подтверждённой команды нет
                            if (formCamera.hasDahuaApi || sdHttpArchive || hikvisionArchive) {
                                SdCardTab(formCamera, onFormatted = onSdFormatted)
                            } else if (dvripArchive) {
                                // Сведения о карте и форматирование по DVRIP (сессия по порту и учётным данным формы)
                                SdCardTab(formCamera, onFormatted = onSdFormatted)
                                Spacer(modifier = Modifier.height(16.dp))
                                DvripArchivePanel(camera = formCamera)
                            } else {
                                Text(
                                    "Сведения о карте и открытие архива станут доступны после определения формата архива.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (archiveDetected && CameraRecording.canTry(formCamera)) {
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(16.dp))
                                RecordingSettingsPanel(formCamera, recordingState)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            ArchiveBlockSettings(
                                active = archiveDetected,
                                enabled = archiveBlockEnabled,
                                onEnabledChange = { archiveBlockEnabled = it },
                                countText = archiveBlockCountStr,
                                onCountChange = { text ->
                                    // Только цифры, не больше двух знаков; допустимые значения 1..10 проверяются при сохранении
                                    if (text.length <= 2 && text.all { it.isDigit() }) archiveBlockCountStr = text
                                },
                                hideOnMetered = archiveBlockHideOnMetered,
                                onHideOnMeteredChange = { archiveBlockHideOnMetered = it }
                            )
                        }

                        EditTab.Advanced -> {
                            SectionTitle("Дополнительно")
                            // Поворот видео
                            Text("Поворот видео", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                VideoRotation.values().forEach { rotation ->
                                    FilterChip(
                                        selected = videoRotation == rotation,
                                        onClick = {
                                            videoRotation = rotation
                                            android.util.Log.d("CameraEditDialog", "Selected rotation: $rotation (${rotation.degrees}°)")
                                        },
                                        label = { Text(rotation.displayName) }
                                    )
                                }
                            }
                            Text(
                                if (initial?.hardwareRotation?.let { it != VideoRotation.NONE } == true) {
                                    "Сейчас поворот выполняет сама камера: при сохранении она получит новую настройку."
                                } else {
                                    "Если камера умеет сама переворачивать картинку (Dahua, Hipcam, XMEye), поворот " +
                                        "выполняет она и при сохранении получит новую настройку; иначе картинку поворачивает приложение."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            // Снимок берётся с HTTP-порта (для ONVIF — адрес снимка запрашивается через ONVIF-порт);
                            // если нужный порт не отвечает, опция недоступна и превью берётся из трансляции
                            val snapshotPortFailed = when (selectedProtocol) {
                                CameraProtocol.HIKVISION, CameraProtocol.DAHUA -> httpPingStatus is PortPingStatus.Error
                                else -> onvifPingStatus is PortPingStatus.Error
                            }
                            val snapshotEnabled = !snapshotPortFailed
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = snapshotEnabled) { snapshotThumbnail = !snapshotThumbnail }
                            ) {
                                Checkbox(
                                    checked = snapshotThumbnail && snapshotEnabled,
                                    onCheckedChange = { snapshotThumbnail = it },
                                    enabled = snapshotEnabled
                                )
                                Column {
                                    Text("Обновлять превью снимком камеры")
                                    Text(
                                        if (snapshotEnabled) {
                                            "Если камера отдаёт снимок по HTTP, он периодически (раз в 10 минут) " +
                                                "ставится в превью камеры. Иначе превью остаётся кадром из трансляции."
                                        } else {
                                            "Порт, через который камера отдаёт снимок, недоступен — " +
                                                "превью берётся из трансляции."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = dahuaApi) {
                                        motionAlerts = !motionAlerts
                                        if (motionAlerts) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                            ) {
                                Checkbox(
                                    checked = motionAlerts && dahuaApi,
                                    onCheckedChange = {
                                        motionAlerts = it
                                        if (it) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    enabled = dahuaApi
                                )
                                Column {
                                    Text("Сообщать о движении и событиях")
                                    Text(
                                        "Плашка на видео и уведомление, пока приложение запущено " +
                                            "(в том числе свёрнутое).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // Опция общая для всех камер: сам способ включения звука у камеры подбирается по тому, что определилось
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { cameraAudioWithApp = !cameraAudioWithApp }
                            ) {
                                Checkbox(checked = cameraAudioWithApp, onCheckedChange = { cameraAudioWithApp = it })
                                Column {
                                    Text("Включать звук в трансляции камеры")
                                    Text(
                                        "Когда звук включён в приложении, а в потоке камеры его нет, приложение включает " +
                                            "звук в настройках потока самой камеры (Hikvision, Dahua; у остальных звук включается " +
                                            "в настройках камеры). Со звуком в потоке он попадает и в записи на SD-карту.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDateTime = !showDateTime }
                            ) {
                                Checkbox(checked = showDateTime, onCheckedChange = { showDateTime = it })
                                Column {
                                    Text("Выводить дату и время")
                                    Text(
                                        "Текущие дата и время поверх видео.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (showDateTime) {
                                Spacer(modifier = Modifier.height(8.dp))
                                // Отступ выравнивает сетку по тексту галочки
                                ClockPositionPicker(
                                    selected = clockPosition,
                                    onSelect = { clockPosition = it },
                                    modifier = Modifier.padding(start = 48.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            // Камера с текущими значениями формы: порты и учётные данные могли измениться, пока окно открыто
                            val timeSyncCamera = (initial ?: IpCamera(id = "", name = "", ipAddress = "")).copy(
                                ipAddress = ipAddress.trim(),
                                httpPort = httpPortStr.toIntOrNull() ?: 0,
                                onvifPort = onvifPortStr.toIntOrNull() ?: 0,
                                username = username,
                                password = password,
                                protocol = selectedProtocol,
                                dahuaApi = dahuaApi
                            )
                            val timeSyncAvailable = ipAddress.isNotBlank() && CameraTimeSync.isAvailable(timeSyncCamera)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = timeSyncAvailable) { autoSyncTime = !autoSyncTime }
                            ) {
                                Checkbox(
                                    checked = autoSyncTime && timeSyncAvailable,
                                    onCheckedChange = { autoSyncTime = it },
                                    enabled = timeSyncAvailable
                                )
                                Column {
                                    Text("Обновлять время автоматически")
                                    Text(
                                        if (timeSyncAvailable) {
                                            "Камере ставятся время и часовой пояс планшета: при добавлении и когда её " +
                                                "не было в сети больше 10 минут."
                                        } else {
                                            "Недоступно: для установки времени нужен ONVIF-порт или HTTP API Dahua."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (timeSyncAvailable) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = {
                                        timeSyncRunning = true
                                        timeSyncStatus = AuthCheckStatus.Idle
                                        coroutineScope.launch {
                                            val result = CameraTimeSync.sync(timeSyncCamera)
                                            timeSyncStatus = if (result.ok) AuthCheckStatus.Success(result.message) else AuthCheckStatus.Error(result.message)
                                            timeSyncRunning = false
                                        }
                                    },
                                    enabled = !timeSyncRunning,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (timeSyncRunning) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Установка времени...", maxLines = 1)
                                    } else {
                                        Icon(Icons.Default.Schedule, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Синхронизировать время", maxLines = 1)
                                    }
                                }
                                when (val status = timeSyncStatus) {
                                    is AuthCheckStatus.Success -> Text(
                                        status.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SuccessGreen,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    is AuthCheckStatus.Error -> Text(
                                        status.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
    }

    val saveButton: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(
                modifier = modifier,
                onClick = {
                    val finalName = name.ifBlank { "Камера IP" }
                    // Пустое поле = порт не задан (0); эффективное значение берётся из effective*Port
                    val finalRtspPort = rtspPortStr.toIntOrNull() ?: 0
                    val finalHttpPort = httpPortStr.toIntOrNull() ?: 0
                    val finalOnvifPort = onvifPortStr.toIntOrNull() ?: 0
                    // У камеры остаётся только порт её стандарта
                    val finalDahuaPort = standardPort(CameraStandard.Dahua, dahuaPortStr)
                    val finalDvripPort = standardPort(CameraStandard.XMEye, dvripPortStr)
                    val finalHipcamPort = standardPort(CameraStandard.Hipcam, hipcamPortStr)

                    val finalStreamProfiles = streamProfiles
                        .filter { it.rtspPath.isNotBlank() }
                        .map { if (it.name.isBlank()) it.copy(name = "Поток") else it }
                    // Если активный поток удалили из списка — сбрасываем на первый (основной)
                    val finalActiveStreamProfileId = initial?.activeStreamProfileId
                        ?.takeIf { id -> finalStreamProfiles.any { it.id == id } }
                    // Основной путь камеры — первый поток; без потоков остаётся прежний путь или типовой для протокола
                    val finalRtspPath = finalStreamProfiles.firstOrNull()?.rtspPath
                        ?: initial?.rtspPath?.takeIf { it.isNotBlank() }
                        ?: selectedProtocol.defaultRtspPath.ifBlank { IpCamera.DEFAULT_RTSP_PATH }

                    // copy() сохраняет скорость PTZ и пресеты редактируемой камеры
                    val resultCamera = (initial ?: IpCamera(id = "", name = "", ipAddress = "")).copy(
                        name = finalName,
                        ipAddress = ipAddress.ifBlank { "192.168.1.100" },
                        rtspPort = finalRtspPort,
                        httpPort = finalHttpPort,
                        onvifPort = finalOnvifPort,
                        dahuaPort = finalDahuaPort,
                        dvripPort = finalDvripPort,
                        hipcamPort = finalHipcamPort,
                        knownPorts = knownPorts.toList(),
                        rtspPath = finalRtspPath,
                        username = username,
                        password = password,
                        protocol = selectedProtocol,
                        ptzRepeatMode = ptzRepeatMode && selectedProtocol.supportsPtz,
                        ptzInvertPan = ptzInvertPan,
                        ptzInvertTilt = ptzInvertTilt,
                        focusAvailability = focusAvailability,
                        streamProfiles = finalStreamProfiles,
                        activeStreamProfileId = finalActiveStreamProfileId,
                        autoLowerQuality = autoLowerQuality,
                        snapshotThumbnail = snapshotThumbnail && !when (selectedProtocol) {
                            CameraProtocol.HIKVISION, CameraProtocol.DAHUA -> httpPingStatus is PortPingStatus.Error
                            else -> onvifPingStatus is PortPingStatus.Error
                        },
                        dahuaApi = dahuaApi,
                        sdHttpArchive = sdHttpArchive,
                        hikvisionArchive = hikvisionArchive,
                        cameraAudioWithApp = cameraAudioWithApp,
                        dvripArchive = dvripArchive,
                        motionAlerts = motionAlerts && dahuaApi,
                        showDateTime = showDateTime,
                        clockPosition = clockPosition,
                        videoRotation = videoRotation,
                        archiveBlockEnabled = archiveBlockEnabled,
                        archiveBlockCount = (archiveBlockCountStr.toIntOrNull() ?: IpCamera.MAX_ARCHIVE_BLOCK_COUNT)
                            .coerceIn(1, IpCamera.MAX_ARCHIVE_BLOCK_COUNT),
                        archiveBlockHideOnMetered = archiveBlockHideOnMetered,
                        autoSyncTime = autoSyncTime
                    )
                    android.util.Log.d("CameraEditDialog", "Saving camera with videoRotation: $videoRotation (${videoRotation.degrees}°)")
                    when {
                        // Расписание записи с ошибкой в камеру не отправить: показываем, что исправить
                        recordingState.changed && recordingState.problem != null -> selectedTab = EditTab.SdCard.ordinal
                        !recordingState.needsWrite -> onSave(resultCamera)
                        // Настройки записи живут в камере: сохраняем диалог, только если камера их приняла
                        else -> coroutineScope.launch {
                            if (recordingState.write(resultCamera)) {
                                onSave(resultCamera)
                            } else {
                                selectedTab = EditTab.SdCard.ordinal
                            }
                        }
                    }
                },
                enabled = !recordingState.saving
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сохранить")
            }
    }

    // Кнопка удаления камеры (только при редактировании существующей камеры)
    val deleteButton: @Composable (Modifier) -> Unit = { modifier ->
        if (isEditing && onDelete != null && cameraToEdit != null) {
            TextButton(
                onClick = { showDeleteConfirmation = true },
                modifier = modifier,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить камеру",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Удалить")
            }
        }
    }

    val cancelButton: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onDismiss, modifier = modifier) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Отмена")
        }
    }

    val dismissButtons: @Composable () -> Unit = {
        Row {
            deleteButton(Modifier)
            cancelButton(Modifier)
        }
    }

    val dialogTitle = if (isEditing) "Редактирование камеры" else "Добавление IP-камеры"
    // Закрытие только кнопкой «Отмена» или «Назад»: случайный тап мимо формы
    // (рядом с клавиатурой, меню или тостом) не должен терять введённые данные
    if (isPhone) {
        Dialog(
            onDismissRequest = onDismiss,
            // decorFitsSystemWindows = false: окно рисуется и под местом скрытой строки состояния
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // У диалога своё окно: приложение на смартфоне без системных панелей, и диалог тоже
            HideSystemBarsInDialog()
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                if (isPhoneLandscape) {
                    // Горизонтально высота — самое дорогое: без заголовка (только шестерёнка у вкладок),
                    // кнопки столбиком справа — форма занимает всю высоту экрана
                    Row(modifier = Modifier.fillMaxSize().imePadding()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 4.dp)) {
                            body(true)
                        }
                        VerticalDivider()
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                            modifier = Modifier
                                .width(150.dp)
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            saveButton(Modifier.fillMaxWidth())
                            cancelButton(Modifier.fillMaxWidth())
                            deleteButton(Modifier.fillMaxWidth())
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
                            Text(text = dialogTitle, style = MaterialTheme.typography.titleLarge)
                        }
                        Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) { body(true) }
                        HorizontalDivider()
                        // FlowRow: при крупном шрифте кнопки переносятся на вторую строку, а не ломают подписи
                        FlowRow(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            deleteButton(Modifier)
                            cancelButton(Modifier)
                            saveButton(Modifier)
                        }
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnClickOutside = false),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(dialogTitle)
                }
            },
            text = { body(false) },
            confirmButton = { saveButton(Modifier) },
            dismissButton = dismissButtons
        )
    }

    // Каналы регистратора: выбор, какие из найденных каналов добавить как отдельные камеры
    if (nvrChannels.isNotEmpty() && onSaveMultiple != null) {
        AlertDialog(
            onDismissRequest = { nvrChannels = emptyList() },
            title = { Text("Каналы регистратора") },
            text = {
                Column(modifier = Modifier.verticalScrollWithBar()) {
                    Text(
                        "Каждый выбранный канал будет добавлен как отдельная камера (${nvrChannels.size} найдено).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    nvrChannels.forEach { channel ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    nvrSelected = if (channel.number in nvrSelected) nvrSelected - channel.number else nvrSelected + channel.number
                                }
                        ) {
                            Checkbox(
                                checked = channel.number in nvrSelected,
                                onCheckedChange = {
                                    nvrSelected = if (it) nvrSelected + channel.number else nvrSelected - channel.number
                                }
                            )
                            Column {
                                Text("Канал ${channel.number}")
                                Text(
                                    channel.streams.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nvrSelected.isNotEmpty(),
                    onClick = {
                        val base = name.ifBlank { "Регистратор" }
                        val cameras = nvrChannels.filter { it.number in nvrSelected }.map { channel ->
                            val profiles = channel.streams.map { StreamProfile(name = it.name, rtspPath = it.path) }
                            IpCamera(
                                id = "",
                                name = "$base · Канал ${channel.number}",
                                ipAddress = ipAddress,
                                rtspPort = rtspPortStr.toIntOrNull() ?: 0,
                                httpPort = httpPortStr.toIntOrNull() ?: 0,
                                onvifPort = onvifPortStr.toIntOrNull() ?: 0,
                                dahuaPort = standardPort(CameraStandard.Dahua, dahuaPortStr),
                                dvripPort = standardPort(CameraStandard.XMEye, dvripPortStr),
                                hipcamPort = standardPort(CameraStandard.Hipcam, hipcamPortStr),
                                knownPorts = knownPorts.toList(),
                                rtspPath = profiles.first().rtspPath,
                                username = username,
                                password = password,
                                protocol = selectedProtocol,
                                ptzRepeatMode = ptzRepeatMode && selectedProtocol.supportsPtz,
                                streamProfiles = profiles
                            )
                        }
                        nvrChannels = emptyList()
                        onSaveMultiple(cameras)
                    }
                ) { Text("Добавить (${nvrSelected.size})") }
            },
            dismissButton = {
                TextButton(onClick = { nvrChannels = emptyList() }) { Text("Отмена") }
            }
        )
    }

    // Попап/Диалог с подробным текстом ошибки
    if (errorPopupText != null) {
        AlertDialog(
            onDismissRequest = { errorPopupText = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Информация об ошибке")
                }
            },
            text = {
                Text(text = errorPopupText ?: "")
            },
            confirmButton = {
                TextButton(onClick = { errorPopupText = null }) {
                    Text("Понятно")
                }
            }
        )
    }

    // Диалог подтверждения удаления камеры
    if (showDeleteConfirmation && cameraToEdit != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Удалить камеру?")
                }
            },
            text = {
                Text("Камера \"${cameraToEdit.name}\" будет удалена. Это действие необратимо.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete?.invoke(cameraToEdit.id)
                        showDeleteConfirmation = false
                        onDismiss()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Отмена")
                }
            }
        )
    }

}

/** Вкладка «Основные»: название, адрес, логин и пароль с проверкой авторизации */
@Composable
private fun IpInputFieldWithPing(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    status: PortPingStatus,
    onPingClick: () -> Unit,
    onErrorInfoClick: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                TooltipIconButton(
                    tooltip = "Проверить IP-адрес",
                    onClick = onPingClick,
                    enabled = status !is PortPingStatus.Loading,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (status is PortPingStatus.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = "Пинг IP",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                when (status) {
                    is PortPingStatus.Success -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "IP доступен",
                            tint = SuccessGreen,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(22.dp)
                        )
                    }
                    is PortPingStatus.Error -> {
                        TooltipIconButton(
                            tooltip = "Подробности ошибки",
                            onClick = { onErrorInfoClick(status.message) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Ошибка подключения (нажмите для подробностей)",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    )
}

@Composable
private fun ColumnScope.MainTab(
    name: String,
    onNameChange: (String) -> Unit,
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,
    ipPingStatus: PortPingStatus,
    onPingIp: () -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onPasswordEntered: () -> Unit,
    onPasswordFocusLost: () -> Unit,
    authCheckStatus: AuthCheckStatus,
    onCheckAuth: () -> Unit,
    onShowError: (String) -> Unit,
    // Узкий экран: логин и пароль друг под другом, а не в одной строке
    compact: Boolean = false
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Название камеры") },
        placeholder = { Text("Гостиная / Входная группа") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(8.dp))

    IpInputFieldWithPing(
        label = "IP-адрес или Host",
        value = ipAddress,
        placeholder = "192.168.1.100",
        onValueChange = onIpAddressChange,
        status = ipPingStatus,
        onPingClick = onPingIp,
        onErrorInfoClick = onShowError
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Два поля в строку (weight) или друг под другом (fillMaxWidth)
    @Composable
    fun CredentialFields(fieldModifier: Modifier, gap: @Composable () -> Unit) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Логин") },
            placeholder = { Text("admin") },
            singleLine = true,
            modifier = fieldModifier
        )

        gap()

        // Ввод пароля завершён (кнопка «Готово» или переход к другому полю) —
        // для найденной поиском камеры потоки ищутся автоматически
        var wasPasswordFocused by remember { mutableStateOf(false) }
        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Пароль") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TooltipIconButton(tooltip = if (passwordVisible) "Скрыть пароль" else "Показать пароль", onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onPasswordEntered() }),
            modifier = fieldModifier
                .onFocusChanged { state ->
                    if (wasPasswordFocused && !state.isFocused) onPasswordFocusLost()
                    wasPasswordFocused = state.isFocused
                }
        )
    }

    if (compact) {
        CredentialFields(Modifier.fillMaxWidth()) { Spacer(modifier = Modifier.height(8.dp)) }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            CredentialFields(Modifier.weight(1f)) { Spacer(modifier = Modifier.width(8.dp)) }
        }
    }

    Spacer(modifier = Modifier.height(if (compact) 12.dp else 8.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = onCheckAuth,
            enabled = authCheckStatus !is AuthCheckStatus.Loading,
            modifier = Modifier.weight(1f)
        ) {
            if (authCheckStatus is AuthCheckStatus.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Проверка...")
            } else {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Проверить авторизацию")
            }
        }
        StatusIndicator(
            status = authCheckStatus,
            errorDescription = "Ошибка авторизации (нажмите для подробностей)",
            onShowError = onShowError
        )
    }
}

/** Поток камеры: название, RTSP-путь, проверка и удаление */
@Composable
private fun StreamProfileRow(
    profile: StreamProfile,
    status: RtspCheckStatus,
    onNameChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onCheck: () -> Unit,
    onShowError: (String) -> Unit,
    onDelete: () -> Unit,
    // Узкий экран: название и путь друг под другом, кнопки справа от названия
    compact: Boolean = false
) {
    val pathField: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = profile.rtspPath,
            onValueChange = onPathChange,
            label = { Text("RTSP путь") },
            placeholder = { Text("/Streaming/Channels/101") },
            singleLine = true,
            modifier = fieldModifier
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (compact) 4.dp else 6.dp)
    ) {
        Column(modifier = Modifier.weight(if (compact) 1f else 2f)) {
            OutlinedTextField(
                value = profile.name,
                onValueChange = onNameChange,
                label = { Text("Название") },
                placeholder = { Text("Основной") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (compact) {
                Spacer(modifier = Modifier.height(6.dp))
                pathField(Modifier.fillMaxWidth())
            }
        }
        if (!compact) {
            Spacer(modifier = Modifier.width(6.dp))
            pathField(Modifier.weight(2f))
        }
        // Проверка потока; после проверки на кнопке — результат
        TooltipIconButton(
            tooltip = if (status is RtspCheckStatus.Error) "Подробности ошибки" else "Проверить поток",
            onClick = {
                if (status is RtspCheckStatus.Error) onShowError(status.message) else onCheck()
            },
            enabled = status !is RtspCheckStatus.Loading && profile.rtspPath.isNotBlank()
        ) {
            when (status) {
                is RtspCheckStatus.Loading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                is RtspCheckStatus.Success -> Icon(Icons.Default.CheckCircle, contentDescription = "Поток доступен", tint = SuccessGreen)
                is RtspCheckStatus.Error -> Icon(Icons.Default.Error, contentDescription = "Ошибка потока (нажмите для подробностей)", tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Default.NetworkCheck, contentDescription = "Проверить поток", tint = MaterialTheme.colorScheme.primary)
            }
        }
        TooltipIconButton(tooltip = "Удалить поток", onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Удалить поток", tint = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Выбор положения даты и времени на видео: сетка 3×3, каждая ячейка — значок экрана
 * с плашкой в нужном месте (без подписей)
 */
@Composable
private fun ClockPositionPicker(
    selected: ClockPosition,
    onSelect: (ClockPosition) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowNames = listOf("сверху", "по центру", "снизу")
    val columnNames = listOf("слева", "по центру", "справа")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0..2) {
                    val position = ClockPosition.entries[row * 3 + column]
                    val isSelected = position == selected
                    val accent = MaterialTheme.colorScheme.primary
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    val outline = MaterialTheme.colorScheme.outline
                    WithTooltip(text = "Дата и время: ${rowNames[row]}, ${columnNames[column]}") {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) accent else outline, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = "Дата и время: ${rowNames[row]}, ${columnNames[column]}" }
                            .clickable { onSelect(position) }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pad = 5.dp.toPx()
                            val barWidth = size.width * 0.34f
                            val barHeight = size.height * 0.2f
                            val x = pad + (size.width - 2 * pad - barWidth) * column / 2f
                            val y = pad + (size.height - 2 * pad - barHeight) * row / 2f
                            drawRoundRect(
                                color = if (isSelected) accent else muted,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barHeight / 2)
                            )
                        }
                    }                    }

                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/**
 * Содержимое вкладки «Архив видео» для камеры, у которой нет команды сведений о карте/форматирования
 * (нет у протокола XMEye/DVRIP — см. комментарий у DvripArchive) — только открыть сам архив записей
 * и напоминание о риске протокола (полный баннер — в самом окне архива, см. DvripArchiveDialog).
 */
@Composable
private fun DvripArchivePanel(camera: IpCamera) {
    var showArchive by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "⚠ Архив по протоколу XMEye/DVRIP (порт ${camera.effectiveDvripPort}): устаревший и слабо защищённый " +
                "протокол, у некоторых прошивок возможны зависания от сетевых запросов. Сведений о состоянии SD-карты " +
                "и форматирования для него нет — только просмотр и скачивание записей.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        FilledTonalButton(onClick = { showArchive = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть архив записей")
        }
    }
    if (showArchive) {
        DvripArchiveDialog(camera = camera, onDismiss = { showArchive = false })
    }
}

/** Зелёная галочка (успех) или красная иконка ошибки с подробностями по нажатию */
@Composable
private fun StatusIndicator(
    status: AuthCheckStatus,
    errorDescription: String,
    onShowError: (String) -> Unit
) {
    when (status) {
        is AuthCheckStatus.Success -> {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = status.message,
                tint = SuccessGreen,
                modifier = Modifier.size(28.dp)
            )
        }
        is AuthCheckStatus.Error -> {
            Spacer(modifier = Modifier.width(8.dp))
            TooltipIconButton(
                tooltip = "Подробности ошибки",
                onClick = { onShowError(status.message) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = errorDescription,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        else -> {}
    }
}

/**
 * Поле порта: выпадающий список с вариантами «Не задан», найденные у этой камеры порты
 * (сначала подтверждённые для нужного протокола) и ручной ввод номера прямо в поле.
 * Справа — проверка порта: он должен быть открыт и отвечать именно нужным протоколом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortDropdownField(
    label: String,
    value: String,
    defaultPort: Int,
    expected: PortKind,
    knownPorts: List<KnownPort>,
    hint: String,
    onValueChange: (String) -> Unit,
    status: PortPingStatus,
    onPingClick: () -> Unit,
    onErrorInfoClick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val matching = knownPorts.filter { expected in it.kinds }
    val others = knownPorts.filter { expected !in it.kinds }
    val emptyLabel = "Не задан (по умолчанию $defaultPort)"

    @Composable
    fun PortItem(known: KnownPort) {
        val selected = value == known.port.toString()
        DropdownMenuItem(
            text = { Text(known.label) },
            leadingIcon = if (selected) {
                { Icon(Icons.Default.Check, contentDescription = "Выбран", modifier = Modifier.size(18.dp)) }
            } else null,
            onClick = {
                onValueChange(known.port.toString())
                expanded = false
            }
        )
    }

    @Composable
    fun SectionHeader(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }

    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
                label = { Text(label) },
                placeholder = { Text(emptyLabel) },
                supportingText = { Text(hint) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .focusRequester(focusRequester)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(emptyLabel) },
                    leadingIcon = if (value.isBlank()) {
                        { Icon(Icons.Default.Check, contentDescription = "Выбран", modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        onValueChange("")
                        expanded = false
                    }
                )
                if (matching.isNotEmpty()) {
                    HorizontalDivider()
                    SectionHeader("Отвечают как ${expected.label}")
                    matching.forEach { PortItem(it) }
                }
                if (others.isNotEmpty()) {
                    HorizontalDivider()
                    SectionHeader("Другие открытые порты")
                    others.forEach { PortItem(it) }
                }
                if (knownPorts.isEmpty()) {
                    HorizontalDivider()
                    SectionHeader("Открытых портов пока не найдено — нажмите «Определить порты автоматически»")
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Ввести номер вручную") },
                    onClick = {
                        expanded = false
                        focusRequester.requestFocus()
                    }
                )
            }
        }

        // Проверка порта и её результат справа от поля
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        ) {
            TooltipIconButton(
                tooltip = "Проверить $label",
                onClick = onPingClick,
                enabled = status !is PortPingStatus.Loading,
                modifier = Modifier.size(36.dp)
            ) {
                if (status is PortPingStatus.Loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = "Проверить $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            when (status) {
                is PortPingStatus.Success -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Порт доступен и отвечает как ${expected.label}",
                    tint = SuccessGreen,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(22.dp)
                )
                is PortPingStatus.Error -> TooltipIconButton(
                    tooltip = "Подробности ошибки",
                    onClick = { onErrorInfoClick(status.message) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Ошибка порта (нажмите для подробностей)",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
                else -> {}
            }
        }
    }
}

/** Стандарт камеры: от него зависят порт (у каждого свой) и функции — архив, события, настройки изображения */
private enum class CameraStandard(val title: String) {
    None("- не определено / не поддерживается -"),
    Dahua("Dahua"),
    XMEye("XMEye/DVRIP"),
    Hipcam("Hipcam")
}

/** Стандарт уже настроенной камеры: по признакам архива, затем по сохранённым портам; новая камера — не определён */
private fun initialCameraStandard(camera: IpCamera?): CameraStandard = when {
    camera == null -> CameraStandard.None
    camera.hasDahuaApi || camera.dahuaPort > 0 -> CameraStandard.Dahua
    camera.dvripArchive || camera.dvripPort > 0 -> CameraStandard.XMEye
    camera.sdHttpArchive || camera.hipcamPort > 0 -> CameraStandard.Hipcam
    else -> CameraStandard.None
}

/** Выбор стандарта камеры: первым пунктом «не определено», затем стандарты; порт выбранного показывается ниже */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraStandardDropdown(selected: CameraStandard, onSelect: (CameraStandard) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.title,
            onValueChange = {},
            readOnly = true,
            label = { Text("Стандарт камеры") },
            supportingText = {
                Text(
                    "Определяется автоматически по открытым портам и формату архива; можно выбрать вручную. " +
                        "От стандарта зависит, какой порт и какие функции (архив, события) используются."
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CameraStandard.entries.forEach { standard ->
                DropdownMenuItem(
                    text = { Text(standard.title) },
                    leadingIcon = if (standard == selected) {
                        { Icon(Icons.Default.Check, contentDescription = "Выбран", modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        onSelect(standard)
                        expanded = false
                    }
                )
            }
        }
    }
}
