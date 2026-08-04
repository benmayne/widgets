package dev.ben.aqiwidget.provider

import java.io.IOException

/**
 * A source of air quality readings.
 *
 * CONTRACT: [Reading.aqi] MUST be on the US EPA AQI scale (0-500), regardless of what scale
 * the underlying service natively reports. Implementations are responsible for normalizing.
 *
 * This is not incidental. The European AQI runs a different 0-100 scale on which 60 is
 * severe rather than moderate, so a provider returning its native scale would silently
 * invert the color mapping in AqiScale and render a green tile on hazardous air.
 */
interface AqiProvider {

    /** Human-readable source name, shown in SetupActivity. */
    val name: String

    /**
     * Fetches the current reading for a coordinate. Blocking; never call on the main thread.
     *
     * @throws IOException on network, HTTP, or parse failure.
     */
    @Throws(IOException::class)
    fun fetch(lat: Double, lon: Double): Reading
}

/**
 * @property aqi US EPA scale, clamped to 0-500. Clamping lives here rather than in each
 *   provider so the invariant travels with the type instead of depending on every provider
 *   author remembering it: an anomalous reading still carries directional meaning — above 500
 *   genuinely is hazardous — so clamping keeps the tile current and correctly colored at the
 *   extreme instead of falling back to stale data. This is out-of-range protection only; it
 *   does *not* detect a provider reporting on the wrong scale entirely (see [AqiProvider]'s
 *   CONTRACT note), which is not mechanically detectable and relies on documentation and
 *   reviewer diligence.
 * @property observedAt epoch millis of the measurement. Providers use the service's own
 *   measurement timestamp when it can be resolved unambiguously, otherwise the fetch time.
 * @property station identifier of the reporting ground station, or null for model-based
 *   sources such as Open-Meteo that have no station.
 */
@ConsistentCopyVisibility
data class Reading private constructor(
    val aqi: Int,
    val observedAt: Long,
    val station: String? = null,
) {
    companion object {
        operator fun invoke(aqi: Int, observedAt: Long, station: String? = null): Reading =
            Reading(aqi.coerceIn(0, 500), observedAt, station)
    }
}
