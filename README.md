# Sapulpa BLE Mesh (Standalone)

**Fully offline, on-device Bluetooth LE mesh.**  
No computer, no Python backend, no ADB, no internet, no root.

Multiple phones running this app automatically form a multi-hop store-and-forward mesh using only public `BluetoothLeAdvertiser` / `BluetoothLeScanner` APIs.

---

## How it works

1. Each phone is a **mesh node** with a random Node ID.
2. You type a short message → it is wrapped in a `MeshPacket` (UUID + hop count + payload).
3. The packet is broadcast via **BLE Manufacturer Specific Data**.
4. Neighbouring phones within ~30–80 m scan, detect the packet, check the UUID (loop prevention), decrement the hop count, and **re-advertise** it.
5. Messages hop phone-to-phone across the city without any infrastructure.

A **foreground service** keeps scanning + advertising alive when the app is backgrounded.

---

## Build the APK

```bash
git clone https://github.com/christhepimp/sapulpa-hybrid-ble-mesh.git
cd sapulpa-hybrid-ble-mesh/android
```

Open the `android/` folder in **Android Studio** → **Build → Build APK(s)**.

Or from the command line (with Android SDK installed):

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Install on two or more phones:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Use

1. Open **Sapulpa BLE Mesh** on each phone.
2. Grant Bluetooth + Location (+ Notifications on Android 13+).
3. Tap **Start Mesh**.
4. Type a short message and tap **Send**.
5. Watch the live log: TX / RX / RELAY events appear as packets hop between devices.

---

## Project structure (Android)

```
android/app/src/main/java/com/sapulpa/blemesh/
├── MainActivity.kt           # Compose dashboard UI
├── MeshEngine.kt             # Store-and-forward, UUID dedup, hop TTL
├── MeshPacket.kt             # Compact binary packet for BLE adv
└── MeshForegroundService.kt  # BLE scan/advertise loop + notification
```

---

## Limits

- Legacy BLE advertisements ≈ 31 bytes → payloads truncated to ~18 characters.
- Range is normal BLE outdoor range (~30–80 m depending on phones and environment).
- Best with 2+ physical devices; a single phone will only see its own heartbeats.

## License

MIT
