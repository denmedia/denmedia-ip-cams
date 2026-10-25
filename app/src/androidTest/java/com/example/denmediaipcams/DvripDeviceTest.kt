package com.example.denmediaipcams

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.DvripArchive
import com.example.denmediaipcams.player.DvripMp4Converter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка на настоящей камере по протоколу XMEye/DVRIP (порт 34567): вход, список дней и записей,
 * скачивание и сборка MP4. Запускается только если переданы аргументы:
 * -e dvripHost … -e dvripUser … -e dvripPass … (учётные данные в код не записываются).
 *
 * Подтверждено живой проверкой 2026-10-12 (камера NVT, прошивка XMEye): вход и поиск записей
 * (OPFileQuery) работают правильно — камера отвечала чистым протоколом без единой ошибки/обрыва на
 * широком и узком диапазонах дат, на обоих каналах. Ноль найденных файлов в тот раз объяснялся пустой
 * SD-картой (подтверждено владельцем камеры), а не ошибкой разбора ответа — поэтому `loginAndListRecordings`
 * не требует непустого результата, только то, что сам протокол отвечает корректно.
 */
@RunWith(AndroidJUnit4::class)
class DvripDeviceTest {

    private fun camera(): IpCamera {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("dvripHost")
        assumeTrue("нет аргументов камеры", host != null)
        return IpCamera(
            id = "dvrip-device-test",
            name = "test",
            ipAddress = host!!,
            username = args.getString("dvripUser").orEmpty(),
            password = args.getString("dvripPass").orEmpty(),
            dvripArchive = true
        )
    }

    @Test
    fun loginAndListRecordings() = runBlocking<Unit> {
        val cam = camera()

        val supported = DvripArchive.isSupported(cam)
        println("DVRIP isSupported: $supported")
        assertTrue("камера не приняла вход по DVRIP (isSupported=$supported)", supported == true)

        // Пустая SD-карта — не ошибка теста (см. комментарий класса), поэтому дальше только печать,
        // без assertTrue на непустой список: нечего скачивать — downloadsAndConvertsRecording сам
        // пропустится через assumeTrue.
        val days = DvripArchive.listDays(cam)
        println("DVRIP дни с записями: $days")
        if (days.isEmpty()) {
            println("На карте нет записей (пусто или ещё не писала) — дальше проверять нечего")
            return@runBlocking
        }

        val day = days.last()
        val recordings = DvripArchive.listRecordings(cam, day)
        println("DVRIP записи за $day: ${recordings.size}")
        recordings.take(10).forEach {
            println("  ${it.fileName}  ${it.start} — ${it.end}  ${it.sizeBytes / 1024} КБ")
        }
    }

    @Test
    fun downloadsAndConvertsRecording() = runBlocking<Unit> {
        val cam = camera()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val days = DvripArchive.listDays(cam)
        assumeTrue("нет дней с записями (пустая SD-карта?) — см. loginAndListRecordings", days.isNotEmpty())

        // Берём самую маленькую запись самого свежего дня с записями — быстрее скачивается
        val day = days.last()
        val recordings = DvripArchive.listRecordings(cam, day)
        assumeTrue("нет записей за $day", recordings.isNotEmpty())
        val recording = recordings.filter { it.sizeBytes > 0 }.minByOrNull { it.sizeBytes } ?: recordings.first()
        println("Скачиваю запись: ${recording.fileName} (${recording.start} — ${recording.end}, ${recording.sizeBytes / 1024} КБ)")

        val mp4 = File(context.cacheDir, "dvrip-device-test.mp4")
        val started = System.currentTimeMillis()
        var lastProgress = 0f
        val size = mp4.outputStream().use { out ->
            DvripMp4Converter.convert(cam, recording, context.cacheDir, out) { lastProgress = it }
        }
        println("mp4: ${size / 1024} КБ за ${(System.currentTimeMillis() - started) / 1000} с, progress=$lastProgress")
        assertTrue("MP4 получился подозрительно маленьким ($size байт)", size > 10_000)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp4.absolutePath)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            println("дорожки: $mimes")
            assertTrue("нет видеодорожки", mimes.any { it?.startsWith("video/") == true })
            val video = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            println("видео: ${video.getString(MediaFormat.KEY_MIME)} ${video.getInteger(MediaFormat.KEY_WIDTH)}x${video.getInteger(MediaFormat.KEY_HEIGHT)}")

            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(mp4.absolutePath)
            val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            assertNotNull("кадр из MP4 не декодируется", frame)
            File(context.cacheDir, "dvrip-device-test-frame.png").outputStream().use { frame!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            println("кадр сохранён: ${context.cacheDir}/dvrip-device-test-frame.png")
        } finally {
            extractor.release()
        }
    }
}
