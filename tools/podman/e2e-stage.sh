#!/bin/bash
set -euo pipefail
source /workspace/tools/podman/device-lib.sh

: "${PEBBLE_PLATFORM:?PEBBLE_PLATFORM is required}"
bridge_apk=/workspace/android/app/build/outputs/apk/debug/locuspebble-bridge-debug.apk
pbw=/workspace/watchapp/build/watchapp.pbw
bridge_activity=app.locuspebble.bridge/io.github.christianherget.locuspebble.bridge.MainActivity
test -s "$bridge_apk"
test -s "$pbw"

capture_artifacts() {
  set +e
  android_screenshot "${PEBBLE_PLATFORM}-failure-android"
  dump_ui
  cp /tmp/locuspebble-window.xml "/artifacts/${PEBBLE_PLATFORM}-failure-ui.xml"
  adb_device_timeout 10 shell content query --uri "$STATUS_URI" > "/artifacts/${PEBBLE_PLATFORM}-failure-status.txt"
  adb_device_timeout 15 logcat -d > "/artifacts/${PEBBLE_PLATFORM}-logcat.txt"
  true
}
trap capture_artifacts EXIT

wait_for_android 180
grant_locus_test_permissions
grant_coreapp_test_permissions
foreground_locus
set_emulator_test_location
adb_device uninstall app.locuspebble.bridge >/dev/null 2>&1 || true
adb_device_timeout 180 install -r "$bridge_apk" >/dev/null
adb_device shell am force-stop app.locuspebble.bridge
adb_device shell am start -W -n "$bridge_activity" >/dev/null
wait_status locus_available true 45
wait_status recording_state STOPPED 30
wait_status bridge_version 0.1.9 15
wait_status bridge_version_code 10 15
wait_status protocol_version 3 15

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

adb_device_timeout 60 push "$pbw" /data/local/tmp/locuspebble.pbw >/dev/null
# This is the debug CoreApp build, so run-as can place the PBW in its private cache with the
# correct owner and SELinux label. A shell-owned file under /sdcard/Android/data is unreadable to
# CoreApp on API 32 even when its Unix mode appears permissive.
adb_device shell run-as coredevices.coreapp \
  cp /data/local/tmp/locuspebble.pbw cache/locuspebble.pbw
adb_device shell am start -W \
  -a android.intent.action.VIEW \
  -d file:///data/user/0/coredevices.coreapp/cache/locuspebble.pbw \
  -t application/octet-stream \
  -n coredevices.coreapp/.MainActivity >/dev/null

adb_device shell am start -W \
  -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
  -n "$bridge_activity" >/dev/null
wait_status watch_app_open true 90
wait_status watch_version 0.1.9 45
profiles=$(wait_nonempty_status locus_profiles 45)
printf 'Locus reported profiles: %s\n' "$(awk -F'|' '{print NF}' <<<"$profiles")"
watch_screenshot "${PEBBLE_PLATFORM}-dashboard"

adb_device shell am start -W -a android.intent.action.VIEW \
  -d pebble://navbar/apps -n coredevices.coreapp/.MainActivity >/dev/null
# A fresh CoreApp starts at "Get Started"; older prepared states may resume at "Connect a Pebble!"
# or the final carousel. QEMU is already connected, so finish whichever bounded onboarding state is
# visible, then resend the Apps deep link because the first one was consumed by onboarding.
complete_coreapp_onboarding 90
adb_device shell am start -W -a android.intent.action.VIEW \
  -d pebble://navbar/apps -n coredevices.coreapp/.MainActivity >/dev/null
# CoreApp keeps the onboarding navigation graph for the lifetime of the activity, so the first
# post-onboarding deep link can leave Watch Home on its default Faces tab. Select Apps explicitly.
tap_text "Apps" 15
tap_text "LocusPebble" 30
tap_text "Settings" 30
settings_deadline=$((SECONDS + 30))
while (( SECONDS < settings_deadline )); do
  dump_ui
  grep -Fq 'text="THEME"' /tmp/locuspebble-window.xml && break
  sleep 0.5
done
cp /tmp/locuspebble-window.xml "/artifacts/${PEBBLE_PLATFORM}-settings.xml"
android_screenshot "${PEBBLE_PLATFORM}-settings"
if ! grep -Fq 'text="THEME"' /tmp/locuspebble-window.xml; then
  echo "${PEBBLE_PLATFORM} settings did not finish loading" >&2
  exit 1
fi

if [[ "$PEBBLE_PLATFORM" == "emery" ]]; then
  tap_text "Send watch heart rate to Locus" 30
  tap_text "Save" 30
  adb_device shell am start -W \
    -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
    -n "$bridge_activity" >/dev/null
  wait_status watch_app_open true 30

  # On this API-32 target, starting Locus's recording FGS while Locus is backgrounded denies that
  # service location/sensor access. The Locus API documents only a broadcast, not a foreground
  # precondition. Foreground Locus before the watch issues START; every command below still
  # originates on the watch.
  foreground_locus
  # Refresh the console fix immediately before START. The emulator retains it until Locus registers
  # its real GPS listener; unlike a shell test provider, Locus accepts this as GPS input.
  set_emulator_test_location
  watch_button select
  watch_screenshot emery-menu-stopped
  watch_button select
  wait_status recording_state RECORDING 30
  wait_nonempty_status active_profile 15 >/dev/null

  relayctl heart-rate 123 --quality excellent >/dev/null
  wait_status watch_heart_rate 123 30
  heart_rate_deadline=$((SECONDS + 20))
  while [[ "$(status_value locus_heart_rate || true)" != 123 ]]; do
    (( SECONDS < heart_rate_deadline )) || break
    sleep 2
    relayctl heart-rate 123 --quality excellent >/dev/null
  done
  wait_status locus_heart_rate 123 3

  watch_button select
  # QEMU accepts injected buttons faster than the watchapp can construct and display its menu.
  # Leave the same human-scale gap a real watch supplies before selecting the first action.
  sleep 1
  watch_button select
  wait_status recording_state PAUSED 30
  watch_button select
  sleep 1
  watch_button select
  wait_status recording_state RECORDING 30

  watch_button select
  sleep 1
  watch_button down
  watch_button down
  watch_button down
  watch_button select
  wait_status last_command ADD_WAYPOINT 30
  wait_status last_command_result OK 15
  wait_status recording_state RECORDING 10

  watch_button select
  sleep 1
  watch_button down
  watch_button down
  watch_button select
  watch_button select
  wait_status recording_state STOPPED 30
  wait_status last_command STOP_SAVE 15
  wait_status last_command_result OK 15
  watch_screenshot emery-stopped
else
  if grep -q "Send watch heart rate to Locus" /tmp/locuspebble-window.xml; then
    echo "Pebble Round 2 incorrectly exposes watch-originated heart rate settings" >&2
    exit 1
  fi
  adb_device shell input keyevent 4
  adb_device shell am start -W \
    -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
    -n "$bridge_activity" >/dev/null
  wait_status watch_app_open true 30
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
fi

android_screenshot "${PEBBLE_PLATFORM}-final-android"
adb_device_timeout 10 shell content query --uri "$STATUS_URI" > "/artifacts/${PEBBLE_PLATFORM}-final-status.txt"
# The emulator uses a verbose boot log and can exceed the artifact timeout after all behavioral
# assertions have passed. Keep a bounded diagnostic tail without converting that into a failure.
adb_device_timeout 30 logcat -d -t 20000 > "/artifacts/${PEBBLE_PLATFORM}-logcat.txt" || true
trap - EXIT
