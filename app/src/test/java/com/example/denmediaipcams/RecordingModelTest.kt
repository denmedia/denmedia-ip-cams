package com.example.denmediaipcams

import com.example.denmediaipcams.network.CameraRecording
import com.example.denmediaipcams.network.MotionSettings
import com.example.denmediaipcams.network.MotionZones
import com.example.denmediaipcams.network.RecordKind
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordSlot
import com.example.denmediaipcams.network.RecordingSettings
import com.example.denmediaipcams.network.WeekSchedule
import com.example.denmediaipcams.network.normalized
import com.example.denmediaipcams.ui.components.scheduleProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingModelTest {

    private fun settings(mode: RecordMode, schedule: WeekSchedule? = WeekSchedule.forMode(RecordMode.OFF)) = RecordingSettings(
        mode = mode,
        supportedModes = RecordMode.entries.toSet(),
        schedule = schedule,
        scheduleSlotsPerDay = 6,
        scheduleKinds = RecordKind.entries.toSet(),
        preRecordSec = 5,
        preRecordMaxSec = 30,
        overwrite = true,
        motion = MotionSettings(enabled = true, sensitivity = 3, sensitivityMax = 6, zones = MotionZones(22, 18, setOf(0, 1, 22))),
        person = null
    )

    @Test
    fun simpleModesHaveMatchingSchedules() {
        for (mode in listOf(RecordMode.OFF, RecordMode.ALWAYS, RecordMode.MOTION)) {
            assertEquals(mode, WeekSchedule.forMode(mode).deriveMode())
        }
    }

    @Test
    fun partialScheduleIsCustom() {
        val day = listOf(RecordSlot(480, 1080, RecordKind.ALWAYS))
        val schedule = WeekSchedule(List(7) { day })
        assertEquals(RecordMode.CUSTOM, schedule.deriveMode())
    }

    @Test
    fun mixedKindsAreCustom() {
        val days = List(7) { listOf(RecordSlot(0, 1440, if (it == 0) RecordKind.MOTION else RecordKind.ALWAYS)) }
        assertEquals(RecordMode.CUSTOM, WeekSchedule(days).deriveMode())
    }

    @Test
    fun customScheduleEqualToSimpleModeIsNormalized() {
        val always = settings(RecordMode.CUSTOM, WeekSchedule.forMode(RecordMode.ALWAYS))
        assertEquals(RecordMode.ALWAYS, always.normalized().mode)
        val real = settings(RecordMode.CUSTOM, WeekSchedule(List(7) { listOf(RecordSlot(0, 600, RecordKind.ALWAYS)) }))
        assertEquals(RecordMode.CUSTOM, real.normalized().mode)
    }

    @Test
    fun matchingIgnoresSlotOrderAndUnexpectedFields() {
        val slotsA = listOf(RecordSlot(0, 600, RecordKind.ALWAYS), RecordSlot(700, 900, RecordKind.MOTION))
        val a = settings(RecordMode.CUSTOM, WeekSchedule(List(7) { slotsA }))
        val b = settings(RecordMode.CUSTOM, WeekSchedule(List(7) { slotsA.reversed() }))
        assertTrue(CameraRecording.matches(a, b))
        // Поле, которое не ожидается (null), не сравнивается
        assertTrue(CameraRecording.matches(a, b.copy(overwrite = null, motion = null)))
        assertFalse(CameraRecording.matches(a, b.copy(preRecordSec = 10)))
        assertFalse(CameraRecording.matches(null, b))
    }

    @Test
    fun matchingComparesMotionAndZones() {
        val base = settings(RecordMode.ALWAYS)
        val motion = base.motion!!
        assertFalse(CameraRecording.matches(base, base.copy(motion = motion.copy(sensitivity = 4))))
        assertFalse(CameraRecording.matches(base, base.copy(motion = motion.copy(zones = motion.zones!!.copy(cells = setOf(0))))))
        assertTrue(CameraRecording.matches(base, base.copy(motion = motion.copy(zones = null))))
    }

    @Test
    fun scheduleProblemsAreReported() {
        val ok = WeekSchedule(List(7) { listOf(RecordSlot(0, 600, RecordKind.ALWAYS), RecordSlot(600, 1440, RecordKind.MOTION)) })
        assertNull(scheduleProblem(ok, 6))
        val overlap = WeekSchedule(List(7) { listOf(RecordSlot(0, 700, RecordKind.ALWAYS), RecordSlot(600, 900, RecordKind.ALWAYS)) })
        assertTrue(scheduleProblem(overlap, 6)!!.contains("пересекаются"))
        val reversed = WeekSchedule(List(7) { listOf(RecordSlot(900, 600, RecordKind.ALWAYS)) })
        assertTrue(scheduleProblem(reversed, 6)!!.contains("раньше"))
        assertTrue(scheduleProblem(ok, 1)!!.contains("не больше 1"))
    }
}
