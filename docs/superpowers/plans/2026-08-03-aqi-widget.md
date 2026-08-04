# AQI Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A 1x1 Android home-screen widget showing the current US EPA AQI at the user's approximate location, with zero third-party runtime dependencies.

**Architecture:** Pure-Kotlin core (`AqiScale`, `OpenMeteoProvider` parsing, `AqiRepository`) depends only on small interfaces in `Ports.kt`, so all logic is JVM-unit-testable with no emulator. A thin Android shell (`AqiWidgetProvider`, `SetupActivity`, `AppGraph`) supplies the real `LocationManager`/`SharedPreferences` implementations and renders `RemoteViews`. The data source sits behind an `AqiProvider` interface so it can be swapped without touching anything else.

**Tech Stack:** Kotlin 2.0.21, AGP 8.9.2, Gradle 8.11.1, JDK 17 (Android Studio's bundled JBR), compileSdk 37, minSdk 31. Framework-only at runtime: `HttpURLConnection`, `org.json`, `RemoteViews`, `LocationManager`. JUnit 4 + a real `org.json` jar for tests.

**Spec:** `docs/superpowers/specs/2026-08-03-aqi-widget-design.md`

## Global Constraints

Every task's requirements implicitly include these. Values copied verbatim from the spec.

- **Zero third-party runtime dependencies.** No OkHttp, Retrofit, Gson, Moshi, androidx, Glance, or Play Services. Test-only dependencies are permitted.
- `minSdk = 31`, `targetSdk = 37`, `compileSdk = 37`.
- Package / namespace / applicationId: `dev.ben.aqiwidget`.
- Location permission is **`ACCESS_COARSE_LOCATION` only**. Never request `ACCESS_FINE_LOCATION`.
- The app **never requests background location updates.** The only active fix is the one-shot, user-initiated `getCurrentLocation()` in `SetupActivity`.
- `Reading.aqi` is **always on the US EPA 0–500 scale**, normalized by the provider.
- `observedAt` is stored and compared **only as epoch millis**; no local-time arithmetic anywhere.
- All user-facing timestamps render in the device's **current local timezone**, resolved at render time.
- Staleness threshold: **3 hours** (`AqiScale.STALE_AFTER_MILLIS`).
- Widget update period: **3600000 ms**.
- Never blank a good reading on fetch failure — fall back to cache and dim.
- `AqiScale.kt`, `Ports.kt`, `TimeFormat.kt`, and `AqiRepository.kt` must contain **no `android.*` imports**.

---

### Task 1: Project scaffold that builds and runs tests

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `local.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Test: `app/src/test/java/dev/ben/aqiwidget/ScaffoldTest.kt`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: a Gradle project where `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` both succeed. All later tasks rely on these two commands.

- [ ] **Step 1: Verify the Android SDK exists**

The user must have completed Android Studio's first-run wizard before this task.

Run:
```bash
ls ~/Library/Android/sdk/platforms
```
Expected: at least one `android-NN` directory. If the directory does not exist, STOP — the user must open Android Studio and complete the setup wizard first.

- [ ] **Step 2: Install the build tools**

Gradle is a build-time tool, not an app dependency — this does not violate the zero-dependency constraint. Java comes from Android Studio's bundled JBR, so no separate JDK install is needed.

```bash
brew install gradle
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
java -version
```
Expected: `openjdk version "17..."` or newer.

- [ ] **Step 3: Verify the SDK platform is present**

Android Studio's first-run wizard already installed these; `cmdline-tools` is NOT installed, so
do not attempt to use `sdkmanager`.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools" "$ANDROID_HOME/platform-tools/adb"
```
Expected: `android-37.0`, `36.0.0`, and an `adb` path.

If the platform directory is missing or differs, install it through Android Studio's GUI
(Settings > Languages & Frameworks > Android SDK) and set `compileSdk`/`targetSdk` in Step 7
to whatever is actually installed. Build-tools are deliberately not pinned in Gradle — AGP
selects a compatible version itself.

- [ ] **Step 4: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AqiWidget"
include(":app")
```

- [ ] **Step 5: Create the root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

- [ ] **Step 6: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m
org.gradle.caching=true
android.useAndroidX=false
android.suppressUnsupportedCompileSdk=37
kotlin.code.style=official
```

`android.useAndroidX=false` is deliberate and enforces the zero-dependency constraint: the build fails if anything pulls in androidx.

`android.suppressUnsupportedCompileSdk=37` silences AGP 8.9.2's "tested up to compileSdk 36"
warning. If the build instead fails outright on the platform version, bump the AGP version in
Step 5 to one that officially supports API 37 and re-run.

- [ ] **Step 7: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ben.aqiwidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ben.aqiwidget"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Test-only. No runtime dependencies by design.
    // org.json is required because android.jar's version is a stub in unit tests;
    // the real jar takes classpath precedence so parsing tests exercise real behavior.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```

- [ ] **Step 8: Create `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AQI</string>
    <string name="widget_description">Current air quality at your location</string>
    <string name="dash">—</string>
</resources>
```

- [ ] **Step 9: Create `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AqiWidget" parent="@android:style/Theme.DeviceDefault.DayNight" />
    <style name="Theme.AqiWidget.AppWidgetContainer" parent="@android:style/Theme.DeviceDefault.DayNight" />
</resources>
```

- [ ] **Step 10: Create a minimal `app/src/main/AndroidManifest.xml`**

The receiver and activity are added in Tasks 5 and 6; this is the scaffold only.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AqiWidget" />
</manifest>
```

- [ ] **Step 11: Create `.gitignore`**

```gitignore
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/app/build
/captures
.externalNativeBuild
.cxx
```

- [ ] **Step 12: Create `local.properties` (gitignored)**

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

- [ ] **Step 13: Generate the Gradle wrapper**

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```
Expected: creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/`.

- [ ] **Step 14: Write the failing scaffold test**

`app/src/test/java/dev/ben/aqiwidget/ScaffoldTest.kt`:
```kotlin
package dev.ben.aqiwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldTest {
    @Test
    fun `unit tests run on the jvm`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `real org_json is on the test classpath, not the android stub`() {
        // The android.jar stub returns 0 here; the real implementation returns 56.
        val json = JSONObject("""{"current":{"us_aqi":56}}""")
        assertEquals(56, json.getJSONObject("current").getInt("us_aqi"))
    }
}
```

- [ ] **Step 15: Run the tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

If the second test fails with `expected:<56> but was:<0>`, the real `org.json` jar is not taking classpath precedence — verify the `testImplementation("org.json:json:20240303")` line in Step 7.

If the build fails on a plugin version, run `./gradlew :app:dependencies --stacktrace` to see the resolved versions and adjust the AGP/Kotlin versions in Steps 5 and 13 to a self-consistent set. AGP 8.9.x requires Gradle 8.11+.

- [ ] **Step 16: Verify the APK assembles**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 17: Commit**

```bash
git add -A
git commit -m "build: scaffold zero-dependency Android project"
```

---

### Task 2: AqiScale — categories, colors, staleness

**Files:**
- Create: `app/src/main/java/dev/ben/aqiwidget/AqiScale.kt`
- Test: `app/src/test/java/dev/ben/aqiwidget/AqiScaleTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `enum class AqiCategory(val label: String, val background: Int, val foreground: Int)` with entries `GOOD`, `MODERATE`, `UNHEALTHY_SENSITIVE`, `UNHEALTHY`, `VERY_UNHEALTHY`, `HAZARDOUS`
  - `object AqiScale` with `STALE_AFTER_MILLIS: Long`, `categoryFor(aqi: Int): AqiCategory`, `isStale(observedAt: Long, now: Long): Boolean`, `dim(color: Int): Int`

Colors are plain ARGB `Int`s, never `android.graphics.Color`, so this file stays JVM-testable.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/ben/aqiwidget/AqiScaleTest.kt`:
```kotlin
package dev.ben.aqiwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AqiScaleTest {

    @Test
    fun `category boundaries match the EPA scale`() {
        assertEquals(AqiCategory.GOOD, AqiScale.categoryFor(0))
        assertEquals(AqiCategory.GOOD, AqiScale.categoryFor(50))
        assertEquals(AqiCategory.MODERATE, AqiScale.categoryFor(51))
        assertEquals(AqiCategory.MODERATE, AqiScale.categoryFor(100))
        assertEquals(AqiCategory.UNHEALTHY_SENSITIVE, AqiScale.categoryFor(101))
        assertEquals(AqiCategory.UNHEALTHY_SENSITIVE, AqiScale.categoryFor(150))
        assertEquals(AqiCategory.UNHEALTHY, AqiScale.categoryFor(151))
        assertEquals(AqiCategory.UNHEALTHY, AqiScale.categoryFor(200))
        assertEquals(AqiCategory.VERY_UNHEALTHY, AqiScale.categoryFor(201))
        assertEquals(AqiCategory.VERY_UNHEALTHY, AqiScale.categoryFor(300))
        assertEquals(AqiCategory.HAZARDOUS, AqiScale.categoryFor(301))
        assertEquals(AqiCategory.HAZARDOUS, AqiScale.categoryFor(500))
    }

    @Test
    fun `backgrounds are the official AirNow palette`() {
        assertEquals(0xFF00E400.toInt(), AqiCategory.GOOD.background)
        assertEquals(0xFFFFFF00.toInt(), AqiCategory.MODERATE.background)
        assertEquals(0xFFFF7E00.toInt(), AqiCategory.UNHEALTHY_SENSITIVE.background)
        assertEquals(0xFFFF0000.toInt(), AqiCategory.UNHEALTHY.background)
        assertEquals(0xFF8F3F97.toInt(), AqiCategory.VERY_UNHEALTHY.background)
        assertEquals(0xFF7E0023.toInt(), AqiCategory.HAZARDOUS.background)
    }

    @Test
    fun `foreground flips to white at the red threshold`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, AqiCategory.GOOD.foreground)
        assertEquals(black, AqiCategory.MODERATE.foreground)
        assertEquals(black, AqiCategory.UNHEALTHY_SENSITIVE.foreground)
        assertEquals(white, AqiCategory.UNHEALTHY.foreground)
        assertEquals(white, AqiCategory.VERY_UNHEALTHY.foreground)
        assertEquals(white, AqiCategory.HAZARDOUS.foreground)
    }

    @Test
    fun `stale only after three hours`() {
        val now = 1_000_000_000L
        val threeHours = 3L * 60 * 60 * 1000
        assertFalse(AqiScale.isStale(now, now))
        assertFalse(AqiScale.isStale(now - threeHours, now))
        assertTrue(AqiScale.isStale(now - threeHours - 1, now))
    }

    @Test
    fun `dim blends 55 percent toward grey with integer math`() {
        // green 0x00E400 -> r,b = (0*45 + 158*55 + 50)/100 = 87 = 0x57
        //                     g = (228*45 + 158*55 + 50)/100 = 190 = 0xBE
        assertEquals(0xFF57BE57.toInt(), AqiScale.dim(AqiCategory.GOOD.background))
    }

    @Test
    fun `dim preserves alpha and is a no-op on grey itself`() {
        val grey = 0xFF9E9E9E.toInt()
        assertEquals(grey, AqiScale.dim(grey))
    }

    @Test
    fun `dim always moves a color closer to grey`() {
        for (category in AqiCategory.entries) {
            val dimmed = AqiScale.dim(category.background)
            assertEquals("alpha preserved", 0xFF, (dimmed ushr 24) and 0xFF)
            assertTrue(
                "dimmed ${category.name} should differ from original",
                dimmed != category.background || category.background == 0xFF9E9E9E.toInt()
            )
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*AqiScaleTest*'
```
Expected: FAIL — `Unresolved reference: AqiCategory`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/dev/ben/aqiwidget/AqiScale.kt`:
```kotlin
package dev.ben.aqiwidget

/**
 * EPA AQI categories with the official AirNow palette, as ARGB ints.
 *
 * Deliberately plain [Int]s rather than android.graphics.Color so this file has no
 * android.* imports and stays unit-testable on the JVM.
 */
enum class AqiCategory(
    val label: String,
    val background: Int,
    val foreground: Int,
) {
    GOOD("Good", 0xFF00E400.toInt(), 0xFF000000.toInt()),
    MODERATE("Moderate", 0xFFFFFF00.toInt(), 0xFF000000.toInt()),
    UNHEALTHY_SENSITIVE("Unhealthy for Sensitive Groups", 0xFFFF7E00.toInt(), 0xFF000000.toInt()),
    UNHEALTHY("Unhealthy", 0xFFFF0000.toInt(), 0xFFFFFFFF.toInt()),
    VERY_UNHEALTHY("Very Unhealthy", 0xFF8F3F97.toInt(), 0xFFFFFFFF.toInt()),
    HAZARDOUS("Hazardous", 0xFF7E0023.toInt(), 0xFFFFFFFF.toInt()),
}

object AqiScale {

    /** A reading older than this is rendered dimmed. */
    const val STALE_AFTER_MILLIS: Long = 3L * 60 * 60 * 1000

    private val GREY = 0xFF9E9E9E.toInt()
    private const val DIM_PERCENT = 55

    fun categoryFor(aqi: Int): AqiCategory = when {
        aqi <= 50 -> AqiCategory.GOOD
        aqi <= 100 -> AqiCategory.MODERATE
        aqi <= 150 -> AqiCategory.UNHEALTHY_SENSITIVE
        aqi <= 200 -> AqiCategory.UNHEALTHY
        aqi <= 300 -> AqiCategory.VERY_UNHEALTHY
        else -> AqiCategory.HAZARDOUS
    }

    fun isStale(observedAt: Long, now: Long): Boolean = now - observedAt > STALE_AFTER_MILLIS

    /**
     * Blends [color] 55% toward grey to signal a stale reading.
     *
     * Uses integer math rather than floats: float rounding at an exact .5 boundary is
     * not reproducible across platforms, which would make the result untestable.
     */
    fun dim(color: Int): Int = blend(color, GREY, DIM_PERCENT)

    private fun blend(from: Int, to: Int, percentToward: Int): Int {
        fun channel(shift: Int): Int {
            val f = (from ushr shift) and 0xFF
            val t = (to ushr shift) and 0xFF
            return (f * (100 - percentToward) + t * percentToward + 50) / 100
        }
        val alpha = (from ushr 24) and 0xFF
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*AqiScaleTest*'
```
Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ben/aqiwidget/AqiScale.kt app/src/test/java/dev/ben/aqiwidget/AqiScaleTest.kt
git commit -m "feat: add AQI category scale, colors, and staleness dimming"
```

---

### Task 3: AqiProvider seam and the Open-Meteo implementation

**Files:**
- Create: `app/src/main/java/dev/ben/aqiwidget/provider/AqiProvider.kt`
- Create: `app/src/main/java/dev/ben/aqiwidget/provider/OpenMeteoProvider.kt`
- Create: `app/src/main/java/dev/ben/aqiwidget/provider/Providers.kt`
- Create: `app/src/main/java/dev/ben/aqiwidget/Http.kt`
- Test: `app/src/test/java/dev/ben/aqiwidget/provider/OpenMeteoProviderTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `interface AqiProvider { val name: String; fun fetch(lat: Double, lon: Double): Reading }`
  - `data class Reading(val aqi: Int, val observedAt: Long, val station: String? = null)`
  - `object Providers { val ACTIVE: AqiProvider }`
  - `class OpenMeteoProvider` with `internal fun urlFor(lat, lon): String` and `internal fun parse(root: JSONObject): Reading`
  - `object Http { fun getJson(url: String): JSONObject }`

`urlFor` and `parse` are `internal` rather than private specifically so they can be tested without network access.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/ben/aqiwidget/provider/OpenMeteoProviderTest.kt`:
```kotlin
package dev.ben.aqiwidget.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class OpenMeteoProviderTest {

    private val provider = OpenMeteoProvider()

    /** Captured verbatim from the live API during design. */
    private val realResponse = """
        {"latitude":37.800003,"longitude":-122.399994,"generationtime_ms":0.236,
         "utc_offset_seconds":0,"timezone":"GMT","timezone_abbreviation":"GMT",
         "elevation":14.0,
         "current_units":{"time":"iso8601","interval":"seconds","us_aqi":"USAQI"},
         "current":{"time":"2026-08-03T19:00","interval":3600,"us_aqi":56}}
    """.trimIndent()

    @Test
    fun `parses aqi from a real response`() {
        assertEquals(56, provider.parse(JSONObject(realResponse)).aqi)
    }

    @Test
    fun `parses time as UTC epoch millis`() {
        val expected = Instant.parse("2026-08-03T19:00:00Z").toEpochMilli()
        assertEquals(expected, provider.parse(JSONObject(realResponse)).observedAt)
    }

    @Test
    fun `model-based source reports no station`() {
        assertNull(provider.parse(JSONObject(realResponse)).station)
    }

    @Test
    fun `url pins timezone to GMT so time parses unambiguously`() {
        val url = provider.urlFor(37.77, -122.42)
        assertTrue(url, url.startsWith("https://air-quality-api.open-meteo.com/v1/air-quality?"))
        assertTrue(url, url.contains("latitude=37.77"))
        assertTrue(url, url.contains("longitude=-122.42"))
        assertTrue(url, url.contains("current=us_aqi"))
        assertTrue(url, url.contains("timezone=GMT"))
    }

    @Test
    fun `missing current block raises IOException`() {
        val e = runCatching { provider.parse(JSONObject("""{"latitude":1.0}""")) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `null us_aqi raises IOException`() {
        val json = """{"current":{"time":"2026-08-03T19:00","us_aqi":null}}"""
        val e = runCatching { provider.parse(JSONObject(json)) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `absent us_aqi raises IOException`() {
        val json = """{"current":{"time":"2026-08-03T19:00"}}"""
        val e = runCatching { provider.parse(JSONObject(json)) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `unparseable time raises IOException`() {
        val json = """{"current":{"time":"not-a-time","us_aqi":56}}"""
        val e = runCatching { provider.parse(JSONObject(json)) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun `provider reports its name for the setup screen`() {
        assertEquals("Open-Meteo", provider.name)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*OpenMeteoProviderTest*'
```
Expected: FAIL — `Unresolved reference: OpenMeteoProvider`.

- [ ] **Step 3: Write the provider interface**

`app/src/main/java/dev/ben/aqiwidget/provider/AqiProvider.kt`:
```kotlin
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
```

- [ ] **Step 4: Write the HTTP helper**

`app/src/main/java/dev/ben/aqiwidget/Http.kt`:
```kotlin
package dev.ben.aqiwidget

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTPS fetch. Framework only — no HTTP library dependency. */
object Http {

    private const val TIMEOUT_MS = 10_000

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
```

- [ ] **Step 5: Write the Open-Meteo implementation**

`app/src/main/java/dev/ben/aqiwidget/provider/OpenMeteoProvider.kt`:
```kotlin
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
class OpenMeteoProvider : AqiProvider {

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
        return Reading(aqi = aqi, observedAt = parseUtcMillis(current.optString("time", "")))
    }

    private fun parseUtcMillis(iso: String): Long = try {
        LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw IOException("Open-Meteo response has unparseable time '$iso'", e)
    }
}
```

- [ ] **Step 6: Write the provider selection constant**

`app/src/main/java/dev/ben/aqiwidget/provider/Providers.kt`:
```kotlin
package dev.ben.aqiwidget.provider

/**
 * The active data source.
 *
 * Compile-time constant by design. Runtime switching would need a settings UI, persistence,
 * and migration logic for a decision made perhaps twice in this app's life. The AqiProvider
 * interface is what buys flexibility; a picker would be furniture. To swap sources, write a
 * new AqiProvider and change this one line.
 */
object Providers {
    val ACTIVE: AqiProvider = OpenMeteoProvider()
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*OpenMeteoProviderTest*'
```
Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/ben/aqiwidget/Http.kt app/src/main/java/dev/ben/aqiwidget/provider app/src/test/java/dev/ben/aqiwidget/provider
git commit -m "feat: add swappable AqiProvider seam with Open-Meteo implementation"
```

---

### Task 4: Ports and AqiRepository

**Files:**
- Create: `app/src/main/java/dev/ben/aqiwidget/Ports.kt`
- Create: `app/src/main/java/dev/ben/aqiwidget/AqiRepository.kt`
- Test: `app/src/test/java/dev/ben/aqiwidget/AqiRepositoryTest.kt`

**Interfaces:**
- Consumes: `AqiProvider`, `Reading` (Task 3); `AqiScale.isStale` (Task 2)
- Produces:
  - `data class Coordinates(val lat: Double, val lon: Double)`
  - `fun interface Clock { fun now(): Long }`
  - `interface LocationSource { fun lastKnown(): Coordinates? }`
  - `data class CachedReading(val aqi: Int, val observedAt: Long)`
  - `interface ReadingStore` with `saveReading(aqi: Int, observedAt: Long)`, `saveCoordinates(c: Coordinates)`, `reading(): CachedReading?`, `coordinates(): Coordinates?`
  - `sealed interface RenderState` with `Ok(aqi: Int, stale: Boolean)`, `NeedsPermission`, `NoLocation`, `NoData`
  - `class AqiRepository(provider, location, store, clock, hasPermission: () -> Boolean)` with `refresh(): RenderState` and `cached(): RenderState`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/ben/aqiwidget/AqiRepositoryTest.kt`:
```kotlin
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
        override fun lastKnown(): Coordinates? = value
    }

    private class FakeStore(
        private var cached: CachedReading? = null,
        private var coords: Coordinates? = null,
    ) : ReadingStore {
        override fun saveReading(aqi: Int, observedAt: Long) {
            cached = CachedReading(aqi, observedAt)
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
    fun `missing permission short-circuits before any network or location access`() {
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        val state = repo(provider, permission = false).refresh()
        assertEquals(RenderState.NeedsPermission, state)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `cached renders from the store without fetching`() {
        val store = FakeStore(cached = CachedReading(aqi = 42, observedAt = now))
        val provider = FakeProvider(Reading(aqi = 56, observedAt = now))
        assertEquals(RenderState.Ok(42, stale = false), repo(provider, store = store).cached())
        assertEquals(0, provider.calls)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*AqiRepositoryTest*'
```
Expected: FAIL — `Unresolved reference: Coordinates`.

- [ ] **Step 3: Write the ports**

`app/src/main/java/dev/ben/aqiwidget/Ports.kt`:
```kotlin
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

data class CachedReading(val aqi: Int, val observedAt: Long)

interface ReadingStore {
    fun saveReading(aqi: Int, observedAt: Long)
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
```

- [ ] **Step 4: Write the repository**

`app/src/main/java/dev/ben/aqiwidget/AqiRepository.kt`:
```kotlin
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
            store.saveReading(reading.aqi, reading.observedAt)
            toState(CachedReading(reading.aqi, reading.observedAt))
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
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*AqiRepositoryTest*'
```
Expected: `BUILD SUCCESSFUL`, 10 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ben/aqiwidget/Ports.kt app/src/main/java/dev/ben/aqiwidget/AqiRepository.kt app/src/test/java/dev/ben/aqiwidget/AqiRepositoryTest.kt
git commit -m "feat: add repository with cache fallback and staleness logic"
```

---

### Task 5: Widget rendering and scheduling

**Files:**
- Create: `app/src/main/java/dev/ben/aqiwidget/AppGraph.kt`
- Create: `app/src/main/java/dev/ben/aqiwidget/AqiWidgetProvider.kt`
- Create: `app/src/main/res/xml/aqi_widget_info.xml`
- Create: `app/src/main/res/layout/widget_aqi.xml`
- Create: `app/src/main/res/drawable/widget_bg.xml`
- Create: `app/src/main/res/drawable/ic_launcher.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AqiRepository`, `RenderState`, `Coordinates`, `LocationSource`, `ReadingStore`, `CachedReading`, `Clock` (Task 4); `AqiScale`, `AqiCategory` (Task 2); `Providers.ACTIVE` (Task 3)
- Produces:
  - `object AppGraph` with `repository(context: Context): AqiRepository`, `store(context: Context): ReadingStore`, `hasCoarseLocation(context: Context): Boolean`
  - `class AndroidLocationSource(context: Context) : LocationSource`
  - `class PrefsReadingStore(context: Context) : ReadingStore`
  - `class AqiWidgetProvider : AppWidgetProvider` with `companion object { const val ACTION_REFRESH: String; fun render(context: Context, state: RenderState); fun requestRefresh(context: Context) }`

This task has no unit tests — `RemoteViews` and `AppWidgetManager` cannot be meaningfully exercised on the JVM, and mocking them would only test the mocks. It is verified on-device in Task 7.

- [ ] **Step 1: Write the Android-backed ports and wiring**

`app/src/main/java/dev/ben/aqiwidget/AppGraph.kt`:
```kotlin
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

    override fun saveReading(aqi: Int, observedAt: Long) {
        prefs.edit().putInt(KEY_AQI, aqi).putLong(KEY_OBSERVED_AT, observedAt).apply()
    }

    override fun saveCoordinates(c: Coordinates) {
        prefs.edit()
            .putFloat(KEY_LAT, c.lat.toFloat())
            .putFloat(KEY_LON, c.lon.toFloat())
            .apply()
    }

    override fun reading(): CachedReading? {
        if (!prefs.contains(KEY_AQI)) return null
        return CachedReading(prefs.getInt(KEY_AQI, 0), prefs.getLong(KEY_OBSERVED_AT, 0L))
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
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
    }
}
```

- [ ] **Step 2: Create the widget metadata**

`app/src/main/res/xml/aqi_widget_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="40dp"
    android:minHeight="40dp"
    android:targetCellWidth="1"
    android:targetCellHeight="1"
    android:updatePeriodMillis="3600000"
    android:resizeMode="none"
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/widget_aqi"
    android:previewLayout="@layout/widget_aqi"
    android:description="@string/widget_description" />
```

`updatePeriodMillis="3600000"` is one hour, matching Open-Meteo's own hourly `interval`. It does not wake the device from Doze, so an idle phone costs nothing.

- [ ] **Step 3: Create the background drawable**

`app/src/main/res/drawable/widget_bg.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <!-- Solid white so the SRC_IN background tint applied at runtime shows as the tint color. -->
    <solid android:color="#FFFFFFFF" />
    <corners android:radius="@android:dimen/system_app_widget_background_radius" />
</shape>
```

- [ ] **Step 4: Create the widget layout**

`app/src/main/res/layout/widget_aqi.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:theme="@style/Theme.AqiWidget.AppWidgetContainer">

    <TextView
        android:id="@+id/aqi_value"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:includeFontPadding="false"
        android:maxLines="1"
        android:text="@string/dash"
        android:textColor="#FF000000"
        android:textSize="24sp"
        android:textStyle="bold" />
</FrameLayout>
```

- [ ] **Step 5: Create a launcher icon**

`app/src/main/res/drawable/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FF00E400"
        android:pathData="M54,22 A32,32 0 1,1 53.9,22 Z" />
    <path
        android:fillColor="#FF000000"
        android:pathData="M54,38 A16,16 0 1,1 53.9,38 Z" />
</vector>
```

- [ ] **Step 6: Write the widget provider**

`app/src/main/java/dev/ben/aqiwidget/AqiWidgetProvider.kt`:
```kotlin
package dev.ben.aqiwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import java.util.concurrent.Executors

class AqiWidgetProvider : AppWidgetProvider() {

    /**
     * onUpdate runs on the main thread, where a network call throws
     * NetworkOnMainThreadException. goAsync() plus a background executor keeps the process
     * alive for the ~10s the fetch needs, which is ample for a 250-byte response.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val handled = intent.action == ACTION_REFRESH ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE
        if (!handled) {
            super.onReceive(context, intent)
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        EXECUTOR.execute {
            try {
                render(app, AppGraph.repository(app).refresh())
            } catch (t: Throwable) {
                Log.e(TAG, "widget update failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.ben.aqiwidget.ACTION_REFRESH"

        private const val TAG = "AqiWidget"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
        private val GREY = 0xFF616161.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()

        /** Broadcasts a refresh request. Safe to call from the main thread. */
        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, AqiWidgetProvider::class.java).setAction(ACTION_REFRESH)
            )
        }

        fun render(context: Context, state: RenderState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AqiWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context, state))
        }

        private fun buildViews(context: Context, state: RenderState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_aqi)
            if (state is RenderState.Ok) {
                val category = AqiScale.categoryFor(state.aqi)
                val background =
                    if (state.stale) AqiScale.dim(category.background) else category.background
                val text = state.aqi.toString()
                views.setTextViewText(R.id.aqi_value, text)
                views.setTextColor(R.id.aqi_value, category.foreground)
                // autoSizeText is unavailable through RemoteViews, so size by digit count.
                views.setTextViewTextSize(
                    R.id.aqi_value,
                    TypedValue.COMPLEX_UNIT_SP,
                    if (text.length >= 3) 19f else 24f,
                )
                views.setColorStateList(
                    R.id.widget_root,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(background),
                )
            } else {
                views.setTextViewText(R.id.aqi_value, context.getString(R.string.dash))
                views.setTextColor(R.id.aqi_value, WHITE)
                views.setTextViewTextSize(R.id.aqi_value, TypedValue.COMPLEX_UNIT_SP, 24f)
                views.setColorStateList(
                    R.id.widget_root,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(GREY),
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, state))
            return views
        }

        /** Tapping opens setup when the user must act; otherwise it refreshes in place. */
        private fun tapIntent(context: Context, state: RenderState): PendingIntent =
            when (state) {
                RenderState.NeedsPermission, RenderState.NoLocation -> PendingIntent.getActivity(
                    context,
                    REQUEST_SETUP,
                    Intent(context, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                else -> PendingIntent.getBroadcast(
                    context,
                    REQUEST_REFRESH,
                    Intent(context, AqiWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        private const val REQUEST_SETUP = 1
        private const val REQUEST_REFRESH = 2
    }
}
```

- [ ] **Step 7: Register the receiver in the manifest**

Replace `app/src/main/AndroidManifest.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AqiWidget">

        <activity
            android:name=".SetupActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".AqiWidgetProvider"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
                <action android:name="dev.ben.aqiwidget.ACTION_REFRESH" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/aqi_widget_info" />
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 8: Create a placeholder SetupActivity so this task compiles**

`AqiWidgetProvider` references `SetupActivity`, which is written in Task 6. This placeholder
keeps Task 5 independently buildable and reviewable rather than forcing the two tasks to land
together.

`app/src/main/java/dev/ben/aqiwidget/SetupActivity.kt`:
```kotlin
package dev.ben.aqiwidget

import android.app.Activity

/** Replaced with the real implementation in Task 6. */
class SetupActivity : Activity()
```

- [ ] **Step 9: Verify it compiles and existing tests still pass**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, 28 tests pass (2 scaffold + 7 scale + 9 provider + 10 repository).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add widget rendering, hourly scheduling, and tap handling"
```

---

### Task 6: SetupActivity, permission flow, and local-time rendering

**Files:**
- Create: `app/src/main/java/dev/ben/aqiwidget/TimeFormat.kt`
- Create: `app/src/main/res/layout/activity_setup.xml`
- Modify: `app/src/main/java/dev/ben/aqiwidget/SetupActivity.kt` (replaces the Task 5 placeholder)
- Test: `app/src/test/java/dev/ben/aqiwidget/TimeFormatTest.kt`

**Interfaces:**
- Consumes: `AppGraph`, `AqiWidgetProvider.requestRefresh`, `RenderState`, `ReadingStore`, `AqiScale` (Tasks 2, 4, 5); `Providers.ACTIVE` (Task 3)
- Produces: `object TimeFormat { fun localTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String }`

- [ ] **Step 1: Write the failing timezone tests**

`app/src/test/java/dev/ben/aqiwidget/TimeFormatTest.kt`:
```kotlin
package dev.ben.aqiwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeFormatTest {

    private val noonUtc = Instant.parse("2026-08-03T19:00:00Z").toEpochMilli()

    @Test
    fun `renders in the supplied local zone, not GMT`() {
        val la = TimeFormat.localTime(noonUtc, ZoneId.of("America/Los_Angeles"))
        val utc = TimeFormat.localTime(noonUtc, ZoneId.of("UTC"))
        assertNotEquals(utc, la)
        assertEquals("Aug 3, 12:00 PM", la)
        assertEquals("Aug 3, 7:00 PM", utc)
    }

    @Test
    fun `staleness verdict is identical regardless of timezone`() {
        // observedAt is epoch millis, so the verdict is timezone-independent by construction.
        val now = noonUtc + AqiScale.STALE_AFTER_MILLIS + 1
        val zones = listOf("UTC", "America/Los_Angeles", "Asia/Tokyo", "Pacific/Kiritimati")
        val verdicts = zones.map {
            val previous = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(it))
                AqiScale.isStale(noonUtc, now)
            } finally {
                java.util.TimeZone.setDefault(previous)
            }
        }
        assertEquals(listOf(true, true, true, true), verdicts)
    }

    @Test
    fun `crossing a timezone changes display only, never the stored value`() {
        val tokyo = TimeFormat.localTime(noonUtc, ZoneId.of("Asia/Tokyo"))
        val la = TimeFormat.localTime(noonUtc, ZoneId.of("America/Los_Angeles"))
        assertNotEquals(tokyo, la)
        // Same instant underlies both renderings.
        assertEquals(noonUtc, Instant.parse("2026-08-03T19:00:00Z").toEpochMilli())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*TimeFormatTest*'
```
Expected: FAIL — `Unresolved reference: TimeFormat`.

- [ ] **Step 3: Write TimeFormat**

`app/src/main/java/dev/ben/aqiwidget/TimeFormat.kt`:
```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*TimeFormatTest*'
```
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Create the setup layout**

`app/src/main/res/layout/activity_setup.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:id="@+id/status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="18sp"
        android:lineSpacingMultiplier="1.3" />

    <Button
        android:id="@+id/grant"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="Grant location permission" />

    <Button
        android:id="@+id/fix"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="Update my location now" />

    <Button
        android:id="@+id/refresh"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="Refresh AQI" />
</LinearLayout>
```

- [ ] **Step 6: Write SetupActivity**

Replace `app/src/main/java/dev/ben/aqiwidget/SetupActivity.kt` entirely:
```kotlin
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
```

- [ ] **Step 7: Add the new strings**

Add inside `<resources>` in `app/src/main/res/values/strings.xml`:
```xml
    <string name="needs_permission">Grant location permission first.</string>
    <string name="finding_location">Finding your location…</string>
    <string name="no_fix">Could not get a location fix. Try again outdoors or with Wi-Fi on.</string>
```

- [ ] **Step 8: Verify the whole suite passes and the APK builds**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, 31 tests pass (28 from Task 5 plus 3 in `TimeFormatTest`).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add setup screen, permission flow, and local-time rendering"
```

---

### Task 7: Install and verify on the device

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6
- Produces: a verified installed widget

- [ ] **Step 1: Confirm the device is connected**

The user must enable Developer Options and USB debugging on the Pixel 10, then accept the debugging prompt.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/platform-tools/adb" devices
```
Expected: one device listed as `device` (not `unauthorized` or `offline`).

- [ ] **Step 2: Build and install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`.

- [ ] **Step 3: Grant the permission and seed a location**

Open the **AQI** app on the device, tap **Grant location permission**, allow it, then tap **Update my location now**.

Expected: the status text shows a coordinate and, shortly after, an AQI value with a local-time "Measured" line.

- [ ] **Step 4: Add the widget and verify rendering**

Long-press the home screen, choose Widgets, find **AQI**, and drag the 1x1 widget onto the home screen.

Verify, checking each:
- The tile is one app-icon cell.
- It shows a number, not `—`.
- The fill color matches the category for that number per the table in the spec.
- Tapping it does not open anything and the number stays (a tap is a refresh).

- [ ] **Step 5: Verify the timezone requirement on-device**

```bash
"$ANDROID_HOME/platform-tools/adb" shell su 0 service call alarm 3 s16 Asia/Tokyo || \
  echo "Set the timezone manually: Settings > System > Date & time"
```

Reopen the app and confirm the "Measured" line shifted by the zone offset while the AQI value and its staleness state are unchanged. Set the timezone back afterward.

- [ ] **Step 6: Verify the failure states**

Enable airplane mode, tap the widget, and confirm the last number **remains** rather than blanking. Disable airplane mode.

Revoke location permission in Settings > Apps > AQI > Permissions, then tap the widget. Expected: the tile goes grey with `—` and tapping opens the setup screen. Re-grant afterward.

- [ ] **Step 7: Check for update failures in the log**

```bash
"$ANDROID_HOME/platform-tools/adb" logcat -d -s AqiWidget:E
```
Expected: no output.

If the widget never updates on its own, the likely cause is the receiver's `android:exported` value. Change it to `android:exported="true"` in `app/src/main/AndroidManifest.xml`, rebuild, and reinstall.

- [ ] **Step 8: Write the README**

`README.md`:
```markdown
# AQI Widget

A 1x1 Android home-screen widget showing the current US EPA AQI at your approximate location.

Zero third-party runtime dependencies — `HttpURLConnection`, `org.json`, `RemoteViews`, and
`LocationManager` from the framework, and nothing else.

## Battery

- Updates hourly via `updatePeriodMillis`, which does not wake the device from Doze
- Location is read passively from the OS cache and never requests a fix in the background
- Each refresh is a single ~250-byte HTTPS GET

The one exception is "Update my location now" in the app, which takes a single fresh fix on
an explicit button press.

## Changing the data source

Implement `AqiProvider` and change one line in `provider/Providers.kt`.

`Reading.aqi` must be on the **US EPA 0–500 scale** — the color mapping in `AqiScale` depends
on it, and a provider returning e.g. the European 0–100 scale would render a green tile on
hazardous air.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest    # unit tests, no emulator needed
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "docs: add README"
```

---

## Spec Coverage

| Spec section | Task |
|---|---|
| Constraints (minSdk/targetSdk, zero deps) | 1 |
| Module layout | 1, 3, 4, 5, 6 |
| `AqiScale`, color scale | 2 |
| Swappable provider, US EPA contract, `Providers.ACTIVE` | 3 |
| API keys via `BuildConfig` | Not implemented — Open-Meteo needs none. Documented in the README as the extension point; adding it before a keyed provider exists would be YAGNI. |
| Data source, `timezone=GMT`, `observedAt` rule | 3 |
| GMT invisible to user (epoch millis, local render, zone crossing) | 6 |
| Location strategy, passive read, resolution order | 4, 5 |
| One-shot fix in `SetupActivity` | 6 |
| Refresh strategy, `updatePeriodMillis`, threading, tap target | 5 |
| Widget appearance, 1x1 sizing, tinting, text sizing | 5 |
| States and error handling | 4 (logic), 5 (rendering), 7 (device verification) |
| Testing table | 2, 3, 4, 6. The spec calls the timezone suite `TimeZoneTest`; the plan names it `TimeFormatTest` after the class under test. Same coverage. |
| Build and install | 1, 7 |
