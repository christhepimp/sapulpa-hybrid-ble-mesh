#!/usr/bin/env bash
# Setup ADB port-forward for the Sapulpa Hybrid BLE Mesh (no root required)
set -euo pipefail

PORT=${1:-5555}

echo "==> Checking ADB…"
adb start-server
adb devices

echo "==> Forwarding host tcp:$PORT → device tcp:$PORT"
adb forward --remove "tcp:$PORT" 2>/dev/null || true
adb forward "tcp:$PORT" "tcp:$PORT"

echo "==> Current forwards:"
adb forward --list

echo ""
echo "Ready. Keep this terminal open (or the forward stays active)."
echo "1. Launch the Sapulpa BLE Mesh app on the phone and tap 'Start Bridge'."
echo "2. On the host run:  python python/mesh_engine.py --port $PORT"
echo ""
