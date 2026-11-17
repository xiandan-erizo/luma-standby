# Display API notes

## What we can ask for, and what we actually control

Three different things get conflated in every "1 Hz standby" claim:

1. **App draw rate** — fully ours. `MinuteScheduler` guarantees it.
2. **System display refresh rate** — a request. `Window.preferredRefreshRate`
   and `Surface.setFrameRate()` are *hints*; the platform's frame-rate arbitrator
   (and OEM policy) may ignore, round, or override them.
3. **OLED panel internal state** — invisible to apps. There is no public API to
   read it, and no public API to force it.

The app therefore reports two facts separately in Diagnostics: what it asked for,
and what `Display.getRefreshRate()` says afterwards. Neither is proof of (3).

## APIs used

| API | Level | Use |
|---|---|---|
| `DisplayManager.getDisplay(DEFAULT_DISPLAY)` | 17 | entry point |
| `Display.getSupportedModes()` | 23 | mode list, resolution + rate |
| `Display.getSupportedRefreshRates()` | **36** | render rates (ignored below 36; falls back to distinct mode rates) |
| `Display.hasArrSupport()` | **36** | adaptive-refresh capability flag |
| `Display.getSuggestedFrameRate()` | **36** | platform's preferred rate for current content |
| `Display.getRefreshRate()` | 17 | observed rate, not a promise |
| `Window.preferredRefreshRate` | 21 | per-window preference |
| `Surface.setFrameRate(fps, compat, change)` | 30/31 | per-surface intent |
| `DisplayManager.DisplayListener` | 17 | notice actual changes |

API-36-only calls are guarded with `Build.VERSION.SDK_INT >= 36`; on older
platforms the diagnostics shows "unknown (API < 36)" rather than crashing.

### Surface vs Window: pick one

Setting both `preferredRefreshRate` and `Surface.setFrameRate()` with different
values makes the arbitrator's behaviour hard to reason about. Current code path:
Window preference in `StandbyActivity` (the standby face is a plain `View`, it has
no Surface of its own). `applySurfaceFrameRate()` exists for the day a
`SurfaceView`-based renderer (e.g. a Rust/GL core) is added — do not enable both
for the same content.

### Compatibility and change policy

* `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` — a clock is not video; there is no
  "smoothness" to trade away.
* `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` — a mode-set flash on a dark standby
  screen is worse than staying at 60 Hz. If Xiaomi 17 refuses the seamless
  switch, we accept the higher rate and record it, rather than strobing the panel.

## Measured result: Xiaomi 17 / HyperOS OS4.0 / Android 17 (API 37)

Date: 2026-11-16. Panel reported by `dumpsys display`:

* 1220 x 2656, density 520, `hasArrSupport true`
* supported render rates: 120, 90, 72, 60, 51.43, 45, 40, 30, 24, 20, 15, 10, 5, 2, **1.0000002**
* `frameRateCategoryRate {normal=60.0, high=90.0}`
* active mode id 3 (120 Hz panel mode, 360 Hz vsync)

What we asked for, and what happened:

| Request path | SurfaceFlinger records | Per-uid rate override |
|---|---|---|
| `Window.preferredRefreshRate = 1f` | `requestedFrameRate: {1.00 Hz, Default}` | `60.000015 Hz` |
| `View.setRequestedFrameRate(1f)` | `requestedFrameRate: {1.00 Hz, Default}` | `60.000015 Hz` |
| `SurfaceControl.Transaction.setFrameRate(probe, 1f, FIXED_SOURCE, ONLY_IF_SEAMLESS)` | `requestedFrameRate: {1.00 Hz, ExactOrMultiple}` | `60.000015 Hz` |
| `preferredRefreshRate = 120f` (Showcase) | `requestedFrameRate: {120.00 Hz, Default}` | `60.000015 Hz` |

Re-measured after a minute-boundary redraw (so a fresh buffer had been queued): unchanged.

Interpretation, stated as a hypothesis rather than a fact: HyperOS resolves *third-party*
window render rate to the 60 Hz "normal" category regardless of the requested rate,
and no public app API changes that. The 1 Hz entry in the supported list is a panel
capability, not a permission granted to ordinary apps. Native AOD runs in the system
process, not through this path.

Consequence for the product: **do not promise 1 Hz.** The measurable win is the
redraw schedule (one frame per minute, verified by design and by `dumpsys gfxinfo`
frame counts), which is orthogonal to the panel rate. Keep the requests in place -
they are free, they are what a well-behaved app should state, and they may be
honoured by other OEM builds - but the diagnostics page reports the *observed*
rate, and the README says plainly that 1 Hz is not guaranteed.

## Measured: window brightness is monotonic but capped

Date: 2026-11-17, same device/build. Requested through
`WindowManager.LayoutParams.screenBrightness`, read back from
`dumpsys display` -> `Display Brightness=`:

| requested (window attr) | applied display brightness |
|---|---|
| 0.05 | 0.0190 |
| 0.20 | 0.0752 |
| 0.50 | 0.1023 |
| 1.00 | 0.1023 |

The window attribute itself is set exactly as asked (`sbrt=0.2` in
`dumpsys window`), so the request is recorded; what the display applies is
HyperOS's own mapping, and it saturates around **0.10 of full range**. Requests
above roughly 0.27 are indistinguishable from each other.

Consequences:

* The brightness slider is honest as a *request*, but its top half is a no-op on
  this build. This is a product decision waiting to be made (compress the slider
  into the responsive region vs keep full range for other ROMs).
* `Settings.System.SCREEN_BRIGHTNESS` was unchanged across all four
  measurements (49 before, 49-50 after), confirming the app never touches the
  global setting.

## Verification protocol (Xiaomi 17)

For each of: native AOD, Luma Standard, Luma Ultra Low Power, Luma Showcase,
plain fullscreen clock:

```bash
adb shell dumpsys display            | grep -i -E "refresh|mode|brightness"
adb shell dumpsys SurfaceFlinger     | grep -i -E "refresh|fps|mode"
adb shell dumpsys batterystats --charged app.luma.standby | head -80
```

Record: requested fps, `getRefreshRate()` samples over 5 minutes, draw count
(from `dumpsys gfxinfo app.luma.standby`), battery current, and temperature.

Expected-but-unproven outcomes to test rather than assume:

* Static face ⇒ platform may keep 60/120 Hz because a static window is not a
  video; Ultra Low Power may be *no better* than Standard in reported rate.
* DreamService may run at a locked rate chosen by HyperOS.
* Charging may itself hold a higher display state.

**If the platform does not drop to 1 Hz, that is a documented result, not a bug.**
Write it down; do not "fix" it with hidden APIs.
