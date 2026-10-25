package com.example.denmediaipcams

import com.example.denmediaipcams.model.IpCamera
import com.example.denmediaipcams.network.HikvisionArchive
import com.example.denmediaipcams.network.HikvisionStorage
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор настоящих ответов DS-I114W (прошивка V5.4.81) */
class HikvisionArchiveTest {

    private val msk = ZoneOffset.ofHours(3)

    // Ответ POST /ISAPI/ContentMgmt/search: одна запись, камера отдаёт время в UTC
    private val searchOne = """<?xml version="1.0" encoding="UTF-8"?>
<CMSearchResult version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<searchID>{C1B5B2A0-0000-4000-8000-000000000003}</searchID>
<responseStatus>true</responseStatus>
<responseStatusStrg>OK</responseStatusStrg>
<numOfMatches>1</numOfMatches>
<matchList>
<searchMatchItem>
<sourceID>{0000000000-0000-0000-0000-000000000000}</sourceID>
<trackID>101</trackID>
<timeSpan>
<startTime>2026-10-22T17:45:34Z</startTime>
<endTime>2026-10-22T17:47:36Z</endTime>
</timeSpan>
<mediaSegmentDescriptor>
<contentType>video</contentType>
<codecType>H.264-BP</codecType>
<playbackURI>rtsp://192.168.1.203/Streaming/tracks/101/?starttime=20261022T174534Z&amp;endtime=20261022T174736Z&amp;name=ch01_00000000000000000&amp;size=13418496</playbackURI>
</mediaSegmentDescriptor>
<metadataMatches>
<metadataDescriptor>recordType.meta.hikvision.com/timing</metadataDescriptor>
</metadataMatches>
</searchMatchItem>
</matchList>
</CMSearchResult>"""

    private val camera = IpCamera(id = "c", name = "Hik", ipAddress = "192.168.1.203", username = "admin", password = "p@ss")

    @Test
    fun parsesRecordingInCameraLocalTime() {
        val page = HikvisionArchive.parseSearch(searchOne, msk)
        val recording = page.items.single()
        assertFalse(page.hasMore)
        assertEquals(LocalDateTime.of(2026, 10, 22, 20, 45, 34), recording.start)
        assertEquals(LocalDateTime.of(2026, 10, 22, 20, 47, 36), recording.end)
        assertEquals(LocalDate.of(2026, 10, 22), recording.day)
        assertEquals(Instant.parse("2026-10-22T17:45:34Z"), recording.startUtc)
        assertEquals(122_000L, recording.durationMs)
        assertEquals(101, recording.trackId)
        assertEquals(13418496L, recording.sizeBytes)
        assertEquals("timing", recording.kind)
        assertEquals(
            "/Streaming/tracks/101/?starttime=20261022T174534Z&endtime=20261022T174736Z&name=ch01_00000000000000000&size=13418496",
            recording.playbackPath
        )
    }

    @Test
    fun noMatchesGivesEmptyPage() {
        val page = HikvisionArchive.parseSearch(
            """<CMSearchResult><responseStatus>true</responseStatus><responseStatusStrg>NO MATCHES</responseStatusStrg>
<numOfMatches>0</numOfMatches><matchList></matchList></CMSearchResult>""",
            msk
        )
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun moreStatusRequestsNextPage() {
        val more = searchOne.replace("<responseStatusStrg>OK<", "<responseStatusStrg>MORE<")
        val page = HikvisionArchive.parseSearch(more, msk)
        assertTrue(page.hasMore)
        assertEquals(1, page.matches)
    }

    @Test
    fun recordingAcrossMidnightBelongsToDayOfItsStart() {
        val xml = searchOne
            .replace("2026-10-22T17:45:34Z", "2026-10-22T20:55:00Z")
            .replace("2026-10-22T17:47:36Z", "2026-10-22T21:05:00Z")
        val recording = HikvisionArchive.parseSearch(xml, msk).items.single()
        // 20:55Z — это 23:55 по камере (UTC+3), конец уже на следующих сутках
        assertEquals(LocalDate.of(2026, 10, 22), recording.day)
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 5, 0), recording.end)
    }

    @Test
    fun readsTimeZoneOffsetOfCamera() {
        val xml = "<Time><timeMode>manual</timeMode><localTime>2026-10-22T20:42:14+03:00</localTime><timeZone>UTC-3:00:00</timeZone></Time>"
        assertEquals(ZoneOffset.ofHours(3), HikvisionArchive.parseOffset(xml))
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), HikvisionArchive.parseOffset("<localTime>2026-01-01T00:00:00-05:30</localTime>"))
        assertEquals(ZoneOffset.UTC, HikvisionArchive.parseOffset("<localTime>2026-01-01T00:00:00Z</localTime>"))
        assertNull(HikvisionArchive.parseOffset("<localTime>2026-01-01T00:00:00</localTime>"))
    }

    @Test
    fun playbackUrlFromStartKeepsCameraAddress() {
        val recording = HikvisionArchive.parseSearch(searchOne, msk).items.single()
        // Пароль со спецсимволами экранируется (как в адресе живой трансляции)
        assertEquals(
            "rtsp://admin:p%40ss@192.168.1.203:554/Streaming/tracks/101/?starttime=20261022T174534Z&endtime=20261022T174736Z&name=ch01_00000000000000000&size=13418496",
            HikvisionArchive.playbackUrl(camera, recording)
        )
    }

    @Test
    fun playbackUrlWithOffsetMovesStartTime() {
        val recording = HikvisionArchive.parseSearch(searchOne, msk).items.single()
        val url = HikvisionArchive.playbackUrl(camera, recording, offsetMs = 30_500)
        assertTrue(url, url.endsWith("/Streaming/tracks/101/?starttime=20261022T174604Z&endtime=20261022T174736Z"))
    }

    @Test
    fun playbackUrlOffsetNeverReachesEnd() {
        val recording = HikvisionArchive.parseSearch(searchOne, msk).items.single()
        val url = HikvisionArchive.playbackUrl(camera, recording, offsetMs = 10_000_000)
        assertTrue(url, url.contains("starttime=20261022T174735Z"))
    }

    @Test
    fun searchRequestUsesUtcAndPaging() {
        val body = HikvisionArchive.searchRequest(
            Instant.parse("2026-10-21T21:00:00Z"), Instant.parse("2026-10-22T21:00:00Z"), position = 100, count = 50
        )
        assertTrue(body.contains("<startTime>2026-10-21T21:00:00Z</startTime>"))
        assertTrue(body.contains("<endTime>2026-10-22T21:00:00Z</endTime>"))
        assertTrue(body.contains("<searchResultPostion>100</searchResultPostion>"))
        assertTrue(body.contains("<maxResults>50</maxResults>"))
        assertTrue(body.contains("<trackID>101</trackID>"))
    }

    // --- SD-карта ---

    private fun storage(status: String, capacity: Int, free: Int) = """<?xml version="1.0" encoding="UTF-8"?>
<storage version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<hddList version="1.0" xmlns="http://www.hikvision.com/ver10/XMLSchema" size="8" >
<hdd version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
<id>1</id>
<hddName>hdde</hddName>
<hddPath></hddPath>
<hddType>SATA</hddType>
<status>$status</status>
<capacity>$capacity</capacity>
<freeSpace>$free</freeSpace>
<property>RW</property>
<property>RW</property>
</hdd>
</hddList>
<workMode>quota</workMode>
</storage>"""

    @Test
    fun readsFormattedCard() {
        val volume = HikvisionStorage.parseStorage(storage("ok", 15001, 14336)).volumes.single()
        assertEquals("Success", volume.state)
        assertEquals("ReadWrite", volume.type)
        assertEquals("1", volume.device)
        assertEquals(15001L * 1024 * 1024, volume.totalBytes)
        assertEquals((15001L - 14336L) * 1024 * 1024, volume.usedBytes)
    }

    @Test
    fun unformattedCardIsShownAsReported() {
        val volume = HikvisionStorage.parseStorage(storage("unformatted", 15001, 0)).volumes.single()
        assertEquals("unformatted", volume.state)
    }

    @Test
    fun cameraWithoutCardGivesNoVolumes() {
        assertTrue(HikvisionStorage.parseStorage("<storage><hddList size=\"8\"></hddList></storage>").volumes.isEmpty())
    }
}
