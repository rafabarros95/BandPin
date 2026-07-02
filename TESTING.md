# BandPin — Test Guide

Step-by-step instructions to verify the end-to-end prototype on a fresh machine.
Test in this order — each stage isolates one layer, so if something fails you
know exactly where the problem is.

---

## What you need

**Hardware**
- ESP32 DevKitC + USB cable
- 1× Trill Flex connected: **SDA → GPIO 21, SCL → GPIO 22, VCC → 3V3, GND → GND**
  (a second strip is optional; see [Strip B](#adding-strip-b) at the end)
- Galaxy Watch 4 (charged, Wi-Fi on the same network as your PC)

**Software**
- Arduino IDE 2.x
  - Boards Manager → install **"esp32" by Espressif Systems**
  - Library Manager → install **"Trill"** (by Bela)
- Android Studio (any recent version)

---

## Stage 1 — Firmware + gesture engine (no watch needed, ~10 min)

1. Open `Backend/BandPinFirmware/BandPinFirmware.ino` in Arduino IDE.
2. Select board **ESP32 Dev Module** + the right COM port, click **Upload**.
3. Open the **Serial Monitor at 115200 baud**. Expected boot log:

   ```
   [BandPin] strip A ready (digits 0-4)
   [BandPin] strip B (0x49) not found — running single-strip (digits 0-4 only)
   [BLE] advertising as 'BandPin'
   [BandPin] ready. Touch the strip.
   ```

   The strip-B line is **normal** with one strip. If it loops
   `waiting for strip A…`, check the wiring above.

4. Test each gesture on the strip and watch the `[EVT]` lines:

   | Do this on the strip | Expected serial output |
   |---|---|
   | Short touch, lift | `DOWN` … `UP` |
   | Slide slowly end to end | `DOWN`, then 4× `TICK` with digit counting 0→4 (or 4→0) |
   | Two quick taps, same spot | `DOWN/UP`, `DOWN/UP`, then **`SELECT digit=N`** |
   | Press & hold ~2 s, no movement | **`DELETE`** after 2 s |

   ✅ **Stage 1 passes** when all four rows behave as described.

   If double-tap won't register or ticks flicker at zone boundaries, note it —
   the tuning constants are at the top of the `.ino`
   (`TAP_MAX_MS`, `DOUBLE_TAP_GAP_MS`, `HOLD_DELETE_MS`, `ZONE_HYSTERESIS`).

---

## Stage 2 — BLE check without the watch (optional, ~2 min)

Useful to isolate BLE from the watch app:

1. Install **nRF Connect** (free) on any phone.
2. Scan → connect to **"BandPin"**.
3. Enable notifications on characteristic `4A420002-…`.
4. Touch the strip → 6-byte hex payloads appear live (byte 0 = event type,
   byte 2 = digit).

⚠️ **Disconnect nRF Connect afterwards** — the ESP32 accepts one central at a
time; while the phone is connected, the watch cannot connect.

---

## Stage 3 — End-to-end with the Galaxy Watch 4

1. Open the `Frontend/` folder in Android Studio (let Gradle sync; it will
   download the SDK components it needs).
2. On the watch: **Settings → Developer options → Wireless debugging → Pair new
   device**, then pair via Android Studio (Device Manager → Pair using Wi-Fi)
   or `adb pair` / `adb connect`.
3. Run the app on the watch.
4. First launch: **grant the Bluetooth permission** — without it the app never
   scans and stays disconnected.
5. The small dot at the bottom of the watch screen turns **green** when the
   band is found (ESP32 serial simultaneously prints `[BLE] watch connected`).
6. Run a trial:
   - **Setup screen:** keep the range chip on **"digits 0–4"** (single strip!).
     Memorise the 4-digit code, tap **Start** — the code disappears.
   - **Entry screen:** only 4 dots are shown. On the band:
     slide → **tick vibration** per zone crossed ·
     double-tap → **stronger buzz**, a dot fills ·
     hold 2 s → **double pulse**, last dot empties.
   - 4th digit auto-confirms → success (rising triple pulse) or failure
     (long buzz) → result screen with entry time.
7. Pull the logged data to confirm logging works:

   ```
   adb pull /data/data/com.android.bandpinwatch/files/bandpin_study .
   ```

   You should get `events.csv` (every band event) and `trials.csv`
   (one row per trial).

   ✅ **Stage 3 passes** when a full trial runs eyes-free and both CSVs contain
   the trial you just did.

---

## What to pay attention to (pilot feedback we need)

1. **Tick latency** — does the vibration feel coupled to the finger crossing a
   zone, or noticeably delayed?
2. **Double-tap reliability** — misfires? Which way (not registering vs.
   registering single taps as selects)?
3. **Zone findability** — do 5 zones on the strip length feel locatable by
   counting ticks?

Write observations down — these map directly to the gesture parameters we have
to pilot-test before the study.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `waiting for strip A…` loops | Wiring (SDA 21 / SCL 22 / 3V3 / GND) or strip not seated in its connector |
| Watch dot stays red | Bluetooth permission denied → reinstall or grant in watch app settings; or nRF Connect still holds the connection; or ESP32 not powered |
| Watch connects, no haptics | Check ESP32 serial: if `[EVT]` lines appear but no `[BLE] notified`-side effects, re-run; if no `[EVT]` lines, it's a sensor issue, not BLE |
| Digits 5–9 in the target code | Range chip is on 0–9 — tap it to switch to 0–4 while only strip A is mounted |
| Gradle sync fails | Android Studio → SDK Manager → install Android 36 platform + build tools when prompted |

---

## Adding strip B

1. On the second Trill Flex board, bridge the address solder jumper so it
   responds at **0x49** (Flex address range 0x48–0x4F; default 0x48).
2. Wire it to the **same** I²C bus (SDA 21, SCL 22, 3V3, GND).
3. Reboot the ESP32 — serial should now show `strip B ready (digits 5-9)`.
4. On the watch setup screen, tap the range chip to **"digits 0–9"**.
No code changes needed.
