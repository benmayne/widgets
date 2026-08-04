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
        // Formula: (f * 45 + 158 * 55 + 50) / 100 where 158 * 55 + 50 = 8740
        //
        // GOOD (0x00E400 = R:0, G:228, B:0):
        //   R: (0 * 45 + 8740) / 100 = 87
        //   G: (228 * 45 + 8740) / 100 = 190
        //   B: (0 * 45 + 8740) / 100 = 87
        assertEquals(0xFF57BE57.toInt(), AqiScale.dim(AqiCategory.GOOD.background))

        // MODERATE (0xFFFF00 = R:255, G:255, B:0):
        //   R: (255 * 45 + 8740) / 100 = 202
        //   G: (255 * 45 + 8740) / 100 = 202
        //   B: (0 * 45 + 8740) / 100 = 87
        assertEquals(0xFFCACA57.toInt(), AqiScale.dim(AqiCategory.MODERATE.background))

        // UNHEALTHY_SENSITIVE (0xFF7E00 = R:255, G:126, B:0):
        //   R: (255 * 45 + 8740) / 100 = 202
        //   G: (126 * 45 + 8740) / 100 = 144
        //   B: (0 * 45 + 8740) / 100 = 87
        assertEquals(0xFFCA9057.toInt(), AqiScale.dim(AqiCategory.UNHEALTHY_SENSITIVE.background))

        // UNHEALTHY (0xFF0000 = R:255, G:0, B:0):
        //   R: (255 * 45 + 8740) / 100 = 202
        //   G: (0 * 45 + 8740) / 100 = 87
        //   B: (0 * 45 + 8740) / 100 = 87
        assertEquals(0xFFCA5757.toInt(), AqiScale.dim(AqiCategory.UNHEALTHY.background))

        // VERY_UNHEALTHY (0x8F3F97 = R:143, G:63, B:151):
        //   R: (143 * 45 + 8740) / 100 = 151
        //   G: (63 * 45 + 8740) / 100 = 115
        //   B: (151 * 45 + 8740) / 100 = 155
        assertEquals(0xFF97739B.toInt(), AqiScale.dim(AqiCategory.VERY_UNHEALTHY.background))

        // HAZARDOUS (0x7E0023 = R:126, G:0, B:35):
        //   R: (126 * 45 + 8740) / 100 = 144
        //   G: (0 * 45 + 8740) / 100 = 87
        //   B: (35 * 45 + 8740) / 100 = 103
        assertEquals(0xFF905767.toInt(), AqiScale.dim(AqiCategory.HAZARDOUS.background))
    }

    @Test
    fun `dim preserves alpha`() {
        for (category in AqiCategory.entries) {
            val dimmed = AqiScale.dim(category.background)
            assertEquals("alpha preserved for ${category.name}", 0xFF, (dimmed ushr 24) and 0xFF)
        }
    }

    @Test
    fun `neutral-state colors used by the widget's no-data tile are pinned`() {
        assertEquals(0xFF616161.toInt(), AqiScale.NEUTRAL_BACKGROUND)
        assertEquals(0xFFFFFFFF.toInt(), AqiScale.NEUTRAL_FOREGROUND)
    }

    @Test
    fun `dim is a no-op on grey itself`() {
        val grey = 0xFF9E9E9E.toInt()
        assertEquals(grey, AqiScale.dim(grey))
    }

    @Test
    fun `dim always moves a color closer to grey`() {
        val greyValue = 0x9E // 158
        for (category in AqiCategory.entries) {
            val original = category.background
            val dimmed = AqiScale.dim(original)

            // Assert alpha is preserved
            assertEquals("alpha preserved", 0xFF, (dimmed ushr 24) and 0xFF)

            // Check R, G, B channels move closer to grey
            val channels = listOf(
                Triple("R", (original shr 16) and 0xFF, (dimmed shr 16) and 0xFF),
                Triple("G", (original shr 8) and 0xFF, (dimmed shr 8) and 0xFF),
                Triple("B", original and 0xFF, dimmed and 0xFF)
            )

            for ((name, origVal, dimmVal) in channels) {
                if (origVal == greyValue) {
                    assertEquals(
                        "${category.name} $name channel already at grey should stay equal",
                        greyValue, dimmVal
                    )
                } else {
                    val origDist = Math.abs(origVal - greyValue)
                    val dimmDist = Math.abs(dimmVal - greyValue)
                    assertTrue(
                        "${category.name} $name channel should move closer to grey: orig=$origVal (dist=$origDist), dimmed=$dimmVal (dist=$dimmDist), target=$greyValue",
                        dimmDist < origDist
                    )
                }
            }
        }
    }
}
