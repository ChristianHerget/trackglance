#!/bin/bash

settings_checkpoint() {
  printf '%s %s\n' "$SECONDS" "$*" >> "${SETTINGS_TRACE:-/artifacts/${PEBBLE_PLATFORM}-settings-checkpoints.txt}"
}

settings_tap_text() {
  local deadline=$1 control=$2 status=0
  tap_text_once_until "$@" || status=$?
  settings_checkpoint "tap control=$control capture_seconds=$TAP_TEXT_CAPTURE_SECONDS remaining_seconds=$((deadline - SECONDS)) outcome=$TAP_TEXT_OUTCOME"
  return "$status"
}

open_trackglance_settings() {
  # Cold first-run onboarding and WebView startup can consume more than two minutes.
  # This is a deadline, not a delay: return as soon as the settings marker is observed.
  local deadline=$((SECONDS + ${1:-180})) foreground='' relay='' bridge='' control optional_prompt
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
    if [[ "$foreground" == *' com.google.android.permissioncontroller/'* \
      || "$foreground" == *' com.android.permissioncontroller/'* ]]; then
      settings_checkpoint permission-controller
      # These are the pinned Pebble App's optional after-connection permissions.
      # Recheck the exact prompt in a fresh dump before declining it.
      if dump_ui "$deadline"; then
        for optional_prompt in \
          'Allow Pebble to access your phone call logs?' \
          'Allow Pebble to access your calendar?' \
          'Allow Pebble to access your contacts?' \
          'Allow Pebble to make and manage phone calls?'; do
          if grep -Fq "text=\"$optional_prompt\"" /tmp/trackglance-window.xml; then
            if settings_tap_text "$deadline" "Don’t allow" exact "$optional_prompt"; then
              settings_checkpoint "optional-permission-denied $optional_prompt"
            else
              settings_checkpoint "optional-permission-not-denied $optional_prompt"
            fi
            break
          fi
        done
      fi
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
    if dump_ui "$deadline"; then
      if (( SECONDS < deadline )) && grep -Fq 'resource-id="generalOpen"' /tmp/trackglance-window.xml; then
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
          if settings_tap_text "$deadline" "$control"; then
            settings_checkpoint "tapped $control"
            last_launch=$SECONDS
            case "$control" in
              Apps) next_control=TrackGlance ;;
              TrackGlance) next_control=Settings ;;
              Settings) next_control='' ;;
              Finished) launched=0 ;;
            esac
          fi
          break
        fi
      done
      # The first swipe can hide the introductory headings before Finished is visible.
      # Continue through the pinned onboarding's app/watchface and language sections too.
      if (( ! matched )) && grep -Eq 'text="(Get Started!|Configure your watch|Add some Watchfaces|Add some Apps|Install a language pack)"' /tmp/trackglance-window.xml; then
        if adb_device_until "$deadline" 5 shell input swipe 540 2100 540 400 200; then
          settings_checkpoint onboarding-swipe
          last_launch=$SECONDS
        else
          settings_checkpoint onboarding-swipe-failed
        fi
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
