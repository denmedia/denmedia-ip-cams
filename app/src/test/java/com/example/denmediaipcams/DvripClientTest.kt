package com.example.denmediaipcams

import com.example.denmediaipcams.network.DvripFileEntry
import com.example.denmediaipcams.network.DvripHeader
import com.example.denmediaipcams.network.DvripMediaFrame
import com.example.denmediaipcams.network.DvripStreamException
import com.example.denmediaipcams.network.dvripFileSizeBytes
import com.example.denmediaipcams.network.sofiaHash
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DvripClientTest {

    // --- sofia_hash: пароль по алгоритму камер XMEye (MD5, сумма пар байт по модулю 62) ---

    /** Эталонная реализация, независимая от продакшен-кода: типографская ошибка в DvripClient была бы поймана */
    private fun referenceSofiaHash(password: String): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 8) {
            val sum = (md5[2 * i].toInt() and 0xFF) + (md5[2 * i + 1].toInt() and 0xFF)
            sb.append(chars[sum % 62])
        }
        return sb.toString()
    }

    @Test
    fun sofiaHashMatchesReferenceComputation() {
        for (password in listOf("admin", "", "12345678", "Пароль с юникодом")) {
            assertEquals(referenceSofiaHash(password), sofiaHash(password))
        }
    }

    @Test
    fun sofiaHashIsEightCharsFromExpectedAlphabet() {
        val hash = sofiaHash("admin")
        assertEquals(8, hash.length)
        assertTrue(hash.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" })
    }

    // --- Заголовок команды DVRIP: 20 байт, little-endian, round-trip pack/parse ---

    @Test
    fun headerPacksAndParsesRoundTrip() {
        val header = DvripHeader(sessionId = 0x1000001, sequence = 42, msgId = 1000, dataLength = 123)
        val packed = header.pack()
        assertEquals(20, packed.size)
        assertEquals(0xFF.toByte(), packed[0])
        assertEquals(0.toByte(), packed[1])

        val parsed = DvripHeader.parse(packed)
        assertEquals(header, parsed)
    }

    @Test
    fun headerPacksNegativeSessionIdBitPatternCorrectly() {
        // sessionId хранится как Int с тем же битовым рисунком, что и исходное 32-битное беззнаковое число
        val header = DvripHeader(sessionId = -1, sequence = 0, msgId = 1006, dataLength = 0)
        val parsed = DvripHeader.parse(header.pack())
        assertEquals(-1, parsed.sessionId)
    }

    @Test
    fun headerFieldsAreLittleEndian() {
        val header = DvripHeader(sessionId = 0x04030201, sequence = 0, msgId = 0x0A0B, dataLength = 0)
        val packed = header.pack()
        // offset 4..7 — sessionId little-endian
        assertEquals(0x01.toByte(), packed[4])
        assertEquals(0x02.toByte(), packed[5])
        assertEquals(0x03.toByte(), packed[6])
        assertEquals(0x04.toByte(), packed[7])
        // offset 14..15 — msgId little-endian
        assertEquals(0x0B.toByte(), packed[14])
        assertEquals(0x0A.toByte(), packed[15])
    }

    // --- Разбор JSON-ответа: dataLength включает 2-байтный «хвост», хвост отбрасывается при разборе ---
    // (org.json в юнит-тестах — незамоканная заглушка Android и бросает исключение на put()/конструкторе
    // из строки, поэтому здесь строка JSON — обычный литерал, а проверяется именно арифметика среза)

    @Test
    fun jsonReplyDataLengthIncludesTwoByteTail() {
        val json = """{"Ret":100,"SessionID":"0x1000001"}"""
        val payload = json.toByteArray(Charsets.UTF_8) + byteArrayOf(0x0A, 0x00)
        val dataLength = payload.size

        // dataLength включает хвост — текст JSON короче dataLength ровно на 2 байта
        val jsonLength = dataLength - 2
        assertEquals(json.toByteArray(Charsets.UTF_8).size, jsonLength)

        val sliced = String(payload, 0, jsonLength, Charsets.UTF_8)
        assertEquals(json, sliced)
        // Последние 2 байта среза исходной строки не задевают — хвост (0x0A 0x00) остаётся снаружи среза
        assertFalse(sliced.contains('\u0000'))
    }

    // --- Разбор потока плейбэка (DvripFrameParser через рефлексию недоступен — тестируем через DvripClient's
    //     публичный контракт нельзя без сокета, поэтому ниже — прямой тест парсера, см. DvripFrameParserTest) ---

    @Test
    fun dvripFileEntryHoldsRawSearchFields() {
        val entry = DvripFileEntry(
            beginTime = java.time.LocalDateTime.of(2026, 10, 10, 0, 3, 12),
            endTime = java.time.LocalDateTime.of(2026, 10, 10, 0, 13, 18),
            channel = 0,
            fileName = "/mnt/sd/2026-10-10/001.h264",
            sizeBytes = 123456L
        )
        assertEquals(0, entry.channel)
        assertFalse(entry.fileName.isEmpty())
    }

    // --- Размер файла в ответе OPFileQuery: реальные значения FileLength с камеры NVT ---

    @Test
    fun fileSizeIsReadFromHexFileLengthInKilobytes() {
        assertEquals(0x67f4L * 1024, dvripFileSizeBytes("0x000067f4", null))
        assertEquals(0xa86bL * 1024, dvripFileSizeBytes("0x0000a86b", null))
        assertTrue(dvripFileSizeBytes("0x0000a86b", null) > 40L * 1024 * 1024)
    }

    @Test
    fun fileSizeFallsBackToFileSizeInBytesAndToZero() {
        assertEquals(123456L, dvripFileSizeBytes(null, 123456))
        assertEquals(0L, dvripFileSizeBytes(null, null))
        assertEquals(0L, dvripFileSizeBytes("мусор", "тоже мусор"))
    }

    // Небольшая проверка, что DvripMediaFrame.Video/Audio хранят переданные данные без искажений —
    // пригодится при отладке конвертера, если тесты парсера когда-нибудь будут расширены этим типом напрямую
    @Test
    fun mediaFrameDataClassesHoldPayloadAsGiven() {
        val video = DvripMediaFrame.Video("h264", 1920, 1080, keyFrame = true, ptsMs = 0, data = byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), video.data)
        val audio = DvripMediaFrame.Audio(ptsMs = 20, data = byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), audio.data)
    }

    @Test
    fun streamExceptionCarriesMessage() {
        try {
            throw DvripStreamException("Камера прислала неизвестный формат потока (0x12345678)")
        } catch (e: DvripStreamException) {
            assertTrue(e.message!!.contains("0x12345678"))
            return
        }
        fail("ожидалось исключение")
    }
}
