# Sapulpa Hybrid BLE Mesh (Chaquopy)

**Single self-contained APK** that embeds the Python Sapulpa mesh engine and drives real BLE radio on the phone.

- **No computer**
- **No ADB**
- **No Termux**
- **No root**

Python runs inside the app via [Chaquopy](https://chaquo.com/chaquopy/). When the simulated mesh reaches a bridge node, Kotlin broadcasts the payload with `BluetoothLeAdvertiser`. Scanned BLE packets are fed back into the Python engine.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│                 Android APK                 │
│  ┌─────────────────┐    ┌────────────────┐  │
│  │  Python         │◄──►│  Kotlin        │  │
│  │  mesh_engine.py │    │  HybridMesh    │  │
│  │  (Chaquopy)     │    │  Service       │  │
│  │  Sapulpa grid   │    │  BLE Adv/Scan  │  │
│  │  multi-hop flood│    │  Compose UI    │  │
│  └─────────────────┘    └────────────────┘  │
└─────────────────────────────────────────────┘
              ▲ physical RF ▼
         nearby phones / BLE devices
```

---

## Build

```bash
git clone https://github.com/christhepimp/sapulpa-hybrid-ble-mesh.git
cd sapulpa-hybrid-ble-mesh/android
```

Open **`android/`** in Android Studio (needs network once for Chaquopy + SDK).

**Build → Build APK(s)** → install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Chaquopy requires NDK ABIs (`arm64-v8a`, `x86_64`). First Gradle sync downloads the Python runtime into the APK (~30–50 MB larger).

---

## Use

1. Open **Sapulpa Hybrid Mesh**
2. Grant Bluetooth + Location (+ Notifications)
3. Tap **Start Hybrid** — Python builds the Sapulpa grid and starts BLE
4. Type a message → **Send** — floods the virtual mesh; bridge nodes trigger real BLE ads
5. Nearby phones running the same app inject scanned packets back into their Python engines

---

## Key files

| Path | Role |
|------|------|
| `app/src/main/python/mesh_engine.py` | Embedded Sapulpa grid + flood (Chaquopy) |
| `…/PythonBridge.kt` | Kotlin ↔ Python method bridge |
| `…/HybridMeshService.kt` | Foreground service, BLE, engine host |
| `…/MainActivity.kt` | Compose dashboard |
| `app/build.gradle.kts` | Chaquopy plugin + ABI filters |

---

## License

MIT
