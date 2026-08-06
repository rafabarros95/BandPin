# BandPin

Eyes-free PIN entry on the smartwatch wristband

*TH Köln · moxd lab · Mobile and Distributed Interactive Systems (MODI SoSe 26)
Team: Rafael Barros · Mahyar Aghazadeh*

---

## What this project is

Entering a PIN on a smartwatch means operating a keypad of roughly three
centimetres with a fingertip that covers a large share of it. BandPin moves the
whole task off the display and onto the wristband instead.

Two capacitive Trill Flex strips are mounted above and below the watch case.
Each strip is divided into five zones, so the two strips together cover the
digits 0 to 9. An ESP32 in the band runs the complete gesture engine and streams
high-level events to a Wear OS app over BLE.

The primary research interest is usability and learnability: can people actually
learn this kind of interaction, and how quickly does it become workable? Reduced
exposure to onlookers is a secondary motivation, not the goal of the project.

The watch screen never shows a digit, only four progress dots. That is a hard
constraint of the concept rather than a styling choice, so preserve it in any
UI change.

---

## Research question

> Can a four-digit PIN be entered reliably and eyes-free on capacitive sensor
> strips mounted on a smartwatch wristband, using only haptic feedback, and can
> users learn this interaction well enough to perform it without looking?

Shoulder-surfing resistance would require a separate observer study, which has
not been run. See the manuscript for what can and cannot be claimed from the
data collected so far.

---

## How a digit is entered

```
   strip A (top of case)      digits 0 1 2 3 4
  ┌────┬────┬────┬────┬────┐
  │ 0  │ 1  │ 2  │ 3  │ 4  │
  └────┴────┴────┴────┴────┘
        ▓▓▓ watch case ▓▓▓          <- the tactile landmark between the ranges
  ┌────┬────┬────┬────┬────┐
  │ 5  │ 6  │ 7  │ 8  │ 9  │
  └────┴────┴────┴────┴────┘
   strip B (palm side)        digits 5 6 7 8 9
```

The layout is fixed. There is no randomised mapping to read off the screen,
because reading anything off the screen would defeat the point.

| Gesture | Event | Meaning |
|---|---|---|
| Finger lands | `DOWN` | contact begins, exploration is silent |
| Slide across a zone boundary | `TICK` | entered the next zone |
| Double-tap in a zone | `SELECT` | digit entered |
| Hold about 2 s without sliding | `DELETE` | remove last digit, repeats while held |
| Finger lifts | `UP` | contact ends |

Sliding cancels a pending tap in the firmware, so an exploratory sweep can never
be mistaken for a selection. That is what makes the interaction safe to perform
blind: you slide and count ticks to find a zone, then double-tap to commit.

### Haptic vocabulary

All feedback comes from the watch. The patterns are as implemented in
`MainActivity.playHaptic`.

| Cue | Pattern | When |
|---|---|---|
| Tick | 30 ms at amplitude 80 | a zone boundary was crossed |
| Select | 220 ms at amplitude 210 | a digit was selected and it matches the expected one |
| Wrong digit | two 60 ms pulses at amplitude 180 | the selected digit does not match |
| Delete | currently identical to wrong digit, see Known issues | a digit was removed |
| Success | three rising pulses, 70 / 160 / 350 ms at amplitude 130 / 190 / 255 | the PIN is correct, or both Set-PIN entries match |
| Failure | 900 ms at amplitude 255 | the PIN is wrong, or the two Set-PIN entries differ |

---

## System architecture

All the intelligence lives on the ESP32. The watch is a thin client.

```
Trill Flex x2 ──I2C──► ESP32 ──BLE GATT NOTIFY──► Galaxy Watch 4
(both at 0x48,         gesture engine             haptics, dots UI, CSV logs
 separate buses)       @ 100 Hz
```

### Wiring

Both Trill Flex boards ship with the same factory address, 0x48. Rather than
re-addressing one board and splitting the I2C lines with a breadboard or a
Y-cable inside the band, each strip gets its own I2C bus. The ESP32 has two
hardware controllers, so both strips keep 0x48 and are wired straight to the
board with no extra components.

| | SDA | SCL | Address | Bus |
|---|---|---|---|---|
| Strip A, digits 0 to 4 | GPIO 25 | GPIO 26 | 0x48 | `TwoWire(1)` |
| Strip B, digits 5 to 9 | GPIO 21 | GPIO 22 | 0x48 | `Wire` |

Common ground, I2C clock at 400 kHz. Both strips are optional at boot and are
re-probed once per second while running, so a strip can be unplugged and
reconnected without restarting the ESP32.

### Active region of the strip

Only part of each Trill Flex is mounted on the band and actually reachable, so
the firmware uses the fraction from `ACTIVE_START` (0.08) to `ACTIVE_END` (0.39)
of the raw sensor length. Touches outside that window are discarded, and
positions inside it are rescaled to 0.0 to 1.0 before being split into the five
zones. Adjust these two constants if the strips are remounted.

### Gesture parameters

Tunable at the top of the firmware, meant to be pilot-tested.

| Constant | Value | Meaning |
|---|---|---|
| `TAP_MAX_MS` | 250 | longest contact that still counts as a tap |
| `DOUBLE_TAP_GAP_MS` | 400 | longest pause between the two taps |
| `HOLD_DELETE_MS` | 2000 | hold this long without sliding to delete |
| `HOLD_REPEAT_DELETE_MS` | 600 | keep holding to delete again, every |
| `ZONE_HYSTERESIS` | 0.04 | anti-flicker margin, fraction of strip length |
| `NUM_ZONES` | 5 | zones per strip |
| `SAMPLE_INTERVAL_MS` | 10 | sensor loop, 100 Hz |

### BLE event payload, 6 bytes

| Byte | Field | Values |
|---|---|---|
| 0 | event type | 0 `DOWN`, 1 `TICK`, 2 `UP`, 3 `SELECT`, 4 `DELETE` |
| 1 | strip | 0 for A, digits 0 to 4; 1 for B, digits 5 to 9 |
| 2 | digit | 0 to 9, already offset by the firmware |
| 3 | position | 0 to 100, normalised position inside the active region |
| 4 to 5 | board time | low 16 bits of `millis()`, big-endian |

Service `4A420001-1000-8000-0080-00805F9B34FB`, characteristic
`4A420002-1000-8000-0080-00805F9B34FB`, device name `BandPin`.

The protocol is duplicated in three files that must stay in sync:
`shared/constants.py` as the reference, `BandPinFirmware.ino`, and
`BandBleClient.kt`. Change one, change all three.

---

## Repository layout

```
BandPin/
├── Backend/
│   ├── BandPinFirmware/BandPinFirmware.ino   <- ESP32 gesture engine and BLE server
│   └── Test_touch/sketch_jun27a/             <- raw Trill sensor test sketch
│
├── Frontend/                                 <- Wear OS app, Kotlin and Compose
│   ├── app/src/main/java/com/android/bandpinwatch/
│   │   ├── ble/BandBleClient.kt              <- scan, connect, decode 6-byte events
│   │   ├── presentation/MainActivity.kt      <- permissions, navigation, haptics
│   │   ├── presentation/PinInputController.kt<- input and study state machine
│   │   ├── presentation/screen/              <- menu, Set-PIN, Enter-PIN screens
│   │   └── study/TrialLogger.kt              <- events.csv and trials.csv
│   ├── Evulation/                            <- exported study data, one folder per session
│   └── export_evaluation.ps1                 <- pulls the CSVs and builds Excel files
│
├── shared/constants.py                       <- protocol reference
└── TESTING.md                                <- 3-stage hardware verification
```

---

## Getting started

### Firmware

Built and flashed from the Arduino IDE. There is no CLI build.

1. Boards Manager, install esp32 by Espressif Systems, then select ESP32 Dev Module.
2. Library Manager, install Trill by Bela.
3. Open `Backend/BandPinFirmware/BandPinFirmware.ino` and upload.
4. Serial Monitor at 115200 baud. Every gesture prints an `[EVT]` line, so the
   whole gesture engine can be verified without the watch.

### Watch app

From `Frontend/`:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Deployment target is a Galaxy Watch 4 over wireless debugging, paired with
`adb pair` and `adb connect`. Grant the Bluetooth permission on first launch or
the app never scans.

### Running a session

The menu offers Enter PIN and Set PIN. Set PIN asks for the new PIN twice and
stores it once both entries match; the stored PIN survives restarts. During
entry the screen shows only the four dots, and the app closes and reopens itself
after each successful entry while keeping the BLE connection alive.

### Exporting study data

`Frontend/export_evaluation.ps1` reads both CSV files off the watch over ADB and
writes one folder per trial containing a CSV and an Excel version. Set `$device`
to the current `ip:port` of the watch and `$baseDir` to the output directory
before running it; both are hardcoded at the top of the script. It needs Python
with `openpyxl` on the PATH.

The equivalent manual pull is:

```
adb -s <ip:port> shell run-as com.android.bandpinwatch cat files/bandpin_study/events.csv
```

`events.csv` holds one row per band event plus the `TRIAL_START`,
`TRIAL_END_SUCCESS`, `TRIAL_END_FAILED` and `TRIAL_CANCELLED` markers.
`trials.csv` holds one row per completed trial with the target and entered PIN,
correctness, error counts, entry and completion time, and the gesture counts.

There are no automated tests. Verification is the hardware procedure in
[TESTING.md](TESTING.md).

---

## Study data

`Frontend/Evulation/` currently holds seven exported sessions covering
participants P1 to P6, with 14 completed trials and roughly 590 logged events.
Folders are named `<session>_<participant>`, and P1 appears in two sessions.

---

## Known issues

- Delete and wrong digit produce the same vibration. `MainActivity.playHaptic`
  lists `HapticCue.DELETE` in two branches of the same `when`, and the first one
  it shares with `WRONG_DIGIT` wins, so the distinct delete pattern below it is
  unreachable. The two cues need to be distinguishable for an eyes-free design.
- The `condition` column comes out empty in the exported trials. It is derived
  from `maxDigit`, which defaults to the single-strip value and has no control
  in the current user interface.
- `participantNumber` and `maxDigit` are not persisted, so they reset when the
  app restarts itself after an entry.
- The target PIN is fixed rather than generated per trial, so learning effects
  and digit-position effects are confounded.
- `EnterPinScreen.kt` is an empty placeholder. The entry UI still lives in
  `MainActivity`.
- The PIN is stored in `SharedPreferences` in the clear, and the logs record
  target and entered PINs. That is fine for a lab prototype and nothing else.
- The ESP32 is powered over USB, so the assembly is not yet a self-contained
  wearable.

---

## Key references

- Brudy et al. (2019). Cross-Device Taxonomy. CHI '19. https://doi.org/10.1145/3290605.3300792
- Cauchard et al. (2016). ActiVibe: Design and Evaluation of Vibrations for Progress Monitoring. CHI '16. https://doi.org/10.1145/2858036.2858046
- Khan, Hengartner and Vogel (2018). Evaluating Attack and Defense Strategies for Smartphone PIN Shoulder Surfing. CHI '18. https://doi.org/10.1145/3173574.3173738
- Mäkelä et al. (2021). Hidden Interaction Techniques. CHI '21. https://doi.org/10.1145/3411764.3445504
- Petersen, Reuter and Böhmer (2026). MultiBand: Adding Multi-Touch to the Smartwatch Wristband. CHI EA '26. https://doi.org/10.1145/3772363.3799304
- Reuter et al. (2025). MultiBezel: Adding Multi-Touch to a Smartwatch Bezel to Control Music. CHI EA '25. https://doi.org/10.1145/3706599.3720156
- Stanke et al. (2024). CaseTouch: Occlusion-Free Touch Input by adding a Thin Sensor Stripe to the Smartwatch Case. MUM '24. https://doi.org/10.1145/3701571.3701583
- Gehman (2025). A User-Centered Approach to Haptic Interface Design. MSc thesis, Penn State.
- Tkacz (2024). A Comparison of Haptic and Visual Support for Navigation in an Audio-Based City Game. MSc thesis, Tampere University.
