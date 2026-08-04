package dev.ben.aqiwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AqiScaleTest {

    @Test
    fun `category boundaries match the EPA scale`() {
        assertEquals(AqiCategory.GOOD, AqiScale.categoryFor(0))
        assertEquals(AqiCategory.GOOD, AqiScale.categoryFor(50))
        assertEquals(AqiCategory.MODERATE, AqiScale.categoryFor(51))
        assertEquals(AqiCategory.MODERATE, AqiScale.categoryFor(100))
        assertEquals(AqiCategory.UNHEALTHY_SENSITIVE, AqiScale.categoryFor(101))
        assertEquals(AqiCategory.UNHEALTHY_SENSITIVE, AqiScale.categoryFor(150))
        assertEquals(AqiCategory.UNHEALTHY, AqiScale.categoryFor(151))
        assertEquals(AqiCategory.UNHEALTHY, AqiScale.categoryFor(200))
        assertEquals(AqiCategory.VERY_UNHEALTHY, AqiScale.categoryFor(201))
        assertEquals(AqiCategory.VERY_UNHEALTHY, AqiScale.categoryFor(300))
        assertEquals(AqiCategory.HAZARDOUS, AqiScale.categoryFor(301))
        assertEquals(AqiCategory.HAZARDOUS, AqiScale.categoryFor(500))
    }

    @Test
    fun `backgrounds are the official AirNow palette`() {
        assertEquals(0xFF00E400.toInt(), AqiCategory.GOOD.background)
        assertEquals(0xFFFFFF00.toInt(), AqiCategory.MODERATE.background)
        assertEquals(0xFFFF7E00.toInt(), AqiCategory.UNHEALTHY_SENSITIVE.background)
        assertEquals(0xFFFF0000.toInt(), AqiCategory.UNHEALTHY.background)
        assertEquals(0xFF8F3F97.toInt(), AqiCategory.VERY_UNHEALTHY.background)
        assertEquals(0xFF7E0023.toInt(), AqiCategory.HAZARDOUS.background)
    }

    @Test
    fun `foreground flips to white at the red threshold`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, AqiCategory.GOOD.foreground)
        assertEquals(black, AqiCategory.MODERATE.foreground)
        assertEquals(black, AqiCategory.UNHEALTHY_SENSITIVE.foreground)
        assertEquals(white, AqiCategory.UNHEALTHY.foreground)
        assertEquals(white, AqiCategory.VERY_UNHEALTHY.foreground)
        assertEquals(white, AqiCategory.HAZARDOUS.foreground)
    }

    @Test
    fun `stale only after three hours`() {
        val now = 1_000_000_000L
        val threeHours = 3L * 60 * 60 * 1000
        assertFalse(AqiScale.isStale(now, now))
        assertFalse(AqiScale.isStale(now - threeHours, now))
        assertTrue(AqiScale.isStale(now - threeHours - 1, now))
    }

    @Test
    fun `dim blends 55 percent toward grey with integer math`() {
        // green 0x00E400 -> r,b = (0*45 + 158*55 + 50)/100 = 87 = 0x57
        //                     g = (228*45 + 158*55 + 50)/100 = 190 = 0xBE
        assertEquals(0xFF57BE57.toInt(), AqiScale.dim(AqiCategory.GOOD.background))
    }

    @Test
    fun `dim preserves alpha and is a no-op on grey itself`() {
        val grey = 0xFF9E9E9E.toInt()
        assertEquals(grey, AqiScale.dim(grey))
    }

    @Test
    fun `dim always moves a color closer to grey`() {
        for (category in AqiCategory.entries) {
            val dimmed = AqiScale.dim(category.background)
            assertEquals("alpha preserved", 0xFF, (dimmed ushr 24) and 0xFF)
            assertTrue(
                "dimmed ${category.name} should differ from original",
                dimmed != category.background || category.background == 0xFF9E9E9E.toInt()
            )
        }
    }
}
