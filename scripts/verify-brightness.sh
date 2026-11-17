#!/usr/bin/env bash
# Verify window brightness + night window on a real device.
# Requires the screen to be on and unlocked; waits up to 5 minutes for that.
set -uo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=$PWD/.tooling/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export HOME=$PWD/.tooling/home
PKG=app.luma.standby.debug
SHOTS=.tooling/shots
mkdir -p "$SHOTS"

state() { adb shell dumpsys display 2>/dev/null | grep -oE "state (ON|OFF)" | head -1; }
locked() { adb shell dumpsys window 2>/dev/null | grep -m1 "isKeyguardShowing=" ; }
override() { adb shell dumpsys display 2>/dev/null | grep -m1 "Display Brightness=" ; }
hzbrightness() { adb shell settings get system screen_brightness | tr -d '\r'; }

pin() {
  adb shell am broadcast --include-stopped-packages \
    -n $PKG/app.luma.standby.DebugSettingsReceiver \
    -a app.luma.standby.DEBUG_SETTINGS "$@" 2>&1 | tail -1
}

relaunch() {
  adb shell am force-stop $PKG > /dev/null 2>&1
  adb shell am start -n $PKG/app.luma.standby.DebugStandby > /dev/null 2>&1
  sleep 3
}

case_run() {
  local label=$1 shot=$2
  shift 2
  pin "$@" > /dev/null
  relaunch
  echo "-- $label"
  echo "   $(override)"
  adb exec-out screencap -p > "$SHOTS/$shot" 2>/dev/null
  echo "   shot $shot $(wc -c < "$SHOTS/$shot" 2>/dev/null)"
}

echo "waiting for an unlocked screen (up to 5 min)"
for i in $(seq 1 60); do
  st=$(state); lk=$(locked)
  if [ "$st" = "state ON" ] && echo "$lk" | grep -q "false"; then
    echo "screen on and unlocked after $((i*5))s"
    break
  fi
  sleep 5
done

st=$(state); lk=$(locked)
if [ "$st" != "state ON" ] || echo "$lk" | grep -q "true"; then
  echo "STILL_LOCKED state=[$st] $lk"
  exit 3
fi

echo "global brightness (system): $(hzbrightness)"
case_run "manual 0.20" "200-brightness-020.png" --ef brightness 0.2 --ez autoNight false
case_run "manual 0.80" "201-brightness-080.png" --ef brightness 0.8 --ez autoNight false

NOW_H=$(date +%H); NOW_M=$(date +%M)
S=$(( (10#$NOW_H * 60 + 10#$NOW_M + 1440 - 5) % 1440 ))
E=$(( (10#$NOW_H * 60 + 10#$NOW_M + 60) % 1440 ))
case_run "night window (brightness -1, night 0.06)" "202-night.png" \
  --ef brightness -1 --ez autoNight true --ei nightStart $S --ei nightEnd $E --ef nightBrightness 0.06

case_run "manual 0.20 again (manual wins over night)" "203-manual-wins.png" \
  --ef brightness 0.2 --ez autoNight true --ei nightStart $S --ei nightEnd $E

pin --ef brightness -1 --ez autoNight false > /dev/null
echo "global brightness after: $(hzbrightness)"
echo "done"
