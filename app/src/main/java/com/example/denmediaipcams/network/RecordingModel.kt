package com.example.denmediaipcams.network

/**
 * Запись камеры на её SD-карту и детекция движения: общая для всех протоколов (Dahua, Hipcam, XMEye) модель
 * настроек. Сами настройки живут в камере и читаются из неё каждый раз — в приложении они не сохраняются.
 * Поля, которые камера не поддерживает, равны null (экран их не показывает).
 */

/** Что камера записывает. [CUSTOM] — расписание не сводится к трём простым режимам (только чтение) */
enum class RecordMode(val title: String) {
    OFF("Не записывать"),
    ALWAYS("Постоянно"),
    MOTION("По движению"),
    CUSTOM("По расписанию")
}

/** Тип записи в интервале расписания */
enum class RecordKind(val title: String) {
    ALWAYS("Постоянно"),
    MOTION("По движению")
}

/** Интервал расписания: минуты от начала суток, 0..1440 (1440 — конец суток), [startMinute] < [endMinute] */
data class RecordSlot(val startMinute: Int, val endMinute: Int, val kind: RecordKind)

/**
 * Недельное расписание записи: [days] — семь списков интервалов, индекс 0 — понедельник, 6 — воскресенье
 * (драйвер сам переводит в нумерацию дней своей камеры). Пустой список — в этот день не записывается.
 */
data class WeekSchedule(val days: List<List<RecordSlot>>) {

    init {
        require(days.size == 7) { "В расписании должно быть 7 дней" }
    }

    /** Простой режим, которому равно расписание, или [RecordMode.CUSTOM] */
    fun deriveMode(): RecordMode {
        val flat = days.map { slots -> slots.sortedBy { it.startMinute } }
        if (flat.all { it.isEmpty() }) return RecordMode.OFF
        fun uniform(kind: RecordKind) = flat.all { it.size == 1 && it[0] == RecordSlot(0, MINUTES_PER_DAY, kind) }
        return when {
            uniform(RecordKind.ALWAYS) -> RecordMode.ALWAYS
            uniform(RecordKind.MOTION) -> RecordMode.MOTION
            else -> RecordMode.CUSTOM
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 1440

        /** Расписание простого режима: [RecordMode.CUSTOM] здесь невозможен — расписание задаётся вручную */
        fun forMode(mode: RecordMode): WeekSchedule {
            val slots = when (mode) {
                RecordMode.OFF -> emptyList()
                RecordMode.ALWAYS -> listOf(RecordSlot(0, MINUTES_PER_DAY, RecordKind.ALWAYS))
                RecordMode.MOTION -> listOf(RecordSlot(0, MINUTES_PER_DAY, RecordKind.MOTION))
                RecordMode.CUSTOM -> throw IllegalArgumentException("Для режима CUSTOM расписание задаётся вручную")
            }
            return WeekSchedule(List(7) { slots })
        }
    }
}

/**
 * Зоны детекции движения сеткой [columns]×[rows]: [cells] — номера выбранных ячеек (строка * columns + столбец,
 * строки сверху вниз, столбцы слева направо). Камеры, у которых зоны — прямоугольники, приводятся к сетке.
 */
data class MotionZones(val columns: Int, val rows: Int, val cells: Set<Int>)

/**
 * Детекция движения. [sensitivity] — от 1 до [sensitivityMax], больше — чувствительнее (драйвер сам
 * переводит в шкалу камеры). [zones] == null — камера не даёт менять зоны.
 */
data class MotionSettings(
    val enabled: Boolean,
    val sensitivity: Int,
    val sensitivityMax: Int,
    val zones: MotionZones?
)

/**
 * Всё, что камера сообщила о записи и детекции. Поля-возможности ([supportedModes], [scheduleSlotsPerDay],
 * [preRecordMaxSec]) только описывают камеру и при записи игнорируются.
 */
data class RecordingSettings(
    val mode: RecordMode,
    /** Режимы, которые камера умеет выбирать (CUSTOM входит, только если есть расписание) */
    val supportedModes: Set<RecordMode>,
    /** null — расписания в камере нет (или его формат неизвестен): доступны только [supportedModes] */
    val schedule: WeekSchedule?,
    /** Сколько интервалов в сутки позволяет камера (0 — расписания нет) */
    val scheduleSlotsPerDay: Int,
    /** Какие типы записи можно ставить в интервалах расписания */
    val scheduleKinds: Set<RecordKind>,
    /** Предзапись, секунд; null — камера не поддерживает */
    val preRecordSec: Int?,
    val preRecordMaxSec: Int,
    /** Перезаписывать старые записи, когда карта заполнена; null — камера не поддерживает */
    val overwrite: Boolean?,
    val motion: MotionSettings?,
    /** Детекция людей/человека (события «человек в кадре»); null — камера не поддерживает */
    val person: Boolean?
)

/** Режим CUSTOM с расписанием, равным простому режиму, — это и есть простой режим (так его и вернёт камера) */
internal fun RecordingSettings.normalized(): RecordingSettings =
    if (mode == RecordMode.CUSTOM && schedule != null) copy(mode = schedule.deriveMode()) else this

/** Один протокол управления записью камеры (Dahua, Hipcam, XMEye/DVRIP) */
internal interface RecordingDriver {

    /** Текущие настройки; null — камера не отвечает или этим способом не управляется */
    suspend fun read(): RecordingSettings?

    /**
     * Записывает в камеру [to]; [from] — то, что в камере сейчас. Пишутся только отличающиеся поля.
     * Если [RecordingSettings.mode] не CUSTOM, расписание берётся из [WeekSchedule.forMode], а не из
     * [RecordingSettings.schedule]. false — камера отклонила запись или не ответила.
     */
    suspend fun write(from: RecordingSettings, to: RecordingSettings): Boolean

    /**
     * То, что [read] должен вернуть после успешной записи [to]. Совпадает с [to], если камера хранит
     * настройки точно; драйвер переопределяет, если приводит их к своим возможностям (например, прямоугольные
     * зоны, округление чувствительности).
     */
    fun expectedAfterWrite(to: RecordingSettings): RecordingSettings = to

    /** Пишет ли камера в эту секунду; по умолчанию камера этого не сообщает */
    suspend fun liveRecording(): LiveRecording = LiveRecording.NOT_REPORTED

    fun close() {}
}
