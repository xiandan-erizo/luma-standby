# Luma Standby

*Stay beautiful. Stay low power.*

[![android](https://github.com/xiandan-erizo/luma-standby/actions/workflows/android.yml/badge.svg)](https://github.com/xiandan-erizo/luma-standby/actions/workflows/android.yml)

An OLED-first standby screen for modern Android phones: a large landscape clock,
five hand-tuned themes, and a redraw policy that does nothing between minute
boundaries. No ads, no accounts, no cloud, no root, no Shizuku.

Target device for v0.1: **Xiaomi 17 (HyperOS)**, but every core feature runs on
stock Android because it is built only on public SDK APIs.

---

## Status: Phase 0 + early Phase 1

What is implemented in this tree:

| Area | State |
|---|---|
| Gradle project, Compose settings UI, custom Canvas clock | done |
| Five PRD themes + JSON serialisation | done |
| Widget contract + 6 widgets (battery, weather, alarm, calendar, media, notification) with slot placement | done, see `docs/widgets.md` |
| 12 curated palettes + 6 typefaces, colour and font chosen independently | done |
| Theme editor (duplicate → edit colours/font/gradient → save/delete) and JSON export/import via clipboard | done |
| Device diagnostics page (API level, HyperOS, display modes, ARR, refresh rates, battery, thermal, dream registration) | done |
| Minute-boundary redraw scheduler (+ time/timezone/date change receivers) | done |
| `StandbyActivity`: fullscreen, dim, double-tap to exit, KEEP_SCREEN_ON only while charging, defaults to `sensorLandscape` | done |
| `DreamService` integration (screen saver entry) | implemented, **blocked on HyperOS 4** — selecting it crashes system_server (platform NPE), see `docs/known-issues.md` |
| Refresh-rate *requests* (window preference + Surface intent helper) | done |
| 30 s refresh-rate sampler for device measurements | done |
| UI localized (en / zh-CN / zh-TW), locale-aware date line | done |
| Measured on device: 6 frames drawn in 130 s of standby (~2.8/min) | verified |
| Manual window brightness + night window with progressive ramp | implemented, unit-tested |
| Theme JSON import/export (clipboard + SAF) with strict validation | implemented |
| Burn-in drift 1-2 px every 20 min | implemented, observation pending |
| Weather, calendar, media, notifications | not started (Phase 4) |
| Rust core | deliberately deferred, see `docs/architecture.md` |

Localized in English, Simplified Chinese and Traditional Chinese (`values/`,
`values-zh-rCN/`, `values-zh-rTW/`); the clock's date line follows the system
locale format too.

What this tree can **not** promise, by design:

* That the panel actually scans at 1 Hz. We request it; the platform decides.
  Measured on Xiaomi 17 / HyperOS 4: the request reaches SurfaceFlinger as
  `1.00 Hz`, but the per-app rate override stays at 60 Hz. See
  `docs/display-api.md` for the numbers.
* That HyperOS hands a third-party APK the native AOD power path. It does not.
* That `screenBrightness = 0.08` means 8 nits. It is a window-relative scalar.

---

## Verified locally

Built on 2026-11-16 with JDK 17 (JBR 17.0.12), AGP 8.9.2, Kotlin 2.1.10,
compileSdk/targetSdk 36, Android platform 36 + build-tools 36.0.0:

* `:app:assembleDebug` → 26.9 MB debug APK (multidex, unminified)
* `:app:assembleRelease` → 2.26 MB minified APK, R8 clean with kotlinx.serialization
* `:app:testDebugUnitTest` → 19 tests, 0 failures

Not yet verified on hardware — that is what `docs/verification.md` is for.

## Building

Requirements:

* JDK 17
* Android SDK Platform **36** and Build-Tools 36.x
* Android Studio (Ladybug or newer) or a local Gradle 8.11+

The Gradle wrapper is committed, so no local Gradle install is needed:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Opening the folder in Android Studio works the same way.

### Debug-only test hooks

The debug variant adds two things the release build does not have: a settings
broadcast and an exported `DebugStandby` activity alias, so
`scripts/power-suite.sh` can pin an exact configuration per measurement group
without driving the UI by taps.

The broadcast must name the component explicitly and allow stopped packages -
HyperOS drops implicit broadcasts to a background/stopped app, which makes a
naive `am broadcast` look successful while doing nothing:

```bash
adb shell am broadcast --include-stopped-packages \
  -n app.luma.standby.debug/app.luma.standby.DebugSettingsReceiver \
  -a app.luma.standby.DEBUG_SETTINGS \
  --es theme black-amber --ef brightness 0.15 --ez autoNight false
# -> Broadcast completed: result=1, data="ok"
```

Dependencies and the Gradle distribution URL are pinned to China-friendly
mirrors (Aliyun Maven, Tencent Gradle). Point them elsewhere if you prefer.

`local.properties` is machine-local and git-ignored; Android Studio writes it.

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Register as system screen saver

1. Open Luma Standby, **Preview** tab, tap *System screen saver settings*.
2. Choose **Luma Standby**.
3. On a charger + docked/idle, the platform starts the dream itself. This is the
   only automatic entry Android permits.

---

## Verifying on the device

```bash
adb shell dumpsys display | grep -i -E "refresh|mode|brightness"
adb shell dumpsys SurfaceFlinger | grep -i -E "refresh|fps|mode"
adb shell dumpsys battery
adb shell dumpsys batterystats --charged app.luma.standby
```

The in-app **Diagnostics** tab reports what the platform *claims*; the dumpsys
output is the cross-check. Neither proves what the OLED panel is doing
internally — treat refresh-rate numbers as intent plus observation, not truth.

Power measurement protocol: `docs/power-management.md`.
Full device acceptance script (ADB commands, expected values): `docs/verification.md`.

---

## Layout

```
app/src/main/java/app/luma/standby/
  MainActivity.kt              Compose shell: Preview / Themes / Diagnostics
  StandbyActivity.kt           in-app fullscreen standby
  ui/clock/ClockView.kt        the Canvas clock (the product)
  ui/clock/MinuteScheduler.kt  boundary-aligned redraw timer
  dream/LumaDreamService.kt    screen-saver integration
  domain/model/                LumaTheme, LumaSettings (pure data, @Serializable)
  domain/theme/                presets + DataStore repository
  platform/display/            DisplayController, RefreshRatePolicy
  platform/power|battery/      PowerManager / BatteryManager wrappers
  platform/hyperos/            best-effort HyperOS detection (never required)
docs/                          architecture, display API, power, themes,
                               verification, known issues, phase 2 report,
                               dream re-enable
scripts/                       bootstrap/build, device smoke test, power suite,
                               adb tap helper
```

## License

Apache-2.0. Fonts are requested from the platform (no bundled TTF), so no font
license obligations attach to v0.1.
