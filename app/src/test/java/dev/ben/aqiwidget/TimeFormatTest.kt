package dev.ben.aqiwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeFormatTest {

    private val noonUtc = Instant.parse("2026-08-03T19:00:00Z").toEpochMilli()

    @Test
    fun `renders in the supplied local zone, not GMT`() {
        val la = TimeFormat.localTime(noonUtc, ZoneId.of("America/Los_Angeles"))
        val utc = TimeFormat.localTime(noonUtc, ZoneId.of("UTC"))
        assertNotEquals(utc, la)
        assertEquals("Aug 3, 12:00 PM", la)
        assertEquals("Aug 3, 7:00 PM", utc)
    }

    @Test
    fun `staleness verdict is identical regardless of timezone`() {
        // observedAt is epoch millis, so the verdict is timezone-independent by construction.
        val now = noonUtc + AqiScale.STALE_AFTER_MILLIS + 1
        val zones = listOf("UTC", "America/Los_Angeles", "Asia/Tokyo", "Pacific/Kiritimati")
        val verdicts = zones.map {
            val previous = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(it))
                AqiScale.isStale(noonUtc, now)
            } finally {
                java.util.TimeZone.setDefault(previous)
            }
        }
        assertEquals(listOf(true, true, true, true), verdicts)
    }

    @Test
    fun `omitted zone resolves the system default and shifts when it changes`() {
        // This is the exact mechanism the "GMT stays invisible to the user" requirement rests
        // on: the zero-argument overload must re-resolve ZoneId.systemDefault() rather than
        // caching it, so changing the platform default changes the rendered string.
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val la = TimeFormat.localTime(noonUtc)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = TimeFormat.localTime(noonUtc)

            assertNotEquals(la, tokyo)
            assertEquals("Aug 3, 12:00 PM", la)
            assertEquals("Aug 4, 4:00 AM", tokyo)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `crossing a timezone changes display only, never the stored value`() {
        val tokyo = TimeFormat.localTime(noonUtc, ZoneId.of("Asia/Tokyo"))
        val la = TimeFormat.localTime(noonUtc, ZoneId.of("America/Los_Angeles"))
        assertNotEquals(tokyo, la)
        // Same instant underlies both renderings.
        assertEquals(noonUtc, Instant.parse("2026-08-03T19:00:00Z").toEpochMilli())
    }
}
