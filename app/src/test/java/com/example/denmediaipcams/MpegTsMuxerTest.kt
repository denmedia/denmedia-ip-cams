package com.example.denmediaipcams

import com.example.denmediaipcams.player.MpegTsMuxer
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsMuxerTest {

    private fun mux(h265: Boolean = true, hasAudio: Boolean = true, block: (MpegTsMuxer) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        block(MpegTsMuxer(h265, hasAudio) { out.write(it) })
        return out.toByteArray()
    }

    private fun packets(stream: ByteArray): List<ByteArray> {
        assertEquals("поток TS состоит из целых пакетов по 188 байт", 0, stream.size % 188)
        return stream.toList().chunked(188).map { it.toByteArray() }
    }

    private fun pid(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)

    /** Полезные данные пакета без поля адаптации */
    private fun payload(p: ByteArray): ByteArray {
        val hasAdaptation = (p[3].toInt() and 0x20) != 0
        val start = if (hasAdaptation) 5 + (p[4].toInt() and 0xFF) else 4
        return p.copyOfRange(start, 188)
    }

    @Test
    fun everyPacketStartsWithSyncByteAndKeyFrameIsPrecededByTables() {
        val frame = ByteArray(1000) { (it % 251).toByte() }
        val stream = mux { it.writeVideo(0, keyFrame = true, annexB = frame) }
        val list = packets(stream)
        assertTrue(list.all { it[0] == 0x47.toByte() })
        assertEquals(0, pid(list[0]))
        assertEquals(MpegTsMuxer.PMT_PID, pid(list[1]))
        assertEquals(MpegTsMuxer.VIDEO_PID, pid(list[2]))
    }

    @Test
    fun tablesHaveValidCrc() {
        val list = packets(mux { it.writeVideo(0, true, ByteArray(10)) })
        for (table in list.take(2)) {
            val data = payload(table)
            val pointer = data[0].toInt() and 0xFF
            val length = ((data[1 + pointer + 1].toInt() and 0x0F) shl 8) or (data[1 + pointer + 2].toInt() and 0xFF)
            val section = data.copyOfRange(1 + pointer, 1 + pointer + 3 + length)
            // CRC считается и по самой секции вместе с CRC — для верной остаётся 0
            assertEquals(0, MpegTsMuxer.crc32Mpeg(section))
        }
    }

    @Test
    fun pmtListsH265AndAudioOrOnlyVideo() {
        fun streamTypes(stream: ByteArray): List<Int> {
            val data = payload(packets(stream)[1])
            val section = data.copyOfRange(1, data.size)
            val length = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
            val end = 3 + length - 4
            val types = mutableListOf<Int>()
            var i = 3 + 9 // после заголовка секции и полей программы (program_info_length = 0)
            while (i + 5 <= end) {
                types += section[i].toInt() and 0xFF
                i += 5
            }
            return types
        }
        assertEquals(listOf(0x24, 0x0F), streamTypes(mux(h265 = true, hasAudio = true) { it.writeVideo(0, true, ByteArray(4)) }))
        assertEquals(listOf(0x1B), streamTypes(mux(h265 = false, hasAudio = false) { it.writeVideo(0, true, ByteArray(4)) }))
    }

    @Test
    fun videoPesRoundTripsAcrossManyPacketsWithContinuity() {
        val frame = ByteArray(5000) { (it * 7).toByte() }
        val list = packets(mux { it.writeVideo(40, true, frame) }).filter { pid(it) == MpegTsMuxer.VIDEO_PID }
        assertTrue(list.size > 20)
        // Счётчик непрерывности растёт на 1 по модулю 16
        list.zipWithNext().forEach { (a, b) -> assertEquals((a[3].toInt() + 1) and 0x0F, b[3].toInt() and 0x0F) }
        // Первый пакет начинает PES и несёт PCR на ключевом кадре
        assertTrue((list[0][1].toInt() and 0x40) != 0)
        assertTrue((list[0][5].toInt() and 0x10) != 0)

        val pes = ByteArrayOutputStream().also { out -> list.forEach { out.write(payload(it)) } }.toByteArray()
        assertEquals(listOf<Byte>(0, 0, 1, 0xE0.toByte()), pes.take(4))
        assertArrayEquals(frame, pes.copyOfRange(14, 14 + frame.size))
    }

    @Test
    fun audioIsWrappedInAdtsWithCorrectLength() {
        val aac = ByteArray(100) { 0x11 }
        val list = packets(mux { it.writeAudio(0, aac) }).filter { pid(it) == MpegTsMuxer.AUDIO_PID }
        val pes = payload(list.single())
        assertEquals(0xC0.toByte(), pes[3])
        val adts = pes.copyOfRange(14, 14 + 107)
        assertEquals(0xFF.toByte(), adts[0])
        val frameLength = ((adts[3].toInt() and 0x03) shl 11) or ((adts[4].toInt() and 0xFF) shl 3) or ((adts[5].toInt() and 0xFF) shr 5)
        assertEquals(107, frameLength)
        assertArrayEquals(aac, adts.copyOfRange(7, 107))
    }
}
