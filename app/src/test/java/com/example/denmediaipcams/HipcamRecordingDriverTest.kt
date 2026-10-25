package com.example.denmediaipcams

import com.example.denmediaipcams.network.CellRect
import com.example.denmediaipcams.network.HipcamRecState
import com.example.denmediaipcams.network.HipcamRecordingCodec
import com.example.denmediaipcams.network.HipcamWindow
import com.example.denmediaipcams.network.MotionZones
import com.example.denmediaipcams.network.RecordKind
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordSlot
import com.example.denmediaipcams.network.RecordingSettings
import com.example.denmediaipcams.network.WeekSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HipcamRecordingDriverTest {

    private val codec = HipcamRecordingCodec

    // Настоящие ответы камеры Hipcam (hi3510, прошивка V19.1.65.16.28): камера 2560x1920, запись постоянная,
    // окно детекции 1 — весь кадр (с отступом сверху), распознавание человека включено
    private val planBody = "var planrec_enable=\"1\";\nvar planrec_chn=\"11\";\nvar planrec_time=\"600\";\nvar planrec_type=\"1\";\n"

    private val mdAttrBody = """var m1_enable="1";
var m1_x="0";
var m1_y="85";
var m1_w="2560";
var m1_h="1834";
var m1_sensitivity="50";
var m1_threshold="0";
var m2_enable="0";
var m2_x="2240";
var m2_y="0";
var m2_w="320";
var m2_h="426";
var m2_sensitivity="50";
var m2_threshold="0";
var m3_enable="0";
var m3_x="0";
var m3_y="1493";
var m3_w="320";
var m3_h="426";
var m3_sensitivity="50";
var m3_threshold="0";
var m4_enable="0";
var m4_x="2240";
var m4_y="1493";
var m4_w="320";
var m4_h="426";
var m4_sensitivity="50";
var m4_threshold="0";
"""

    private val encoderBody = "var bps_1=\"1536\";\nvar fps_1=\"10\";\nvar gop_1=\"40\";\nvar brmode_1=\"1\";\nvar imagegrade_1=\"2\";\nvar width_1=\"2560\";\nvar height_1=\"1920\";\n"
    private val smdBody = "var smd_enable=\"1\";\nvar smd_threshold=\"256\";\nvar smd_x=\"0\";\nvar smd_y=\"0\";\nvar smd_w=\"1280\";\nvar smd_h=\"720\";\n"
    private val errorBody = "[Error]Param error."

    private val full = "P".repeat(48)
    private val none = "N".repeat(48)

    /** Ответ getscheduleex: week0 — воскресенье */
    private fun schedule(rows: List<String>) =
        "var etm=\"0\";\n" + rows.mapIndexed { day, row -> "var week$day=\"$row\";\n" }.joinToString("")

    private fun state(
        planEnable: String = "1",
        plan: List<String>? = List(7) { full },
        motion: List<String>? = List(7) { full },
        mdAttr: String = mdAttrBody,
        record: String = "off",
        smd: String? = smdBody
    ): HipcamRecState = codec.buildState(
        plan = planBody.replace("planrec_enable=\"1\"", "planrec_enable=\"$planEnable\""),
        planSchedule = plan?.let { schedule(it) },
        motionSchedule = motion?.let { schedule(it) },
        mdAttr = mdAttr,
        encoder = encoderBody,
        smd = smd,
        mdAlarm = "var md_record_switch=\"$record\";\n"
    )!!

    private fun cellsOfRect(c0: Int, r0: Int, c1: Int, r1: Int): Set<Int> =
        (r0..r1).flatMap { r -> (c0..c1).map { c -> r * 22 + c } }.toSet()

    /** Что станет с окном после записи в камеру и чтения обратно: камера хранит окна в ячейках сетки 1280x720 */
    private fun throughCamera(window: HipcamWindow, width: Int, height: Int): HipcamWindow {
        fun stored(value: Int, size: Int, grid: Int) = (codec.encodeCoordinate(value, size, grid) * grid / size) * size / grid
        return window.copy(
            x = stored(window.x, width, 1280), y = stored(window.y, height, 720),
            w = stored(window.w, width, 1280), h = stored(window.h, height, 720)
        )
    }

    private fun writeAndRead(base: HipcamRecState, to: RecordingSettings) = codec.toSettings(codec.applyTo(base, to))

    @Test
    fun parsesRealResponses() {
        val vars = codec.parseVars(planBody)
        assertEquals("1", vars["planrec_enable"])
        assertEquals("600", vars["planrec_time"])
        assertEquals(7, codec.parseWeeks(schedule(List(7) { full }))?.size)
        assertNull(codec.parseWeeks(errorBody))
        assertNull(codec.parseWeeks(schedule(List(7) { "PPPP" })))
        val windows = codec.parseWindows(mdAttrBody)!!
        assertEquals(HipcamWindow(true, 0, 85, 2560, 1834, 50, 0), windows[0])
        assertEquals(HipcamWindow(false, 2240, 0, 320, 426, 50, 0), windows[1])
        assertEquals(HipcamWindow(false, 2240, 1493, 320, 426, 50, 0), windows[3])
        assertNull(codec.parseWindows(errorBody))
    }

    @Test
    fun realCameraReadsAsContinuousRecording() {
        val settings = codec.toSettings(state())
        assertEquals(RecordMode.ALWAYS, settings.mode)
        assertEquals(setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION, RecordMode.CUSTOM), settings.supportedModes)
        assertEquals(WeekSchedule.forMode(RecordMode.ALWAYS), settings.schedule)
        assertEquals(48, settings.scheduleSlotsPerDay)
        assertEquals(setOf(RecordKind.ALWAYS, RecordKind.MOTION), settings.scheduleKinds)
        assertNull(settings.preRecordSec)
        assertNull(settings.overwrite)
        assertEquals(true, settings.person)
        val motion = settings.motion!!
        assertTrue(motion.enabled)
        assertEquals(50, motion.sensitivity)
        assertEquals(100, motion.sensitivityMax)
        // Окно 1 закрывает весь кадр (верхняя ячейка — на пятую часть): выбраны все ячейки сетки 22x18
        assertEquals(22, motion.zones!!.columns)
        assertEquals(18, motion.zones!!.rows)
        assertEquals(396, motion.zones!!.cells.size)
    }

    @Test
    fun switchedOffRecordingReadsAsOff() {
        assertEquals(RecordMode.OFF, codec.toSettings(state(planEnable = "0")).mode)
    }

    @Test
    fun motionRecordingNeedsSwitchAndEnabledWindow() {
        val onMotion = state(planEnable = "0", record = "on")
        assertEquals(RecordMode.MOTION, codec.toSettings(onMotion).mode)
        // Сохранение по тревоге выключено — по движению ничего не пишется
        assertEquals(RecordMode.OFF, codec.toSettings(state(planEnable = "0", record = "off")).mode)
        // Ни одно окно детекции не включено — тревог не будет
        val noWindows = state(planEnable = "0", record = "on", mdAttr = mdAttrBody.replace("m1_enable=\"1\"", "m1_enable=\"0\""))
        assertEquals(RecordMode.OFF, codec.toSettings(noWindows).mode)
    }

    @Test
    fun customScheduleMapsDaysAndKinds() {
        val monday = "N".repeat(16) + "P".repeat(20) + "N".repeat(12) // 08:00-18:00
        val plan = List(7) { if (it == 1) monday else none } // у камеры week0 — воскресенье, week1 — понедельник
        val motion = List(7) {
            when (it) {
                0 -> "N".repeat(44) + "P".repeat(4)
                1 -> full
                else -> none
            }
        }
        val settings = codec.toSettings(state(plan = plan, motion = motion, record = "on"))
        assertEquals(RecordMode.CUSTOM, settings.mode)
        val days = settings.schedule!!.days
        // Пересечение непрерывной записи и записи по движению — непрерывная
        assertEquals(
            listOf(
                RecordSlot(0, 480, RecordKind.MOTION),
                RecordSlot(480, 1080, RecordKind.ALWAYS),
                RecordSlot(1080, 1440, RecordKind.MOTION)
            ),
            days[0]
        )
        assertEquals(listOf(RecordSlot(1320, 1440, RecordKind.MOTION)), days[6])
        for (day in 1..5) assertTrue(days[day].isEmpty())
    }

    @Test
    fun readSettingsWrittenBackChangeNothing() {
        val monday = "N".repeat(16) + "P".repeat(20) + "N".repeat(12)
        val states = listOf(
            state(),
            state(planEnable = "0"),
            state(planEnable = "0", record = "on"),
            state(plan = List(7) { if (it == 1) monday else none }, motion = List(7) { if (it == 1) full else none }, record = "on")
        )
        for (base in states) {
            val read = codec.toSettings(base)
            assertEquals(read, writeAndRead(base, read))
        }
    }

    @Test
    fun switchingFromAlwaysToMotionTouchesOnlySwitches() {
        val base = state()
        val to = codec.toSettings(base).copy(mode = RecordMode.MOTION)
        val target = codec.applyTo(base, to)
        assertEquals(RecordMode.MOTION, codec.toSettings(target).mode)
        // Включения идут раньше выключений: запись не прерывается
        assertEquals(
            listOf("cmd=setmdalarm&-aname=record&-switch=on", "cmd=setplanrecattr&-planrec_enable=0"),
            codec.commandsFor(base, target)
        )
    }

    @Test
    fun switchingOffKeepsSchedules() {
        val base = state()
        val target = codec.applyTo(base, codec.toSettings(base).copy(mode = RecordMode.OFF))
        assertEquals(listOf("cmd=setplanrecattr&-planrec_enable=0"), codec.commandsFor(base, target))
        assertEquals(RecordMode.OFF, codec.toSettings(target).mode)
    }

    @Test
    fun customScheduleIsRoundedToHalfHours() {
        val base = state()
        val days = List(7) { day ->
            when (day) {
                0 -> listOf(RecordSlot(490, 1070, RecordKind.ALWAYS)) // 08:10-17:50 -> 08:00-18:00
                1 -> listOf(RecordSlot(720, 730, RecordKind.ALWAYS)) // десять минут — меньше ячейки, пропадает
                else -> emptyList()
            }
        }
        val to = codec.toSettings(base).copy(mode = RecordMode.CUSTOM, schedule = WeekSchedule(days))
        val target = codec.applyTo(base, to)
        val mondayRow = "N".repeat(16) + "P".repeat(20) + "N".repeat(12)
        assertTrue(target.planEnabled)
        assertEquals(false, target.recordOnMotion)
        assertEquals(List(7) { if (it == 1) mondayRow else none }, target.planWeeks)
        // Расписание детекции не трогается, если по движению ничего не пишется
        assertEquals(List(7) { full }, target.motionWeeks)
        val expected = codec.toSettings(target)
        assertEquals(listOf(RecordSlot(480, 1080, RecordKind.ALWAYS)), expected.schedule!!.days[0])
        assertTrue(expected.schedule!!.days[1].isEmpty())
        assertEquals(RecordMode.CUSTOM, expected.mode)
        val week = (0..6).joinToString("") { "&-week$it=" + (if (it == 1) mondayRow else none) }
        assertEquals(listOf("cmd=setscheduleex&-ename=plan$week"), codec.commandsFor(base, target))
    }

    @Test
    fun cameraWithoutScheduleOffersOnlySimpleModes() {
        val base = state(plan = null, motion = null)
        assertFalse(base.hasSchedule)
        val settings = codec.toSettings(base)
        assertNull(settings.schedule)
        assertEquals(0, settings.scheduleSlotsPerDay)
        assertEquals(setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION), settings.supportedModes)
        assertEquals(RecordMode.ALWAYS, settings.mode)
        val target = codec.applyTo(base, settings.copy(mode = RecordMode.MOTION))
        assertNull(target.planWeeks)
        assertEquals(
            listOf("cmd=setmdalarm&-aname=record&-switch=on", "cmd=setplanrecattr&-planrec_enable=0"),
            codec.commandsFor(base, target)
        )
    }

    @Test
    fun cameraWithoutMotionDetectionOffersNoMotionMode() {
        val base = codec.buildState(planBody, schedule(List(7) { full }), schedule(List(7) { full }), errorBody, encoderBody, errorBody, errorBody)!!
        val settings = codec.toSettings(base)
        assertNull(settings.motion)
        assertNull(settings.person)
        assertEquals(setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.CUSTOM), settings.supportedModes)
        assertEquals(setOf(RecordKind.ALWAYS), settings.scheduleKinds)
    }

    @Test
    fun notAHipcamIsNotRecognised() {
        assertNull(codec.buildState(errorBody, null, null, null, null, null, null))
        assertNull(codec.buildState(null, null, null, null, null, null, null))
    }

    @Test
    fun personDetectionIsSmartHumanoidRecognition() {
        val off = state(smd = smdBody.replace("smd_enable=\"1\"", "smd_enable=\"0\""))
        assertEquals(false, codec.toSettings(off).person)
        val base = state()
        val target = codec.applyTo(base, codec.toSettings(base).copy(person = false))
        assertEquals(listOf("cmd=setsmdattr&-smd_enable=0"), codec.commandsFor(base, target))
        assertEquals(false, codec.toSettings(target).person)
    }

    @Test
    fun coordinatesAreSentSoThatCameraReadsThemBackUnchanged() {
        // Камера отбрасывает остаток при записи (ячейка 2,67 пикселя по вертикали): значения, прочитанные из камеры,
        // отправляются с округлением вверх, иначе окно съезжает вниз при каждой записи
        assertEquals(86, codec.encodeCoordinate(85, 1920, 720))
        assertEquals(1835, codec.encodeCoordinate(1834, 1920, 720))
        assertEquals(1494, codec.encodeCoordinate(1493, 1920, 720))
        assertEquals(427, codec.encodeCoordinate(426, 1920, 720))
        assertEquals(2240, codec.encodeCoordinate(2240, 2560, 1280))
        assertEquals(0, codec.encodeCoordinate(0, 1920, 720))
    }

    @Test
    fun sensitivityChangeRewritesAllWindowsWithoutDrift() {
        val base = state()
        val motion = codec.toSettings(base).motion!!
        val to = codec.toSettings(base).copy(motion = motion.copy(sensitivity = 60))
        val target = codec.applyTo(base, to)
        assertEquals(60, codec.toSettings(target).motion!!.sensitivity)
        assertEquals(
            listOf(
                "cmd=setmdattr&-chn=1&-name=1&-enable=1&-s=60&-t=0&-x=0&-y=86&-w=2560&-h=1835",
                "cmd=setmdattr&-chn=1&-name=2&-enable=0&-s=60&-t=0&-x=2240&-y=0&-w=320&-h=427",
                "cmd=setmdattr&-chn=1&-name=3&-enable=0&-s=60&-t=0&-x=0&-y=1494&-w=320&-h=427",
                "cmd=setmdattr&-chn=1&-name=4&-enable=0&-s=60&-t=0&-x=2240&-y=1494&-w=320&-h=427"
            ),
            codec.commandsFor(base, target)
        )
    }

    @Test
    fun disablingMotionOnlySwitchesTheEnabledWindowOff() {
        val base = state()
        val motion = codec.toSettings(base).motion!!
        val target = codec.applyTo(base, codec.toSettings(base).copy(motion = motion.copy(enabled = false)))
        assertEquals(
            listOf("cmd=setmdattr&-chn=1&-name=1&-enable=0&-s=50&-t=0&-x=0&-y=86&-w=2560&-h=1835"),
            codec.commandsFor(base, target)
        )
        val read = codec.toSettings(target).motion!!
        assertFalse(read.enabled)
        // Зоны выключенной детекции остаются в окнах и читаются обратно
        assertEquals(396, read.zones!!.cells.size)
    }

    @Test
    fun rectangleZoneIsWrittenAsOneWindowAndSurvivesCameraRounding() {
        val base = state()
        val cells = cellsOfRect(3, 2, 6, 4)
        val to = codec.toSettings(base).let { it.copy(motion = it.motion!!.copy(zones = MotionZones(22, 18, cells))) }
        val target = codec.applyTo(base, to)
        val windows = target.windows!!
        assertEquals(HipcamWindow(true, 349, 213, 465, 320, 50, 0), windows[0])
        for (i in 1..3) assertEquals(windows[0].copy(enabled = false), windows[i])
        assertEquals(cells, codec.toSettings(target).motion!!.zones!!.cells)
        // То, что камера после записи отдаёт округлённым, всё равно читается теми же ячейками
        val real = windows.map { throughCamera(it, 2560, 1920) }
        assertEquals(cells, codec.cellsOf(real, 2560, 1920))
        assertEquals(
            "cmd=setmdattr&-chn=1&-name=1&-enable=1&-s=50&-t=0&-x=350&-y=214&-w=466&-h=320",
            codec.commandsFor(base, target).first()
        )
    }

    @Test
    fun threeSeparateZonesUseThreeWindows() {
        val base = state()
        val cells = cellsOfRect(0, 0, 2, 1) + cellsOfRect(10, 5, 12, 9) + cellsOfRect(18, 14, 21, 17)
        val to = codec.toSettings(base).let { it.copy(motion = it.motion!!.copy(zones = MotionZones(22, 18, cells))) }
        val target = codec.applyTo(base, to)
        assertEquals(listOf(true, true, true, false), target.windows!!.map { it.enabled })
        assertEquals(cells, codec.toSettings(target).motion!!.zones!!.cells)
        val real = target.windows!!.map { throughCamera(it, 2560, 1920) }
        assertEquals(cells, codec.cellsOf(real, 2560, 1920))
    }

    @Test
    fun zonesOutOfDisabledMotionStayInWindows() {
        val base = state()
        val cells = cellsOfRect(3, 2, 6, 4)
        val to = codec.toSettings(base).let {
            it.copy(motion = it.motion!!.copy(enabled = false, zones = MotionZones(22, 18, cells)))
        }
        val read = codec.toSettings(codec.applyTo(base, to)).motion!!
        assertFalse(read.enabled)
        assertEquals(cells, read.zones!!.cells)
    }

    @Test
    fun clearedZonesSwitchMotionOff() {
        val base = state()
        val to = codec.toSettings(base).let { it.copy(motion = it.motion!!.copy(zones = MotionZones(22, 18, emptySet()))) }
        assertFalse(codec.toSettings(codec.applyTo(base, to)).motion!!.enabled)
    }

    @Test
    fun cellsDecomposeIntoRectanglesAndMergeWhenTooMany() {
        val two = cellsOfRect(0, 0, 2, 1) + cellsOfRect(10, 5, 12, 9)
        assertEquals(listOf(CellRect(0, 0, 2, 1), CellRect(10, 5, 12, 9)), codec.decompose(two))
        // Г-образная фигура — два прямоугольника
        val corner = cellsOfRect(0, 0, 4, 0) + cellsOfRect(0, 1, 0, 5)
        assertEquals(2, codec.decompose(corner).size)
        // Пять разрозненных ячеек не умещаются в четыре окна: ближайшие объединяются, лишнее накрывается
        val scattered = setOf(0, 3 * 22 + 5, 6 * 22 + 10, 9 * 22 + 15, 12 * 22 + 20)
        val rects = codec.decompose(scattered)
        assertEquals(4, rects.size)
        for (cell in scattered) {
            val column = cell % 22
            val row = cell / 22
            assertTrue(rects.any { column in it.c0..it.c1 && row in it.r0..it.r1 })
        }
        assertTrue(codec.decompose(emptySet()).isEmpty())
    }

    @Test
    fun overlappingSlotsPreferContinuousRecording() {
        val base = state(planEnable = "0", record = "off")
        val days = List(7) {
            listOf(RecordSlot(0, 720, RecordKind.MOTION), RecordSlot(360, 1440, RecordKind.ALWAYS))
        }
        val read = writeAndRead(base, codec.toSettings(base).copy(mode = RecordMode.CUSTOM, schedule = WeekSchedule(days)))
        assertEquals(listOf(RecordSlot(0, 360, RecordKind.MOTION), RecordSlot(360, 1440, RecordKind.ALWAYS)), read.schedule!!.days[3])
    }
}
