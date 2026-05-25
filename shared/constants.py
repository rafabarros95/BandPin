"""
BandPin — shared constants
--------------------------
Single source of truth for UUIDs, zone definitions, and PIN protocol.
Import from both the ESP32 firmware and the Watch app test harness.
"""

# ── BLE GATT UUIDs (must match Kotlin constants in WatchApp) ────────────────
BANDPIN_SERVICE_UUID  = "4A420001-1000-8000-0080-00805F9B34FB"
TAP_CHARACTERISTIC_UUID = "4A420002-1000-8000-0080-00805F9B34FB"

# ── Zone definitions ─────────────────────────────────────────────────────────
NUM_ZONES   = 3
ZONE_NAMES  = ["LEFT", "MID", "RIGHT"]

# ── PIN protocol ─────────────────────────────────────────────────────────────
PIN_LENGTH  = 4          # 4-digit PIN
NUM_DIGITS  = 10         # digits 0–9

# Digit-to-zone assignment for the DEFAULT (non-shuffled) layout.
# In the actual prototype this mapping is randomised per digit entry.
# Layout: 4 digits per zone (10 digits across 3 zones, zone MID gets 4)
#   LEFT:  0, 1, 2, 3
#   MID:   4, 5, 6
#   RIGHT: 7, 8, 9
DEFAULT_ZONE_MAP: dict[int, int] = {
    0: 0, 1: 0, 2: 0, 3: 0,
    4: 1, 5: 1, 6: 1,
    7: 2, 8: 2, 9: 2,
}

# ── BLE payload format ────────────────────────────────────────────────────────
# 4 bytes big-endian:
#   [0] zone index (0–2)
#   [1] intra-zone position * 100 (0–100)
#   [2] duration_ms high byte
#   [3] duration_ms low byte
BLE_PAYLOAD_SIZE = 4
