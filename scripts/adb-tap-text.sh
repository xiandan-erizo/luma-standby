#!/usr/bin/env bash
# Tap the center of the first UI node whose text matches exactly.
set -uo pipefail
export HOME=$PWD/.tooling/home
ADB=$PWD/.tooling/android-sdk/platform-tools/adb
TARGET=$1
$ADB shell uiautomator dump /sdcard/tap.xml > /dev/null 2>&1
$ADB shell cat /sdcard/tap.xml > /tmp/tap.xml 2>/dev/null
COORDS=$(python3 - "$TARGET" <<'PY'
import re, sys
target = sys.argv[1]
xml = open('/tmp/tap.xml', encoding='utf-8').read()
best = None
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t = m.group(1)
    if t.strip() == target:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        best = ((x1 + x2) // 2, (y1 + y2) // 2)
        break
if best is None:
    sys.exit(1)
print(best[0], best[1])
PY
)
if [ -z "$COORDS" ]; then
  echo "NOT_FOUND: $TARGET"
  exit 2
fi
echo "tap $TARGET at $COORDS"
$ADB shell input tap $COORDS
