#!/usr/bin/env bash
# Map requested window brightness -> Display Brightness as reported by dumpsys.
set -uo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=$PWD/.tooling/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export HOME=$PWD/.tooling/home
PKG=app.luma.standby.debug

pin() {
  adb shell am broadcast --include-stopped-packages \
    -n $PKG/app.luma.standby.DebugSettingsReceiver \
    -a app.luma.standby.DEBUG_SETTINGS "$@" > /dev/null 2>&1
}
window_sbrt() { adb shell dumpsys window windows 2>/dev/null | grep -m1 "pkg = $PKG" | grep -oE "sbrt=[0-9.-]+" ; }
applied() { adb shell dumpsys display 2>/dev/null | grep -m1 "Display Brightness=" | cut -d= -f2; }

echo "requested,window_attr,applied_brightness"
for v in 0.05 0.20 0.50 1.00; do
  pin --ef brightness $v --ez autoNight false
  adb shell am force-stop $PKG > /dev/null 2>&1
  adb shell am start -n $PKG/app.luma.standby.DebugStandby > /dev/null 2>&1
  sleep 3
  echo "$v,$(window_sbrt),$(applied)"
done
