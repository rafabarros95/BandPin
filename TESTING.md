# BandPin — Test Guide

Step-by-step instructions to verify the end-to-end prototype on a fresh machine.
Test in this order — each stage isolates one layer, so if something fails you
know exactly where the problem is.

---

## What you need

**Hardware**
- ESP32 DevKitC + USB cable
- 1× Trill Flex as **strip A**: **SDA → GPIO 25, SCL → GPIO 26, VCC → 5V, GND → GND**
  (a second strip is optional; see [Strip B](#adding-strip-b) at the end)
- Galaxy Watch 4 (charged, Wi-Fi on the same network as your PC)

Both strips keep the factory address **0x48** — each sits on its own I²C bus, so
there is no address conflict and nothing has to be jumpered or split.

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
   [BandPin] booting...
   [BandPin] searching for strip B on GPIO 21/22...
   [BandPin] strip B absent; continuing with strip A only
   [BandPin] searching for strip A on GPIO 25/26...
   [BandPin] strip A ready: digits 0-4
   [BLE] advertising as BandPin
   [BandPin] touch either strip
   ```

   The strip-B line is normal with one strip mounted. Both strips are optional
   and are re-probed once per second, so you can plug one in while the firmware
   is running and it will announce `strip B detected; initializing...` followed
   by `strip B restored: digits 5-9`.

4. Test each gesture on the strip and watch the `[EVT]` lines:

   | Do this on the strip | Expected serial output |
   |---|---|
   | Short touch, lift | `DOWN` … `UP` |
   | Slide slowly end to end | `DOWN`, then 4× `TICK` with digit counting 0→4 (or 4→0) |
   | Two quick taps, same spot | `DOWN/UP`, `DOWN/UP`, then **`SELECT digit=N`** |
   | Press & hold ~2 s, no movement | **`DELETE`** after 2 s, repeating every 600 ms while held |

   ✅ **Stage 1 passes** when all four rows behave as described.

   Only the mounted part of the strip is live: the firmware ignores anything
   outside `ACTIVE_START` (0.08) to `ACTIVE_END` (0.39) of the raw sensor
   length. If a whole end of the strip appears dead, that is this window, not a
   broken sensor — retune those two constants after remounting.

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
5. Watch the ESP32 serial for `[BLE] watch connected` to confirm the link.
6. Run a trial:
   - **Menu screen:** set the participant with **−/+** and the condition with the
     **"Digits 0–4" / "Digits 0–9"** button (0–4 while only strip A is mounted).
     These become the `participantId` and `condition` columns of the log, so set
     them before the session. Tap **Enter PIN**.
   - **Entry screen:** only 4 dots are shown. On the band:
     slide → **tick vibration** per zone crossed ·
     double-tap → **stronger buzz**, a dot fills; a *different* pattern if the
     digit does not match the expected one ·
     hold 2 s → **double pulse**, last dot empties.
   - 4th digit auto-confirms → success (rising pulses) or failure (long buzz).
     The app then closes and reopens for the next trial, keeping the BLE link.
   - To set a new PIN, use **Set Pin** and enter it twice.
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
| `waiting for strip A…` loops | Wiring (SDA 25 / SCL 26 / 5V / GND) or strip not seated in its connector |
| Watch never connects | Bluetooth permission denied → reinstall or grant in watch app settings; or nRF Connect still holds the connection; or ESP32 not powered |
| Watch connects, no haptics | Check ESP32 serial: if `[EVT]` lines appear, it is an app problem; if not, it is a sensor problem, not BLE |
| `condition` column says `SingleStrip` but both strips were used | The condition button on the menu was left on **Digits 0–4** — it is set by the experimenter, not detected |
| Gradle sync fails | Android Studio → SDK Manager → install Android 36 platform + build tools when prompted |

---

## Adding strip B

1. Wire the second Trill Flex to the **second** I²C bus:
   **SDA → GPIO 21, SCL → GPIO 22, VCC → 3V3, GND → GND**.
2. Leave its address at the factory default **0x48** — the two strips are on
   separate buses, so they cannot collide.
3. No reboot and no code change needed. The firmware probes both buses once per
   second and will print `strip B detected; initializing...` followed by
   `strip B ready: digits 5-9` within about a second of plugging it in.

The two buses are fixed in the firmware (`I2C_A = TwoWire(1)` on GPIO 25/26,
`I2C_B = Wire` on GPIO 21/22). There is no single-bus build any more, so both
boards stay at 0x48 and nothing has to be jumpered.
