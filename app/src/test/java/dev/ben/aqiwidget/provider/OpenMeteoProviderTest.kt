package dev.ben.aqiwidget.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class OpenMeteoProviderTest {

    private val provider = OpenMeteoProvider()

    /** Captured verbatim from the live API during design. */
    private val realResponse = """
        {"latitude":37.800003,"longitude":-122.399994,"generationtime_ms":0.236,
         "utc_offset_seconds":0,"timezone":"GMT","timezone_abbreviation":"GMT",
         "elevation":14.0,
         "current_units":{"time":"iso8601","interval":"seconds","us_aqi":"USAQI"},
         "current":{"time":"2026-08-03T19:00","interval":3600,"us_aqi":56}}
    """.trimIndent()

    @Test
    fun `parses aqi from a real response`() {
        assertEquals(56, provider.parse(JSONObject(realResponse)).aqi)
    }

    @Test
    fun `parses time as UTC epoch millis`() {
        val expected = Instant.parse("2026-08-03T19:00:00Z").toEpochMilli()
        assertEquals(expected, provider.parse(JSONObject(realResponse)).observedAt)
    }

    @Test
    fun `model-based source reports no station`() {
        assertNull(provider.parse(JSONObject(realResponse)).station)
    }

    @Test
    fun `url pins timezone to GMT so time parses unambiguously`() {
        val url = provider.urlFor(37.77, -122.42)
        assertTrue(url, url.startsWith("https://air-quality-api.open-meteo.com/v1/air-quality?"))
        assertTrue(url, url.contains("latitude=37.77"))
        assertTrue(url, url.contains("longitude=-122.42"))
        assertTrue(url, url.contains("current=us_aqi"))
        assertTrue(url, url.contains("timezone=GMT"))
    }

    @Test
    fun `missing current block raises IOException`() {
        val e = runCatching { provider.parse(JSONObject("""{"latitude":1.0}""")) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `null us_aqi raises IOException`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":null}}"""
        val e = runCatching { provider.parse(JSONObject(json)) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `absent us_aqi raises IOException`() {
        val json = """{"current":{"time":"2026-08-03T19:00"}}"""
        val e = runCatching { provider.parse(JSONObject(json)) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `unparseable time falls back to fetch time instead of throwing`() {
        val fetchTime = 1_700_000_000_000L
        val fixedClockProvider = OpenMeteoProvider(now = { fetchTime })
        val json = """{"current":{"time":"not-a-time","us_aqi":56}}"""
        val reading = fixedClockProvider.parse(JSONObject(json))
        assertEquals(56, reading.aqi)
        assertEquals(fetchTime, reading.observedAt)
    }

    @Test
    fun `absent time falls back to fetch time instead of throwing`() {
        val fetchTime = 1_700_000_000_000L
        val fixedClockProvider = OpenMeteoProvider(now = { fetchTime })
        val json = """{"current":{"us_aqi":56}}"""
        val reading = fixedClockProvider.parse(JSONObject(json))
        assertEquals(56, reading.aqi)
        assertEquals(fetchTime, reading.observedAt)
    }

    @Test
    fun `provider reports its name for the setup screen`() {
        assertEquals("Open-Meteo", provider.name)
    }

    @Test
    fun `in-range aqi passes through unchanged`() {
        assertEquals(56, provider.parse(JSONObject(realResponse)).aqi)
    }

    @Test
    fun `negative aqi clamps to 0`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":-5}}"""
        assertEquals(0, provider.parse(JSONObject(json)).aqi)
    }

    @Test
    fun `aqi above 500 clamps to 500`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":999}}"""
        assertEquals(500, provider.parse(JSONObject(json)).aqi)
    }

    @Test
    fun `boundary value 0 passes through unchanged`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":0}}"""
        assertEquals(0, provider.parse(JSONObject(json)).aqi)
    }

    @Test
    fun `boundary value 500 passes through unchanged`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":500}}"""
        assertEquals(500, provider.parse(JSONObject(json)).aqi)
    }

    @Test
    fun `non-numeric us_aqi raises IOException`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":"abc"}}"""
        val e = runCatching { provider.parse(JSONObject(json)) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }
}
