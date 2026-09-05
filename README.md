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

`Reading.aqi` must be on the **US EPA 0–500 scale**. `Reading`'s own factory clamps into that
range, so an out-of-range value can't slip through regardless of which provider constructs it.

That clamp is **not** what protects against the scarier failure: the European AQI runs a
different 0–100 scale on which 60 is severe rather than moderate, so a provider returning its
native scale would silently invert the color mapping and paint a **green tile on hazardous
air**. An EU-scale 60 is *inside* 0–500, so it passes the clamp untouched — the clamp only
catches out-of-range values, not wrong-scale ones. There is no mechanical defense against a
wrong-scale provider; it is load-bearing, not bureaucratic, that every new `AqiProvider`
implementation is verified by hand against the `AqiProvider` KDoc contract before it ships.

Provider selection is a compile-time constant rather than a settings screen, deliberately. The
interface is what buys flexibility; a picker would be furniture.

`Reading.station` carries the reporting ground station's identifier for station-based sources
(WAQI, AirNow); it is null for model-based ones like Open-Meteo. It is plumbed all the way
through `ReadingStore` and rendered by `SetupActivity` when present, so adding a station-based
provider is a one-file change — write the `AqiProvider` and swap the `Providers.ACTIVE` line —
not four.

Open-Meteo needs no API key. If a future provider does, the intended extension point is a
`BuildConfig` field fed from `local.properties` (gitignored) — that plumbing does not exist
yet and would need to be added alongside that provider.

## Architecture

The core is pure Kotlin with **no `android.*` imports**, so all logic is JVM-testable with no
emulator:

| File | Responsibility |
|---|---|
| `AqiScale.kt` | AQI number → EPA category, colors, staleness, dimming |
| `provider/AqiProvider.kt` | The swappable-source contract; `Reading`'s factory clamps `aqi` to 0-500 |
| `provider/OpenMeteoProvider.kt` | URL building + JSON parsing |
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

Neither `JAVA_HOME` nor `adb` is set up globally on this machine, so both paths are spelled
out. Java comes from Android Studio's bundled JBR — `/usr/bin/java` is only a stub and will
fail with "Unable to locate a Java Runtime".

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"   # puts adb on PATH

./gradlew :app:testDebugUnitTest    # 52 unit tests, no emulator needed
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Add the three exports to `~/.zshrc` to skip them next time.

Toolchain: AGP 9.3.0 with built-in Kotlin (do **not** add `org.jetbrains.kotlin.android` —
AGP 9.x conflicts with it), Gradle 9.5.0, JVM target 17, compileSdk/targetSdk 37, minSdk 31.

`minSdk 31` is required by `RemoteViews.setColorStateList` and
`@android:dimen/system_app_widget_background_radius`.

## Connecting over Wi-Fi (no cable)

Wireless debugging is the easier path, and the only one that works if your USB-C cable is
charge-only — a very common failure. The tell: the phone charges, but `adb devices` is empty
*and* `system_profiler SPUSBDataType` shows nothing enumerating. If the Mac sees no USB device
at all (rather than an `unauthorized` one), no amount of toggling USB debugging will help —
the cable has no data lines. Many cables bundled with chargers and power banks are like this.

Phone and Mac must be on the same Wi-Fi. Then:

1. **Settings → System → Developer options → Wireless debugging** → on
2. Tap **Pair device with pairing code** — note the 6-digit code and the `IP:port` shown
3. Pair, using *that* port:
   ```bash
   adb pair 192.168.1.144:37403 735737
   ```
4. Connect, using the **different** `IP:port` from the main Wireless debugging screen — this
   is the step everyone gets wrong; the pairing port is not the connection port:
   ```bash
   adb connect 192.168.1.144:35257
   adb devices          # should list the phone as `device`
   ```

Pairing persists across reboots; the connect port changes each time wireless debugging is
toggled off and on.

**On a production phone `adb root` is unavailable**, so the hand-sent refresh broadcast used
for emulator testing will not work — the receiver is `exported="false"` and silently drops it.
Drive the app's own buttons instead (`adb shell input tap X Y`, coordinates from
`adb shell uiautomator dump /sdcard/ui.xml`), or just tap the phone.

## Installing over USB

1. On the Pixel: Settings → About phone → tap **Build number** 7× to unlock Developer
   options, then Developer options → enable **USB debugging**.
2. Plug in over USB and accept the "Allow USB debugging?" prompt on the phone.
3. `adb devices` should list it as `device` (not `unauthorized`). Then `adb install -r …`
   from the Build section above.

## First run

1. Open the **AQI** app, tap **Grant location permission**.
2. Tap **Update my location now** to seed a coordinate.
3. Long-press the home screen → Widgets → AQI → drag the 1×1 tile out.

Tapping the tile refreshes it. If it shows a grey `—`, tapping opens the setup screen.

## Testing locally

Unit tests need nothing but a JDK:

```bash
./gradlew :app:testDebugUnitTest
```

For anything involving the widget itself, there is a preconfigured emulator (`aqi_test`,
API 36 arm64):

```bash
"$ANDROID_HOME/emulator/emulator" -avd aqi_test -no-window -no-audio -no-boot-anim &
adb wait-for-device
adb root          # needed — see below
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.ben.aqiwidget android.permission.ACCESS_COARSE_LOCATION
```

**`adb root` is required to trigger a refresh by hand.** The widget receiver is
`android:exported="false"`, so a broadcast from the ordinary shell user is silently *enqueued
but never delivered* — the process never even starts, and you get no error. That is the
receiver correctly refusing broadcasts from other apps; the system's own `APPWIDGET_UPDATE`
is exempt. As root:

```bash
# force a fetch, exactly like tapping the tile
adb shell am broadcast --include-stopped-packages \
  -a dev.ben.aqiwidget.ACTION_REFRESH -n dev.ben.aqiwidget/.AqiWidgetProvider

# what the app actually stored
adb shell "cat /data/data/dev.ben.aqiwidget/shared_prefs/aqi.xml"
```

`--include-stopped-packages` matters after `am force-stop` or a fresh install — a stopped
package receives no broadcasts until something launches it.

To see a specific tile state, write the prefs directly, then broadcast. `observed_at` is
epoch millis; backdate it past 3h to get the dimmed treatment:

| To see | Set |
|---|---|
| Yellow, black text | `aqi` 51–100, `observed_at` = now |
| Dimmed red, white text, 3 digits | `aqi` 168, `observed_at` = now − 4h |
| Grey `—` | `adb shell pm revoke dev.ben.aqiwidget android.permission.ACCESS_COARSE_LOCATION` |

Screenshot the result with `adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`.

Two emulator limitations worth knowing before you chase a phantom bug:

- **Mock location does not work.** Modern Android refuses to let the shell uid grant itself
  `MOCK_LOCATION`, and `adb emu geo fix` only feeds the GPS provider, which this app cannot
  read under coarse-only permission. Seed `lat`/`lon` into the prefs instead — the app falls
  back to cached coordinates, which is a real production path.
- **Doze and the hourly `updatePeriodMillis` tick** can't be meaningfully observed on an
  emulator. Those only prove out on the real phone over hours.

Verified on a real Pixel 10 Pro (Android 17, API 37): the one-shot fix returned real
coordinates, the fetch stored AQI 66 with a timestamp matching the live API exactly. Worth
knowing — on that first run the passive location cache was **empty** despite the phone being
in daily use, so the widget had no location until the one-shot button was pressed. That is the
cold-start case the button exists for. Once seeded, the coordinate persists in
`SharedPreferences`, so the widget always has a fallback thereafter.

## Testing

52 JVM unit tests cover the scale boundaries and exact dimmed colors, Open-Meteo parsing and
failure paths (including the fetch-time timestamp fallback), repository caching, staleness,
and station round-tripping, and timezone invariance including the default-zone path.

Widget rendering is not unit-testable — `RemoteViews` and `AppWidgetManager` cannot be
meaningfully exercised on the JVM, and mocking them would only test the mocks. It was instead
verified on an API 36 emulator by binding a real widget through the launcher and screenshotting
each state: fresh Moderate (yellow/black "56"), stale Unhealthy (dimmed `#CA5757`/white "168",
which also exercises 3-digit text sizing), and permission-revoked (grey `—`). The offline path
was verified to retain the cached reading rather than blank it, and tap-to-refresh was verified
to replace a seeded stale value with a live fetch.
