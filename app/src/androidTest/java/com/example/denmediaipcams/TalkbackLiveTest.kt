package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraTalkback
import com.example.denmediaipcams.network.DahuaTalkback
import com.example.denmediaipcams.network.G711
import com.example.denmediaipcams.network.TalkbackChannel
import com.example.denmediaipcams.network.TalkbackRefusedException
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Канал разговора с настоящей камерой: открывается, принимает секунду тишины (G.711, 40 мс кадрами, как в приложении)
 * и закрывается; динамик при этом молчит. Аргументы: Hikvision — `-e hikHost … -e hikUser … -e hikPass …`,
 * Dahua — `-e dahuaHost … -e dahuaUser … -e dahuaPass …`.
 */
@RunWith(AndroidJUnit4::class)
class TalkbackLiveTest {

    private fun camera(prefix: String, hikvision: Boolean): IpCamera? {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("${prefix}Host") ?: return null
        return IpCamera(
            id = "$prefix-talk-test", name = "test", ipAddress = host,
            username = args.getString("${prefix}User").orEmpty(), password = args.getString("${prefix}Pass").orEmpty(),
            protocol = CameraProtocol.NONE, hikvisionArchive = hikvision, dahuaApi = !hikvision
        )
    }

    private fun speakSilence(camera: IpCamera, open: (IpCamera) -> TalkbackChannel = CameraTalkback::open) {
        val channel = open(camera)
        try {
            println("TALK ${camera.ipAddress}: канал открыт, кодек ${channel.law}")
            val frame = ByteArray(320) { G711.encode(channel.law, 0) }
            repeat(25) {
                channel.write(frame, frame.size)
                Thread.sleep(40)
            }
        } finally {
            channel.close()
        }
        // После закрытия канал открывается снова: сеанс не остался занятым
        open(camera).close()
        println("TALK ${camera.ipAddress}: передано 1 с тишины, канал закрыт и открывается повторно")
    }

    @Test
    fun hikvisionAcceptsSilence() {
        val camera = camera("hik", hikvision = true)
        assumeTrue("нет аргументов камеры Hikvision", camera != null)
        speakSilence(camera!!)
    }

    @Test
    fun dahuaAcceptsSilence() {
        val camera = camera("dahua", hikvision = false)
        assumeTrue("нет аргументов камеры Dahua", camera != null)
        try {
            // Разговор с Dahua в приложении выключен (камера принимает поток, но звук не воспроизводит); тест — для опытов
            speakSilence(camera!!, DahuaTalkback::open)
        } catch (e: TalkbackRefusedException) {
            // Камера не приняла ни singlepart, ни multipart
            println("TALK ${camera!!.ipAddress}: камера не принимает разговор по HTTP — ${e.message}")
            assumeTrue("камера не поддерживает postAudio", false)
        }
    }

    @Test
    fun unsupportedCameraIsRefused() {
        val camera = IpCamera(id = "x", name = "x", ipAddress = "192.0.2.1", username = "a", password = "b")
        assertEquals(false, CameraTalkback.isSupported(camera))
    }
}
