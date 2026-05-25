"""
BandPin — ESP32 BLE GATT peripheral (MicroPython)
--------------------------------------------------
Advertises a custom BandPin GATT service and notifies the
Galaxy Watch 4 on every confirmed tap event from the band.

GATT layout:
  Service UUID:       4A42-0001-...  (BandPin service)
  Characteristic:     4A42-0002-...  (tap event, NOTIFY)
    Payload (4 bytes):
      byte 0: zone index (0=LEFT, 1=MID, 2=RIGHT)
      byte 1: zone position * 100 (0–100, integer)
      byte 2: duration_ms >> 8 (high byte)
      byte 3: duration_ms & 0xFF (low byte)

Usage:
  from ble_peripheral import BandPinBLE
  ble = BandPinBLE()
  ble.on_tap({"zone": 1, "position": 0.5, "duration_ms": 120})
"""

import bluetooth
import struct
import time
from micropython import const

# ── UUIDs ───────────────────────────────────────────────────────────────────
_BANDPIN_SERVICE_UUID = bluetooth.UUID("4A420001-1000-8000-0080-00805F9B34FB")
_TAP_CHAR_UUID        = bluetooth.UUID("4A420002-1000-8000-0080-00805F9B34FB")

_FLAG_NOTIFY = const(0x0010)
_FLAG_READ   = const(0x0002)

# ── Advertising payload helpers ──────────────────────────────────────────────
_ADV_TYPE_FLAGS              = const(0x01)
_ADV_TYPE_COMPLETE_LOCAL_NAME = const(0x09)
_ADV_TYPE_UUID16_COMPLETE     = const(0x03)

def _adv_payload(name: str) -> bytes:
    """Build a minimal BLE advertising payload."""
    payload = bytearray()
    # Flags: LE General Discoverable, BR/EDR not supported
    payload += bytes([2, _ADV_TYPE_FLAGS, 0x06])
    # Complete local name
    name_b = name.encode()
    payload += bytes([len(name_b) + 1, _ADV_TYPE_COMPLETE_LOCAL_NAME]) + name_b
    return bytes(payload)


class BandPinBLE:
    """
    BLE GATT peripheral for BandPin.
    Call notify_tap(event) to push a tap event to a connected central (Watch 4).
    """

    def __init__(self, device_name: str = "BandPin"):
        self._ble = bluetooth.BLE()
        self._ble.active(True)
        self._ble.irq(self._irq)

        # Register GATT service
        tap_char = (
            _TAP_CHAR_UUID,
            _FLAG_NOTIFY | _FLAG_READ,
        )
        bandpin_service = (
            _BANDPIN_SERVICE_UUID,
            (tap_char,),
        )
        ((self._tap_handle,),) = self._ble.gatts_register_services(
            (bandpin_service,)
        )

        self._connected = False
        self._conn_handle = None
        self._adv_payload = _adv_payload(device_name)
        self._start_advertising()
        print(f"[BandPin BLE] advertising as '{device_name}'")

    def _irq(self, event, data):
        """BLE IRQ handler."""
        _IRQ_CENTRAL_CONNECT    = const(1)
        _IRQ_CENTRAL_DISCONNECT = const(2)

        if event == _IRQ_CENTRAL_CONNECT:
            conn_handle, _, _ = data
            self._connected = True
            self._conn_handle = conn_handle
            self._ble.gap_advertise(None)  # stop advertising
            print(f"[BandPin BLE] connected (handle={conn_handle})")

        elif event == _IRQ_CENTRAL_DISCONNECT:
            self._connected = False
            self._conn_handle = None
            print("[BandPin BLE] disconnected — restarting advertising")
            self._start_advertising()

    def _start_advertising(self):
        self._ble.gap_advertise(
            interval_us=100_000,      # 100 ms advertising interval
            adv_data=self._adv_payload,
        )

    def notify_tap(self, event: dict):
        """
        Send a tap event to the connected Watch 4.

        Args:
            event: dict with keys zone (int), position (float), duration_ms (int)
        """
        if not self._connected or self._conn_handle is None:
            return  # no central connected

        zone        = event["zone"]          & 0xFF
        position    = int(event["position"] * 100) & 0xFF
        duration_ms = event.get("duration_ms", 0) & 0xFFFF

        payload = struct.pack(
            ">BBBB",
            zone,
            position,
            (duration_ms >> 8) & 0xFF,
            duration_ms & 0xFF,
        )

        try:
            self._ble.gatts_notify(self._conn_handle, self._tap_handle, payload)
            print(
                f"[BandPin BLE] notified → zone={zone} "
                f"pos={position/100:.2f} dur={duration_ms}ms"
            )
        except OSError as e:
            print(f"[BandPin BLE] notify failed: {e}")

    @property
    def is_connected(self) -> bool:
        return self._connected
