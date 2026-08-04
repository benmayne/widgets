# AQI Widget — Design

**Date:** 2026-08-03
**Status:** Approved, ready for implementation planning

## Purpose

A minimal Android app whose sole purpose is to provide a 1x1 home-screen widget showing the
current air quality index at the user's approximate location. Target device: Pixel 10.

Explicit priority order, per the user: **battery usage and minimal dependencies over accuracy.**
Every design decision below resolves in that direction. Approximate location is acceptable.

## Non-goals

- No history, graphs, forecasts, or pollutant breakdowns
- No notifications or alerts
- No multi-widget or multi-location support
- No settings UI (see "Provider selection" for why)
- Not published to Play Store; sideloaded to one device

## Constraints

| Constraint | Value |
|---|---|
| minSdk | 31 (Android 12) — device runs far newer; a high floor avoids legacy branches |
| targetSdk / compileSdk | 37 — the platform Android Studio installed; compiling against the newest is standard and needs no extra download |
| Language | Kotlin |
| Third-party runtime dependencies | **Zero** |
| Test dependencies | JUnit only |

Zero runtime dependencies is achievable because everything needed is in the Android framework:
`HttpURLConnection` for networking, `org.json` for parsing, `RemoteViews` for the widget,
`LocationManager` for location, and framework `requestPermissions()` for the permission flow.
No OkHttp, Retrofit, Gson, Moshi, androidx, Glance, or Play Services.

## Architecture

### Module layout

```
widgets/
  settings.gradle.kts
  build.gradle.kts
  gradlew, gradle/wrapper/
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/dev/ben/aqiwidget/
      AqiWidgetProvider.kt      # AppWidgetProvider: scheduling, rendering, tap handling
      AqiRepository.kt          # orchestration: location -> fetch -> cache -> state
      AqiScale.kt               # pure: AQI number -> category, colors, staleness dimming
      SetupActivity.kt          # permission grant + status + one-shot location fix
      Http.kt                   # ~15-line HttpURLConnection + org.json helper
      Ports.kt                  # LocationSource, ReadingStore, Clock interfaces
      provider/
        AqiProvider.kt          # the swappable-source interface + Reading
        OpenMeteoProvider.kt    # default implementation
        Providers.kt            # ACTIVE constant
    src/main/res/
      layout/widget_aqi.xml
      xml/aqi_widget_info.xml
      drawable/widget_bg.xml
      values/{strings,colors,themes}.xml
      mipmap-*/ic_launcher
    src/test/java/dev/ben/aqiwidget/
      AqiScaleTest.kt
      OpenMeteoProviderTest.kt
      AqiRepositoryTest.kt
```

### Component responsibilities

**`AqiScale.kt`** — pure functions, no Android imports, fully unit-testable.
Maps an AQI integer to its EPA category, background color, and foreground color; and blends
a color toward grey for the stale state.

**`AqiProvider` / `OpenMeteoProvider`** — the data-source seam. See "Swappable provider".

**`AqiRepository.kt`** — the only orchestration point. Resolves location, calls the active
provider, persists the result, and returns a `RenderState`. Depends on the `Ports.kt`
interfaces rather than Android classes, so its caching and staleness logic is JVM-testable.

**`AqiWidgetProvider.kt`** — `AppWidgetProvider` subclass. Owns the hourly update, the tap
`PendingIntent`, the background thread, and turning a `RenderState` into `RemoteViews`.

**`SetupActivity.kt`** — exists for one structural reason: **widgets cannot request runtime
permissions.** Something with an `Activity` context must ask for `ACCESS_COARSE_LOCATION`.
Secondarily it displays the current reading, the active provider's name, the station (if the
provider is station-based), and the last-update time.

## Swappable provider

Accuracy is explicitly deprioritized today, but the source must be replaceable later without
a rewrite. The seam:

```kotlin
/**
 * A source of air quality readings.
 *
 * CONTRACT: `Reading.aqi` MUST be on the US EPA AQI scale (0-500), regardless of what
 * scale the underlying service natively reports. Implementations are responsible for
 * normalizing. This is not incidental: the European AQI runs a different 0-100 scale on
 * which 60 is severe rather than moderate, so a provider returning its native scale would
 * silently invert the color mapping in AqiScale and render a green tile on hazardous air.
 */
interface AqiProvider {
    val name: String
    /** @throws java.io.IOException on network or parse failure. */
    fun fetch(lat: Double, lon: Double): Reading
}

data class Reading(
    val aqi: Int,                  // US EPA scale, clamped to 0-500
    val observedAt: Long,          // epoch millis of measurement
    val station: String? = null    // station-based providers only; null for model-based
)
```

Providers **clamp** `aqi` into `0..500` rather than throwing on an out-of-range value. An
anomalous reading still carries directional meaning — above 500 genuinely is hazardous — so
clamping keeps the tile current and correctly colored at the extreme instead of falling back
to stale data. Without enforcement the scale contract would be documentation nobody checks,
which matters most for a provider swapped in later whose scale has not been verified by hand.

`station` is nullable to preserve a real asymmetry rather than flatten it: Open-Meteo is
model-based and has no station, while WAQI and AirNow do. The 1x1 tile ignores it;
`SetupActivity` shows it when present, so a future accuracy-focused provider isn't reduced
to the weakest source's shape.

Each provider stays small because `Http.getJson(url): JSONObject` handles the transport.
`OpenMeteoProvider` is roughly 20 lines of URL-building and field extraction.

**API keys:** implementations take a key as a constructor argument, sourced from a
`BuildConfig` field populated from `local.properties` (gitignored). Open-Meteo needs no key
and stays keyless; adding a keyed provider later does not mean committing a secret.

**Not built.** Open-Meteo needs no key, so this plumbing was deliberately not implemented —
`buildConfig` is not enabled and nothing reads `local.properties`. It is recorded here as the
intended extension point, to be added alongside the first keyed provider rather than
speculatively.

**Provider selection is a compile-time constant**, not a settings screen:

```kotlin
object Providers { val ACTIVE: AqiProvider = OpenMeteoProvider() }
```

Deliberate YAGNI. Runtime switching would require a settings UI, persistence, and migration
logic to support a decision made perhaps twice in the app's life. The interface is what buys
flexibility; a picker would be furniture. If A/B comparison is ever wanted, the seam already
exists and the constant becomes a preference read.

## Data source

Open-Meteo Air Quality API. Verified live during design:

```
GET https://air-quality-api.open-meteo.com/v1/air-quality
      ?latitude=<lat>&longitude=<lon>&current=us_aqi&timezone=GMT

{"latitude":37.8,"longitude":-122.4,...,"current":{"time":"2026-08-03T19:00",
 "interval":3600,"us_aqi":56}}
```

`timezone=GMT` rather than `auto` is deliberate. Open-Meteo returns `current.time` as a
local ISO string with no offset suffix, so under `auto` it cannot be converted to epoch
millis without also reading `utc_offset_seconds` and doing the arithmetic. Pinning to GMT
makes the field parse as UTC directly and unambiguously.

**`observedAt` rule:** providers set it to the measurement timestamp when they can supply one
unambiguously — Open-Meteo can, given the above — and otherwise to the fetch time. This
matters because staleness dimming is meant to express *"how old is this measurement,"* not
*"how long since we last made a request."*

**GMT is a wire-format detail and must stay invisible to the user.** Three requirements
follow, and they are testable rather than aspirational:

1. `observedAt` is stored and compared **only as epoch millis**. Staleness arithmetic is
   therefore timezone-independent by construction — no local-time math anywhere.
2. Every user-facing timestamp — the "last updated" line in `SetupActivity` is currently the
   only one — is rendered in the **device's current local timezone** via
   `ZoneId.systemDefault()`, resolved at render time rather than cached.
3. Because both of the above resolve at read time, crossing a timezone (or a DST boundary)
   changes only how an existing reading is *displayed*, never whether it is considered
   stale, and never its stored value. No migration or refresh is needed on timezone change.

No API key, no signup, free for non-commercial use, ~250-byte response, returns `us_aqi`
directly on the US EPA scale so no normalization math is needed. It is a CAMS model forecast
interpolated to the coordinates rather than a ground-sensor reading — an estimate, which is
the accuracy tradeoff the user accepted.

Its `interval` is 3600 seconds: **the upstream data itself only changes hourly**, which sets
the refresh cadence below. Polling faster would return identical bytes.

## Location strategy

`LocationManager.getLastKnownLocation()`, reading the OS's already-cached fix across the
`network` and `passive` providers and taking the most recent. **The app never requests a
location update**, so it never powers on location hardware and its location cost is zero.

The `gps` provider is deliberately excluded. `getLastKnownLocation(GPS_PROVIDER)` requires
`ACCESS_FINE_LOCATION` — under coarse-only permission it always throws `SecurityException`,
which this code would swallow, making it a dead branch. This app deliberately never requests
fine location, so including `gps` here would only be a permanently-failing no-op.

Permission: `ACCESS_COARSE_LOCATION` only. Fine location is neither needed nor requested —
AQI varies over kilometers.

**Known weakness and its mitigation.** A purely passive read returns null if no app has
caused a fix recently, which would leave a cold-installed widget with nothing to show.
`SetupActivity` therefore offers a **one-shot** `LocationManager.getCurrentLocation()` — an
actual fresh fix, triggered only by explicit user action inside the app, never from the
widget or from background code. This is battery-acceptable precisely because it is rare and
user-initiated. Its result is persisted as the fallback coordinate.

Resolution order each refresh:
1. Most recent non-null `getLastKnownLocation()` across providers
2. Otherwise, last-good coordinates from `SharedPreferences`
3. Otherwise, `NoLocation` state

## Refresh strategy

`updatePeriodMillis = 3600000` (1 hour) in `aqi_widget_info.xml`, plus tap-to-refresh.

Three properties compound into a negligible battery cost:

- `updatePeriodMillis` **does not wake the device from Doze.** An idle phone in a pocket
  costs nothing; updates land when the device is already awake. The effective interval
  stretches beyond an hour while idle, which is desirable, not a defect.
- The OS batches this wakeup with other apps' scheduled work.
- Each refresh is one sub-kilobyte HTTPS GET plus a passive memory read for location.

Accepted tradeoffs of choosing this over `WorkManager`: no "only run when network connected"
constraint and no automatic retry with backoff. Both are absorbed by the caching behavior —
a failed fetch keeps displaying the last known value rather than blanking, and the next
hourly tick retries naturally. This costs one dependency and some robustness in poor
connectivity, which the stated priorities accept.

**Threading:** `AppWidgetProvider.onUpdate` runs on the main thread, where a network call
raises `NetworkOnMainThreadException`. `onReceive` is overridden to call `goAsync()` and run
the work on a single-thread `Executor`, finishing the `PendingResult` in a `finally`. The
~10 second `goAsync` budget is ample for a 250-byte fetch. Actions other than update/refresh
delegate to `super.onReceive` so `onDeleted`/`onEnabled`/`onDisabled` still dispatch.

**Tap target:** a `PendingIntent.getBroadcast` with a custom `ACTION_REFRESH` and
`FLAG_IMMUTABLE`. When the location permission has not been granted, the tap instead opens
`SetupActivity` via `PendingIntent.getActivity`.

## Widget appearance

One app-icon cell: `minWidth`/`minHeight` 40dp with `targetCellWidth`/`targetCellHeight` of
1, `resizeMode="none"`, `widgetCategory="home_screen"`.

A rounded-corner shape drawable filled with the category color, and the AQI number large and
centered. The color carries the meaning at a glance; the number is the detail. No category
label — at 1x1 it would need to shrink to roughly 8sp and "Unhealthy for Sensitive Groups"
would have to be abbreviated past the point of usefulness.

Tinting uses `RemoteViews.setColorStateList(id, "setBackgroundTintList", …)` on the shape
drawable (API 31+), which is why minSdk 31 is convenient.

Text size is set explicitly via `setTextViewTextSize` based on digit count (2 digits vs 3),
because `autoSizeText` is not usable through `RemoteViews`.

### Color scale

Official AirNow palette:

| AQI | Category | Background | Text |
|---|---|---|---|
| 0–50 | Good | `#00E400` | black |
| 51–100 | Moderate | `#FFFF00` | black |
| 101–150 | Unhealthy for Sensitive Groups | `#FF7E00` | black |
| 151–200 | Unhealthy | `#FF0000` | white |
| 201–300 | Very Unhealthy | `#8F3F97` | white |
| 301+ | Hazardous | `#7E0023` | white |

## States and error handling

A 1x1 tile has no room to explain itself, so every failure degrades to something legible.

| State | Tile | Tap action |
|---|---|---|
| `Ok(aqi, stale=false)` | Category color, AQI number | Refresh |
| `Ok(aqi, stale=true)` | Same, dimmed | Refresh |
| `NeedsPermission` | Grey, `—` | Open `SetupActivity` |
| `NoLocation` | Grey, `—` | Open `SetupActivity` |
| `NoData` | Grey, `—` | Refresh |

**Never blank a good value on failure.** A failed fetch retains the last reading; the tile
only communicates doubt via dimming once the reading exceeds **3 hours** old. Dimming is
implemented as a pure color blend (55% toward `#9E9E9E`) computed in `AqiScale`, so it is
deterministic and unit-testable rather than a view-level alpha.

**Persisted in `SharedPreferences`:** last AQI, its `observedAt` timestamp, and last-good
latitude/longitude.

## Testing

`AqiScale` and provider parsing are pure and get real JVM unit tests. The repository becomes
testable by depending on three tiny interfaces in `Ports.kt` — `LocationSource`,
`ReadingStore`, `Clock` — rather than on Android classes directly.

| Test | Covers |
|---|---|
| `AqiScaleTest` | Category boundaries at 0, 50/51, 100/101, 150/151, 200/201, 300/301, 500; foreground flip at the red threshold; dim blend math |
| `OpenMeteoProviderTest` | Parses the captured real response; `current.time` converts to the correct UTC epoch millis; correct URL construction; missing or null `us_aqi` raises `IOException` |
| `AqiRepositoryTest` | With a `FakeProvider`: fresh fetch, staleness detection at the 3h boundary, fallback to cache on fetch failure, fallback to cached coordinates when location is null, `NoLocation` when nothing is available |
| `TimeZoneTest` | Staleness verdict is identical under several `TimeZone` defaults for the same epoch millis; the "last updated" string renders in local time and shifts when the default zone changes |

Widget rendering and the permission flow are verified manually on the device; they are not
meaningfully unit-testable and mocking them would test the mocks.

## Build and install

Android Studio (arm64 build, installed via Homebrew). The first-run SDK wizard is GUI-only
and must be completed by the user; afterward the SDK lives at `~/Library/Android/sdk` and
the project can also be built and installed from the command line via the Gradle wrapper and
`adb install`, so compilation can be verified without the IDE.

## Risks

| Risk | Mitigation |
|---|---|
| Passive location never populates on a quiet device | One-shot fresh fix in `SetupActivity`; cached coordinates thereafter |
| OEM/launcher throttles `updatePeriodMillis` beyond an hour | Acceptable — AQI moves slowly, and tap-to-refresh always works |
| Open-Meteo non-commercial terms or rate limits change | Provider interface makes replacement a one-file change |
| Model-based estimate diverges from local sensors | Accepted explicitly; swap to WAQI or AirNow via the seam if it matters later |
