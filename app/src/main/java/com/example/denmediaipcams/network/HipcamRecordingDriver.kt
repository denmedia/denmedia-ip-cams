package com.example.denmediaipcams.network

import com.example.denmediaipcams.model.IpCamera
import kotlinx.coroutines.delay

/**
 * Запись на SD-карту, расписание и детекция движения камер Hipcam (HiSilicon hi3510): те же команды
 * `/cgi-bin/hi3510/param.cgi`, что вызывает веб-интерфейс камеры (страницы record, md, alarm, alarmsmd).
 *
 * Что есть в камере и как это отображается на общую модель:
 * - `getplanrecattr` / `setplanrecattr` -planrec_enable (1/0): запись «по расписанию» (непрерывная) включена;
 *   planrec_chn (11 — основной поток, 12 — дополнительный; смена требует перезапуска камеры), planrec_time (длина
 *   файла записи, 15..900 с) и planrec_type (формат файла) приложение не трогает;
 * - `getscheduleex&-ename=plan` / `setscheduleex&-ename=plan&-week0..-week6`: расписание непрерывной записи,
 *   семь строк по 48 символов (получасовые ячейки, 'P' — записывать, 'N' — нет); week0 — воскресенье;
 * - `getscheduleex&-ename=md`: то же для детекции движения (когда она «на охране»);
 * - `getmdalarm&-aname=record` / `setmdalarm&-aname=record&-switch=on|off`: по движению сохранять видео на SD-карту;
 * - `getmdattr` / `setmdattr&-chn=1&-name=1..4&-enable&-s&-t&-x&-y&-w&-h`: до четырёх окон детекции, чувствительность
 *   `s` 0..100 (в приложении 1..100, больше — чувствительнее), порог `t` не меняется, прямоугольник в пикселях основного
 *   потока (размер берётся из `getvencattr&-chn=11`);
 * - `getsmdattr` / `setsmdattr&-smd_enable` (1/0): «умное распознавание человека» — это и есть детекция людей.
 *
 * Режимы: «Постоянно» — непрерывная запись по расписанию plan; «По движению» — запись только по тревоге детекции
 * (record=on, расписание md, непрерывная запись выключена); в интервалах расписания «Постоянно» берётся из plan,
 * «По движению» — из md, при пересечении побеждает «Постоянно». Расписание в камере с точностью до 30 минут: границы
 * интервалов округляются до ближайших 30 минут. Окна детекции — прямоугольники, поэтому зоны сетки 22×18 записываются
 * не больше чем четырьмя прямоугольниками (если ячейки иначе не уложить, окна объединяются в охватывающие
 * прямоугольники); прочитанное при проверке записи берётся уже в том виде, в каком его хранит камера
 * ([expectedAfterWrite]). Предзаписи и перезаписи в камере нет — оба поля null.
 */
internal class HipcamRecordingDriver(private val camera: IpCamera) : RecordingDriver {

    /** Состояние камеры при первом чтении: от него считается [expectedAfterWrite] и к нему откатывается запись */
    private var firstRead: HipcamRecState? = null

    private fun get(query: String) = CameraHttp.request(
        "http://${camera.ipAddress}:${camera.effectiveHipcamPort}/cgi-bin/hi3510/param.cgi?$query",
        user = camera.username, pass = camera.password, timeoutMs = TIMEOUT_MS
    )

    private fun body(query: String): String? = get(query).takeIf { it.isSuccess }?.body

    private fun send(query: String): Boolean {
        val response = get(query)
        return response.isSuccess && response.body.contains("Succeed", ignoreCase = true)
    }

    /** Читает камеру целиком; null — камера не ответила или это не Hipcam с управлением записью */
    private fun fetch(): HipcamRecState? {
        val plan = body("cmd=getplanrecattr") ?: return null
        if ("planrec_enable" !in HipcamRecordingCodec.parseVars(plan)) return null
        return HipcamRecordingCodec.buildState(
            plan = plan,
            planSchedule = body("cmd=getscheduleex&-ename=plan"),
            motionSchedule = body("cmd=getscheduleex&-ename=md"),
            mdAttr = body("cmd=getmdattr"),
            encoder = body("cmd=getvencattr&-chn=11"),
            smd = body("cmd=getsmdattr"),
            mdAlarm = body("cmd=getmdalarm&-aname=record")
        )
    }

    override suspend fun read(): RecordingSettings? {
        val state = fetch() ?: return null
        if (firstRead == null) firstRead = state
        return HipcamRecordingCodec.toSettings(state)
    }

    override suspend fun write(from: RecordingSettings, to: RecordingSettings): Boolean {
        val base = fetch() ?: return false
        // Откат к прочитанному в самом начале восстанавливает камеру точно (в том числе геометрию окон), а не «по смыслу»
        val original = firstRead
        val target = if (original != null && to == HipcamRecordingCodec.toSettings(original)) {
            original
        } else {
            HipcamRecordingCodec.applyTo(base, to)
        }
        for (command in HipcamRecordingCodec.commandsFor(base, target)) {
            if (!send(command)) return false
            delay(WRITE_PAUSE_MS)
        }
        return true
    }

    override fun expectedAfterWrite(to: RecordingSettings): RecordingSettings {
        val base = firstRead ?: return to
        return HipcamRecordingCodec.toSettings(HipcamRecordingCodec.applyTo(base, to))
    }

    /** `getrecstatus` отвечает `rec_status="none"`, пока камера не пишет; любое другое значение считается записью */
    override suspend fun liveRecording(): LiveRecording {
        val response = get("cmd=getrecstatus")
        if (response.code == 0) return LiveRecording.UNREACHABLE
        if (!response.isSuccess) return LiveRecording.NOT_REPORTED
        return when (HipcamRecordingCodec.parseRecStatus(response.body)) {
            true -> LiveRecording.RECORDING
            false -> LiveRecording.NOT_RECORDING
            null -> LiveRecording.NOT_REPORTED
        }
    }

    private companion object {
        const val TIMEOUT_MS = 6_000
        const val WRITE_PAUSE_MS = 200L
    }
}

/** Окно детекции движения Hipcam (m1..m4): прямоугольник в пикселях основного потока, как его отдаёт камера */
internal data class HipcamWindow(
    val enabled: Boolean,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** 0..100 */
    val sensitivity: Int,
    /** Порог камеры: смысл не раскрыт, приложение его не меняет */
    val threshold: Int
)

/**
 * Всё, что приложение читает и пишет в камере Hipcam. Расписания — строки по 48 символов в нумерации камеры
 * (0 — воскресенье); null — камера расписание не отдала. [windows] null — детекции движения нет.
 */
internal data class HipcamRecState(
    val planEnabled: Boolean,
    val planWeeks: List<String>?,
    val motionWeeks: List<String>?,
    val windows: List<HipcamWindow>?,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Сохранять видео при тревоге детекции; null — переключателя в камере нет */
    val recordOnMotion: Boolean?,
    /** Распознавание человека; null — камера его не умеет */
    val person: Boolean?
) {
    /** Расписание есть, только если камера отдала оба: непрерывной записи и детекции */
    val hasSchedule: Boolean get() = planWeeks != null && motionWeeks != null
}

/** Прямоугольник из ячеек сетки зон, границы включительно */
internal data class CellRect(val c0: Int, val r0: Int, val c1: Int, val r1: Int) {
    val area: Int get() = (c1 - c0 + 1) * (r1 - r0 + 1)
}

/** Разбор ответов Hipcam и перевод между его состоянием и общей моделью [RecordingSettings]; без обращений к сети */
internal object HipcamRecordingCodec {

    /** Ячеек расписания в сутках (по 30 минут) */
    const val SLOTS = 48
    const val SLOT_MINUTES = 30

    /** Сколько окон детекции в камере */
    const val WINDOWS = 4

    /** Сетка зон в приложении */
    const val COLUMNS = 22
    const val ROWS = 18

    const val SENSITIVITY_MAX = 100

    // Камера хранит окна в ячейках сетки 1280×720 (поверх изображения любого размера) и отдаёт их в пикселях потока
    private const val GRID_X = 1280
    private const val GRID_Y = 720

    private val FULL_DAY = "P".repeat(SLOTS)

    /** Строки вида var name="value"; из ответа param.cgi */
    fun parseVars(body: String): Map<String, String> =
        Regex("""var\s+(\w+)\s*=\s*"([^"]*)"""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }

    /** Ответ getrecstatus: пишет ли камера сейчас (`none` — нет); null — в ответе нет rec_status */
    fun parseRecStatus(body: String): Boolean? =
        parseVars(body)["rec_status"]?.let { it.isNotBlank() && !it.equals("none", ignoreCase = true) }

    /** Семь строк расписания week0..week6 по [SLOTS] символов; null — ответ не расписание */
    fun parseWeeks(body: String?): List<String>? {
        val vars = body?.let { parseVars(it) } ?: return null
        val weeks = ArrayList<String>(7)
        for (day in 0 until 7) {
            val week = vars["week$day"] ?: return null
            if (week.length != SLOTS) return null
            weeks.add(week)
        }
        return weeks
    }

    /** Окна m1..m4 из ответа getmdattr; null — это не он */
    fun parseWindows(body: String?): List<HipcamWindow>? {
        val vars = body?.let { parseVars(it) } ?: return null
        val windows = ArrayList<HipcamWindow>(WINDOWS)
        for (i in 1..WINDOWS) {
            val enable = vars["m${i}_enable"] ?: return null
            val x = vars["m${i}_x"]?.toIntOrNull() ?: return null
            val y = vars["m${i}_y"]?.toIntOrNull() ?: return null
            val w = vars["m${i}_w"]?.toIntOrNull() ?: return null
            val h = vars["m${i}_h"]?.toIntOrNull() ?: return null
            val sensitivity = vars["m${i}_sensitivity"]?.toIntOrNull() ?: return null
            val threshold = vars["m${i}_threshold"]?.toIntOrNull() ?: 0
            windows.add(HipcamWindow(enable == "1", x, y, w, h, sensitivity, threshold))
        }
        return windows
    }

    /**
     * Собирает состояние из ответов камеры (null — команда не поддерживается). Окон детекции без размера кадра
     * (getvencattr основного потока) нет: зоны не из чего переводить в сетку.
     */
    fun buildState(
        plan: String?,
        planSchedule: String?,
        motionSchedule: String?,
        mdAttr: String?,
        encoder: String?,
        smd: String?,
        mdAlarm: String?
    ): HipcamRecState? {
        val planVars = plan?.let { parseVars(it) } ?: return null
        val planEnable = planVars["planrec_enable"] ?: return null
        val encoderVars = encoder?.let { parseVars(it) }
        val width = encoderVars?.get("width_1")?.toIntOrNull() ?: 0
        val height = encoderVars?.get("height_1")?.toIntOrNull() ?: 0
        val windows = if (width > 0 && height > 0) parseWindows(mdAttr) else null
        val recordOnMotion = mdAlarm?.let { parseVars(it) }?.get("md_record_switch")?.let { it == "on" }
        val person = smd?.let { parseVars(it) }?.get("smd_enable")?.let { it == "1" }
        return HipcamRecState(
            planEnabled = planEnable == "1",
            planWeeks = parseWeeks(planSchedule),
            motionWeeks = parseWeeks(motionSchedule),
            windows = windows,
            imageWidth = width,
            imageHeight = height,
            recordOnMotion = recordOnMotion,
            person = person
        )
    }

    // ---------------- состояние камеры -> модель ----------------

    fun toSettings(state: HipcamRecState): RecordingSettings {
        val schedule = scheduleOf(state)
        val motionRecordable = canRecordOnMotion(state)
        val modes = buildSet<RecordMode> {
            add(RecordMode.OFF)
            add(RecordMode.ALWAYS)
            if (motionRecordable) add(RecordMode.MOTION)
            if (state.hasSchedule) add(RecordMode.CUSTOM)
        }
        return RecordingSettings(
            mode = schedule.deriveMode(),
            supportedModes = modes,
            schedule = schedule.takeIf { state.hasSchedule },
            scheduleSlotsPerDay = if (state.hasSchedule) SLOTS else 0,
            scheduleKinds = if (motionRecordable) setOf(RecordKind.ALWAYS, RecordKind.MOTION) else setOf(RecordKind.ALWAYS),
            preRecordSec = null,
            preRecordMaxSec = 0,
            overwrite = null,
            motion = motionOf(state),
            person = state.person
        )
    }

    private fun canRecordOnMotion(state: HipcamRecState) = state.recordOnMotion != null && state.windows != null

    /**
     * Расписание, которое реально выполняет камера: непрерывная запись — ячейки plan при включённой записи;
     * по движению — ячейки md, если сохранение видео по тревоге включено и хоть одно окно детекции включено.
     */
    private fun scheduleOf(state: HipcamRecState): WeekSchedule {
        val motionActive = state.recordOnMotion == true && state.windows?.any { it.enabled } == true
        val planWeeks = if (state.hasSchedule) state.planWeeks else null
        val motionWeeks = if (state.hasSchedule) state.motionWeeks else null
        return WeekSchedule(List(7) { day ->
            val cameraDay = (day + 1) % 7 // у камеры 0 — воскресенье, в модели 0 — понедельник
            val plan = planWeeks?.get(cameraDay) ?: FULL_DAY
            val motion = motionWeeks?.get(cameraDay) ?: FULL_DAY
            slotsOf(List(SLOTS) { i ->
                when {
                    state.planEnabled && plan[i] == 'P' -> RecordKind.ALWAYS
                    motionActive && motion[i] == 'P' -> RecordKind.MOTION
                    else -> null
                }
            })
        })
    }

    /** Подряд идущие ячейки одного типа в интервалы */
    private fun slotsOf(kinds: List<RecordKind?>): List<RecordSlot> {
        val slots = ArrayList<RecordSlot>()
        var i = 0
        while (i < kinds.size) {
            val kind = kinds[i]
            if (kind == null) {
                i++
                continue
            }
            var j = i
            while (j < kinds.size && kinds[j] == kind) j++
            slots.add(RecordSlot(i * SLOT_MINUTES, j * SLOT_MINUTES, kind))
            i = j
        }
        return slots
    }

    private fun motionOf(state: HipcamRecState): MotionSettings? {
        val windows = state.windows ?: return null
        val first = windows.firstOrNull { it.enabled } ?: windows[0]
        return MotionSettings(
            enabled = windows.any { it.enabled },
            sensitivity = first.sensitivity.coerceIn(1, SENSITIVITY_MAX),
            sensitivityMax = SENSITIVITY_MAX,
            zones = MotionZones(COLUMNS, ROWS, cellsOf(windows, state.imageWidth, state.imageHeight))
        )
    }

    // ---------------- зоны: прямоугольники <-> сетка ----------------

    /** Граница [index] из [count] равных частей отрезка длиной [size] */
    private fun edge(index: Int, count: Int, size: Int) = index * size / count

    /**
     * Ячейки сетки, которые накрывают окна: включённые, а если включённых нет — все (в выключенном состоянии зоны
     * остаются в окнах). Ячейка выбрана, если окно закрывает хотя бы восьмую часть её ширины и высоты: камера
     * округляет окна до своей сетки, лишний «хвост» в несколько пикселей соседнюю ячейку не выбирает.
     */
    fun cellsOf(windows: List<HipcamWindow>, width: Int, height: Int): Set<Int> {
        val enabled = windows.filter { it.enabled }
        val used = if (enabled.isEmpty()) windows else enabled
        val cells = LinkedHashSet<Int>()
        for (row in 0 until ROWS) {
            val top = edge(row, ROWS, height)
            val bottom = edge(row + 1, ROWS, height)
            for (column in 0 until COLUMNS) {
                val left = edge(column, COLUMNS, width)
                val right = edge(column + 1, COLUMNS, width)
                if (used.any { covers(it, left, top, right, bottom) }) cells.add(row * COLUMNS + column)
            }
        }
        return cells
    }

    private fun covers(window: HipcamWindow, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val overlapX = minOf(window.x + window.w, right) - maxOf(window.x, left)
        val overlapY = minOf(window.y + window.h, bottom) - maxOf(window.y, top)
        return overlapX > 0 && overlapY > 0 && overlapX * 8 >= right - left && overlapY * 8 >= bottom - top
    }

    /**
     * Раскладывает ячейки на прямоугольники: горизонтальные отрезки строк, совпадающие по столбцам с отрезком строки
     * выше, склеиваются. Если получилось больше окон камеры, ближайшая по «лишней» площади пара заменяется
     * охватывающим прямоугольником, пока их не станет [WINDOWS]. Результат всегда накрывает все ячейки.
     */
    fun decompose(cells: Set<Int>): List<CellRect> {
        val rects = ArrayList<CellRect>()
        var previous: List<Int> = emptyList() // номера прямоугольников, дотянувшихся до предыдущей строки
        for (row in 0 until ROWS) {
            val current = ArrayList<Int>()
            var column = 0
            while (column < COLUMNS) {
                if ((row * COLUMNS + column) !in cells) {
                    column++
                    continue
                }
                var end = column
                while (end + 1 < COLUMNS && (row * COLUMNS + end + 1) in cells) end++
                val start = column
                val above = previous.firstOrNull { rects[it].c0 == start && rects[it].c1 == end }
                if (above != null) {
                    rects[above] = rects[above].copy(r1 = row)
                    current.add(above)
                } else {
                    rects.add(CellRect(start, row, end, row))
                    current.add(rects.lastIndex)
                }
                column = end + 1
            }
            previous = current
        }
        while (rects.size > WINDOWS) {
            var bestI = 0
            var bestJ = 1
            var bestCost = Int.MAX_VALUE
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val cost = boundingBox(rects[i], rects[j]).area - rects[i].area - rects[j].area
                    if (cost < bestCost) {
                        bestCost = cost
                        bestI = i
                        bestJ = j
                    }
                }
            }
            rects[bestI] = boundingBox(rects[bestI], rects[bestJ])
            rects.removeAt(bestJ)
        }
        return rects
    }

    private fun boundingBox(a: CellRect, b: CellRect) =
        CellRect(minOf(a.c0, b.c0), minOf(a.r0, b.r0), maxOf(a.c1, b.c1), maxOf(a.r1, b.r1))

    /**
     * Четыре окна камеры под прямоугольники [rects] (непустой список): первые окна — сами прямоугольники, остальные —
     * копии первого, выключенные (чтобы в выключенном состоянии зоны читались без лишних ячеек). [enabled] false —
     * выключены все. Чувствительность и порог окон остаются прежними.
     */
    private fun layout(rects: List<CellRect>, base: List<HipcamWindow>, width: Int, height: Int, enabled: Boolean): List<HipcamWindow> =
        List(WINDOWS) { i ->
            val rect = rects.getOrNull(i) ?: rects[0]
            val x = edge(rect.c0, COLUMNS, width)
            val y = edge(rect.r0, ROWS, height)
            base[i].copy(
                enabled = enabled && i < rects.size,
                x = x,
                y = y,
                w = edge(rect.c1 + 1, COLUMNS, width) - x,
                h = edge(rect.r1 + 1, ROWS, height) - y
            )
        }

    // ---------------- модель -> состояние камеры ----------------

    /**
     * Состояние, в которое придёт камера после записи [to] из состояния [base]; всё, что камера не умеет, остаётся
     * как было. Читать результат через [toSettings] — это и есть то, что вернёт чтение после записи.
     */
    fun applyTo(base: HipcamRecState, to: RecordingSettings): HipcamRecState {
        val motionRecordable = canRecordOnMotion(base)
        val schedule = if (to.mode == RecordMode.CUSTOM) to.schedule ?: scheduleOf(base) else WeekSchedule.forMode(to.mode)
        val kinds = schedule.days.map { slotKinds(it, motionRecordable) }
        val anyAlways = kinds.any { day -> day.any { it == RecordKind.ALWAYS } }
        val anyMotion = kinds.any { day -> day.any { it == RecordKind.MOTION } }

        fun week(cameraDay: Int, kind: RecordKind) =
            kinds[(cameraDay + 6) % 7].joinToString("") { if (it == kind) "P" else "N" }

        var state = base.copy(planEnabled = anyAlways)
        // Расписание переписывается, только если оно используется: «Не записывать» и «Постоянно» не трогают чужое
        if (base.hasSchedule) {
            if (anyAlways) state = state.copy(planWeeks = List(7) { week(it, RecordKind.ALWAYS) })
            if (anyMotion) state = state.copy(motionWeeks = List(7) { week(it, RecordKind.MOTION) })
        }
        if (base.recordOnMotion != null) state = state.copy(recordOnMotion = anyMotion)
        if (to.person != null && base.person != null) state = state.copy(person = to.person)
        val motion = to.motion
        if (motion != null) state = applyMotion(state, motion)
        return state
    }

    /** 48 ячеек суток: тип записи в каждой; границы интервалов округляются до 30 минут, «Постоянно» сильнее «По движению» */
    private fun slotKinds(slots: List<RecordSlot>, motionAllowed: Boolean): List<RecordKind?> {
        fun round(minute: Int) = (minute.coerceIn(0, WeekSchedule.MINUTES_PER_DAY) + SLOT_MINUTES / 2) / SLOT_MINUTES * SLOT_MINUTES
        return List(SLOTS) { cell ->
            val from = cell * SLOT_MINUTES
            val to = from + SLOT_MINUTES
            var kind: RecordKind? = null
            for (slot in slots) {
                if (round(slot.startMinute) > from || round(slot.endMinute) < to) continue
                if (slot.kind == RecordKind.ALWAYS) {
                    kind = RecordKind.ALWAYS
                    break
                }
                if (motionAllowed) kind = RecordKind.MOTION
            }
            kind
        }
    }

    private fun applyMotion(state: HipcamRecState, motion: MotionSettings): HipcamRecState {
        val windows = state.windows ?: return state
        val width = state.imageWidth
        val height = state.imageHeight
        val current = cellsOf(windows, width, height)
        val wanted = motion.zones
            ?.takeIf { it.columns == COLUMNS && it.rows == ROWS }
            ?.cells?.filter { it in 0 until COLUMNS * ROWS }?.toSet()
            ?: current
        val enabledNow = windows.any { it.enabled }
        var result = when {
            // Выключено или смотреть не на что: окна гасятся, геометрия остаётся (по ней читаются зоны)
            wanted.isEmpty() || !motion.enabled ->
                if (wanted.isEmpty() || wanted == current) windows.map { it.copy(enabled = false) }
                else layout(decompose(wanted), windows, width, height, enabled = false)
            // Зоны те же — геометрия окон не переписывается (камера при записи округляет её)
            wanted == current && enabledNow -> windows
            else -> layout(decompose(wanted), windows, width, height, enabled = true)
        }
        val sensitivity = motion.sensitivity.coerceIn(1, SENSITIVITY_MAX)
        val shown = (result.firstOrNull { it.enabled } ?: result[0]).sensitivity.coerceIn(1, SENSITIVITY_MAX)
        if (shown != sensitivity) result = result.map { it.copy(sensitivity = sensitivity) }
        return state.copy(windows = result)
    }

    // ---------------- команды записи ----------------

    /** Команды, переводящие камеру из [old] в [new]: только то, что различается. Сначала включения, потом выключения */
    fun commandsFor(old: HipcamRecState, new: HipcamRecState): List<String> {
        val commands = ArrayList<String>()
        val oldWindows = old.windows
        val newWindows = new.windows
        if (oldWindows != null && newWindows != null) {
            for (i in 0 until WINDOWS) {
                if (oldWindows[i] != newWindows[i]) commands.add(windowCommand(i + 1, newWindows[i], new.imageWidth, new.imageHeight))
            }
        }
        val newMotionWeeks = new.motionWeeks
        if (newMotionWeeks != null && old.motionWeeks != newMotionWeeks) commands.add(scheduleCommand("md", newMotionWeeks))
        val newPlanWeeks = new.planWeeks
        if (newPlanWeeks != null && old.planWeeks != newPlanWeeks) commands.add(scheduleCommand("plan", newPlanWeeks))

        val switchesOn = ArrayList<String>()
        val switchesOff = ArrayList<String>()
        fun switch(on: Boolean, command: String) {
            if (on) switchesOn.add(command) else switchesOff.add(command)
        }
        if (old.planEnabled != new.planEnabled) {
            switch(new.planEnabled, "cmd=setplanrecattr&-planrec_enable=${if (new.planEnabled) 1 else 0}")
        }
        val newRecordOnMotion = new.recordOnMotion
        if (newRecordOnMotion != null && old.recordOnMotion != newRecordOnMotion) {
            switch(newRecordOnMotion, "cmd=setmdalarm&-aname=record&-switch=${if (newRecordOnMotion) "on" else "off"}")
        }
        val newPerson = new.person
        if (newPerson != null && old.person != newPerson) {
            switch(newPerson, "cmd=setsmdattr&-smd_enable=${if (newPerson) 1 else 0}")
        }
        commands.addAll(switchesOn)
        commands.addAll(switchesOff)
        return commands
    }

    private fun scheduleCommand(name: String, weeks: List<String>): String =
        "cmd=setscheduleex&-ename=$name" + weeks.mapIndexed { day, week -> "&-week$day=$week" }.joinToString("")

    /** Окна всегда пишутся целиком: частичная запись сдвигает геометрию из-за округления в камере */
    private fun windowCommand(index: Int, window: HipcamWindow, width: Int, height: Int): String =
        "cmd=setmdattr&-chn=1&-name=$index&-enable=${if (window.enabled) 1 else 0}" +
            "&-s=${window.sensitivity.coerceIn(0, SENSITIVITY_MAX)}&-t=${window.threshold}" +
            "&-x=${encodeCoordinate(window.x, width, GRID_X)}&-y=${encodeCoordinate(window.y, height, GRID_Y)}" +
            "&-w=${encodeCoordinate(window.w, width, GRID_X)}&-h=${encodeCoordinate(window.h, height, GRID_Y)}"

    /**
     * Значение, которое надо отправить, чтобы камера прочитала обратно ровно [value]. Камера делит координату на
     * размер ячейки (размер потока / [grid]) с отбрасыванием остатка при записи и умножает при чтении, так что
     * «записал то, что прочитал» сдвигает окно вниз на ячейку; здесь значение округляется вверх до границы ячейки.
     */
    fun encodeCoordinate(value: Int, size: Int, grid: Int): Int {
        if (size <= 0) return value
        val cell = (value * grid + size - 1) / size
        return (cell * size + grid - 1) / grid
    }
}
