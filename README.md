# Sapulpa Hybrid BLE Mesh

**Unrooted** hybrid system: a Python spatial Bluetooth mesh emulator covering Sapulpa, Oklahoma, plus a normal user-space Android companion app that bridges the virtual mesh to real RF via ADB port-forwarding.

> No root, no custom HCI HAL, no SELinux tricks.  
> The phone only uses the public `BluetoothLeAdvertiser` / `BluetoothLeScanner` APIs.

---

## Architecture

```
┌──────────────────────────────┐          adb forward          ┌─────────────────────────────┐
│  Python “Sapulpa Mesh”       │  tcp://127.0.0.1:5555  ◄────► │  Android Companion App      │
│  • dense GPS grid            │                               │  (Kotlin / Jetpack Compose) │
│  • Haversine proximity mesh  │                               │  • TCP server :5555         │
│  • multi-hop store-forward   │                               │  • BLE Advertiser (TX)      │
│  • edge “bridge” nodes       │                               │  • BLE Scanner  (RX)        │
└──────────────────────────────┘                               └─────────────────────────────┘
         virtual city mesh                                              physical RF
```

1. **Python City Grid** builds thousands of software emulator nodes over Sapulpa bounds  
   (35.9600–36.0600 N, −96.1600–−96.0500 W) with ~80 m BLE range and Haversine distances.
2. Packets travel hop-by-hop inside the virtual mesh.
3. When a packet reaches a designated **bridge node**, the engine serialises it and pushes it over the ADB-forwarded socket.
4. The **unrooted Android app** receives the frame, then calls `BluetoothLeAdvertiser` so the phone’s real antenna broadcasts the payload.
5. Conversely, when the app’s `BluetoothLeScanner` hears a matching advertisement, it tunnels the bytes back to the Python mesh, where they are injected at a bridge node.

---

## Quick Start (no root)

### 1. Phone
- Install / sideload the companion APK (or open the `android/` project in Android Studio and run it).
- Grant Bluetooth + Location permissions.
- Tap **Start Bridge**. The app listens on port 5555.

### 2. Host – ADB tunnel
```bash
chmod +x scripts/setup_adb_bridge.sh
./scripts/setup_adb_bridge.sh          # defaults to port 5555
# or:  adb forward tcp:5555 tcp:5555
```

### 3. Host – Python mesh
```bash
pip install -r python/requirements.txt
./scripts/run_mesh.sh
# or:  python python/mesh_engine.py --port 5555
```

The engine will:
- build the Sapulpa grid,
- wire the proximity mesh,
- run a multi-hop flood,
- push the resulting payload to the phone for real RF broadcast,
- listen a few seconds for any air-side replies.

An interactive map `sapulpa_mesh_map.html` is written next to the engine.

---

## Project Layout

```
sapulpa-hybrid-ble-mesh/
├── python/
│   ├── mesh_engine.py      # full spatial mesh + ADB socket client
│   └── requirements.txt
├── android/
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── java/com/sapulpa/blemesh/MainActivity.kt
├── scripts/
│   ├── setup_adb_bridge.sh
│   └── run_mesh.sh
└── README.md
```

---

## Protocol (length-prefixed JSON)

**Host → Phone** (`mesh_to_rf`)
```json
{
  "type": "mesh_to_rf",
  "payload_b64": "<hex of raw bytes>",
  "meta": { "from_node": 42, "bridge_node": 0, "bd_addr": "02:…" },
  "ts": 1710000000.0
}
```

**Phone → Host** (`rf_to_mesh`) – same framing, type `rf_to_mesh`.

Frames are sent as `[4-byte big-endian length][utf-8 JSON]`.

---

## Building the Android APK

```bash
cd android
# open in Android Studio, or:
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Minimum SDK 26, target 34. Requires a device/emulator with BLE.

---

## Notes & Limits

- Legacy BLE advertisements are limited to ~31 bytes; the demo truncates payloads to 20 bytes of service data. For larger messages use a connectable GATT service or multiple packets.
- The current scanner→Python reverse path is best-effort while the TCP client stays connected; extend `handleClient` if you need continuous bidirectional streaming.
- Grid spacing defaults to 70 m for a dense mesh; change `GRID_SPACING_M` / `BT_RANGE_M` in `mesh_engine.py` as needed.

## License

MIT
