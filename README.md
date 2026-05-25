# BandPin

**Shoulder-surfing-resistant PIN entry via the smartwatch wristband**

TH Köln · moxd lab · Mobile and Distributed Interactive Systems (MoDIS SoSe 26)
Team: Rafael Barros · Mahyar Aghazadeh
Supervisor: Prof. Dr. Matthias Böhmer

---

## What this project is

BandPin is a research prototype that investigates whether moving PIN entry away
from the smartwatch touchscreen — and onto the wristband — meaningfully reduces
the risk of shoulder-surfing attacks, while remaining usable enough for everyday use.

The starting point for this work is Khan et al.'s finding that **80% of 4-digit PINs
entered on a touchscreen can be reconstructed by an attacker after only 4 observations**.
BandPin asks: what happens to that number when the user taps the wristband instead of
the screen?

---

## Research question

> Does PIN entry via a capacitive sensor stripe on the smartwatch wristband provide
> measurably better shoulder-surfing resistance than conventional touchscreen PIN entry,
> while remaining usable and learnable?

Three dimensions are measured:

| Dimension | Metric |
|---|---|
| **Security** | PIN reconstruction success rate (attacker role) |
| **Usability** | Task completion time · error rate · NASA-TLX |
| **Learnability** | Performance change across repeated sessions |

---

## Where BandPin sits in the literature

Two HCI research threads rarely overlap: *wristband/case input* and *shoulder-surfing-resistant PINs*.

```
Wristband / case input          Shoulder-surfing-resistant PINs
────────────────────────        ────────────────────────────────
CaseTouch (Stanke et al. MUM'24)   2GesturePIN (Guerar et al. 2019)
MultiBezel (Reuter et al. CHI'25)  Khan et al. — 80% attack baseline
MultiBand  (Böhmer et al. 2026)

                    ↘        ↙
                      BandPin
               (security + wristband input)
```

BandPin is the first prototype to combine these threads on a smartwatch wristband.
The hardware builds directly on CaseTouch (Stanke et al., MUM '24) and MultiBand
(Böhmer et al., 2026), reusing the Trill Flex + ESP32 setup without any cutting or
hardware modification.

---

## How a digit is entered

The Trill Flex strip runs along the wristband below the watch face. It is divided into
three logical zones:

```
  wrist-proximal ◄──────── wristband ────────► fingertip-distal
  ┌─────────────┬─────────────────┬─────────────────────────────┐
  │    LEFT     │       MID       │            RIGHT             │
  │   (0–33%)   │   (33%–67%)    │          (67%–100%)          │
  └─────────────┴─────────────────┴─────────────────────────────┘
```

For each of the 4 PIN digits, the watch screen shows a **randomised** digit→zone
mapping, for example:

```
  LEFT: 0 3 7 9      MID: 1 4 8      RIGHT: 2 5 6
```

The user glances at the screen, finds their digit, then taps the matching zone on the
band — never touching the screen. An observer watching the wrist sees *which zone*
was tapped (roughly 33% of the information per digit), but not which digit, because
the mapping shuffles on every entry attempt. Video replay is therefore defeated.

When a zone contains multiple digits, the intra-zone tap position (left vs right within
the zone) disambiguates the exact digit. The watch confirms silently with a 30 ms haptic
pulse.

**Security property in one line:**
> Observer sees zone (~33% info per digit) · Randomised mapping defeats video replay ·
> Touchscreen shows nothing (100% occlusion of input).

---

## System architecture

BandPin is a three-node distributed system:

```
  ┌─────────────────┐     I²C      ┌──────────────────┐    BLE GATT    ┌──────────────────┐
  │   Trill Flex    │ ──────────►  │     ESP32         │ ─────────────► │  Galaxy Watch 4  │
  │  (wristband     │              │  (in band housing) │    NOTIFY      │   (Wear OS)      │
  │   sensor)       │              │                   │                │                  │
  │                 │              │ • Reads @ 100 Hz  │                │ • Renders PIN UI │
  │ • Capacitive 1D │              │ • Weighted        │                │ • Randomised     │
  │   array         │              │   centroid        │                │   zone mapping   │
  │ • 30 segments   │              │ • Zone classify   │                │ • PIN validation │
  │ • 15-bit res.   │              │ • Debounce 50 ms  │                │ • Haptic confirm │
  │ • Full length,  │              │ • BLE peripheral  │                │ • Trust anchor   │
  │   no cutting    │              │                   │                │                  │
  └─────────────────┘              └──────────────────┘                └──────────────────┘
```

### Data flow for a single tap

```
1. Finger touches band
2. Trill Flex → 30 capacitive values over I²C (every 10 ms)
3. ESP32 computes weighted centroid → normalised position [0.0–1.0]
4. Zone classifier: position → LEFT / MID / RIGHT  (with 4% hysteresis)
5. Debounce filter: contact held ≥ 50 ms → confirmed tap event
6. BLE GATT NOTIFY → 4-byte payload to Watch 4
7. Watch decodes zone + intra-zone position
8. PinEngine maps zone → digit (using current randomised mapping)
9. If PIN complete → validate against stored secret → haptic feedback
```

### BLE payload format (4 bytes, big-endian)

```
  Byte 0 : zone index         (0 = LEFT, 1 = MID, 2 = RIGHT)
  Byte 1 : intra-zone pos × 100  (0–100)
  Byte 2 : duration_ms high byte
  Byte 3 : duration_ms low byte
```

---

## File structure

```
bandpin/
│
├── README.md                   ← you are here
│
├── esp32_firmware/
│   ├── main.py                 ← entry point; flash this as main.py on the ESP32
│   ├── trill_reader.py         ← Trill Flex I²C driver + zone classifier + debounce
│   └── ble_peripheral.py       ← BLE GATT peripheral; notifies Watch 4 on each tap
│
├── watch_app/
│   └── BandPinApp.kt           ← full Wear OS app (BLE client + PIN engine + Compose UI)
│
├── shared/
│   └── constants.py            ← BLE UUIDs, zone definitions, PIN protocol constants
│                                  (Python reference; Kotlin equivalents are in BandPinApp.kt)
│
└── study_logger.py             ← records per-trial data (time, errors, condition) to CSV + JSON
```

---

## Module responsibilities

### `trill_reader.py`

| Class | Responsibility |
|---|---|
| `TrillFlex` | I²C driver — sends scan command, reads 30×2-byte values, computes weighted centroid |
| `ZoneClassifier` | Maps normalised position to LEFT/MID/RIGHT with hysteresis; emits confirmed tap events after debounce |
| `BandPinSensor` | Top-level 100 Hz loop; wires sensor → classifier → callback |

### `ble_peripheral.py`

Registers a custom GATT service and one notifiable characteristic. Packs each tap
event into 4 bytes and calls `gatts_notify()`. Restarts advertising automatically on
disconnect so the watch can reconnect without rebooting the ESP32.

### `main.py`

Six lines: instantiate `BandPinBLE`, define `on_tap` callback that calls
`ble.notify_tap()`, instantiate `BandPinSensor` with the callback, run the loop.

### `BandPinApp.kt`

| Class | Responsibility |
|---|---|
| `PinEngine` | Randomised digit→zone mapping; intra-zone disambiguation; PIN validation |
| `BleManager` | GATT client; scans for "BandPin"; subscribes to notifications; parses 4-byte payload |
| `BandPinViewModel` | State machine: `Scanning → Entering → Success / Failure`; Compose-friendly `StateFlow` |
| `BandPinApp` | Root Composable; reacts to state changes |
| `EnteringScreen` | Shows PIN progress dots + current zone→digit mapping chips |
| `MainActivity` | Wires BLE manager + viewmodel; triggers haptic on each tap |

### `study_logger.py`

Records `participant_id`, `condition` (bandpin / touchscreen), `trial_number`,
`completion_time_ms`, `num_errors`, and individual tap events. Writes two files per
session: a detailed `.json` (tap-level) and a summary `.csv` ready for pandas or R.

---

## Study design (overview)

**Within-subjects**, two conditions, counterbalanced:

- **Baseline:** standard touchscreen PIN on the Galaxy Watch 4
- **Treatment:** BandPin wristband entry

**User role:** participant enters a 4-digit PIN — measures completion time, error rate, NASA-TLX.

**Attacker role:** observer watches video recordings from three angles (top, side, tilted)
and tries to reconstruct the PIN — measures reconstruction success rate.

The primary hypothesis: BandPin reduces PIN reconstruction rate significantly below
Khan et al.'s 80% baseline, while keeping completion time and error rate at an
acceptable level.

---

## Hardware setup

| Component | Detail |
|---|---|
| Trill Flex | Full strip (30 segments, no cutting), mounted on wristband below watch face |
| ESP32 DevKitC | SDA → GPIO 21, SCL → GPIO 22, powered via USB during study sessions |
| Galaxy Watch 4 | 40 mm, Wear OS; receives BLE notifications from ESP32 |
| Wristband housing | 3D-printed clip to hold ESP32 and route I²C cable to sensor |

---

## Getting started

### Flash the ESP32 (MicroPython)

1. Install MicroPython on the ESP32 (`esptool.py` or Thonny).
2. Copy `esp32_firmware/trill_reader.py`, `esp32_firmware/ble_peripheral.py`, and
   `esp32_firmware/main.py` to the ESP32 root using `mpremote` or the IntelliJ
   MicroPython plugin.
3. Reset the board — it will advertise as **"BandPin"** immediately.

### Build and deploy the watch app

1. Open `watch_app/` as an Android/Wear OS project in Android Studio (or IntelliJ
   with the Android plugin).
2. Set the `targetSdk` to 30+ and add the required permissions to `AndroidManifest.xml`:
   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `VIBRATE`.
3. Pair the Galaxy Watch 4 via ADB over Wi-Fi and run the app.

### Run the study logger

```python
from study_logger import TrialLogger

logger = TrialLogger(output_dir="data/")
logger.start_trial("P01", "bandpin", trial_num=1)
# ... trial runs ...
logger.end_trial(correct=True)
logger.save(session_id="session_01")
```

---

## Key references

- Stanke et al. (2024). **CaseTouch: Occlusion-Free Touch Input by adding a Thin Sensor Stripe to the Smartwatch Case.** MUM '24. — hardware basis
- Böhmer et al. (2026). **MultiBand: Adding Multi-Touch to the Smartwatch Wristband for Extended Interaction.** — wristband input basis
- Khan, Hengartner & Vogel. **Evaluating Attack and Defense Strategies for Smartphone PIN Shoulder Surfing.** — 80% baseline attack figure
- Guerar et al. (2019). **2GesturePIN: Securing PIN-Based Authentication on Smartwatches.** — related security approach
- Reuter et al. (2025). **MultiBezel: Adding Multi-Touch to a Smartwatch Bezel to Control Music.** CHI EA '25.
