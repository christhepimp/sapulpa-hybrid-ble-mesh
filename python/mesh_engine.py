#!/usr/bin/env python3
"""
Sapulpa Mesh – Python spatial Bluetooth mesh emulator
======================================================
Dense geographic grid of software BLE emulator nodes covering Sapulpa, OK.
Store-and-forward multi-hop routing. Edge nodes push/pull payloads over a
TCP socket that is mapped to an unrooted Android companion app via
`adb forward`.

No root required on the phone.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import random
import socket
import struct
import sys
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple

try:
    import folium
    from folium.plugins import MarkerCluster
    HAS_FOLIUM = True
except ImportError:
    HAS_FOLIUM = False

# ---------------------------------------------------------------------------
# Geographic constants – Sapulpa, Oklahoma
# ---------------------------------------------------------------------------
SAPULPA_BOUNDS = {
    "min_lat": 35.9600,
    "max_lat": 36.0600,
    "min_lon": -96.1600,
    "max_lon": -96.0500,
}
SAPULPA_CENTER = (
    (SAPULPA_BOUNDS["min_lat"] + SAPULPA_BOUNDS["max_lat"]) / 2,
    (SAPULPA_BOUNDS["min_lon"] + SAPULPA_BOUNDS["max_lon"]) / 2,
)

GRID_SPACING_M = 70.0    # dense grid as specified
BT_RANGE_M = 80.0        # typical BLE outdoor range (must be > spacing)
HARDWARE_BRIDGE_COUNT = 4
DEFAULT_BRIDGE_PORT = 5555   # local port; adb forward tcp:5555 tcp:5555


# ---------------------------------------------------------------------------
# Node model
# ---------------------------------------------------------------------------
@dataclass
class MeshNode:
    lat: float
    lon: float
    node_id: int
    bd_addr: str = ""
    state: str = "idle"
    neighbors: List["MeshNode"] = field(default_factory=list)
    packet_buffer: List[bytes] = field(default_factory=list)
    is_bridge: bool = False

    def __post_init__(self) -> None:
        if not self.bd_addr:
            self.bd_addr = (
                f"02:{random.randint(0,255):02X}:{random.randint(0,255):02X}:"
                f"{random.randint(0,255):02X}:{random.randint(0,255):02X}:"
                f"{self.node_id % 256:02X}"
            )

    def distance_to(self, other: "MeshNode") -> float:
        R = 6_371_000.0
        dlat = math.radians(other.lat - self.lat)
        dlon = math.radians(other.lon - self.lon)
        a = (
            math.sin(dlat / 2) ** 2
            + math.cos(math.radians(self.lat))
            * math.cos(math.radians(other.lat))
            * math.sin(dlon / 2) ** 2
        )
        return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    def can_hear(self, other: "MeshNode") -> bool:
        return self.distance_to(other) <= BT_RANGE_M

    def connect(self, peer: "MeshNode") -> None:
        if peer not in self.neighbors:
            self.neighbors.append(peer)
            peer.neighbors.append(self)
        if not self.is_bridge:
            self.state = "connected"
        if not peer.is_bridge:
            peer.state = "connected"

    def enqueue(self, data: bytes) -> None:
        self.packet_buffer.append(data)


# ---------------------------------------------------------------------------
# Grid + mesh
# ---------------------------------------------------------------------------
def create_grid(spacing_m: float = GRID_SPACING_M) -> List[MeshNode]:
    lat_m = 111_320.0
    lon_m = 111_320.0 * math.cos(math.radians(SAPULPA_CENTER[0]))
    dlat = spacing_m / lat_m
    dlon = spacing_m / lon_m

    nodes: List[MeshNode] = []
    nid = 0
    lat = SAPULPA_BOUNDS["min_lat"]
    row = 0
    while lat <= SAPULPA_BOUNDS["max_lat"] + 1e-9:
        lon = SAPULPA_BOUNDS["min_lon"]
        offset = (dlon * 0.5) if (row % 2) else 0.0
        while lon <= SAPULPA_BOUNDS["max_lon"] + 1e-9:
            nodes.append(MeshNode(round(lat, 6), round(lon + offset, 6), nid))
            nid += 1
            lon += dlon
        lat += dlat
        row += 1
    return nodes


def build_mesh(nodes: List[MeshNode]) -> int:
    if not nodes:
        return 0
    cell_m = BT_RANGE_M
    lat_m = 111_320.0
    lon_m = 111_320.0 * math.cos(math.radians(SAPULPA_CENTER[0]))
    cell_dlat = cell_m / lat_m
    cell_dlon = cell_m / lon_m

    grid: Dict[Tuple[int, int], List[int]] = defaultdict(list)
    for i, n in enumerate(nodes):
        ix = int((n.lat - SAPULPA_BOUNDS["min_lat"]) / cell_dlat)
        iy = int((n.lon - SAPULPA_BOUNDS["min_lon"]) / cell_dlon)
        grid[(ix, iy)].append(i)

    edges = 0
    for i, n in enumerate(nodes):
        ix = int((n.lat - SAPULPA_BOUNDS["min_lat"]) / cell_dlat)
        iy = int((n.lon - SAPULPA_BOUNDS["min_lon"]) / cell_dlon)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in grid.get((ix + dx, iy + dy), []):
                    if j <= i:
                        continue
                    if n.can_hear(nodes[j]):
                        n.connect(nodes[j])
                        edges += 1
    return edges


def designate_bridges(nodes: List[MeshNode], count: int = HARDWARE_BRIDGE_COUNT) -> List[MeshNode]:
    ranked = sorted(
        nodes,
        key=lambda n: abs(n.lat - SAPULPA_CENTER[0]) + abs(n.lon - SAPULPA_CENTER[1]),
        reverse=True,
    )
    bridges = []
    for n in ranked[:count]:
        n.is_bridge = True
        n.state = "bridge"
        bridges.append(n)
    return bridges


# ---------------------------------------------------------------------------
# Multi-hop flood
# ---------------------------------------------------------------------------
def flood(nodes: List[MeshNode], source: MeshNode, payload: bytes, max_hops: int = 30) -> dict:
    source.enqueue(payload)
    visited: Set[int] = set()
    frontier = deque([source])
    hop = 0
    bridges_hit: List[int] = []

    while frontier and hop < max_hops:
        nxt: deque = deque()
        while frontier:
            node = frontier.popleft()
            if node.node_id in visited:
                continue
            visited.add(node.node_id)
            if node.is_bridge:
                bridges_hit.append(node.node_id)
            for neigh in node.neighbors:
                if neigh.node_id not in visited:
                    neigh.enqueue(payload)
                    nxt.append(neigh)
        frontier = nxt
        hop += 1

    return {
        "source": source.node_id,
        "reached": len(visited),
        "total": len(nodes),
        "hops": hop,
        "bridges": bridges_hit,
        "coverage": 100.0 * len(visited) / max(len(nodes), 1),
    }


# ---------------------------------------------------------------------------
# ADB socket bridge (talks to the unrooted Android companion app)
# ---------------------------------------------------------------------------
class AdbBridgeClient:
    """
    Simple length-prefixed framing over TCP.
    The Android app listens on a port that is forwarded with:
        adb forward tcp:5555 tcp:5555
    """

    def __init__(self, host: str = "127.0.0.1", port: int = DEFAULT_BRIDGE_PORT):
        self.host = host
        self.port = port
        self.sock: Optional[socket.socket] = None

    def connect(self, timeout: float = 3.0) -> bool:
        try:
            self.sock = socket.create_connection((self.host, self.port), timeout=timeout)
            self.sock.settimeout(2.0)
            print(f"[bridge] Connected to Android companion at {self.host}:{self.port}")
            return True
        except OSError as e:
            print(f"[bridge] Cannot reach companion app ({e}). "
                  f"Is the app running and `adb forward tcp:{self.port} tcp:{self.port}` active?")
            self.sock = None
            return False

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def _send_frame(self, data: bytes) -> bool:
        if not self.sock:
            return False
        try:
            header = struct.pack(">I", len(data))
            self.sock.sendall(header + data)
            return True
        except OSError as e:
            print(f"[bridge] send error: {e}")
            return False

    def _recv_frame(self) -> Optional[bytes]:
        if not self.sock:
            return None
        try:
            hdr = self._recv_exact(4)
            if not hdr:
                return None
            length = struct.unpack(">I", hdr)[0]
            if length > 1_000_000:
                return None
            return self._recv_exact(length)
        except OSError:
            return None

    def _recv_exact(self, n: int) -> Optional[bytes]:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    def push_to_phone(self, payload: bytes, meta: Optional[dict] = None) -> bool:
        """Serialize a mesh packet and send it to the Android app for RF broadcast."""
        envelope = {
            "type": "mesh_to_rf",
            "payload_b64": payload.hex(),
            "meta": meta or {},
            "ts": time.time(),
        }
        raw = json.dumps(envelope).encode("utf-8")
        ok = self._send_frame(raw)
        if ok:
            print(f"[bridge] → phone  {len(payload)} bytes  meta={meta}")
        return ok

    def pull_from_phone(self) -> Optional[dict]:
        """Non-blocking-ish receive of a packet the phone scanned from the air."""
        raw = self._recv_frame()
        if not raw:
            return None
        try:
            msg = json.loads(raw.decode("utf-8"))
            print(f"[bridge] ← phone  type={msg.get('type')}  "
                  f"payload_len={len(msg.get('payload_b64',''))//2}")
            return msg
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None


# ---------------------------------------------------------------------------
# Map
# ---------------------------------------------------------------------------
def generate_map(nodes: List[MeshNode], path: str = "sapulpa_mesh_map.html") -> None:
    if not HAS_FOLIUM:
        print("[map] folium missing – skip")
        return
    m = folium.Map(location=SAPULPA_CENTER, zoom_start=12)
    folium.Rectangle(
        bounds=[
            [SAPULPA_BOUNDS["min_lat"], SAPULPA_BOUNDS["min_lon"]],
            [SAPULPA_BOUNDS["max_lat"], SAPULPA_BOUNDS["max_lon"]],
        ],
        color="#3388ff", weight=2, fill=False, popup="Sapulpa bounds",
    ).add_to(m)
    cluster = MarkerCluster().add_to(m)
    for n in nodes:
        color = "red" if n.is_bridge else "blue"
        icon = "signal" if n.is_bridge else "info-sign"
        popup = (
            f"<b>Node {n.node_id}</b><br>BD_ADDR {n.bd_addr}<br>"
            f"State {n.state}<br>Neighbours {len(n.neighbors)}"
        )
        folium.Marker(
            [n.lat, n.lon],
            popup=popup,
            icon=folium.Icon(color=color, icon=icon, prefix="glyphicon"),
            tooltip=f"N{n.node_id}",
        ).add_to(cluster)
    m.save(path)
    print(f"[map] wrote {path}")


# ---------------------------------------------------------------------------
# Main simulation + bridge loop
# ---------------------------------------------------------------------------
async def run_simulation(
    bridge_port: int = DEFAULT_BRIDGE_PORT,
    push_demo: bool = True,
    make_map: bool = True,
) -> None:
    print("=" * 64)
    print("  Sapulpa Hybrid BLE Mesh – Python Engine")
    print("=" * 64)

    print("\n[1] Building city grid…")
    nodes = create_grid()
    print(f"    {len(nodes)} emulator nodes")

    print(f"\n[2] Proximity mesh (range {BT_RANGE_M} m)…")
    edges = build_mesh(nodes)
    degrees = [len(n.neighbors) for n in nodes]
    print(f"    {edges} links  |  avg degree {sum(degrees)/len(degrees):.1f}  |  isolated {sum(1 for d in degrees if d==0)}")

    print("\n[3] Designating edge bridges…")
    bridges = designate_bridges(nodes)
    print(f"    bridge node IDs: {[b.node_id for b in bridges]}")

    print("\n[4] Multi-hop flood test…")
    src = random.choice(nodes)
    payload = b"SAPULPA-HYBRID-MESH-v1"
    result = flood(nodes, src, payload)
    print(f"    source={result['source']}  reached={result['reached']}/{result['total']}  "
          f"({result['coverage']:.1f}%)  bridges_hit={result['bridges']}")

    if make_map:
        print("\n[5] Map…")
        generate_map(nodes)

    # ---- live bridge to Android companion ---------------------------------
    print("\n[6] ADB socket bridge…")
    client = AdbBridgeClient(port=bridge_port)
    connected = client.connect()

    if connected and push_demo:
        target = bridges[0]
        if result["bridges"]:
            bid = result["bridges"][0]
            target = next(b for b in bridges if b.node_id == bid)
        meta = {
            "from_node": result["source"],
            "bridge_node": target.node_id,
            "bd_addr": target.bd_addr,
        }
        client.push_to_phone(payload, meta=meta)

        print("    Listening for phone → mesh packets (5 s)…")
        deadline = time.time() + 5
        while time.time() < deadline:
            msg = client.pull_from_phone()
            if msg and msg.get("type") == "rf_to_mesh":
                raw = bytes.fromhex(msg.get("payload_b64", ""))
                target.enqueue(raw)
                print(f"    Injected air packet into mesh at bridge {target.node_id}")
            await asyncio.sleep(0.2)

    client.close()
    print("\nDone.")
    print("=" * 64)


def main() -> None:
    parser = argparse.ArgumentParser(description="Sapulpa Hybrid BLE Mesh engine")
    parser.add_argument("--port", type=int, default=DEFAULT_BRIDGE_PORT)
    parser.add_argument("--no-map", action="store_true")
    parser.add_argument("--no-push", action="store_true")
    args = parser.parse_args()
    asyncio.run(run_simulation(
        bridge_port=args.port,
        push_demo=not args.no_push,
        make_map=not args.no_map,
    ))


if __name__ == "__main__":
    main()
