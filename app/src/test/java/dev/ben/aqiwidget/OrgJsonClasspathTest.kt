package dev.ben.aqiwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards a specific classpath hazard: with `unitTests.isReturnDefaultValues = true`, the
 * android.jar stub's org.json classes silently return default values (e.g. 0) instead of
 * throwing, so a build misconfiguration that let the stub win over the real `org.json:json`
 * dependency would fail silently rather than loudly. This test would catch that.
 */
class OrgJsonClasspathTest {

    @Test
    fun `real org_json is on the test classpath, not the android stub`() {
        // The android.jar stub returns 0 here; the real implementation returns 56.
        val json = JSONObject("""{"current":{"us_aqi":56}}""")
        assertEquals(56, json.getJSONObject("current").getInt("us_aqi"))
    }
}
