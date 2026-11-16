# Power management

## Budget model

At rest, the standby face should cost roughly:

* OLED emission — proportional to lit area × brightness × colour. Black (#000000)
  pixels are off; champagne-on-black is the lowest-emission usable theme.
* Compositor — one frame per minute is indistinguishable from zero.
* CPU — one wake per minute to redraw, plus the platform's own wakeups.
* Radio — zero in v0.1 (no network permission is requested).

The dominant term on an idle phone is **not** our app; it is the panel, the
SoC's always-on path, and whether the OEM gave the app a low-power display
window at all.

## What the code does

| Mechanism | Where |
|---|---|
| Boundary-aligned redraw (1/min, or 1/s if seconds shown) | `MinuteScheduler` |
| No animation, no Choreographer loop, no value animator | `ClockView` |
| KEEP_SCREEN_ON only while charging | `StandbyActivity.setKeepScreenOn` |
| Per-window dim (never global brightness) | `StandbyActivity.applyBrightness` |
| Burn-in drift ≤ 3 dp, 4 positions, one step/minute | `ClockView.driftFor` |
| No foreground service, no AlarmManager, no polling | — |
| No network, no location, no calendar in v0.1 | manifest |

### What we deliberately do NOT do

* No `WakeLock` to "keep the clock alive". A stopped clock that redraws on the
  next minute is correct; a wakelock is not.
* No `AlarmManager` per-minute wakeups. WorkManager's 15-minute floor is fine for
  weather (Phase 4) and wrong for a clock.
* No `WRITE_SETTINGS` / global brightness writes. The user's system brightness is
  theirs.
* No attempt to disable the lock screen, Doze, or battery optimisation.

## Brightness semantics

`WindowManager.LayoutParams.screenBrightness` is a 0..1 scalar with a
device-defined curve; `-1` means "system default".

* `0.08` is **not** 8 nits. On a 1-nit-minimum panel it may still be well above
  minimum, because the OEM curve compresses the low end.
* v0.1 default is `-1` (follow system). Manual dim is opt-in in Themes.
* Ultra Low Power keeps the theme's `brightnessProfile` but does not silently
  force a brightness value — a user who set 40% and got 6% would call that a bug.

## Measurement protocol

Goal: comparable numbers, not pretty numbers.

1. Same room, same ambient light, 23 ± 2 °C.
2. Battery 60–80%, then plug the official charger (or hold at same level).
3. Airplane mode on; Wi-Fi off; Bluetooth off; no notifications arriving.
4. Fixed system brightness, auto-brightness OFF.
5. Each run: 30–60 min, screen showing only the standby face.
6. Record every 5 min: `dumpsys battery` level/temperature, current from
   BatteryManager stats, `dumpsys gfxinfo` frame count, `dumpsys display` rate.
7. Three repeats per configuration; report median, not best.

Comparison set: native AOD · plain fullscreen clock (no scheduling) · Luma
Standard · Luma Ultra Low Power · Luma Showcase (expected worst, as a sanity
bound).

Metrics worth reporting: mean current (mA), %/hour, temperature delta, frames
drawn per hour, wakeups per hour.

## Acceptance for v0.1

* Static mode draws ≤ ~62 frames/hour (once per minute, ±1).
* No network traffic at all.
* No wakelock held while the face is static.
* Rate requests and observed rates both appear in Diagnostics.
* Nothing in the app assumes it achieved 1 Hz.
