#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="${SCREENSHOT_OUTPUT_DIR:-$SCRIPT_DIR/sphinx/_static}"
APK="$PROJECT_DIR/android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk"
PACKAGE=app.trackglance.bridge
ACTIVITY="$PACKAGE/io.github.christianherget.trackglance.bridge.BridgeScreenshotActivity"

if [[ ! -s "$APK" ]]; then
  echo "Error: missing debug APK: $APK" >&2
  exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb is required to capture Android Bridge screenshots." >&2
  exit 1
fi

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
fi

old_font_scale="$("${ADB[@]}" shell settings get system font_scale | tr -d '\r')"
old_night_mode="$("${ADB[@]}" shell cmd uimode night | awk '{print $NF}' | tr -d '\r')"
old_rotation="$("${ADB[@]}" shell settings get system accelerometer_rotation | tr -d '\r')"

restore_device() {
  "${ADB[@]}" shell settings put system font_scale "$old_font_scale" >/dev/null 2>&1 || true
  "${ADB[@]}" shell settings put system accelerometer_rotation "$old_rotation" >/dev/null 2>&1 || true
  case "$old_night_mode" in
    yes|no|auto|custom_schedule|custom_bedtime)
      "${ADB[@]}" shell cmd uimode night "$old_night_mode" >/dev/null 2>&1 || true
      ;;
  esac
  "${ADB[@]}" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
}
trap restore_device EXIT

mkdir -p "$OUTPUT_DIR"
"${ADB[@]}" install -r "$APK" >/dev/null
"${ADB[@]}" shell settings put system font_scale 1.0
"${ADB[@]}" shell settings put system accelerometer_rotation 0

capture() {
  local mode=$1 dark=$2 target=$3
  "${ADB[@]}" shell cmd uimode night "$mode" >/dev/null
  "${ADB[@]}" shell am force-stop "$PACKAGE"
  "${ADB[@]}" shell am start -W -n "$ACTIVITY" --ez dark_theme "$dark" >/dev/null
  sleep 1
  "${ADB[@]}" exec-out screencap -p > "$target"
  if [[ ! -s "$target" ]]; then
    echo "Error: Android did not produce $target" >&2
    exit 1
  fi
}

capture no false "$OUTPUT_DIR/bridge_app_light.png"
capture yes true "$OUTPUT_DIR/bridge_app_dark.png"
python3 "$SCRIPT_DIR/validate_bridge_screenshots.py" "$OUTPUT_DIR"

echo "Captured native Android Bridge screenshots in $OUTPUT_DIR."
