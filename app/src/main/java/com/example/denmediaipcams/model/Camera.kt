package com.example.denmediaipcams.model

enum class CameraProtocol(val displayName: String, val defaultRtspPath: String) {
    ONVIF("ONVIF Standard", "/live/ch0"),
    HIKVISION("Hikvision ISAPI", "/Streaming/Channels/101"),
    DAHUA("Dahua CGI", "/cam/realmonitor?channel=1&subtype=0"),
    GENERIC_HTTP("Generic HTTP CGI", "/live/ch0"),
    DEMO_SIMULATOR("Demo Simulator", ""),
    NONE("Без PTZ (только видео)", "");

    val supportsPtz: Boolean get() = this != NONE
}

/** Какой протокол реально отвечает на открытом порту камеры (определяется по ответу, а не по номеру) */
enum class PortKind(val label: String) {
    RTSP("RTSP"),
    HTTP("HTTP"),
    HTTPS("HTTPS"),
    ONVIF("ONVIF"),
    DAHUA_SDK("Dahua SDK"),
    DVRIP("XMEye/DVRIP")
}

/** Открытый порт камеры и протоколы, которые на нём подтверждены проверкой */
data class KnownPort(val port: Int, val kinds: Set<PortKind>) {
    val label: String
        get() = if (kinds.isEmpty()) "$port" else "$port · ${kinds.joinToString(", ") { it.label }}"
}

/**
 * Один из нескольких потоков камеры с разным качеством (например, основной и дополнительный).
 */
data class StreamProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val rtspPath: String
)

data class Preset(
    val id: Int,
    val name: String,
    val token: String = id.toString(),
    /** В этой ячейке сохранено положение камеры */
    val isSaved: Boolean = false
)

/**
 * Где на видео показывать дату и время. Порядок — по строкам сетки 3×3 (сверху вниз, слева направо),
 * поэтому строка и столбец считаются из номера.
 */
enum class VideoRotation(val displayName: String, val degrees: Int) {
    NONE("Без поворота", 0),
    LEFT_90("90° влево", 270),
    RIGHT_90("90° вправо", 90),
    UPSIDE_DOWN("180°", 180);

    /** Поворот на 90° (а не на 180°): камеры чаще всего умеют только переворот, а не такой поворот */
    val isQuarterTurn: Boolean get() = this == LEFT_90 || this == RIGHT_90
}

/**
 * Положение изображения, которое камера выдаёт сама (настройки в самой камере): отражение по вертикали
 * и по горизонтали, разворот на 90° ([rotate90]: 0 — нет, 1 — по часовой стрелке, 2 — против).
 * Переворот на 180° — это отражение сразу по обеим осям.
 */
data class CameraOrientation(val flip: Boolean, val mirror: Boolean, val rotate90: Int = 0) {

    /** Положение камеры, при котором картинка повёрнута на [rotation] относительно этого */
    fun turned(rotation: VideoRotation): CameraOrientation = when (rotation) {
        VideoRotation.NONE -> this
        VideoRotation.UPSIDE_DOWN -> copy(flip = !flip, mirror = !mirror)
        VideoRotation.RIGHT_90 -> copy(rotate90 = 1)
        VideoRotation.LEFT_90 -> copy(rotate90 = 2)
    }

    fun encode(): String = "${if (flip) 1 else 0},${if (mirror) 1 else 0},$rotate90"

    companion object {
        fun decode(text: String?): CameraOrientation? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 3) return null
            val rotate = parts[2].toIntOrNull() ?: return null
            return CameraOrientation(parts[0] == "1", parts[1] == "1", rotate)
        }
    }
}

/** Управление фокусом камеры: определить по ответу камеры или задать вручную (камера может сообщать неверно) */
enum class FocusAvailability(val displayName: String) {
    AUTO("Авто"),
    YES("Есть"),
    NO("Нет")
}

enum class ClockPosition {
    TOP_START, TOP_CENTER, TOP_END,
    CENTER_START, CENTER, CENTER_END,
    BOTTOM_START, BOTTOM_CENTER, BOTTOM_END;

    /** 0 — сверху, 1 — по центру, 2 — снизу */
    val row: Int get() = ordinal / 3

    /** 0 — слева, 1 — по центру, 2 — справа */
    val column: Int get() = ordinal % 3
}

/**
 * Данные о камере для подключения и настройки.
 */
data class IpCamera(
    val id: String,
    val name: String,
    val ipAddress: String,
    val rtspPort: Int = DEFAULT_RTSP_PORT,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val onvifPort: Int = DEFAULT_ONVIF_PORT,
    /** Проприетарный порт Dahua (SDK); 0 — не задан */
    val dahuaPort: Int = DEFAULT_DAHUA_PORT,
    /** Открытые порты этой камеры, найденные и проверенные при добавлении (свои у каждой камеры) */
    val knownPorts: List<KnownPort> = emptyList(),
    val rtspPath: String = DEFAULT_RTSP_PATH,
    val username: String = "",
    val password: String = "",
    val protocol: CameraProtocol = CameraProtocol.ONVIF,
    val ptzSpeed: Float = 0.5f,
    /**
     * Режим для недорогих поворотных камер (Yoosee и аналоги): каждая команда поворота выполняется
     * ограниченным шагом, поэтому пока курок удержан, команда повторяется. Зума и фокуса у таких камер нет.
     */
    val ptzRepeatMode: Boolean = false,
    /** Зеркалить поворот по горизонтали: камера, у которой команда «вправо» поворачивает влево (оси самой камеры) */
    val ptzInvertPan: Boolean = false,
    /** Зеркалить наклон по вертикали: камера, у которой команда «вверх» наклоняет вниз (оси самой камеры) */
    val ptzInvertTilt: Boolean = false,
    /** Есть ли у камеры управляемый фокус: «авто» — по ответу камеры */
    val focusAvailability: FocusAvailability = FocusAvailability.AUTO,
    val presets: List<Preset> = defaultPresets(),
    /** Дополнительные потоки камеры (разное качество); пусто — используется только rtspPath */
    val streamProfiles: List<StreamProfile> = emptyList(),
    /** Какой из streamProfiles сейчас воспроизводится; null — первый из списка (или rtspPath, если список пуст) */
    val activeStreamProfileId: String? = null,
    /** Временно переключаться на поток с меньшим разрешением, пока связь с камерой нестабильна (нужно 2+ потока) */
    val autoLowerQuality: Boolean = true,
    /** Периодически ставить в превью камеры снимок, который камера отдаёт по HTTP (если отдаёт) */
    val snapshotThumbnail: Boolean = false,
    /**
     * Камера отвечает на HTTP API Dahua (события, архив, двусторонний звук). Не зависит от выбранного
     * протокола PTZ: Dahua с PTZ по ONVIF тоже поддерживает эти функции.
     */
    val dahuaApi: Boolean = false,
    /** Камера отдаёт SD-карту HTTP-каталогом /sd/ (Hipcam/HiSilicon): архив записей без Dahua API */
    val sdHttpArchive: Boolean = false,
    /** Камера Hikvision (и совместимые) отвечает на ISAPI: архив записей на SD-карте, поиск и воспроизведение по RTSP */
    val hikvisionArchive: Boolean = false,
    /**
     * Когда звук включён в приложении, а в потоке камеры он выключен, приложение включает звук в настройках потока
     * самой камеры (сейчас — Hikvision, см. HikvisionAudio). Со звуком в потоке он попадает и в записи на SD-карту.
     */
    val cameraAudioWithApp: Boolean = true,
    /**
     * Включает архив по проприетарному протоколу XMEye/DVRIP (порт 34567, см. DvripArchive/DvripClient) —
     * для камер без каталога /sd/ и без Dahua API. Протокол устарел и слабо защищён (пароль передаётся
     * лишь слабо хэшированным, известны уязвимости), а некорректные сетевые запросы к части прошивок
     * приводили к зависанию или перезагрузке камеры, поэтому включается только явно пользователем —
     * никогда автоматическим сканом портов (см. PortDetector.XMEYE_SDK_PORTS).
     */
    val dvripArchive: Boolean = false,
    /** Порт протокола XMEye/DVRIP; 0 — не задан (используется [DEFAULT_DVRIP_PORT]) */
    val dvripPort: Int = DEFAULT_DVRIP_PORT,
    /** Порт HTTP-интерфейса Hipcam (каталог /sd/, hi3510 CGI); 0 — не задан, используется HTTP порт */
    val hipcamPort: Int = 0,
    /** Слушать события камеры (движение и др.) и сообщать о них, пока приложение работает */
    val motionAlerts: Boolean = false,
    /** Показывать дату и время в правом верхнем углу плеера */
    val showDateTime: Boolean = true,
    /** Положение даты и времени на видео */
    val clockPosition: ClockPosition = ClockPosition.TOP_END,
    /** Поворот видео на заданный угол */
    val videoRotation: VideoRotation = VideoRotation.NONE,
    /**
     * Какую часть поворота [videoRotation] уже выполняет сама камера (её настройки переворота картинки).
     * Приложение поворачивает программно только остаток, см. [softwareRotationDegrees]
     */
    val hardwareRotation: VideoRotation = VideoRotation.NONE,
    /** Положение изображения камеры без поворота от приложения (запоминается перед первой записью в камеру) */
    val rotationBase: CameraOrientation? = null,
    /** Выводить на главном экране блок «Архив видео» с последними записями */
    val archiveBlockEnabled: Boolean = true,
    /** Сколько последних записей показывать в блоке (1..[MAX_ARCHIVE_BLOCK_COUNT]) */
    val archiveBlockCount: Int = MAX_ARCHIVE_BLOCK_COUNT,
    /** Не выводить блок, пока устройство работает через лимитное подключение к сети */
    val archiveBlockHideOnMetered: Boolean = false,
    /**
     * Ставить камере время и часовой пояс планшета: при добавлении камеры и когда она появилась в сети после
     * долгого перерыва (см. CameraTimeSync). Работает, только если камера позволяет менять время.
     */
    val autoSyncTime: Boolean = true
) {
    companion object {
        const val MAX_ARCHIVE_BLOCK_COUNT = 10
        const val DEFAULT_RTSP_PORT = 554
        const val DEFAULT_HTTP_PORT = 80
        const val DEFAULT_ONVIF_PORT = 8080
        const val DEFAULT_DAHUA_PORT = 37777
        const val DEFAULT_DVRIP_PORT = 34567
        const val DEFAULT_RTSP_PATH = "/live/ch0"

        fun defaultPresets(): List<Preset> {
            return (1..8).map { Preset(it, "Пресет $it", it.toString(), false) }
        }
    }

    /** Доступны функции Dahua: отмечены вручную/автоопределением или выбран протокол Dahua */
    val hasDahuaApi: Boolean
        get() = dahuaApi || protocol == CameraProtocol.DAHUA

    /** Эффективный RTSP порт */
    val effectiveRtspPort: Int
        get() = if (rtspPort > 0) rtspPort else DEFAULT_RTSP_PORT

    /** Эффективный HTTP порт */
    val effectiveHttpPort: Int
        get() = if (httpPort > 0) httpPort else DEFAULT_HTTP_PORT

    /** Эффективный ONVIF порт */
    val effectiveOnvifPort: Int
        get() = if (onvifPort > 0) onvifPort else DEFAULT_ONVIF_PORT

    /** Эффективный порт Dahua SDK */
    val effectiveDahuaPort: Int
        get() = if (dahuaPort > 0) dahuaPort else DEFAULT_DAHUA_PORT

    /** Эффективный порт Hipcam: свой, а если не задан — HTTP порт */
    val effectiveHipcamPort: Int
        get() = if (hipcamPort > 0) hipcamPort else effectiveHttpPort

    /** Эффективный порт XMEye/DVRIP */
    val effectiveDvripPort: Int
        get() = if (dvripPort > 0) dvripPort else DEFAULT_DVRIP_PORT

    /**
     * Формирует полный RTSP URL с учетом авторизации, порта и пути.
     */
    val fullRtspUrl: String
        get() {
            if (ipAddress.startsWith("rtsp://") ||
                ipAddress.startsWith("http://") || ipAddress.startsWith("https://")) {
                return ipAddress
            }
            // Только пароль (без логина) — часть камер так и работает: rtsp://:пароль@host
            if (username.isBlank() && password.isBlank()) return rtspUrlWithoutCredentials
            // Спецсимволы в логине/пароле (@ : # / ? %) обязательно экранируются,
            // иначе URL разбирается неверно и плеер подключается не к тому хосту/пути
            val user = android.net.Uri.encode(username)
            val pass = android.net.Uri.encode(password)
            return "rtsp://$user:$pass@$ipAddress:$effectiveRtspPort$formattedRtspPath"
        }

    /**
     * RTSP URL без логина и пароля (для отображения и диагностики).
     */
    val rtspUrlWithoutCredentials: String
        get() {
            if (ipAddress.startsWith("rtsp://") ||
                ipAddress.startsWith("http://") || ipAddress.startsWith("https://")) {
                return ipAddress
            }
            return "rtsp://$ipAddress:$effectiveRtspPort$formattedRtspPath"
        }

    /**
     * Смартфон: если дата и время выводятся справа сверху, кнопка «три точки» плеера
     * переносится в правый нижний угол, чтобы они не мешали друг другу
     */
    fun menuAtBottomCorner(isPhone: Boolean): Boolean =
        isPhone && showDateTime && clockPosition == ClockPosition.TOP_END

    /** Угол (по часовой стрелке), на который видео поворачивает приложение: то, что не сделала камера */
    val softwareRotationDegrees: Int
        get() = ((videoRotation.degrees - hardwareRotation.degrees) % 360 + 360) % 360

    /**
     * Переводит направление, заданное на экране (pan: вправо > 0, tilt: вверх > 0), в команду для камеры.
     * Видео показано повёрнутым на [videoRotation], поэтому «вправо» на экране — это другая сторона для самой камеры;
     * затем применяются зеркала осей камеры ([ptzInvertPan], [ptzInvertTilt]).
     */
    fun cameraMove(screenPan: Float, screenTilt: Float): Pair<Float, Float> {
        val (pan, tilt) = when (videoRotation) {
            VideoRotation.NONE -> screenPan to screenTilt
            VideoRotation.UPSIDE_DOWN -> -screenPan to -screenTilt
            // Картинка повёрнута по часовой: верх кадра камеры — справа на экране, левая сторона — вверху
            VideoRotation.RIGHT_90 -> -screenTilt to screenPan
            VideoRotation.LEFT_90 -> screenTilt to -screenPan
        }
        // «+ 0f» превращает −0.0 (результат смены знака у нуля) в 0.0, чтобы в команду не попадало «-0.0»
        return ((if (ptzInvertPan) -pan else pan) + 0f) to ((if (ptzInvertTilt) -tilt else tilt) + 0f)
    }

    /** Выбранный поток из streamProfiles, если он настроен для этой камеры */
    val activeStreamProfile: StreamProfile?
        get() = streamProfiles.firstOrNull { it.id == activeStreamProfileId } ?: streamProfiles.firstOrNull()

    private val formattedRtspPath: String
        get() {
            val path = activeStreamProfile?.rtspPath ?: rtspPath
            return if (path.startsWith("/")) path else "/$path"
        }
}

sealed class PtzCommand {
    data class Move(val pan: Float, val tilt: Float, val speed: Float = 0.5f) : PtzCommand()
    data class Zoom(val level: Float) : PtzCommand() // > 0: Zoom In, < 0: Zoom Out
    data class Focus(val direction: FocusDirection) : PtzCommand()
    /** Непрерывная фокусировка: < 0 — ближе, > 0 — дальше; до команды FocusStop */
    data class FocusMove(val speed: Float) : PtzCommand()
    object FocusStop : PtzCommand()
    /** token — идентификатор положения в камере (ONVIF назначает его сам при сохранении) */
    data class GoToPreset(val presetId: Int, val token: String) : PtzCommand()
    data class SavePreset(val presetId: Int) : PtzCommand()
    data class SetDayNightMode(val mode: DayNightMode) : PtzCommand()
    object Stop : PtzCommand()
}

enum class FocusDirection {
    NEAR, FAR, AUTO
}

enum class DayNightMode(val displayName: String) {
    AUTO("Авто"),
    DAY("День (Цвет)"),
    NIGHT("Ночь (ИК)");

    companion object {
        fun fromString(value: String?): DayNightMode = when (value?.trim()?.uppercase()) {
            "DAY", "ON", "COLOR" -> DAY
            "NIGHT", "OFF", "BLACKWHITE", "BW" -> NIGHT
            else -> AUTO
        }
    }
}