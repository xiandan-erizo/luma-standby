#!/usr/bin/env bash
# Power baseline harness (v2) for Luma Standby.
#
#   ./scripts/power-suite.sh 60            # minutes per group (device unplugged)
#   LUMA_ALLOW_CHARGING=1 ./scripts/power-suite.sh 1   # mechanics smoke test only
#
# Why unplugged: adb over USB charges the phone, so a battery-delta measurement on
# USB is meaningless. Use wireless adb (adb tcpip 5555, adb connect IP:5555),
# unplug, then run. LUMA_ALLOW_CHARGING=1 only smoke-tests the script.
#
# Groups: B standby mid, C standby low, D standby black, E standby night,
#         A system AOD last (it puts the screen to sleep).
set -uo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=$PWD/.tooling/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export HOME=$PWD/.tooling/home
PKG=app.luma.standby.debug
MINUTES=${1:-15}
INTERVAL=30
ALLOW_CHARGING=${LUMA_ALLOW_CHARGING:-0}
OUT=.tooling/power-$(date +%Y%m%d-%H%M%S).csv

battery()      { adb shell dumpsys battery 2>/dev/null; }
level()        { battery | awk '/ level:/ {print $2; exit}'; }
temp()         { battery | awk '/ temperature:/ {print $2; exit}'; }
charge_uah()   { battery | awk '/Charge counter:/ {print $3; exit}'; }
charging()     { battery | awk '/AC powered|USB powered|Wireless powered/ {print $3}' | grep -c true; }
screen_state() { adb shell dumpsys display 2>/dev/null | grep -oE "state (ON|OFF)" | head -1 | cut -d' ' -f2; }
frames()       { adb shell dumpsys gfxinfo $PKG 2>/dev/null | awk '/Total frames rendered/ {print $4; exit}'; }
rate_hz()      { adb shell dumpsys display 2>/dev/null | grep -oE "frameRateHz=[0-9.]+" | head -1 | cut -d= -f2; }
focused()      { adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | grep -c DebugStandby; }

pin() {
  adb shell am broadcast --include-stopped-packages \
    -n $PKG/app.luma.standby.DebugSettingsReceiver \
    -a app.luma.standby.DEBUG_SETTINGS "$@" 2>&1 | tail -1
}

preflight() {
  local plugged locked
  locked=$(adb shell dumpsys window 2>/dev/null | grep -m1 "isKeyguardShowing=true" | wc -l)
  if [ "$locked" != "0" ]; then
    echo "REFUSING: device is locked - the standby face cannot be focused, so every"
    echo "sample would measure the lock screen. Unlock first (or set LUMA_ALLOW_LOCKED=1)."
    exit 5
  fi
  plugged=$(charging)
  if [ "$plugged" != "0" ]; then
    if [ "$ALLOW_CHARGING" != "1" ]; then
      echo "REFUSING: device is charging - battery delta would be meaningless."
      echo "Use wireless adb and unplug, or set LUMA_ALLOW_CHARGING=1 for a mechanics-only run."
      exit 4
    fi
    echo "WARNING: charging; numbers are mechanics-only, not power measurements."
  fi
  adb shell svc power stayon true > /dev/null 2>&1
}

sample_group() {
  local name=$1 start_epoch end_epoch row invalid=0
  local start_level end_level start_temp end_temp start_charge end_charge start_frames end_frames hz
  start_epoch=$(date +%s)
  start_level=$(level); start_temp=$(temp); start_charge=$(charge_uah); start_frames=$(frames)
  echo "  sampling $name for $MINUTES min"
  end_epoch=$((start_epoch + MINUTES * 60))
  while [ "$(date +%s)" -lt "$end_epoch" ]; do
    sleep "$INTERVAL"
    hz=$(rate_hz)
    row="$name,$(date +%s),$(level),$(temp),$(charge_uah),$(frames),$(screen_state),$(focused),$hz"
    echo "$row" >> "$OUT.raw"
    if [ "$(echo "$row" | cut -d, -f8)" = "0" ]; then invalid=1; fi
  done
  end_level=$(level); end_temp=$(temp); end_charge=$(charge_uah); end_frames=$(frames)
  local seconds=$(( $(date +%s) - start_epoch ))
  local used=$((start_level - end_level))
  local charge_delta=$(( (end_charge - start_charge) / 1000 ))
  echo "$name,$start_level,$end_level,$used,$start_temp,$end_temp,$start_frames,$end_frames,$charge_delta,$seconds,$invalid" >> "$OUT"
  echo "  $name: level $start_level -> $end_level (delta $used%), temp $start_temp -> $end_temp, charge $charge_delta mAh, frames [$start_frames -> $end_frames], invalid=$invalid"
}

mkdir -p .tooling
echo "group,start_level,end_level,delta_pct,temp_start,temp_end,frames_start,frames_end,charge_mah,seconds,any_unfocused_sample" > "$OUT"
: > "$OUT.raw"
echo "writing $OUT ($MINUTES min/group, sample every $INTERVAL s)"
preflight

echo "== B: standby, mid brightness"
pin --es theme atlantic-rose --ef brightness 0.6 --ez autoNight false > /dev/null
adb shell am force-stop $PKG > /dev/null 2>&1
adb shell am start -n $PKG/app.luma.standby.DebugStandby > /dev/null 2>&1
sleep 3
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "B-standby-mid"

echo "== C: standby, low brightness"
pin --ef brightness 0.15 > /dev/null
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "C-standby-low"

echo "== D: standby, pure-black theme"
pin --es theme black-amber --ef brightness 0.6 > /dev/null
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "D-standby-black"

echo "== E: standby, night window"
NOW_H=$(date +%H); NOW_M=$(date +%M)
S=$(( (10#$NOW_H * 60 + 10#$NOW_M + 1440 - 5) % 1440 ))
E=$(( (10#$NOW_H * 60 + 10#$NOW_M + 60) % 1440 ))
pin --es theme atlantic-rose --ef brightness -1 --ez autoNight true --ei nightStart $S --ei nightEnd $E --ef nightBrightness 0.06 > /dev/null
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "E-standby-night"

echo "== A: system AOD (screen off)"
adb shell am force-stop $PKG > /dev/null 2>&1
adb shell input keyevent KEYCODE_SLEEP > /dev/null 2>&1
sample_group "A-system-aod"

adb shell svc power stayon false > /dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
echo "done: $OUT (raw timeline: $OUT.raw)"
