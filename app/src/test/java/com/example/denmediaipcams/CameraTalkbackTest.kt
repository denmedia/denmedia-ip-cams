package com.example.denmediaipcams

import com.example.denmediaipcams.model.CameraProtocol
import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.CameraTalkback
import com.example.denmediaipcams.network.DahuaTalkback
import com.example.denmediaipcams.network.G711
import com.example.denmediaipcams.network.G711Law
import com.example.denmediaipcams.network.HikvisionTalkback
import com.example.denmediaipcams.player.TalkbackRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraTalkbackTest {

    private fun decodeMuLaw(byte: Byte): Int {
        val u = byte.toInt().inv() and 0xFF
        val sample = (((u and 0x0F) shl 3) + 0x84 shl ((u shr 4) and 0x07)) - 0x84
        return if (u and 0x80 != 0) -sample else sample
    }

    private fun decodeALaw(byte: Byte): Int {
        val a = byte.toInt() xor 0x55 and 0xFF
        var t = (a and 0x0F) shl 4
        val segment = (a and 0x70) shr 4
        t = when (segment) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (segment - 1)
        }
        return if (a and 0x80 != 0) t else -t
    }

    @Test
    fun g711KnownValues() {
        assertEquals(0xFF.toByte(), G711.muLaw(0))
        assertEquals(0x80.toByte(), G711.muLaw(32767))
        assertEquals(0x00.toByte(), G711.muLaw(-32768))
        assertEquals(0xD5.toByte(), G711.aLaw(0))
        assertEquals(0xAA.toByte(), G711.aLaw(32767))
        assertEquals(0x2A.toByte(), G711.aLaw(-32768))
    }

    @Test
    fun g711RoundTripStaysWithinQuantizationStep() {
        for (sample in -32768..32767 step 7) {
            val limit = abs(sample) / 12 + 16
            assertTrue("μ-law $sample", abs(decodeMuLaw(G711.muLaw(sample)) - sample) <= limit)
            assertTrue("A-law $sample", abs(decodeALaw(G711.aLaw(sample)) - sample) <= limit)
        }
    }

    @Test
    fun g711SilenceDecodesToNearZero() {
        assertTrue(abs(decodeMuLaw(G711.muLaw(0))) <= 8)
        assertTrue(abs(decodeALaw(G711.aLaw(0))) <= 8)
    }

    @Test
    fun recorderAveragesSamplesWhenDownsampling() {
        val pcm = shortArrayOf(1000, 3000, -2000, -4000, 0, 0)
        val out = ByteArray(3)
        TalkbackRecorder.encode(pcm, 2, G711Law.MU_LAW, out)
        assertEquals(G711.muLaw(2000), out[0])
        assertEquals(G711.muLaw(-3000), out[1])
        assertEquals(G711.muLaw(0), out[2])
    }

    @Test
    fun recorderKeepsSamplesWithoutDownsampling() {
        val pcm = shortArrayOf(100, -100)
        val out = ByteArray(2)
        TalkbackRecorder.encode(pcm, 1, G711Law.A_LAW, out)
        assertEquals(G711.aLaw(100), out[0])
        assertEquals(G711.aLaw(-100), out[1])
    }

    @Test
    fun hikvisionChannelCodecIsRecognised() {
        assertEquals(G711Law.MU_LAW, HikvisionTalkback.parseLaw("G.711ulaw"))
        assertEquals(G711Law.A_LAW, HikvisionTalkback.parseLaw(" G.711alaw "))
        assertNull(HikvisionTalkback.parseLaw("G.722.1"))
        assertNull(HikvisionTalkback.parseLaw(null))
    }

    @Test
    fun hikvisionStatusIsExtracted() {
        val xml = "<ResponseStatus><statusCode>7</statusCode><statusString>Device Busy</statusString></ResponseStatus>"
        assertEquals("Device Busy", HikvisionTalkback.parseStatus(xml))
        assertNull(HikvisionTalkback.parseStatus("<x/>"))
    }

    @Test
    fun dahuaAudioOutputCountIsParsed() {
        assertEquals(1, DahuaTalkback.parseOutputs("result=1\r\n"))
        assertEquals(0, DahuaTalkback.parseOutputs("result=0"))
        assertNull(DahuaTalkback.parseOutputs("Error Bad Request!"))
    }

    @Test
    fun talkbackOnlyForHikvisionWithLogin() {
        val base = IpCamera(id = "1", name = "c", ipAddress = "192.168.1.5", username = "admin", password = "x", protocol = CameraProtocol.ONVIF)
        assertFalse(CameraTalkback.isSupported(base))
        assertTrue(CameraTalkback.isSupported(base.copy(hikvisionArchive = true)))
        // Dahua принимает поток по HTTP, но не воспроизводит звук: кнопка разговора не показывается
        assertFalse(CameraTalkback.isSupported(base.copy(dahuaApi = true)))
        assertFalse(CameraTalkback.isSupported(base.copy(protocol = CameraProtocol.DAHUA)))
        assertFalse(CameraTalkback.isSupported(base.copy(hikvisionArchive = true, username = "")))
        assertFalse(CameraTalkback.isSupported(base.copy(hikvisionArchive = true, ipAddress = "rtsp://x/y")))
    }
}
