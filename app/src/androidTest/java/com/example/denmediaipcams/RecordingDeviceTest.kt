package com.example.denmediaipcams

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraRecording
import com.example.denmediaipcams.network.RecordMode
import com.example.denmediaipcams.network.RecordingWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка записи/детекции на настоящей камере. Запускается только с аргументами:
 * -e recHost … -e recUser … -e recPass … -e recKind dahua|hipcam|dvrip [-e recWrite 1]
 * (учётные данные в код не записываются). Без recWrite только читает. С recWrite меняет чувствительность,
 * предзапись и режим записи, а в конце возвращает прочитанные вначале настройки.
 */
@RunWith(AndroidJUnit4::class)
class RecordingDeviceTest {

    private val tag = "RecTest"

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("recHost")
        assumeTrue("нет аргументов камеры", host != null)
        val kind = args.getString("recKind")
        return IpCamera(
            id = "device-test",
            name = "test-$kind",
            ipAddress = host!!,
            username = args.getString("recUser").orEmpty(),
            password = args.getString("recPass").orEmpty(),
            dahuaApi = kind == "dahua",
            sdHttpArchive = kind == "hipcam",
            dvripArchive = kind == "dvrip"
        )
    }

    @Test
    fun readsSettings() = runBlocking<Unit> {
        val settings = CameraRecording.read(camera())
        Log.i(tag, "settings: $settings")
        assertNotNull(settings)
    }

    @Test
    fun writesAndRestores() = runBlocking<Unit> {
        assumeTrue("запись не разрешена", InstrumentationRegistry.getArguments().getString("recWrite") == "1")
        val camera = camera()
        val original = CameraRecording.read(camera)
        assertNotNull(original)
        original!!
        Log.i(tag, "original: $original")
        try {
            original.motion?.let { motion ->
                val changed = if (motion.sensitivity < motion.sensitivityMax) motion.sensitivity + 1 else motion.sensitivity - 1
                val result = CameraRecording.write(camera, original.copy(motion = motion.copy(sensitivity = changed)))
                Log.i(tag, "sensitivity -> $changed: $result")
                assertTrue("чувствительность: $result", result is RecordingWriteResult.Saved)
            }
            original.preRecordSec?.let { seconds ->
                val changed = if (seconds < original.preRecordMaxSec) seconds + 1 else seconds - 1
                val result = CameraRecording.write(camera, original.copy(preRecordSec = changed))
                Log.i(tag, "preRecord -> $changed: $result")
                assertTrue("предзапись: $result", result is RecordingWriteResult.Saved)
            }
            if (RecordMode.OFF in original.supportedModes && original.mode != RecordMode.OFF) {
                val result = CameraRecording.write(camera, original.copy(mode = RecordMode.OFF))
                Log.i(tag, "mode -> OFF: $result")
                assertTrue("режим OFF: $result", result is RecordingWriteResult.Saved && result.settings.mode == RecordMode.OFF)
            }
        } finally {
            val restored = CameraRecording.write(camera, original)
            Log.i(tag, "restore: $restored")
            assertTrue("возврат прежних настроек: $restored", restored is RecordingWriteResult.Saved)
        }
        val after = CameraRecording.read(camera)
        Log.i(tag, "after: $after")
        assertTrue("после возврата настройки отличаются", CameraRecording.matches(after, original))
    }
}
