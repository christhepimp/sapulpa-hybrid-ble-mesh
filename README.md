# Sapulpa Hybrid BLE Mesh

**Cross-town multi-hop without intermediate phones.**

Thousands of **software Bluetooth emulators** form a virtual mesh over the map of Sapulpa, Oklahoma. Data travels hop-by-hop through those emulators. Real phones only attach at edge **bridge** nodes over normal BLE — so packets can cross town even when no other phone is in the middle.

```
  Phone A (BLE)                Phone B (BLE)
       │                            ▲
       ▼                            │
  [bridge node] ── virtual ── [bridge node]
       │         emulators         │
       └──── thousands of hops ────┘
              (no phones needed)
```

## How it works

1. **On Start Hybrid**, Python (via Chaquopy) builds a dense GPS grid of software BLE nodes covering Sapulpa (~120 m spacing, ~100 m virtual range).
2. Nodes within range become virtual neighbours (Haversine distance).
3. Outermost nodes are marked **bridges** — the only ones allowed to use the phone’s real radio.
4. You type a message → it is injected at a random emulator → **store-and-forward flood** across the virtual mesh.
5. When a bridge is reached, Kotlin calls `BluetoothLeAdvertiser` so the **phone blasts the packet on real RF**.
6. Another phone (or a BLE device) that scans it feeds the bytes back into *its* engine at a bridge → flood continues in software.

**No intermediate phones. No internet. No ADB. No root.**

## Build APK

```bash
git clone https://github.com/christhepimp/sapulpa-hybrid-ble-mesh.git
cd sapulpa-hybrid-ble-mesh/android
# Open in Android Studio → Build → Build APK(s)
```

Requires one-time network for Chaquopy Python runtime (~30–50 MB in the APK).

## Use

1. Install on one or more phones.
2. **Start Hybrid** → watch log: `READY: N software emulators…`
3. **Send** a short message → log shows virtual hops, then `BRIDGE→PHONE RF`.
4. A second phone nearby can scan the BLE ad and inject into its own mesh.

## Key files

| File | Role |
|------|------|
| `app/src/main/python/mesh_engine.py` | Dense Sapulpa emulator grid + multi-hop flood |
| `PythonBridge.kt` | In-process Python ↔ Kotlin |
| `HybridMeshService.kt` | BLE advertiser/scanner + engine host |
| `MainActivity.kt` | Compose UI |

## License

MIT
