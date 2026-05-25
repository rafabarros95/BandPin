"""
BandPin — ESP32 main entry point (MicroPython)
----------------------------------------------
Wires the Trill Flex sensor loop to the BLE GATT peripheral.
Flash this as main.py on the ESP32.

Boot sequence:
  1. Initialise BLE peripheral → start advertising
  2. Initialise Trill Flex sensor
  3. Run 100 Hz sensor loop → forward tap events over BLE GATT NOTIFY
"""

from trill_reader import BandPinSensor
from ble_peripheral import BandPinBLE
import time


def main():
    print("[BandPin] starting up…")

    # Initialise BLE — starts advertising immediately
    ble = BandPinBLE(device_name="BandPin")

    # Tap callback: forward every confirmed tap straight to BLE
    def on_tap(event: dict):
        print(
            f"[BandPin] tap  zone={event['zone_name']}  "
            f"pos={event['position']:.2f}  dur={event['duration_ms']}ms"
        )
        ble.notify_tap(event)

    # Initialise sensor with callback
    sensor = BandPinSensor(sda_pin=21, scl_pin=22, on_tap=on_tap)
    print("[BandPin] sensor ready — entering 100 Hz loop")

    # Main loop — tick() is internally rate-limited to 100 Hz
    while True:
        sensor.tick()
        # Yield to MicroPython scheduler every cycle so BLE IRQs fire
        time.sleep_ms(1)


if __name__ == "__main__":
    main()
