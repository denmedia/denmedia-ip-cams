package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.delay

/**
 * Запись на SD-карту, расписание, детекция движения и людей у Dahua (HTTP CGI configManager). Проверено на
 * DH-SD-H2C-0400B; у других прошивок часть таблиц может отсутствовать — тогда соответствующие поля null.
 *
 * Где что лежит:
 * - Record[0]: TimeSection[день][интервал] = «маска ЧЧ:ММ:СС-ЧЧ:ММ:СС» (маска: бит 0 — запись постоянно, бит 1 — по
 *   движению, бит 2 — по тревоге; 0 — интервал не используется), PreRecord — предзапись, секунд;
 * - RecordMode[0].Mode: 0 — по расписанию, 1 — запись включена вручную (всегда), 2 — запись остановлена;
 * - StorageGroup[ReadWrite].OverWrite — перезапись старых файлов;
 * - MotionDetect[0]: Enable, MotionDetectWindow[0] (Sensitive 0..100, Region — маска зон), EventHandler (что делать
 *   при движении: RecordEnable и график срабатывания TimeSection);
 * - SmartMotionDetect[0]: Enable + ObjectTypes.Human — события «человек в кадре».
 *
 * Запись по движению и события людей без включённой детекции не работают, поэтому при записи расписания с интервалом
 * «по движению» (или person = true) детекция включается принудительно ([DahuaRecordingFormat.expectedAfterWrite]
 * это учитывает), а у MotionDetect проверяется связка записи (RecordEnable, график срабатывания на всю неделю).
 * Камера обрывает соединение на строке запроса около 1 КБ, поэтому длинные записи уходят несколькими запросами.
 */
internal class DahuaRecordingDriver(private val camera: IpCamera) : RecordingDriver {

    /** Индекс группы хранения «ReadWrite» (запись на карту), найденный при последнем чтении */
    private var storageGroup = 0

    private fun get(path: String) = CameraHttp.request(
        "${DahuaApi.baseUrl(camera)}/cgi-bin/$path", user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS
    )

    /** Таблица настроек камеры; null — камера её не знает (400) или не ответила */
    private suspend fun readTable(name: String): Map<String, String>? {
        delay(PAUSE_MS)
        val response = get("configManager.cgi?action=getConfig&name=$name")
        if (!response.isSuccess) return null
        return DahuaRecordingFormat.parseTable(response.body).takeIf { it.isNotEmpty() }
    }

    override suspend fun read(): RecordingSettings? {
        val record = readTable("Record") ?: return null
        val tables = DahuaTables(
            record = record,
            mode = readTable("RecordMode"),
            storage = readTable("StorageGroup"),
            motion = readTable("MotionDetect"),
            smart = readTable("SmartMotionDetect")
        )
        tables.storage?.let { storageGroup = DahuaRecordingFormat.storageGroupIndex(it) ?: 0 }
        return DahuaRecordingFormat.settingsFrom(tables)
    }

    override suspend fun write(from: RecordingSettings, to: RecordingSettings): Boolean {
        val plan = DahuaRecordingFormat.planWrite(from, to, storageGroup) ?: return false
        val tables = plan.fields.toMutableMap()
        if (plan.needsRecordLink) {
            // Запись по движению работает, только если в самой детекции включена запись и она взведена на всё время
            val motion = readTable("MotionDetect") ?: return false
            val fixes = DahuaRecordingFormat.recordLinkFixes(motion)
            if (fixes.isNotEmpty()) tables["MotionDetect"] = tables["MotionDetect"].orEmpty() + fixes
        }
        for (table in WRITE_ORDER) {
            val fields = tables[table] ?: continue
            for (chunk in DahuaRecordingFormat.chunkFields(fields)) {
                delay(PAUSE_MS)
                val response = get(DahuaRecordingFormat.setRequest(chunk))
                if (!response.isSuccess || !response.body.trim().startsWith("OK", ignoreCase = true)) return false
            }
        }
        return true
    }

    override fun expectedAfterWrite(to: RecordingSettings): RecordingSettings = DahuaRecordingFormat.expectedAfterWrite(to)

    private companion object {
        const val TIMEOUT_MS = 6_000
        const val PAUSE_MS = 150L

        // Детекция раньше расписания: если запись оборвётся на середине, лучше лишние события, чем расписание «по движению» без детекции
        val WRITE_ORDER = listOf("StorageGroup", "MotionDetect", "SmartMotionDetect", "RecordMode", "Record")
    }
}

/** Ответы getConfig нужных таблиц (ключи вида «Record[0].PreRecord»); null — таблицы в камере нет */
internal class DahuaTables(
    val record: Map<String, String>,
    val mode: Map<String, String>?,
    val storage: Map<String, String>?,
    val motion: Map<String, String>?,
    val smart: Map<String, String>?
)

/** Что надо записать в камеру: таблица → пары «ключ без table. → значение»; [needsRecordLink] — проверить связку записи с движением */
internal class DahuaWritePlan(
    val fields: Map<String, List<Pair<String, String>>>,
    val needsRecordLink: Boolean
)

/** Один интервал TimeSection камеры: маска, минуты от начала суток (конец суток — 1440) */
internal data class DahuaSlot(val mask: Int, val startMinute: Int, val endMinute: Int)

/** Чистые функции разбора и сборки настроек Dahua (без сети) */
internal object DahuaRecordingFormat {

    const val COLUMNS = 22
    const val ROWS = 18
    const val SENSITIVITY_MAX = 10
    const val PRE_RECORD_MAX_SEC = 30

    /** Камера хранит Sensitive 0..100, приложение — уровни 1..10: уровень * 10 */
    private const val SENSITIVE_STEP = 10
    private const val MASK_ALWAYS = 1
    private const val MASK_MOTION = 2
    private const val MODE_MANUAL = 1
    private const val MODE_STOPPED = 2
    private const val EMPTY_SLOT = "0 00:00:00-23:59:59"
    private const val ARMED_ALL_DAY = "1 00:00:00-23:59:59"

    /** Камера обрывает соединение, если строка запроса длиннее примерно 1 КБ: поля уходят частями */
    private const val MAX_FIELDS_LENGTH = 760

    private const val MOTION_WINDOW = "MotionDetect[0].MotionDetectWindow[0]"
    private const val ARMING = "MotionDetect[0].EventHandler.TimeSection"

    private val SLOT_REGEX = Regex("""^(\d+)\s+(\d{1,2}):(\d{2}):(\d{2})-(\d{1,2}):(\d{2}):(\d{2})$""")
    private val GROUP_NAME_REGEX = Regex("""^StorageGroup\[(\d+)]\.Name$""")

    // ---------------- Разбор ----------------

    /** Ответ getConfig: строки «table.Record[0].PreRecord=5» → «Record[0].PreRecord» → «5» */
    fun parseTable(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in body.lineSequence()) {
            val text = line.trim()
            if (!text.startsWith("table.")) continue
            val eq = text.indexOf('=')
            if (eq < 0) continue
            result[text.substring("table.".length, eq)] = text.substring(eq + 1).trim()
        }
        return result
    }

    /** «2 00:00:00-23:59:59»; конец 23:59:59 (и 24:00:00) — конец суток. Секунды начала отбрасываются, конца — округляются вверх */
    fun parseSlot(value: String): DahuaSlot? {
        val match = SLOT_REGEX.find(value.trim()) ?: return null
        val g = match.groupValues.drop(1).map { it.toInt() }
        val end = g[4] * 60 + g[5] + if (g[6] > 0) 1 else 0
        return DahuaSlot(mask = g[0], startMinute = g[1] * 60 + g[2], endMinute = minOf(end, WeekSchedule.MINUTES_PER_DAY))
    }

    /** День приложения (0 — понедельник) → день камеры (0 — воскресенье) */
    fun dahuaDay(modelDay: Int): Int = (modelDay + 1) % 7

    private fun slotKey(dahuaDay: Int, slot: Int) = "Record[0].TimeSection[$dahuaDay][$slot]"

    /** Интервал камеры → интервал приложения: постоянная запись важнее движения; пустые и тревожные — null */
    private fun recordSlotOf(slot: DahuaSlot): RecordSlot? {
        if (slot.startMinute < 0 || slot.endMinute <= slot.startMinute) return null
        val kind = when {
            (slot.mask and MASK_ALWAYS) != 0 -> RecordKind.ALWAYS
            (slot.mask and MASK_MOTION) != 0 -> RecordKind.MOTION
            else -> return null
        }
        return RecordSlot(slot.startMinute, slot.endMinute, kind)
    }

    /** Расписание Record[0] и число интервалов в сутки; null — расписания в ответе нет. День камеры 7 (праздники) не читается */
    fun parseSchedule(record: Map<String, String>): Pair<WeekSchedule, Int>? {
        var perDay = 0
        while (record.containsKey(slotKey(0, perDay))) perDay++
        if (perDay == 0) return null
        val days = ArrayList<List<RecordSlot>>(7)
        for (modelDay in 0 until 7) {
            val slots = ArrayList<RecordSlot>()
            for (index in 0 until perDay) {
                val slot = parseSlot(record[slotKey(dahuaDay(modelDay), index)] ?: return null) ?: return null
                recordSlotOf(slot)?.let { slots.add(it) }
            }
            days.add(normalizeDay(slots))
        }
        return WeekSchedule(days) to perDay
    }

    /** Порядок по началу, пустые отброшены, соседние и пересекающиеся интервалы одного типа слиты в один */
    fun normalizeDay(slots: List<RecordSlot>): List<RecordSlot> {
        val sorted = slots
            .filter { it.startMinute >= 0 && it.startMinute < it.endMinute && it.endMinute <= WeekSchedule.MINUTES_PER_DAY }
            .sortedWith(compareBy({ it.startMinute }, { it.endMinute }))
        val merged = ArrayList<RecordSlot>()
        for (slot in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.kind == slot.kind && slot.startMinute <= last.endMinute) {
                merged[merged.size - 1] = last.copy(endMinute = maxOf(last.endMinute, slot.endMinute))
            } else {
                merged.add(slot)
            }
        }
        return merged
    }

    /** Режим: ручная запись (1) и остановка (2) важнее расписания */
    fun parseMode(table: Map<String, String>?, schedule: WeekSchedule): RecordMode =
        when (table?.get("RecordMode[0].Mode")?.toIntOrNull()) {
            MODE_MANUAL -> RecordMode.ALWAYS
            MODE_STOPPED -> RecordMode.OFF
            else -> schedule.deriveMode()
        }

    /** Индекс группы хранения «ReadWrite» (запись на карту), иначе 0, если такая группа есть */
    fun storageGroupIndex(storage: Map<String, String>): Int? {
        for ((key, value) in storage) {
            if (value != "ReadWrite") continue
            GROUP_NAME_REGEX.find(key)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 0.takeIf { storage.containsKey("StorageGroup[0].OverWrite") }
    }

    /** Sensitive камеры 0..100 → уровень 1..[SENSITIVITY_MAX] (70 → 7) */
    fun sensitivityLevel(sensitive: Int): Int = ((sensitive + SENSITIVE_STEP / 2) / SENSITIVE_STEP).coerceIn(1, SENSITIVITY_MAX)

    /** Уровень 1..[SENSITIVITY_MAX] → Sensitive камеры (7 → 70) */
    fun sensitiveOf(level: Int): Int = level.coerceIn(1, SENSITIVITY_MAX) * SENSITIVE_STEP

    /**
     * Маска зон камеры → ячейки сетки: Region — 18 строк сверху вниз, в каждой 22 младших бита, младший бит — левый
     * столбец (4194303 = 2^22 - 1 — все столбцы строки)
     */
    fun regionToCells(rows: List<Int>): Set<Int> {
        val cells = HashSet<Int>()
        for ((row, mask) in rows.withIndex()) {
            for (column in 0 until COLUMNS) {
                if (((mask shr column) and 1) == 1) cells.add(row * COLUMNS + column)
            }
        }
        return cells
    }

    /** Обратное к [regionToCells]; ячейки вне сетки 22x18 отбрасываются */
    fun cellsToRegion(cells: Set<Int>): List<Int> {
        val rows = IntArray(ROWS)
        for (cell in cells) {
            if (cell < 0 || cell >= ROWS * COLUMNS) continue
            rows[cell / COLUMNS] = rows[cell / COLUMNS] or (1 shl (cell % COLUMNS))
        }
        return rows.toList()
    }

    /** Детекция движения по первому окну MotionDetectWindow[0]; null — в камере нет такой таблицы */
    fun parseMotion(table: Map<String, String>?): MotionSettings? {
        if (table == null) return null
        val enabled = table["MotionDetect[0].Enable"]?.toBooleanStrictOrNull() ?: return null
        val sensitive = table["$MOTION_WINDOW.Sensitive"]?.toIntOrNull() ?: return null
        val rows = (0 until ROWS).mapNotNull { table["$MOTION_WINDOW.Region[$it]"]?.toIntOrNull() }
        val zones = if (rows.size == ROWS) MotionZones(COLUMNS, ROWS, regionToCells(rows)) else null
        return MotionSettings(enabled, sensitivityLevel(sensitive), SENSITIVITY_MAX, zones)
    }

    /** Люди в кадре: SmartMotionDetect включён и среди объектов есть человек; null — таблицы нет */
    fun parsePerson(table: Map<String, String>?): Boolean? {
        if (table == null) return null
        val enabled = table["SmartMotionDetect[0].Enable"]?.toBooleanStrictOrNull() ?: return null
        val human = table["SmartMotionDetect[0].ObjectTypes.Human"]?.toBooleanStrictOrNull() ?: true
        return enabled && human
    }

    fun settingsFrom(tables: DahuaTables): RecordingSettings? {
        val (schedule, perDay) = parseSchedule(tables.record) ?: return null
        val motion = parseMotion(tables.motion)
        val modes = HashSet<RecordMode>()
        modes.add(RecordMode.OFF)
        modes.add(RecordMode.ALWAYS)
        if (motion != null) modes.add(RecordMode.MOTION)
        modes.add(RecordMode.CUSTOM)
        val kinds = if (motion != null) setOf(RecordKind.ALWAYS, RecordKind.MOTION) else setOf(RecordKind.ALWAYS)
        val group = tables.storage?.let { storageGroupIndex(it) }
        return RecordingSettings(
            mode = parseMode(tables.mode, schedule),
            supportedModes = modes,
            schedule = schedule,
            scheduleSlotsPerDay = perDay,
            scheduleKinds = kinds,
            preRecordSec = tables.record["Record[0].PreRecord"]?.toIntOrNull(),
            preRecordMaxSec = PRE_RECORD_MAX_SEC,
            overwrite = group?.let { tables.storage?.get("StorageGroup[$it].OverWrite")?.toBooleanStrictOrNull() },
            motion = motion,
            person = parsePerson(tables.smart)
        )
    }

    // ---------------- Запись ----------------

    /** Интервал приложения → «маска ЧЧ:ММ:СС-ЧЧ:ММ:СС»; конец суток — 23:59:59 */
    fun encodeSlot(slot: RecordSlot): String {
        val mask = if (slot.kind == RecordKind.ALWAYS) MASK_ALWAYS else MASK_MOTION
        val end = if (slot.endMinute >= WeekSchedule.MINUTES_PER_DAY) "23:59:59" else clock(slot.endMinute)
        return "$mask ${clock(slot.startMinute)}-$end"
    }

    private fun clock(minute: Int) = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}:00"

    /** Все [perDay] интервалов суток для камеры (лишние — «0 …»); null — интервалов больше, чем камера принимает */
    fun encodeDay(slots: List<RecordSlot>, perDay: Int): List<String>? {
        val normal = normalizeDay(slots)
        if (normal.size > perDay) return null
        return normal.map { encodeSlot(it) } + List(perDay - normal.size) { EMPTY_SLOT }
    }

    /** Расписание, которое будет в камере: у простых режимов — по режиму, у CUSTOM — заданное; null — CUSTOM без расписания */
    fun effectiveSchedule(settings: RecordingSettings): WeekSchedule? =
        if (settings.mode == RecordMode.CUSTOM) settings.schedule else WeekSchedule.forMode(settings.mode)

    private fun hasMotionSlot(schedule: WeekSchedule?): Boolean =
        schedule != null && schedule.days.any { day -> day.any { it.kind == RecordKind.MOTION } }

    /**
     * Включена ли детекция движения после записи [to]: запись по движению и события людей без неё не работают, поэтому
     * включается принудительно. null — детекции движения в камере нет.
     */
    fun motionEnabledAfter(to: RecordingSettings): Boolean? {
        val motion = to.motion ?: return null
        return motion.enabled || hasMotionSlot(effectiveSchedule(to)) || to.person == true
    }

    /** Что записать, чтобы камера пришла к [to]; null — [to] камера принять не может (слишком много интервалов, чужая сетка зон) */
    fun planWrite(from: RecordingSettings, to: RecordingSettings, storageGroup: Int): DahuaWritePlan? {
        val fields = LinkedHashMap<String, List<Pair<String, String>>>()

        val storage = ArrayList<Pair<String, String>>()
        val overwrite = to.overwrite
        if (overwrite != null && overwrite != from.overwrite) storage.add("StorageGroup[$storageGroup].OverWrite" to overwrite.toString())
        if (storage.isNotEmpty()) fields["StorageGroup"] = storage

        // Режим меняется — возвращаем камеру к записи по расписанию (из ручной записи и остановки)
        if (to.mode != from.mode) fields["RecordMode"] = listOf("RecordMode[0].Mode" to "0")

        val record = ArrayList<Pair<String, String>>()
        val schedule = effectiveSchedule(to)
        val scheduleUntouched = to.mode == from.mode && to.mode != RecordMode.CUSTOM
        if (schedule != null && !scheduleUntouched) {
            for (modelDay in 0 until 7) {
                val wanted = normalizeDay(schedule.days[modelDay])
                val current = from.schedule?.days?.get(modelDay)?.let { normalizeDay(it) }
                if (wanted == current) continue
                val encoded = encodeDay(wanted, from.scheduleSlotsPerDay) ?: return null
                encoded.forEachIndexed { index, value -> record.add(slotKey(dahuaDay(modelDay), index) to value) }
            }
        }
        val preRecord = to.preRecordSec
        if (preRecord != null && preRecord != from.preRecordSec) {
            record.add("Record[0].PreRecord" to preRecord.coerceIn(0, PRE_RECORD_MAX_SEC).toString())
        }
        if (record.isNotEmpty()) fields["Record"] = record

        val motion = ArrayList<Pair<String, String>>()
        val wantedMotion = to.motion
        if (wantedMotion != null) {
            val enabled = motionEnabledAfter(to)
            if (enabled != null && enabled != from.motion?.enabled) motion.add("MotionDetect[0].Enable" to enabled.toString())
            val level = wantedMotion.sensitivity.coerceIn(1, SENSITIVITY_MAX)
            if (level != from.motion?.sensitivity) motion.add("$MOTION_WINDOW.Sensitive" to sensitiveOf(level).toString())
            val zones = wantedMotion.zones
            if (zones != null) {
                if (zones.columns != COLUMNS || zones.rows != ROWS) return null
                val wantedRows = cellsToRegion(zones.cells)
                val currentZones = from.motion?.zones?.takeIf { it.columns == COLUMNS && it.rows == ROWS }
                val currentRows = currentZones?.let { cellsToRegion(it.cells) }
                for (row in 0 until ROWS) {
                    if (currentRows == null || currentRows[row] != wantedRows[row]) {
                        motion.add("$MOTION_WINDOW.Region[$row]" to wantedRows[row].toString())
                    }
                }
            }
        }
        if (motion.isNotEmpty()) fields["MotionDetect"] = motion

        val person = to.person
        if (person != null && person != from.person) {
            val smart = ArrayList<Pair<String, String>>()
            smart.add("SmartMotionDetect[0].Enable" to person.toString())
            if (person) smart.add("SmartMotionDetect[0].ObjectTypes.Human" to "true")
            fields["SmartMotionDetect"] = smart
        }

        return DahuaWritePlan(fields, needsRecordLink = to.motion != null && hasMotionSlot(schedule))
    }

    /**
     * Что поправить в MotionDetect, чтобы движение действительно писалось: у обработчика события включена запись
     * (EventHandler.RecordEnable), она идёт с канала 0 и обработчик взведён на всю неделю целиком
     * (EventHandler.TimeSection — график срабатывания, не путать с графиком записи Record).
     */
    fun recordLinkFixes(motion: Map<String, String>): List<Pair<String, String>> {
        val fixes = ArrayList<Pair<String, String>>()
        if (motion["MotionDetect[0].EventHandler.RecordEnable"]?.toBooleanStrictOrNull() == false) {
            fixes.add("MotionDetect[0].EventHandler.RecordEnable" to "true")
        }
        val channel = motion["MotionDetect[0].EventHandler.RecordChannels[0]"]
        if (channel != null && channel != "0") fixes.add("MotionDetect[0].EventHandler.RecordChannels[0]" to "0")
        for (day in 0 until 7) {
            var perDay = 0
            while (motion.containsKey("$ARMING[$day][$perDay]")) perDay++
            if (perDay == 0) continue
            val slots = (0 until perDay).map { parseSlot(motion["$ARMING[$day][$it]"].orEmpty()) }
            val coversDay = slots.any { it != null && it.mask != 0 && it.startMinute == 0 && it.endMinute >= WeekSchedule.MINUTES_PER_DAY }
            if (coversDay) continue
            fixes.add("$ARMING[$day][0]" to ARMED_ALL_DAY)
            for (index in 1 until perDay) fixes.add("$ARMING[$day][$index]" to EMPTY_SLOT)
        }
        return fixes
    }

    /** Ключ → «Record%5B0%5D.PreRecord»: скобки в таком виде принимают и камеры, и клиенты, не разбирающие [] в адресе */
    fun encodeKey(key: String): String = key.replace("[", "%5B").replace("]", "%5D")

    private fun encodeValue(value: String): String = value.replace(" ", "%20")

    /** Запрос setConfig (относительно /cgi-bin/) с полями [fields] */
    fun setRequest(fields: List<Pair<String, String>>): String =
        "configManager.cgi?action=setConfig&" + fields.joinToString("&") { (key, value) -> "${encodeKey(key)}=${encodeValue(value)}" }

    /** Разбивает поля на запросы, у которых часть с полями не длиннее [maxLength] */
    fun chunkFields(fields: List<Pair<String, String>>, maxLength: Int = MAX_FIELDS_LENGTH): List<List<Pair<String, String>>> {
        val chunks = ArrayList<List<Pair<String, String>>>()
        var current = ArrayList<Pair<String, String>>()
        var length = 0
        for (field in fields) {
            val size = encodeKey(field.first).length + encodeValue(field.second).length + 2
            if (current.isNotEmpty() && length + size > maxLength) {
                chunks.add(current)
                current = ArrayList()
                length = 0
            }
            current.add(field)
            length += size
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    /** То, что [DahuaRecordingDriver.read] вернёт после записи [to]: слитые интервалы, режим по итоговому расписанию, принудительно включённое движение */
    fun expectedAfterWrite(to: RecordingSettings): RecordingSettings {
        val schedule = effectiveSchedule(to)?.let { s -> WeekSchedule(s.days.map { normalizeDay(it) }) }
        val mode = if (to.mode == RecordMode.CUSTOM && schedule != null) schedule.deriveMode() else to.mode
        val motion = to.motion?.let { m ->
            m.copy(
                enabled = motionEnabledAfter(to) ?: m.enabled,
                sensitivity = m.sensitivity.coerceIn(1, SENSITIVITY_MAX),
                zones = m.zones?.let { z ->
                    if (z.columns == COLUMNS && z.rows == ROWS) z.copy(cells = z.cells.filter { it in 0 until COLUMNS * ROWS }.toSet()) else z
                }
            )
        }
        return to.copy(
            mode = mode,
            schedule = schedule ?: to.schedule,
            preRecordSec = to.preRecordSec?.coerceIn(0, PRE_RECORD_MAX_SEC),
            motion = motion
        )
    }
}
