# Build a downloadable APK (no root)

This environment has **no Android SDK and no network**, so a binary `.apk` cannot be compiled here.  
On any machine with Android Studio (or the command-line SDK) you can produce the APK in ~2 minutes.

## Option A – Android Studio (easiest)

1. Clone or download this repo:
   ```bash
   git clone https://github.com/christhepimp/sapulpa-hybrid-ble-mesh.git
   cd sapulpa-hybrid-ble-mesh/android
   ```
2. Open the **`android/`** folder in Android Studio (File → Open).
3. Let Gradle sync finish (it downloads the SDK / dependencies the first time).
4. Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. When finished, click **locate** in the notification.  
   The file is:
   ```
   android/app/build/outputs/apk/debug/app-debug.apk
   ```
6. Install on a phone:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Option B – Command line

```bash
cd sapulpa-hybrid-ble-mesh/android

./gradlew :app:assembleDebug

# APK appears here:
ls -la app/build/outputs/apk/debug/app-debug.apk
```

## After install

```bash
# On the host
./scripts/setup_adb_bridge.sh          # adb forward tcp:5555 tcp:5555

# On the phone: open “Sapulpa BLE Mesh” → tap Start Bridge

# On the host
./scripts/run_mesh.sh
```

## Release (signed) APK

For a shareable release build, create a keystore and run:

```bash
./gradlew :app:assembleRelease
```

(You will need a `signingConfigs` block in `app/build.gradle.kts`.)

---

**Minimum device:** Android 8.0 (API 26) with BLE support.  
**Permissions:** Bluetooth Scan / Advertise / Connect + Location (required by the system for BLE scans).  
**No root required.**
