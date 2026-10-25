package com.example.denmediaipcams

import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.player.DvripStreamPlayer
import com.example.denmediaipcams.player.DvripThumbnails
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Превью записей DVRIP на настоящей камере. Аргументы: -e dvripHost … -e dvripUser … -e dvripPass … */
@RunWith(AndroidJUnit4::class)
class DvripThumbnailTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("dvripHost")
        assumeTrue("нет аргументов камеры", host != null)
        return IpCamera(
            id = "dvrip-thumb-test", name = "test", ipAddress = host!!,
            username = args.getString("dvripUser").orEmpty(), password = args.getString("dvripPass").orEmpty(),
            dvripArchive = true
        )
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun buildsThumbnailsWhileAnotherRecordingIsPlaying() = runBlocking<Unit> {
        val cam = camera()
        val context = instrumentation.targetContext
        val days = DvripArchive.listDays(cam)
        assumeTrue("нет записей", days.isNotEmpty())
        val recordings = DvripArchive.listRecordings(cam, days.last()).sortedByDescending { it.sizeBytes }
        assumeTrue("нужно хотя бы 4 записи", recordings.size >= 4)

        // Одна запись играет, пока для трёх других строятся превью
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filterNot { it.name.contains("goldfish") }
        }
        val builder = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(selector))
        val player = onMain { DvripStreamPlayer(context, cam, recordings[0], builder) }
        try {
            onMain { player.start(0) }
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && onMain { player.exo.playbackState } != Player.STATE_READY) Thread.sleep(200)

            val started = System.currentTimeMillis()
            recordings.drop(1).take(3).forEachIndexed { index, recording ->
                val t0 = System.currentTimeMillis()
                val bitmap = DvripThumbnails.load(context, cam, recording)
                println("THUMB #$index ${recording.start}: ${bitmap?.width}x${bitmap?.height} за ${System.currentTimeMillis() - t0} мс")
                assertNotNull("превью не построено для ${recording.start}", bitmap)
                assertTrue(bitmap!!.width in 100..400)
                if (index == 0) File(context.cacheDir, "dvrip-thumb.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            }
            println("THUMB всего ${System.currentTimeMillis() - started} мс; позиция плеера ${onMain { player.positionMs() }} мс")
            // Основной просмотр не пострадал от параллельных сеансов
            assertNull(onMain { player.error })
            val before = onMain { player.positionMs() }
            Thread.sleep(3_000)
            assertTrue("воспроизведение остановилось", onMain { player.positionMs() } > before)
            assertNull(onMain { player.error })

            // Повторный запрос берётся из кэша, без камеры
            val t1 = System.currentTimeMillis()
            assertNotNull(DvripThumbnails.cached(context, cam, recordings[1]))
            println("THUMB из кэша за ${System.currentTimeMillis() - t1} мс")
        } finally {
            onMain { player.release() }
        }
    }
}
