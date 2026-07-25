# Sapulpa Hybrid BLE Mesh

**Cross-town messaging with fixed BLE relays + virtual emulators.**

```
Phone ──BLE──► Fixed relay ──BLE──► Fixed relay ── … ──► Phone
                  ▲                      ▲
                  └── software emulators fill routing ──┘
```

- **Virtual emulators** – dense software nodes over the whole Sapulpa map (multi-hop in the app).
- **Fixed BLE relays** – planned GPS slots every **~80 m**. Put a real radio there (spare phone, Pi, ESP32) running this app.
- When fixed relays form a continuous chain, **two phones anywhere along the chain can talk** without random people in the middle.

## Why fixed relays?

Phone BLE only reaches ~30–80 m. Sapulpa is kilometers across.  
So we mark a **grid of fixed positions**. Deploy cheap always-on BLE nodes at those coordinates. Packets hop:

`phone → fixed → fixed → … → fixed → phone`

The app’s virtual mesh still runs the routing logic and can demo the full path in software even before all hardware is installed.

## Numbers (default)

| Setting | Value |
|---------|--------|
| Virtual emulator spacing | ~120 m |
| Fixed relay spacing | **~80 m** (BLE-friendly) |
| Virtual radio range | ~100 m |

~80 m spacing over the configured bounds implies **on the order of a few thousand fixed slots** for full continuous coverage. You can raise `FIXED_RELAY_SPACING_M` in `mesh_engine.py` (e.g. 200–500 m) for a sparser backbone (fewer devices, possible gaps).

## Build

```bash
git clone https://github.com/christhepimp/sapulpa-hybrid-ble-mesh.git
cd sapulpa-hybrid-ble-mesh/android
# Android Studio → Build APK
```

## Deploy

1. Install the APK on **user phones** (send/receive).
2. Install the same APK (or a headless build) on **fixed devices** at each relay coordinate from `get_bridge_coords()` / log.
3. Leave fixed devices powered and **Start Hybrid**.
4. Phones join at the edges of the chain; data walks relay-to-relay across town.

## Use on a phone

1. **Start Hybrid** → log shows emulator count + fixed relay count.  
2. **Send** a message → flood through virtual mesh; every fixed/bridge node triggers BLE TX.  
3. Nearby fixed node or second phone scans → injects → continues the path.

## Key file

`app/src/main/python/mesh_engine.py` — grid, fixed-relay placement, flood, phone RF hooks.

## License

MIT
