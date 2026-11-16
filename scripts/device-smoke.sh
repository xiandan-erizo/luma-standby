#!/usr/bin/env bash
# Device smoke test: wait for authorization, install, launch, screenshot, dump.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
export ANDROID_HOME=$ROOT/.tooling/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export HOME=$ROOT/.tooling/home
SHOTS=$ROOT/.tooling/shots
mkdir -p "$SHOTS" "$HOME"

PKG=app.luma.standby.debug
ACT_MAIN=app.luma.standby.MainActivity
ACT_STANDBY=app.luma.standby.StandbyActivity

echo "=== waiting for authorization at $(date +%H:%M:%S)"
for n in $(seq 1 240); do
  state=$(adb devices | awk 'NR>1 && NF>1 {print $2}' | head -1)
  if [ "$state" = "device" ]; then echo "authorized"; break; fi
  sleep 2
done

state=$(adb devices | awk 'NR>1 && NF>1 {print $2}' | head -1)
if [ "$state" != "device" ]; then echo "STILL_UNAUTHORIZED"; exit 3; fi

echo "=== device"
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.mi.os.version.name
adb shell getprop ro.mi.os.version.code

echo "=== install"
adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -3

echo "=== launch main"
adb shell am start -W -n $PKG/$ACT_MAIN 2>&1 | tail -5
sleep 4
adb exec-out screencap -p > "$SHOTS/01-preview.png"
echo "shot 01 $(wc -c < "$SHOTS/01-preview.png") bytes"

echo "=== tabs"
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '>\n' | grep -o 'text="[^"]*"[^/]*bounds="[^"]*"' | head -30

echo "=== launch standby"
adb shell am start -W -n $PKG/$ACT_STANDBY 2>&1 | tail -5
sleep 4
adb exec-out screencap -p > "$SHOTS/02-standby.png"
echo "shot 02 $(wc -c < "$SHOTS/02-standby.png") bytes"

echo "=== display state"
adb shell dumpsys display 2>/dev/null | grep -i -E "mActiveModeId|refreshRate|DisplayDeviceInfo" | head -10
echo "=== gfxinfo"
adb shell dumpsys gfxinfo $PKG 2>/dev/null | grep -E "Total frames|Janky" | head -5
echo "=== dream"
adb shell settings get secure screensaver_components
echo "=== done at $(date +%H:%M:%S)"
