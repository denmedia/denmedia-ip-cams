package com.example.denmediaipcams

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.example.denmediaipcams.player.HxvtExtractor
import com.example.denmediaipcams.player.HxvtKeyFrame
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

@UnstableApi
class HxvtExtractorTest {

    private class Sample(val timeUs: Long, val flags: Int, val size: Int)

    private class RecordingTrack : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Sample>()
        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            throw UnsupportedOperationException()

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples += Sample(timeUs, flags, size)
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = mutableMapOf<Int, RecordingTrack>()
        var seekMap: SeekMap? = null
        var ended = false
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack() }
        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun frame(out: ByteArrayOutputStream, tag: String, timeMs: Int, type: Int, payload: ByteArray) {
        out.write(tag.toByteArray(Charsets.ISO_8859_1))
        out.write(le32(payload.size))
        out.write(le32(timeMs))
        out.write(le32(type))
        out.write(payload)
    }

    private fun nal(type: Int, size: Int) =
        byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1) + ByteArray(size) { 0x55 }

    /** Файл: две группы кадров (ключевой + 3 обычных) по 40 мс, между ними звук по 20 мс */
    private fun syntheticFile(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HXVT".toByteArray())
        out.write(le32(2560))
        out.write(le32(1920))
        out.write(le32(0))
        var time = 1_000_000
        repeat(2) {
            frame(out, "HXVF", time, 1, nal(32, 20))
            frame(out, "HXVF", time, 1, nal(33, 30))
            frame(out, "HXVF", time, 1, nal(34, 10))
            frame(out, "HXVF", time, 1, nal(19, 500))
            frame(out, "HXAF", time + 74, 0, ByteArray(164) { 0x55 })
            repeat(3) { n ->
                time += 40
                frame(out, "HXVF", time, 2, nal(1, 100 + n))
                frame(out, "HXAF", time + 34, 0, ByteArray(164) { 0x55 })
            }
            time += 40
        }
        return out.toByteArray()
    }

    /** Как загрузчик ExoPlayer: по запросу экстрактора (RESULT_SEEK) входной поток открывается заново с нужной позиции */
    private fun readAll(data: ByteArray, extractor: HxvtExtractor, output: RecordingOutput, start: Int = 0) {
        fun openAt(position: Long): DefaultExtractorInput {
            val reader = object : androidx.media3.common.DataReader {
                var position = position.toInt()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (this.position >= data.size) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, data.size - this.position)
                    System.arraycopy(data, this.position, buffer, offset, count)
                    this.position += count
                    return count
                }
            }
            return DefaultExtractorInput(reader, position, data.size.toLong())
        }
        var input = openAt(start.toLong())
        extractor.init(output)
        val holder = PositionHolder()
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return
                Extractor.RESULT_SEEK -> input = openAt(holder.position)
            }
        }
    }

    /** К синтетическому файлу добавляется индекс HXFI, как у законченной записи камеры */
    private fun syntheticFileWithIndex(): ByteArray {
        val body = syntheticFile()
        // Записи ключевых кадров: VPS по смещению 16 (время 0) и второй ключевой кадр (время 160 мс)
        val second = (16 until body.size).first { i ->
            i + 22 <= body.size && String(body, i, 4, Charsets.ISO_8859_1) == "HXVF" && body[i + 12].toInt() == 1 &&
                (body[i + 20].toInt() shr 1) and 0x3F == 32 && i > 100
        }
        val index = ByteArrayOutputStream()
        index.write(le32(16)); index.write(le32(0))
        index.write(le32(second)); index.write(le32(160))
        val data = index.toByteArray().copyOf(200_000)
        val out = ByteArrayOutputStream()
        out.write(body)
        // Звуковые записи после кадров: длина файла должна превышать размер индекса, иначе экстрактор его не ищет
        var time = 2_000_000
        repeat(1300) {
            frame(out, "HXAF", time, 0, ByteArray(164) { 0x55 })
            time += 20
        }
        out.write("HXFI".toByteArray())
        out.write(le32(200_000))
        out.write(le32(160)) // длительность файла, мс
        out.write(le32(2)) // служебное число
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun readsVideoAndAudioTracks() {
        val output = RecordingOutput()
        readAll(syntheticFile(), HxvtExtractor(durationUs = 10_000_000), output)

        assertTrue(output.ended)
        val video = output.tracks.getValue(0)
        val audio = output.tracks.getValue(1)
        assertEquals(MimeTypes.VIDEO_H265, video.format?.sampleMimeType)
        assertEquals(2560, video.format?.width)
        assertEquals(1920, video.format?.height)
        // VPS, SPS и PPS (по 6 байт заголовка и данным) переданы декодеру
        assertEquals(26 + 36 + 16, video.format?.initializationData?.single()?.size)
        assertEquals(MimeTypes.AUDIO_ALAW, audio.format?.sampleMimeType)
        assertEquals(8000, audio.format?.sampleRate)

        // Два ключевых кадра (VPS+SPS+PPS+IDR склеены в один) и по три обычных
        assertEquals(8, video.samples.size)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, video.samples[0].flags)
        assertEquals(26 + 36 + 16 + 506, video.samples[0].size)
        assertEquals(0, video.samples[1].flags)
        assertEquals(0L, video.samples[0].timeUs)
        assertEquals(40_000L, video.samples[1].timeUs)
        // Звук: заголовок в 4 байта отброшен
        assertEquals(8, audio.samples.size)
        assertEquals(160, audio.samples[0].size)
        assertEquals(74_000L, audio.samples[0].timeUs)
    }

    @Test
    fun seekMapIsProportionalToFileLength() {
        val data = syntheticFile()
        val output = RecordingOutput()
        readAll(data, HxvtExtractor(durationUs = 10_000_000), output)
        val seekMap = output.seekMap
        assertNotNull(seekMap)
        assertTrue(seekMap!!.isSeekable)
        assertEquals(10_000_000L, seekMap.getDurationUs())
        val half = seekMap.getSeekPoints(5_000_000).first
        assertEquals(5_000_000L, half.timeUs)
        assertEquals(16L + (data.size - 16) / 2, half.position)
    }

    @Test
    fun afterSeekStartsFromNextKeyFrame() {
        val data = syntheticFile()
        val keyFrames = data.indices.filter { i ->
            i + 22 <= data.size && String(data, i, 4, Charsets.ISO_8859_1) == "HXVF" && data[i + 12].toInt() == 1 &&
                (data[i + 20].toInt() shr 1) and 0x3F == 32
        }
        assertEquals(2, keyFrames.size)

        // Перемотка в середину первого кадра: чтение должно продолжиться со второго ключевого кадра
        val extractor = HxvtExtractor(durationUs = 10_000_000)
        val output = RecordingOutput()
        readAll(data, extractor, output)
        output.tracks.values.forEach { it.samples.clear() }

        val start = keyFrames[0] + 40
        extractor.seek(start.toLong(), 0)
        val reader = object : androidx.media3.common.DataReader {
            var position = start
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= data.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, data.size - position)
                System.arraycopy(data, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val input = DefaultExtractorInput(reader, start.toLong(), data.size.toLong())
        val holder = PositionHolder()
        while (extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) Unit

        val video = output.tracks.getValue(0)
        assertEquals(4, video.samples.size)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, video.samples[0].flags)
        // Время второй группы считается от начала файла, а не от точки перемотки
        assertEquals(160_000L, video.samples[0].timeUs)
    }

    @Test
    fun indexFromFileTailGivesExactSeekPointsAndDuration() {
        val data = syntheticFileWithIndex()
        val output = RecordingOutput()
        readAll(data, HxvtExtractor(durationUs = C.TIME_UNSET), output)

        val seekMap = output.seekMap!!
        assertTrue(seekMap.isSeekable)
        assertEquals(false, seekMap.isEstimated)
        assertEquals(160_000L, seekMap.getDurationUs())
        // Перед вторым ключевым кадром — первый; на самом времени ключевого кадра — он сам
        val before = seekMap.getSeekPoints(100_000)
        assertEquals(0L, before.first.timeUs)
        assertEquals(16L, before.first.position)
        assertEquals(160_000L, before.second.timeUs)
        assertEquals(160_000L, seekMap.getSeekPoints(160_000).first.timeUs)
        // Чтение закончилось на индексе, а не приняло его за кадры: видео осталось 8 кадров
        assertEquals(8, output.tracks.getValue(0).samples.size)
    }

    @Test
    fun keyFrameIsParsedFromFileStart() {
        val keyFrame = HxvtKeyFrame.parse(syntheticFile())
        assertNotNull(keyFrame)
        assertEquals(2560, keyFrame!!.width)
        assertEquals(1920, keyFrame.height)
        // VPS + SPS + PPS отдельно, а в кадре они же и IDR
        assertEquals(26 + 36 + 16, keyFrame.parameterSets.size)
        assertEquals(26 + 36 + 16 + 506, keyFrame.data.size)
    }

    @Test
    fun truncatedKeyFrameIsNotReturned() {
        val data = syntheticFile()
        // Оборван на середине IDR: декодировать нечего
        assertEquals(null, HxvtKeyFrame.parse(data.copyOf(16 + 16 + 26 + 16 + 36 + 16 + 16 + 100)))
        assertEquals(null, HxvtKeyFrame.parse(data.copyOf(10)))
    }

    /** Необязательная проверка индекса на целом файле с камеры: HXVT_FULL=путь к законченному .265 */
    @Test
    fun readsIndexOfRealCameraFile() {
        val path = System.getenv("HXVT_FULL")
        assumeTrue(path != null && File(path).exists())
        val data = File(path!!).readBytes()
        val output = RecordingOutput()
        readAll(data, HxvtExtractor(durationUs = C.TIME_UNSET), output)
        val seekMap = output.seekMap!!
        println("real index: duration=${seekMap.getDurationUs()} estimated=${seekMap.isEstimated}")
        assertEquals(false, seekMap.isEstimated)
        assertTrue(seekMap.getDurationUs() in 500_000_000..700_000_000)
        // Точка перемотки указывает на запись VPS ключевого кадра
        for (seconds in listOf(0L, 1L, 100L, 301L, 600L)) {
            val point = seekMap.getSeekPoints(seconds * 1_000_000).first
            val offset = point.position.toInt()
            assertEquals("HXVF", String(data, offset, 4, Charsets.ISO_8859_1))
            assertEquals(32, (data[offset + 20].toInt() shr 1) and 0x3F)
            assertTrue(point.timeUs <= seconds * 1_000_000 && seconds * 1_000_000 - point.timeUs < 5_000_000)
        }
        // Все кадры файла прочитаны, индекс не принят за кадры
        val video = output.tracks.getValue(0)
        assertTrue(video.samples.size > 1000)
        assertTrue(video.samples.last().timeUs in 595_000_000..610_000_000)
    }

    /** Необязательная проверка на настоящем файле с камеры: HXVT_SAMPLE=путь к началу .265 */
    @Test
    fun readsRealCameraFile() {
        val path = System.getenv("HXVT_SAMPLE")
        assumeTrue(path != null && File(path).exists())
        val data = File(path!!).readBytes()
        val output = RecordingOutput()
        readAll(data, HxvtExtractor(durationUs = 606_000_000), output)
        val video = output.tracks.getValue(0)
        val audio = output.tracks.getValue(1)
        println("real: video=${video.samples.size} audio=${audio.samples.size} size=${video.format?.width}x${video.format?.height}")
        assertTrue(video.samples.size > 100)
        assertTrue(audio.samples.size > 100)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, video.samples[0].flags)
        // Время растёт и у видео, и у звука
        assertTrue(video.samples.zipWithNext().all { (a, b) -> b.timeUs >= a.timeUs })
        assertTrue(audio.samples.zipWithNext().all { (a, b) -> b.timeUs > a.timeUs })

        val keyFrame = HxvtKeyFrame.parse(data)
        assertNotNull(keyFrame)
        println("real keyframe: ${keyFrame!!.width}x${keyFrame.height} data=${keyFrame.data.size} params=${keyFrame.parameterSets.size}")
        assertEquals(2560, keyFrame.width)
        assertTrue(keyFrame.data.size > 100_000)
    }
}
