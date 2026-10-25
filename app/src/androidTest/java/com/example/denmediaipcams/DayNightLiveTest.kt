package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.DayNightMode
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.PtzCommand
import com.example.denmediaipcams.network.PtzController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Переключение день/ночь на настоящей камере (по ONVIF-протоколу в настройках) с возвратом исходного режима.
 * Аргументы: -e addHost, -e onvifPort, -e httpPort, -e addUser, -e addPass
 */
@RunWith(AndroidJUnit4::class)
class DayNightLiveTest {

    @Test
    fun switchesAndRestores() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        val camera = IpCamera(
            id = "dn", name = "dn", ipAddress = host!!,
            httpPort = args.getString("httpPort")?.toIntOrNull() ?: 80,
            onvifPort = args.getString("onvifPort")?.toIntOrNull() ?: 80,
            username = args.getString("addUser").orEmpty(), password = args.getString("addPass").orEmpty(),
            protocol = CameraProtocol.ONVIF
        )
        val ptz = PtzController()
        val original = ptz.fetchDayNightMode(camera)
        println("DAYNIGHT start=$original")
        try {
            for (mode in listOf(DayNightMode.NIGHT, DayNightMode.DAY)) {
                val ok = ptz.executeCommand(camera, PtzCommand.SetDayNightMode(mode))
                delay(1500)
                val read = ptz.fetchDayNightMode(camera)
                println("DAYNIGHT set=$mode ok=$ok read=$read")
                assertEquals(mode, read)
            }
        } finally {
            original?.let { ptz.executeCommand(camera, PtzCommand.SetDayNightMode(it)) }
            delay(1500)
            println("DAYNIGHT restored=${ptz.fetchDayNightMode(camera)}")
        }
    }
}
