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
 * @property aqi US EPA scale, 0-500.
 * @property observedAt epoch millis of the measurement. Providers use the service's own
 *   measurement timestamp when it can be resolved unambiguously, otherwise the fetch time.
 * @property station identifier of the reporting ground station, or null for model-based
 *   sources such as Open-Meteo that have no station.
 */
data class Reading(
    val aqi: Int,
    val observedAt: Long,
    val station: String? = null,
)
