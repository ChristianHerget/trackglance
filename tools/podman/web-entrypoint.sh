#!/bin/bash
set -euo pipefail

for _ in $(seq 1 300); do
  [[ -s /run/trackglance/android-discovery.ini ]] && break
  sleep 0.1
done
test -s /run/trackglance/android-discovery.ini
/opt/gateway-venv/bin/videobridge-gateway \
  --port=8080 \
  --discovery_file=/run/trackglance/android-discovery.ini &
gateway_pid=$!
trap 'kill "$gateway_pid" 2>/dev/null || true' EXIT INT TERM
cd /opt/aemu/js/example
exec npm run dev -- --host 0.0.0.0 --port 5173
