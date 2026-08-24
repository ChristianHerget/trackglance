#!/bin/bash
set -euo pipefail

platform=${PEBBLE_PLATFORM:-emery}
case "$platform" in
  emery|gabbro) ;;
  *) echo "Unsupported Pebble platform: $platform" >&2; exit 2 ;;
esac

pebble_data_root=${XDG_DATA_HOME:-/opt/pebble-data}/pebble-sdk
sdk_dir="$pebble_data_root/SDKs/${PEBBLE_SDK_VERSION:-4.33.1}"
board_dir="$sdk_dir/sdk-core/pebble/$platform"
state_dir="/pebble-state/$platform"
flash="$state_dir/qemu_spi_flash.bin"
mkdir -p "$state_dir" /run/locuspebble /artifacts
if [[ ! -s "$flash" ]]; then
  bzip2 -dc "$board_dir/qemu/qemu_spi_flash.bin.bz2" > "$flash.partial"
  test "$(stat -c %s "$flash.partial")" -eq 33554432
  mv "$flash.partial" "$flash"
fi

"$sdk_dir/toolchain/bin/qemu-pebble" \
  -rtc base=utc \
  -serial null \
  -serial tcp::12345,server=on,wait=off \
  -serial file:/artifacts/pebble-${platform}.serial.log \
  -kernel "$board_dir/qemu/qemu_micro_flash.bin" \
  -gdb tcp::12347,server=on,wait=off \
  -monitor tcp::12348,server=on,wait=off \
  -machine "pebble-$platform" \
  -cpu cortex-m33 \
  -drive "if=mtd,format=raw,file=$flash" \
  -audio driver=none \
  -display none &
qemu_pid=$!
trap 'kill "$qemu_pid" 2>/dev/null || true; wait "$qemu_pid" 2>/dev/null || true' EXIT INT TERM

for attempt in $(seq 1 100); do
  if (exec 3<>/dev/tcp/127.0.0.1/12345) 2>/dev/null; then
    exec 3>&-
    break
  fi
  if ! kill -0 "$qemu_pid" 2>/dev/null; then
    echo "PebbleOS QEMU exited before opening its protocol socket" >&2
    exit 1
  fi
  sleep 0.1
done

exec python3 /workspace/tools/podman/relay.py \
  --qemu-port 12345 \
  --watch-platform "$platform" \
  --control-socket /run/locuspebble/relay.sock \
  --transcript "/artifacts/relay-${platform}.jsonl"
