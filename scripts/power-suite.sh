#!/usr/bin/env bash
# Power baseline harness for Luma Standby.
#
#   ./scripts/power-suite.sh 20     # minutes per group (default 15)
#
# Groups: A system AOD, B standby mid, C standby low, D standby black,
#         E standby night window.
set -uo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=$PWD/.tooling/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export HOME=$PWD/.tooling/home
PKG=app.luma.standby.debug
MINUTES=${1:-15}
OUT=.tooling/power-$(date +%Y%m%d-%H%M%S).csv

battery()    { adb shell dumpsys battery 2>/dev/null; }
level()      { battery | awk '/level:/ {print $2; exit}'; }
temp()       { battery | awk '/temperature:/ {print $2; exit}'; }
current_ua() { adb shell cat /sys/class/power_supply/battery/current_now 2>/dev/null | tr -d '\r' | head -1; }
frames()     { adb shell dumpsys gfxinfo $PKG 2>/dev/null | awk '/Total frames rendered/ {print $4; exit}'; }
refresh()    { adb shell dumpsys display 2>/dev/null | grep -oE "frameRatehz=[0-9.]+" | head -1 | cut -d= -f2; }

# Two things are needed on HyperOS: an explicit component (implicit broadcasts to
# a background/stopped app are dropped) and --include-stopped-packages. With only
# one of them the pin silently did nothing, so the result code is checked.
pin() {
  local out
  out=$(adb shell am broadcast --include-stopped-packages \
    -n $PKG/app.luma.standby.DebugSettingsReceiver \
    -a app.luma.standby.DEBUG_SETTINGS "$@" 2>&1)
  case "$out" in
    *result=1*) : ;;
    *) echo "  WARN pin failed: $out" ;;
  esac
  sleep 1
}

sample_group() {
  local name=$1 start_level start_temp start_frames start_time end_level end_temp end_frames end_time cur hz used
  start_level=$(level); start_temp=$(temp); start_frames=$(frames); start_time=$(date +%s)
  sleep $((MINUTES * 60))
  end_level=$(level); end_temp=$(temp); end_frames=$(frames); end_time=$(date +%s)
  cur=$(current_ua); hz=$(refresh)
  used=$((start_level - end_level))
  if [ -z "$hz" ]; then hz="n/a"; fi
  if [ -z "$cur" ]; then cur="n/a"; fi
  echo "$name,$start_level,$end_level,$used,$start_temp,$end_temp,$start_frames,$end_frames,$cur,$hz,$((end_time - start_time))" >> "$OUT"
  echo "  $name: level $start_level -> $end_level (-$used%), temp $start_temp -> $end_temp, frames $start_frames -> $end_frames, rate $hz Hz, current $cur uA"
}

mkdir -p .tooling/shots
echo "group,start_level,end_level,delta_pct,temp_start,temp_end,frames_start,frames_end,current_ua,rate_hz,seconds" > "$OUT"
echo "writing $OUT ($MINUTES min/group)"

echo "== A: system AOD (screen off)"
adb shell svc power stayon false > /dev/null 2>&1
adb shell input keyevent KEYCODE_SLEEP > /dev/null 2>&1
sample_group "A-system-aod"

echo "== B: standby, mid brightness"
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
adb shell wm dismiss-keyguard > /dev/null 2>&1
pin --es theme atlantic-rose --ef brightness 0.6 --ez autoNight false
adb shell am start -n $PKG/app.luma.standby.DebugStandby > /dev/null 2>&1
sleep 2
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "B-standby-mid"

echo "== C: standby, low brightness"
pin --ef brightness 0.15
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "C-standby-low"

echo "== D: standby, pure-black theme"
pin --es theme black-amber --ef brightness 0.6
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "D-standby-black"

echo "== E: standby, night window active"
NOW_H=$(date +%H); NOW_M=$(date +%M)
START_MIN=$(( (10#$NOW_H * 60 + 10#$NOW_M + 1440 - 5) % 1440 ))
END_MIN=$(( (10#$NOW_H * 60 + 10#$NOW_M + 60) % 1440 ))
pin --es theme atlantic-rose --ef brightness -1 --ez autoNight true --ei nightStart $START_MIN --ei nightEnd $END_MIN --ef nightBrightness 0.06
adb shell dumpsys gfxinfo $PKG reset > /dev/null 2>&1
sample_group "E-standby-night"

adb shell am force-stop $PKG > /dev/null 2>&1
echo "done: $OUT"
