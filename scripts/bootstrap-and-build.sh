#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
TOOLING=$ROOT/.tooling
SDK=$TOOLING/android-sdk
mkdir -p "$SDK" "$TOOLING"

# The DSH sandbox only permits writes inside the workspace, so every tool that
# would normally touch ~/.android or ~/.gradle is redirected here.
# Only ANDROID_USER_HOME: setting ANDROID_PREFS_ROOT too makes AGP abort with
# "several environment variables ... contain different paths".
export ANDROID_USER_HOME=$TOOLING/android-home
export GRADLE_USER_HOME=$TOOLING/gradle-home
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

step() { echo "=== $* at $(date +%H:%M:%S)"; }

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  step "download commandline-tools"
  curl -fsSL --retry 3 -o "$TOOLING/cmdline-tools.zip" https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip
  rm -rf "$SDK/cmdline-tools"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$TOOLING/cmdline-tools.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

step "accept licenses"
yes | sdkmanager --licenses > /dev/null || true

step "install sdk packages"
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"

step "gradle distribution"
if [ ! -x "$TOOLING/gradle-8.11.1/bin/gradle" ]; then
  curl -fsSL --retry 3 -o "$TOOLING/gradle.zip" https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
  unzip -q "$TOOLING/gradle.zip" -d "$TOOLING"
fi
GRADLE=$TOOLING/gradle-8.11.1/bin/gradle

echo "sdk.dir=$SDK" > local.properties

step "generate wrapper"
"$GRADLE" -q wrapper --gradle-version 8.11.1 || true

step "assembleDebug"
"$GRADLE" --no-daemon --console=plain :app:assembleDebug --stacktrace

step "done"
ls -l app/build/outputs/apk/debug/ || true
