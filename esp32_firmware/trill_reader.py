"""
BandPin — Trill Flex sensor reader (MicroPython / ESP32)
---------------------------------------------------------
Reads the Trill Flex capacitive strip over I²C at 100 Hz.
The full 30-segment strip is used uncut, mounted on the wristband
below the watch face. Zone 0 = wrist-side, Zone 2 = fingertip-side.

Hardware connections (ESP32 DevKitC):
  Trill Flex SDA  → GPIO 21
  Trill Flex SCL  → GPIO 22
  Trill Flex VCC  → 3.3V
  Trill Flex GND  → GND

Dependencies (MicroPython):
  machine.I2C, machine.Pin (built-in)
"""

from machine import I2C, Pin
import time
import struct

# ── Trill Flex I²C constants ────────────────────────────────────────────────
TRILL_ADDR        = 0x18          # default Trill Flex I²C address
TRILL_CMD_RESET   = 0xFF
TRILL_CMD_SCAN    = 0x02
TRILL_REG_DATA    = 0x00
TRILL_NUM_SEGS    = 30            # full strip, no cutting needed
SAMPLE_RATE_HZ    = 100           # 100 Hz as per CaseTouch architecture
SAMPLE_INTERVAL_MS = 1000 // SAMPLE_RATE_HZ   # 10 ms

# ── Zone boundaries (normalised 0.0–1.0 along the band) ────────────────────
# Band is horizontal; zone 0 = wrist side, zone 2 = fingertip side.
ZONE_BOUNDARIES = [
    (0.0,   0.333),   # ZONE 0: LEFT  (wrist-proximal third)
    (0.333, 0.667),   # ZONE 1: MID   (centre of band)
    (0.667, 1.0),     # ZONE 2: RIGHT (fingertip-distal third)
]
ZONE_NAMES = ["LEFT", "MID", "RIGHT"]

# Hysteresis margin to prevent zone flickering at boundaries (normalised units)
HYSTERESIS = 0.04

# Touch detection threshold (raw sensor value, 0–32767 for 15-bit)
TOUCH_THRESHOLD = 500
# Minimum touch duration before a tap is registered (ms)
DEBOUNCE_MS = 50


class TrillFlex:
    """
    Driver for the Trill Flex capacitive strip over I²C.
    Returns normalised centroid position and touch state.
    """

    def __init__(self, i2c: I2C, address: int = TRILL_ADDR):
        self.i2c = i2c
        self.address = address
        self._reset()

    def _reset(self):
        """Send hardware reset command to Trill Flex."""
        try:
            self.i2c.writeto(self.address, bytes([TRILL_CMD_RESET]))
            time.sleep_ms(50)
        except OSError as e:
            raise RuntimeError(f"Trill Flex not found at 0x{self.address:02X}: {e}")

    def read_raw(self) -> list[int]:
        """
        Request a scan and read all 30 segment values.
        Returns list of 30 integers (0–32767, 15-bit resolution).
        """
        self.i2c.writeto(self.address, bytes([TRILL_CMD_SCAN]))
        time.sleep_ms(2)  # allow scan to complete

        # Each segment value is 2 bytes (big-endian), 30 segments = 60 bytes
        data = self.i2c.readfrom_mem(self.address, TRILL_REG_DATA, TRILL_NUM_SEGS * 2)
        values = []
        for i in range(TRILL_NUM_SEGS):
            val = struct.unpack_from(">H", data, i * 2)[0]
            values.append(val)
        return values

    def read_centroid(self) -> tuple[float | None, bool]:
        """
        Compute the weighted centroid position across all segments.

        Returns:
            (position, touched)
            position: float in [0.0, 1.0], None if no touch
            touched:  True if touch exceeds threshold
        """
        raw = self.read_raw()
        total_weight = sum(raw)
        active = [v for v in raw if v > TOUCH_THRESHOLD]

        if not active or total_weight < TOUCH_THRESHOLD * 2:
            return None, False

        # Weighted centroid over segment indices
        weighted_sum = sum(i * v for i, v in enumerate(raw))
        centroid_idx = weighted_sum / total_weight

        # Normalise to [0.0, 1.0]
        normalised = centroid_idx / (TRILL_NUM_SEGS - 1)
        return normalised, True


class ZoneClassifier:
    """
    Converts a normalised Trill position to a discrete zone with hysteresis.
    Emits tap events only after DEBOUNCE_MS of stable contact.
    """

    def __init__(self):
        self._current_zone: int | None = None
        self._touch_start_ms: int | None = None
        self._in_touch: bool = False
        self._last_zone_raw: int | None = None

    def classify_raw(self, position: float) -> int:
        """Map normalised position to zone index 0/1/2."""
        for zone_idx, (lo, hi) in enumerate(ZONE_BOUNDARIES):
            if lo <= position < hi:
                return zone_idx
        return 2  # clamp to rightmost

    def classify_with_hysteresis(self, position: float) -> int:
        """Zone classification with hysteresis to suppress boundary flicker."""
        raw_zone = self.classify_raw(position)

        if self._current_zone is None:
            self._current_zone = raw_zone
            return raw_zone

        lo, hi = ZONE_BOUNDARIES[self._current_zone]
        # Stay in current zone unless clearly outside with hysteresis margin
        if (lo - HYSTERESIS) <= position < (hi + HYSTERESIS):
            return self._current_zone

        self._current_zone = raw_zone
        return raw_zone

    def update(self, position: float | None, touched: bool) -> dict | None:
        """
        Feed a new sensor reading. Returns a tap event dict when a valid
        tap is confirmed, otherwise None.

        Tap event format:
            {
                "type":     "tap",
                "zone":     int,        # 0=LEFT, 1=MID, 2=RIGHT
                "zone_name": str,
                "position": float,      # centroid within zone [0.0–1.0]
                "duration_ms": int,
            }
        """
        now = time.ticks_ms()

        if touched and position is not None:
            zone = self.classify_with_hysteresis(position)

            if not self._in_touch:
                # Touch began
                self._in_touch = True
                self._touch_start_ms = now
                self._last_zone_raw = zone

            # Accumulate — no event yet

        else:
            if self._in_touch:
                # Touch ended — check debounce
                duration = time.ticks_diff(now, self._touch_start_ms)
                zone = self._last_zone_raw

                self._in_touch = False
                self._touch_start_ms = None

                if duration >= DEBOUNCE_MS and zone is not None:
                    lo, hi = ZONE_BOUNDARIES[zone]
                    # Position within zone (0.0 = zone start, 1.0 = zone end)
                    zone_position = (position - lo) / (hi - lo) if position else 0.5

                    return {
                        "type": "tap",
                        "zone": zone,
                        "zone_name": ZONE_NAMES[zone],
                        "position": round(zone_position, 3),
                        "duration_ms": duration,
                    }

        return None


class BandPinSensor:
    """
    Top-level sensor loop. Call .tick() at SAMPLE_RATE_HZ.
    Yields tap events to a registered callback.
    """

    def __init__(self, sda_pin: int = 21, scl_pin: int = 22,
                 on_tap=None):
        self.i2c = I2C(0, sda=Pin(sda_pin), scl=Pin(scl_pin), freq=400_000)
        self.trill = TrillFlex(self.i2c)
        self.classifier = ZoneClassifier()
        self._on_tap = on_tap
        self._last_tick_ms = time.ticks_ms()

    def tick(self):
        """
        Call this in your main loop. Internally rate-limited to 100 Hz.
        Invokes on_tap(event) callback when a confirmed tap occurs.
        """
        now = time.ticks_ms()
        if time.ticks_diff(now, self._last_tick_ms) < SAMPLE_INTERVAL_MS:
            return
        self._last_tick_ms = now

        position, touched = self.trill.read_centroid()
        event = self.classifier.update(position, touched)

        if event and self._on_tap:
            self._on_tap(event)

    def run_forever(self):
        """Blocking loop. Use tick() instead for integration with BLE."""
        while True:
            self.tick()
