package com.example.denmediaipcams.network

import android.util.Log
import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Один интервал расписания камеры XMEye в строке «1 00:00:00-24:00:00» ([enabled] — первое число) */
internal data class DvripSection(val enabled: Boolean, val startMinute: Int, val endMinute: Int)

/** Расписание в виде строк конфига камеры: интервалы («1 00:00:00-24:00:00») и их маски («0x00000007»), по строке на день */
internal data class DvripScheduleRows(val sections: List<List<String>>, val masks: List<List<String>>)

/**
 * Разбор и сборка значений конфигов записи камер XMEye/DVRIP (проверено на XM530V200). Работает с обычными типами
 * Kotlin, без org.json, поэтому проверяется юнит-тестами. Что установлено на живой камере:
 * - `Record.[0]`: `TimeSection` и `Mask` — по 7 строк (дни) × 6 интервалов. Строка 0 — воскресенье, 1 — понедельник,
 *   … 6 — суббота (в модели приложения 0 — понедельник). Интервал «E ЧЧ:ММ:СС-ЧЧ:ММ:СС»: E — включён ли (0/1), конец
 *   суток — «24:00:00». Маска интервала: бит 0 (1) — обычная запись (файл [R]), бит 1 (2) — запись по движению
 *   (файл [M]), бит 2 (4) — по тревоге; у камеры по умолчанию 0x7. Нулевая маска — интервал ничего не записывает
 *   (запись останавливается сразу). Приложение пишет 0x7 для «постоянно» и 0x2 для «по движению».
 * - `RecordMode`: «ConfigRecord» — по расписанию, «ManualRecord» — пишет всегда независимо от расписания (файл [H]),
 *   «ClosedRecord» — не писать (останавливается сразу).
 * - `Detect.MotionDetect.[0]`: `Level` 1..6 (по принятому в XMEye порядку больше — чувствительнее; на камере это
 *   не проверялось), `Region` — 32 слова, а сетка камеры 7×7 (возможность `MotionArea`: GridColumn=7, GridRow=7).
 *   Порядок ячеек (строка сверху вниз — слово, столбец слева направо — бит, младшие 7 бит) принят по умолчанию и на
 *   живом движении не проверен, поэтому правка зон в драйвере выключена ([DvripRecordingDriver.ZONES_EDITABLE]).
 */
internal object DvripRecordingCodec {

    const val DAYS = 7
    const val MASK_REGULAR = 0x1
    const val MASK_MOTION = 0x2
    const val MASK_ALARM = 0x4
    const val MASK_ALL = 0x7

    const val SENSITIVITY_MAX = 6
    const val PRE_RECORD_MAX_SEC = 30
    const val GRID_COLUMNS = 7
    const val GRID_ROWS = 7

    const val MODE_CONFIG = "ConfigRecord"
    const val MODE_MANUAL = "ManualRecord"
    const val MODE_CLOSED = "ClosedRecord"
    const val OVERWRITE_ON = "OverWrite"
    const val OVERWRITE_OFF = "StopRecord"

    private const val EMPTY_SECTION = "0 00:00:00-24:00:00"
    private const val FULL_SECTION = "1 00:00:00-24:00:00"
    private val SECTION = Regex("""^\s*(\d+)\s+(\d{1,2}):(\d{2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2}):(\d{2})\s*$""")
    private val SLOT_ORDER = compareBy<RecordSlot>({ it.startMinute }, { it.endMinute })

    /** Строка камеры (0 — воскресенье) для дня модели (0 — понедельник) и обратно */
    fun cameraRow(day: Int): Int = (day + 1) % DAYS

    fun modelDay(row: Int): Int = (row + DAYS - 1) % DAYS

    /** Число в виде «0x00000007» — так камера хранит маски */
    fun hex32(value: Long): String = "0x%08X".format(value and 0xFFFFFFFFL)

    fun hex32(value: Int): String = hex32(value.toLong())

    /** Число из «0x00000007» (или просто из «7»); null — не число */
    fun parseNumber(text: String?): Long? =
        runCatching { java.lang.Long.decode(text?.trim().orEmpty()) }.getOrNull()

    /** Булево значение конфига: камера отдаёт true/false, но у части полей — числа или шестнадцатеричные строки */
    fun parseFlag(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> parseNumber(raw)?.let { it != 0L } ?: raw.toBooleanStrictOrNull()
        else -> null
    }

    /** Разбор интервала «1 00:00:00-24:00:00»; секунды отбрасываются (приложение работает с минутами). null — не интервал */
    fun parseSection(text: String): DvripSection? {
        val m = SECTION.matchEntire(text) ?: return null
        val g = m.groupValues
        val start = g[2].toInt() * 60 + g[3].toInt()
        val end = (g[5].toInt() * 60 + g[6].toInt()).coerceAtMost(WeekSchedule.MINUTES_PER_DAY)
        return DvripSection(enabled = (g[1].toIntOrNull() ?: 0) != 0, startMinute = start, endMinute = end)
    }

    fun formatSection(enabled: Boolean, startMinute: Int, endMinute: Int): String =
        "%d %02d:%02d:00-%02d:%02d:00".format(if (enabled) 1 else 0, startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)

    /** Тип записи по маске интервала; null — маска ничего не записывает. Бит «обычная запись» главнее остальных */
    fun kindOf(mask: Int): RecordKind? = when {
        mask and MASK_REGULAR != 0 -> RecordKind.ALWAYS
        mask and (MASK_MOTION or MASK_ALARM) != 0 -> RecordKind.MOTION
        else -> null
    }

    /** Маска, которую приложение пишет для типа записи (обычная — как у камеры по умолчанию, все три бита) */
    fun maskOf(kind: RecordKind): Int = when (kind) {
        RecordKind.ALWAYS -> MASK_ALL
        RecordKind.MOTION -> MASK_MOTION
    }

    /** Интервалы одного дня; null — строки не разобрались. Выключенные и с нулевой маской интервалы пропускаются */
    fun parseDay(sections: List<String>, masks: List<String>): List<RecordSlot>? {
        val slots = mutableListOf<RecordSlot>()
        for (i in sections.indices) {
            val section = parseSection(sections[i]) ?: return null
            val mask = (if (i < masks.size) parseNumber(masks[i]) else 0L) ?: return null
            val kind = kindOf(mask.toInt()) ?: continue
            if (!section.enabled || section.endMinute <= section.startMinute) continue
            if (section.startMinute >= WeekSchedule.MINUTES_PER_DAY) continue
            slots += RecordSlot(section.startMinute, section.endMinute, kind)
        }
        return slots.sortedWith(SLOT_ORDER)
    }

    /** Недельное расписание из `TimeSection` и `Mask` камеры (строки — с воскресенья); null — формат не тот */
    fun parseSchedule(sections: List<List<String>>, masks: List<List<String>>): WeekSchedule? {
        if (sections.size != DAYS || masks.size != DAYS) return null
        val days = MutableList(DAYS) { emptyList<RecordSlot>() }
        for (row in 0 until DAYS) {
            days[modelDay(row)] = parseDay(sections[row], masks[row]) ?: return null
        }
        return WeekSchedule(days)
    }

    /**
     * Строки конфига для расписания [wanted]. Дни, которые уже записаны в камере как нужно, остаются строками камеры
     * как есть (маски и пустые интервалы не меняются). null — формат конфига не тот или в какой-то день интервалов
     * больше, чем их умеет камера.
     */
    fun buildSchedule(existingSections: List<List<String>>, existingMasks: List<List<String>>, wanted: WeekSchedule): DvripScheduleRows? {
        if (existingSections.size != DAYS || existingMasks.size != DAYS) return null
        val sections = mutableListOf<List<String>>()
        val masks = mutableListOf<List<String>>()
        for (row in 0 until DAYS) {
            val slotCount = existingSections[row].size
            if (slotCount == 0 || existingMasks[row].size != slotCount) return null
            val slots = wanted.days[modelDay(row)].sortedWith(SLOT_ORDER)
            if (parseDay(existingSections[row], existingMasks[row]) == slots) {
                sections.add(existingSections[row])
                masks.add(existingMasks[row])
                continue
            }
            if (slots.size > slotCount) return null
            sections.add(List(slotCount) { i -> slots.getOrNull(i)?.let { formatSection(true, it.startMinute, it.endMinute) } ?: EMPTY_SECTION })
            masks.add(List(slotCount) { i -> hex32(slots.getOrNull(i)?.let { maskOf(it.kind) } ?: 0) })
        }
        return DvripScheduleRows(sections, masks)
    }

    /** Простой режим по `RecordMode` и расписанию */
    fun modeOf(recordMode: String?, schedule: WeekSchedule): RecordMode = when (recordMode) {
        MODE_CLOSED -> RecordMode.OFF
        MODE_MANUAL -> RecordMode.ALWAYS
        else -> schedule.deriveMode()
    }

    /** Нужна ли режиму или расписанию запись по движению (тогда должна работать детекция и её обработчик) */
    fun usesMotion(mode: RecordMode, schedule: WeekSchedule?): Boolean =
        mode == RecordMode.MOTION ||
            (mode == RecordMode.CUSTOM && schedule?.days?.any { day -> day.any { it.kind == RecordKind.MOTION } } == true)

    /** Перезапись старых записей: «OverWrite» — да, «StopRecord» — остановить запись при заполнении карты */
    fun parseOverwrite(text: String?): Boolean? = when (text) {
        OVERWRITE_ON -> true
        OVERWRITE_OFF -> false
        else -> null
    }

    // --- Детекция движения ---

    /** Level камеры (1..6, больше — чувствительнее) в чувствительность приложения и обратно: шкалы совпадают */
    fun sensitivityOf(level: Int): Int = level.coerceIn(1, SENSITIVITY_MAX)

    fun levelOf(sensitivity: Int): Int = sensitivity.coerceIn(1, SENSITIVITY_MAX)

    /** Зоны детекции из `Region`: сетка 7×7, ячейка (строка, столбец) — бит «столбец» слова «строка»; null — формат не тот */
    fun regionToZones(region: List<String>): MotionZones? {
        if (region.size < GRID_ROWS) return null
        val cells = mutableSetOf<Int>()
        for (row in 0 until GRID_ROWS) {
            val word = parseNumber(region[row]) ?: return null
            for (col in 0 until GRID_COLUMNS) {
                if ((word shr col) and 1L != 0L) cells += row * GRID_COLUMNS + col
            }
        }
        return MotionZones(GRID_COLUMNS, GRID_ROWS, cells)
    }

    /**
     * `Region` для зон [zones]: меняются только биты сетки (7 младших бит первых 7 слов), остальное остаётся как
     * в камере [current] (у новой камеры там 0xFFFFFFFF). null — зоны не той сетки или [current] не разобрался.
     */
    fun zonesToRegion(zones: MotionZones, current: List<String>): List<String>? {
        if (zones.columns != GRID_COLUMNS || zones.rows != GRID_ROWS || current.size < GRID_ROWS) return null
        val gridBits = (1L shl GRID_COLUMNS) - 1
        val result = mutableListOf<String>()
        for ((row, text) in current.withIndex()) {
            if (row >= GRID_ROWS) {
                result += text
                continue
            }
            val old = parseNumber(text) ?: return null
            var bits = 0L
            for (col in 0 until GRID_COLUMNS) {
                if (row * GRID_COLUMNS + col in zones.cells) bits = bits or (1L shl col)
            }
            result += hex32((old and gridBits.inv()) or bits)
        }
        return result
    }

    /** Включена ли у обработчика события запись: `RecordEnable` и бит канала 0 в `RecordMask` */
    fun recordsOnEvent(recordEnable: Boolean, recordMask: String?): Boolean =
        recordEnable && ((parseNumber(recordMask) ?: 0L) and 1L) != 0L

    /** `RecordMask` с включённым каналом 0 (остальные биты не трогаются) */
    fun withChannelBit(recordMask: String?): String = hex32((parseNumber(recordMask) ?: 0L) or 1L)

    /** Охватывает ли расписание обработчика события все сутки каждого дня (иначе движение вне окна не записывается) */
    fun coversWholeWeek(sections: List<List<String>>): Boolean = sections.size == DAYS && sections.all { day ->
        day.any { text ->
            val section = parseSection(text)
            section != null && section.enabled && section.startMinute == 0 && section.endMinute == WeekSchedule.MINUTES_PER_DAY
        }
    }

    /** Расписание обработчика «все сутки каждый день» такого же размера, как [existing] */
    fun wholeWeekSections(existing: List<List<String>>): List<List<String>> =
        existing.map { day -> List(day.size) { i -> if (i == 0) FULL_SECTION else EMPTY_SECTION } }
}

/**
 * Запись на SD-карту XMEye/DVRIP-камеры: режим и недельное расписание (Record.[0]), предзапись, перезапись старых
 * записей (General.General), детекция движения (Detect.MotionDetect.[0]) и детекция человека
 * (Detect.HumanDetection.[0]). Каждый конфиг читается и пишется целиком, как его вернула камера, изменяются только
 * нужные поля. Вход один на всё применение, запросы идут по одному с паузами; любая ошибка связи или входа — «камера
 * не умеет/не отвечает» (см. [DvripClient]).
 */
internal class DvripRecordingDriver(private val camera: IpCamera) : RecordingDriver {

    private val client = DvripClient(camera)
    private var loggedIn = false

    private suspend fun <T> session(block: suspend () -> T): T? = try {
        if (!loggedIn) {
            client.login()
            loggedIn = true
        }
        block()
    } catch (e: ArchiveException) {
        Log.w(TAG, "${camera.name}: DVRIP: ${e.message}")
        null
    }

    override suspend fun read(): RecordingSettings? = session { readSettings() }

    override suspend fun write(from: RecordingSettings, to: RecordingSettings): Boolean =
        session { writeSettings(from, to) } == true

    override fun close() = client.close()

    /** Пауза между запросами: прошивка слабая, не нужно заваливать её подряд идущими командами */
    private suspend fun pause() = delay(REQUEST_PAUSE_MS)

    private suspend fun readSettings(): RecordingSettings? {
        val record = client.getConfig(RECORD) ?: return null
        val codec = DvripRecordingCodec
        val schedule = codec.parseSchedule(rowsOf(record.optJSONArray("TimeSection")), rowsOf(record.optJSONArray("Mask")))
            ?: return null
        val mode = codec.modeOf(record.optString("RecordMode"), schedule)
        val preRecord = record.takeIf { it.has("PreRecord") }?.optInt("PreRecord")

        pause()
        val overwrite = client.getConfig(GENERAL)?.let { codec.parseOverwrite(it.optString("OverWrite")) }
        pause()
        val motion = client.getConfig(MOTION)?.let { motionOf(it) }
        pause()
        val person = client.getConfig(HUMAN)?.takeIf { it.has("Enable") }?.let { codec.parseFlag(it.opt("Enable")) }

        val kinds = if (motion != null) setOf(RecordKind.ALWAYS, RecordKind.MOTION) else setOf(RecordKind.ALWAYS)
        val modes = if (motion != null) {
            setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION, RecordMode.CUSTOM)
        } else {
            setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.CUSTOM)
        }
        return RecordingSettings(
            mode = mode,
            supportedModes = modes,
            schedule = schedule,
            scheduleSlotsPerDay = record.optJSONArray("TimeSection")?.optJSONArray(0)?.length() ?: 0,
            scheduleKinds = kinds,
            preRecordSec = preRecord,
            preRecordMaxSec = codec.PRE_RECORD_MAX_SEC,
            overwrite = overwrite,
            motion = motion,
            person = person
        )
    }

    private fun motionOf(config: JSONObject): MotionSettings? {
        val codec = DvripRecordingCodec
        if (!config.has("Enable") || !config.has("Level")) return null
        val enabled = codec.parseFlag(config.opt("Enable")) ?: return null
        return MotionSettings(
            enabled = enabled,
            sensitivity = codec.sensitivityOf(config.optInt("Level", 3)),
            sensitivityMax = codec.SENSITIVITY_MAX,
            zones = if (ZONES_EDITABLE) codec.regionToZones(stringsOf(config.optJSONArray("Region"))) else null
        )
    }

    private suspend fun writeSettings(from: RecordingSettings, to: RecordingSettings): Boolean {
        val codec = DvripRecordingCodec
        val wantsMotion = codec.usesMotion(to.mode, to.schedule)
        if (wantsMotion && to.motion == null && from.motion == null) return false

        // Сначала детекция движения: режим «по движению» не должен остаться без работающей детекции
        if (!writeMotion(from, to, wantsMotion)) return false
        pause()
        if (!writeRecord(from, to)) return false
        pause()
        if (to.overwrite != null && to.overwrite != from.overwrite) {
            if (!edit(GENERAL) { it.put("OverWrite", if (to.overwrite) codec.OVERWRITE_ON else codec.OVERWRITE_OFF) }) return false
            pause()
        }
        if (to.person != null && to.person != from.person) {
            if (!edit(HUMAN) { it.put("Enable", to.person) }) return false
        }
        return true
    }

    /** Режим, расписание и предзапись (Record.[0]); ничего не пишет, если менять нечего */
    private suspend fun writeRecord(from: RecordingSettings, to: RecordingSettings): Boolean {
        val codec = DvripRecordingCodec
        val modeChanged = to.mode != from.mode
        val schedule = if (to.mode == RecordMode.CUSTOM) to.schedule else WeekSchedule.forMode(to.mode).takeIf { to.mode != RecordMode.OFF }
        val scheduleChanged = schedule != null && (modeChanged || schedule != from.schedule)
        val preRecord = to.preRecordSec?.takeIf { it != from.preRecordSec }?.coerceIn(0, codec.PRE_RECORD_MAX_SEC)
        if (!modeChanged && !scheduleChanged && preRecord == null) return true

        val record = client.getConfig(RECORD) ?: return false
        if (modeChanged) {
            record.put("RecordMode", if (to.mode == RecordMode.OFF) codec.MODE_CLOSED else codec.MODE_CONFIG)
        }
        if (scheduleChanged && schedule != null) {
            val rows = codec.buildSchedule(rowsOf(record.optJSONArray("TimeSection")), rowsOf(record.optJSONArray("Mask")), schedule)
                ?: return false
            record.put("TimeSection", jsonRows(rows.sections))
            record.put("Mask", jsonRows(rows.masks))
        }
        if (preRecord != null && record.has("PreRecord")) record.put("PreRecord", preRecord)
        return client.setConfig(RECORD, record)
    }

    /**
     * Детекция движения (Detect.MotionDetect.[0]): включение, чувствительность, зоны. Если режиму нужна запись по
     * движению ([wantsMotion]), детекция включается, а её обработчик события записывает видео: RecordEnable, бит
     * канала в RecordMask и расписание обработчика на все сутки.
     */
    private suspend fun writeMotion(from: RecordingSettings, to: RecordingSettings, wantsMotion: Boolean): Boolean {
        val codec = DvripRecordingCodec
        val wanted = to.motion
        if (wanted == null && !wantsMotion) return true
        val config = client.getConfig(MOTION) ?: return false
        val before = config.toString()

        if (wanted != null && (wanted.enabled != from.motion?.enabled)) config.put("Enable", wanted.enabled)
        if (wantsMotion) config.put("Enable", true)
        if (wanted != null && wanted.sensitivity != from.motion?.sensitivity) config.put("Level", codec.levelOf(wanted.sensitivity))
        val zones = wanted?.zones
        if (ZONES_EDITABLE && zones != null && zones.cells != from.motion?.zones?.cells) {
            val region = codec.zonesToRegion(zones, stringsOf(config.optJSONArray("Region"))) ?: return false
            config.put("Region", JSONArray(region))
        }
        if (wantsMotion) {
            val handler = config.optJSONObject("EventHandler") ?: return false
            if (!codec.recordsOnEvent(handler.optBoolean("RecordEnable", false), handler.opt("RecordMask")?.toString())) {
                handler.put("RecordEnable", true)
                handler.put("RecordMask", codec.withChannelBit(handler.opt("RecordMask")?.toString()))
            }
            val sections = rowsOf(handler.optJSONArray("TimeSection"))
            if (!codec.coversWholeWeek(sections)) handler.put("TimeSection", jsonRows(codec.wholeWeekSections(sections)))
        }
        if (config.toString() == before) return true
        return client.setConfig(MOTION, config)
    }

    /** Читает конфиг [name], даёт [change] изменить его и пишет обратно, если он изменился */
    private suspend fun edit(name: String, change: (JSONObject) -> Unit): Boolean {
        val config = client.getConfig(name) ?: return false
        val before = config.toString()
        change(config)
        return config.toString() == before || client.setConfig(name, config)
    }

    override fun expectedAfterWrite(to: RecordingSettings): RecordingSettings {
        val codec = DvripRecordingCodec
        val motion = to.motion?.let { m ->
            m.copy(
                enabled = m.enabled || codec.usesMotion(to.mode, to.schedule),
                sensitivity = codec.sensitivityOf(m.sensitivity)
            )
        }
        return to.copy(motion = motion, preRecordSec = to.preRecordSec?.coerceIn(0, codec.PRE_RECORD_MAX_SEC))
    }

    private fun stringsOf(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { i -> array.opt(i)?.toString() ?: "" }

    private fun rowsOf(array: JSONArray?): List<List<String>> =
        if (array == null) emptyList() else List(array.length()) { i -> stringsOf(array.optJSONArray(i)) }

    private fun jsonRows(rows: List<List<String>>): JSONArray {
        val result = JSONArray()
        for (row in rows) result.put(JSONArray(row))
        return result
    }

    companion object {
        /**
         * Можно ли менять зоны детекции. Сетка 7×7 известна от камеры (MotionArea), но какой бит какой ячейке
         * соответствует (строка 0 — верх? бит 0 — левый столбец?), не проверено движением в кадре: включать после
         * проверки (задать зону в одном углу, пройти перед камерой, убедиться, что тревога идёт только там).
         */
        const val ZONES_EDITABLE = false

        private const val TAG = "DvripRecording"
        private const val RECORD = "Record.[0]"
        private const val GENERAL = "General.General"
        private const val MOTION = "Detect.MotionDetect.[0]"
        private const val HUMAN = "Detect.HumanDetection.[0]"
        private const val REQUEST_PAUSE_MS = 250L
    }
}
