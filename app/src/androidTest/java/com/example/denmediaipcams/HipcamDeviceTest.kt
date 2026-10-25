package com.example.denmediaipcams

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HipcamArchive
import com.example.denmediaipcams.network.HipcamStorage
import com.example.denmediaipcams.player.HipcamThumbnails
import com.example.denmediaipcams.player.HxvtMp4Converter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка на настоящей камере Hipcam: превью, состояние карты и сборка MP4.
 * Запускается только если переданы аргументы: -e hipcamHost … -e hipcamUser … -e hipcamPass …
 * (учётные данные в код не записываются). Ничего на камере не меняет: форматирование не вызывается.
 */
@RunWith(AndroidJUnit4::class)
class HipcamDeviceTest {

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("hipcamHost")
        assumeTrue("нет аргументов камеры", host != null)
        return IpCamera(
            id = "device-test",
            name = "test",
            ipAddress = host!!,
            username = args.getString("hipcamUser").orEmpty(),
            password = args.getString("hipcamPass").orEmpty()
        )
    }

    @Test
    fun readsCardState() = runBlocking<Unit> {
        val info = HipcamStorage.info(camera())
        val volume = info.volumes.single()
        println("SD: state=${volume.state} total=${volume.totalBytes} used=${volume.usedBytes}")
        assertEquals("Success", volume.state)
        assertTrue((volume.totalBytes ?: 0) > 1L shl 30)
    }

    @Test
    fun buildsThumbnailAndMp4() = runBlocking<Unit> {
        val camera = camera()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = HipcamArchive.listDays(camera).dropLast(1).last()
        // Самая маленькая запись дня — быстрее скачивается; последний день не берём: там файл, который пишется сейчас
        val recording = HipcamArchive.listRecordings(camera, day).minByOrNull { it.sizeBytes }!!
        println("record: ${recording.path} ${recording.sizeBytes / 1024 / 1024} МБ ${recording.durationMs / 1000} с")

        // По шагам: что именно не получилось, если превью не строится
        val head = HipcamArchive.readStart(camera, recording, 1 shl 20)
        assertNotNull("камера не отдала начало файла", head)
        val keyFrame = com.example.denmediaipcams.player.HxvtKeyFrame.parse(head!!)
        assertNotNull("ключевой кадр не найден в первом мегабайте (${head.size} байт)", keyFrame)
        val decoded = com.example.denmediaipcams.player.HevcFrameDecoder.decode(
            keyFrame!!.width, keyFrame.height, keyFrame.parameterSets, keyFrame.data, 320
        )
        assertNotNull("декодер не выдал кадр (${keyFrame.width}x${keyFrame.height}, кадр ${keyFrame.data.size} байт)", decoded)

        val started = System.currentTimeMillis()
        val thumbnail = HipcamThumbnails.load(context, camera, recording)
        println("thumbnail: ${thumbnail?.width}x${thumbnail?.height} за ${System.currentTimeMillis() - started} мс")
        assertNotNull("превью не построено", thumbnail)
        var luma = 0L
        val pixels = IntArray(thumbnail!!.width * thumbnail.height)
        thumbnail.getPixels(pixels, 0, thumbnail.width, 0, 0, thumbnail.width, thumbnail.height)
        pixels.forEach { luma += ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) / 3 }
        println("средняя яркость превью: ${luma / pixels.size}")
        assertTrue("превью пустое", luma / pixels.size > 5)

        val mp4 = File(context.cacheDir, "device-test.mp4")
        val convertStarted = System.currentTimeMillis()
        var lastProgress = 0f
        val size = mp4.outputStream().use { out ->
            HxvtMp4Converter.convert(camera, recording, context.cacheDir, out) { lastProgress = it }
        }
        println("mp4: ${size / 1024 / 1024} МБ за ${(System.currentTimeMillis() - convertStarted) / 1000} с, progress=$lastProgress")
        assertTrue(size > 1_000_000)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp4.absolutePath)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            println("дорожки: $mimes")
            assertTrue(mimes.contains("video/hevc"))
            assertTrue("нет звука", mimes.contains("audio/mp4a-latm"))
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME) == "video/hevc" }
            val durationS = video.getLong(MediaFormat.KEY_DURATION) / 1_000_000
            println("длительность видео: $durationS с (по имени файла ${recording.durationMs / 1000} с), ${video.getInteger(MediaFormat.KEY_WIDTH)}x${video.getInteger(MediaFormat.KEY_HEIGHT)}")
            assertTrue(kotlin.math.abs(durationS - recording.durationMs / 1000) < 30)

            // Звук: AAC-кадры идут почти на всю длительность видео
            val audioIndex = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            extractor.selectTrack(audioIndex)
            var audioSamples = 0
            var lastAudioUs = 0L
            while (extractor.sampleTime >= 0) {
                audioSamples++
                lastAudioUs = extractor.sampleTime
                extractor.advance()
            }
            println("звук: $audioSamples кадров AAC, до ${lastAudioUs / 1_000_000} с")
            assertTrue("звук короче видео", lastAudioUs / 1_000_000 > durationS - 30)

            // Кадр из середины готового MP4 реально декодируется
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(mp4.absolutePath)
            val frame = retriever.getFrameAtTime(durationS / 2 * 1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            assertNotNull("кадр из MP4 не декодируется", frame)
            File(context.cacheDir, "device-test-frame.png").outputStream().use { frame!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        } finally {
            extractor.release()
        }
    }
}
