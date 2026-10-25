package com.example.denmediaipcams

import com.example.denmediaipcams.network.HikvisionRecordingCodec
import com.example.denmediaipcams.network.RecordKind
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordSlot
import com.example.denmediaipcams.network.WeekSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор и сборка XML записи по настоящим ответам DS-I114W (прошивка V5.4.81) */
class HikvisionRecordingCodecTest {

    private fun action(day: String, from: String, to: String, mode: String, id: Int = 1) =
        "<ScheduleAction><id>$id</id><ScheduleActionStartTime><DayOfWeek>$day</DayOfWeek><TimeOfDay>$from</TimeOfDay></ScheduleActionStartTime>" +
            "<ScheduleActionEndTime><DayOfWeek>$day</DayOfWeek><TimeOfDay>$to</TimeOfDay></ScheduleActionEndTime>" +
            "<ScheduleDSTEnable>false</ScheduleDSTEnable><Description>nothing</Description>" +
            "<Actions><Record>true</Record><ActionRecordingMode>$mode</ActionRecordingMode></Actions></ScheduleAction>"

    private fun track(actions: String, pre: Int = 5, loop: Boolean = true) = """<?xml version="1.0" encoding="UTF-8"?>
<Track version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<id>101</id>
<LoopEnable>$loop</LoopEnable>
<TrackSchedule>
<ScheduleBlock ScheduleActionSize="8" >
<ScheduleBlockGUID>{00000000-0000-0000-0000-000000000000}</ScheduleBlockGUID>
<ScheduleBlockType>www.hikvision.com/racm/schedule/ver10</ScheduleBlockType>
$actions
</ScheduleBlock>
</TrackSchedule>
<CustomExtensionList>
<CustomExtension>
<CustomExtensionName>www.hikvision.com/RaCM/trackExt/ver10</CustomExtensionName>
<enableSchedule>true</enableSchedule>
<PreRecordTimeSeconds>$pre</PreRecordTimeSeconds>
<PostRecordTimeSeconds>5</PostRecordTimeSeconds>
</CustomExtension>
</CustomExtensionList>
</Track>"""

    private val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    @Test
    fun emptyScheduleMeansNoRecording() {
        // Так камера отвечала до включения записи: у блока расписания нет ни одного интервала
        val schedule = HikvisionRecordingCodec.parseSchedule(track(""))!!
        assertEquals(RecordMode.OFF, schedule.deriveMode())
    }

    @Test
    fun placeholderIntervalsAreIgnored() {
        // Камера в описании возможностей показывает пустые интервалы 00:00–00:00
        val schedule = HikvisionRecordingCodec.parseSchedule(track(action("Monday", "00:00:00", "00:00:00", "CMR")))!!
        assertEquals(RecordMode.OFF, schedule.deriveMode())
    }

    @Test
    fun continuousWeekIsAlwaysMode() {
        val xml = track(days.joinToString("") { action(it, "00:00:00", "24:00:00", "CMR") })
        val schedule = HikvisionRecordingCodec.parseSchedule(xml)!!
        assertEquals(RecordMode.ALWAYS, schedule.deriveMode())
        assertEquals(listOf(RecordSlot(0, 1440, RecordKind.ALWAYS)), schedule.days[6])
    }

    @Test
    fun motionWeekAndMixedSchedule() {
        val motion = track(days.joinToString("") { action(it, "00:00:00", "24:00:00", "MOTION") })
        assertEquals(RecordMode.MOTION, HikvisionRecordingCodec.parseSchedule(motion)!!.deriveMode())

        val mixed = track(
            action("Monday", "08:30:00", "18:00:00", "CMR", 1) + action("Monday", "22:00:00", "24:00:00", "MOTION", 2)
        )
        val schedule = HikvisionRecordingCodec.parseSchedule(mixed)!!
        assertEquals(RecordMode.CUSTOM, schedule.deriveMode())
        assertEquals(
            listOf(RecordSlot(510, 1080, RecordKind.ALWAYS), RecordSlot(1320, 1440, RecordKind.MOTION)),
            schedule.days[0]
        )
        assertTrue(schedule.days[1].isEmpty())
    }

    @Test
    fun unknownRecordingModeIsNotEditable() {
        // Режим, которого приложение не знает (тревога, события), нельзя молча заменить на другой
        assertNull(HikvisionRecordingCodec.parseSchedule(track(action("Monday", "00:00:00", "24:00:00", "ALARM"))))
    }

    @Test
    fun intervalAcrossMidnightIsNotEditable() {
        val across = "<ScheduleAction><id>1</id><ScheduleActionStartTime><DayOfWeek>Monday</DayOfWeek><TimeOfDay>22:00:00</TimeOfDay></ScheduleActionStartTime>" +
            "<ScheduleActionEndTime><DayOfWeek>Tuesday</DayOfWeek><TimeOfDay>06:00:00</TimeOfDay></ScheduleActionEndTime>" +
            "<Actions><Record>true</Record><ActionRecordingMode>CMR</ActionRecordingMode></Actions></ScheduleAction>"
        assertNull(HikvisionRecordingCodec.parseSchedule(track(across)))
    }

    @Test
    fun writtenScheduleReadsBackTheSame() {
        val wanted = WeekSchedule(
            List(7) { day ->
                if (day == 2) {
                    listOf(RecordSlot(0, 360, RecordKind.MOTION), RecordSlot(360, 1440, RecordKind.ALWAYS))
                } else emptyList()
            }
        )
        val written = HikvisionRecordingCodec.withSchedule(track(days.joinToString("") { action(it, "00:00:00", "24:00:00", "CMR") }), wanted)!!
        assertEquals(wanted, HikvisionRecordingCodec.parseSchedule(written))
        // Остальное в ответе камеры остаётся как было
        assertTrue(written.contains("<ScheduleBlockGUID>{00000000-0000-0000-0000-000000000000}</ScheduleBlockGUID>"))
        assertTrue(written.contains("<PostRecordTimeSeconds>5</PostRecordTimeSeconds>"))
        assertTrue(written.contains("<TimeOfDay>06:00:00</TimeOfDay>"))
    }

    @Test
    fun wholeDayEndsAt24() {
        val xml = HikvisionRecordingCodec.withSchedule(track(""), WeekSchedule.forMode(RecordMode.ALWAYS))!!
        assertEquals(7, Regex("<TimeOfDay>24:00:00</TimeOfDay>").findAll(xml).count())
        assertEquals(WeekSchedule.forMode(RecordMode.ALWAYS), HikvisionRecordingCodec.parseSchedule(xml))
    }

    @Test
    fun switchingOffLeavesNoIntervals() {
        val xml = HikvisionRecordingCodec.withSchedule(
            track(days.joinToString("") { action(it, "00:00:00", "24:00:00", "CMR") }), WeekSchedule.forMode(RecordMode.OFF)
        )!!
        assertFalse(xml.contains("<ScheduleAction>"))
        assertEquals(RecordMode.OFF, HikvisionRecordingCodec.parseSchedule(xml)!!.deriveMode())
    }

    @Test
    fun changesTagValuesOnly() {
        val xml = track("", pre = 5, loop = true)
        assertEquals("5", HikvisionRecordingCodec.tag(xml, "PreRecordTimeSeconds"))
        val changed = HikvisionRecordingCodec.withTag(xml, "PreRecordTimeSeconds", "15")!!
        assertEquals("15", HikvisionRecordingCodec.tag(changed, "PreRecordTimeSeconds"))
        assertEquals("5", HikvisionRecordingCodec.tag(changed, "PostRecordTimeSeconds"))
        assertEquals("false", HikvisionRecordingCodec.tag(HikvisionRecordingCodec.withTag(xml, "LoopEnable", "false")!!, "LoopEnable"))
        assertNull(HikvisionRecordingCodec.withTag(xml, "NoSuchTag", "1"))
    }

    @Test
    fun readsMotionTagWithAttributes() {
        // В описании возможностей у тегов есть атрибуты opt/min/max
        val xml = """<MotionDetection><enabled opt="true,false">true</enabled><enableHighlight>false</enableHighlight>
<sensitivityLevel min="0" max="100" step="20">80</sensitivityLevel></MotionDetection>"""
        assertEquals("true", HikvisionRecordingCodec.tag(xml, "enabled"))
        assertEquals("80", HikvisionRecordingCodec.tag(xml, "sensitivityLevel"))
        assertEquals("60", HikvisionRecordingCodec.tag(HikvisionRecordingCodec.withTag(xml, "sensitivityLevel", "60")!!, "sensitivityLevel"))
    }

    @Test
    fun sensitivityScalesBetweenCameraAndApp() {
        assertEquals(4, HikvisionRecordingCodec.sensitivityOf(80))
        assertEquals(1, HikvisionRecordingCodec.sensitivityOf(0))
        assertEquals(5, HikvisionRecordingCodec.sensitivityOf(100))
        assertEquals(80, HikvisionRecordingCodec.levelOf(4))
        for (level in 1..5) assertEquals(level, HikvisionRecordingCodec.sensitivityOf(HikvisionRecordingCodec.levelOf(level)))
    }

    // Триггер движения, как его отдаёт камера: у уведомлений нет id
    private val emptyTrigger = """<EventTrigger version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<id>VMD-1</id><eventType>VMD</eventType><videoInputChannelID>1</videoInputChannelID>
<EventTriggerNotificationList>
</EventTriggerNotificationList>
</EventTrigger>"""

    private val recordingTrigger = """<EventTrigger><id>VMD-1</id><EventTriggerNotificationList>
<EventTriggerNotification>
<id>record-1</id>
<notificationMethod>record</notificationMethod>
<videoInputID>1</videoInputID>
<notificationRecurrence>beginning</notificationRecurrence>
</EventTriggerNotification>
</EventTriggerNotificationList></EventTrigger>"""

    @Test
    fun detectsRecordingLink() {
        assertFalse(HikvisionRecordingCodec.linksToRecording(emptyTrigger))
        assertTrue(HikvisionRecordingCodec.linksToRecording(recordingTrigger))
    }

    @Test
    fun addsRecordingLinkToEmptyTrigger() {
        val linked = HikvisionRecordingCodec.withRecordingLink(emptyTrigger)!!
        assertTrue(HikvisionRecordingCodec.linksToRecording(linked))
        assertTrue(linked.contains("<id>record-1</id>"))
        assertTrue(linked.contains("<videoInputID>1</videoInputID>"))
        assertTrue(linked.contains("<id>VMD-1</id>"))
    }

    @Test
    fun keepsOtherNotificationsAndGivesThemIds() {
        // При чтении id у уведомлений нет, а при записи он нужен: берётся из способа уведомления
        val beep = """<EventTrigger><id>VMD-1</id><EventTriggerNotificationList>
<EventTriggerNotification><notificationMethod>beep</notificationMethod><notificationRecurrence>beginning</notificationRecurrence></EventTriggerNotification>
</EventTriggerNotificationList></EventTrigger>"""
        val linked = HikvisionRecordingCodec.withRecordingLink(beep)!!
        assertTrue(linked.contains("<id>beep</id><notificationMethod>beep</notificationMethod>"))
        assertTrue(HikvisionRecordingCodec.linksToRecording(linked))
        assertNotNull(HikvisionRecordingCodec.withRecordingLink(linked))
        assertEquals(2, Regex("<EventTriggerNotification>").findAll(linked).count())
    }

    @Test
    fun triggerWithoutNotificationListIsRejected() {
        assertNull(HikvisionRecordingCodec.withRecordingLink("<EventTrigger><id>VMD-1</id></EventTrigger>"))
    }
}
