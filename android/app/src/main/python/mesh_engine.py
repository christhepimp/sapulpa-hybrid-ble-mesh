"""
Sapulpa mesh engine – runs inside the Android APK via Chaquopy.
No Folium, no sockets, no ADB. Kotlin injects BLE RX and receives TX via bridge.
"""

from __future__ import annotations

import math
import random
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple

bridge = None  # set from Kotlin


def _log(msg: str) -> None:
    print(msg)
    if bridge is not None:
        try:
            bridge.on_log(msg)
        except Exception:
            pass


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

GRID_SPACING_M = 200.0
BT_RANGE_M = 90.0
HARDWARE_BRIDGE_COUNT = 4
MAX_HOPS = 12


@dataclass
class MeshNode:
    lat: float
    lon: float
    node_id: int
    bd_addr: str = ""
    state: str = "idle"
    neighbors: List["MeshNode"] = field(default_factory=list)
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


_nodes: List[MeshNode] = []
_bridges: List[MeshNode] = []
_seen: Set[str] = set()
_running = False


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
                        n.neighbors.append(nodes[j])
                        nodes[j].neighbors.append(n)
                        edges += 1
    return edges


def designate_bridges(nodes: List[MeshNode], count: int = HARDWARE_BRIDGE_COUNT) -> List[MeshNode]:
    ranked = sorted(
        nodes,
        key=lambda n: abs(n.lat - SAPULPA_CENTER[0]) + abs(n.lon - SAPULPA_CENTER[1]),
        reverse=True,
    )
    out = []
    for n in ranked[:count]:
        n.is_bridge = True
        n.state = "bridge"
        out.append(n)
    return out


def flood(source: MeshNode, payload: bytes, max_hops: int = MAX_HOPS) -> dict:
    pkt_id = str(uuid.uuid4())
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
                if bridge is not None:
                    try:
                        meta = (
                            f'{{"from_node":{source.node_id},'
                            f'"bridge_node":{node.node_id},'
                            f'"bd_addr":"{node.bd_addr}",'
                            f'"pkt_id":"{pkt_id}"}}'
                        )
                        bridge.on_tx(payload.hex(), meta)
                    except Exception as e:
                        _log(f"bridge.on_tx error: {e}")
            for neigh in node.neighbors:
                if neigh.node_id not in visited:
                    nxt.append(neigh)
        frontier = nxt
        hop += 1

    return {
        "source": source.node_id,
        "reached": len(visited),
        "total": len(_nodes),
        "hops": hop,
        "bridges": bridges_hit,
        "pkt_id": pkt_id,
    }


def start_engine() -> str:
    global _nodes, _bridges, _running, _seen
    _log("Building Sapulpa grid…")
    _nodes = create_grid()
    edges = build_mesh(_nodes)
    _bridges = designate_bridges(_nodes)
    _seen.clear()
    _running = True
    msg = f"Engine ready: {len(_nodes)} nodes, {edges} links, {len(_bridges)} bridges"
    _log(msg)
    if bridge is not None:
        try:
            bridge.on_stats(len(_nodes), edges, len(_bridges))
        except Exception:
            pass
    return msg


def stop_engine() -> str:
    global _running
    _running = False
    _log("Engine stopped")
    return "stopped"


def is_running() -> bool:
    return _running


def inject_message(text: str) -> str:
    if not _nodes:
        return "engine not started"
    src = random.choice(_nodes)
    payload = text.encode("utf-8")[:40]
    _log(f"Inject from node {src.node_id}: {text[:40]}")
    result = flood(src, payload)
    summary = (
        f"Flood: src={result['source']} reached={result['reached']}/{result['total']} "
        f"bridges={result['bridges']}"
    )
    _log(summary)
    return summary


def on_ble_rx(payload_hex: str, peer: str = "") -> str:
    if not _nodes or not _bridges:
        return "engine not ready"
    try:
        payload = bytes.fromhex(payload_hex)
    except ValueError:
        return "bad hex"
    pkt_id = payload_hex[:16]
    if pkt_id in _seen:
        return "duplicate"
    _seen.add(pkt_id)
    if len(_seen) > 2000:
        _seen.clear()
    bridge_node = random.choice(_bridges)
    _log(f"BLE RX {len(payload)} B from {peer or '?'} → bridge {bridge_node.node_id}")
    result = flood(bridge_node, payload)
    return f"rx flood reached={result['reached']}"


def get_status() -> str:
    if not _nodes:
        return "idle"
    return f"running nodes={len(_nodes)} bridges={len(_bridges)}"


def get_bridge_coords() -> str:
    if not _bridges:
        return ""
    return ";".join(f"{b.node_id},{b.lat},{b.lon}" for b in _bridges)
