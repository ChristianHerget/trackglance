#!/bin/bash
# Run only on an isolated API 34+ test device with the debug Bridge already installed.
set -euo pipefail
: "${SUPERVISION_TEST_DEVICE:?Set SUPERVISION_TEST_DEVICE=1 for an isolated disposable device}"
[[ "$SUPERVISION_TEST_DEVICE" == 1 ]]
source "$(dirname "${BASH_SOURCE[0]}")/device-lib.sh"
package=app.trackglance.bridge
activity=$package/io.github.christianherget.trackglance.bridge.MainActivity
api=$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')
(( api >= 34 ))

select_mode() {
  local mode=$1 scroll
  for ((scroll = 0; scroll < 4; scroll++)); do
    adb_device shell input swipe 540 600 540 1800 350
  done
  for ((scroll = 0; scroll < 8; scroll++)); do
    if tap_text "$mode" 2 exact; then return; fi
    adb_device shell input swipe 540 1800 540 600 350
  done
  echo "Cannot find supervision mode $mode" >&2
  return 1
}

adb_device shell pm clear "$package" >/dev/null
adb_device shell am start -W -n "$activity" >/dev/null
wait_status supervision_mode OFF 15
dump_ui
if grep -q 'permission_allow_button' /tmp/trackglance-window.xml; then
  echo 'Off unexpectedly requested notification permission' >&2
  exit 1
fi
select_mode Notify
tap_text 't allow' 15
wait_status supervision_mode OFF 10
for ((scroll = 0; scroll < 4; scroll++)); do
  dump_ui
  if grep -Fq 'Notifications blocked' /tmp/trackglance-window.xml; then break; fi
  adb_device shell input swipe 540 1800 540 600 350
done
grep -Fq 'Notifications blocked' /tmp/trackglance-window.xml
select_mode Auto-start
# Android may suppress a repeated denied request; either result must retain Auto-start.
tap_text 't allow' 5 || true
wait_status supervision_mode AUTO_START 10
adb_device shell dumpsys activity services "$package" > /tmp/supervision-service.txt
grep -Fq 'SupervisionService' /tmp/supervision-service.txt
grep -Fq 'isForeground=true' /tmp/supervision-service.txt
adb_device shell input keyevent KEYCODE_HOME

# A plain process kill must allow START_STICKY recreation; force-stop is deliberately different.
old_pid=$(adb_device shell pidof "$package" | tr -d '\r')
adb_device shell run-as "$package" kill -9 "$old_pid"
restart_deadline=$((SECONDS + 45))
new_pid=
while (( SECONDS < restart_deadline )); do
  new_pid=$(adb_device shell pidof "$package" | tr -d '\r' || true)
  [[ -n "$new_pid" && "$new_pid" != "$old_pid" ]] && break
  sleep 1
done
[[ -n "$new_pid" && "$new_pid" != "$old_pid" ]]
wait_status supervision_mode AUTO_START 10
wait_status supervision_phase WAITING 10
adb_device shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb_device shell am start -W -n "$activity" >/dev/null
select_mode Notify
wait_status supervision_mode NOTIFY 10
adb_device shell input keyevent KEYCODE_HOME
adb_device shell pm revoke "$package" android.permission.POST_NOTIFICATIONS
adb_device shell am start -W -n "$activity" >/dev/null
wait_status supervision_mode OFF 10
printf 'API %s: Off permission behavior, Notify denial, Auto-start denial, specialUse startup, sticky recreation, and later notification blocking passed.\n' "$api"
