# BandPin

**Learning haptic, eyes-free PIN entry via two capacitive strips on a smartwatch**

TH Köln · moxd lab · Mobile and Distributed Interactive Systems (MoDIS SoSe 26)
Team: Rafael Barros · Mahyar Aghazadeh
Supervisor: Prof. Dr. Matthias Böhmer

> Evolved from CasePin — building on CaseTouch (Stanke et al., MUM '24) and
> MultiBand (Petersen, Reuter & Böhmer, CHI EA '26). The primary goal is
> **interaction & learnability**; security is a secondary, measured aspect.

---

## Research question

> Can users effectively learn haptic, eyes-free PIN entry via two capacitive
> strips on a smartwatch — how do speed, accuracy and display dependence
> develop with practice — and does shifting input off the screen also reduce
> the information exposed to observers?

---

## Interaction concept — how a digit is entered, by feel

Fixed 5 + 5 layout across two Trill Flex strips (no randomisation — that was CasePin):

```
Strip A (top of wrist)       Strip B (palm side)
┌───┬───┬───┬───┬───┐        ┌───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │        │ 5 │ 6 │ 7 │ 8 │ 9 │
└───┴───┴───┴───┴───┘        └───┴───┴───┴───┴───┘
```

| Gesture | Meaning |
|---|---|
| **Slide** along a strip | One vibration tick per zone boundary — counting ticks gives the position, no display needed |
| **Double-tap** | Select the current digit (frequent action → cheapest gesture) |
| **Hold ~2 s** | Delete the last digit (rare action → deliberate gesture) |
| **4th digit** | Auto-confirm — no separate confirm button |

The watch screen shows **only ● ● ● ● progress dots** — never the digits.
With practice, users jump directly to memorised positions: that learning
effect is what the study measures.

---

## System architecture

```
Trill Flex × 2  ──I²C──►  ESP32  ──BLE GATT NOTIFY──►  Galaxy Watch 4
(strip A 0x48,            (zone detection,             (haptic ticks,
 strip B 0x49)             gesture engine)              dots UI, trial logger)
```

The ESP32 runs the full gesture engine and sends compact high-level events;
the watch only vibrates, renders dots, and logs.

### BLE event payload (6 bytes)

| Byte | Content |
|---|---|
| 0 | event: 0=DOWN · 1=TICK · 2=UP · 3=SELECT · 4=DELETE |
| 1 | strip: 0=A · 1=B |
| 2 | digit 0–9 (already strip-offset) |
| 3 | position along strip × 100 |
| 4–5 | ESP32 `millis()` low 16 bits (latency analysis) |

Service `4A420001-…`, characteristic `4A420002-…` (NOTIFY), device name **BandPin**.
Full constant list: [shared/constants.py](shared/constants.py).

---

## File structure

```
BandPin/
├── Backend/
│   ├── BandPinFirmware/
│   │   └── BandPinFirmware.ino   ← THE firmware: 2× Trill Flex + gesture engine + BLE
│   └── Test_touch/
│       └── sketch_jun27a.ino     ← raw-data hardware test (kept for debugging)
│
├── Frontend/                     ← Wear OS app (Android Studio project)
│   └── app/src/main/java/com/android/bandpinwatch/
│       ├── ble/BandBleClient.kt              ← BLE scan/connect/notify client
│       ├── study/TrialLogger.kt              ← per-event + per-trial CSV on the watch
│       └── presentation/
│           ├── MainActivity.kt               ← permissions, haptics, screens
│           └── PinInputController.kt         ← study state machine (SETUP→ENTERING→RESULT)
│
├── shared/constants.py           ← protocol reference (UUIDs, events, gesture params)
├── study_logger.py               ← laptop-side logger (optional, serial-based analysis)
│
├── esp32_firmware/               ← DEPRECATED: old CasePin MicroPython concept
└── watch_app/                    ← DEPRECATED: old CasePin Kotlin sketch
```

---

## Getting started

### 1. Flash the ESP32 (Arduino IDE)

1. Install the **ESP32 board package** and the **Trill** library (Library Manager → "Trill").
2. Open `Backend/BandPinFirmware/BandPinFirmware.ino`, select your ESP32 board + port, upload.
3. Serial monitor at **115200 baud** — you should see:
   `strip A ready (digits 0-4)` … `[BLE] advertising as 'BandPin'`.
4. Every gesture prints a line (`[EVT] TICK strip=A digit=2 …`) — you can verify
   the whole gesture engine before the watch is even paired.

**Wiring (both strips share the I²C bus):** SDA → GPIO 21, SCL → GPIO 22, 3V3, GND.

**Strip B:** change its I²C address to **0x49** via the solder jumpers on the back
of the Trill Flex board (Flex range: 0x48–0x4F). Until strip B is connected the
firmware runs single-strip automatically (digits 0–4 only).

### 2. Build & run the watch app

1. Open `Frontend/` in Android Studio.
2. Connect the Galaxy Watch 4 (ADB over Wi-Fi: Settings → Developer options → Wireless debugging).
3. Run — grant the Bluetooth permission on first launch.
4. The dot at the bottom of the screen turns **green** when the band is connected.

### 3. Run a study trial

1. **Setup screen:** pick participant (± chips), read the target code, choose the
   digit range (`0–4` while only strip A is mounted, `0–9` with both), tap **Start**.
2. **Entry screen:** dots only. Slide → tick per zone, double-tap → digit,
   hold ~2 s → delete. 4th digit auto-confirms.
3. **Result screen:** ✓/✗ + entry time → **Next trial**.

### 4. Pull the logged data

```
adb pull /data/data/com.android.bandpinwatch/files/bandpin_study .
```

Two CSVs per session: `events.csv` (one row per band event) and `trials.csv`
(one row per trial: target, entered, correct, entry time, tick/delete counts).

---

## Gesture parameters (pilot-test these)

All in one place at the top of `BandPinFirmware.ino`:

| Parameter | Default | Meaning |
|---|---|---|
| `TAP_MAX_MS` | 250 | max contact time to count as a tap |
| `DOUBLE_TAP_GAP_MS` | 400 | max pause between the two taps |
| `HOLD_DELETE_MS` | 2000 | hold duration for delete |
| `ZONE_HYSTERESIS` | 0.04 | anti-flicker margin at zone boundaries |

---

## Key references

- Petersen, Reuter & Böhmer (2026). **MultiBand.** CHI EA '26 — direct predecessor (Trill Flex + ESP32 on the band)
- Stanke et al. (2024). **CaseTouch.** MUM '24 — origin of the sensor-stripe idea
- Perrault et al. (2013). **WatchIt.** CHI '13 — eyes-free strap gestures are viable & measurable
- Ahn et al. (2015). **BandSense.** CHI EA '15 — two-sensor band number entry (no learnability study)
- Khan, Hengartner & Vogel (2018). **PIN shoulder surfing.** CHI '18 — secondary security motivation
