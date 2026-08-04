package dev.ben.aqiwidget

import dev.ben.aqiwidget.provider.AqiProvider
import java.io.IOException

/**
 * Resolves location, fetches a reading, caches it, and reduces the result to a [RenderState].
 *
 * Depends only on the interfaces in Ports.kt, never on android.* types.
 */
class AqiRepository(
    private val provider: AqiProvider,
    private val location: LocationSource,
    private val store: ReadingStore,
    private val clock: Clock,
    private val hasPermission: () -> Boolean,
) {

    /**
     * Blocking. Never call on the main thread.
     *
     * A missing permission short-circuits to [RenderState.NeedsPermission] even when a cached
     * reading exists. That is intentional and distinct from the never-blank-on-failure rule:
     * a revoked permission is a configuration problem only the user can fix, and the tile's
     * tap target must route to SetupActivity to say so.
     */
    fun refresh(): RenderState {
        if (!hasPermission()) return RenderState.NeedsPermission

        val coordinates = location.lastKnown()?.also(store::saveCoordinates)
            ?: store.coordinates()
            ?: return RenderState.NoLocation

        return try {
            val reading = provider.fetch(coordinates.lat, coordinates.lon)
            store.saveReading(reading.aqi, reading.observedAt, reading.station)
            toState(CachedReading(reading.aqi, reading.observedAt, reading.station))
        } catch (e: IOException) {
            // Never blank a good value on a failed fetch. The next hourly tick retries.
            cached()
        }
    }

    /** Renders from cache with no network or location access. */
    fun cached(): RenderState = store.reading()?.let(::toState) ?: RenderState.NoData

    private fun toState(r: CachedReading) =
        RenderState.Ok(r.aqi, AqiScale.isStale(r.observedAt, clock.now()))
}
