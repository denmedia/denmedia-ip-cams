package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.model.PortKind
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.PortDetector
import com.example.denmediaipcams.network.PtzProtocolDetector
import com.example.denmediaipcams.network.RtspPathFinder
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Повторяет шаги формы добавления камеры (порты → проверка → поиск потоков → PTZ → архив) и после каждого
 * проверяет, что RTSP-порт камеры ещё отвечает. Запускается только с аргументами -e addHost … -e addUser … -e addPass …
 */
@RunWith(AndroidJUnit4::class)
class CameraAddFlowTest {

    private fun alive(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
        true
    } catch (_: Exception) {
        false
    }

    /** Быстрое добавление подставляет admin/admin: с неверным паролем поиск должен остановиться, не дойдя до опасных путей */
    @Test
    fun wrongCredentialsKeepCameraAlive() = runBlocking<Unit> {
        val host = InstrumentationRegistry.getArguments().getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        host!!
        assertTrue("камера недоступна до начала", alive(host, 554))
        val streams = RtspPathFinder.findAllStreams(host, 554, 8080, "admin", "admin", null) { }
        delay(1500)
        println("STREAMS wrong creds -> $streams | 554 ${if (alive(host, 554)) "alive" else "DOWN"}")
        assertTrue("камера перестала отвечать после поиска потоков", alive(host, 554))
        val single = RtspPathFinder.find(host, 554, 8080, "admin", "admin", "/live/ch0") { }
        delay(1500)
        println("FIND wrong creds -> $single | 554 ${if (alive(host, 554)) "alive" else "DOWN"}")
        assertTrue("камера перестала отвечать после поиска пути", alive(host, 554))
    }

    /** Повторные поиски потоков не должны копить открытые RTSP-сессии на камере */
    @Test
    fun repeatedStreamSearchKeepsCameraAlive() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        host!!
        assertTrue("камера недоступна до начала", alive(host, 554))
        repeat(15) { run ->
            val result = RtspPathFinder.findAllStreams(
                host, 554, 8080, args.getString("addUser").orEmpty(), args.getString("addPass").orEmpty(), null
            ) { }
            delay(500)
            val ok = alive(host, 554)
            println("RUN ${run + 1} -> ${result::class.simpleName} | 554 ${if (ok) "alive" else "DOWN"}")
            assertTrue("камера перестала отвечать после поиска №${run + 1}", ok)
        }
    }

    @Test
    fun addFlowKeepsCameraAlive() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        host!!
        val user = args.getString("addUser").orEmpty()
        val pass = args.getString("addPass").orEmpty()

        suspend fun step(name: String, block: suspend () -> String) {
            val result = block()
            delay(1500)
            val ok = alive(host, 554)
            println("STEP $name -> $result | 554 ${if (ok) "alive" else "DOWN"}")
            assertTrue("камера перестала отвечать после шага «$name»", ok)
        }

        assertTrue("камера недоступна до начала", alive(host, 554))
        var ports = emptyList<com.example.denmediaipcams.model.KnownPort>()
        step("detect ports") {
            ports = PortDetector.detect(host)
            ports.joinToString { "${it.port}:${it.kinds}" }
        }
        step("verify rtsp 554") { PortDetector.verify(host, 554, PortKind.RTSP).toString() }
        step("find all streams") {
            RtspPathFinder.findAllStreams(host, 554, 8080, user, pass, null) { println("  progress: $it") }.toString()
        }
        step("ptz detect") {
            PtzProtocolDetector.detect(host, 80, 8080, user, pass) { }.toString()
        }
        step("hipcam archive probe") {
            HipcamArchive.isSupported(IpCamera(id = "t", name = "", ipAddress = host, httpPort = 80, username = user, password = pass)).toString()
        }
    }
}
