# AQI Widget

A 1×1 Android home-screen widget showing the current US EPA air quality index at your
approximate location.

Zero third-party runtime dependencies — `HttpURLConnection`, `org.json`, `RemoteViews`, and
`LocationManager` from the framework, and nothing else. No OkHttp, Retrofit, Gson, androidx,
Glance, or Play Services. `android.useAndroidX=false` in `gradle.properties` makes that
mechanical: the build fails if anything pulls androidx in.

## What it looks like

| State | Tile |
|---|---|
| Fresh reading | Category color, AQI number (yellow `#FFFF00` + black text at AQI 56) |
| Reading over 3h old | Same color blended 55% toward grey, so `#FF0000` renders as `#CA5757` |
| No permission / no location | Grey tile, `—`. Tapping opens the setup screen |
| Fetch failed | **Keeps showing the last number.** Never blanks |

Colors are the official AirNow palette; text flips from black to white at the red threshold.

## Battery

This is the design's main priority, ahead of accuracy:

- Updates hourly via `updatePeriodMillis`, which **does not wake the device from Doze**. An
  idle phone in a pocket costs nothing, and the real interval stretches past an hour while
  idle — desirable, not a defect.
- Location is read **passively** from the OS cache via `getLastKnownLocation` across the
  network and passive providers. The widget never calls `requestLocationUpdates`, so it never
  powers on location hardware.
- Each refresh is a single ~250-byte HTTPS GET. Open-Meteo's own data only changes hourly, so
  polling faster would return identical bytes.

The one exception is **"Update my location now"** in the app, which takes a single fresh fix.
It is battery-acceptable precisely because it fires only on an explicit button press, and it
exists to solve the cold-start case where a fresh install has no cached fix to read.

## Timezones

The API is queried with `timezone=GMT` purely so its timestamps parse unambiguously —
Open-Meteo returns `current.time` as a local ISO string with no offset suffix, which under
`timezone=auto` cannot be converted to epoch millis without separately reading
`utc_offset_seconds`.

That is a wire-format detail and never reaches you: `observedAt` is stored and compared only
as epoch millis, and every displayed time resolves `ZoneId.systemDefault()` at render time.
Crossing a timezone changes only the display, never whether a reading counts as stale.

## Changing the data source

Implement `AqiProvider` and change one line in `provider/Providers.kt`:

```kotlin
object Providers { val ACTIVE: AqiProvider = OpenMeteoProvider() }
```

`Reading.aqi` must be on the **US EPA 0–500 scale**, and providers clamp into that range. This
is load-bearing, not bureaucratic: the European AQI runs a different 0–100 scale on which 60
is severe rather than moderate, so a provider returning its native scale would silently invert
the color mapping and paint a **green tile on hazardous air** — a failure that looks perfectly
fine on screen.

Provider selection is a compile-time constant rather than a settings screen, deliberately. The
interface is what buys flexibility; a picker would be furniture.

API keys, if a future provider needs one, go in a `BuildConfig` field fed from
`local.properties` (gitignored). Open-Meteo needs none.

## Architecture

The core is pure Kotlin with **no `android.*` imports**, so all logic is JVM-testable with no
emulator:

| File | Responsibility |
|---|---|
| `AqiScale.kt` | AQI number → EPA category, colors, staleness, dimming |
| `provider/AqiProvider.kt` | The swappable-source contract |
| `provider/OpenMeteoProvider.kt` | URL building + JSON parsing + clamping |
| `Ports.kt` | `LocationSource` / `ReadingStore` / `Clock` seams |
| `AqiRepository.kt` | location → fetch → cache → `RenderState` |
| `TimeFormat.kt` | epoch millis → local-time string |
| `Http.kt` | ~15-line `HttpURLConnection` + `org.json` helper |
| `AppGraph.kt` | Android-backed ports and hand-rolled wiring |
| `AqiWidgetProvider.kt` | Scheduling, `RemoteViews` rendering, tap handling |
| `SetupActivity.kt` | Permission grant, status, one-shot location fix |

`SetupActivity` exists for one structural reason: **widgets cannot request runtime
permissions**, so something with an `Activity` context must ask for `ACCESS_COARSE_LOCATION`.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest    # 38 unit tests, no emulator needed
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 9.3.0 with built-in Kotlin (do **not** add `org.jetbrains.kotlin.android` —
AGP 9.x conflicts with it), Gradle 9.5.0, JVM target 17, compileSdk/targetSdk 37, minSdk 31.

`minSdk 31` is required by `RemoteViews.setColorStateList` and
`@android:dimen/system_app_widget_background_radius`.

## First run

1. Open the **AQI** app, tap **Grant location permission**.
2. Tap **Update my location now** to seed a coordinate.
3. Long-press the home screen → Widgets → AQI → drag the 1×1 tile out.

Tapping the tile refreshes it. If it shows a grey `—`, tapping opens the setup screen.

## Testing

38 JVM unit tests cover the scale boundaries and exact dimmed colors, Open-Meteo parsing and
failure paths, repository caching and staleness, and timezone invariance.

Widget rendering is not unit-testable — `RemoteViews` and `AppWidgetManager` cannot be
meaningfully exercised on the JVM, and mocking them would only test the mocks. It was instead
verified on an API 36 emulator by binding a real widget through the launcher and screenshotting
each state: fresh Moderate (yellow/black "56"), stale Unhealthy (dimmed `#CA5757`/white "168",
which also exercises 3-digit text sizing), and permission-revoked (grey `—`). The offline path
was verified to retain the cached reading rather than blank it, and tap-to-refresh was verified
to replace a seeded stale value with a live fetch.
