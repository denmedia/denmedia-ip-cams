package com.example.denmediaipcams

import com.example.denmediaipcams.network.DvripFrameParser
import com.example.denmediaipcams.network.DvripMediaFrame
import com.example.denmediaipcams.network.DvripStreamException
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DvripFrameParserTest {

    private fun be32(value: Int) =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun le32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    /** 0x1FC: media(1) fps(1) w8(1) h8(1) datetime(4 LE) length(4 LE) + данные */
    private fun keyFrameBytes(media: Int, fps: Int, w8: Int, h8: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(0x1FC))
        out.write(media)
        out.write(fps)
        out.write(w8)
        out.write(h8)
        out.write(le32(0)) // datetimeBits — парсером не используется
        out.write(le32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /** 0x1FD: length(4 LE) + данные */
    private fun deltaFrameBytes(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(0x1FD))
        out.write(le32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /** 0x1FA: media(1) sampleRate(1) length(2 LE) + данные */
    private fun audioFrameBytes(media: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(0x1FA))
        out.write(media)
        out.write(0) // sampleRate — парсером не используется
        out.write(le16(payload.size))
        out.write(payload)
        return out.toByteArray()
    }


    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Ключевой кадр H.265 с VPS/SPS/PPS как у камеры NVT: SPS на 2560x1440 */
    private val nvtKeyPayload = byteArrayOf(0, 0, 0, 1) + hex("40010c01ffff0160") +
        byteArrayOf(0, 0, 0, 1) + hex("4201010160000003000003000003000003003fa001402005a1fe72ee4c92e20100000303e800002ee008") +
        byteArrayOf(0, 0, 0, 1) + hex("4401c172b46240") +
        byteArrayOf(0, 0, 0, 1) + hex("2601af09")

    @Test
    fun nvtMediaCodec0x13IsH265AndSizeComesFromSps() {
        // Заголовок кадра камеры: w8 = 0x40 (2560/8 = 320 не умещается в байт), h8 = 0xb4
        val bytes = keyFrameBytes(media = 0x13, fps = 12, w8 = 0x40, h8 = 0xb4, payload = nvtKeyPayload)
        val frames = mutableListOf<DvripMediaFrame>()
        DvripFrameParser { frames.add(it) }.feed(bytes)

        val video = frames.single() as DvripMediaFrame.Video
        assertEquals("h265", video.codec)
        assertEquals(2560, video.width)
        assertEquals(1440, video.height)
    }

    @Test
    fun unknownMediaCodecIsDetectedFromKeyFrameData() {
        val bytes = keyFrameBytes(media = 0x55, fps = 12, w8 = 0x40, h8 = 0xb4, payload = nvtKeyPayload)
        val frames = mutableListOf<DvripMediaFrame>()
        DvripFrameParser { frames.add(it) }.feed(bytes)

        val video = frames.single() as DvripMediaFrame.Video
        assertEquals("h265", video.codec)
        assertEquals(2560, video.width)
    }

    /** Камера добивает файл байтами 0xFF до целого числа килобайт: это конец данных, а не неизвестный формат */
    @Test
    fun ffPaddingAfterLastFrameEndsTheStreamWithoutError() {
        val bytes = keyFrameBytes(media = 0x13, fps = 12, w8 = 0x40, h8 = 0xb4, payload = nvtKeyPayload) +
            deltaFrameBytes(byteArrayOf(0, 0, 0, 1, 2, 1, 7)) +
            ByteArray(465) { 0xFF.toByte() }
        val frames = mutableListOf<DvripMediaFrame>()
        val parser = DvripFrameParser { frames.add(it) }
        // Заполнитель приходит и отдельным куском: после конца данных парсер уже ничего не разбирает
        parser.feed(bytes.copyOfRange(0, bytes.size - 100))
        parser.feed(bytes.copyOfRange(bytes.size - 100, bytes.size))

        assertEquals(2, frames.size)
        assertTrue(parser.reachedPadding)
    }

    /** Старт с середины записи: камера отдаёт хвост файла с произвольного байта, разбор ждёт ближайшего ключевого кадра */
    @Test
    fun resyncSkipsGarbageUntilKeyFrameAndWorksAcrossChunkBoundaries() {
        val garbage = ByteArray(300) { (it * 31 + 7).toByte() }
        val key = keyFrameBytes(media = 0x13, fps = 12, w8 = 0x40, h8 = 0xb4, payload = nvtKeyPayload)
        val delta = deltaFrameBytes(byteArrayOf(0, 0, 0, 1, 2, 1, 7))
        val bytes = garbage + key + delta

        for (chunkSize in intArrayOf(bytes.size, 1000, 7)) {
            val frames = mutableListOf<DvripMediaFrame>()
            val parser = DvripFrameParser(true) { frames.add(it) }
            bytes.toList().chunked(chunkSize).forEach { parser.feed(it.toByteArray()) }

            assertEquals("размер куска $chunkSize", 2, frames.size)
            val first = frames[0] as DvripMediaFrame.Video
            assertTrue(first.keyFrame)
            assertEquals(0L, first.ptsMs)
            assertEquals(2560, first.width)
        }
    }

    /** Время видео копится без потерь на округлении: 12 кадров/с — это не целое число миллисекунд на кадр */
    @Test
    fun videoTimestampsDoNotDriftAtTwelveFps() {
        val key = keyFrameBytes(media = 0x13, fps = 12, w8 = 0x40, h8 = 0xb4, payload = nvtKeyPayload)
        val delta = deltaFrameBytes(byteArrayOf(0, 0, 0, 1, 2, 1, 7))
        val frames = mutableListOf<DvripMediaFrame>()
        val parser = DvripFrameParser { frames.add(it) }
        parser.feed(key)
        repeat(3599) { parser.feed(delta) }
        // 3600 кадров по 12 в секунду — ровно 300 секунд (по миллисекундам с округлением набежало бы 1,2 с)
        val last = frames.last() as DvripMediaFrame.Video
        assertTrue("ptsMs=${last.ptsMs}", kotlin.math.abs(last.ptsMs - 299_917L) <= 2)
    }

    @Test
    fun parsesKeyFrameSplitAcrossTwoChunks() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x26, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        // w8/h8 — ширина/высота делённые на 8, каждое умещается в один байт (макс. 255*8 = 2040)
        val frameBytes = keyFrameBytes(media = 3, fps = 25, w8 = 240, h8 = 135, payload = payload)

        val frames = mutableListOf<DvripMediaFrame>()
        val parser = DvripFrameParser { frames.add(it) }

        // Разбиваем ровно посередине полезных данных (после 4-байтного тега + 12-байтного заголовка + часть payload)
        val splitPoint = 4 + 12 + 5
        parser.feed(frameBytes.copyOfRange(0, splitPoint))
        assertTrue("до второго куска кадр ещё не должен быть выдан", frames.isEmpty())
        parser.feed(frameBytes.copyOfRange(splitPoint, frameBytes.size))

        assertEquals(1, frames.size)
        val video = frames[0] as DvripMediaFrame.Video
        assertEquals("h265", video.codec)
        assertEquals(240 * 8, video.width)
        assertEquals(135 * 8, video.height)
        assertTrue(video.keyFrame)
        assertEquals(0L, video.ptsMs)
        assertArrayEquals(payload, video.data)
    }

    @Test
    fun parsesDeltaFrameAfterKeyFrame() {
        val keyPayload = byteArrayOf(0, 0, 0, 1, 0x26, 9, 9)
        val deltaPayload = byteArrayOf(10, 11, 12, 13)
        val bytes = keyFrameBytes(media = 2, fps = 10, w8 = 100, h8 = 60, payload = keyPayload) +
            deltaFrameBytes(deltaPayload)

        val frames = mutableListOf<DvripMediaFrame>()
        DvripFrameParser { frames.add(it) }.feed(bytes)

        assertEquals(2, frames.size)
        val key = frames[0] as DvripMediaFrame.Video
        val delta = frames[1] as DvripMediaFrame.Video
        assertEquals("h264", key.codec)
        assertTrue(key.keyFrame)
        assertFalse(delta.keyFrame)
        // Ширина/высота и кодек «липкие» — остаются от последнего ключевого кадра
        assertEquals(key.width, delta.width)
        assertEquals(key.height, delta.height)
        assertEquals(key.codec, delta.codec)
        assertArrayEquals(deltaPayload, delta.data)
        // fps=10 → 100 мс между кадрами
        assertEquals(0L, key.ptsMs)
        assertEquals(100L, delta.ptsMs)
    }

    @Test
    fun parsesAlawAudioFrame() {
        val audioPayload = ByteArray(160) { 0x55 }
        val bytes = audioFrameBytes(media = 0x0E, payload = audioPayload)

        val frames = mutableListOf<DvripMediaFrame>()
        DvripFrameParser { frames.add(it) }.feed(bytes)

        assertEquals(1, frames.size)
        val audio = frames[0] as DvripMediaFrame.Audio
        assertArrayEquals(audioPayload, audio.data)
        assertEquals(0L, audio.ptsMs)
    }

    @Test
    fun nonAlawAudioIsDiscardedButStreamStaysInSync() {
        val discardedPayload = ByteArray(50) { 0x11 }
        val realAudioPayload = ByteArray(160) { 0x55 }
        val bytes = audioFrameBytes(media = 0x01, payload = discardedPayload) +
            audioFrameBytes(media = 0x0E, payload = realAudioPayload)

        val frames = mutableListOf<DvripMediaFrame>()
        DvripFrameParser { frames.add(it) }.feed(bytes)

        // Кадр с неподдерживаемым кодеком не эмитится, но его байты корректно пропущены —
        // следующий (поддерживаемый) кадр разобран правильно
        assertEquals(1, frames.size)
        assertArrayEquals(realAudioPayload, (frames[0] as DvripMediaFrame.Audio).data)
    }

    @Test
    fun unknownDataTypeThrowsGracefulException() {
        val bytes = be32(0x7777)
        try {
            DvripFrameParser { }.feed(bytes)
            fail("ожидалось DvripStreamException")
        } catch (e: DvripStreamException) {
            assertTrue(e.message!!.contains("00007777", ignoreCase = true))
        }
    }

    @Test
    fun feedingOneByteAtATimeStillParsesCorrectly() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x26, 1, 2, 3)
        val bytes = keyFrameBytes(media = 2, fps = 15, w8 = 10, h8 = 10, payload = payload)

        val frames = mutableListOf<DvripMediaFrame>()
        val parser = DvripFrameParser { frames.add(it) }
        for (b in bytes) parser.feed(byteArrayOf(b))

        assertEquals(1, frames.size)
        assertArrayEquals(payload, (frames[0] as DvripMediaFrame.Video).data)
    }
}
