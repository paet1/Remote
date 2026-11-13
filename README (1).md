# Ampera-e vLinker Remote – TEST BUILD

Minimal Android test app for vLinker MC-IOS over BLE.

## Functions
- Connect to `vLinker MC-IOS`
- **Live SOC** (State of Charge) via HS-CAN polling (PID 228334 @ ECU 7E4)
- **Battery info**: temperature, HV voltage, HV current, capacity
- Lock
- Unlock driver door
- Unlock all doors
- Open trunk
- Preheat ON
- Preheat OFF
- Shows BLE/vLinker replies in real-time log

## BLE (vLinker MC-IOS)
- Service: `000018f0-0000-1000-8000-00805f9b34fb`
- RX indication: `00002af0-0000-1000-8000-00805f9b34fb`
- TX write: `00002af1-0000-1000-8000-00805f9b34fb`

## Live SOC / Battery (HS-CAN, protocol 6, 500 kbps)

| PID | ECU | Description | Formula |
|-----|-----|-------------|---------|
| 228334 | 7E4 | SOC displayed | A × 100 / 255 % |
| 22434F | 7E4 | Battery temperature | A − 40 °C |
| 22436B | 7E4 | HV charger voltage | (A×256+B) / 2 V |
| 22436C | 7E4 | HV charger current | (A×256+B) / 20 A |
| 2245F9 | 7E4 | Battery capacity (2019+) | (A×256+B) × 0.0032 kWh |

Init: `ATSP6; ATST96; ATSH7E4`

## Remote Commands (SWCAN, STP 61, 33.3 kbps)

The wake sequence and vehicle commands reproduce the OnStar/MyVoltStar SWCAN protocol:

### SWCAN Wake Sequence
1. `ATZ`, `ATE0`, `ATH1`, `ATAL`, `ATS1` – adapter setup
2. `STP 61` – switch to SWCAN 33.3 kbps
3. `ATCAF0` – CAN auto formatting off
4. `STCSWM 2` – high-voltage wakeup mode
5. Send wake frames on headers 100, 620, 621, 622, 627, 62C, 62D
6. `STCSWM 3` – return to normal mode

### Vehicle Commands (extended 29-bit CAN ID 0x1024E097)
- `ATCP 10` + `ATSH 24 E0 97` for the extended header
- Lock: `00 01 FF` → `0C 00 FF` → `00 00 FF`
- Unlock driver: `00 02 FF` → `0C 00 FF` → `00 00 FF`
- Unlock all: `00 03 FF` → `0C 00 FF` → `00 00 FF`
- Open trunk: `02 00 FF`
- Preheat ON: `00 01 FF` → `80 01 FF` → `00 00 FF`
- Preheat OFF: `00 01 FF` → `40 01 FF` → `00 00 FF`

## Build on GitHub from Android phone
1. Create a new empty GitHub repository.
2. Upload the CONTENTS of this ZIP to the repository root. The `.github` folder must also be present.
3. Open the repository → Actions → `Build Android APK`.
4. Run the workflow (or it starts after push).
5. Open the completed workflow → Artifacts → `AmperaE-vLinker-debug`.
6. Download ZIP, extract `app-debug.apk`, and install it.

Android may ask permission to install unknown apps for your browser/file manager.

## Safety
This is an experimental test app. Use only on your own vehicle and while the vehicle is safely parked. The buttons intentionally require a confirmation dialog before sending a vehicle command.
