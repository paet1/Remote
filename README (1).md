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

## Remote Commands (SWCAN)

The command sequences are reproduced 1:1 from tested nRF Connect macros.

### SWCAN Wake Sequence (tested, exact timings)

| # | Command | Wait | Description |
|---|---------|------|-------------|
| 1 | `ATE0` | 200 ms | Echo off |
| 2 | `STP 61` | 150 ms | SWCAN 11-bit 33.3 kbps |
| 3 | `STPO` | 150 ms | Open protocol |
| 4 | `STCSWM 2` | 30 ms | HV wakeup transceiver mode |
| 5 | `ATSH100` | 50 ms | Set header 0x100 |
| 6 | `ATRTR` | 20 ms | Send RTR frame (HV pulse) |
| 7 | `STCSWM 3` | 200 ms | Return to normal mode |
| 8 | `ATSH621` | 50 ms | Set header 0x621 |
| 9 | `00FFFFFFFFFF0000` | 720 ms | BCM wake frame |
| 10 | `STP 62` | 150 ms | SWCAN 29-bit 33.3 kbps |
| 11 | `STPO` | 150 ms | Open protocol |
| 12 | `ATSH1024E097` | 50 ms | Extended 29-bit header |

### Vehicle Commands (on extended CAN ID 0x1024E097)

**Lock:**
`0001FF` → 180 ms → `0C00FF` → 1250 ms → `0000FF`

**Unlock all doors:**
`0003FF` → 180 ms → `0C00FF` → 1250 ms → `0000FF`

**Unlock driver door:**
`0002FF` → 180 ms → `0C00FF` → 1250 ms → `0000FF`

**Open trunk:**
`0200FF`

**Preheat ON:**
`8001FF` → 360 ms → `0000FF`

**Preheat OFF:**
`4001FF` → 360 ms → `0000FF`

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
