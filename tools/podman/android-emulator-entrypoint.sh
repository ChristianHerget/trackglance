#!/bin/bash
set -euo pipefail

avd_home=/data/android-home
if [[ ! -f "$avd_home/MediumPhone.avd/config.ini" ]]; then
  mkdir -p "$avd_home"
  cp -a /android-home/. "$avd_home/"
fi
sed -i "s|^path=.*|path=$avd_home/MediumPhone.avd|" "$avd_home/MediumPhone.ini"
find "$avd_home/MediumPhone.avd" -maxdepth 1 -name '*.lock' -delete
mkdir -p /root/.android /root/.config/pulse /run/locuspebble /tmp/android-unknown
ln -sfn "$avd_home" /root/.android/avd
export ANDROID_AVD_HOME="$avd_home"
export PULSE_SERVER=unix:/tmp/pulse-socket

pulseaudio -D --log-target=stderr --exit-idle-time=-1
/android/sdk/platform-tools/adb start-server
socat tcp-listen:5555,reuseaddr,fork tcp:127.0.0.1:5557 &

emulator/emulator \
  -avd MediumPhone \
  -ports 5556,5557 \
  -grpc 8554 \
  -no-window \
  -skip-adb-auth \
  -no-boot-anim \
  -no-metrics \
  -shell-serial file:/tmp/android-unknown/kernel.log \
  -logcat-output /tmp/android-unknown/logcat.log \
  -feature AllowSnapshotMigration \
  -qemu -append panic=1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true; wait "$emulator_pid" 2>/dev/null || true' EXIT INT TERM

for attempt in $(seq 1 300); do
  discovery=$(find /tmp /root/.android/avd/running -name 'pid_*.ini' \
    -type f -print -quit 2>/dev/null || true)
  if [[ -n "$discovery" ]]; then
    cp "$discovery" /run/locuspebble/android-discovery.ini
    break
  fi
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    wait "$emulator_pid"
    exit 1
  fi
  sleep 0.1
done
test -s /run/locuspebble/android-discovery.ini
for attempt in $(seq 1 100); do
  if [[ -s /root/.emulator_console_auth_token ]]; then
    install -m 600 /root/.emulator_console_auth_token \
      /run/locuspebble/emulator-console-auth-token
    break
  fi
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    wait "$emulator_pid"
    exit 1
  fi
  sleep 0.1
done
test -s /run/locuspebble/emulator-console-auth-token
wait "$emulator_pid"
