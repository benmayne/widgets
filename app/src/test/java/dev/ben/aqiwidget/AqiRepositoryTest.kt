package dev.ben.aqiwidget

import dev.ben.aqiwidget.provider.AqiProvider
import dev.ben.aqiwidget.provider.Reading
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AqiRepositoryTest {

    private val now = 1_700_000_000_000L
    private val here = Coordinates(37.77, -122.42)

    private class FakeProvider(
        var reading: Reading? = null,
        var failure: IOException? = null,
    ) : AqiProvider {
        override val name = "Fake"
        var calls = 0
        var lastLat: Double? = null
        var lastLon: Double? = null
        override fun fetch(lat: Double, lon: Double): Reading {
            calls++
            lastLat = lat
            lastLon = lon
            failure?.let { throw it }
            return reading!!
        }
    }

    private class FakeLocation(var value: Coordinates?) : LocationSource {
        var calls = 0
        override fun lastKnown(): Coordinates? {
            calls++
            return value
        }
    }

    private class FakeStore(
        private var cached: CachedReading? = null,
        private var coords: Coordinates? = null,
    ) : ReadingStore {
        override fun saveReading(aqi: Int, observedAt: Long, station: String?) {
            cached = CachedReading(aqi, observedAt, station)
        }
        override fun saveCoordinates(c: Coordinates) {
            coords = c
        }
        override fun reading(): CachedReading? = cached
        override fun coordinates(): Coordinates? = coords
    }

    private fun repo(
        provider: AqiProvider,
        location: LocationSource = FakeLocation(here),
        store: ReadingStore = FakeStore(),
        permission: Boolean = true,
    ) = AqiRepository(provider, location, store, { now }, { permission })

    @Test
    fun `fresh fetch returns Ok and is not stale`() {
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        assertEquals(RenderState.Ok(56, stale = false), repo(provider).refresh())
    }

    @Test
    fun `reading older than three hours is marked stale`() {
        val old = now - AqiScale.STALE_AFTER_MILLIS - 1
        val provider = FakeProvider(Reading(aqi = 56, observedAt = old))
        assertEquals(RenderState.Ok(56, stale = true), repo(provider).refresh())
    }

    @Test
    fun `reading exactly at the threshold is not yet stale`() {
        val edge = now - AqiScale.STALE_AFTER_MILLIS
        val provider = FakeProvider(Reading(aqi = 56, observedAt = edge))
        assertEquals(RenderState.Ok(56, stale = false), repo(provider).refresh())
    }

    @Test
    fun `fetch failure falls back to the cached reading instead of blanking`() {
        val store = FakeStore(cached = CachedReading(aqi = 42, observedAt = now))
        val provider = FakeProvider(failure = IOException("offline"))
        assertEquals(RenderState.Ok(42, stale = false), repo(provider, store = store).refresh())
    }

    @Test
    fun `fetch failure with no cache yields NoData`() {
        val provider = FakeProvider(failure = IOException("offline"))
        assertEquals(RenderState.NoData, repo(provider).refresh())
    }

    @Test
    fun `falls back to cached coordinates when the OS has no fix`() {
        val store = FakeStore(coords = here)
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        val state = repo(provider, location = FakeLocation(null), store = store).refresh()
        assertEquals(RenderState.Ok(56, stale = false), state)
        assertEquals(here.lat, provider.lastLat!!, 0.0001)
        assertEquals(here.lon, provider.lastLon!!, 0.0001)
    }

    @Test
    fun `no location and no cached coordinates yields NoLocation without fetching`() {
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        val state = repo(provider, location = FakeLocation(null)).refresh()
        assertEquals(RenderState.NoLocation, state)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `a fresh fix is persisted for later use`() {
        val store = FakeStore()
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        repo(provider, store = store).refresh()
        assertEquals(here, store.coordinates())
    }

    @Test
    fun `a station-based reading round-trips through save and read`() {
        val store = FakeStore()
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now, station = "US Diplomatic Post"))
        repo(provider, store = store).refresh()
        assertEquals("US Diplomatic Post", store.reading()?.station)
    }

    @Test
    fun `a model-based reading with no station stays null after save and read`() {
        val store = FakeStore()
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now, station = null))
        repo(provider, store = store).refresh()
        assertEquals(null, store.reading()?.station)
    }

    @Test
    fun `missing permission short-circuits before any network or location access`() {
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        val location = FakeLocation(here)
        val store = FakeStore()
        val state = repo(provider, location = location, store = store, permission = false).refresh()
        assertEquals(RenderState.NeedsPermission, state)
        assertEquals("Network access was not short-circuited", 0, provider.calls)
        assertEquals("Location access was not short-circuited", 0, location.calls)
        assertEquals("Coordinates were saved despite missing permission", null, store.coordinates())
    }

    @Test
    fun `cached renders from the store without fetching`() {
        val store = FakeStore(cached = CachedReading(aqi = 42, observedAt = now))
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        assertEquals(RenderState.Ok(42, stale = false), repo(provider, store = store).cached())
        assertEquals(0, provider.calls)
    }
}
