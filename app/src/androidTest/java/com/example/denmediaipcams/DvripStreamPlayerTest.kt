package com.example.denmediaipcams

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.network.DvripRecording
import com.example.denmediaipcams.player.DvripStreamPlayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Потоковый просмотр записи камеры XMEye/DVRIP на настоящей камере: старт без скачивания целиком, перемотка
 * перезапуском потока с нужной секунды. Аргументы: -e dvripHost … -e dvripUser … -e dvripPass …
 */
@RunWith(AndroidJUnit4::class)
class DvripStreamPlayerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("dvripHost")
        assumeTrue("нет аргументов камеры", host != null)
        return IpCamera(
            id = "dvrip-stream-test", name = "test", ipAddress = host!!,
            username = args.getString("dvripUser").orEmpty(), password = args.getString("dvripPass").orEmpty(),
            dvripArchive = true
        )
    }

    /** Аппаратный HEVC-декодер эмулятора (goldfish) падает на потоке, поэтому его исключаем: работает программный */
    private fun newPlayer(cam: IpCamera, recording: DvripRecording): DvripStreamPlayer {
        val context = instrumentation.targetContext
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filterNot { it.name.contains("goldfish") }
        }
        val builder = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(selector))
        return DvripStreamPlayer(context, cam, recording, builder)
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

    private fun longestRecording(cam: IpCamera): DvripRecording = runBlocking {
        val days = DvripArchive.listDays(cam)
        assumeTrue("нет записей", days.isNotEmpty())
        DvripArchive.listRecordings(cam, days.last()).maxByOrNull { it.sizeBytes }!!
    }

    @Test
    fun streamsAndSeeks() {
        val cam = camera()
        val recording = longestRecording(cam)
        println("STREAM запись ${recording.start} — ${recording.end}, ${recording.sizeBytes / 1024} КБ")
        val context = instrumentation.targetContext
        val player = onMain { newPlayer(cam, recording) }
        try {
            val started = System.currentTimeMillis()
            onMain { player.start(0) }
            waitFor(30_000, "первые кадры") { player.exo.playbackState == Player.STATE_READY }
            println("STREAM до первых кадров: ${System.currentTimeMillis() - started} мс")
            val tracks = onMain { player.exo.currentTracks }
            println("STREAM дорожки: video=${tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)} audio=${tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)}")
            assertTrue("нет видео", onMain { player.exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO } })
            assertTrue("нет звука", onMain { player.exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO } })
            assertNull(onMain { player.error })

            waitFor(10_000, "позиция пошла") { player.positionMs() >= 2_000 }
            println("STREAM позиция через ${System.currentTimeMillis() - started} мс: ${onMain { player.positionMs() }} мс; размер ${onMain { player.exo.videoSize }}")

            // Перемотка на 2:00 внутри записи
            val target = 120_000L.coerceAtMost(player.durationMs - 30_000)
            val seekStarted = System.currentTimeMillis()
            onMain { player.seekTo(target) }
            waitFor(30_000, "кадры после перемотки") { player.exo.playbackState == Player.STATE_READY }
            println("STREAM перемотка на $target мс: кадры через ${System.currentTimeMillis() - seekStarted} мс")
            assertTrue("позиция после перемотки", onMain { player.positionMs() } >= target)
            waitFor(10_000, "позиция идёт после перемотки") { player.positionMs() >= target + 2_000 }
            assertNull(onMain { player.error })

            // Перемотка назад к началу
            onMain { player.seekTo(0) }
            waitFor(30_000, "кадры после возврата") { player.exo.playbackState == Player.STATE_READY }
            waitFor(10_000, "позиция идёт с начала") { player.positionMs() in 1_500..20_000 }
            assertNull(onMain { player.error })
            assertEquals(false, onMain { player.isBuffering })
        } finally {
            onMain { player.release() }
        }
    }

    @Test
    fun playsToTheEndOfShortTailAndReportsEnded() {
        val cam = camera()
        val recording = longestRecording(cam)
        val context = instrumentation.targetContext
        val player = onMain { newPlayer(cam, recording) }
        var ended = false
        try {
            onMain { player.onEnded = { ended = true } }
            // Последние ~12 секунд записи: после них поток должен закончиться, и плеер сообщит о конце
            onMain { player.start(player.durationMs - 12_000) }
            waitFor(60_000, "конец записи") { ended }
            assertNull(onMain { player.error })
        } finally {
            onMain { player.release() }
        }
    }
}
