package com.example.denmediaipcams.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.denmediaipcams.EventNotifier
import com.example.denmediaipcams.data.CameraRepository
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.model.FocusDirection
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.Preset
import com.example.denmediaipcams.model.PtzCommand
import com.example.denmediaipcams.model.VideoRotation
import com.example.denmediaipcams.network.CameraEventState
import com.example.denmediaipcams.network.CameraRotation
import com.example.denmediaipcams.network.CameraScanner
import com.example.denmediaipcams.network.CameraTimeSync
import com.example.denmediaipcams.network.DahuaApi
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.DahuaEvent
import com.example.denmediaipcams.network.DiscoveredCamera
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.SecurityNoticeNotifier
import com.example.denmediaipcams.network.NetworkTester
import com.example.denmediaipcams.network.PortDetector
import com.example.denmediaipcams.network.PtzController
import com.example.denmediaipcams.network.RecordingIndicator
import com.example.denmediaipcams.network.RecordingIndicatorState
import com.example.denmediaipcams.network.RecordingStatusTracker
import com.example.denmediaipcams.player.StreamSessionManager
import com.example.denmediaipcams.player.ThumbnailStore
import com.example.denmediaipcams.ui.components.isNetworkMetered
import com.example.denmediaipcams.widget.CameraFrameGrabber
import com.example.denmediaipcams.widget.WidgetPrefs
import com.example.denmediaipcams.widget.WidgetRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val EVENT_RECONNECT_DELAY_MS = 10_000L
private const val EVENT_BADGE_TIMEOUT_MS = 60_000L
private const val ONLINE_CHECK_INTERVAL_MS = 15_000L
private const val RECORDING_STATUS_INTERVAL_MS = 10_000L
// Камера, которой не было в сети дольше этого, при возвращении получает время планшета заново
private const val TIME_SYNC_GAP_MS = 10 * 60 * 1_000L
// Как часто записывать «камера была в сети»: чаще незачем, а проверка доступности идёт каждые 15 с
private const val TIME_SYNC_SEEN_WRITE_MS = 60_000L
// Даже уже синхронизированную камеру перепроверяем не реже этого: часть камер (подтверждено на NVT
// живьём) сама сбрасывает себе часовой пояс за считаные
// минуты без видимой причины — разового «поставили и забыли» для них недостаточно
private const val TIME_SYNC_RESYNC_MS = 5 * 60 * 1_000L
// Неудачная попытка синхронизации времени повторяется не сразу, а с растущей паузой (1, 2, 4, 8, 16, 30, 30… мин),
// чтобы не долбить камеру, которая стабильно отказывает (неверный пароль, нет прав), но и не "залипать" навсегда
private const val TIME_SYNC_RETRY_BASE_MS = 60_000L
private const val TIME_SYNC_RETRY_MAX_MS = 30 * 60_000L
private const val TIME_SYNC_RETRY_SHIFT_CAP = 5

/** Неудачная попытка синхронизации времени камеры: сколько раз подряд не удалось и когда повторить следующую */
private data class TimeSyncFailure(val count: Int, val retryAtMs: Long)
// Обновление превью в списке камер: по безлимитной сети чаще, по лимитной — реже
private const val THUMBNAIL_REFRESH_UNMETERED_MS = 60 * 1_000L
private const val THUMBNAIL_REFRESH_METERED_MS = 10 * 60 * 1_000L

// Цифровой зум: шаг обновления, скорость при полном отклонении (натуральный логарифм кратности в секунду) и предел
private const val DIGITAL_ZOOM_TICK_MS = 33L
private const val DIGITAL_ZOOM_RATE = 1.0f
private const val MAX_DIGITAL_ZOOM = 4f

// Сколько ждать, пока камера доедет до сохранённого положения, перед снимком превью
private const val PRESET_ARRIVAL_WAIT_MS = 5_000L

sealed class NetworkScanState {
    object Idle : NetworkScanState()
    data class Scanning(val progress: String) : NetworkScanState()
    data class Done(val cameras: List<DiscoveredCamera>) : NetworkScanState()
    data class Failed(val message: String) : NetworkScanState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Зависимости создаются внутри: стандартная фабрика AndroidViewModelFactory
    // умеет вызывать только конструктор с единственным параметром Application
    private val repository = CameraRepository(application.applicationContext)
    private val ptzController = PtzController()

    /** Соединения с камерами живут здесь, а не в экранах — см. StreamSessionManager */
    val streamSessions = StreamSessionManager(application)

    val cameras: StateFlow<List<IpCamera>> = repository.cameras

    val selectedCameraId: StateFlow<String?> = repository.selectedCameraId

    val selectedCamera: StateFlow<IpCamera?> = combine(cameras, selectedCameraId) { cameraList, id ->
        cameraList.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isMultiGrid = MutableStateFlow(false)
    val isMultiGrid: StateFlow<Boolean> = _isMultiGrid.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Время последнего PTZ-действия (SystemClock.elapsedRealtime) — для полоски задержки на плеере */
    private val _lastPtzActionAtMs = MutableStateFlow(0L)
    val lastPtzActionAtMs: StateFlow<Long> = _lastPtzActionAtMs.asStateFlow()

    /** Доступность камер в сети: id камеры → отвечает ли её RTSP-порт */
    private val _cameraOnline = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val cameraOnline: StateFlow<Map<String, Boolean>> = _cameraOnline.asStateFlow()

    /** Задержка ответа RTSP-порта, мс: id камеры → сглаженное значение; нет записи — камера не отвечает */
    private val _cameraLatency = MutableStateFlow<Map<String, Long>>(emptyMap())
    val cameraLatency: StateFlow<Map<String, Long>> = _cameraLatency.asStateFlow()

    /** Текущий режим день/ночь камер: id камеры → DayNightMode */
    private val _dayNightMode = MutableStateFlow<Map<String, DayNightMode>>(emptyMap())
    val dayNightMode: StateFlow<Map<String, DayNightMode>> = _dayNightMode.asStateFlow()

    /** Поддержка переключения режима день/ночь: id камеры → поддерживается ли */
    private val _dayNightSupported = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val dayNightSupported: StateFlow<Map<String, Boolean>> = _dayNightSupported.asStateFlow()

    /** Поддержка зума и фокуса, если камера её сообщила: id камеры → возможности; нет записи — считаем, что есть */
    private val _lensCaps = MutableStateFlow<Map<String, DahuaApi.LensCaps>>(emptyMap())
    val lensCaps: StateFlow<Map<String, DahuaApi.LensCaps>> = _lensCaps.asStateFlow()

    /** Поворот и зум, которые камера сообщила сама: id камеры → возможности; нет записи — считаем, что есть */
    private val _ptzCaps = MutableStateFlow<Map<String, PtzController.PtzCaps>>(emptyMap())
    val ptzCaps: StateFlow<Map<String, PtzController.PtzCaps>> = _ptzCaps.asStateFlow()

    // Подписки на события камер Dahua: id камеры → (настройки подключения, задача).
    // Объявлено до init: подписка стартует сразу при создании модели
    private val eventJobs = mutableMapOf<String, Pair<String, Job>>()

    // Проверка «это Dahua» для сохранённых камер: один запрос на каждое сочетание адреса, порта и учётных данных.
    // Повторно не спрашиваем — неверный пароль на камере считается неудачной попыткой входа
    private val dahuaProbePrefs = application.getSharedPreferences("denmedia_dahua_probe", Context.MODE_PRIVATE)
    private val dahuaProbesInFlight = mutableSetOf<String>()

    // Поворот видео камеры уже пробовали применить: ключ — камера, её подключение и выбранный поворот.
    // Повторно в этом запуске не спрашиваем, пока ничего из этого не изменилось. Объявлено до init по той же причине
    private val rotationAttempts = mutableSetOf<String>()
    private val rotationLock = Mutex()

    // Кэш настроек записи камер для точки записи на плеере. Объявлено до init по той же причине
    private val recordingTracker = RecordingStatusTracker()

    // Подключение и способы управления камеры, для которых режим день/ночь уже проверен: id камеры → отпечаток.
    // Объявлено до init по той же причине: наблюдатель стартует из init и сразу обращается к словарю
    private val dayNightChecked = mutableMapOf<String, String>()

    // То же для возможностей PTZ: id камеры → отпечаток подключения, с которым они уже запрошены
    private val ptzCapsChecked = mutableMapOf<String, String>()

    // Установка времени камер: когда камеру в последний раз видели в сети (хранится на диске, чтобы перерыв
    // считался и между запусками), камеры, которым время ещё нужно поставить, неудачные попытки (счётчик
    // и время следующего повтора — см. TIME_SYNC_RETRY_*) и когда камеру в последний раз успешно синхронизировали
    // (чтобы перепроверять её не реже TIME_SYNC_RESYNC_MS, даже если она всё это время была на связи — часть камер
    // сбрасывает себе часовой пояс сама). Правка настроек камеры и новый перерыв в связи сбрасывают счётчик
    // неудач и дают немедленный повтор. Объявлено до init по той же причине
    private val timeSyncPrefs = application.getSharedPreferences("denmedia_time_sync", Context.MODE_PRIVATE)
    private val timeSyncPending = mutableSetOf<String>()
    private val timeSyncFailures = mutableMapOf<String, TimeSyncFailure>()
    private val timeSyncLastSyncedAt = mutableMapOf<String, Long>()
    private val timeSyncInFlight = mutableSetOf<String>()

    init {
        startSnapshotThumbnails()
        startOnlineMonitoring()
        startRecordingStatusMonitoring()
        observeDayNightMode()
        observeCameraEvents()
        observeDahuaDetection()
        observeSdArchiveDetection()
        observeHikvisionArchiveDetection()
        observeDvripArchiveDetection()
        observeVideoRotation()
    }

    /**
     * Поворот видео выполняет сама камера, если умеет (Dahua, Hipcam, XMEye — см. [CameraRotation]); плеер
     * доворачивает программно лишь остаток. Камеры без поворота в приложении (и без прежнего поворота в камере)
     * не трогаем. Работает и для камер, у которых поворот был настроен ещё до этой возможности.
     */
    private fun observeVideoRotation() {
        viewModelScope.launch {
            cameras.collect { list ->
                list.filter {
                    (it.videoRotation != VideoRotation.NONE || it.hardwareRotation != VideoRotation.NONE) && CameraRotation.canTry(it)
                }.forEach { camera ->
                    val key = listOf(
                        camera.id, camera.ipAddress, camera.effectiveHttpPort, camera.effectiveHipcamPort, camera.effectiveDvripPort,
                        camera.username, camera.password.hashCode(), camera.videoRotation, camera.dahuaApi, camera.sdHttpArchive, camera.dvripArchive
                    ).joinToString("|")
                    if (!rotationAttempts.add(key)) return@forEach
                    viewModelScope.launch { applyVideoRotation(camera.id) }
                }
            }
        }
    }

    private suspend fun applyVideoRotation(cameraId: String) = rotationLock.withLock {
        // Берём камеру заново: пока ждали очередь, её могли изменить
        val camera = cameras.value.firstOrNull { it.id == cameraId } ?: return@withLock
        val outcome = try {
            CameraRotation.apply(camera)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MainViewModel", "Rotation of ${camera.name}: ${e.message}")
            null
        } ?: return@withLock
        if (outcome.hardwareRotation == camera.hardwareRotation && outcome.base == camera.rotationBase) return@withLock
        // Результат записываем в самую свежую версию камеры, чтобы не затереть правки пользователя
        cameras.value.firstOrNull { it.id == cameraId }?.let {
            repository.updateCamera(it.copy(hardwareRotation = outcome.hardwareRotation, rotationBase = outcome.base))
        }
        if (outcome.hardwareRotation != camera.hardwareRotation) {
            _statusMessage.value = if (outcome.hardwareRotation == VideoRotation.NONE) {
                "Камера '${camera.name}': поворот выполняет приложение"
            } else {
                "Камера '${camera.name}': поворот ${outcome.hardwareRotation.displayName} выполнила сама камера"
            }
        }
    }

    /** Камеры Hipcam отдают SD-карту каталогом /sd/: если он есть, в плеере появляется «Архив записей» */
    private fun observeSdArchiveDetection() {
        viewModelScope.launch {
            cameras.collect { list ->
                list.filter { !it.sdHttpArchive && !it.hikvisionArchive && !it.hasDahuaApi && it.username.isNotBlank() && !it.ipAddress.contains("://") }
                    .forEach { camera ->
                        val key = "sd|${camera.id}|${camera.ipAddress}|${camera.effectiveHipcamPort}|${camera.username}|${camera.password.hashCode()}"
                        if (dahuaProbePrefs.getBoolean(key, false) || !dahuaProbesInFlight.add(key)) return@forEach
                        viewModelScope.launch {
                            val supported = HipcamArchive.isSupported(camera)
                            // Камера не ответила — проверка не считается выполненной и повторится позже
                            if (supported != null) dahuaProbePrefs.edit().putBoolean(key, true).apply()
                            if (supported == true) {
                                cameras.value.firstOrNull { it.id == camera.id }?.let { repository.updateCamera(it.copy(sdHttpArchive = true)) }
                            }
                            dahuaProbesInFlight.remove(key)
                        }
                    }
            }
        }
    }

    /** Камеры Hikvision отвечают на ISAPI (/ISAPI/System/deviceInfo): если ответили, в плеере появляется «Архив записей» */
    private fun observeHikvisionArchiveDetection() {
        viewModelScope.launch {
            cameras.collect { list ->
                list.filter {
                    !it.hikvisionArchive && !it.hasDahuaApi && !it.sdHttpArchive && it.username.isNotBlank() && !it.ipAddress.contains("://")
                }.forEach { camera ->
                    val key = "hik|${camera.id}|${camera.ipAddress}|${camera.effectiveHttpPort}|${camera.username}|${camera.password.hashCode()}"
                    if (dahuaProbePrefs.getBoolean(key, false) || !dahuaProbesInFlight.add(key)) return@forEach
                    viewModelScope.launch {
                        val supported = HikvisionArchive.isSupported(camera)
                        // Камера не ответила — проверка не считается выполненной и повторится позже
                        if (supported != null) dahuaProbePrefs.edit().putBoolean(key, true).apply()
                        if (supported == true) {
                            cameras.value.firstOrNull { it.id == camera.id }?.let { repository.updateCamera(it.copy(hikvisionArchive = true)) }
                        }
                        dahuaProbesInFlight.remove(key)
                    }
                }
            }
        }
    }

    /**
     * Архив по протоколу XMEye/DVRIP (порт 34567): проверяется, только если у камеры ещё нет более
     * безопасного способа архива (Dahua, Hikvision, Hipcam /sd/) — протокол слабо защищён и у части прошивок
     * известны зависания от сетевых запросов, поэтому им пользуемся, только когда больше нечем (см.
     * комментарий PortDetector.XMEYE_SDK_PORTS). По явному решению пользователя это всё равно делается
     * автоматически, а не только по галочке в настройках камеры — она остаётся как ручной способ
     * включить архив до того, как заработает автоопределение (например, сразу после ввода пароля).
     * Один успешный вход — повод показать (один раз за всё время) уведомление о риске протокола.
     */
    private fun observeDvripArchiveDetection() {
        viewModelScope.launch {
            cameras.collect { list ->
                list.filter {
                    !it.dvripArchive && !it.hasDahuaApi && !it.sdHttpArchive && !it.hikvisionArchive &&
                        it.username.isNotBlank() && !it.ipAddress.contains("://")
                }.forEach { camera ->
                    val key = "dvrip|${camera.id}|${camera.ipAddress}|${camera.effectiveDvripPort}|${camera.username}|${camera.password.hashCode()}"
                    if (dahuaProbePrefs.getBoolean(key, false) || !dahuaProbesInFlight.add(key)) return@forEach
                    viewModelScope.launch {
                        val supported = DvripArchive.isSupported(camera)
                        // Камера не ответила — проверка не считается выполненной и повторится позже
                        if (supported != null) dahuaProbePrefs.edit().putBoolean(key, true).apply()
                        if (supported == true) {
                            cameras.value.firstOrNull { it.id == camera.id }?.let { repository.updateCamera(it.copy(dvripArchive = true)) }
                            SecurityNoticeNotifier.noticeFirstSuccess(getApplication<Application>())
                        }
                        dahuaProbesInFlight.remove(key)
                    }
                }
            }
        }
    }

    private fun observeDahuaDetection() {
        viewModelScope.launch {
            cameras.collect { list ->
                list.filter { !it.dahuaApi && it.protocol != CameraProtocol.DAHUA && it.username.isNotBlank() && !it.ipAddress.contains("://") }
                    .forEach { camera ->
                        val key = "${camera.id}|${camera.ipAddress}|${camera.effectiveHttpPort}|${camera.username}|${camera.password.hashCode()}"
                        if (dahuaProbePrefs.getBoolean(key, false) || !dahuaProbesInFlight.add(key)) return@forEach
                        viewModelScope.launch {
                            val probe = DahuaApi.probe(camera.ipAddress, camera.effectiveHttpPort, camera.username, camera.password)
                            // Камера не ответила — проверка не считается выполненной и повторится позже
                            if (probe.reachable) dahuaProbePrefs.edit().putBoolean(key, true).apply()
                            if (probe.model != null) {
                                cameras.value.firstOrNull { it.id == camera.id }?.let { repository.updateCamera(it.copy(dahuaApi = true)) }
                            }
                            dahuaProbesInFlight.remove(key)
                        }
                    }
            }
        }
    }

    private fun observeCameraEvents() {
        viewModelScope.launch {
            cameras.collect { list ->
                val wanted = list.filter { it.hasDahuaApi && it.motionAlerts && !it.ipAddress.contains("://") }
                val wantedIds = wanted.map { it.id }.toSet()
                eventJobs.keys.filter { it !in wantedIds }.forEach { id ->
                    eventJobs.remove(id)?.second?.cancel()
                    CameraEventState.active.remove(id)
                }
                wanted.forEach { camera ->
                    // Подписка пересоздаётся только если изменились адрес, порт или учётные данные
                    val settings = listOf(camera.ipAddress, camera.effectiveHttpPort, camera.username, camera.password).joinToString("|")
                    val existing = eventJobs[camera.id]
                    if (existing?.first == settings) return@forEach
                    existing?.second?.cancel()
                    eventJobs[camera.id] = settings to viewModelScope.launch { runEventListener(camera) }
                }
            }
        }
    }

    private suspend fun runEventListener(camera: IpCamera) {
        while (currentCoroutineContext().isActive) {
            try {
                DahuaApi.listenEvents(camera) { event -> onCameraEvent(camera, event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "Events of ${camera.name}: ${e.message}")
            }
            CameraEventState.active.remove(camera.id)
            delay(EVENT_RECONNECT_DELAY_MS)
        }
    }

    private fun onCameraEvent(camera: IpCamera, event: DahuaEvent) {
        val title = event.title ?: return
        when {
            event.isStart -> {
                CameraEventState.active[camera.id] = title
                EventNotifier.notify(getApplication(), camera, title)
                // Если камера не пришлёт «Stop», плашка не должна висеть бесконечно
                viewModelScope.launch {
                    delay(EVENT_BADGE_TIMEOUT_MS)
                    if (CameraEventState.active[camera.id] == title) CameraEventState.active.remove(camera.id)
                }
            }
            event.isStop -> if (CameraEventState.active[camera.id] == title) CameraEventState.active.remove(camera.id)
        }
    }

    /**
     * Режим день/ночь определяется автоматически для любой камеры, независимо от выбранного протокола PTZ: пробуются все
     * способы, какими камера может его отдавать (см. PtzController.fetchDayNightMode). Проверка повторяется, когда у камеры
     * меняется подключение или определяется новый способ управления (например, фоновое определение архива Hikvision).
     */
    private fun observeDayNightMode() {
        viewModelScope.launch {
            selectedCamera.collect { camera ->
                if (camera == null) return@collect
                if (camera.protocol == CameraProtocol.DAHUA && !_lensCaps.value.containsKey(camera.id)) {
                    _lensCaps.value = _lensCaps.value + (camera.id to DahuaApi.fetchLensCaps(camera))
                }
                // Возможности PTZ — отдельной задачей: если камера не ответит, режим день/ночь не ждёт тайм-аута
                val ptzFingerprint = listOf(
                    camera.protocol, camera.ipAddress, camera.effectiveHttpPort, camera.effectiveOnvifPort, camera.username,
                    camera.password.hashCode()
                ).joinToString("|")
                if (ptzCapsChecked[camera.id] != ptzFingerprint) {
                    ptzCapsChecked[camera.id] = ptzFingerprint
                    viewModelScope.launch {
                        val caps = ptzController.fetchPtzCaps(camera)
                        // Пока шёл запрос, подключение могли поменять — устаревший ответ не записываем
                        if (ptzCapsChecked[camera.id] != ptzFingerprint) return@launch
                        _ptzCaps.value = if (caps != null) _ptzCaps.value + (camera.id to caps) else _ptzCaps.value - camera.id
                    }
                }
                val fingerprint = listOf(
                    camera.protocol, camera.ipAddress, camera.effectiveHttpPort, camera.effectiveOnvifPort, camera.username,
                    camera.password.hashCode(), camera.dahuaApi, camera.sdHttpArchive, camera.hikvisionArchive, camera.dvripArchive
                ).joinToString("|")
                if (dayNightChecked[camera.id] == fingerprint) return@collect
                dayNightChecked[camera.id] = fingerprint
                val mode = ptzController.fetchDayNightMode(camera)
                if (mode != null) {
                    _dayNightSupported.value = _dayNightSupported.value + (camera.id to true)
                    _dayNightMode.value = _dayNightMode.value + (camera.id to mode)
                } else {
                    _dayNightSupported.value = _dayNightSupported.value + (camera.id to false)
                    _dayNightMode.value = _dayNightMode.value - camera.id
                }
            }
        }
    }

    fun selectCamera(id: String) {
        repository.selectCamera(id)
    }

    fun toggleMultiGrid() {
        _isMultiGrid.value = !_isMultiGrid.value
    }

    /** Выбрать камеру и показать её одну (выйти из сетки 2×2) */
    fun showSingleCamera(id: String) {
        repository.selectCamera(id)
        _isMultiGrid.value = false
    }

    fun addCamera(camera: IpCamera) {
        val created = repository.addCamera(camera)
        repository.selectCamera(created.id)
        _statusMessage.value = "Камера '${created.name}' добавлена"
        checkOnlineNow()
        WidgetRenderer.requestUpdate(getApplication())
    }

    /**
     * Быстрое добавление камер, найденных в сети: у каждой проверяются открытые порты и протокол
     * на них, подобранные порты и список открытых портов сохраняются в самой камере.
     */
    fun addDiscoveredCameras(found: List<DiscoveredCamera>) {
        viewModelScope.launch {
            found.map { camera ->
                async {
                    val ports = PortDetector.detect(
                        host = camera.host,
                        extraPorts = listOfNotNull(camera.rtspPort, camera.onvifPort)
                    )
                    val selection = PortDetector.select(ports)
                    IpCamera(
                        id = "",
                        name = camera.name ?: "Камера ${camera.host}",
                        ipAddress = camera.host,
                        // Порт, который не подтвердился проверкой, остаётся пустым (0), а не подставляется
                        rtspPort = selection.rtsp ?: 0,
                        httpPort = selection.http ?: 0,
                        onvifPort = selection.onvif ?: 0,
                        dahuaPort = selection.dahua ?: 0,
                        dvripPort = selection.dvrip ?: 0,
                        knownPorts = ports,
                        username = "admin",
                        password = ""
                    )
                }
            }.awaitAll().forEach { addCamera(it) }
        }
    }

    fun updateCamera(camera: IpCamera) {
        android.util.Log.d("MainViewModel", "Updating camera: ${camera.name}, VideoRotation: ${camera.videoRotation}")
        val wasAutoSync = cameras.value.firstOrNull { it.id == camera.id }?.autoSyncTime
        // Камеру правили (адрес, пароль...) — прежняя неудача установки времени могла быть из-за этого;
        // только что включённая автоустановка сработает сразу, а не после следующего перерыва в связи
        timeSyncFailures.remove(camera.id)
        if (camera.autoSyncTime && wasAutoSync == false) timeSyncPending.add(camera.id)
        repository.updateCamera(camera)
        _statusMessage.value = "Камера '${camera.name}' обновлена"
        checkOnlineNow()
        WidgetRenderer.requestUpdate(getApplication())
    }

    /** Перестановка камер в списке (перетаскиванием) */
    fun moveCamera(from: Int, to: Int) {
        repository.moveCamera(from, to)
        WidgetRenderer.requestUpdate(getApplication())
    }

    fun deleteCamera(id: String) {
        repository.deleteCamera(id)
        timeSyncPrefs.edit().remove("seen_$id").apply()
        ThumbnailStore.deleteCamera(getApplication(), id)
        _statusMessage.value = "Камера удалена"
        WidgetRenderer.requestUpdate(getApplication())
    }

    private fun markPtzAction() {
        _lastPtzActionAtMs.value = SystemClock.elapsedRealtime()
    }

    fun sendMove(pan: Float, tilt: Float) {
        val camera = selectedCamera.value ?: return
        markPtzAction()
        viewModelScope.launch {
            // Джойстик и жесты работают в осях экрана; камере нужны её собственные оси
            val (cameraPan, cameraTilt) = camera.cameraMove(pan, tilt)
            val cmd = PtzCommand.Move(cameraPan, cameraTilt, camera.ptzSpeed)
            val success = ptzController.executeCommand(camera, cmd)
            if (!success) {
                _statusMessage.value = "Ошибка отправки команды поворота"
            }
        }
    }

    // ---------------- Цифровой зум (камеры без оптического зума) ----------------

    private val _digitalZoom = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Увеличение кадра в приложении: id камеры → кратность (1 — без увеличения) */
    val digitalZoom: StateFlow<Map<String, Float>> = _digitalZoom.asStateFlow()

    private var digitalZoomJob: Job? = null
    @Volatile private var digitalZoomVelocity = 0f

    /** Пока зум удерживается, кратность плавно растёт или падает со скоростью, заданной отклонением (−1…1) */
    private fun startDigitalZoom(cameraId: String, level: Float) {
        digitalZoomVelocity = level
        if (digitalZoomJob?.isActive == true) return
        digitalZoomJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(DIGITAL_ZOOM_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val seconds = (now - last) / 1000f
                last = now
                val current = _digitalZoom.value[cameraId] ?: 1f
                val next = (current * kotlin.math.exp(digitalZoomVelocity * DIGITAL_ZOOM_RATE * seconds))
                    .coerceIn(1f, MAX_DIGITAL_ZOOM)
                _digitalZoom.value = _digitalZoom.value + (cameraId to next)
            }
        }
    }

    private fun stopDigitalZoom() {
        digitalZoomVelocity = 0f
        digitalZoomJob?.cancel()
        digitalZoomJob = null
    }

    fun sendZoom(zoomLevel: Float) {
        val camera = selectedCamera.value ?: return
        // Камеры в режиме повтора не имеют оптического зума — увеличиваем кадр в приложении
        if (camera.ptzRepeatMode) {
            startDigitalZoom(camera.id, zoomLevel)
            return
        }
        markPtzAction()
        viewModelScope.launch {
            val cmd = PtzCommand.Zoom(zoomLevel)
            val success = ptzController.executeCommand(camera, cmd)
            if (!success) {
                _statusMessage.value = "Ошибка изменения оптического зума"
            }
        }
    }

    fun sendFocus(direction: FocusDirection) {
        val camera = selectedCamera.value ?: return
        markPtzAction()
        viewModelScope.launch {
            val cmd = PtzCommand.Focus(direction)
            val success = ptzController.executeCommand(camera, cmd)
            if (!success) {
                _statusMessage.value = "Ошибка фокусировки"
            }
        }
    }

    /** Непрерывная фокусировка с качельки: < 0 — ближе, > 0 — дальше */
    fun sendFocusMove(speed: Float) {
        val camera = selectedCamera.value ?: return
        markPtzAction()
        viewModelScope.launch {
            val success = ptzController.executeCommand(camera, PtzCommand.FocusMove(speed))
            if (!success) {
                _statusMessage.value = "Ошибка фокусировки"
            }
        }
    }

    fun sendFocusStop() {
        val camera = selectedCamera.value ?: return
        markPtzAction()
        viewModelScope.launch {
            ptzController.executeCommand(camera, PtzCommand.FocusStop)
        }
    }

    fun sendDayNightMode(mode: DayNightMode) {
        val camera = selectedCamera.value ?: return
        markPtzAction()
        viewModelScope.launch {
            val success = ptzController.executeCommand(camera, PtzCommand.SetDayNightMode(mode))
            if (success) {
                _dayNightMode.value = _dayNightMode.value + (camera.id to mode)
                _statusMessage.value = "Режим видео: ${mode.displayName}"
            } else {
                _statusMessage.value = "Ошибка переключения режима день/ночь"
            }
        }
    }

    fun sendStop() {
        val camera = selectedCamera.value ?: return
        stopDigitalZoom()
        markPtzAction()
        viewModelScope.launch {
            ptzController.executeCommand(camera, PtzCommand.Stop)
        }
    }

    fun goToPreset(presetId: Int) {
        val camera = selectedCamera.value ?: return
        // Идентификатор положения, который назначила сама камера при сохранении (ONVIF)
        val token = camera.presets.firstOrNull { it.id == presetId }?.token?.takeIf { it.isNotEmpty() } ?: presetId.toString()
        markPtzAction()
        val actionAt = _lastPtzActionAtMs.value
        viewModelScope.launch {
            val success = ptzController.executeCommand(camera, PtzCommand.GoToPreset(presetId, token))
            _statusMessage.value = if (success) "Переход к пресету $presetId" else "Ошибка перехода к пресету $presetId"
            // Обновляем превью после поворота камеры в положение
            if (success) {
                capturePresetThumbnailAfterArrival(camera, presetId, actionAt)
            }
        }
    }

    /**
     * Ждёт, пока камера доедет до положения (и кадр дойдёт по сети), и сохраняет кадр в превью положения.
     * Если за это время камерой управляли, кадр уже не соответствует положению — превью не снимается.
     */
    private suspend fun capturePresetThumbnailAfterArrival(camera: IpCamera, presetId: Int, actionAt: Long): Boolean {
        val session = streamSessions.sessionFor(camera.id) ?: return false
        delay(PRESET_ARRIVAL_WAIT_MS + session.latencyEstimateMs())
        if (_lastPtzActionAtMs.value != actionAt) return false
        val frame = session.captureFrame(ThumbnailStore.THUMBNAIL_WIDTH) ?: return false
        return try {
            ThumbnailStore.save(getApplication(), ThumbnailStore.presetKey(camera.id, presetId), frame)
        } finally {
            frame.recycle()
        }
    }

    // ---------------- Обход положений для превью ----------------

    private val _isPresetTourRunning = MutableStateFlow(false)
    val isPresetTourRunning: StateFlow<Boolean> = _isPresetTourRunning.asStateFlow()
    /** Положение, превью которого снимается прямо сейчас */
    private val _presetTourCurrentId = MutableStateFlow<Int?>(null)
    val presetTourCurrentId: StateFlow<Int?> = _presetTourCurrentId.asStateFlow()
    private var presetTourJob: Job? = null

    /** Камера по очереди переходит ко всем сохранённым положениям, у каждого снимается превью */
    fun capturePresetThumbnails() {
        val camera = selectedCamera.value ?: return
        val saved = camera.presets.filter { it.isSaved }
        if (saved.isEmpty()) {
            _statusMessage.value = "У камеры нет сохранённых положений"
            return
        }
        if (streamSessions.sessionFor(camera.id)?.isPlaying != true) {
            _statusMessage.value = "Дождитесь, пока появится видео камеры, — превью снимаются с него"
            return
        }
        presetTourJob?.cancel()
        presetTourJob = viewModelScope.launch {
            _isPresetTourRunning.value = true
            var captured = 0
            try {
                for ((index, preset) in saved.withIndex()) {
                    _presetTourCurrentId.value = preset.id
                    _statusMessage.value = "Превью положения ${preset.id} (${index + 1} из ${saved.size})..."
                    markPtzAction()
                    val actionAt = _lastPtzActionAtMs.value
                    val presetToken = preset.token.takeIf { it.isNotEmpty() } ?: preset.id.toString()
                    if (!ptzController.executeCommand(camera, PtzCommand.GoToPreset(preset.id, presetToken))) continue
                    if (capturePresetThumbnailAfterArrival(camera, preset.id, actionAt)) {
                        captured++
                    } else if (_lastPtzActionAtMs.value != actionAt) {
                        _statusMessage.value = "Обход положений прерван: камерой управляли вручную"
                        return@launch
                    }
                }
                _statusMessage.value = "Превью обновлены: $captured из ${saved.size}"
            } finally {
                _isPresetTourRunning.value = false
                _presetTourCurrentId.value = null
            }
        }
    }

    fun cancelPresetTour() {
        presetTourJob?.cancel()
        presetTourJob = null
        _isPresetTourRunning.value = false
        _presetTourCurrentId.value = null
    }

    fun savePreset(presetId: Int) {
        val camera = selectedCamera.value ?: return
        val preset = camera.presets.firstOrNull { it.id == presetId } ?: Preset(presetId, "Пресет $presetId")
        viewModelScope.launch {
            // Кадр снимаем сразу — он соответствует положению, которое сохраняется
            val frame = streamSessions.sessionFor(camera.id)?.captureFrame(ThumbnailStore.THUMBNAIL_WIDTH)
            val token = ptzController.savePreset(camera, preset)
            if (token != null) {
                updatePresets(camera.id) { if (it.id == presetId) it.copy(isSaved = true, token = token) else it }
                frame?.let { ThumbnailStore.save(getApplication(), ThumbnailStore.presetKey(camera.id, presetId), it) }
            }
            frame?.recycle()
            _statusMessage.value = if (token != null) "Текущее положение сохранено: ${preset.id}" else "Ошибка сохранения положения"
        }
    }

    /** Сохраняет текущее положение в первую свободную ячейку */
    fun addPreset() {
        val camera = selectedCamera.value ?: return
        val free = camera.presets.firstOrNull { !it.isSaved }
        if (free == null) {
            _statusMessage.value = "Все ${camera.presets.size} ячеек заняты — пересохраните одну из них долгим нажатием"
            return
        }
        savePreset(free.id)
    }

    fun renamePreset(presetId: Int, name: String) {
        val camera = selectedCamera.value ?: return
        updatePresets(camera.id) { if (it.id == presetId) it.copy(name = name) else it }
    }

    /** Убирает отметку о сохранённом положении (в самой камере положение остаётся) */
    fun clearPreset(presetId: Int) {
        val camera = selectedCamera.value ?: return
        updatePresets(camera.id) { if (it.id == presetId) it.copy(isSaved = false, token = presetId.toString()) else it }
        ThumbnailStore.delete(getApplication(), ThumbnailStore.presetKey(camera.id, presetId))
    }

    /**
     * Запрашивает у камеры список сохранённых в ней положений и отмечает их в пресетах
     * (в том числе положения, сохранённые через родное приложение камеры).
     */
    fun syncSavedPresets() {
        val camera = selectedCamera.value ?: return
        if (!camera.protocol.supportsPtz) return
        viewModelScope.launch {
            val saved = ptzController.fetchSavedPresets(camera) ?: return@launch
            // Названия из камеры подставляются только вместо стандартных «Пресет N»: названные пользователем не затираются
            val cameraNames = ptzController.fetchPresetNames(camera).orEmpty()
            updatePresets(camera.id) { preset ->
                val token = saved[preset.id]
                if (token != null) {
                    val cameraName = cameraNames[token.toIntOrNull() ?: preset.id]
                    val name = if (cameraName != null && preset.name == "Пресет ${preset.id}") cameraName else preset.name
                    preset.copy(isSaved = true, token = token, name = name)
                } else preset
            }
        }
    }

    private fun updatePresets(cameraId: String, transform: (Preset) -> Preset) {
        // Берём актуальную версию камеры: за время запроса её могли изменить
        val current = cameras.value.firstOrNull { it.id == cameraId } ?: return
        val updatedPresets = current.presets.map(transform)
        if (updatedPresets != current.presets) {
            repository.updateCamera(current.copy(presets = updatedPresets))
        }
    }

    /** Переключает поток (качество/протокол) камеры во время воспроизведения */
    fun selectStreamProfile(cameraId: String, profileId: String) {
        val current = cameras.value.firstOrNull { it.id == cameraId } ?: return
        repository.updateCamera(current.copy(activeStreamProfileId = profileId))
    }

    /** Меняет стандарт PTZ камеры (панель управления); остальные настройки не затрагиваются */
    fun selectProtocol(cameraId: String, protocol: com.example.denmediaipcams.model.CameraProtocol) {
        val current = cameras.value.firstOrNull { it.id == cameraId } ?: return
        if (current.protocol == protocol) return
        repository.updateCamera(current.copy(protocol = protocol))
        _statusMessage.value = "Стандарт PTZ: ${protocol.displayName}"
    }

    /** Переподключает указанную камеру */
    fun reconnectCamera(cameraId: String) {
        val session = streamSessions.sessionFor(cameraId) ?: return
        session.reconnect()
        _statusMessage.value = "Переподключение к камере..."
    }

    fun showStatus(message: String) {
        _statusMessage.value = message
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // ---------------- Поиск камер в локальной сети ----------------

    private val _scanState = MutableStateFlow<NetworkScanState>(NetworkScanState.Idle)
    val scanState: StateFlow<NetworkScanState> = _scanState.asStateFlow()
    private var scanJob: Job? = null

    fun startNetworkScan() {
        scanJob?.cancel()
        _scanState.value = NetworkScanState.Scanning("Подготовка...")
        scanJob = viewModelScope.launch {
            _scanState.value = try {
                val found = CameraScanner.scan(getApplication()) { progress ->
                    _scanState.value = NetworkScanState.Scanning(progress)
                }
                NetworkScanState.Done(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetworkScanState.Failed(e.message ?: "Ошибка поиска камер")
            }
        }
    }

    fun dismissNetworkScan() {
        scanJob?.cancel()
        scanJob = null
        _scanState.value = NetworkScanState.Idle
    }

    // ---------------- Доступность камер ----------------

    private fun startOnlineMonitoring() {
        viewModelScope.launch {
            while (true) {
                refreshOnlineStatus()
                delay(ONLINE_CHECK_INTERVAL_MS)
            }
        }
    }

    /** Приложение на переднем плане (выставляет MainActivity): в фоне превью не обновляются */
    @Volatile
    var isAppInForeground = true

    /**
     * Периодически обновляет превью доступных камер в списке: по лимитному подключению реже, чем по безлимитному.
     * Берётся кадр идущей трансляции, иначе HTTP-снимок, иначе кадр из RTSP (только для камер без снимков
     * и не чаще, чем позволяет интервал). Камеры, которые сейчас не отвечают, пропускаются.
     */
    private fun startSnapshotThumbnails() {
        viewModelScope.launch {
            // Первая проверка доступности камер должна успеть завершиться
            delay(5_000L)
            while (true) {
                val metered = isNetworkMetered(getApplication())
                if (isAppInForeground) {
                    val online = _cameraOnline.value
                    cameras.value.filter { online[it.id] == true }.forEach { camera ->
                        try {
                            if (refreshListThumbnail(camera)) WidgetRenderer.requestUpdate(getApplication())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
                delay(if (metered) THUMBNAIL_REFRESH_METERED_MS else THUMBNAIL_REFRESH_UNMETERED_MS)
            }
        }
    }

    private suspend fun refreshListThumbnail(camera: IpCamera): Boolean {
        val session = streamSessions.sessionFor(camera.id)
        if (session != null && session.isPlaying && session.saveCameraThumbnail()) return true
        val frame = CameraFrameGrabber.grab(getApplication(), camera, ThumbnailStore.THUMBNAIL_WIDTH) ?: return false
        return try {
            ThumbnailStore.save(getApplication(), ThumbnailStore.cameraKey(camera.id), frame)
        } finally {
            frame.recycle()
        }
    }

    /**
     * Точка записи на плеере: раз в [RECORDING_STATUS_INTERVAL_MS] у каждой камеры узнаётся, пишет ли она на SD-карту.
     * Пока приложение в фоне, камеры не опрашиваются (картинки с точкой всё равно никто не видит).
     */
    private fun startRecordingStatusMonitoring() {
        viewModelScope.launch {
            // Первая проверка доступности камер должна успеть завершиться
            delay(3_000L)
            while (true) {
                if (isAppInForeground) refreshRecordingIndicators()
                delay(RECORDING_STATUS_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshRecordingIndicators() {
        val list = cameras.value
        val online = _cameraOnline.value
        val ids = list.map { it.id }.toSet()
        recordingTracker.forget(ids)
        RecordingIndicatorState.states.keys.retainAll(ids)
        list.map { camera ->
            viewModelScope.async {
                val indicator = try {
                    recordingTracker.indicator(camera, online[camera.id])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Recording status of ${camera.name}: ${e.message}")
                    RecordingIndicator.LOST
                }
                camera.id to indicator
            }
        }.awaitAll().forEach { (id, indicator) -> RecordingIndicatorState.states[id] = indicator }
    }

    private fun checkOnlineNow() {
        viewModelScope.launch { refreshOnlineStatus() }
    }

    /** Камера считается в сети, если её RTSP-порт принимает подключение */
    private suspend fun refreshOnlineStatus() {
        val results = cameras.value.map { camera ->
            viewModelScope.async {
                val uri = android.net.Uri.parse(camera.rtspUrlWithoutCredentials)
                val host = uri.host ?: camera.ipAddress
                val port = if (uri.port > 0) uri.port else camera.effectiveRtspPort
                camera.id to NetworkTester.tcpPingMs(host, port)
            }
        }.awaitAll()
        val statuses = results.associate { (id, ping) -> id to (ping != null) }
        // Задержку берём из той же проверки доступности (лишних запросов нет) и сглаживаем,
        // чтобы значок не мигал от одиночных всплесков
        val previous = _cameraLatency.value
        _cameraLatency.value = results.mapNotNull { (id, ping) ->
            ping?.let { id to (previous[id]?.let { old -> (old + it) / 2 } ?: it) }
        }.toMap()
        _cameraOnline.value = statuses
        autoSyncCameraTime(statuses)
        // Виджет-список камер использует те же статусы, что и приложение (здесь они обновляются чаще, раз в 15 с)
        if (WidgetPrefs.saveOnline(getApplication(), statuses)) {
            WidgetRenderer.requestUpdate(getApplication())
        }
    }

    /**
     * Ставит камере время и часовой пояс планшета, если включено «Обновлять время автоматически»: когда камеру
     * только что добавили, когда её не было в сети дольше [TIME_SYNC_GAP_MS] (могла перезагрузиться и потерять
     * время), и затем повторно не реже [TIME_SYNC_RESYNC_MS] — часть камер сбрасывает себе часовой пояс сама,
     * без перезагрузки и без видимой причины (подтверждено на NVT, живым видео с её OSD).
     */
    private fun autoSyncCameraTime(statuses: Map<String, Boolean>) {
        val list = cameras.value
        val ids = list.map { it.id }.toSet()
        timeSyncPending.retainAll(ids)
        timeSyncFailures.keys.retainAll(ids)
        timeSyncLastSyncedAt.keys.retainAll(ids)
        val now = System.currentTimeMillis()
        list.filter { statuses[it.id] == true }.forEach { camera ->
            val seenKey = "seen_${camera.id}"
            // Нет записи (новая камера или первый запуск после обновления) — как очень давний перерыв
            val sinceSeen = now - timeSyncPrefs.getLong(seenKey, 0L)
            // Отрицательное значение: часы планшета перевели назад, прежним отметкам верить нельзя
            if (sinceSeen > TIME_SYNC_GAP_MS || sinceSeen < 0) {
                timeSyncPending.add(camera.id)
                timeSyncFailures.remove(camera.id)
            }
            if (sinceSeen > TIME_SYNC_SEEN_WRITE_MS || sinceSeen < 0) {
                timeSyncPrefs.edit().putLong(seenKey, now).apply()
            }
            // Важное событие (добавили/вернулась в сеть после перерыва/неудача) отличаем от обычной
            // плановой перепроверки — плановую не показываем пользователю, чтобы не спамить
            val wasPending = camera.id in timeSyncPending
            val dueForResync = now - (timeSyncLastSyncedAt[camera.id] ?: 0L) > TIME_SYNC_RESYNC_MS
            if (!wasPending && !dueForResync) return@forEach
            val failure = timeSyncFailures[camera.id]
            if (failure != null && now < failure.retryAtMs) return@forEach
            if (!camera.autoSyncTime || !CameraTimeSync.isAvailable(camera)) return@forEach
            if (!timeSyncInFlight.add(camera.id)) return@forEach
            viewModelScope.launch {
                try {
                    val result = CameraTimeSync.sync(camera)
                    if (result.ok) {
                        timeSyncPending.remove(camera.id)
                        timeSyncFailures.remove(camera.id)
                        timeSyncLastSyncedAt[camera.id] = System.currentTimeMillis()
                        if (wasPending) {
                            _statusMessage.value = "Камера '${camera.name}': время и часовой пояс установлены по планшету"
                        }
                    } else {
                        recordTimeSyncFailure(camera.id)
                        Log.w("MainViewModel", "Time sync of ${camera.name}: ${result.message}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordTimeSyncFailure(camera.id)
                    Log.w("MainViewModel", "Time sync of ${camera.name}: ${e.message}")
                } finally {
                    timeSyncInFlight.remove(camera.id)
                }
            }
        }
    }

    /** Увеличивает счётчик неудачных попыток синхронизации времени и назначает время следующего повтора (бэкофф) */
    private fun recordTimeSyncFailure(id: String) {
        val count = (timeSyncFailures[id]?.count ?: 0) + 1
        val delayMs = (TIME_SYNC_RETRY_BASE_MS shl (count - 1).coerceAtMost(TIME_SYNC_RETRY_SHIFT_CAP))
            .coerceAtMost(TIME_SYNC_RETRY_MAX_MS)
        timeSyncFailures[id] = TimeSyncFailure(count, System.currentTimeMillis() + delayMs)
    }

    override fun onCleared() {
        streamSessions.releaseAll()
        super.onCleared()
    }
}
