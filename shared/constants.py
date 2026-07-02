"""
BandPin — shared constants
--------------------------
Single source of truth for UUIDs, the digit layout, and the BLE event
protocol. Must stay in sync with:
  Backend/BandPinFirmware/BandPinFirmware.ino   (ESP32, Arduino C++)
  Frontend/.../ble/BandBleClient.kt             (Wear OS app, Kotlin)
"""

# ── BLE GATT UUIDs ───────────────────────────────────────────────────────────
BANDPIN_SERVICE_UUID = "4A420001-1000-8000-0080-00805F9B34FB"
EVENT_CHARACTERISTIC_UUID = "4A420002-1000-8000-0080-00805F9B34FB"
DEVICE_NAME = "BandPin"

# ── Digit layout (fixed, per the BandPin proposal) ───────────────────────────
# Strip A (top of wrist,  I²C 0x48): digits 0–4, zone 0 at the strip start
# Strip B (palm side,     I²C 0x49): digits 5–9, zone 0 at the strip start
NUM_STRIPS = 2
ZONES_PER_STRIP = 5
PIN_LENGTH = 4

TRILL_ADDR_STRIP_A = 0x48   # factory default
TRILL_ADDR_STRIP_B = 0x49   # set via solder jumper on the Trill board

# ── Gesture parameters (mirror of the firmware; pilot-test these) ────────────
TAP_MAX_MS = 250            # max contact time to count as a tap
DOUBLE_TAP_GAP_MS = 400     # max pause between the two taps of a double-tap
HOLD_DELETE_MS = 2000       # hold this long without sliding = delete
ZONE_HYSTERESIS = 0.04      # fraction of strip length, anti-flicker margin

# ── BLE event payload (6 bytes) ──────────────────────────────────────────────
#   [0] event type (see EVENT_* below)
#   [1] strip index      (0 = A, 1 = B)
#   [2] digit            (0–9, already offset by the firmware)
#   [3] position * 100   (0–100, along the whole strip)
#   [4] board millis() >> 8   (low 16 bits of ESP32 clock, for latency analysis)
#   [5] board millis() & 0xFF
BLE_PAYLOAD_SIZE = 6

EVENT_DOWN = 0     # finger lands on a strip
EVENT_TICK = 1     # zone boundary crossed while sliding → haptic tick
EVENT_UP = 2       # finger lifted
EVENT_SELECT = 3   # double-tap → digit chosen
EVENT_DELETE = 4   # hold ≥ HOLD_DELETE_MS → remove last digit

EVENT_NAMES = {
    EVENT_DOWN: "DOWN",
    EVENT_TICK: "TICK",
    EVENT_UP: "UP",
    EVENT_SELECT: "SELECT",
    EVENT_DELETE: "DELETE",
}
