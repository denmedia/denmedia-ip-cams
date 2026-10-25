package com.example.denmediaipcams

import com.example.denmediaipcams.network.HipcamArchive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HipcamArchiveTest {

    // Ответы камеры (Hipcam RealServer) на запросы /sd/ и /sd/20261010/record000/
    private val rootListing = """<html><head><title>Index of /sd/</title></head><body><pre><table cellpadding="0"><tr><th><a href="?nd">Name</a></th></tr><tr><td colspan="3"><hr></td></tr><tr><td><a href="/sd/..">Parent directory</a></td><td>&nbsp;-</td><td>&nbsp;&nbsp;-</td></tr>
<tr><td><a href="/sd/20260928/">20260928/</a></td><td>&nbsp;28-Sep-2026 00:10</td><td>&nbsp;&nbsp;[DIRECTORY]</td></tr>
<tr><td><a href="/sd/20261010/">20261010/</a></td><td>&nbsp;10-Oct-2026 00:10</td><td>&nbsp;&nbsp;[DIRECTORY]</td></tr>
</table></body></html>"""

    private val folderListing = """<html><body><table>
<tr><td><a href="/sd/20261010/record000/..">Parent directory</a></td><td>&nbsp;-</td><td>&nbsp;&nbsp;-</td></tr>
<tr><td><a href="/sd/20261010/record000/P261010_020112_021118.265">P261010_020112_021118.265</a></td><td>&nbsp;10-Oct-2026 02:11</td><td>&nbsp;&nbsp;80.0M</td></tr>
<tr><td><a href="/sd/20261010/record000/P261010_235900_000906.265">P261010_235900_000906.265</a></td><td>&nbsp;11-Oct-2026 00:09</td><td>&nbsp;&nbsp;13.1k</td></tr>
<tr><td><a href="/sd/20261010/record000/readme.txt">readme.txt</a></td><td>&nbsp;10-Oct-2026 02:11</td><td>&nbsp;&nbsp;1k</td></tr>
</table></body></html>"""

    @Test
    fun parsesDaysFromRoot() {
        assertEquals(
            listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 10)),
            HipcamArchive.parseDays(rootListing)
        )
    }

    @Test
    fun parsesRecordingsAndSkipsForeignFiles() {
        val day = LocalDate.of(2026, 10, 10)
        val recordings = HipcamArchive.parseRecordings(HipcamArchive.parseEntries(folderListing), day, "/sd/20261010/record000/")
        assertEquals(2, recordings.size)
        val first = recordings[0]
        assertEquals(LocalDateTime.of(2026, 10, 10, 2, 1, 12), first.start)
        assertEquals(LocalDateTime.of(2026, 10, 10, 2, 11, 18), first.end)
        assertEquals("/sd/20261010/record000/P261010_020112_021118.265", first.path)
        assertEquals('P', first.kind)
        assertEquals(80L * 1024 * 1024, first.sizeBytes)
        assertEquals(606_000L, first.durationMs)
    }

    @Test
    fun recordingAcrossMidnightEndsNextDay() {
        val day = LocalDate.of(2026, 10, 10)
        val recording = HipcamArchive.parseRecordings(HipcamArchive.parseEntries(folderListing), day, "/sd/20261010/record000/")[1]
        assertEquals(LocalDateTime.of(2026, 10, 10, 23, 59, 0), recording.start)
        assertEquals(LocalDateTime.of(2026, 10, 11, 0, 9, 6), recording.end)
        assertEquals(606_000L, recording.durationMs)
    }

    @Test
    fun parsesSizes() {
        assertEquals(13414L, HipcamArchive.parseSize("13.1k"))
        assertEquals(512L, HipcamArchive.parseSize("512"))
        assertEquals(0L, HipcamArchive.parseSize("[DIRECTORY]"))
    }
}
