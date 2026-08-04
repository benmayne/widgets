package dev.ben.aqiwidget

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTPS fetch. Framework only — no HTTP library dependency. */
object Http {

    // Connect + read worst case is 10s, matching the goAsync budget documented in
    // AqiWidgetProvider and the design doc's "Threading" section. Shorter also means less
    // radio hold time, serving the battery priority; a failed fetch is benign since the
    // cache is retained and the next hourly tick retries.
    private const val TIMEOUT_MS = 5_000

    @Throws(IOException::class)
    fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("Malformed JSON from $url", e)
        } finally {
            connection.disconnect()
        }
    }
}
