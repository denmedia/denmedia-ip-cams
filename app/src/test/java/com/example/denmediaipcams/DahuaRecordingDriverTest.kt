package com.example.denmediaipcams

import com.example.denmediaipcams.network.CameraRecording
import com.example.denmediaipcams.network.DahuaRecordingFormat
import com.example.denmediaipcams.network.DahuaSlot
import com.example.denmediaipcams.network.DahuaTables
import com.example.denmediaipcams.network.DahuaWritePlan
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
 * Разбор и сборка настроек записи Dahua. Образцы — ответы настоящей DH-SD-H2C-0400B (прошивка с MotionDetectWindow,
 * 22x18 блоков, 6 интервалов в сутки, дни 0..7 с воскресеньем в нуле); повторяющиеся строки (TimeSection, Region)
 * собираются циклами с теми же значениями, что вернула камера.
 */
class DahuaRecordingDriverTest {

    // ---------------- Образцы ответов камеры ----------------

    /** getConfig Record: [days] — интервалы дней камеры (0 — воскресенье, 7 — праздничный), остальные — [firstSlot] и пустые */
    private fun recordBody(
        days: Map<Int, List<String>> = emptyMap(),
        firstSlot: String = "2 00:00:00-23:59:59",
        preRecord: Int = 5
    ): String = buildString {
        append("table.Record[0].Stream=0\n")
        append("table.Record[0].PreRecord=$preRecord\n")
        append("table.Record[0].HolidayEnable=false\n")
        append("table.Record[0].Redundancy=false\n")
        for (day in 0..7) {
            for (slot in 0..5) {
                val value = days[day]?.getOrNull(slot) ?: if (slot == 0) firstSlot else "0 00:00:00-23:59:59"
                append("table.Record[0].TimeSection[$day][$slot]=$value\n")
            }
        }
        append("table.Record[0].Format=dav\n")
    }

    private fun modeBody(mode: Int) = "table.RecordMode[0].Mode=$mode\ntable.RecordMode[0].ModeExtra1=0\n"

    private val storageBody = """table.StorageGroup[0].FileHoldTime=0
table.StorageGroup[0].OverWrite=true
table.StorageGroup[0].PicturePathRule=%y-%M-%d/%c/jpg/%h/%m/%s[%E][%O@%S][%R][%P].jpg
table.StorageGroup[0].RecordPathRule=%y-%M-%d/%c/dav/%h/%h.%m.%s-%h.%m.%s[%E][%O@%S][%R].%t
table.StorageGroup[0].Memo=For Reading & Writing Files
table.StorageGroup[0].Name=ReadWrite
table.StorageGroup[0].Channels[0].MaxPictures=0
table.StorageGroup[0].Channels[0].Path=none
table.StorageGroup[1].FileHoldTime=0
table.StorageGroup[1].OverWrite=true
table.StorageGroup[1].Memo=For FTP Files
table.StorageGroup[1].Name=Remote
table.StorageGroup[2].FileHoldTime=0
table.StorageGroup[2].OverWrite=false
table.StorageGroup[2].Memo=For Reading Files
table.StorageGroup[2].Name=ReadOnly
"""

    /** getConfig MotionDetect: детекция выключена, окно Region1 на весь кадр, чувствительность 70 — как у камеры по умолчанию */
    private fun motionBody(
        enabled: Boolean = false,
        sensitive: Int = 70,
        regionRow: Int = 4194303,
        recordEnable: Boolean = true,
        arming: Map<Int, String> = emptyMap()
    ): String = buildString {
        append("table.MotionDetect[0].Enable=$enabled\n")
        append("table.MotionDetect[0].EventHandler.RecordChannels[0]=0\n")
        append("table.MotionDetect[0].EventHandler.RecordEnable=$recordEnable\n")
        append("table.MotionDetect[0].EventHandler.RecordLatch=10\n")
        for (day in 0..6) {
            for (slot in 0..5) {
                val value = if (slot == 0) arming[day] ?: "1 00:00:00-23:59:59" else "0 00:00:00-23:59:59"
                append("table.MotionDetect[0].EventHandler.TimeSection[$day][$slot]=$value\n")
            }
        }
        append("table.MotionDetect[0].Level=3\n")
        append("table.MotionDetect[0].MotionDetectWindow[0].Id=0\n")
        append("table.MotionDetect[0].MotionDetectWindow[0].Name=Region1\n")
        for (row in 0..17) append("table.MotionDetect[0].MotionDetectWindow[0].Region[$row]=$regionRow\n")
        append("table.MotionDetect[0].MotionDetectWindow[0].Sensitive=$sensitive\n")
        append("table.MotionDetect[0].MotionDetectWindow[0].Threshold=5\n")
        append("table.MotionDetect[0].MotionDetectWindow[0].Window[2]=8191\n")
        append("table.MotionDetect[0].MotionDetectWindow[1].Id=1\n")
        for (row in 0..17) append("table.MotionDetect[0].MotionDetectWindow[1].Region[$row]=0\n")
        append("table.MotionDetect[0].MotionDetectWindow[1].Sensitive=70\n")
        for (row in 0..17) append("table.MotionDetect[0].Region[$row]=0\n")
    }

    private fun smartBody(enabled: Boolean = false, human: Boolean = true) = """table.SmartMotionDetect[0].Enable=$enabled
table.SmartMotionDetect[0].Sensitivity=Middle
table.SmartMotionDetect[0].ObjectTypes.Human=$human
table.SmartMotionDetect[0].ObjectTypes.Vehicle=false
table.SmartMotionDetect[0].ObjectTypes.Animal=false
table.SmartMotionDetect[0].SmartTrack=false
"""

    private fun parse(body: String) = DahuaRecordingFormat.parseTable(body)

    /** Состояние камеры на момент проверки: запись «по движению» (маска 2 весь день), детекция выключена */
    private fun cameraTables(
        record: String = recordBody(),
        mode: String? = modeBody(0),
        motion: String? = motionBody(),
        smart: String? = smartBody()
    ) = DahuaTables(parse(record), mode?.let { parse(it) }, parse(storageBody), motion?.let { parse(it) }, smart?.let { parse(it) })

    private fun real(tables: DahuaTables = cameraTables()): RecordingSettings = DahuaRecordingFormat.settingsFrom(tables)!!

    /** Те же настройки с включённой детекцией: чтобы запись не добавляла принудительное включение */
    private fun realOn(): RecordingSettings {
        val settings = real()
        return settings.copy(motion = settings.motion!!.copy(enabled = true))
    }

    private fun allDays(slots: List<RecordSlot>) = WeekSchedule(List(7) { slots })

    /** Применяет план записи к таблицам так, как это сделала бы камера */
    private fun applied(tables: DahuaTables, plan: DahuaWritePlan, motionFixes: List<Pair<String, String>> = emptyList()) = DahuaTables(
        record = tables.record + plan.fields["Record"].orEmpty().toMap(),
        mode = tables.mode?.plus(plan.fields["RecordMode"].orEmpty().toMap()),
        storage = tables.storage?.plus(plan.fields["StorageGroup"].orEmpty().toMap()),
        motion = tables.motion?.plus(plan.fields["MotionDetect"].orEmpty().toMap())?.plus(motionFixes.toMap()),
        smart = tables.smart?.plus(plan.fields["SmartMotionDetect"].orEmpty().toMap())
    )

    // ---------------- Разбор ----------------

    @Test
    fun parsesTableKeysAndValues() {
        val table = parse(recordBody() + "garbage line\n")
        assertEquals("2 00:00:00-23:59:59", table["Record[0].TimeSection[0][0]"])
        assertEquals("0 00:00:00-23:59:59", table["Record[0].TimeSection[7][5]"])
        assertEquals("5", table["Record[0].PreRecord"])
        assertEquals("For Reading & Writing Files", parse(storageBody)["StorageGroup[0].Memo"])
        assertEquals(emptyMap<String, String>(), parse("Error\nBad Request!\n"))
    }

    @Test
    fun parsesSlots() {
        assertEquals(DahuaSlot(2, 0, 1440), DahuaRecordingFormat.parseSlot("2 00:00:00-23:59:59"))
        assertEquals(DahuaSlot(1, 510, 720), DahuaRecordingFormat.parseSlot("1 08:30:00-12:00:00"))
        assertEquals(DahuaSlot(6, 0, 1440), DahuaRecordingFormat.parseSlot("6 00:00:00-24:00:00"))
        assertEquals(DahuaSlot(0, 0, 0), DahuaRecordingFormat.parseSlot("0 00:00:00-00:00:00"))
        assertNull(DahuaRecordingFormat.parseSlot("garbage"))
        assertNull(DahuaRecordingFormat.parseSlot(""))
    }

    @Test
    fun readsRealCameraState() {
        val settings = real()
        // Маска 2 весь день по всем дням: «по движению»; детекция при этом выключена
        assertEquals(RecordMode.MOTION, settings.mode)
        assertEquals(WeekSchedule.forMode(RecordMode.MOTION), settings.schedule)
        assertEquals(setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION, RecordMode.CUSTOM), settings.supportedModes)
        assertEquals(setOf(RecordKind.ALWAYS, RecordKind.MOTION), settings.scheduleKinds)
        assertEquals(6, settings.scheduleSlotsPerDay)
        assertEquals(5, settings.preRecordSec)
        assertEquals(30, settings.preRecordMaxSec)
        assertEquals(true, settings.overwrite)
        assertEquals(false, settings.person)
        // Сетка 22x18 = 396 ячеек, все выбраны (Region = 4194303 = 2^22 - 1); Sensitive 70 → уровень 7 из 10
        assertEquals(MotionSettings(false, 7, 10, MotionZones(22, 18, (0 until 396).toSet())), settings.motion)
    }

    @Test
    fun readsModeFromRecordModeBeforeSchedule() {
        assertEquals(RecordMode.ALWAYS, real(cameraTables(mode = modeBody(1))).mode)
        assertEquals(RecordMode.OFF, real(cameraTables(mode = modeBody(2))).mode)
        assertEquals(RecordMode.MOTION, real(cameraTables(mode = modeBody(0))).mode)
        assertEquals(RecordMode.MOTION, real(cameraTables(mode = null)).mode)
    }

    @Test
    fun dayZeroOfCameraIsSunday() {
        val record = recordBody(
            days = mapOf(
                0 to listOf("1 08:00:00-12:00:00", "2 13:00:00-18:00:00"),
                1 to listOf("3 00:00:00-12:00:00", "4 12:00:00-18:00:00", "6 18:00:00-23:59:59"),
                7 to listOf("1 00:00:00-23:59:59")
            ),
            firstSlot = "0 00:00:00-23:59:59"
        )
        val schedule = real(cameraTables(record = record)).schedule!!
        // Воскресенье камеры (0) — последний день приложения; понедельник (1) — первый
        assertEquals(listOf(RecordSlot(480, 720, RecordKind.ALWAYS), RecordSlot(780, 1080, RecordKind.MOTION)), schedule.days[6])
        // Маска 3 — постоянная запись (и движение), 4 — только тревога (не наш тип), 6 — движение и тревога
        assertEquals(listOf(RecordSlot(0, 720, RecordKind.ALWAYS), RecordSlot(1080, 1440, RecordKind.MOTION)), schedule.days[0])
        for (day in 1..5) assertEquals(emptyList<RecordSlot>(), schedule.days[day])
        // Праздничный день (7) не читается
        assertEquals(RecordMode.CUSTOM, real(cameraTables(record = record)).mode)
    }

    @Test
    fun scheduleWithoutTimeSectionIsUnsupported() {
        assertNull(DahuaRecordingFormat.parseSchedule(emptyMap()))
        assertNull(DahuaRecordingFormat.settingsFrom(DahuaTables(parse("table.Record[0].PreRecord=5\n"), null, null, null, null)))
    }

    @Test
    fun cameraWithoutOptionalTablesGivesNulls() {
        val settings = real(cameraTables(motion = null, smart = null))
        assertNull(settings.motion)
        assertNull(settings.person)
        assertEquals(setOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.CUSTOM), settings.supportedModes)
        assertEquals(setOf(RecordKind.ALWAYS), settings.scheduleKinds)
    }

    @Test
    fun readsPersonFromSmartMotionDetect() {
        assertEquals(true, DahuaRecordingFormat.parsePerson(parse(smartBody(enabled = true, human = true))))
        assertEquals(false, DahuaRecordingFormat.parsePerson(parse(smartBody(enabled = true, human = false))))
        assertEquals(false, DahuaRecordingFormat.parsePerson(parse(smartBody(enabled = false, human = true))))
        assertNull(DahuaRecordingFormat.parsePerson(null))
    }

    @Test
    fun findsReadWriteStorageGroupByName() {
        val body = "table.StorageGroup[0].Name=Remote\ntable.StorageGroup[0].OverWrite=true\n" +
            "table.StorageGroup[1].Name=ReadWrite\ntable.StorageGroup[1].OverWrite=false\n"
        val storage = parse(body)
        assertEquals(1, DahuaRecordingFormat.storageGroupIndex(storage))
        assertEquals(0, DahuaRecordingFormat.storageGroupIndex(parse(storageBody)))
        assertNull(DahuaRecordingFormat.storageGroupIndex(parse("table.StorageGroup[0].Name=Remote\n")))
    }

    // ---------------- Зоны и чувствительность ----------------

    @Test
    fun regionBitsAreRowsOfTwentyTwoColumnsLowBitFirst() {
        assertEquals((0 until 396).toSet(), DahuaRecordingFormat.regionToCells(List(18) { 4194303 }))
        assertEquals(emptySet<Int>(), DahuaRecordingFormat.regionToCells(List(18) { 0 }))
        // Младший бит — левый столбец строки, старший (21-й) — правый
        assertEquals(setOf(22), DahuaRecordingFormat.regionToCells(List(18) { if (it == 1) 1 else 0 }))
        assertEquals(setOf(21), DahuaRecordingFormat.regionToCells(List(18) { if (it == 0) 1 shl 21 else 0 }))
    }

    @Test
    fun cellsBecomeRegionRows() {
        val rows = DahuaRecordingFormat.cellsToRegion(setOf(0, 21, 22, 395, 396, -1))
        assertEquals(18, rows.size)
        assertEquals(1 or (1 shl 21), rows[0])
        assertEquals(1, rows[1])
        assertEquals(1 shl 21, rows[17])
        assertEquals(0, rows[2])
        assertEquals(List(18) { 4194303 }, DahuaRecordingFormat.cellsToRegion((0 until 396).toSet()))
    }

    @Test
    fun regionSurvivesRoundTrip() {
        val cells = setOf(3, 10, 21, 22, 100, 200, 300, 395)
        assertEquals(cells, DahuaRecordingFormat.regionToCells(DahuaRecordingFormat.cellsToRegion(cells)))
    }

    @Test
    fun sensitivityMapsToTenLevels() {
        assertEquals(7, DahuaRecordingFormat.sensitivityLevel(70))
        assertEquals(10, DahuaRecordingFormat.sensitivityLevel(100))
        assertEquals(10, DahuaRecordingFormat.sensitivityLevel(95))
        assertEquals(1, DahuaRecordingFormat.sensitivityLevel(0))
        assertEquals(1, DahuaRecordingFormat.sensitivityLevel(14))
        assertEquals(2, DahuaRecordingFormat.sensitivityLevel(15))
        assertEquals(70, DahuaRecordingFormat.sensitiveOf(7))
        assertEquals(100, DahuaRecordingFormat.sensitiveOf(11))
        assertEquals(10, DahuaRecordingFormat.sensitiveOf(0))
        for (level in 1..10) {
            assertEquals(level, DahuaRecordingFormat.sensitivityLevel(DahuaRecordingFormat.sensitiveOf(level)))
        }
    }

    // ---------------- Интервалы расписания ----------------

    @Test
    fun encodesSlots() {
        assertEquals("1 08:00:00-12:00:00", DahuaRecordingFormat.encodeSlot(RecordSlot(480, 720, RecordKind.ALWAYS)))
        assertEquals("2 00:00:00-23:59:59", DahuaRecordingFormat.encodeSlot(RecordSlot(0, 1440, RecordKind.MOTION)))
        assertEquals("1 20:00:00-23:59:59", DahuaRecordingFormat.encodeSlot(RecordSlot(1200, 1440, RecordKind.ALWAYS)))
        assertEquals("2 01:30:00-02:15:00", DahuaRecordingFormat.encodeSlot(RecordSlot(90, 135, RecordKind.MOTION)))
    }

    @Test
    fun encodedSlotParsesBack() {
        val slot = RecordSlot(1200, 1440, RecordKind.ALWAYS)
        val parsed = DahuaRecordingFormat.parseSlot(DahuaRecordingFormat.encodeSlot(slot))!!
        assertEquals(DahuaSlot(1, 1200, 1440), parsed)
    }

    @Test
    fun dayIsPaddedToSlotCountAndRejectsOverflow() {
        val empty = "0 00:00:00-23:59:59"
        assertEquals(List(6) { empty }, DahuaRecordingFormat.encodeDay(emptyList(), 6))
        // Соседние интервалы одного типа сливаются в один
        val merged = DahuaRecordingFormat.encodeDay(listOf(RecordSlot(0, 600, RecordKind.ALWAYS), RecordSlot(600, 1440, RecordKind.ALWAYS)), 6)
        assertEquals(listOf("1 00:00:00-23:59:59") + List(5) { empty }, merged)
        // Разные типы не сливаются
        val kinds = DahuaRecordingFormat.encodeDay(listOf(RecordSlot(0, 600, RecordKind.ALWAYS), RecordSlot(600, 1440, RecordKind.MOTION)), 6)
        assertEquals(listOf("1 00:00:00-10:00:00", "2 10:00:00-23:59:59") + List(4) { empty }, kinds)
        // Семь интервалов с промежутками в шесть мест не помещаются
        val seven = List(7) { RecordSlot(it * 100, it * 100 + 50, RecordKind.MOTION) }
        assertNull(DahuaRecordingFormat.encodeDay(seven, 6))
    }

    // ---------------- Запрос к камере ----------------

    @Test
    fun buildsSetRequestWithEncodedBracketsAndSpaces() {
        val request = DahuaRecordingFormat.setRequest(
            listOf("Record[0].TimeSection[4][0]" to "2 00:00:00-23:59:59", "Record[0].PreRecord" to "5")
        )
        assertEquals(
            "configManager.cgi?action=setConfig&Record%5B0%5D.TimeSection%5B4%5D%5B0%5D=2%2000:00:00-23:59:59&Record%5B0%5D.PreRecord=5",
            request
        )
    }

    @Test
    fun longWritesAreSplitIntoShortRequests() {
        // Камера обрывает соединение на запросе в 1 КБ (проверено: 826 символов проходят, 1198 — нет)
        val fields = (0 until 7).flatMap { day -> (0 until 6).map { "Record[0].TimeSection[$day][$it]" to "2 00:00:00-23:59:59" } }
        val chunks = DahuaRecordingFormat.chunkFields(fields)
        assertEquals(listOf(12, 12, 12, 6), chunks.map { it.size })
        assertEquals(fields, chunks.flatten())
        for (chunk in chunks) assertTrue(DahuaRecordingFormat.setRequest(chunk).length < 800)
        assertEquals(emptyList<List<Pair<String, String>>>(), DahuaRecordingFormat.chunkFields(emptyList()))
    }

    // ---------------- План записи ----------------

    @Test
    fun switchingToAlwaysWritesWholeWeekAndModeOnly() {
        val from = real()
        val to = from.copy(mode = RecordMode.ALWAYS, schedule = WeekSchedule.forMode(RecordMode.ALWAYS))
        val plan = DahuaRecordingFormat.planWrite(from, to, 0)!!
        assertEquals(listOf("RecordMode", "Record"), plan.fields.keys.toList())
        assertEquals(listOf("RecordMode[0].Mode" to "0"), plan.fields["RecordMode"])
        val record = plan.fields["Record"]!!
        assertEquals(42, record.size)
        // Понедельник приложения — день 1 камеры, воскресенье — день 0
        assertEquals("Record[0].TimeSection[1][0]" to "1 00:00:00-23:59:59", record.first())
        assertEquals("Record[0].TimeSection[0][5]" to "0 00:00:00-23:59:59", record.last())
        assertTrue(record.contains("Record[0].TimeSection[0][0]" to "1 00:00:00-23:59:59"))
        assertTrue(record.none { it.first.contains("TimeSection[7]") })
        assertFalse(plan.needsRecordLink)
    }

    @Test
    fun savingMotionModeForcesDetectionOnAndLinksRecording() {
        val from = real().copy(mode = RecordMode.ALWAYS, schedule = WeekSchedule.forMode(RecordMode.ALWAYS))
        val to = from.copy(mode = RecordMode.MOTION, schedule = WeekSchedule.forMode(RecordMode.MOTION))
        val plan = DahuaRecordingFormat.planWrite(from, to, 0)!!
        assertEquals(listOf("MotionDetect[0].Enable" to "true"), plan.fields["MotionDetect"])
        assertEquals("Record[0].TimeSection[1][0]" to "2 00:00:00-23:59:59", plan.fields["Record"]!!.first())
        assertTrue(plan.needsRecordLink)
        // Детекция уже включена — лишнего не пишется
        val onPlan = DahuaRecordingFormat.planWrite(from.copy(motion = from.motion!!.copy(enabled = true)), to.copy(motion = to.motion!!.copy(enabled = true)), 0)!!
        assertNull(onPlan.fields["MotionDetect"])
    }

    @Test
    fun unchangedSettingsWriteNothing() {
        val from = realOn()
        assertTrue(DahuaRecordingFormat.planWrite(from, from, 0)!!.fields.isEmpty())
        // Камера в режиме «по движению» с выключенной детекцией: сохранение включает детекцию, иначе запись не работает
        val off = real()
        assertEquals(listOf("MotionDetect[0].Enable" to "true"), DahuaRecordingFormat.planWrite(off, off, 0)!!.fields["MotionDetect"])
    }

    @Test
    fun customScheduleWritesOnlyChangedDay() {
        val from = realOn()
        val thursday = listOf(RecordSlot(480, 720, RecordKind.ALWAYS), RecordSlot(720, 1080, RecordKind.MOTION))
        val schedule = WeekSchedule(from.schedule!!.days.mapIndexed { index, day -> if (index == 3) thursday else day })
        val to = from.copy(mode = RecordMode.CUSTOM, schedule = schedule)
        val plan = DahuaRecordingFormat.planWrite(from, to, 0)!!
        val empty = "0 00:00:00-23:59:59"
        // Четверг приложения (3) — день 4 камеры
        assertEquals(
            listOf(
                "Record[0].TimeSection[4][0]" to "1 08:00:00-12:00:00",
                "Record[0].TimeSection[4][1]" to "2 12:00:00-18:00:00",
                "Record[0].TimeSection[4][2]" to empty,
                "Record[0].TimeSection[4][3]" to empty,
                "Record[0].TimeSection[4][4]" to empty,
                "Record[0].TimeSection[4][5]" to empty
            ),
            plan.fields["Record"]
        )
        assertEquals(listOf("RecordMode[0].Mode" to "0"), plan.fields["RecordMode"])
        assertTrue(plan.needsRecordLink)
    }

    @Test
    fun tooManySlotsAreRejected() {
        val from = realOn()
        val seven = List(7) { RecordSlot(it * 100, it * 100 + 50, RecordKind.MOTION) }
        val to = from.copy(mode = RecordMode.CUSTOM, schedule = WeekSchedule(List(7) { seven }))
        assertNull(DahuaRecordingFormat.planWrite(from, to, 0))
    }

    @Test
    fun motionSensitivityAndZonesWriteOnlyDifferences() {
        val from = realOn()
        val to = from.copy(motion = from.motion!!.copy(sensitivity = 3, zones = MotionZones(22, 18, setOf(0))))
        val plan = DahuaRecordingFormat.planWrite(from, to, 0)!!
        val window = "MotionDetect[0].MotionDetectWindow[0]"
        val expected = listOf("$window.Sensitive" to "30", "$window.Region[0]" to "1") + (1..17).map { "$window.Region[$it]" to "0" }
        assertEquals(expected, plan.fields["MotionDetect"])
        // Зоны не менялись — строки Region не пишутся
        val sensitivityOnly = DahuaRecordingFormat.planWrite(from, from.copy(motion = from.motion!!.copy(sensitivity = 10)), 0)!!
        assertEquals(listOf("$window.Sensitive" to "100"), sensitivityOnly.fields["MotionDetect"])
        // Сетка не 22x18 — камера её не примет
        assertNull(DahuaRecordingFormat.planWrite(from, from.copy(motion = from.motion!!.copy(zones = MotionZones(20, 15, setOf(0)))), 0))
    }

    @Test
    fun preRecordOverwriteAndPersonAreWritten() {
        val from = realOn()
        val to = from.copy(preRecordSec = 99, overwrite = false, person = true)
        val plan = DahuaRecordingFormat.planWrite(from, to, 0)!!
        assertEquals(listOf("StorageGroup[0].OverWrite" to "false"), plan.fields["StorageGroup"])
        assertEquals(listOf("Record[0].PreRecord" to "30"), plan.fields["Record"])
        assertEquals(
            listOf("SmartMotionDetect[0].Enable" to "true", "SmartMotionDetect[0].ObjectTypes.Human" to "true"),
            plan.fields["SmartMotionDetect"]
        )
        val off = DahuaRecordingFormat.planWrite(to, from.copy(person = false), 1)!!
        assertEquals(listOf("SmartMotionDetect[0].Enable" to "false"), off.fields["SmartMotionDetect"])
        assertEquals(listOf("StorageGroup[1].OverWrite" to "true"), off.fields["StorageGroup"])
    }

    @Test
    fun personForcesDetectionOn() {
        val from = real().copy(mode = RecordMode.ALWAYS, schedule = WeekSchedule.forMode(RecordMode.ALWAYS))
        val plan = DahuaRecordingFormat.planWrite(from, from.copy(person = true), 0)!!
        assertEquals(listOf("MotionDetect[0].Enable" to "true"), plan.fields["MotionDetect"])
    }

    // ---------------- Связка записи с движением ----------------

    @Test
    fun healthyEventHandlerNeedsNoFixes() {
        assertEquals(emptyList<Pair<String, String>>(), DahuaRecordingFormat.recordLinkFixes(parse(motionBody())))
    }

    @Test
    fun disabledRecordInEventHandlerIsSwitchedOn() {
        val fixes = DahuaRecordingFormat.recordLinkFixes(parse(motionBody(recordEnable = false)))
        assertEquals(listOf("MotionDetect[0].EventHandler.RecordEnable" to "true"), fixes)
    }

    @Test
    fun partialArmingScheduleIsReplacedByWholeDay() {
        val empty = "0 00:00:00-23:59:59"
        val fixes = DahuaRecordingFormat.recordLinkFixes(parse(motionBody(arming = mapOf(2 to "1 08:00:00-18:00:00", 5 to "0 00:00:00-23:59:59"))))
        val prefix = "MotionDetect[0].EventHandler.TimeSection"
        val expected = listOf("$prefix[2][0]" to "1 00:00:00-23:59:59") + (1..5).map { "$prefix[2][$it]" to empty } +
            listOf("$prefix[5][0]" to "1 00:00:00-23:59:59") + (1..5).map { "$prefix[5][$it]" to empty }
        assertEquals(expected, fixes)
    }

    // ---------------- Ожидание после записи ----------------

    @Test
    fun expectedMergesSlotsAndDerivesModeFromSchedule() {
        val from = realOn()
        val merged = allDays(listOf(RecordSlot(0, 600, RecordKind.ALWAYS), RecordSlot(600, 1440, RecordKind.ALWAYS)))
        val expected = DahuaRecordingFormat.expectedAfterWrite(from.copy(mode = RecordMode.CUSTOM, schedule = merged))
        // Расписание из одного интервала на весь день камера отдаёт как простой режим
        assertEquals(RecordMode.ALWAYS, expected.mode)
        assertEquals(WeekSchedule.forMode(RecordMode.ALWAYS), expected.schedule)
        assertEquals(RecordMode.OFF, DahuaRecordingFormat.expectedAfterWrite(from.copy(mode = RecordMode.CUSTOM, schedule = allDays(emptyList()))).mode)
        val half = allDays(listOf(RecordSlot(0, 720, RecordKind.ALWAYS)))
        assertEquals(RecordMode.CUSTOM, DahuaRecordingFormat.expectedAfterWrite(from.copy(mode = RecordMode.CUSTOM, schedule = half)).mode)
    }

    @Test
    fun expectedClampsValuesAndForcesDetection() {
        val from = real()
        val expected = DahuaRecordingFormat.expectedAfterWrite(
            from.copy(
                mode = RecordMode.MOTION,
                preRecordSec = 99,
                motion = from.motion!!.copy(enabled = false, sensitivity = 99, zones = MotionZones(22, 18, setOf(0, 5, 400)))
            )
        )
        assertEquals(30, expected.preRecordSec)
        assertEquals(true, expected.motion!!.enabled)
        assertEquals(10, expected.motion!!.sensitivity)
        assertEquals(setOf(0, 5), expected.motion!!.zones!!.cells)
        // Без записи по движению и людей детекцию никто не включает
        val always = DahuaRecordingFormat.expectedAfterWrite(from.copy(mode = RecordMode.ALWAYS))
        assertEquals(false, always.motion!!.enabled)
    }

    @Test
    fun cameraReadBackMatchesExpectationAfterEveryKindOfWrite() {
        val tables = cameraTables(motion = motionBody(enabled = true))
        val from = real(tables)
        val thursday = listOf(RecordSlot(480, 720, RecordKind.ALWAYS), RecordSlot(720, 1080, RecordKind.MOTION))
        val custom = WeekSchedule(from.schedule!!.days.mapIndexed { index, day -> if (index == 3) thursday else day })
        val wanted = listOf(
            from.copy(mode = RecordMode.ALWAYS),
            from.copy(mode = RecordMode.OFF),
            from.copy(mode = RecordMode.CUSTOM, schedule = custom),
            from.copy(mode = RecordMode.CUSTOM, schedule = allDays(listOf(RecordSlot(0, 600, RecordKind.ALWAYS), RecordSlot(600, 1440, RecordKind.ALWAYS)))),
            from.copy(preRecordSec = 12, overwrite = false, person = true),
            from.copy(motion = from.motion!!.copy(sensitivity = 2, zones = MotionZones(22, 18, setOf(0, 1, 22, 395))))
        )
        for (to in wanted) {
            val plan = DahuaRecordingFormat.planWrite(from, to, 0)
            assertNotNull(plan)
            val actual = DahuaRecordingFormat.settingsFrom(applied(tables, plan!!))
            val expected = DahuaRecordingFormat.expectedAfterWrite(to)
            assertTrue("камера вернула бы не то, что записано: $to", CameraRecording.matches(actual, expected))
        }
    }
}
