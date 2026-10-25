package com.example.denmediaipcams

import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripRecordingCodec
import com.example.denmediaipcams.network.DvripRecordingDriver
import com.example.denmediaipcams.network.MotionSettings
import com.example.denmediaipcams.network.MotionZones
import com.example.denmediaipcams.network.RecordKind
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordSlot
import com.example.denmediaipcams.network.RecordingSettings
import com.example.denmediaipcams.network.WeekSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистые помощники драйвера записи XMEye/DVRIP. Образцы строк — из настоящих ответов камеры XM530V200
 * (Record.[0], Detect.MotionDetect.[0]); org.json в юнит-тестах не работает, поэтому JSON здесь не собирается.
 */
class DvripRecordingDriverTest {

    private val codec = DvripRecordingCodec

    // Record.[0] камеры: в воскресенье…субботу (строки 0..6) 1-й интервал — все сутки, остальные пять выключены
    private val full = "1 00:00:00-24:00:00"
    private val empty = "0 00:00:00-24:00:00"
    private val realSections: List<List<String>> = List(7) { listOf(full, empty, empty, empty, empty, empty) }
    private val realMasks: List<List<String>> =
        List(7) { listOf("0x00000007", "0x00000000", "0x00000000", "0x00000000", "0x00000000", "0x00000000") }

    private val allDay = RecordSlot(0, 1440, RecordKind.ALWAYS)

    @Test
    fun parsesRealSections() {
        val on = codec.parseSection(full)!!
        assertTrue(on.enabled)
        assertEquals(0, on.startMinute)
        assertEquals(1440, on.endMinute)
        assertFalse(codec.parseSection(empty)!!.enabled)

        val partial = codec.parseSection("1 08:30:15-17:45:59")!!
        assertEquals(8 * 60 + 30, partial.startMinute)
        assertEquals(17 * 60 + 45, partial.endMinute)
    }

    @Test
    fun garbageSectionIsNull() {
        assertNull(codec.parseSection(""))
        assertNull(codec.parseSection("00:00:00-24:00:00"))
        assertNull(codec.parseSection("1 0:0-24:00"))
    }

    @Test
    fun formatsSectionTheWayCameraDoes() {
        assertEquals(full, codec.formatSection(true, 0, 1440))
        assertEquals(empty, codec.formatSection(false, 0, 1440))
        assertEquals("1 08:05:00-17:45:00", codec.formatSection(true, 8 * 60 + 5, 17 * 60 + 45))
        val section = codec.parseSection(codec.formatSection(true, 90, 1000))!!
        assertEquals(90, section.startMinute)
        assertEquals(1000, section.endMinute)
    }

    @Test
    fun maskBitsMeanRegularMotionAlarm() {
        assertEquals(RecordKind.ALWAYS, codec.kindOf(0x7))
        assertEquals(RecordKind.ALWAYS, codec.kindOf(0x1))
        assertEquals(RecordKind.MOTION, codec.kindOf(0x2))
        assertEquals(RecordKind.MOTION, codec.kindOf(0x4))
        assertEquals(RecordKind.MOTION, codec.kindOf(0x6))
        assertNull(codec.kindOf(0))
        assertEquals(0x7, codec.maskOf(RecordKind.ALWAYS))
        assertEquals(0x2, codec.maskOf(RecordKind.MOTION))
    }

    @Test
    fun parsesNumbersAndFlags() {
        assertEquals(7L, codec.parseNumber("0x00000007"))
        assertEquals(0xFFFFFFFFL, codec.parseNumber("0xFFFFFFFF"))
        assertEquals(7L, codec.parseNumber("7"))
        assertNull(codec.parseNumber(""))
        assertNull(codec.parseNumber(null))
        assertEquals("0x00000007", codec.hex32(7))
        assertEquals("0xFFFFFFFF", codec.hex32(0xFFFFFFFFL))
        assertEquals(true, codec.parseFlag(true))
        assertEquals(false, codec.parseFlag(0))
        assertEquals(true, codec.parseFlag("0x00000001"))
        assertEquals(false, codec.parseFlag("0x00000000"))
        assertNull(codec.parseFlag(null))
    }

    @Test
    fun realRecordConfigIsAlwaysEverywhere() {
        val schedule = codec.parseSchedule(realSections, realMasks)!!
        assertEquals(RecordMode.ALWAYS, schedule.deriveMode())
        for (day in schedule.days) assertEquals(listOf(allDay), day)
    }

    @Test
    fun cameraWeekStartsOnSunday() {
        // Проверено на камере: запись в четверг (4-я строка Mask) остановила строка с индексом 4
        assertEquals(0, codec.cameraRow(6))
        assertEquals(1, codec.cameraRow(0))
        assertEquals(4, codec.cameraRow(3))
        for (day in 0 until 7) assertEquals(day, codec.modelDay(codec.cameraRow(day)))

        // Только в строке 1 (понедельник) запись по движению в рабочие часы
        val sections = realSections.mapIndexed { row, s -> if (row == 1) listOf("1 09:00:00-18:00:00") + s.drop(1) else s }
        val masks = realMasks.mapIndexed { row, m -> if (row == 1) listOf("0x00000002") + m.drop(1) else m }
        val schedule = codec.parseSchedule(sections, masks)!!
        assertEquals(listOf(RecordSlot(540, 1080, RecordKind.MOTION)), schedule.days[0])
        assertEquals(listOf(allDay), schedule.days[6])
        assertEquals(RecordMode.CUSTOM, schedule.deriveMode())
    }

    @Test
    fun disabledOrMaskedSlotsAreSkipped() {
        val sections = listOf("0 00:00:00-12:00:00", "1 12:00:00-24:00:00", "1 01:00:00-02:00:00")
        val masks = listOf("0x00000007", "0x00000001", "0x00000000")
        assertEquals(listOf(RecordSlot(720, 1440, RecordKind.ALWAYS)), codec.parseDay(sections, masks))
        assertNull(codec.parseDay(listOf("мусор"), listOf("0x1")))
        assertNull(codec.parseDay(listOf(full), listOf("не число")))
    }

    @Test
    fun badScheduleShapeIsNull() {
        assertNull(codec.parseSchedule(realSections.take(6), realMasks))
        assertNull(codec.parseSchedule(realSections, realMasks.take(6)))
    }

    @Test
    fun unchangedDaysKeepCameraRowsAsIs() {
        val wanted = WeekSchedule.forMode(RecordMode.ALWAYS)
        val rows = codec.buildSchedule(realSections, realMasks, wanted)!!
        assertEquals(realSections, rows.sections)
        // Маски камеры (0x7) не перезаписываются, хотя приложение для «постоянно» написало бы те же 0x7
        assertEquals(realMasks, rows.masks)
    }

    @Test
    fun motionModeWritesDetectBitInEveryDay() {
        val rows = codec.buildSchedule(realSections, realMasks, WeekSchedule.forMode(RecordMode.MOTION))!!
        for (row in 0 until 7) {
            assertEquals(listOf(full, empty, empty, empty, empty, empty), rows.sections[row])
            assertEquals(listOf("0x00000002", "0x00000000", "0x00000000", "0x00000000", "0x00000000", "0x00000000"), rows.masks[row])
        }
        assertEquals(RecordMode.MOTION, codec.parseSchedule(rows.sections, rows.masks)!!.deriveMode())
    }

    @Test
    fun customScheduleRoundTripsAndChangesOnlyNeededDays() {
        val monday = listOf(RecordSlot(0, 360, RecordKind.MOTION), RecordSlot(480, 1020, RecordKind.ALWAYS))
        val days = List(7) { d -> if (d == 0) monday else listOf(allDay) }
        val wanted = WeekSchedule(days)
        val rows = codec.buildSchedule(realSections, realMasks, wanted)!!

        // Понедельник — строка 1; остальные дни не тронуты
        assertEquals(listOf("1 00:00:00-06:00:00", "1 08:00:00-17:00:00", empty, empty, empty, empty), rows.sections[1])
        assertEquals(listOf("0x00000002", "0x00000007", "0x00000000", "0x00000000", "0x00000000", "0x00000000"), rows.masks[1])
        assertEquals(realSections[0], rows.sections[0])
        assertEquals(realMasks[3], rows.masks[3])
        assertEquals(wanted, codec.parseSchedule(rows.sections, rows.masks))
    }

    @Test
    fun scheduleOrderDoesNotMatterButSlotLimitDoes() {
        val shuffled = listOf(RecordSlot(600, 700, RecordKind.ALWAYS), RecordSlot(0, 100, RecordKind.MOTION))
        val rows = codec.buildSchedule(realSections, realMasks, WeekSchedule(List(7) { shuffled }))!!
        assertEquals("1 00:00:00-01:40:00", rows.sections[2][0])
        assertEquals("1 10:00:00-11:40:00", rows.sections[2][1])

        val seven = List(7) { i -> RecordSlot(i * 100, i * 100 + 50, RecordKind.ALWAYS) }
        assertNull(codec.buildSchedule(realSections, realMasks, WeekSchedule(List(7) { seven })))
        val six = seven.take(6)
        assertNotNull(codec.buildSchedule(realSections, realMasks, WeekSchedule(List(7) { six })))
    }

    @Test
    fun emptyScheduleClearsAllSlots() {
        val rows = codec.buildSchedule(realSections, realMasks, WeekSchedule.forMode(RecordMode.OFF))!!
        for (row in 0 until 7) {
            assertEquals(List(6) { empty }, rows.sections[row])
            assertEquals(List(6) { "0x00000000" }, rows.masks[row])
        }
        assertEquals(RecordMode.OFF, codec.parseSchedule(rows.sections, rows.masks)!!.deriveMode())
    }

    @Test
    fun recordModeStringsMapToSimpleModes() {
        val always = WeekSchedule.forMode(RecordMode.ALWAYS)
        val motion = WeekSchedule.forMode(RecordMode.MOTION)
        assertEquals(RecordMode.OFF, codec.modeOf("ClosedRecord", always))
        assertEquals(RecordMode.ALWAYS, codec.modeOf("ManualRecord", motion))
        assertEquals(RecordMode.ALWAYS, codec.modeOf("ConfigRecord", always))
        assertEquals(RecordMode.MOTION, codec.modeOf("ConfigRecord", motion))
        assertEquals(RecordMode.OFF, codec.modeOf("ConfigRecord", WeekSchedule.forMode(RecordMode.OFF)))
        assertEquals(RecordMode.ALWAYS, codec.modeOf("", always))
    }

    @Test
    fun motionIsNeededByMotionModeAndMotionSlots() {
        assertTrue(codec.usesMotion(RecordMode.MOTION, null))
        assertFalse(codec.usesMotion(RecordMode.ALWAYS, null))
        assertFalse(codec.usesMotion(RecordMode.OFF, null))
        val custom = WeekSchedule(List(7) { listOf(RecordSlot(0, 60, RecordKind.MOTION)) })
        assertTrue(codec.usesMotion(RecordMode.CUSTOM, custom))
        assertFalse(codec.usesMotion(RecordMode.CUSTOM, WeekSchedule(List(7) { listOf(allDay) })))
    }

    @Test
    fun overwriteStringsFromGeneralConfig() {
        assertEquals(true, codec.parseOverwrite("OverWrite"))
        assertEquals(false, codec.parseOverwrite("StopRecord"))
        assertNull(codec.parseOverwrite(""))
        assertNull(codec.parseOverwrite(null))
    }

    @Test
    fun sensitivityScaleIsOneToSixLargerIsMoreSensitive() {
        assertEquals(6, codec.SENSITIVITY_MAX)
        for (level in 1..6) assertEquals(level, codec.levelOf(codec.sensitivityOf(level)))
        assertEquals(1, codec.sensitivityOf(0))
        assertEquals(6, codec.sensitivityOf(9))
        assertEquals(1, codec.levelOf(-3))
        assertEquals(6, codec.levelOf(7))
    }

    // Detect.MotionDetect.[0].Region камеры: 32 слова 0xFFFFFFFF; сетка камеры — 7×7 (MotionArea)
    private val realRegion: List<String> = List(32) { "0xFFFFFFFF" }

    @Test
    fun realRegionSelectsWholeGrid() {
        val zones = codec.regionToZones(realRegion)!!
        assertEquals(7, zones.columns)
        assertEquals(7, zones.rows)
        assertEquals((0 until 49).toSet(), zones.cells)
        // Весь выбор записывается теми же словами, что вернула камера
        assertEquals(realRegion, codec.zonesToRegion(zones, realRegion))
    }

    @Test
    fun regionCellsAreRowWordsAndColumnBits() {
        val region = List(32) { "0x00000000" }.toMutableList()
        region[0] = "0x00000001" // строка 0, столбец 0
        region[2] = "0x00000044" // строка 2, столбцы 2 и 6
        region[6] = "0x00000040" // строка 6, столбец 6
        val zones = codec.regionToZones(region)!!
        assertEquals(setOf(0, 2 * 7 + 2, 2 * 7 + 6, 6 * 7 + 6), zones.cells)
    }

    @Test
    fun zonesWriteOnlyGridBitsAndKeepTheRest() {
        val zones = MotionZones(7, 7, setOf(0, 8, 48))
        val region = codec.zonesToRegion(zones, realRegion)!!
        assertEquals(32, region.size)
        assertEquals("0xFFFFFF81", region[0]) // бит 0 выбран, 1..6 сняты, старшие 25 бит камеры не тронуты
        assertEquals("0xFFFFFF82", region[1]) // ячейка 8 — строка 1, столбец 1
        assertEquals("0xFFFFFFC0", region[6]) // ячейка 48 — строка 6, столбец 6
        assertEquals("0xFFFFFF80", region[3])
        for (row in 7 until 32) assertEquals("0xFFFFFFFF", region[row])
        assertEquals(zones, codec.regionToZones(region))
    }

    @Test
    fun zonesOfOtherGridAreRejected() {
        assertNull(codec.zonesToRegion(MotionZones(22, 18, setOf(1)), realRegion))
        assertNull(codec.zonesToRegion(MotionZones(7, 7, setOf(1)), listOf("0x1")))
        assertNull(codec.regionToZones(listOf("0x1", "0x2")))
        assertNull(codec.regionToZones(List(7) { "мусор" }))
    }

    // EventHandler из Detect.MotionDetect.[0]: RecordEnable=true, RecordMask="0x00000001", расписание на все сутки
    @Test
    fun handlerRecordsWhenEnabledAndChannelBitSet() {
        assertTrue(codec.recordsOnEvent(true, "0x00000001"))
        assertTrue(codec.recordsOnEvent(true, "0x00000003"))
        assertFalse(codec.recordsOnEvent(false, "0x00000001"))
        assertFalse(codec.recordsOnEvent(true, "0x00000000"))
        assertFalse(codec.recordsOnEvent(true, null))
        assertEquals("0x00000001", codec.withChannelBit("0x00000000"))
        assertEquals("0x00000003", codec.withChannelBit("0x00000002"))
        assertEquals("0x00000001", codec.withChannelBit(null))
    }

    @Test
    fun handlerScheduleMustCoverEveryDay() {
        assertTrue(codec.coversWholeWeek(realSections))
        val brokenDay = realSections.mapIndexed { row, s -> if (row == 2) listOf("1 00:00:00-12:00:00") + s.drop(1) else s }
        assertFalse(codec.coversWholeWeek(brokenDay))
        val disabledDay = realSections.mapIndexed { row, s -> if (row == 5) List(6) { empty } else s }
        assertFalse(codec.coversWholeWeek(disabledDay))
        assertFalse(codec.coversWholeWeek(realSections.take(3)))

        val fixed = codec.wholeWeekSections(brokenDay)
        assertEquals(realSections, fixed)
        assertTrue(codec.coversWholeWeek(fixed))
    }

    // --- expectedAfterWrite: что должен вернуть read() после записи ---

    private val driver = DvripRecordingDriver(IpCamera(id = "1", name = "c", ipAddress = "192.168.1.2"))

    private fun settings(mode: RecordMode, motion: MotionSettings?, preRecord: Int? = 5) = RecordingSettings(
        mode = mode,
        supportedModes = setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION, RecordMode.CUSTOM),
        schedule = WeekSchedule.forMode(RecordMode.ALWAYS),
        scheduleSlotsPerDay = 6,
        scheduleKinds = setOf(RecordKind.ALWAYS, RecordKind.MOTION),
        preRecordSec = preRecord,
        preRecordMaxSec = 30,
        overwrite = true,
        motion = motion,
        person = true
    )

    @Test
    fun motionModeForcesDetectionOn() {
        val off = MotionSettings(enabled = false, sensitivity = 3, sensitivityMax = 6, zones = null)
        assertTrue(driver.expectedAfterWrite(settings(RecordMode.MOTION, off)).motion!!.enabled)
        assertFalse(driver.expectedAfterWrite(settings(RecordMode.ALWAYS, off)).motion!!.enabled)
        assertNull(driver.expectedAfterWrite(settings(RecordMode.MOTION, null)).motion)
    }

    @Test
    fun preRecordIsLimitedToCameraRange() {
        val m = MotionSettings(true, 3, 6, null)
        assertEquals(30, driver.expectedAfterWrite(settings(RecordMode.ALWAYS, m, preRecord = 90)).preRecordSec)
        assertEquals(0, driver.expectedAfterWrite(settings(RecordMode.ALWAYS, m, preRecord = -2)).preRecordSec)
        assertNull(driver.expectedAfterWrite(settings(RecordMode.ALWAYS, m, preRecord = null)).preRecordSec)
    }
}
