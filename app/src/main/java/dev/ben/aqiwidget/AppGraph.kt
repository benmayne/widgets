package dev.ben.aqiwidget

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import dev.ben.aqiwidget.provider.Providers

/** Hand-rolled wiring. A DI framework would be a dependency for no benefit at this size. */
object AppGraph {

    fun repository(context: Context): AqiRepository {
        val app = context.applicationContext
        return AqiRepository(
            provider = Providers.ACTIVE,
            location = AndroidLocationSource(app),
            store = store(app),
            clock = { System.currentTimeMillis() },
            hasPermission = { hasCoarseLocation(app) },
        )
    }

    fun store(context: Context): ReadingStore = PrefsReadingStore(context.applicationContext)

    fun hasCoarseLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Reads only what the OS has already cached, taking the most recent fix across providers.
 * Never calls requestLocationUpdates, so it never powers on location hardware.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override fun lastKnown(): Coordinates? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val newest = providers
            .mapNotNull { p ->
                try {
                    manager.getLastKnownLocation(p)
                } catch (e: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null // provider not present on this device
                }
            }
            .maxByOrNull { it.time }
        return newest?.let { Coordinates(it.latitude, it.longitude) }
    }
}

class PrefsReadingStore(context: Context) : ReadingStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aqi", Context.MODE_PRIVATE)

    override fun saveReading(aqi: Int, observedAt: Long, station: String?) {
        val editor = prefs.edit().putInt(KEY_AQI, aqi).putLong(KEY_OBSERVED_AT, observedAt)
        if (station == null) editor.remove(KEY_STATION) else editor.putString(KEY_STATION, station)
        editor.apply()
    }

    override fun saveCoordinates(c: Coordinates) {
        prefs.edit()
            .putFloat(KEY_LAT, c.lat.toFloat())
            .putFloat(KEY_LON, c.lon.toFloat())
            .apply()
    }

    override fun reading(): CachedReading? {
        if (!prefs.contains(KEY_AQI)) return null
        return CachedReading(
            prefs.getInt(KEY_AQI, 0),
            prefs.getLong(KEY_OBSERVED_AT, 0L),
            prefs.getString(KEY_STATION, null),
        )
    }

    override fun coordinates(): Coordinates? {
        if (!prefs.contains(KEY_LAT)) return null
        return Coordinates(
            prefs.getFloat(KEY_LAT, 0f).toDouble(),
            prefs.getFloat(KEY_LON, 0f).toDouble(),
        )
    }

    private companion object {
        // Float is plenty: ~1m of precision at these magnitudes, far finer than AQI varies.
        const val KEY_AQI = "aqi"
        const val KEY_OBSERVED_AT = "observed_at"
        const val KEY_STATION = "station"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
    }
}
