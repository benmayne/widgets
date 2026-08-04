package dev.ben.aqiwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldTest {
    @Test
    fun `unit tests run on the jvm`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `real org_json is on the test classpath, not the android stub`() {
        // The android.jar stub returns 0 here; the real implementation returns 56.
        val json = JSONObject("""{"current":{"us_aqi":56}}""")
        assertEquals(56, json.getJSONObject("current").getInt("us_aqi"))
    }
}
