#!/bin/bash
set -euo pipefail

source /workspace/tools/podman/device-lib.sh
source /workspace/tools/podman/release-metadata.sh
load_release_metadata /workspace

: "${PEBBLE_PLATFORM:?PEBBLE_PLATFORM is required}"
case "$PEBBLE_PLATFORM" in
  emery|gabbro) ;;
  *) echo "Unsupported Pebble platform: $PEBBLE_PLATFORM" >&2; exit 2 ;;
esac

bridge_apk=/workspace/android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk
pbw=/workspace/watchapp/build/watchapp.pbw
bridge_activity=app.trackglance.bridge/io.github.christianherget.trackglance.bridge.MainActivity
ready_file=/run/trackglance/manual-ready
rm -f "$ready_file"
test -s "$bridge_apk"
test -s "$pbw"

wait_for_android 180
grant_locus_test_permissions
grant_coreapp_test_permissions
foreground_locus
adb_device uninstall app.trackglance.bridge >/dev/null 2>&1 || true
adb_device_timeout 180 install -r "$bridge_apk" >/dev/null
# Locus caches external main-function activities. It was already running when the bridge was
# installed, so restart it once to make the Add-ons row appear below Various in All features.
adb_device shell am force-stop menion.android.locus
foreground_locus
adb_device shell am force-stop app.trackglance.bridge
adb_device shell am start -W -n "$bridge_activity" >/dev/null
wait_status locus_available true 45
wait_status recording_state STOPPED 30
wait_nonempty_status locus_profiles 45 >/dev/null
wait_status bridge_version "$RELEASE_ANDROID_VERSION" 15
wait_status bridge_version_code "$RELEASE_ANDROID_CODE" 15
wait_status protocol_version "$RELEASE_PROTOCOL_VERSION" 15

relay_deadline=$((SECONDS + 45))
until relayctl status >/dev/null 2>&1; do
  (( SECONDS < relay_deadline )) || {
    echo "Pebble QEMU relay did not become ready" >&2
    exit 1
  }
  sleep 0.5
done

adb_device shell am broadcast \
  -a coredevices.coreapp.ADD_QEMU_WATCH \
  -n coredevices.coreapp/coredevices.coreapp.debug.QemuSetupReceiver \
  --es host 10.0.2.2 --ei port 12344 --ez connect true >/dev/null
wait_status watch_connected true 60
wait_status pebble_app_package coredevices.coreapp 15

adb_device_timeout 60 push "$pbw" /data/local/tmp/trackglance.pbw >/dev/null
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

# A source-built Pebble App golden state can still be at any bounded onboarding screen. Finish it
# after QEMU is connected, then reopen TrackGlance because onboarding may consume the first intent.
adb_device shell am start -W -a android.intent.action.VIEW \
  -d pebble://navbar/apps -n coredevices.coreapp/.MainActivity >/dev/null
complete_coreapp_onboarding 90
adb_device shell am start -W \
  -a locus.api.android.INTENT_ITEM_MAIN_FUNCTION \
  -n "$bridge_activity" >/dev/null
wait_status watch_app_open true 45

foreground_locus
wait_status recording_state STOPPED 15
printf 'platform=%s\nready_at=%s\n' "$PEBBLE_PLATFORM" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$ready_file"

exec python3 /workspace/tools/podman/manual_lab.py \
  --platform "$PEBBLE_PLATFORM" \
  --artifacts /artifacts \
  --relay-socket /run/trackglance/relay.sock \
  --host 127.0.0.1 \
  --port 8081
