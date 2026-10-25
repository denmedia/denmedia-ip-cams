package com.example.denmediaipcams

import com.example.denmediaipcams.network.PosixTimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PosixTimeZoneTest {

    private val at = Instant.parse("2026-10-16T12:00:00Z")

    private fun posix(zone: String) = PosixTimeZone.of(ZoneId.of(zone), at)

    @Test
    fun europeanZoneWithSummerTime() {
        assertEquals("CET-1CEST,M3.5.0,M10.5.0/3", posix("Europe/Berlin"))
        assertEquals("EET-2EEST,M3.5.0/3,M10.5.0/4", posix("Europe/Athens"))
    }

    @Test
    fun americanZoneWithSummerTime() {
        assertEquals("EST5EDT,M3.2.0,M11.1.0", posix("America/New_York"))
    }

    @Test
    fun zoneWithoutSummerTime() {
        assertEquals("MSK-3", posix("Europe/Moscow"))
    }

    @Test
    fun fractionalOffsetIsWrittenWithMinutes() {
        assertEquals("IST-5:30", posix("Asia/Kolkata"))
    }

    @Test
    fun parseActiveOffsetSecondsRoundTripsWithoutSummerTime() {
        // Europe/Moscow: "MSK-3", без перехода на летнее время — значение одинаково для обоих daylight
        assertEquals(3 * 3600, PosixTimeZone.parseActiveOffsetSeconds("MSK-3", daylight = false))
        assertEquals(3 * 3600, PosixTimeZone.parseActiveOffsetSeconds("MSK-3", daylight = true))
    }

    @Test
    fun parseActiveOffsetSecondsPicksStandardOrSummerPart() {
        val tz = posix("Europe/Berlin") // "CET-1CEST,M3.5.0,M10.5.0/3"
        assertEquals(1 * 3600, PosixTimeZone.parseActiveOffsetSeconds(tz, daylight = false))
        assertEquals(2 * 3600, PosixTimeZone.parseActiveOffsetSeconds(tz, daylight = true))
    }

    @Test
    fun parseActiveOffsetSecondsIgnoresCamerasOwnZoneName() {
        // Камера эхом может вернуть не ту строку, что мы послали, а своё название с тем же смещением —
        // значение должно разбираться одинаково независимо от имени
        assertEquals(-3 * 3600, PosixTimeZone.parseActiveOffsetSeconds("BRT3", daylight = false))
        assertEquals(3 * 3600, PosixTimeZone.parseActiveOffsetSeconds("EAT-3", daylight = false))
    }

    @Test
    fun parseActiveOffsetSecondsHandlesFractionalOffset() {
        assertEquals(5 * 3600 + 30 * 60, PosixTimeZone.parseActiveOffsetSeconds("IST-5:30", daylight = false))
    }

    @Test
    fun parseActiveOffsetSecondsReturnsNullForUnparsableString() {
        assertEquals(null, PosixTimeZone.parseActiveOffsetSeconds("", daylight = false))
    }
}
