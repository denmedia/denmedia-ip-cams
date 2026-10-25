package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.model.FocusDirection
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.PtzCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

class PtzController {

    companion object {
        private const val TAG = "PtzController"

        // Длительность импульса фокусировки: непрерывная фокусировка без остановки уводит объектив в крайнее положение
        private const val FOCUS_PULSE_MS = 300L

        // Мёртвая зона джойстика для протоколов с дискретными направлениями (Dahua)
        private const val DIRECTION_DEAD_ZONE = 0.3f

        // Пошаговый фокус: шаг при полном отклонении качельки (диапазон камеры обычно −1…1) и частота шагов.
        // При полном отклонении весь диапазон проходится примерно за 5-6 секунд
        private const val RELATIVE_FOCUS_STEP = 0.10f
        private const val FOCUS_STEP_INTERVAL_MS = 150L

        // Режим повтора: как часто повторяется команда поворота, пока курок удержан
        private const val REPEAT_MOVE_INTERVAL_MS = 300L

        // Повтор недоставленного Stop (связь с камерой пропала во время поворота)
        private const val STOP_RETRY_INTERVAL_MS = 1000L
        private const val STOP_RETRY_WINDOW_MS = 120_000L
    }

    /** ONVIF-параметры камеры, полученные из GetCapabilities/GetProfiles (кэшируются) */
    private data class OnvifPtzTarget(
        /** null — у камеры нет PTZ-сервиса (например, фиксированная камера с режимом день/ночь) */
        val ptzUrl: String?,
        val imagingUrl: String?,
        val profileToken: String,
        val videoSourceToken: String?,
        val clockOffsetMs: Long
    )

    private val onvifTargets = ConcurrentHashMap<String, OnvifPtzTarget>()

    /** Dahua останавливает движение командой stop с тем же кодом, что и у start */
    private val dahuaLastCode = ConcurrentHashMap<String, String>()

    // Команды отправляются строго по очереди: иначе Stop может обогнать последний Move/Zoom
    private val commandMutex = Mutex()

    // Последняя ещё не отправленная непрерывная команда (Move/Zoom)
    private val pendingContinuous = AtomicReference<Pair<IpCamera, PtzCommand>?>(null)

    // ---------------- Режим повтора команды (дешёвые поворотные камеры: Yoosee и аналоги) ----------------
    //
    // Такая камера выполняет каждый ContinuousMove ограниченным шагом и сама останавливается, а джойстик шлёт
    // команду один раз — приходилось отпускать и тянуть курок заново. Пока курок удержан, команду повторяем.
    // Зума и фокуса у этих камер нет.

    private val repeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repeatJob: Job? = null
    @Volatile private var repeatTarget: Pair<IpCamera, PtzCommand.Move>? = null

    private fun startRepeatedMove(camera: IpCamera, move: PtzCommand.Move) {
        repeatTarget = camera to move
        if (repeatJob?.isActive == true) return
        repeatJob = repeatScope.launch {
            while (isActive) {
                val (cam, cmd) = repeatTarget ?: break
                commandMutex.withLock { sendCommand(cam, cmd) }
                delay(REPEAT_MOVE_INTERVAL_MS)
            }
        }
    }

    private fun stopRepeatedMove() {
        repeatTarget = null
        repeatJob?.cancel()
        repeatJob = null
    }

    // ---------------- Доставка Stop после потери связи ----------------
    //
    // Непрерывное движение камера продолжает, пока не получит Stop. Если связь пропала в момент поворота,
    // Stop не доходит, и после восстановления камера доезжает до крайнего положения. Поэтому недоставленный
    // Stop повторяется в фоне, пока не пройдёт; новое движение той же камеры повтор отменяет.

    private val stopRetryJobs = ConcurrentHashMap<String, Job>()

    private fun retryStopInBackground(camera: IpCamera) {
        val job = repeatScope.launch {
            val deadline = System.currentTimeMillis() + STOP_RETRY_WINDOW_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(STOP_RETRY_INTERVAL_MS)
                // Запрос в сети нельзя прервать, поэтому отмену (новое движение) проверяем уже под блокировкой
                val delivered = commandMutex.withLock {
                    if (isActive) sendCommand(camera, PtzCommand.Stop) else true
                }
                if (delivered) break
            }
        }
        stopRetryJobs.put(camera.id, job)?.cancel()
        job.invokeOnCompletion { stopRetryJobs.remove(camera.id, job) }
    }

    private fun cancelStopRetry(camera: IpCamera) {
        stopRetryJobs.remove(camera.id)?.cancel()
    }

    suspend fun executeCommand(camera: IpCamera, command: PtzCommand): Boolean {
        if (command is PtzCommand.Move || command is PtzCommand.Zoom || command is PtzCommand.GoToPreset) {
            cancelStopRetry(camera)
        }
        if (camera.ptzRepeatMode && camera.protocol == CameraProtocol.ONVIF) {
            when (command) {
                is PtzCommand.Move -> {
                    startRepeatedMove(camera, command)
                    return true
                }
                // У камер этого типа нет зума и фокуса
                is PtzCommand.Zoom, is PtzCommand.Focus, is PtzCommand.FocusMove, is PtzCommand.FocusStop -> return false
                is PtzCommand.Stop -> stopRepeatedMove()
                else -> {}
            }
        }

        if (command is PtzCommand.Move || command is PtzCommand.Zoom || command is PtzCommand.FocusMove) {
            // Жесты шлют Move/Zoom очень часто: если камера ещё не ответила на предыдущую команду,
            // промежуточные заменяются самой свежей — она уйдёт сразу после текущей
            pendingContinuous.set(camera to command)
            var result = true
            while (pendingContinuous.get() != null) {
                if (!commandMutex.tryLock()) return true
                try {
                    while (true) {
                        val (cam, cmd) = pendingContinuous.getAndSet(null) ?: break
                        result = sendCommand(cam, cmd)
                    }
                } finally {
                    commandMutex.unlock()
                }
            }
            return result
        }

        // Stop и прочие команды отменяют ещё не отправленное движение
        pendingContinuous.set(null)
        val result = commandMutex.withLock { sendCommand(camera, command) }
        if (!result && command is PtzCommand.Stop && camera.protocol.supportsPtz) retryStopInBackground(camera)
        return result
    }

    private suspend fun sendCommand(camera: IpCamera, command: PtzCommand): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing PTZ command $command for camera ${camera.name} (${camera.protocol})")
            if (command is PtzCommand.SetDayNightMode) {
                // Режим день/ночь меняется тем способом, каким камера его отдала, независимо от выбранного протокола PTZ
                sendDayNightMode(camera, command.mode)?.let { return@withContext it }
                if (dayNightBackends[dayNightKey(camera)] == DayNightBackend.ONVIF) return@withContext sendOnvifCommand(camera, command)
            }
            when (camera.protocol) {
                CameraProtocol.ONVIF -> sendOnvifCommand(camera, command)
                CameraProtocol.HIKVISION -> sendHikvisionCommand(camera, command)
                CameraProtocol.DAHUA -> sendDahuaCommand(camera, command)
                CameraProtocol.GENERIC_HTTP -> sendGenericHttpCommand(camera, command)
                CameraProtocol.NONE -> {
                    Log.i(TAG, "Camera ${camera.name} has no PTZ — command ignored")
                    false
                }
                CameraProtocol.DEMO_SIMULATOR -> {
                    Log.i(TAG, "Demo Simulator: PTZ Command $command executed successfully.")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing PTZ command for ${camera.name}", e)
            false
        }
    }

    /**
     * Сохраняет текущее положение камеры в ячейку пресета.
     * @return идентификатор положения в камере (для ONVIF его может назначить камера) или null при ошибке
     */
    suspend fun savePreset(camera: IpCamera, preset: com.example.denmediaipcams.model.Preset): String? {
        pendingContinuous.set(null)
        return commandMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Saving preset ${preset.id} for camera ${camera.name} (${camera.protocol})")
                    if (camera.protocol == CameraProtocol.ONVIF) {
                        val target = resolveOnvifTarget(camera) ?: return@withContext null
                        onvifSetPreset(camera, target, preset.id, preset.id.toString())
                    } else {
                        if (sendCommand(camera, PtzCommand.SavePreset(preset.id))) preset.id.toString() else null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving preset for ${camera.name}", e)
                    null
                }
            }
        }
    }

    /**
     * Пресеты, сохранённые в самой камере (в том числе через её родное приложение):
     * номер ячейки → идентификатор положения в камере.
     * null — протокол не поддерживает чтение списка или камера не ответила.
     */
    suspend fun fetchSavedPresets(camera: IpCamera): Map<Int, String>? = withContext(Dispatchers.IO) {
        try {
            when (camera.protocol) {
                CameraProtocol.ONVIF -> {
                    val target = resolveOnvifTarget(camera) ?: return@withContext null
                    val response = OnvifClient.request(
                        target.ptzUrl ?: return@withContext null,
                        """<GetPresets xmlns="http://www.onvif.org/ver20/ptz/wsdl">""" +
                            "<ProfileToken>${OnvifClient.xmlEscape(target.profileToken)}</ProfileToken></GetPresets>",
                        camera.username, camera.password, target.clockOffsetMs
                    ) ?: return@withContext null
                    Log.d(TAG, "ONVIF GetPresets for ${camera.name}: ${response.take(3000)}")
                    // Номер ячейки: из имени «PresetN» (так сохраняет приложение), иначе — числовой токен
                    Regex(
                        """<(?:\w+:)?Preset\b[^>]*\btoken="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Preset>""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(response).mapNotNull { match ->
                        val token = match.groupValues[1]
                        val name = Regex("""<(?:\w+:)?Name>([^<]*)<""").find(match.groupValues[2])?.groupValues?.get(1)?.trim()
                        val id = name?.let { Regex("""^Preset(\d+)$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                            ?: token.toIntOrNull()
                        id?.let { it to token }
                    }.toMap()
                }
                CameraProtocol.HIKVISION -> {
                    val response = CameraHttp.request(
                        "http://${camera.ipAddress}:${camera.effectiveHttpPort}/ISAPI/PTZCtrl/channels/1/presets",
                        user = camera.username, pass = camera.password
                    )
                    if (!response.isSuccess) return@withContext null
                    Regex("""<PTZPreset\b.*?</PTZPreset>""", RegexOption.DOT_MATCHES_ALL).findAll(response.body)
                        .filter { !it.value.contains("<enabled>false</enabled>") }
                        .mapNotNull { Regex("""<id>(\d+)</id>""").find(it.value)?.groupValues?.get(1)?.toIntOrNull() }
                        .associateWith { it.toString() }
                }
                CameraProtocol.DAHUA -> {
                    val response = CameraHttp.request(
                        "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/ptz.cgi?action=getPresets&channel=1",
                        user = camera.username, pass = camera.password
                    )
                    if (!response.isSuccess) return@withContext null
                    Regex("""presets\[\d+]\.Index=(\d+)""").findAll(response.body)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }
                        .associateWith { it.toString() }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch presets for ${camera.name}: ${e.message}")
            null
        }
    }

    /**
     * Названия, которые дали положениям в самой камере (родное приложение, веб-интерфейс): номер ячейки → название.
     * Стандартные «PresetN» и «NNN» не возвращаются — это не названия, а номера. null — не ONVIF или камера не ответила.
     */
    suspend fun fetchPresetNames(camera: IpCamera): Map<Int, String>? = withContext(Dispatchers.IO) {
        if (camera.protocol != CameraProtocol.ONVIF) return@withContext null
        try {
            val target = resolveOnvifTarget(camera) ?: return@withContext null
            val response = OnvifClient.request(
                target.ptzUrl ?: return@withContext null,
                """<GetPresets xmlns="http://www.onvif.org/ver20/ptz/wsdl">""" +
                    "<ProfileToken>${OnvifClient.xmlEscape(target.profileToken)}</ProfileToken></GetPresets>",
                camera.username, camera.password, target.clockOffsetMs
            ) ?: return@withContext null
            CameraDetails.parsePresetNames(response)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch preset names for ${camera.name}: ${e.message}")
            null
        }
    }

    /**
     * Запрашивает текущий режим день/ночь (IrCutFilter) камеры.
     * null — камера не отвечает или режим не поддерживается.
     */
    suspend fun fetchDayNightMode(camera: IpCamera): DayNightMode? = withContext(Dispatchers.IO) {
        try {
            if (camera.protocol == CameraProtocol.DEMO_SIMULATOR) return@withContext DayNightMode.AUTO
            // Режим день/ночь не зависит от того, какой протокол выбран для PTZ: пробуем все способы, какими камера
            // может его отдавать, а запомненный сработавший способ потом и управляет режимом (см. [sendDayNightMode])
            fun found(backend: DayNightBackend, mode: DayNightMode?): DayNightMode? {
                if (mode != null) dayNightBackends[dayNightKey(camera)] = backend
                return mode
            }
            found(DayNightBackend.DVRIP, fetchDvripDayNight(camera))?.let { return@withContext it }
            if (camera.hikvisionArchive || camera.protocol == CameraProtocol.HIKVISION) {
                found(DayNightBackend.HIKVISION, fetchHikvisionDayNight(camera))?.let { return@withContext it }
            }
            // Dahua с ONVIF: IrCutFilter в Imaging нет или он не работает, режим читается CGI камеры (у остальных — ошибка)
            if (camera.hasDahuaApi || camera.protocol == CameraProtocol.ONVIF) {
                found(DayNightBackend.DAHUA, fetchDahuaDayNight(camera))?.let { return@withContext it }
            }
            if (camera.sdHttpArchive) {
                found(DayNightBackend.HI3510, fetchHi3510Lamp(camera) ?: fetchHi3510Infrared(camera))?.let { return@withContext it }
            }
            if (camera.protocol == CameraProtocol.GENERIC_HTTP) return@withContext null
            found(DayNightBackend.ONVIF, fetchOnvifDayNight(camera))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch day/night mode for ${camera.name}: ${e.message}")
            null
        }
    }

    /** Чем камера отдаёт и принимает режим день/ночь (определяется при чтении режима) */
    private enum class DayNightBackend { DVRIP, HIKVISION, DAHUA, HI3510, ONVIF }

    private val dayNightBackends = ConcurrentHashMap<String, DayNightBackend>()

    private fun dayNightKey(camera: IpCamera) = "${camera.id}|${camera.ipAddress}|${camera.effectiveHttpPort}"

    /** Режим день/ночь через ONVIF Imaging (IrCutFilter); null — Imaging нет или режим не поддерживается */
    private fun fetchOnvifDayNight(camera: IpCamera): DayNightMode? {
        val target = resolveOnvifTarget(camera) ?: return null
        val imagingUrl = target.imagingUrl ?: return null
        val sourceToken = OnvifClient.xmlEscape(target.videoSourceToken ?: return null)
        fun imaging(operation: String) = OnvifClient.request(
            imagingUrl,
            """<$operation xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken></$operation>""",
            camera.username, camera.password, target.clockOffsetMs
        )
        // Многие камеры не возвращают IrCutFilter в текущих настройках, но поддерживают его —
        // это видно по списку допустимых режимов (IrCutFilterModes) в GetOptions
        val settings = imaging("GetImagingSettings")
        val filter = settings?.let { Regex("""<(?:\w+:)?IrCutFilter>([^<]+)<""").find(it)?.groupValues?.get(1) }
        return when {
            filter != null -> DayNightMode.fromString(filter)
            imaging("GetOptions")?.contains("IrCutFilterModes") == true -> DayNightMode.AUTO
            else -> (fetchHi3510Lamp(camera) ?: fetchHi3510Infrared(camera) ?: fetchDahuaDayNight(camera))
                // Камера не сообщает о поддержке, но Imaging-сервис есть — даём попробовать переключение
                ?: DayNightMode.AUTO
        }
    }

    /**
     * Меняет режим день/ночь тем способом, которым камера его отдала при чтении ([fetchDayNightMode]); пока способ
     * не известен — по протоколу камеры, как раньше.
     */
    private fun sendDayNightMode(camera: IpCamera, mode: DayNightMode): Boolean? = when (dayNightBackends[dayNightKey(camera)]) {
        DayNightBackend.HIKVISION -> setHikvisionDayNight(camera, mode)
        DayNightBackend.DAHUA -> setDahuaDayNight(camera, mode)
        DayNightBackend.DVRIP -> setDvripDayNight(camera, mode)
        DayNightBackend.HI3510 -> runCatching { setHi3510Lamp(camera, mode) }.getOrDefault(false) ||
            runCatching { setHi3510Infrared(camera, mode) }.getOrDefault(false)
        DayNightBackend.ONVIF, null -> null
    }

    // ---------------- Возможности PTZ и фокуса ----------------

    /**
     * Что камера умеет на самом деле: поворот (pan/tilt), оптический зум и управляемый фокус.
     * null — камера не сообщила (считаем, что есть: управление остаётся на экране).
     */
    data class PtzCaps(val panTilt: Boolean?, val zoom: Boolean?, val focus: Boolean? = null) {
        /** Камера явно сообщила, что нет ни поворота, ни зума */
        val none: Boolean get() = panTilt == false && zoom == false
    }

    /**
     * Спрашивает у камеры, есть ли у неё поворот, зум и фокус (ONVIF — PTZ-сервис, профили, узлы PTZ и
     * GetMoveOptions сервиса Imaging; Hikvision — возможности PTZCtrl и Image). Протокол в настройках выбирается
     * вручную и бывает выбран у неповоротной камеры или камеры с неподвижным объективом — ответ камеры позволяет
     * скрыть лишнее управление. null — камера не ответила или протокол не проверяется (у Dahua — [DahuaApi.fetchLensCaps]).
     */
    suspend fun fetchPtzCaps(camera: IpCamera): PtzCaps? = withContext(Dispatchers.IO) {
        try {
            when (camera.protocol) {
                CameraProtocol.ONVIF -> fetchOnvifPtzCaps(camera)
                CameraProtocol.HIKVISION -> fetchHikvisionPtzCaps(camera)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch PTZ caps for ${camera.name}: ${e.message}")
            null
        }?.also { Log.i(TAG, "PTZ caps for ${camera.name}: $it") }
    }

    private fun fetchOnvifPtzCaps(camera: IpCamera): PtzCaps? {
        val endpoints = OnvifClient.discover(camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password)
            ?: return null
        val profiles = OnvifClient.getProfiles(endpoints, camera.username, camera.password)
        if (profiles.isEmpty()) return null
        // Фокус — в сервисе Imaging и от PTZ не зависит: бывает и у неповоротной камеры с моторизованным объективом
        val focus = fetchOnvifFocusSupport(camera, endpoints, profiles)
        val ptzUrl = endpoints.ptzUrl
        if (ptzUrl == null || profiles.none { it.hasPtz }) return PtzCaps(panTilt = false, zoom = false, focus = focus)
        val nodes = OnvifClient.request(
            ptzUrl, """<GetNodes xmlns="http://www.onvif.org/ver20/ptz/wsdl"/>""",
            camera.username, camera.password, endpoints.clockOffsetMs
        ) ?: return PtzCaps(null, null, focus)
        return parseOnvifPtzNodes(nodes).copy(focus = focus)
    }

    /** Поворот и зум по пространствам координат узлов PTZ (GetNodes): пространство скорости не считается */
    internal fun parseOnvifPtzNodes(xml: String): PtzCaps {
        if (!Regex("""<(?:\w+:)?SupportedPTZSpaces\b""").containsMatchIn(xml)) return PtzCaps(null, null)
        fun has(axis: String) = Regex("""<(?:\w+:)?\w*$axis(?:Velocity|Position|Translation)Space\b""").containsMatchIn(xml)
        return PtzCaps(panTilt = has("PanTilt"), zoom = has("Zoom"))
    }

    private fun fetchOnvifFocusSupport(
        camera: IpCamera,
        endpoints: OnvifClient.Endpoints,
        profiles: List<OnvifClient.Profile>
    ): Boolean? {
        val imagingUrl = endpoints.imagingUrl ?: return false
        val sourceToken = (profiles.firstOrNull { it.hasPtz } ?: profiles.first()).videoSourceToken
            ?: OnvifClient.request(
                endpoints.mediaUrl,
                """<GetVideoSources xmlns="http://www.onvif.org/ver10/media/wsdl"/>""",
                camera.username, camera.password, endpoints.clockOffsetMs
            )?.let { Regex("""<(?:\w+:)?VideoSources\b[^>]*\btoken="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: return null
        // Камера только что ответила на запросы профилей: отказ здесь — SOAP Fault «перемещение фокуса не поддерживается»
        val options = OnvifClient.request(
            imagingUrl,
            """<GetMoveOptions xmlns="http://www.onvif.org/ver20/imaging/wsdl">""" +
                "<VideoSourceToken>${OnvifClient.xmlEscape(sourceToken)}</VideoSourceToken></GetMoveOptions>",
            camera.username, camera.password, endpoints.clockOffsetMs
        ) ?: return false
        return parseOnvifFocusMoveOptions(options)
    }

    /**
     * Ответ GetMoveOptions: фокус управляемый, если камера умеет перемещать его в точку или на шаг (Absolute, Relative).
     * Одно непрерывное перемещение (Continuous) не в счёт: его заявляют и камеры с неподвижным объективом (NVT) —
     * для настоящих камер только с ним фокус включается вручную в настройках камеры
     */
    internal fun parseOnvifFocusMoveOptions(xml: String): Boolean =
        Regex("""<(?:\w+:)?(?:Absolute|Relative)\b""").containsMatchIn(xml)

    private fun fetchHikvisionPtzCaps(camera: IpCamera): PtzCaps? {
        val base = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/ISAPI"
        val ptz = CameraHttp.request(
            "$base/PTZCtrl/channels/1/capabilities", user = camera.username, pass = camera.password, timeoutMs = 5_000
        )
        val image = CameraHttp.request(
            "$base/Image/channels/1/capabilities", user = camera.username, pass = camera.password, timeoutMs = 5_000
        )
        val caps = parseHikvisionPtzCaps(ptz.code, ptz.body)
        val focus = parseHikvisionFocusCaps(image.code, image.body)
        if (caps == null && focus == null) return null
        return (caps ?: PtzCaps(null, null)).copy(focus = focus)
    }

    /**
     * Ответ /ISAPI/PTZCtrl/channels/1/capabilities. Камера без PTZ отвечает 403/404 (notSupport) или
     * возможностями без пространств поворота и зума. Нет связи, 401 и нехватка прав — неизвестно.
     */
    internal fun parseHikvisionPtzCaps(code: Int, body: String): PtzCaps? = when {
        code in 200..299 -> {
            fun has(axis: String) = Regex("""<\w*$axis\w*Space\b""").containsMatchIn(body)
            PtzCaps(panTilt = has("PanTilt"), zoom = has("Zoom"))
        }
        code == 403 && body.contains("lowPrivilege", ignoreCase = true) -> null
        code == 400 || code == 403 || code == 404 -> PtzCaps(panTilt = false, zoom = false)
        else -> null
    }

    /**
     * Ответ /ISAPI/Image/channels/1/capabilities: управляемый фокус есть, если среди возможностей изображения
     * есть FocusConfiguration. Коды ответа — как у [parseHikvisionPtzCaps].
     */
    internal fun parseHikvisionFocusCaps(code: Int, body: String): Boolean? = when {
        code in 200..299 -> Regex("""<FocusConfiguration\b""").containsMatchIn(body)
        code == 403 && body.contains("lowPrivilege", ignoreCase = true) -> null
        code == 400 || code == 403 || code == 404 -> false
        else -> null
    }

    // ---------------- ONVIF ----------------

    private fun onvifCacheKey(camera: IpCamera) =
        "${camera.ipAddress}:${camera.effectiveOnvifPort}:${camera.username}:${camera.password.hashCode()}"

    private fun resolveOnvifTarget(camera: IpCamera): OnvifPtzTarget? {
        val key = onvifCacheKey(camera)
        onvifTargets[key]?.let { return it }

        val endpoints = OnvifClient.discover(camera.ipAddress, camera.effectiveOnvifPort, camera.username, camera.password)
            ?: return null
        // PTZ-сервис не обязателен: у фиксированных камер остаются Imaging (день/ночь, фокус) и т.п.
        if (endpoints.ptzUrl == null) Log.i(TAG, "ONVIF PTZ service not found for ${camera.name}")
        val profiles = OnvifClient.getProfiles(endpoints, camera.username, camera.password)
        val profile = profiles.firstOrNull { it.hasPtz } ?: profiles.firstOrNull() ?: return null

        // Токен видеоисточника бывает не указан в профиле — берём из GetVideoSources
        val videoSourceToken = profile.videoSourceToken
            ?: OnvifClient.request(
                endpoints.mediaUrl,
                """<GetVideoSources xmlns="http://www.onvif.org/ver10/media/wsdl"/>""",
                camera.username, camera.password, endpoints.clockOffsetMs
            )?.let { Regex("""<(?:\w+:)?VideoSources\b[^>]*\btoken="([^"]+)"""").find(it)?.groupValues?.get(1) }

        return OnvifPtzTarget(
            ptzUrl = endpoints.ptzUrl,
            imagingUrl = endpoints.imagingUrl,
            profileToken = profile.token,
            videoSourceToken = videoSourceToken,
            clockOffsetMs = endpoints.clockOffsetMs
        ).also { onvifTargets[key] = it }
    }

    private fun sendOnvifCommand(camera: IpCamera, command: PtzCommand): Boolean {
        val target = resolveOnvifTarget(camera) ?: return false
        val token = OnvifClient.xmlEscape(target.profileToken)

        fun send(url: String, body: String) =
            OnvifClient.request(url, body, camera.username, camera.password, target.clockOffsetMs) != null

        // Команды без PTZ-сервиса (фокус и день/ночь идут через Imaging) выполнимы и на фиксированной камере
        val ptzUrl = target.ptzUrl.orEmpty()
        val needsPtz = command !is PtzCommand.Focus && command !is PtzCommand.FocusMove &&
            command !is PtzCommand.FocusStop && command !is PtzCommand.SetDayNightMode
        if (needsPtz && ptzUrl.isEmpty()) return false

        val success = when (command) {
            is PtzCommand.Move -> {
                val x = (command.pan * command.speed).coerceIn(-1f, 1f)
                val y = (command.tilt * command.speed).coerceIn(-1f, 1f)
                send(ptzUrl, onvifContinuousMove(token, """<PanTilt x="$x" y="$y" xmlns="http://www.onvif.org/ver10/schema"/>"""))
            }
            is PtzCommand.Zoom -> {
                val z = (command.level * camera.ptzSpeed).coerceIn(-1f, 1f)
                send(ptzUrl, onvifContinuousMove(token, """<Zoom x="$z" xmlns="http://www.onvif.org/ver10/schema"/>"""))
            }
            is PtzCommand.Stop -> send(
                ptzUrl,
                """<Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>$token</ProfileToken>""" +
                    "<PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>"
            )
            is PtzCommand.GoToPreset -> {
                val presetToken = OnvifClient.xmlEscape(command.token)
                val gotoBody = """<GotoPreset xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>$token</ProfileToken>""" +
                    "<PresetToken>$presetToken</PresetToken>"
                // Speed по стандарту необязателен, но часть прошивок без него отвечает ошибкой
                send(ptzUrl, "$gotoBody</GotoPreset>") || send(
                    ptzUrl,
                    gotoBody + "<Speed>" +
                        """<PanTilt x="1" y="1" xmlns="http://www.onvif.org/ver10/schema"/>""" +
                        """<Zoom x="1" xmlns="http://www.onvif.org/ver10/schema"/>""" +
                        "</Speed></GotoPreset>"
                )
            }
            is PtzCommand.SavePreset -> onvifSetPreset(camera, target, command.presetId, command.presetId.toString()) != null
            is PtzCommand.Focus -> sendOnvifFocus(camera, target, command.direction)
            is PtzCommand.FocusMove -> {
                if (supportsRelativeFocus(camera, target)) {
                    startRelativeFocus(camera, target, command.speed)
                    true
                } else {
                    onvifFocusMove(camera, target, command.speed)
                }
            }
            is PtzCommand.FocusStop -> {
                // Пошаговый фокус останавливается сам — достаточно прекратить шаги
                if (stopRelativeFocus()) true else onvifImaging(camera, target) { sourceToken ->
                    """<Stop xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken></Stop>"""
                }
            }
            is PtzCommand.SetDayNightMode -> sendOnvifDayNightMode(camera, target, command.mode)
        }

        // Возможно, сменились параметры камеры — при следующей команде определим заново
        if (!success) onvifTargets.remove(onvifCacheKey(camera))
        return success
    }

    /**
     * SetPreset: камера может назначить положению собственный идентификатор (PresetToken) —
     * его нужно запомнить, иначе GotoPreset по нашему номеру не сработает.
     * Сначала просим сохранить под нашим токеном, при отказе — создать новое положение.
     * @return токен положения в камере или null при ошибке
     */
    private fun onvifSetPreset(camera: IpCamera, target: OnvifPtzTarget, presetId: Int, presetToken: String): String? {
        val profileToken = OnvifClient.xmlEscape(target.profileToken)

        fun setPreset(withToken: Boolean): String? {
            val body = """<SetPreset xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>$profileToken</ProfileToken>""" +
                "<PresetName>Preset$presetId</PresetName>" +
                (if (withToken) "<PresetToken>${OnvifClient.xmlEscape(presetToken)}</PresetToken>" else "") +
                "</SetPreset>"
            val response = OnvifClient.request(target.ptzUrl ?: return null, body, camera.username, camera.password, target.clockOffsetMs)
                ?: return null
            return Regex("""<(?:\w+:)?PresetToken>([^<]+)<""").find(response)?.groupValues?.get(1)?.trim()
                ?: presetToken
        }

        return (setPreset(withToken = true) ?: setPreset(withToken = false))
            ?.also { Log.i(TAG, "ONVIF preset $presetId saved with token '$it'") }
    }

    private fun onvifContinuousMove(profileToken: String, velocity: String) =
        """<ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>$profileToken</ProfileToken>""" +
            "<Velocity>$velocity</Velocity></ContinuousMove>"

    /** Запрос к сервису Imaging (фокус) с токеном видеоисточника профиля */
    private fun onvifImaging(camera: IpCamera, target: OnvifPtzTarget, body: (sourceToken: String) -> String): Boolean {
        val imagingUrl = target.imagingUrl ?: return false
        val sourceToken = OnvifClient.xmlEscape(target.videoSourceToken ?: return false)
        return OnvifClient.request(imagingUrl, body(sourceToken), camera.username, camera.password, target.clockOffsetMs) != null
    }

    // ---------------- Пошаговый фокус (ONVIF Relative) ----------------
    //
    // Многие камеры выполняют «непрерывную» фокусировку как один шаг на величину скорости
    // и не поддерживают Stop: качелька тогда за долю секунды уводит фокус в крайнее положение.
    // Поэтому, если камера умеет относительные шаги, пока качелька отклонена, отправляем
    // небольшие шаги с частотой FOCUS_STEP_INTERVAL_MS; отпустили — шаги прекращаются.

    /** Поддержка относительных шагов фокуса по камерам (GetMoveOptions), ключ — onvifCacheKey */
    private val relativeFocusSupport = ConcurrentHashMap<String, Boolean>()

    private val focusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var focusJob: Job? = null
    @Volatile private var focusSpeed = 0f

    private fun supportsRelativeFocus(camera: IpCamera, target: OnvifPtzTarget): Boolean {
        val key = onvifCacheKey(camera)
        relativeFocusSupport[key]?.let { return it }
        val imagingUrl = target.imagingUrl ?: return false
        val sourceToken = OnvifClient.xmlEscape(target.videoSourceToken ?: return false)
        val options = OnvifClient.request(
            imagingUrl,
            """<GetMoveOptions xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken></GetMoveOptions>""",
            camera.username, camera.password, target.clockOffsetMs
        )
        val supported = options != null && Regex("""<(?:\w+:)?Relative\b""").containsMatchIn(options)
        Log.i(TAG, "Relative focus for ${camera.name}: $supported")
        return supported.also { relativeFocusSupport[key] = it }
    }

    private fun startRelativeFocus(camera: IpCamera, target: OnvifPtzTarget, speed: Float) {
        focusSpeed = speed.coerceIn(-1f, 1f)
        if (focusJob?.isActive == true) {
            // Если фокусировка уже идет, просто обновляем скорость
            return
        }
        focusJob = focusScope.launch {
            var manualModeTried = false
            while (isActive && focusSpeed != 0f) {
                val distance = focusSpeed * RELATIVE_FOCUS_STEP
                val moved = onvifImaging(camera, target) { sourceToken ->
                    """<Move xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken>""" +
                        """<Focus><Relative xmlns="http://www.onvif.org/ver10/schema"><Distance>$distance</Distance></Relative></Focus></Move>"""
                }
                // В режиме автофокуса ручные шаги отклоняются — один раз переключаем в MANUAL
                if (!moved && !manualModeTried) {
                    manualModeTried = true
                    setOnvifFocusMode(camera, target, "MANUAL")
                    // Не продолжаем сразу, а ждем интервал чтобы камера применила изменения
                    delay(FOCUS_STEP_INTERVAL_MS)
                    continue
                }
                delay(FOCUS_STEP_INTERVAL_MS)
            }
        }
    }

    /** @return true, если шёл пошаговый фокус */
    private fun stopRelativeFocus(): Boolean {
        val wasActive = focusJob?.isActive == true
        focusSpeed = 0f
        focusJob?.cancel()
        focusJob = null
        return wasActive
    }

    private fun setOnvifFocusMode(camera: IpCamera, target: OnvifPtzTarget, mode: String) =
        onvifImaging(camera, target) { sourceToken ->
            """<SetImagingSettings xmlns="http://www.onvif.org/ver20/imaging/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">""" +
                "<VideoSourceToken>$sourceToken</VideoSourceToken>" +
                "<ImagingSettings><tt:Focus><tt:AutoFocusMode>$mode</tt:AutoFocusMode></tt:Focus></ImagingSettings>" +
                "</SetImagingSettings>"
        }

    private fun onvifFocusMove(camera: IpCamera, target: OnvifPtzTarget, speed: Float): Boolean {
        val moveBody = { sourceToken: String ->
            """<Move xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken>""" +
                """<Focus><Continuous xmlns="http://www.onvif.org/ver10/schema"><Speed>${speed.coerceIn(-1f, 1f)}</Speed></Continuous></Focus></Move>"""
        }
        if (onvifImaging(camera, target, moveBody)) return true

        // В режиме автофокуса камеры отклоняют ручную фокусировку — переключаем в MANUAL и повторяем
        val manualSet = onvifImaging(camera, target) { sourceToken ->
            """<SetImagingSettings xmlns="http://www.onvif.org/ver20/imaging/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">""" +
                "<VideoSourceToken>$sourceToken</VideoSourceToken>" +
                "<ImagingSettings><tt:Focus><tt:AutoFocusMode>MANUAL</tt:AutoFocusMode></tt:Focus></ImagingSettings>" +
                "</SetImagingSettings>"
        }
        return manualSet && onvifImaging(camera, target, moveBody)
    }

    private fun sendOnvifFocus(camera: IpCamera, target: OnvifPtzTarget, direction: FocusDirection): Boolean {
        val imagingUrl = target.imagingUrl ?: return false
        val sourceToken = OnvifClient.xmlEscape(target.videoSourceToken ?: return false)

        fun send(body: String) =
            OnvifClient.request(imagingUrl, body, camera.username, camera.password, target.clockOffsetMs) != null

        if (direction == FocusDirection.AUTO) {
            return send(
                """<SetImagingSettings xmlns="http://www.onvif.org/ver20/imaging/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">""" +
                    "<VideoSourceToken>$sourceToken</VideoSourceToken>" +
                    "<ImagingSettings><tt:Focus><tt:AutoFocusMode>AUTO</tt:AutoFocusMode></tt:Focus></ImagingSettings>" +
                    "</SetImagingSettings>"
            )
        }

        val speed = if (direction == FocusDirection.NEAR) -camera.ptzSpeed else camera.ptzSpeed
        val started = send(
            """<Move xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken>""" +
                """<Focus><Continuous xmlns="http://www.onvif.org/ver10/schema"><Speed>$speed</Speed></Continuous></Focus></Move>"""
        )
        if (!started) return false
        Thread.sleep(FOCUS_PULSE_MS)
        return send("""<Stop xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$sourceToken</VideoSourceToken></Stop>""")
    }

    private fun sendOnvifDayNightMode(camera: IpCamera, target: OnvifPtzTarget, mode: DayNightMode): Boolean {
        val filterVal = when (mode) {
            DayNightMode.DAY -> "ON"
            DayNightMode.NIGHT -> "OFF"
            DayNightMode.AUTO -> "AUTO"
        }
        // Камеры на прошивке Hi3510 принимают ONVIF-команду, но ИК-фильтр не меняют — работает только их CGI.
        // Поэтому CGI пробуем первым: на остальных камерах он вернёт ошибку и сработает ONVIF.
        if (setDvripDayNight(camera, mode)) return true
        if (runCatching { setHi3510Lamp(camera, mode) }.getOrDefault(false)) return true
        if (runCatching { setHi3510Infrared(camera, mode) }.getOrDefault(false)) return true
        // Dahua отвечает на ONVIF IrCutFilter успехом, но не переключает режим — её CGI пробуем до ONVIF
        if (runCatching { setDahuaDayNight(camera, mode) }.getOrDefault(false)) return true
        val onvifOk = onvifImaging(camera, target) { sourceToken ->
            """<SetImagingSettings xmlns="http://www.onvif.org/ver20/imaging/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">""" +
                "<VideoSourceToken>$sourceToken</VideoSourceToken>" +
                "<ImagingSettings><tt:IrCutFilter>$filterVal</tt:IrCutFilter></ImagingSettings>" +
                "</SetImagingSettings>"
        }
        return onvifOk || runCatching { setHi3510Infrared(camera, mode) }.getOrDefault(false)
    }

    // ---------------- Dahua: режим день/ночь (VideoInOptions[0].DayNightColor) ----------------
    //
    // Dahua с PTZ по ONVIF принимает IrCutFilter, но картинку не меняет: цвет / чёрно-белое переключает только её CGI.
    // DayNightColor: 0 — всегда цвет (день), 1 — авто по освещённости (камера переключается с задержкой
    // DayNightSwitchDelay, обычно 10 с), 2 — всегда чёрно-белое (ночь). Проверено по снимкам камеры DH-SD-H2C.

    private fun dahuaConfigUrl(camera: IpCamera) =
        "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/configManager.cgi?action="

    /** null — камера не отдаёт DayNightColor (не Dahua или режим не поддерживается) */
    private fun fetchDahuaDayNight(camera: IpCamera): DayNightMode? {
        val response = CameraHttp.request(
            dahuaConfigUrl(camera) + "getConfig&name=VideoInOptions",
            user = camera.username, pass = camera.password
        )
        if (!response.isSuccess) return null
        return when (Regex("""VideoInOptions\[0]\.DayNightColor=(\d+)""").find(response.body)?.groupValues?.get(1)) {
            null -> null
            "0" -> DayNightMode.DAY
            "2" -> DayNightMode.NIGHT
            else -> DayNightMode.AUTO
        }
    }

    private fun setDahuaDayNight(camera: IpCamera, mode: DayNightMode): Boolean {
        val value = when (mode) {
            DayNightMode.DAY -> 0
            DayNightMode.NIGHT -> 2
            DayNightMode.AUTO -> 1
        }
        val response = CameraHttp.request(
            dahuaConfigUrl(camera) + "setConfig&VideoInOptions%5B0%5D.DayNightColor=$value",
            user = camera.username, pass = camera.password
        )
        return response.isSuccess && response.body.trim().equals("OK", ignoreCase = true)
    }

    // ---------------- XMEye/DVRIP: режим ночной подсветки (Camera.WhiteLight.WorkMode) ----------------
    //
    // Камеры XMEye с белыми светодиодами в дополнение к ИК (например, XM530V200) принимают ONVIF IrCutFilter и
    // DayNightColor с кодом успеха, но ничего не меняют. Переключается WorkMode белой подсветки — тот же выбор
    // «ИК / полноцветный / интеллектуальный», что и lamp_mode у Hi3510. Протокол слабо защищён, поэтому
    // используется только для камер с включённым пользователем dvripArchive (см. DvripClient).

    private fun dvripWhiteLightMode(mode: DayNightMode) = when (mode) {
        DayNightMode.DAY -> "KeepOpen"
        DayNightMode.NIGHT -> "Close"
        DayNightMode.AUTO -> "Intelligent"
    }

    // IRCUTMode камеры (то же поле меняет ONVIF IrCutFilter, проверено): 0 — фильтр включён (день), 1 — выключен (ночь, ИК), 2 — авто
    private fun dvripIrCutMode(mode: DayNightMode) = when (mode) {
        DayNightMode.DAY -> 0
        DayNightMode.NIGHT -> 1
        DayNightMode.AUTO -> 2
    }

    private suspend fun <T> withDvrip(camera: IpCamera, block: suspend (DvripClient) -> T?): T? {
        if (!camera.dvripArchive) return null
        val client = DvripClient(camera)
        return try {
            client.login()
            block(client)
        } catch (e: Exception) {
            Log.w(TAG, "DVRIP недоступен для ${camera.name}: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    private suspend fun fetchDvripDayNight(camera: IpCamera): DayNightMode? =
        withDvrip(camera) { it.nightLight() }?.let { light ->
            when (light.irCutMode) {
                0 -> DayNightMode.DAY
                1 -> DayNightMode.NIGHT
                2 -> DayNightMode.AUTO
                else -> null
            }
        }

    /** День/ночь/авто = ИК-фильтр + белая подсветка вместе: у камеры с двумя видами подсветки это две независимые настройки */
    private fun setDvripDayNight(camera: IpCamera, mode: DayNightMode): Boolean = runBlocking {
        withDvrip(camera) { client ->
            val filter = client.setIrCutMode(dvripIrCutMode(mode))
            val light = client.setWhiteLightMode(dvripWhiteLightMode(mode))
            filter || light
        }
    } == true

    // ---------------- Hi3510 CGI (ИК-режим камер без IrCutFilter в ONVIF) ----------------

    private fun hi3510Url(camera: IpCamera, query: String) =
        "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/hi3510/param.cgi?$query"

    // На камерах с режимом «Night Vision Mode» (lamp_mode: 0 — обычный/ИК, 1 — полноцветный, 2 — интеллектуальный)
    // infraredstat управляет лишь ИК-подсветкой, а чёрно-белая/цветная картинка переключается через lamp_mode.
    private fun fetchHi3510Lamp(camera: IpCamera): DayNightMode? {
        val response = CameraHttp.request(hi3510Url(camera, "cmd=getlampattrex"), user = camera.username, pass = camera.password)
        if (!response.isSuccess) return null
        return when (Regex("""lamp_mode\s*=\s*"?(\d)""").find(response.body)?.groupValues?.get(1)) {
            "1" -> DayNightMode.DAY
            "2" -> DayNightMode.AUTO
            "0" -> fetchHi3510Infrared(camera).takeIf { it == DayNightMode.NIGHT } ?: DayNightMode.AUTO
            else -> null
        }
    }

    private fun setHi3510Lamp(camera: IpCamera, mode: DayNightMode): Boolean {
        val lampMode = when (mode) {
            DayNightMode.DAY -> 1
            DayNightMode.NIGHT -> 0
            DayNightMode.AUTO -> 2
        }
        val response = CameraHttp.request(
            hi3510Url(camera, "cmd=setlampattrex&-lamp_mode=$lampMode"),
            user = camera.username, pass = camera.password
        )
        if (!response.isSuccess || response.body.contains("error", ignoreCase = true)) return false
        // В обычном режиме ночью включаем ИК-подсветку, в остальных — отдаём её камере
        runCatching { setHi3510Infrared(camera, if (mode == DayNightMode.NIGHT) DayNightMode.NIGHT else DayNightMode.AUTO) }
        return true
    }

    private fun fetchHi3510Infrared(camera: IpCamera): DayNightMode? {
        val response = CameraHttp.request(hi3510Url(camera, "cmd=getinfrared"), user = camera.username, pass = camera.password)
        if (!response.isSuccess) return null
        val state = Regex("""infraredstat\s*=\s*"?(\w+)""").find(response.body)?.groupValues?.get(1) ?: return null
        return when (state.lowercase()) {
            "auto" -> DayNightMode.AUTO
            "open" -> DayNightMode.NIGHT
            "close" -> DayNightMode.DAY
            else -> null
        }
    }

    private fun setHi3510Infrared(camera: IpCamera, mode: DayNightMode): Boolean {
        val value = when (mode) {
            DayNightMode.AUTO -> "auto"
            DayNightMode.NIGHT -> "open"
            DayNightMode.DAY -> "close"
        }
        val response = CameraHttp.request(
            hi3510Url(camera, "cmd=setinfrared&-infraredstat=$value"),
            user = camera.username, pass = camera.password
        )
        return response.isSuccess && !response.body.contains("error", ignoreCase = true)
    }

    // ---------------- Hikvision ISAPI ----------------

    private fun sendHikvisionCommand(camera: IpCamera, command: PtzCommand): Boolean {
        val base = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/ISAPI"
        val ptz = "$base/PTZCtrl/channels/1"

        fun put(url: String, body: String) = CameraHttp.request(
            url, "PUT", body, "application/xml", camera.username, camera.password
        ).isSuccess

        // Скорость ISAPI: -100..100, знак задаёт направление
        fun scaled(value: Float, speed: Float) = (value * speed * 100).roundToInt().coerceIn(-100, 100)

        return when (command) {
            is PtzCommand.Move -> put(
                "$ptz/continuous",
                "<PTZData><pan>${scaled(command.pan, command.speed)}</pan><tilt>${scaled(command.tilt, command.speed)}</tilt></PTZData>"
            )
            is PtzCommand.Zoom -> put(
                "$ptz/continuous",
                "<PTZData><zoom>${scaled(command.level, camera.ptzSpeed)}</zoom></PTZData>"
            )
            is PtzCommand.Stop -> put(
                "$ptz/continuous",
                "<PTZData><pan>0</pan><tilt>0</tilt><zoom>0</zoom></PTZData>"
            )
            is PtzCommand.GoToPreset -> put("$ptz/presets/${command.presetId}/goto", "")
            is PtzCommand.SavePreset -> put(
                "$ptz/presets/${command.presetId}",
                "<PTZPreset><id>${command.presetId}</id><presetName>Preset ${command.presetId}</presetName></PTZPreset>"
            )
            is PtzCommand.Focus -> {
                if (command.direction == FocusDirection.AUTO) {
                    // Однократная автофокусировка (опечатка «foucs» — так в API Hikvision)
                    return put("$ptz/onepushfoucs/start", "")
                }
                val value = if (command.direction == FocusDirection.NEAR) -60 else 60
                val started = put("$base/Image/channels/1/focus", "<FocusData><focus>$value</focus></FocusData>")
                if (started) {
                    Thread.sleep(FOCUS_PULSE_MS)
                    put("$base/Image/channels/1/focus", "<FocusData><focus>0</focus></FocusData>")
                }
                started
            }
            is PtzCommand.FocusMove -> put(
                "$base/Image/channels/1/focus",
                "<FocusData><focus>${scaled(command.speed, 1f)}</focus></FocusData>"
            )
            is PtzCommand.FocusStop -> put("$base/Image/channels/1/focus", "<FocusData><focus>0</focus></FocusData>")
            is PtzCommand.SetDayNightMode -> setHikvisionDayNight(camera, command.mode)
        }
    }

    // ---------------- Hikvision ISAPI: режим день/ночь (/ISAPI/Image/channels/1/ircutFilter) ----------------
    //
    // Прошивки отдают режим по-разному: DS-I114W (V5.4.81) — `<IrcutFilter><IrcutFilterType>auto|day|night</IrcutFilterType>…`,
    // более старые — `<IrCutFilter><mode>…</mode>`. Меняется только значение режима в том XML, который отдала камера,
    // остальные поля (уровень и время перехода в день и т. п.) остаются как были.

    private fun hikvisionIrCutUrl(camera: IpCamera) = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/ISAPI/Image/channels/1/ircutFilter"

    /** Режим из XML настроек ИК-фильтра; null — режима в ответе нет */
    internal fun parseHikvisionDayNight(xml: String): DayNightMode? =
        (Regex("""<IrcutFilterType>\s*([^<]*?)\s*</IrcutFilterType>""", RegexOption.IGNORE_CASE).find(xml)
            ?: Regex("""<mode>\s*([^<]*?)\s*</mode>""").find(xml))?.groupValues?.get(1)?.let { DayNightMode.fromString(it) }

    /** XML настроек ИК-фильтра с другим режимом; null — режима в XML нет */
    internal fun withHikvisionDayNight(xml: String, mode: DayNightMode): String? {
        val value = when (mode) {
            DayNightMode.DAY -> "day"
            DayNightMode.NIGHT -> "night"
            DayNightMode.AUTO -> "auto"
        }
        val typed = Regex("""(<IrcutFilterType>)\s*[^<]*?\s*(</IrcutFilterType>)""", RegexOption.IGNORE_CASE)
        if (typed.containsMatchIn(xml)) return typed.replace(xml) { it.groupValues[1] + value + it.groupValues[2] }
        val plain = Regex("""(<mode>)\s*[^<]*?\s*(</mode>)""")
        return if (plain.containsMatchIn(xml)) plain.replace(xml) { it.groupValues[1] + value + it.groupValues[2] } else null
    }

    private fun fetchHikvisionDayNight(camera: IpCamera): DayNightMode? {
        val response = CameraHttp.request(hikvisionIrCutUrl(camera), user = camera.username, pass = camera.password)
        return if (response.isSuccess) parseHikvisionDayNight(response.body) else null
    }

    private fun setHikvisionDayNight(camera: IpCamera, mode: DayNightMode): Boolean {
        val current = CameraHttp.request(hikvisionIrCutUrl(camera), user = camera.username, pass = camera.password)
        if (!current.isSuccess) return false
        val changed = withHikvisionDayNight(current.body, mode) ?: return false
        val saved = CameraHttp.request(hikvisionIrCutUrl(camera), "PUT", changed, "application/xml", camera.username, camera.password)
        return saved.isSuccess && saved.body.contains("<statusString>OK</statusString>")
    }

    // ---------------- Dahua CGI ----------------

    private fun sendDahuaCommand(camera: IpCamera, command: PtzCommand): Boolean {
        val base = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/ptz.cgi"
        val speedInt = (camera.ptzSpeed * 8).roundToInt().coerceIn(1, 8)

        fun get(url: String) = CameraHttp.request(url, user = camera.username, pass = camera.password).isSuccess

        fun start(code: String, arg1: Int, arg2: Int): Boolean {
            dahuaLastCode[camera.id] = code
            return get("$base?action=start&channel=1&code=$code&arg1=$arg1&arg2=$arg2&arg3=0")
        }

        fun stop(): Boolean {
            val code = dahuaLastCode[camera.id] ?: return true
            // Код забываем только после доставки: иначе повтор недоставленного Stop ничего бы не отправил
            return get("$base?action=stop&channel=1&code=$code&arg1=0&arg2=0&arg3=0")
                .also { if (it) dahuaLastCode.remove(camera.id, code) }
        }

        return when (command) {
            is PtzCommand.Move -> {
                val left = command.pan < -DIRECTION_DEAD_ZONE
                val right = command.pan > DIRECTION_DEAD_ZONE
                val up = command.tilt > DIRECTION_DEAD_ZONE
                val down = command.tilt < -DIRECTION_DEAD_ZONE
                val code = when {
                    left && up -> "LeftUp"
                    right && up -> "RightUp"
                    left && down -> "LeftDown"
                    right && down -> "RightDown"
                    left -> "Left"
                    right -> "Right"
                    up -> "Up"
                    down -> "Down"
                    else -> return stop()
                }
                val moveSpeed = (command.speed * 8).roundToInt().coerceIn(1, 8)
                // Пока направление не изменилось, повторно команду не шлём
                if (dahuaLastCode[camera.id] == code) true else start(code, moveSpeed, moveSpeed)
            }
            is PtzCommand.Zoom -> start(if (command.level > 0) "ZoomTele" else "ZoomWide", 0, speedInt)
            is PtzCommand.Stop -> stop()
            is PtzCommand.GoToPreset -> get("$base?action=start&channel=1&code=GotoPreset&arg1=0&arg2=${command.presetId}&arg3=0")
            is PtzCommand.SavePreset -> get("$base?action=start&channel=1&code=SetPreset&arg1=0&arg2=${command.presetId}&arg3=0")
            is PtzCommand.Focus -> {
                if (command.direction == FocusDirection.AUTO) {
                    return get("http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/devVideoInput.cgi?action=autoFocus&channel=1")
                }
                val started = start(if (command.direction == FocusDirection.NEAR) "FocusNear" else "FocusFar", 0, speedInt)
                if (started) {
                    Thread.sleep(FOCUS_PULSE_MS)
                    stop()
                }
                started
            }
            is PtzCommand.FocusMove -> {
                val code = if (command.speed < 0) "FocusNear" else "FocusFar"
                val focusSpeed = (abs(command.speed) * 8).roundToInt().coerceIn(1, 8)
                start(code, 0, focusSpeed)
            }
            is PtzCommand.FocusStop -> stop()
            is PtzCommand.SetDayNightMode -> setDahuaDayNight(camera, command.mode)
        }
    }

    // ---------------- Generic ----------------

    private fun sendGenericHttpCommand(camera: IpCamera, command: PtzCommand): Boolean {
        val cmd = when (command) {
            is PtzCommand.Move -> when {
                abs(command.pan) >= abs(command.tilt) && command.pan > 0 -> "right"
                abs(command.pan) >= abs(command.tilt) -> "left"
                command.tilt > 0 -> "up"
                else -> "down"
            }
            is PtzCommand.Zoom -> if (command.level > 0) "zoomin" else "zoomout"
            is PtzCommand.Stop -> "stop"
            is PtzCommand.GoToPreset -> "preset&id=${command.presetId}"
            is PtzCommand.SavePreset -> "setpreset&id=${command.presetId}"
            is PtzCommand.Focus -> "focus_${command.direction.name.lowercase()}"
            is PtzCommand.FocusMove -> if (command.speed < 0) "focus_near" else "focus_far"
            is PtzCommand.FocusStop -> "stop"
            is PtzCommand.SetDayNightMode -> "daynight_${command.mode.name.lowercase()}"
        }
        val url = "http://${camera.ipAddress}:${camera.effectiveHttpPort}/cgi-bin/ptzctrl?cmd=$cmd"
        return CameraHttp.request(url, user = camera.username, pass = camera.password).isSuccess
    }
}
