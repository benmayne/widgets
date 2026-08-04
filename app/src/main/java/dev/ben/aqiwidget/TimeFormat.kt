package dev.ben.aqiwidget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders stored epoch-millis timestamps for display.
 *
 * The API is queried with timezone=GMT purely so its timestamps parse unambiguously; that is
 * a wire-format detail and must never reach the user. Everything here resolves the zone at
 * render time, so crossing a timezone changes only the display, never a stored value.
 */
object TimeFormat {

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    fun localTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(FORMATTER)
}
