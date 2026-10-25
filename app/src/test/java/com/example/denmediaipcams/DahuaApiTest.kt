package com.example.denmediaipcams

import com.example.denmediaipcams.network.DahuaApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class DahuaApiTest {

    @Test
    fun parsesMotionStartEvent() {
        val event = DahuaApi.parseEvent("Code=VideoMotion;action=Start;index=0")
        assertEquals("VideoMotion", event?.code)
        assertEquals(true, event?.isStart)
        assertEquals("Движение", event?.title)
    }

    @Test
    fun parsesEventWithDataPayload() {
        val event = DahuaApi.parseEvent("Code=CrossLineDetection;action=Stop;index=1;data={ \"Name\": \"a;b\" }")
        assertEquals("CrossLineDetection", event?.code)
        assertEquals(true, event?.isStop)
        assertEquals(1, event?.index)
    }

    @Test
    fun ignoresHeartbeatAndBoundaries() {
        assertNull(DahuaApi.parseEvent("Heartbeat"))
        assertNull(DahuaApi.parseEvent("--myboundary"))
        assertNull(DahuaApi.parseEvent("Content-Length: 9"))
    }

    @Test
    fun unknownEventHasNoTitle() {
        assertNull(DahuaApi.parseEvent("Code=SomethingNew;action=Start;index=0")?.title)
    }

    @Test
    fun parsesRecordingList() {
        val body = """
            found=2
            items[0].Channel=0
            items[0].StartTime=2026-10-10 12:00:00
            items[0].EndTime=2026-10-10 12:05:30
            items[0].Type=dav
            items[0].Length=1048576
            items[1].StartTime=2026-10-10 13:00:00
            items[1].EndTime=2026-10-10 13:10:00
            items[1].Type=dav
        """.trimIndent()
        val recordings = DahuaApi.parseRecordings(body)
        assertEquals(2, recordings.size)
        assertEquals(LocalDateTime.of(2026, 10, 10, 12, 0, 0), recordings[0].start)
        assertEquals(1048576L, recordings[0].lengthBytes)
        assertEquals(
            "/cam/playback?channel=1&subtype=0&starttime=2026_10_10_12_00_00&endtime=2026_10_10_12_05_30",
            recordings[0].playbackPath
        )
    }

    @Test
    fun skipsBrokenRecordings() {
        val body = "items[0].StartTime=garbage\nitems[0].EndTime=2026-10-10 12:05:30\n" +
            "items[1].StartTime=2026-10-10 14:00:00\nitems[1].EndTime=2026-10-10 13:00:00"
        assertEquals(0, DahuaApi.parseRecordings(body).size)
    }

    @Test
    fun buildsRecordingFromRpcFields() {
        val recording = DahuaApi.recordingFrom(
            mapOf("StartTime" to "2026-10-10 08:00:00", "EndTime" to "2026-10-10 08:10:00", "Type" to "dav", "Length" to "2048")
        )
        assertEquals(LocalDateTime.of(2026, 10, 10, 8, 0, 0), recording?.start)
        assertEquals(2048L, recording?.lengthBytes)
        // Пустая длина (поля нет в ответе) — не ошибка
        assertNull(DahuaApi.recordingFrom(mapOf("StartTime" to "", "EndTime" to ""))?.start)
        assertEquals(
            null,
            DahuaApi.recordingFrom(mapOf("StartTime" to "2026-10-10 08:00:00", "EndTime" to "2026-10-10 08:00:00", "Length" to ""))?.lengthBytes
        )
    }

    @Test
    fun addsFmtpToH265WithoutIt() {
        val sdp = "v=0\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=recvonly\r\nm=audio 0 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000"
        val fixed = com.example.denmediaipcams.network.addMissingH265Fmtp(sdp)
        assertEquals(true, fixed.contains("a=rtpmap:98 H265/90000\r\na=fmtp:98 sprop-max-don-diff=0\r\na=recvonly"))
        // Аудио и повторный проход ничего лишнего не добавляют
        assertEquals(fixed, com.example.denmediaipcams.network.addMissingH265Fmtp(fixed))
        assertEquals(1, Regex("a=fmtp:").findAll(fixed).count())
    }

    @Test
    fun keepsExistingH265Fmtp() {
        val sdp = "m=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=fmtp:98 sprop-vps=AAAA"
        assertEquals(sdp, com.example.denmediaipcams.network.addMissingH265Fmtp(sdp))
    }

    @Test
    fun lensCapsFromExplicitFlags() {
        val caps = DahuaApi.parseLensCaps("caps.ZoomSupport=true\ncaps.FocusSupport=false\n", null, null)
        assertEquals(true, caps.zoom)
        assertEquals(false, caps.focus)
    }

    @Test
    fun lensCapsFromFocusStatusAndElectricFocus() {
        assertEquals(true, DahuaApi.parseLensCaps("", "caps.ElectricFocus=true\n", false).focus)
        assertEquals(true, DahuaApi.parseLensCaps("", "", true).zoom)
    }

    @Test
    fun lensCapsAbsentWhenEverythingRefused() {
        val caps = DahuaApi.parseLensCaps("", "", false)
        assertEquals(false, caps.zoom)
        assertEquals(false, caps.focus)
    }

    @Test
    fun lensCapsUnknownWithoutAnswer() {
        val caps = DahuaApi.parseLensCaps(null, null, null)
        assertEquals(null, caps.zoom)
        assertEquals(null, caps.focus)
    }

}
