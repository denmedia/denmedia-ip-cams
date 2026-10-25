package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraDetails
import com.example.denmediaipcams.network.PtzController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Дополнительные сведения камеры на настоящей камере: ONVIF-данные, движение, изображение, названия пресетов.
 * Изображение «меняется» на то же значение, что и сейчас, — настройки камеры остаются прежними.
 * Аргументы: -e addHost, -e onvifPort, -e httpPort, -e dahua (true/false), -e addUser, -e addPass
 */
@RunWith(AndroidJUnit4::class)
class CameraDetailsLiveTest {

    @Test
    fun readsDetails() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        val camera = IpCamera(
            id = "live", name = "live", ipAddress = host!!,
            httpPort = args.getString("httpPort")?.toIntOrNull() ?: 80,
            onvifPort = args.getString("onvifPort")?.toIntOrNull() ?: 80,
            username = args.getString("addUser").orEmpty(), password = args.getString("addPass").orEmpty(),
            protocol = CameraProtocol.ONVIF, dahuaApi = args.getString("dahua") == "true"
        )
        val details = CameraDetails.onvifDetails(camera)
        println("DETAILS[$host] device=${details?.device}")
        details?.streams?.forEach { println("DETAILS[$host] stream=$it") }
        println("DETAILS[$host] motion=${CameraDetails.motionReport(camera)}")
        val params = CameraDetails.imagingParams(camera)
        println("DETAILS[$host] imaging=$params")
        params?.firstOrNull()?.let {
            val ok = CameraDetails.setImagingParam(camera, it.key, it.value)
            val after = CameraDetails.imagingParams(camera)?.firstOrNull { p -> p.key == it.key }
            println("DETAILS[$host] set ${it.key}=${it.value} (same value) ok=$ok after=${after?.value}")
            assertTrue("SetImagingSettings отклонён", ok)
        }
        println("DETAILS[$host] presetNames=${PtzController().fetchPresetNames(camera)}")
    }
}
