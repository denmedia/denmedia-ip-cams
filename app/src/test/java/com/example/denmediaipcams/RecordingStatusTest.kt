package com.example.denmediaipcams

import com.example.denmediaipcams.network.HipcamRecordingCodec
import com.example.denmediaipcams.network.LiveRecording
import com.example.denmediaipcams.network.MotionSettings
import com.example.denmediaipcams.network.RecordKind
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordSlot
import com.example.denmediaipcams.network.RecordingActivity
import com.example.denmediaipcams.network.RecordingIndicator
import com.example.denmediaipcams.network.RecordingSettings
import com.example.denmediaipcams.network.StorageInfo
import com.example.denmediaipcams.network.StorageVolume
import com.example.denmediaipcams.network.WeekSchedule
import com.example.denmediaipcams.ui.components.loaderAlpha
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStatusTest {

    // 2026-10-12 — понедельник
    private val mondayNoon = LocalDateTime.of(2026, 10, 12, 12, 0)
    private val mondayNight = LocalDateTime.of(2026, 10, 12, 3, 0)
    private val sundayNoon = LocalDateTime.of(2026, 10, 18, 12, 0)

    private fun settings(
        mode: RecordMode,
        schedule: WeekSchedule? = WeekSchedule.forMode(mode.takeIf { it != RecordMode.CUSTOM } ?: RecordMode.OFF),
        motion: Boolean? = null,
        person: Boolean? = null
    ) = RecordingSettings(
        mode = mode,
        supportedModes = RecordMode.entries.toSet(),
        schedule = schedule,
        scheduleSlotsPerDay = 6,
        scheduleKinds = RecordKind.entries.toSet(),
        preRecordSec = null,
        preRecordMaxSec = 0,
        overwrite = null,
        motion = motion?.let { MotionSettings(it, 3, 6, null) },
        person = person
    )

    private fun indicator(s: RecordingSettings, live: LiveRecording = LiveRecording.NOT_REPORTED, card: Boolean? = true, now: LocalDateTime = mondayNoon) =
        RecordingActivity.indicator(s, live, card, now)

    @Test
    fun alwaysModeRecordsAnyTime() {
        assertEquals(RecordingIndicator.RECORDING, indicator(settings(RecordMode.ALWAYS)))
        assertEquals(RecordingIndicator.RECORDING, indicator(settings(RecordMode.ALWAYS), now = mondayNight))
    }

    @Test
    fun scheduleRecordsOnlyInsideAlwaysIntervalOfThatDay() {
        // Понедельник 08:00–18:00, остальные дни пусты
        val days = List(7) { if (it == 0) listOf(RecordSlot(480, 1080, RecordKind.ALWAYS)) else emptyList() }
        val s = settings(RecordMode.CUSTOM, WeekSchedule(days))
        assertEquals(RecordingIndicator.RECORDING, indicator(s, now = mondayNoon))
        assertEquals(RecordingIndicator.IDLE, indicator(s, now = mondayNight))
        assertEquals(RecordingIndicator.IDLE, indicator(s, now = sundayNoon))
    }

    @Test
    fun intervalEndIsExclusive() {
        val s = settings(RecordMode.CUSTOM, WeekSchedule(List(7) { listOf(RecordSlot(0, 720, RecordKind.ALWAYS)) }))
        assertTrue(RecordingActivity.scheduledNow(s, LocalDateTime.of(2026, 10, 12, 11, 59)))
        assertFalse(RecordingActivity.scheduledNow(s, LocalDateTime.of(2026, 10, 12, 12, 0)))
    }

    @Test
    fun motionIntervalIsNotContinuousRecording() {
        val s = settings(RecordMode.MOTION, motion = true)
        assertEquals(RecordingIndicator.STANDBY, indicator(s))
    }

    @Test
    fun detectionOnMeansGreenWhenNotRecording() {
        assertEquals(RecordingIndicator.STANDBY, indicator(settings(RecordMode.OFF, motion = true)))
        assertEquals(RecordingIndicator.STANDBY, indicator(settings(RecordMode.OFF, person = true)))
        assertEquals(RecordingIndicator.IDLE, indicator(settings(RecordMode.OFF, motion = false, person = false)))
        assertEquals(RecordingIndicator.IDLE, indicator(settings(RecordMode.OFF)))
    }

    @Test
    fun recordingBeatsDetection() {
        assertEquals(RecordingIndicator.RECORDING, indicator(settings(RecordMode.ALWAYS, motion = true, person = true)))
    }

    @Test
    fun cameraOwnAnswerBeatsSchedule() {
        // Камера сказала «пишу» (запись по тревоге) — красная, хотя по расписанию записи нет
        val motionOnly = settings(RecordMode.MOTION, motion = true)
        assertEquals(RecordingIndicator.RECORDING, indicator(motionOnly, live = LiveRecording.RECORDING))
        // Камера сказала «не пишу» — не верим расписанию «постоянно»
        assertEquals(RecordingIndicator.IDLE, indicator(settings(RecordMode.ALWAYS), live = LiveRecording.NOT_RECORDING))
        assertEquals(RecordingIndicator.STANDBY, indicator(settings(RecordMode.ALWAYS, motion = true), live = LiveRecording.NOT_RECORDING))
    }

    @Test
    fun unreachableCameraIsLost() {
        assertEquals(RecordingIndicator.LOST, indicator(settings(RecordMode.ALWAYS), live = LiveRecording.UNREACHABLE))
    }

    @Test
    fun missingCardStopsScheduledRecordingButNotCameraAnswer() {
        val always = settings(RecordMode.ALWAYS)
        assertEquals(RecordingIndicator.IDLE, indicator(always, card = false))
        assertEquals(RecordingIndicator.RECORDING, indicator(always, card = null))
        assertEquals(RecordingIndicator.RECORDING, indicator(always, live = LiveRecording.RECORDING, card = false))
    }

    @Test
    fun cardUsableRequiresHealthyWritableVolume() {
        fun info(vararg volumes: StorageVolume) = StorageInfo(volumes.toList(), null)
        assertFalse(RecordingActivity.cardUsable(info()))
        assertTrue(RecordingActivity.cardUsable(info(StorageVolume("/sd", 100, 10, state = "Success", type = "ReadWrite"))))
        assertTrue(RecordingActivity.cardUsable(info(StorageVolume("/mnt/sd", null, null))))
        assertFalse(RecordingActivity.cardUsable(info(StorageVolume("/sd", 100, 10, state = "Success", type = "ReadOnly"))))
        assertFalse(RecordingActivity.cardUsable(info(StorageVolume("/sd", 100, 10, state = "Error"))))
    }

    @Test
    fun hipcamRecStatusParsing() {
        assertEquals(false, HipcamRecordingCodec.parseRecStatus("var rec_status=\"none\";\n"))
        assertEquals(false, HipcamRecordingCodec.parseRecStatus("var rec_status=\"NONE\";"))
        assertEquals(false, HipcamRecordingCodec.parseRecStatus("var rec_status=\"\";"))
        assertEquals(true, HipcamRecordingCodec.parseRecStatus("var rec_status=\"alarm\";"))
        assertNull(HipcamRecordingCodec.parseRecStatus("Error 404: Not Found\ninvalid request"))
    }

    @Test
    fun loaderDotsFadeInTurnAndStayInRange() {
        for (step in 0..100) {
            val phase = step / 100f
            for (index in 0..2) assertTrue(loaderAlpha(phase, index) in 0.199f..1.001f)
        }
        // Точки сдвинуты по фазе: не гаснут и не загораются все разом
        assertTrue(loaderAlpha(0.05f, 0) != loaderAlpha(0.05f, 1))
        assertTrue(loaderAlpha(0.05f, 1) != loaderAlpha(0.05f, 2))
        // Цикл замкнут: в начале и в конце прозрачность одна и та же
        assertEquals(loaderAlpha(0f, 1), loaderAlpha(1f, 1), 0.0001f)
    }
}
