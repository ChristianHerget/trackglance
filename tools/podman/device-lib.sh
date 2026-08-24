#!/bin/bash

ADB_SERIAL=${ADB_SERIAL:-127.0.0.1:5555}
STATUS_URI=content://app.locuspebble.bridge.debug-status/status
EMULATOR_TEST_LATITUDE=50.9662
EMULATOR_TEST_LONGITUDE=10.3065
EMULATOR_CONSOLE_TOKEN=/run/locuspebble/emulator-console-auth-token
LOCUS_START_ACTIVITY=menion.android.locus/com.asamm.android.library.androidCore.features.startScreen.StartScreen

adb_device() {
  adb -s "$ADB_SERIAL" "$@"
}

adb_device_timeout() {
  local duration=$1
  shift
  timeout "$duration" adb -s "$ADB_SERIAL" "$@"
}

set_emulator_test_location() {
  # Locus rejects Android shell test-provider points as mock input. Use the pod-private Emulator
  # console so the guest receives the same non-mock GPS fix as `adb emu geo fix`.
  adb_device shell cmd location set-location-enabled true
  adb_device shell cmd location providers remove-test-provider gps >/dev/null 2>&1 || true
  test -s "$EMULATOR_CONSOLE_TOKEN"
  python3 /workspace/tools/podman/emulator-console.py \
    --token-file "$EMULATOR_CONSOLE_TOKEN" \
    --latitude "$EMULATOR_TEST_LATITUDE" \
    --longitude "$EMULATOR_TEST_LONGITUDE"
}

foreground_locus() {
  adb_device shell am start -W -n "$LOCUS_START_ACTIVITY" >/dev/null
  local deadline=$((SECONDS + ${1:-30}))
  while (( SECONDS < deadline )); do
    if adb_device shell dumpsys activity activities \
      | grep -Eq 'topResumedActivity=.* menion\.android\.locus/'; then
      # A cold Locus activity can be resumed before its map and recording engine are ready. START
      # is silently lost in that interval on slower API-32 hosts, so preserve the observed
      # launch-wait-intent compatibility sequence after the observable foreground transition.
      sleep "${LOCUS_FOREGROUND_SETTLE_SECONDS:-10}"
      return 0
    fi
    sleep 0.25
  done
  echo "Locus did not become the foreground activity within ${1:-30} seconds" >&2
  return 1
}

wait_for_android() {
  local deadline=$((SECONDS + ${1:-180}))
  while (( SECONDS < deadline )); do
    # adb connect reports some connection failures with status zero. Retry it as part of
    # readiness polling so a runner that races the emulator proxy does not keep an
    # unregistered TCP serial for the entire timeout.
    timeout 10 adb connect "$ADB_SERIAL" >/dev/null 2>&1 || true
    if [[ "$(adb_device_timeout 5 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      adb_device shell settings put global window_animation_scale 0
      adb_device shell settings put global transition_animation_scale 0
      adb_device shell settings put global animator_duration_scale 0
      set_emulator_test_location
      return
    fi
    sleep 1
  done
  echo "Android did not finish booting within ${1:-180} seconds" >&2
  return 1
}

grant_locus_test_permissions() {
  adb_device shell pm grant menion.android.locus android.permission.ACCESS_COARSE_LOCATION
  adb_device shell pm grant menion.android.locus android.permission.ACCESS_FINE_LOCATION
  adb_device shell pm grant menion.android.locus android.permission.ACCESS_BACKGROUND_LOCATION
  # The pinned fixture targets modern Android but retains legacy external storage on API 32. Without
  # these grants its first-run screen reports that the working directory cannot be created.
  adb_device shell pm grant menion.android.locus android.permission.READ_EXTERNAL_STORAGE
  adb_device shell pm grant menion.android.locus android.permission.WRITE_EXTERNAL_STORAGE
  # Locus otherwise opens the battery-optimization settings page on the first recording command
  # and rejects that background API request before the user can respond.
  adb_device shell dumpsys deviceidle whitelist +menion.android.locus >/dev/null
}

grant_coreapp_test_permissions() {
  adb_device shell cmd notification allow_listener \
    coredevices.coreapp/io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
}

complete_locus_onboarding() {
  local deadline=$((SECONDS + ${1:-90})) working_directory_retries=0
  while (( SECONDS < deadline )); do
    dump_ui || true
    if grep -Fq 'resource-id="menion.android.locus:id/drawer_layout"' \
      /tmp/locuspebble-window.xml; then
      return 0
    fi
    if grep -Fq 'text="START"' /tmp/locuspebble-window.xml; then
      tap_text START 10
    elif grep -Fq 'text="Problem with working directory"' /tmp/locuspebble-window.xml; then
      working_directory_retries=$((working_directory_retries + 1))
      if (( working_directory_retries > 3 )); then
        echo "Locus could not initialize its API-32 working directory after three clean relaunches" >&2
        return 1
      fi
      tap_text CLOSE 10
      adb_device shell am force-stop menion.android.locus
      sleep 2
      adb_device shell monkey -p menion.android.locus 1 >/dev/null
    fi
    sleep 0.5
  done
  echo "Locus onboarding did not reach the map within ${1:-60} seconds" >&2
  return 1
}

complete_coreapp_onboarding() {
  local deadline=$((SECONDS + ${1:-90}))
  while (( SECONDS < deadline )); do
    dump_ui || true
    if grep -Fq 'text="Apps"' /tmp/locuspebble-window.xml; then
      return 0
    fi
    if grep -Fq 'text="Get Started"' /tmp/locuspebble-window.xml; then
      tap_text "Get Started" 10
    elif grep -Fq 'text="Connect a Pebble"' /tmp/locuspebble-window.xml \
      || grep -Fq 'text="Connect a Pebble!"' /tmp/locuspebble-window.xml; then
      tap_text "Connect a Pebble" 10
    elif grep -Fq 'text="I have a:"' /tmp/locuspebble-window.xml; then
      tap_text Watch 10
    elif grep -Fq 'text="Skip"' /tmp/locuspebble-window.xml; then
      tap_text Skip 10
    elif grep -Fq 'text="Finished"' /tmp/locuspebble-window.xml; then
      tap_text Finished 10
    elif grep -Fq 'text="Get Started!"' /tmp/locuspebble-window.xml; then
      adb_device shell input swipe 540 2100 540 400 200
    else
      adb_device shell input swipe 540 2100 540 400 200
    fi
    sleep 0.5
  done
  echo "CoreApp onboarding did not reach Watch Home within ${1:-90} seconds" >&2
  return 1
}

status_value() {
  local field=$1
  adb_device_timeout 5 shell content query --uri "$STATUS_URI" --projection "$field" 2>/dev/null \
    | sed -n "s/^Row: 0 ${field}=//p" | tr -d '\r'
}

wait_status() {
  local field=$1 expected=$2 timeout=${3:-45}
  local deadline=$((SECONDS + timeout)) value
  while (( SECONDS < deadline )); do
    value=$(status_value "$field" || true)
    [[ "$value" == "$expected" ]] && return
    sleep 0.5
  done
  echo "Timed out waiting for status $field=$expected; last value was ${value:-<missing>}" >&2
  return 1
}

wait_nonempty_status() {
  local field=$1 timeout=${2:-45}
  local deadline=$((SECONDS + timeout)) value
  while (( SECONDS < deadline )); do
    value=$(status_value "$field" || true)
    if [[ -n "$value" && "$value" != "NULL" ]]; then
      printf '%s\n' "$value"
      return
    fi
    sleep 0.5
  done
  echo "Timed out waiting for non-empty status $field" >&2
  return 1
}

relayctl() {
  python3 /workspace/tools/podman/relayctl.py --socket /run/locuspebble/relay.sock "$@"
}

watch_button() {
  relayctl button "$1" >/dev/null
  # The relay can inject consecutive presses much faster than PebbleOS can apply menu-window
  # selection changes. Real hardware naturally supplies this gap between button presses.
  sleep 0.35
}

watch_screenshot() {
  local name=$1
  timeout 4 bash -c \
    "exec 3<>/dev/tcp/127.0.0.1/12348; printf 'screendump /artifacts/${name}.ppm\\n' >&3; sleep 0.5" \
    >/dev/null 2>&1 || true
  test -s "/artifacts/${name}.ppm"
}

android_screenshot() {
  # `screencap` can finish writing the PNG but keep its adb stream open while the software-rendered
  # emulator is busy. The artifact, rather than timely process teardown, is the required result.
  adb_device_timeout 30 exec-out screencap -p > "/artifacts/$1.png" || true
  test -s "/artifacts/$1.png"
}

dump_ui() {
  adb_device_timeout 15 shell uiautomator dump /sdcard/locuspebble-window.xml >/dev/null
  adb_device_timeout 10 pull /sdcard/locuspebble-window.xml /tmp/locuspebble-window.xml >/dev/null
}

tap_text() {
  local needle=$1
  local timeout=${2:-30}
  local deadline=$((SECONDS + timeout)) coordinates
  while (( SECONDS < deadline )); do
    dump_ui || true
    coordinates=$(python3 - "$needle" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
needle = sys.argv[1].casefold()
try:
    root = ET.parse('/tmp/locuspebble-window.xml').getroot()
except Exception:
    raise SystemExit
parents = {child: parent for parent in root.iter() for child in parent}
for node in root.iter('node'):
    text = (node.attrib.get('text','') + ' ' + node.attrib.get('content-desc','')).casefold()
    if needle in text:
        target = node
        while target is not None and target.attrib.get('clickable') != 'true':
            target = parents.get(target)
        if target is None:
            target = node
        bounds = [int(value) for value in re.findall(r'\d+', target.attrib.get('bounds',''))]
        if len(bounds) == 4:
            print((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
            break
PY
)
    if [[ -n "$coordinates" ]]; then
      adb_device shell input tap $coordinates
      return
    fi
    sleep 0.5
  done
  echo "Timed out waiting for Android UI text: $needle" >&2
  return 1
}
