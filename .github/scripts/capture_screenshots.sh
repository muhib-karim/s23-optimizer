#!/usr/bin/env bash
# Runs inside android-emulator-runner: installs the debug APK and captures each screen of the real app.
# The emulator has no Shizuku, so every change control shows its disabled, explained state.
set -euo pipefail
OUT=${1:-site/screenshots}
PKG=dev.muhib.s23optimizer
mkdir -p "$OUT"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c || true
# Usage access is an app-op the user grants in Settings; grant it here so the audit has app evidence.
adb shell appops set "$PKG" GET_USAGE_STATS allow
# A discharging battery at a plausible level, and a clean status bar.
adb emu power ac off || true
adb emu power status discharging || true
adb emu power capacity 78 || true
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter > /dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 > /dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false > /dev/null

shot() {
  local name=$1 wait=$2; shift 2
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@" > /dev/null
  sleep "$wait"
  # A crash would otherwise leave the launcher in the screenshot; fail with the crash log instead.
  if ! adb shell dumpsys activity activities | grep -E "(mResumedActivity|topResumedActivity).*$PKG" > /dev/null; then
    echo "::error::$name: $PKG is not in the foreground"
    adb logcat -d -b crash | tail -60
    exit 1
  fi
  adb exec-out screencap -p > "$OUT/$name.png"
  test -s "$OUT/$name.png"
  echo "captured $name"
}

shot onboarding 6 --es screen ONBOARDING
shot dashboard 25 --es screen DASHBOARD --ez autoAudit true
shot audit 6 --es screen AUDIT
shot optimise 6 --es screen OPTIMISE
shot measured 6 --es screen MEASURED
shot history 6 --es screen HISTORY
shot settings 6 --es screen SETTINGS
adb shell am broadcast -a com.android.systemui.demo -e command exit > /dev/null
ls -la "$OUT"
