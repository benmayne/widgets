package dev.ben.aqiwidget

/**
 * EPA AQI categories with the official AirNow palette, as ARGB ints.
 *
 * Deliberately plain [Int]s rather than android.graphics.Color so this file has no
 * android.* imports and stays unit-testable on the JVM.
 */
enum class AqiCategory(
    val label: String,
    val background: Int,
    val foreground: Int,
) {
    GOOD("Good", 0xFF00E400.toInt(), 0xFF000000.toInt()),
    MODERATE("Moderate", 0xFFFFFF00.toInt(), 0xFF000000.toInt()),
    UNHEALTHY_SENSITIVE("Unhealthy for Sensitive Groups", 0xFFFF7E00.toInt(), 0xFF000000.toInt()),
    UNHEALTHY("Unhealthy", 0xFFFF0000.toInt(), 0xFFFFFFFF.toInt()),
    VERY_UNHEALTHY("Very Unhealthy", 0xFF8F3F97.toInt(), 0xFFFFFFFF.toInt()),
    HAZARDOUS("Hazardous", 0xFF7E0023.toInt(), 0xFFFFFFFF.toInt()),
}

object AqiScale {

    /** A reading older than this is rendered dimmed. */
    const val STALE_AFTER_MILLIS: Long = 3L * 60 * 60 * 1000

    /**
     * A cached reading younger than this is served as-is on a non-forced refresh, skipping
     * both the location read and the network fetch. Safe against the 1-hour
     * `updatePeriodMillis`: the hourly tick's cache is always older than this, so scheduled
     * refreshes still always fetch. Only the extra broadcasts (reboot, launcher restart,
     * widget re-add, app update) get skipped, since upstream data only changes hourly anyway.
     */
    const val FRESH_ENOUGH_MILLIS: Long = 30L * 60 * 1000

    /** Widget background for NeedsPermission / NoLocation / NoData — no AQI to color by. */
    val NEUTRAL_BACKGROUND = 0xFF616161.toInt()

    /** Widget foreground (the "—" glyph) to pair with [NEUTRAL_BACKGROUND]. */
    val NEUTRAL_FOREGROUND = 0xFFFFFFFF.toInt()

    /** Target color for [dim]'s stale-reading blend. Distinct from the neutral-state greys. */
    private val BLEND_GREY = 0xFF9E9E9E.toInt()
    private const val DIM_PERCENT = 55

    fun categoryFor(aqi: Int): AqiCategory = when {
        aqi <= 50 -> AqiCategory.GOOD
        aqi <= 100 -> AqiCategory.MODERATE
        aqi <= 150 -> AqiCategory.UNHEALTHY_SENSITIVE
        aqi <= 200 -> AqiCategory.UNHEALTHY
        aqi <= 300 -> AqiCategory.VERY_UNHEALTHY
        else -> AqiCategory.HAZARDOUS
    }

    fun isStale(observedAt: Long, now: Long): Boolean = now - observedAt > STALE_AFTER_MILLIS

    /**
     * Blends [color] 55% toward grey to signal a stale reading.
     *
     * Uses integer math rather than floats: float rounding at an exact .5 boundary is
     * not reproducible across platforms, which would make the result untestable.
     */
    fun dim(color: Int): Int = blend(color, BLEND_GREY, DIM_PERCENT)

    private fun blend(from: Int, to: Int, percentToward: Int): Int {
        fun channel(shift: Int): Int {
            val f = (from ushr shift) and 0xFF
            val t = (to ushr shift) and 0xFF
            return (f * (100 - percentToward) + t * percentToward + 50) / 100
        }
        val alpha = (from ushr 24) and 0xFF
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
