# Architecture

## The one rule

**The clock must work with zero OEM cooperation.** Everything HyperOS-specific
sits behind an adapter that returns "unavailable" on any other device, and no
code path is allowed to *require* it.

```
        MainActivity (Compose)          StandbyActivity        LumaDreamService
                |                            |                        |
                +-------------+--------------+------------------------+
                              |
                        domain/theme (DataStore + JSON)
                              |
                    +---------+---------+
                    |                   |
              ClockView            RefreshRatePolicy
              (Canvas, 1 draw/min)  (window preference intent)
                    |                   |
                    +---------+---------+
                              |
                    platform/display | power | battery | hyperos
                              |
                        Android SDK / HyperOS
```

## Why Kotlin only, no Rust yet

The PRD allows a Rust core. It is not here, on purpose:

* A standby clock's cost is **wakeups**, not arithmetic. Rust does not reduce
  wakeups; deleting timers does. There is no hot loop to optimise.
* Colour/layout maths is a few hundred lines and runs once per minute. At
  1 draw/minute, even interpreted Kotlin is beyond irrelevant.
* FFI adds a build axis (cargo-ndk, ABI splits, 4 APK variants) that buys
  nothing measurable on the battery.

When a real reason appears — an on-device theme compiler, a shared core with an
iOS port, a pixel-drift model that needs sub-millisecond precision — add
`core/native` then. The domain layer is already pure data, so the seam exists.

## Rendering model

`ClockView` is a plain `View`:

* `onDraw` paints background (solid or linear gradient), the time, the date, and
  an optional hint. No Compose in the standby path, so no recomposition, no
  snapshot state, no frame clock.
* `MinuteScheduler` posts to the main `Handler` at `60_000 - now % 60_000 + 40ms`
  (or the second boundary when seconds are on). Exactly one draw per boundary.
* `ACTION_TIME_TICK`, `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`,
  `ACTION_DATE_CHANGED` force a reschedule so a DST jump or manual clock change
  cannot leave a stale face on screen.
* The receiver is registered in `onAttachedToWindow` and unregistered in
  `onDetachedFromWindow`. No callback outlives the window.

## State model

`LumaSettings` is one immutable `@Serializable` object persisted as JSON in
DataStore. There is no second source of truth: the UI writes a whole object, the
clock reads a whole object. That makes theme import/export trivial (same codec)
and keeps the door open for a future Rust core to consume the same JSON.

## Boundary between UI and platform

`RefreshRatePolicy` maps the product's three states to two numbers: a target fps
*request* and a draw interval. Only the second number is enforceable by us, and
the code is honest about that — see `docs/display-api.md`.

## Failure modes we accept

| Failure | Behaviour |
|---|---|
| HyperOS property read blocked | `HyperOSDetector` returns empty strings, diagnostics shows "-" |
| Dream service disabled by the OEM | diagnostics says so; in-app standby still works |
| Platform ignores `preferredRefreshRate` | we draw once a minute anyway; the panel does what it wants |
| DataStore JSON corrupted | falls back to `LumaSettings()` defaults |
| No charger attached | KEEP_SCREEN_ON is cleared; the screen sleeps as the user expects |
