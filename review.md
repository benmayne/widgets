# Code Review — AQI Widget

Independent review of the repo at commit `a97c7e8`, from first principles: design docs,
implementation, and design↔implementation conformance. Verified mechanically before review:
`./gradlew :app:testDebugUnitTest` passes 42/42 (matching the README's count),
`:app:assembleDebug` succeeds, and `local.properties` is gitignored and untracked.

**Overall verdict:** solid. Architecture matches the design doc almost line-for-line, the
pure-core/thin-shell split is real (the logic that matters is JVM-tested), and the docs are
honest. No showstoppers found. Four substantive issues below, then nits. Items 1 and 4 are
the ones worth fixing before install.

---

## 1. HTTP timeouts can exceed the `goAsync()` budget the design itself cites

**Where:** `Http.kt:12` (`TIMEOUT_MS = 10_000`), design doc "Threading" section.

The design doc says "the ~10 second goAsync budget is ample for a 250-byte fetch," but the
implementation sets 10s connect + 10s read. A hung server can consume ~20s — and
`readTimeout` is per-read, so a slow-drip response can stretch further. If the budget is
exceeded, the system may kill the process mid-fetch. Consequence is mild (cache is retained,
next hourly tick retries), but design and implementation contradict each other.

**Fix:** drop both timeouts to ~4–5s. Worst case then stays inside the budget with margin,
and 4s is still generous for a 250-byte response.

## 2. Every system broadcast triggers a network fetch — no freshness guard, despite battery being the stated top priority

**Where:** `AqiWidgetProvider.onReceive` → `AqiRepository.refresh()`.

`ACTION_APPWIDGET_UPDATE` arrives not just hourly but on reboot, launcher restart, and
widget re-add. Each one goes straight to the network even if the cached reading is minutes
old. The design doc itself notes upstream data only changes hourly ("polling faster would
return identical bytes"), yet nothing prevents exactly that.

**Fix:** in the repository, skip the fetch and render from cache when the cached reading is
fresher than ~30 minutes. Taps arrive as the distinct `ACTION_REFRESH`, so tap-to-refresh
can stay unconditional while the guard applies only to system broadcasts. Small win, but on
the axis the design says matters most; also insulates against Open-Meteo rate limiting.

## 3. The 0–500 clamp does not defend against the failure mode the docs use to justify it

**Where:** `OpenMeteoProvider.kt:44`, `AqiProvider.kt` KDoc, design doc "Swappable provider",
README "Changing the data source".

Both docs motivate the clamp with the "green tile on hazardous air" scenario — a
European-scale provider where 60 is severe. But an EU-scale 60 is *inside* 0–500 and passes
the clamp untouched. Clamping only handles out-of-range values; the wrong-scale hazard
remains enforced purely by documentation. That's acceptable (a wrong scale isn't mechanically
detectable), but the docs oversell what the clamp buys — reword them.

Related structural point: the clamp lives inside each provider, so a future provider can
simply forget it. The one guarantee that *can* be enforced centrally should be:

**Fix:** move the clamp into `Reading` itself — a factory function or `init` block that
coerces `aqi` into 0..500 — so the invariant travels with the type rather than with each
implementer's diligence. Then correct the design doc/README wording so the clamp is justified
by what it actually does (out-of-range protection), not by the wrong-scale scenario.

## 4. Permanently-denied permission is a dead end in SetupActivity

**Where:** `SetupActivity.kt:28-30` (grant button), `onRequestPermissionsResult`.

After the user denies twice (or "don't ask again"), tapping **Grant location permission**
produces no dialog — the result returns denied immediately and the status line just keeps
saying "NOT granted" with no explanation. There is no path to recover from inside the app.

**Fix:** when the result is denied and `shouldShowRequestPermissionRationale()` is false,
launch the app's settings page via
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (with the package URI), and/or say so in the
status text. Corner case for a single-user sideloaded app, but confusing when hit.

---

## Design-doc drift (trivial, fix the doc not the code)

- Module layout lists `values/colors.xml` and `mipmap-*/ic_launcher`; neither exists
  (colors live in `AqiScale.kt`, the icon is `drawable/ic_launcher.xml`).
- Test table names `TimeZoneTest`; the real file is `TimeFormatTest`.

## Nits (fix or ignore at your discretion)

- `SetupActivity.kt:117` — `"%.2f".format(...)` uses the default locale; some locales render
  "Near 37,77, -122,42". Similarly `TimeFormat` hardcodes US 12-hour format regardless of
  the device's 24-hour preference. Fine for one user's phone.
- The `getCurrentLocation` callback in `SetupActivity` holds the destroyed activity (null
  `CancellationSignal`, `mainExecutor` callback touching `status`). Brief leak at worst.
  While there: consider `LocationManager.FUSED_PROVIDER` (API 31+, framework-only, minSdk
  already permits it) instead of `NETWORK_PROVIDER` for the one-shot fix.
- The `dev.ben.aqiwidget.ACTION_REFRESH` entry in the manifest intent-filter is unnecessary —
  all refresh intents are explicit. Harmless.
- The 1.5s `postDelayed(::showStatus)` after requesting a refresh can show pre-refresh data
  if the fetch is slow. Self-correcting via the Refresh button.
- Stale dimming only applies at the next render, so a reading can be hours old but still
  bright while the device dozes. Inherent to the chosen refresh model and consistent with the
  design's accepted tradeoffs — noting for awareness, not a defect.

## Conformance check (all verified present and correct — no action needed)

State table incl. tap routing (NeedsPermission/NoLocation → setup, else → refresh);
never-blank-on-failure; permission short-circuit; epoch-millis-only staleness math;
render-time zone resolution; passive-only location across network+passive providers;
`goAsync` with `finally`-guarded `finish()`; `super.onReceive` delegation for lifecycle
actions.

## Explicitly good (do not "fix" these)

- `OrgJsonClasspathTest` — guards a real silent failure mode (`isReturnDefaultValues` making
  the android.jar org.json stub return zeros instead of throwing).
- Integer-math dim blend with exact-hex test assertions, for cross-platform reproducibility.
- Repository tests cover the cases that matter: both sides of the 3-hour boundary,
  fetch-failure-keeps-cache, coordinate fallback ordering, and the permission short-circuit
  asserting zero provider *and* zero location calls.
