#!/usr/bin/env bash
# Convenience launcher for the Python side of the hybrid mesh
set -euo pipefail
cd "$(dirname "$0")/.."
PORT=${1:-5555}

if ! python3 -c "import folium" 2>/dev/null; then
  echo "Installing folium…"
  pip install -q folium
fi

echo "Starting Sapulpa mesh engine (port $PORT)…"
python3 python/mesh_engine.py --port "$PORT"
