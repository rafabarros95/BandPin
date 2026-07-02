package com.android.bandpinwatch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Event types sent by the ESP32 gesture engine.
 * Must stay in sync with BandPinFirmware.ino.
 */
enum class BandEventType(val code: Int) {
    DOWN(0), TICK(1), UP(2), SELECT(3), DELETE(4);

    companion object {
        fun fromCode(code: Int): BandEventType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One decoded 6-byte BLE notification from the band.
 *
 * @param strip     0 = strip A (digits 0-4), 1 = strip B (digits 5-9)
 * @param digit     0-9, already offset by the firmware
 * @param position  0.0-1.0 along the strip
 * @param boardTimeMs  low 16 bits of the ESP32 millis() clock
 */
data class BandEvent(
    val type: BandEventType,
    val strip: Int,
    val digit: Int,
    val position: Float,
    val boardTimeMs: Int,
    val receivedAtMs: Long = System.currentTimeMillis(),
)

/**
 * BLE GATT client for the ESP32 "BandPin" peripheral.
 * Scans by service UUID, connects, subscribes to the event characteristic
 * and rescans automatically when the connection drops.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before start()
class BandBleClient(
    private val context: Context,
    private val onEvent: (BandEvent) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "BandBleClient"
        val SERVICE_UUID: UUID = UUID.fromString("4A420001-1000-8000-0080-00805F9B34FB")
        val EVENT_CHAR_UUID: UUID = UUID.fromString("4A420002-1000-8000-0080-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "BandPin"
    }

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val matchesService =
                result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
            val matchesName = result.device.name == DEVICE_NAME
            if (matchesService || matchesName) {
                Log.i(TAG, "found ${result.device.address} — connecting")
                stopScan()
                connect(result.device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "connected — discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "disconnected — rescanning")
                    onConnectionChange(false)
                    g.close()
                    gatt = null
                    start() // auto-reconnect
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(SERVICE_UUID)
                ?.getCharacteristic(EVENT_CHAR_UUID)
            if (characteristic == null) {
                Log.w(TAG, "BandPin service/characteristic missing — disconnecting")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            onConnectionChange(true)
        }

        @Deprecated("Deprecated in API 33, still delivered on Wear OS 3 (API 30)")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handlePayload(characteristic.uuid, characteristic.value ?: return)
        }

        // API 33+ variant
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handlePayload(characteristic.uuid, value)
        }
    }

    private fun handlePayload(uuid: UUID, bytes: ByteArray) {
        if (uuid != EVENT_CHAR_UUID || bytes.size < 6) return
        val type = BandEventType.fromCode(bytes[0].toInt() and 0xFF) ?: return
        val event = BandEvent(
            type = type,
            strip = bytes[1].toInt() and 0xFF,
            digit = bytes[2].toInt() and 0xFF,
            position = (bytes[3].toInt() and 0xFF) / 100f,
            boardTimeMs = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF),
        )
        onEvent(event)
    }

    /** Begin scanning for the band. Call only after BLE permissions are granted. */
    fun start() {
        if (scanning || gatt != null) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BLE scanner (adapter off?)")
            return
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        scanning = true
        Log.i(TAG, "scanning for BandPin…")
    }

    private fun stopScan() {
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun stop() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
