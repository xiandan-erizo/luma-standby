# Phase 2 acceptance report

Date: 2026-11-16. Device: Xiaomi 17 (25113PN0EC), HyperOS OS4.0.6.0.XPCCNXM,
Android 17 / API 37, 1220x2656 @520dpi, ARR supported.

## Scope decision

DreamService / system AOD takeover is frozen: the platform crashes on the only
reachable entry point (see `docs/known-issues.md`). `StandbyActivity` is the only
product entry. The service is unregistered from the shipped manifest; source is
kept for other ROMs (`docs/dream-service.md`).

## Status matrix

| Module | State | Verified on device |
|---|---|---|
| StandbyActivity | usable, main line | yes |
| One frame per minute | kept | yes - 6 frames in 130 s |
| Orientation switch | kept | yes - portrait and landscape both render |
| Themes, i18n (en / zh-CN / zh-TW) | kept | yes |
| Manual window brightness | implemented | partial - settings persist, brightness attribute pending final check |
| Night window + ramp + cross-midnight | implemented | logic covered by unit tests, device boundary run pending |
| Theme clipboard / SAF import-export | implemented | clipboard path exercised before the rework, SAF pending |
| Burn-in micro-shift (1-2 px, 20 min) | implemented | sequence unit-tested, 24-48 h observation pending |
| Power baseline A-E | harness ready | full 5-group run pending |
| DreamService | blocked on HyperOS 4 | n/a - disabled on purpose |

## What changed in Phase 2

1. **Brightness model** - `NightMode` is now a pure function of minute-of-day,
   the manual value and the current system brightness; `BrightnessController`
   applies it to the window and never touches `Settings.System`. Priority:
   manual > night window > system; the override is restored on `onPause`.
2. **Night ramp** - start/end are configurable in minutes-of-day, cross-midnight
   windows are handled explicitly, and the transition is a linear fade across
   `nightRampMinutes` around each boundary. The ramp interpolates from the real
   system brightness, so "follow system" still fades instead of stepping.
3. **Clock tick hook** - night brightness is re-evaluated exactly on the clock's
   own redraw boundary; no second timer was added.
4. **Theme import is a trust boundary** - `ThemeImport` validates JSON field by
   field and rejects bad colours, unknown fonts, missing fields. A rejected
   import leaves persisted themes untouched. SAF export/import added.
5. **Burn-in drift** - replaced the per-minute 3 dp jump with a deterministic
   12-position walk, 1-2 px, one step every 20 minutes.
6. **DreamService hardened and unregistered** - all platform calls guarded; the
   manifest block is commented out with a pointer to the re-enable doc.

## Test coverage

`./gradlew :app:testDebugUnitTest` - 40 tests, 0 failures:

| Suite | Tests | Covers |
|---|---|---|
| NightModeTest | 12 | cross-midnight windows, ramp in/out, zero ramp, manual priority, parsing |
| BurnInDriftTest | 6 | interval stepping, amplitude bounds, no consecutive repeats |
| ThemeImportTest | 7 | valid import, bad colour/font/name, non-JSON, preset round-trip |
| ThemeEditingTest | 6 | duplicate, upsert, delete, hex validation |
| ThemeJsonTest | 5 | serialisation round-trip, unknown keys, fallbacks |
| ClockTimingTest | 4 | minute/second boundary scheduling |

## Gate for calling Phase 2 done

1. Device run of the brightness matrix (system / manual / night) with window
   attribute readback.
2. SAF export then import of the same theme, with a corrupted file rejected.
3. 24-48 h burn-in observation (screenshots at intervals, no visible jump).
4. Power suite A-E at 60 min per group, three repeats, reported as medians.

Nothing in this report claims hardware behaviour that was not measured; the
pending items above are listed as pending on purpose.