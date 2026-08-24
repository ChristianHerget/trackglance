#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STATIC_DIR="${SCREENSHOT_OUTPUT_DIR:-$SCRIPT_DIR/sphinx/_static}"
SKIP_CAPTURE="${SCREENSHOT_SKIP:-0}"
PEBBLE_XDG_DATA_HOME="${PEBBLE_SCREENSHOT_XDG_DATA_HOME:-/tmp/pebble-sdk-data}"
PBW="$PROJECT_DIR/watchapp/build/watchapp.pbw"
SCREENSHOTS=(
  screenshot_emery_dashboard.png
  screenshot_emery_menu.png
  screenshot_emery_profiles.png
  screenshot_emery_waypoints.png
  screenshot_emery_layout_1.png
  screenshot_emery_layout_2.png
  screenshot_emery_layout_3.png
  screenshot_emery_layout_4.png
  screenshot_emery_layout_5.png
  screenshot_emery_layout_6.png
  screenshot_gabbro_dashboard.png
  screenshot_gabbro_menu.png
  watch_settings_overview.png
  watch_settings_profile.png
)

mkdir -p "$STATIC_DIR"

if [[ "$SKIP_CAPTURE" == "1" ]]; then
  for screenshot in "${SCREENSHOTS[@]}"; do
    if [[ ! -s "$STATIC_DIR/$screenshot" ]]; then
      echo "Error: missing existing documentation screenshot: $STATIC_DIR/$screenshot" >&2
      exit 1
    fi
  done
  echo "Skipping documentation screenshot capture; keeping existing emulator images."
  exit 0
fi

if ! command -v pebble >/dev/null 2>&1; then
  echo "Error: 'pebble' is required to generate documentation screenshots." >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "Error: 'node' is required to generate documentation screenshots." >&2
  exit 1
fi

while IFS=$'\t' read -r name value; do
  if [[ ! "$name" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
    echo "Error: invalid screenshot metadata name: $name" >&2
    exit 1
  fi
  printf -v "$name" '%s' "$value"
done < <(
  node - "$PROJECT_DIR/watchapp/package.json" "$PROJECT_DIR/watchapp/src/pkjs/index.js" <<'NODE'
const packageJson = require(process.argv[2]);
const settings = require(process.argv[3]);

if (packageJson.version !== settings.RELEASE) {
  throw new Error('watch package and settings release versions differ');
}

function emit(name, value) {
  process.stdout.write(`${name}\t${value}\n`);
}

emit('APP_UUID', packageJson.pebble.uuid);
emit('APP_VERSION', packageJson.version);
emit('PROTOCOL_VERSION', settings.VERSION);
emit('TYPE_SNAPSHOT', settings.TYPES.snapshot);
emit('TYPE_CONFIG_CHUNK', settings.TYPES.configChunk);
for (const [name, value] of Object.entries(packageJson.pebble.messageKeys)) {
  emit(`KEY_${name}`, value);
}
emit('DEFAULT_CONFIG_BASE64', Buffer.from(settings.serialize(settings.defaults), 'utf8').toString('base64'));
NODE
)

: "${APP_UUID:?Missing watchapp UUID}"
: "${APP_VERSION:?Missing watchapp version}"
: "${PROTOCOL_VERSION:?Missing protocol version}"
: "${KEY_PROTOCOL_VERSION:?Missing protocol key metadata}"
: "${KEY_TRANSFER_GENERATION:?Missing protocol key metadata}"

DEFAULT_CONFIG_PAYLOAD="$(
  node - "$DEFAULT_CONFIG_BASE64" <<'NODE'
process.stdout.write(Buffer.from(process.argv[2], 'base64').toString('utf8'));
NODE
)"

pebble_for_screenshots() {
  local command=${1:-}
  local -a display_args=()
  if [[ -z "${DISPLAY:-}" ]]; then
    case "$command" in
      send-app-message|screenshot|install|emu-button) display_args=(--vnc) ;;
    esac
  fi
  env XDG_DATA_HOME="$PEBBLE_XDG_DATA_HOME" pebble "$@" "${display_args[@]}"
}

stop_screenshot_emulators() {
  pebble_for_screenshots kill --force >/dev/null 2>&1 || true
}

trap stop_screenshot_emulators EXIT
stop_screenshot_emulators
pebble_for_screenshots wipe

echo "Building the Pebble watchapp..."
(
  cd "$PROJECT_DIR/watchapp"
  pebble build
)

if [[ ! -s "$PBW" ]]; then
  echo "Error: Pebble build did not produce $PBW" >&2
  exit 1
fi

send_snapshot() {
  local platform=$1
  local state=$2
  local epoch=$3
  pebble_for_screenshots send-app-message --emulator "$platform" --app-uuid "$APP_UUID" \
    --int "$KEY_PROTOCOL_VERSION=$PROTOCOL_VERSION" "$KEY_MESSAGE_TYPE=$TYPE_SNAPSHOT" \
      "$KEY_RECORDING_STATE=$state" "$KEY_DISTANCE_METRES=6840" \
      "$KEY_CURRENT_SPEED_CMPS=142" "$KEY_AVERAGE_SPEED_CMPS=128" \
      "$KEY_ALTITUDE_DECIMETRES=23410" "$KEY_ASCENT_DECIMETRES=4860" \
      "$KEY_UNIT_SYSTEM=0" "$KEY_MOVING_SECONDS=5772" \
      "$KEY_MOVING_DISTANCE_METRES=6220" "$KEY_MAX_SPEED_CMPS=315" \
      "$KEY_DESCENT_DECIMETRES=4310" "$KEY_VERTICAL_SPEED_CMPS=12" \
      "$KEY_SLOPE_TENTHS_PERCENT=37" "$KEY_AVERAGE_HEART_RATE=132" \
      "$KEY_MAX_HEART_RATE=158" "$KEY_AVERAGE_CADENCE=74" "$KEY_MAX_CADENCE=92" \
      "$KEY_AVERAGE_POWER=186" "$KEY_MAX_POWER=412" "$KEY_ENERGY_KCAL=824" \
      "$KEY_CURRENT_HEART_RATE=137" \
    --uint "$KEY_SAMPLE_EPOCH_SECONDS=$epoch" "$KEY_ELAPSED_SECONDS=6252" \
    --string "$KEY_APP_VERSION=$APP_VERSION"
}

send_configuration() {
  local platform=$1
  local transfer_id=$2
  local payload=$3
  pebble_for_screenshots send-app-message --emulator "$platform" --app-uuid "$APP_UUID" \
    --int "$KEY_PROTOCOL_VERSION=$PROTOCOL_VERSION" "$KEY_MESSAGE_TYPE=$TYPE_CONFIG_CHUNK" \
      "$KEY_CHUNK_INDEX=0" "$KEY_CHUNK_COUNT=1" "$KEY_TRANSFER_ID=$transfer_id" \
      "$KEY_TRANSFER_GENERATION=1" \
    --string "$KEY_CHUNK_DATA=$payload" "$KEY_APP_VERSION=$APP_VERSION"
}

capture_stable_screenshot() {
  local platform=$1
  local target=$2
  local previous="${target}.previous"
  local candidate="${target}.candidate"

  rm -f "$previous" "$candidate"
  pebble_for_screenshots screenshot "$previous" --emulator "$platform" --no-open
  for attempt in 1 2 3 4 5; do
    sleep 1
    pebble_for_screenshots screenshot "$candidate" --emulator "$platform" --no-open
    if cmp -s "$previous" "$candidate"; then
      mv "$candidate" "$target"
      rm -f "$previous"
      return
    fi
    mv "$candidate" "$previous"
  done

  rm -f "$previous" "$candidate"
  echo "Error: $platform did not produce a stable screenshot for $target." >&2
  exit 1
}

capture_emery_layouts() {
  local transfer_id=1
  local metrics=(1 3 5 6 10 22)
  local metric_list=""

  for count in 1 2 3 4 5 6; do
    if [[ -n "$metric_list" ]]; then
      metric_list+=","
    fi
    metric_list+="${metrics[count - 1]}"

    local stopped_epoch=$((2000000100 + count * 2))
    local recording_epoch=$((stopped_epoch + 1))
    local payload="dark|0|0|5"$'\n'"$count metrics|Walking|0|$metric_list|layout-$count"
    local target="$STATIC_DIR/screenshot_emery_layout_${count}.png"
    local target_temp="${target}.partial"

    send_snapshot emery 0 "$stopped_epoch"
    send_configuration emery "$((transfer_id + count))" "$payload"
    sleep 1
    send_snapshot emery 2 "$recording_epoch"
    sleep 1
    capture_stable_screenshot emery "$target_temp"
    if [[ ! -s "$target_temp" ]]; then
      echo "Error: Emery did not produce the $count-metric layout screenshot." >&2
      exit 1
    fi
    mv "$target_temp" "$target"
  done

  send_snapshot emery 0 2000000200
  send_configuration emery "$((transfer_id + 7))" "$DEFAULT_CONFIG_PAYLOAD"
  sleep 1
}

capture_platform() {
  local platform=$1
  local dashboard="$STATIC_DIR/screenshot_${platform}_dashboard.png"
  local menu="$STATIC_DIR/screenshot_${platform}_menu.png"
  local dashboard_temp="${dashboard}.partial"
  local menu_temp="${menu}.partial"

  rm -f "$dashboard_temp" "$menu_temp"

  echo "Installing and launching the watchapp on $platform..."
  pebble_for_screenshots install "$PBW" --emulator "$platform" --force

  sleep 1

  echo "Loading known documentation settings on $platform..."
  send_snapshot "$platform" 0 1999999999
  send_configuration "$platform" 1 "$DEFAULT_CONFIG_PAYLOAD"
  sleep 2

  echo "Sending representative active-recording telemetry to $platform..."
  send_snapshot "$platform" 1 2000000000

  sleep 5

  pebble_for_screenshots screenshot "$dashboard_temp" --emulator "$platform" --no-open

  pebble_for_screenshots emu-button click select --emulator "$platform"
  sleep 1

  pebble_for_screenshots screenshot "$menu_temp" --emulator "$platform" --no-open

  if [[ ! -s "$dashboard_temp" || ! -s "$menu_temp" ]]; then
    echo "Error: $platform did not produce both dashboard and menu screenshots." >&2
    exit 1
  fi
  if cmp -s "$dashboard_temp" "$menu_temp"; then
    echo "Error: $platform dashboard and menu screenshots are identical." >&2
    exit 1
  fi

  mv "$dashboard_temp" "$dashboard"
  mv "$menu_temp" "$menu"

  if [[ "$platform" == "emery" ]]; then
    local profiles="$STATIC_DIR/screenshot_emery_profiles.png"
    local waypoints="$STATIC_DIR/screenshot_emery_waypoints.png"
    local profiles_temp="${profiles}.partial"
    local waypoints_temp="${waypoints}.partial"

    rm -f "$profiles_temp" "$waypoints_temp"

    pebble_for_screenshots emu-button click down --emulator "$platform"
    pebble_for_screenshots emu-button click select --emulator "$platform"
    sleep 1
    pebble_for_screenshots screenshot "$profiles_temp" --emulator "$platform" --no-open
    pebble_for_screenshots emu-button click back --emulator "$platform"
    pebble_for_screenshots emu-button click back --emulator "$platform"

    pebble_for_screenshots emu-button click select --emulator "$platform"
    pebble_for_screenshots emu-button click down --repeat 4 --emulator "$platform"
    sleep 1
    pebble_for_screenshots screenshot "$waypoints_temp" --emulator "$platform" --no-open
    pebble_for_screenshots emu-button click back --emulator "$platform"

    if [[ ! -s "$profiles_temp" || ! -s "$waypoints_temp" ]]; then
      echo "Error: Emery did not produce profile and waypoint screenshots." >&2
      exit 1
    fi
    mv "$profiles_temp" "$profiles"
    mv "$waypoints_temp" "$waypoints"
    capture_emery_layouts
  else
    pebble_for_screenshots emu-button click back --emulator "$platform"
  fi
}

capture_platform emery
capture_platform gabbro

LOCK_HASH="$(node - "$SCRIPT_DIR/package-lock.json" <<'NODE'
const crypto = require('crypto');
const fs = require('fs');
process.stdout.write(crypto.createHash('sha256').update(fs.readFileSync(process.argv[2])).digest('hex'));
NODE
)"
LOCK_STAMP="$SCRIPT_DIR/node_modules/.package-lock.sha256"
if [[ ! -f "$LOCK_STAMP" ]] || [[ "$(cat "$LOCK_STAMP")" != "$LOCK_HASH" ]] || \
    ! node - "$SCRIPT_DIR/node_modules/playwright-chromium" <<'NODE'
const fs = require('fs');
const { chromium } = require(process.argv[2]);
process.exit(fs.existsSync(chromium.executablePath()) ? 0 : 1);
NODE
then
  npm ci --prefix "$SCRIPT_DIR"
  printf '%s\n' "$LOCK_HASH" > "$LOCK_STAMP"
fi
node "$SCRIPT_DIR/render_watch_settings_screenshots.js" "$STATIC_DIR"

echo "Documentation screenshots now show app actions and every metric layout."
