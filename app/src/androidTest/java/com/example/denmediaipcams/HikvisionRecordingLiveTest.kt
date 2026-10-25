package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraRecording
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordingSettings
import com.example.denmediaipcams.network.RecordingWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Управление записью камеры Hikvision на настоящей камере: чтение, смена режима и предзаписи с возвратом прежних
 * настроек. Аргументы: -e hikHost … -e hikUser … -e hikPass …
 */
@RunWith(AndroidJUnit4::class)
class HikvisionRecordingLiveTest {

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("hikHost")
        assumeTrue("нет аргументов камеры", host != null)
        return IpCamera(
            id = "hik-rec-test", name = "test", ipAddress = host!!,
            username = args.getString("hikUser").orEmpty(), password = args.getString("hikPass").orEmpty(),
            hikvisionArchive = true
        )
    }

    private fun write(cam: IpCamera, settings: RecordingSettings): RecordingSettings {
        val result = runBlocking { CameraRecording.write(cam, settings) }
        assertTrue("запись настроек: $result", result is RecordingWriteResult.Saved)
        return (result as RecordingWriteResult.Saved).settings
    }

    @Test
    fun readsAndChangesRecordingSettings() {
        val cam = camera()
        assertTrue(CameraRecording.canTry(cam))
        val original = runBlocking { CameraRecording.read(cam) }
        assertNotNull("камера не отдала настройки записи", original)
        original!!
        println("HIKREC исходные: режим=${original.mode} предзапись=${original.preRecordSec} перезапись=${original.overwrite} движение=${original.motion}")
        assertTrue(RecordMode.MOTION in original.supportedModes)

        try {
            // Предзапись
            val pre = if (original.preRecordSec == 10) 15 else 10
            val changedPre = write(cam, original.copy(preRecordSec = pre))
            assertEquals(pre, changedPre.preRecordSec)

            // По движению: расписание «по движению» и детекция включена
            val motion = write(cam, changedPre.copy(mode = RecordMode.MOTION))
            println("HIKREC по движению: режим=${motion.mode} движение=${motion.motion}")
            assertEquals(RecordMode.MOTION, motion.mode)
            assertEquals(true, motion.motion?.enabled)

            // Чувствительность
            val sens = if (motion.motion?.sensitivity == 3) 2 else 3
            val changedSens = write(cam, motion.copy(motion = motion.motion!!.copy(sensitivity = sens)))
            assertEquals(sens, changedSens.motion?.sensitivity)

            // Выключить запись и включить постоянную
            assertEquals(RecordMode.OFF, write(cam, changedSens.copy(mode = RecordMode.OFF)).mode)
            assertEquals(RecordMode.ALWAYS, write(cam, changedSens.copy(mode = RecordMode.ALWAYS)).mode)
        } finally {
            val restored = write(cam, original)
            println("HIKREC возвращено: режим=${restored.mode} предзапись=${restored.preRecordSec} движение=${restored.motion}")
            assertEquals(original.mode, restored.mode)
            assertEquals(original.preRecordSec, restored.preRecordSec)
            assertEquals(original.motion?.sensitivity, restored.motion?.sensitivity)
            assertEquals(original.motion?.enabled, restored.motion?.enabled)
        }
    }
}
