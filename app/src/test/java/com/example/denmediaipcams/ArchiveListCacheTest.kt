package com.example.denmediaipcams

import com.example.denmediaipcams.data.ArchiveListCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ArchiveListCacheTest {

    private var now = 1_000_000L

    @Before
    fun setUp() {
        ArchiveListCache.clock = { now }
    }

    @After
    fun tearDown() {
        ArchiveListCache.clock = System::currentTimeMillis
        ArchiveListCache.clearCamera("cam1")
        ArchiveListCache.clearCamera("cam2")
    }

    @Test
    fun returnsSavedListUntilTtlExpires() {
        val key = ArchiveListCache.daysKey("dvrip", "cam1")
        val days = listOf(LocalDate.of(2026, 10, 10))
        ArchiveListCache.put(key, days)

        now += ArchiveListCache.TTL_MS - 1
        assertEquals(days, ArchiveListCache.get<List<LocalDate>>(key))

        now += 1
        assertNull(ArchiveListCache.get<List<LocalDate>>(key))
    }

    @Test
    fun putRenewsTtl() {
        val key = ArchiveListCache.daysKey("hipcam", "cam1")
        ArchiveListCache.put(key, listOf(1))
        now += ArchiveListCache.TTL_MS - 1
        ArchiveListCache.put(key, listOf(2))
        now += ArchiveListCache.TTL_MS - 1
        assertEquals(listOf(2), ArchiveListCache.get<List<Int>>(key))
    }

    @Test
    fun clearCameraDropsOnlyThatCamera() {
        val day = LocalDate.of(2026, 10, 10)
        ArchiveListCache.put(ArchiveListCache.daysKey("dvrip", "cam1"), listOf(day))
        ArchiveListCache.put(ArchiveListCache.dayKey("dvrip", "cam1", day), listOf("a"))
        ArchiveListCache.put(ArchiveListCache.daysKey("dvrip", "cam2"), listOf(day))

        ArchiveListCache.clearCamera("cam1")

        assertNull(ArchiveListCache.get<List<LocalDate>>(ArchiveListCache.daysKey("dvrip", "cam1")))
        assertNull(ArchiveListCache.get<List<String>>(ArchiveListCache.dayKey("dvrip", "cam1", day)))
        assertEquals(listOf(day), ArchiveListCache.get<List<LocalDate>>(ArchiveListCache.daysKey("dvrip", "cam2")))
    }
}
