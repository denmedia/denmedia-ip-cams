package com.example.denmediaipcams

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HikvisionRecording
import com.example.denmediaipcams.player.HikvisionPlayback
import com.example.denmediaipcams.player.HikvisionPlayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Архив и воспроизведение записей камеры Hikvision на настоящей камере: поиск по ISAPI, показ по RTSP,
 * перемотка перезапуском потока. Аргументы: -e hikHost … -e hikUser … -e hikPass …
 */
@RunWith(AndroidJUnit4::class)
class HikvisionPlayerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("hikHost")
        assumeTrue("нет аргументов камеры", host != null)
        return IpCamera(
            id = "hik-player-test", name = "test", ipAddress = host!!,
            username = args.getString("hikUser").orEmpty(), password = args.getString("hikPass").orEmpty(),
            hikvisionArchive = true
        )
    }

    /** Аппаратный декодер эмулятора (goldfish) падает на потоке, поэтому его исключаем: работает программный */
    private fun newPlayer(): HikvisionPlayer {
        val context = instrumentation.targetContext
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filterNot { it.name.contains("goldfish") }
        }
        return HikvisionPlayback.createPlayer(context, ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(selector)))
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(200)
        }
        throw AssertionError("не дождались: $what")
    }

    private fun longestRecording(cam: IpCamera): HikvisionRecording = runBlocking {
        val days = HikvisionArchive.listDays(cam)
        assumeTrue("нет записей", days.isNotEmpty())
        println("HIK дни: $days")
        HikvisionArchive.listRecordings(cam, days.last()).maxByOrNull { it.durationMs }!!
    }

    @Test
    fun listsDaysAndRecordings() {
        val cam = camera()
        val recording = longestRecording(cam)
        println("HIK запись ${recording.start} — ${recording.end}, ${recording.sizeBytes / 1024} КБ, ${recording.kind}")
        assertTrue(recording.durationMs > 0)
        runBlocking {
            val latest = HikvisionArchive.latest(cam, 5)
            println("HIK новейшие: ${latest.map { it.start }}")
            assertTrue(latest.isNotEmpty())
            assertTrue(latest.zipWithNext().all { (a, b) -> a.startUtc >= b.startUtc })
        }
    }

    /** На эмуляторе декодер H.264 падает даже на живом потоке камеры: тест рассчитан на настоящее устройство */
    @Test
    fun playsAndSeeks() {
        val cam = camera()
        val recording = longestRecording(cam)
        println("HIK играю ${recording.start} — ${recording.end}")
        val player = onMain { newPlayer() }
        try {
            val started = System.currentTimeMillis()
            onMain { player.open(cam, recording) }
            waitFor(30_000, "первые кадры") { player.playbackState == Player.STATE_READY }
            println("HIK до первых кадров: ${System.currentTimeMillis() - started} мс")
            assertTrue("нет видео", onMain { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO } })
            assertNull(onMain { player.playerError })
            assertEquals(recording.durationMs, onMain { player.duration })
            assertTrue(onMain { player.isCurrentMediaItemSeekable })

            waitFor(15_000, "позиция пошла") { player.currentPosition >= 2_000 }
            println("HIK позиция: ${onMain { player.currentPosition }} мс, размер ${onMain { player.videoSize }}")

            // Перемотка на 60 с внутри записи
            val target = 60_000L.coerceAtMost(recording.durationMs - 30_000)
            val seekStarted = System.currentTimeMillis()
            onMain { player.seekTo(target) }
            waitFor(30_000, "кадры после перемотки") { player.playbackState == Player.STATE_READY }
            println("HIK перемотка на $target мс: кадры через ${System.currentTimeMillis() - seekStarted} мс")
            assertTrue("позиция после перемотки", onMain { player.currentPosition } >= target)
            waitFor(15_000, "позиция идёт после перемотки") { player.currentPosition >= target + 2_000 }
            assertNull(onMain { player.playerError })

            // Назад к началу
            onMain { player.seekTo(0) }
            waitFor(30_000, "кадры после возврата") { player.playbackState == Player.STATE_READY }
            waitFor(15_000, "позиция идёт с начала") { player.currentPosition in 1_500..20_000 }
            assertNull(onMain { player.playerError })
        } finally {
            onMain { player.release() }
        }
    }
}
