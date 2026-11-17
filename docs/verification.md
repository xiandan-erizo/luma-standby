# Device verification

Everything below is meant to be run with the phone plugged in over ADB. It is the
Phase 0/1 acceptance script for Xiaomi 17 (HyperOS), and it deliberately records
**what the platform says**, not what we wish it said.

## 0. Provision

```bash
export ANDROID_HOME=$PWD/.tooling/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices
adb shell getprop ro.build.version.release     # Android version
adb shell getprop ro.build.version.sdk         # API level
adb shell getprop ro.mi.os.version.name        # HyperOS version (may be empty)
adb shell getprop ro.mi.os.version.code
```

## 1. Install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.luma.standby.debug/app.luma.standby.MainActivity
```

The debug build carries the `app.luma.standby.debug` application id, so the
component name above is the debug one.

## 2. Phase 0 capability report

Open the **Diagnostics** tab. Expect:

* API level and HyperOS version strings.
* Supported refresh rates list (should include 1 and 120 on Xiaomi 17).
* ARR support = yes/unknown, suggested fps.
* Dream service registered = **yes**.
* Selected by user = `not set` until step 4.

Cross-check with the platform:

```bash
adb shell dumpsys display | grep -i -E "refresh|mode|brightness"
adb shell dumpsys SurfaceFlinger | grep -i -E "refresh|fps|mode"
adb shell dumpsys battery
```

## 3. Standby face

```bash
adb shell am start -n app.luma.standby.debug/app.luma.standby.StandbyActivity
```

Checks:

* Landscape, black background, no status bar, no navigation bar.
* Double tap exits.
* Minute changes exactly on the boundary; no visual jump when the digits change.
* With **Show seconds = On**, the seconds tick without drift over 5 minutes.
* With the device unplugged, the screen is allowed to sleep (no wakelock).

Frame accounting (should be ~60 frames/hour in static mode):

```bash
adb shell dumpsys gfxinfo app.luma.standby.debug | head -30
```

## 4. DreamService (the only automatic entry)

```bash
adb shell am start -a android.settings.DREAM_SETTINGS
```

Choose **Luma Standby**, then:

```bash
adb shell dumpsys dream | head -40
adb shell settings get secure screensaver_components
```

Expected: `screensaver_components` contains `app.luma.standby.debug/.dream.LumaDreamService`.

Trigger: charger attached + screen off + docked/idle. HyperOS may also honour
"screen saver" from the lock screen; note whichever behaviour is observed rather
than assuming.

## 5. Refresh-rate experiment

For each mode (Standard / Ultra low / Showcase), with the face static:

1. Diagnostics → **Sample for 30s**.
2. Record min / max / average / histogram.
3. Repeat with a sampling of `dumpsys display` every second in a shell loop:

```bash
for i in $(seq 1 30); do
  adb shell dumpsys display | grep -m1 -i "mActiveModeId\|refresh" ;
  sleep 1
done
```

Report the result honestly. If the platform keeps 60/120 Hz for a static window,
that is a finding, not a defect to hide.

## 6. Power run (30-60 min per configuration)

**Unplug before measuring.** adb over USB charges the phone, so a battery delta on
USB says nothing about consumption. Use wireless debugging instead:

```bash
adb tcpip 5555
adb connect <phone-ip>:5555      # keep USB for this step, then unplug
adb devices                      # expect <phone-ip>:5555  device
```

Requirements for `scripts/power-suite.sh`:

* device unlocked (a secure lock would make every sample the lock screen)
* device not charging (the harness refuses otherwise; `LUMA_ALLOW_CHARGING=1`
  runs it as a mechanics-only smoke test)
* screen kept awake by the harness itself

```bash
./scripts/power-suite.sh 60      # 5 groups x 60 min, samples every 30 s
```

Output: `.tooling/power-<timestamp>.csv` (per-group summary, including
`any_unfocused_sample`) plus `.csv.raw` (30 s timeline with level, temperature,
charge counter, frames, screen state, focus, refresh rate).

Fix room temperature, brightness, airplane mode, charger state. Then:

```bash
adb shell dumpsys batterystats --reset
# ... run 30-60 min ...
adb shell dumpsys batterystats --charged app.luma.standby.debug > luma-batterystats.txt
adb shell dumpsys battery
```

Configurations: native AOD · Luma Standard · Luma Ultra low · Luma Showcase ·
plain fullscreen clock. Three repeats; report medians.

## 7. Report template

```
device: Xiaomi 17 / HyperOS <version> / API <n>
mode: <name>
duration: <min>
avg current: <mA>      level delta: <%>
temperature: <start> -> <end> C
refresh rate observed: min <hz> / max <hz> / avg <hz>
frames drawn: <n>/hour
network traffic: 0 bytes
notes:
```
