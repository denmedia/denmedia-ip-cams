package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.PtzCommand
import com.example.denmediaipcams.network.PtzController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Режим день/ночь камеры Hikvision не зависит от выбранного протокола PTZ: у камеры протокол «нет PTZ», режим всё равно
 * определяется и переключается по ISAPI. Исходный режим возвращается. Аргументы: -e hikHost … -e hikUser … -e hikPass …
 */
@RunWith(AndroidJUnit4::class)
class HikvisionDayNightLiveTest {

    @Test
    fun readsAndSwitchesDayNightWithoutPtzProtocol() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("hikHost")
        assumeTrue("нет аргументов камеры", host != null)
        val camera = IpCamera(
            id = "hik-daynight-test", name = "test", ipAddress = host!!,
            username = args.getString("hikUser").orEmpty(), password = args.getString("hikPass").orEmpty(),
            protocol = CameraProtocol.NONE, hikvisionArchive = true
        )
        val controller = PtzController()
        runBlocking {
            val original = controller.fetchDayNightMode(camera)
            println("HIKDN исходный режим: $original")
            assertNotNull("камера не отдала режим день/ночь", original)
            try {
                for (mode in listOf(DayNightMode.NIGHT, DayNightMode.DAY, DayNightMode.AUTO)) {
                    assertTrue("не удалось переключить в $mode", controller.executeCommand(camera, PtzCommand.SetDayNightMode(mode)))
                    assertEquals(mode, controller.fetchDayNightMode(camera))
                    println("HIKDN переключено: $mode")
                }
            } finally {
                controller.executeCommand(camera, PtzCommand.SetDayNightMode(original!!))
            }
            assertEquals(original, controller.fetchDayNightMode(camera))
        }
    }
}
