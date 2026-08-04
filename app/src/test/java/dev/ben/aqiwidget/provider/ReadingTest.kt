package dev.ben.aqiwidget.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the 0-500 clamp at the type level rather than trusting each [AqiProvider] implementer
 * to apply it. See [Reading]'s KDoc: this is out-of-range protection only, distinct from (and
 * not a defense against) a provider reporting on the wrong scale entirely.
 */
class ReadingTest {

    @Test
    fun `negative aqi clamps to 0`() {
        assertEquals(0, Reading(aqi = -5, observedAt = 0L).aqi)
    }

    @Test
    fun `aqi above 500 clamps to 500`() {
        assertEquals(500, Reading(aqi = 999, observedAt = 0L).aqi)
    }

    @Test
    fun `boundary value 0 passes through unchanged`() {
        assertEquals(0, Reading(aqi = 0, observedAt = 0L).aqi)
    }

    @Test
    fun `boundary value 500 passes through unchanged`() {
        assertEquals(500, Reading(aqi = 500, observedAt = 0L).aqi)
    }

    @Test
    fun `in-range aqi passes through unchanged`() {
        assertEquals(56, Reading(aqi = 56, observedAt = 0L).aqi)
    }
}
