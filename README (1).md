# Ampera-e vLinker Remote – TEST BUILD

Minimal Android test app for vLinker MC-IOS over BLE.

## Functions
- Connect to `vLinker MC-IOS`
- Lock
- Unlock
- Preheat ON
- Preheat OFF
- Shows BLE/vLinker replies

BLE:
- Service: `000018f0-0000-1000-8000-00805f9b34fb`
- RX indication: `00002af0-0000-1000-8000-00805f9b34fb`
- TX write: `00002af1-0000-1000-8000-00805f9b34fb`

The wake sequence and vehicle commands reproduce the tested nRF Connect macro sequence:
- SWCAN wake using STP 61 / STCSWM 2, header 100 RTR, STCSWM 3, header 621
- switch to STP 62
- extended header 1024E097
- Lock: 00 01 FF -> 0C 00 FF -> 00 00 FF
- Unlock: 00 03 FF -> 0C 00 FF -> 00 00 FF
- Preheat ON: 80 01 FF -> 00 00 FF
- Preheat OFF: 40 01 FF -> 00 00 FF

## Build on GitHub from Android phone
1. Create a new empty GitHub repository.
2. Upload the CONTENTS of this ZIP to the repository root. The `.github` folder must also be present.
3. Open the repository -> Actions -> `Build Android APK`.
4. Run the workflow (or it starts after push).
5. Open the completed workflow -> Artifacts -> `AmperaE-vLinker-debug`.
6. Download ZIP, extract `app-debug.apk`, and install it.

Android may ask permission to install unknown apps for your browser/file manager.

## Safety
This is an experimental test app. Use only on your own vehicle and while the vehicle is safely parked. The buttons intentionally require a confirmation dialog before sending a vehicle command.
