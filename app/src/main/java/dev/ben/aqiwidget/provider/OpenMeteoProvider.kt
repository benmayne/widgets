package dev.ben.aqiwidget.provider

import dev.ben.aqiwidget.Http
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Open-Meteo Air Quality API. No API key, free for non-commercial use.
 *
 * Reports `us_aqi` natively on the US EPA scale, so no normalization is needed. It is a
 * CAMS model forecast interpolated to the coordinate rather than a ground-sensor reading.
 */
class OpenMeteoProvider(private val now: () -> Long = System::currentTimeMillis) : AqiProvider {

    override val name: String = "Open-Meteo"

    override fun fetch(lat: Double, lon: Double): Reading = parse(Http.getJson(urlFor(lat, lon)))

    /**
     * `timezone=GMT` rather than `auto` is deliberate: Open-Meteo returns `current.time` as a
     * local ISO string with no offset suffix, which under `auto` cannot be converted to epoch
     * millis without separately reading `utc_offset_seconds`. Pinning to GMT makes it parse
     * as UTC directly. This is a wire-format detail and never surfaces to the user.
     */
    internal fun urlFor(lat: Double, lon: Double): String =
        "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lon&current=us_aqi&timezone=GMT"

    @Throws(IOException::class)
    internal fun parse(root: JSONObject): Reading {
        val current = root.optJSONObject("current")
            ?: throw IOException("Open-Meteo response missing 'current'")
        if (current.isNull("us_aqi")) {
            throw IOException("Open-Meteo response has null or absent us_aqi")
        }
        val aqi = current.optInt("us_aqi", Int.MIN_VALUE)
        if (aqi == Int.MIN_VALUE) throw IOException("Open-Meteo us_aqi was not an integer")
        // Reading's factory clamps aqi to 0-500, so an out-of-range value is not rejected here.
        return Reading(aqi = aqi, observedAt = parseUtcMillis(current.optString("time", "")))
    }

    /**
     * Falls back to the fetch time rather than throwing when the timestamp is absent or
     * unparseable. The AqiProvider KDoc contract is "the measurement timestamp when it can be
     * resolved unambiguously, otherwise the fetch time" — a bad timestamp is not a reason to
     * discard an otherwise-good `us_aqi` and fall back to a stale cached reading upstream in
     * AqiRepository. This mirrors the clamp decision above: a degraded-but-present value beats
     * rejecting the whole reading.
     */
    private fun parseUtcMillis(iso: String): Long {
        if (iso.isEmpty()) return now()
        return try {
            LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeParseException) {
            now()
        }
    }
}
