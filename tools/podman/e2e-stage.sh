#!/bin/bash
set -euo pipefail
source /workspace/tools/podman/device-lib.sh
source /workspace/tools/podman/settings-navigation.sh
source /workspace/tools/podman/release-metadata.sh
load_release_metadata /workspace

: "${PEBBLE_PLATFORM:?PEBBLE_PLATFORM is required}"
bridge_apk=/workspace/android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk
pbw=/workspace/watchapp/build/watchapp.pbw
bridge_activity=app.trackglance.bridge/io.github.christianherget.trackglance.bridge.MainActivity
test -s "$bridge_apk"
test -s "$pbw"

capture_artifacts() {
  local original_status=$? retention_status=0
  set +e
  watch_screenshot "${PEBBLE_PLATFORM}-failure-watch"
  android_screenshot "${PEBBLE_PLATFORM}-failure-android"
  dump_ui
  cp /tmp/trackglance-window.xml "/artifacts/${PEBBLE_PLATFORM}-failure-ui.xml"
  adb_device_timeout 10 shell content query --uri "$STATUS_URI" > "/artifacts/${PEBBLE_PLATFORM}-failure-status.txt"
  adb_device_timeout 15 logcat -d > "/artifacts/${PEBBLE_PLATFORM}-logcat.txt" || retention_status=74
  (( original_status != 0 )) || original_status=$retention_status
  exit "$original_status"
}
trap capture_artifacts EXIT

wait_for_android 180
grant_locus_test_permissions
grant_coreapp_test_permissions
foreground_locus
set_emulator_test_location
adb_device uninstall app.trackglance.bridge >/dev/null 2>&1 || true
adb_device_timeout 180 install -r "$bridge_apk" >/dev/null
grant_bridge_test_notifications
adb_device shell am force-stop app.trackglance.bridge
adb_device shell am start -W -n "$bridge_activity" >/dev/null
wait_status locus_available true 45
wait_status recording_state STOPPED 30
wait_status bridge_version "$RELEASE_ANDROID_VERSION" 15
wait_status bridge_version_code "$RELEASE_ANDROID_CODE" 15
wait_status protocol_version "$RELEASE_PROTOCOL_VERSION" 15

relay_deadline=$((SECONDS + 45))
until relayctl status >/dev/null 2>&1; do
  (( SECONDS < relay_deadline )) || { echo "Pebble QEMU relay did not become ready" >&2; exit 1; }
  sleep 0.5
done

adb_device shell am broadcast \
  -a coredevices.coreapp.ADD_QEMU_WATCH \
  -n coredevices.coreapp/coredevices.coreapp.debug.QemuSetupReceiver \
  --es host 10.0.2.2 --ei port 12344 --ez connect true >/dev/null
wait_status watch_connected true 60
wait_status pebble_app_package coredevices.coreapp 15

adb_device_timeout 60 push "$pbw" /data/local/tmp/trackglance.pbw >/dev/null
# This is the debug CoreApp build, so run-as can place the PBW in its private cache with the
# correct owner and SELinux label. A shell-owned file under /sdcard/Android/data is unreadable to
# CoreApp on API 32 even when its Unix mode appears permissive.
adb_device shell run-as coredevices.coreapp \
  cp /data/local/tmp/trackglance.pbw cache/trackglance.pbw
adb_device shell am start -W \
  -a android.intent.action.VIEW \
  -d file:///data/user/0/coredevices.coreapp/cache/trackglance.pbw \
  -t application/octet-stream \
  -n coredevices.coreapp/.MainActivity >/dev/null

adb_device shell am start -W \
  -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
  -n "$bridge_activity" >/dev/null
wait_status watch_app_open true 90
wait_status watch_version "$RELEASE_WATCH_VERSION" 45
profiles=$(wait_nonempty_status locus_profiles 45)
printf 'Locus reported profiles: %s\n' "$(awk -F'|' '{print NF}' <<<"$profiles")"
recording_profile=$(cut -d'|' -f2 <<<"$profiles")
test -n "$recording_profile"
watch_screenshot "${PEBBLE_PLATFORM}-dashboard"

open_trackglance_settings
cp /tmp/trackglance-window.xml "/artifacts/${PEBBLE_PLATFORM}-settings.xml"
android_screenshot "${PEBBLE_PLATFORM}-settings"

tap_text "$recording_profile" 30
tap_text "Use watch steps for this activity" 30
tap_text "Done" 30

tap_text "General settings" 30
general_loaded=0
general_deadline=$((SECONDS + 30))
while (( SECONDS < general_deadline )); do
  dump_ui
  if grep -Fq 'resource-id="theme"' /tmp/trackglance-window.xml; then
    general_loaded=1
    break
  fi
  sleep 0.5
done
if (( ! general_loaded )); then
  echo "${PEBBLE_PLATFORM} General settings did not finish loading" >&2
  exit 1
fi

toggle_watch_steps_source() {
  open_trackglance_settings
  tap_text "$recording_profile" 30
  tap_text "Use watch steps for this activity" 30
  tap_text "Done" 30
  tap_text "Save" 30
  adb_device shell am start -W \
    -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
    -n "$bridge_activity" >/dev/null
  wait_status watch_app_open true 30
}

mute_locus_watch_notifications() {
  # API 34 Locus recording updates are mirrorable notifications. Keep its notification cards
  # from covering TrackGlance controls; TrackGlance's own outage channel remains enabled.
  local api coordinates attempt x y
  api=$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')
  (( api >= 34 )) || return 0
  adb_device shell am start -W -n coredevices.coreapp/.MainActivity >/dev/null
  tap_text "Notifications" 30 exact
  tap_text "Search" 15 exact
  adb_device shell input text Locus
  tap_text "Locus Map" 15 exact
  for ((attempt = 0; attempt < 5; attempt++)); do
    dump_ui
    coordinates=$(python3 - <<'PYCODE'
import re
import xml.etree.ElementTree as ET
root = ET.parse('/tmp/trackglance-window.xml').getroot()
assert any(n.get('text') == 'App Notifications' for n in root.iter('node'))
assert any(n.get('text') == 'Locus Map' for n in root.iter('node'))
control = next(n for n in root.iter('node') if n.get('checkable') == 'true')
if control.get('checked') == 'false':
    print('muted')
else:
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', control.get('bounds')))
    print((x1 + x2) // 2, (y1 + y2) // 2)
PYCODE
)
    if [[ "$coordinates" == muted ]]; then
      android_screenshot "${PEBBLE_PLATFORM}-locus-notifications-muted"
      adb_device shell am start -W -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
        -n "$bridge_activity" >/dev/null
      return 0
    fi
    read -r x y <<< "$coordinates"
    adb_device shell input tap "$x" "$y"
    sleep 1
  done
  echo 'Locus notification mirroring did not switch off' >&2
  return 1
}

enable_supervision() {
  adb_device shell am start -W -n "$bridge_activity" >/dev/null
  local scroll
  for ((scroll = 0; scroll < 8; scroll++)); do
    if tap_text "Auto-start" 2 exact; then
      break
    fi
    adb_device shell input swipe 540 1800 540 600 350
  done
  wait_status supervision_mode AUTO_START 10
}

screen_off_supervision_recovery() {
  local expected_sources=$1
  wait_status supervision_sources "$expected_sources" 30
  wait_status supervision_phase ACTIVE 15
  watch_button back
  wait_status watch_app_open false 10
  wait_status supervision_phase CLOSED 10
  adb_device shell input keyevent KEYCODE_HOME
  adb_device shell input keyevent KEYCODE_SLEEP
  # Do not query the debug provider or otherwise activate Bridge during this interval.
  sleep 40
  adb_device shell input keyevent KEYCODE_WAKEUP
  adb_device shell wm dismiss-keyguard
  wait_status watch_app_open true 10
  wait_status supervision_phase ACTIVE 10
  adb_device shell am start -W -n "$bridge_activity" >/dev/null
  watch_screenshot "${PEBBLE_PLATFORM}-supervision-recovered"
}

run_step_acceptance() {
  mute_locus_watch_notifications
  enable_supervision
  foreground_locus
  set_emulator_test_location
  relayctl steps 1000 >/dev/null
  watch_screenshot "${PEBBLE_PLATFORM}-menu-stopped"
  local start_result
  start_result=$(adb_device shell content call --uri "$STATUS_URI" \
    --method acceptance-start-recording --arg "$recording_profile")
  grep -Fq 'result=requested' <<<"$start_result"
  wait_status recording_state RECORDING 30
  wait_nonempty_status active_profile 15 >/dev/null
  wait_status watch_steps 0 30

  relayctl steps 1012 >/dev/null
  wait_status watch_steps 12 80
  watch_screenshot "${PEBBLE_PLATFORM}-recording-dashboard"

  watch_button select
  sleep 1
  watch_screenshot "${PEBBLE_PLATFORM}-pause-menu"
  watch_button select
  watch_screenshot "${PEBBLE_PLATFORM}-pause-selected"
  wait_status recording_state PAUSED 30
  relayctl steps 5 >/dev/null
  wait_status watch_steps 17 80

  toggle_watch_steps_source
  wait_status watch_steps NULL 30
  watch_screenshot "${PEBBLE_PLATFORM}-steps-unavailable"
  toggle_watch_steps_source
  wait_status watch_steps 17 30
  watch_screenshot "${PEBBLE_PLATFORM}-steps-recovered"
}

if [[ "$PEBBLE_PLATFORM" == "emery" ]]; then
  tap_text "Send watch heart rate to Locus" 30
  tap_text "Done" 30
  tap_text "Save" 30
  adb_device shell am start -W \
    -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
    -n "$bridge_activity" >/dev/null
  wait_status watch_app_open true 30

  run_step_acceptance
  watch_button select
  sleep 1
  watch_button select
  wait_status recording_state RECORDING 30

  watch_heart_rate_deadline=$((SECONDS + 30))
  while [[ "$(status_value watch_heart_rate || true)" != 123 ]]; do
    (( SECONDS < watch_heart_rate_deadline )) || break
    relayctl heart-rate 123 --quality excellent >/dev/null
    sleep 2
  done
  wait_status watch_heart_rate 123 3
  heart_rate_deadline=$((SECONDS + 20))
  while [[ "$(status_value locus_heart_rate || true)" != 123 ]]; do
    (( SECONDS < heart_rate_deadline )) || break
    sleep 2
    relayctl heart-rate 123 --quality excellent >/dev/null
  done
  wait_status locus_heart_rate 123 3
  screen_off_supervision_recovery "HEART_RATE|STEPS"

  watch_button select
  sleep 1
  watch_button down
  watch_button down
  watch_button select
  # Allow the dynamically constructed Emery waypoint submenu to become interactive on loaded hosts.
  sleep 2
  # Emery groups quick and dictated waypoints in a submenu; choose the first (quick) entry.
  watch_button select
  wait_status last_command ADD_WAYPOINT 30
  wait_status last_command_result OK 15
  wait_status recording_state RECORDING 10
  # The Emery waypoint submenu remains above the dashboard after its parent controls window closes.
  watch_button back
  sleep 1

  watch_button select
  sleep 1
  watch_button down
  watch_button select
  sleep 1
  watch_button select
  wait_status recording_state STOPPED 30
  wait_status last_command STOP_SAVE 15
  wait_status last_command_result OK 15
  watch_screenshot emery-stopped
else
  if grep -q "Send watch heart rate to Locus" /tmp/trackglance-window.xml; then
    echo "Pebble Round 2 incorrectly exposes watch-originated heart rate settings" >&2
    exit 1
  fi
  tap_text "Done" 30
  tap_text "Save" 30
  adb_device shell am start -W \
    -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
    -n "$bridge_activity" >/dev/null
  wait_status watch_app_open true 30
  run_step_acceptance
  watch_button select
  watch_screenshot gabbro-menu
  relayctl heart-rate 123 --quality excellent >/dev/null
  sleep 3
  value=$(status_value watch_heart_rate || true)
  if [[ -n "$value" && "$value" != "NULL" ]]; then
    echo "Pebble Round 2 forwarded unsupported watch-originated heart rate" >&2
    exit 1
  fi
  watch_button back
  watch_button select
  sleep 1
  watch_button select
  wait_status recording_state RECORDING 30
  screen_off_supervision_recovery "STEPS"
  watch_button select
  sleep 1
  watch_button down
  watch_button select
  sleep 1
  watch_button select
  wait_status recording_state STOPPED 30
fi

android_screenshot "${PEBBLE_PLATFORM}-final-android"
adb_device_timeout 10 shell content query --uri "$STATUS_URI" > "/artifacts/${PEBBLE_PLATFORM}-final-status.txt"
# The emulator uses a verbose boot log and can exceed the artifact timeout after all behavioral
# assertions have passed. Keep a bounded diagnostic tail without converting that into a failure.
adb_device_timeout 30 logcat -d -t 20000 > "/artifacts/${PEBBLE_PLATFORM}-logcat.txt" || true
trap - EXIT
