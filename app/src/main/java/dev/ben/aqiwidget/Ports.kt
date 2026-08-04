package dev.ben.aqiwidget

/**
 * Seams that keep AqiRepository free of android.* imports so its caching and staleness
 * logic can be unit-tested on the JVM. The Android-backed implementations live in AppGraph.
 */

data class Coordinates(val lat: Double, val lon: Double)

fun interface Clock {
    /** Epoch millis. */
    fun now(): Long
}

interface LocationSource {
    /**
     * The most recent fix the OS already has cached, or null if it has none.
     * Implementations MUST NOT request a new fix — that would power on hardware.
     */
    fun lastKnown(): Coordinates?
}

data class CachedReading(val aqi: Int, val observedAt: Long, val station: String? = null)

interface ReadingStore {
    fun saveReading(aqi: Int, observedAt: Long, station: String?)
    fun saveCoordinates(c: Coordinates)
    fun reading(): CachedReading?
    fun coordinates(): Coordinates?
}

/** Everything the widget needs to render, and nothing more. */
sealed interface RenderState {
    data class Ok(val aqi: Int, val stale: Boolean) : RenderState
    data object NeedsPermission : RenderState
    data object NoLocation : RenderState
    data object NoData : RenderState
}
