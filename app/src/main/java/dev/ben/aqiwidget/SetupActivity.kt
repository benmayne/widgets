package dev.ben.aqiwidget

import android.Manifest
import android.app.Activity
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import dev.ben.aqiwidget.provider.Providers

/**
 * Exists for one structural reason: widgets cannot request runtime permissions, so something
 * with an Activity context must ask for ACCESS_COARSE_LOCATION.
 *
 * It also owns the app's only active location request — a one-shot getCurrentLocation that
 * fires solely on an explicit button press. That is what makes it battery-acceptable, and it
 * solves the cold-start case where a fresh install has no cached fix to read passively.
 */
class SetupActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.grant).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_CODE)
        }
        findViewById<Button>(R.id.fix).setOnClickListener { requestOneShotFix() }
        findViewById<Button>(R.id.refresh).setOnClickListener {
            AqiWidgetProvider.requestRefresh(this)
            status.postDelayed(::showStatus, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        showStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) {
            AqiWidgetProvider.requestRefresh(this)
            showStatus()
        }
    }

    /**
     * The only place this app ever actively powers on location hardware. User-initiated and
     * one-shot; the result is persisted as the fallback coordinate for passive reads later.
     */
    private fun requestOneShotFix() {
        if (!AppGraph.hasCoarseLocation(this)) {
            status.text = getString(R.string.needs_permission)
            return
        }
        val manager = getSystemService(LocationManager::class.java) ?: return
        status.text = getString(R.string.finding_location)
        manager.getCurrentLocation(
            LocationManager.NETWORK_PROVIDER,
            null,
            mainExecutor,
        ) { location ->
            if (location == null) {
                status.text = getString(R.string.no_fix)
                return@getCurrentLocation
            }
            AppGraph.store(this).saveCoordinates(
                Coordinates(location.latitude, location.longitude)
            )
            AqiWidgetProvider.requestRefresh(this)
            status.postDelayed(::showStatus, 1500)
        }
    }

    private fun showStatus() {
        val store = AppGraph.store(this)
        val reading = store.reading()
        val coordinates = store.coordinates()
        status.text = buildString {
            appendLine("Source: ${Providers.ACTIVE.name}")
            appendLine()
            if (reading == null) {
                appendLine("No reading yet.")
            } else {
                val category = AqiScale.categoryFor(reading.aqi)
                appendLine("AQI ${reading.aqi} — ${category.label}")
                // Rendered in the device's current local zone, resolved right now.
                appendLine("Measured ${TimeFormat.localTime(reading.observedAt)}")
                if (AqiScale.isStale(reading.observedAt, System.currentTimeMillis())) {
                    appendLine("(stale — over 3 hours old)")
                }
            }
            appendLine()
            appendLine(
                if (AppGraph.hasCoarseLocation(this@SetupActivity)) "Location permission granted"
                else "Location permission NOT granted"
            )
            appendLine(
                if (coordinates == null) "No location known yet"
                else "Near %.2f, %.2f".format(coordinates.lat, coordinates.lon)
            )
        }
    }

    private companion object {
        const val REQUEST_CODE = 1
    }
}
