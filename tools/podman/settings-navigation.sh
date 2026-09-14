#!/bin/bash

settings_checkpoint() {
  printf '%s %s\n' "$SECONDS" "$*" >> "${SETTINGS_TRACE:-/artifacts/${PEBBLE_PLATFORM}-settings-checkpoints.txt}"
}

open_trackglance_settings() {
  local deadline=$((SECONDS + ${1:-120})) foreground='' relay='' bridge='' control
  local launched=0 last_launch=0 matched=0 next_control=Apps
  local -a controls=()
  settings_checkpoint begin
  while (( SECONDS < deadline )); do
    foreground=$(adb_device_timeout 5 shell dumpsys activity activities 2>/dev/null \
      | grep 'topResumedActivity=' || true)
    relay=$(relayctl status 2>&1 || true)
    bridge=$(status_value watch_connected || true)
    settings_checkpoint "health foreground=$foreground relay=$relay bridge=$bridge"
    if [[ "$bridge" != true ]] || ! python3 -c \
      'import json,sys; s=json.load(sys.stdin); sys.exit(not (s.get("phone_connected") and s.get("qemu_connected")))' \
      <<< "$relay" 2>/dev/null; then
      sleep 1
      continue
    fi
    if [[ "$foreground" != *' coredevices.coreapp/'* ]] || (( ! launched )); then
      adb_device_timeout 10 shell am start -W -a android.intent.action.VIEW \
        -d pebble://navbar/apps -n coredevices.coreapp/.MainActivity >/dev/null || true
      settings_checkpoint apps-deep-link
      launched=1
      next_control=Apps
      last_launch=$SECONDS
      sleep 1
      continue
    fi
    # Never interpret a stale dump as a newly rendered WebView.
    rm -f /tmp/trackglance-window.xml
    if dump_ui; then
      if grep -Fq 'resource-id="generalOpen"' /tmp/trackglance-window.xml; then
        settings_checkpoint settings-webview-passed
        return 0
      fi
      # Each missing or disappearing control is recoverable within this attempt.
      matched=0
      controls=('Get Started' 'Connect a Pebble' 'Connect a Pebble!' Skip Finished "$next_control")
      if grep -Fq 'text="I have a:"' /tmp/trackglance-window.xml; then
        controls=(Watch)
      fi
      for control in "${controls[@]}"; do
        [[ -n "$control" ]] || continue
        if grep -Fq "text=\"$control\"" /tmp/trackglance-window.xml; then
          matched=1
          if tap_text "$control" 1; then
            settings_checkpoint "tapped $control"
            last_launch=$SECONDS
            case "$control" in
              Apps) next_control=TrackGlance ;;
              TrackGlance) next_control=Settings ;;
              Settings) next_control='' ;;
              Finished) launched=0 ;;
            esac
          else
            settings_checkpoint "missing $control"
          fi
          break
        fi
      done
      if (( ! matched )) && grep -Eq 'text="(Get Started!|Configure your watch)"' /tmp/trackglance-window.xml; then
        adb_device_timeout 5 shell input swipe 540 2100 540 400 200 || true
        settings_checkpoint onboarding-swipe
        last_launch=$SECONDS
      fi
    fi
    if (( SECONDS - last_launch >= 20 )); then
      launched=0
      settings_checkpoint reopen-apps
    fi
    sleep 1
  done
  settings_checkpoint expired
  dump_ui || true
  {
    echo "Pebble App settings recovery expired"
    echo "Foreground activity: $foreground"
    echo "Relay status: $relay"
    echo "Bridge watch_connected: $bridge"
    adb_device_timeout 5 shell content query --uri "$STATUS_URI" || true
    echo "UI dump:"
    cat /tmp/trackglance-window.xml 2>/dev/null || true
    cat "${SETTINGS_TRACE:-/artifacts/${PEBBLE_PLATFORM}-settings-checkpoints.txt}"
  } >&2
  return 1
}
