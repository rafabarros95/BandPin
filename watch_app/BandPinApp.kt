/*
 * BandPin — Galaxy Watch 4 / Wear OS app
 * ----------------------------------------
 * Kotlin + Jetpack Compose
 *
 * Responsibilities:
 *   1. BLE GATT client  — connect to ESP32 "BandPin" peripheral
 *   2. PIN engine        — randomised digit→zone mapping per digit entry
 *   3. PIN UI            — Jetpack Compose watch face rendering
 *   4. Validation        — compare entered PIN to stored secret locally
 *   5. Auth token        — emit signed token on successful auth
 *
 * File layout (single file for prototype clarity):
 *   - BandPinConstants      UUIDs, zone names
 *   - PinEngine             randomised mapping + validation logic
 *   - BleManager            GATT client lifecycle
 *   - BandPinViewModel      state holder (Compose-friendly)
 *   - BandPinApp            root Composable
 *   - MainActivity          entry point
 */

package de.thkoeln.moxd.bandpin

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID


// ── Constants ──────────────────────────────────────────────────────────────

object BandPinConstants {
    val SERVICE_UUID         = UUID.fromString("4A420001-1000-8000-0080-00805F9B34FB")
    val TAP_CHAR_UUID        = UUID.fromString("4A420002-1000-8000-0080-00805F9B34FB")
    val CCCD_UUID            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_NAME    = "BandPin"
    const val PIN_LENGTH     = 4
    const val NUM_DIGITS     = 10

    val ZONE_NAMES = listOf("LEFT", "MID", "RIGHT")
}


// ── PIN engine ─────────────────────────────────────────────────────────────

/**
 * Manages the randomised digit→zone mapping and PIN validation.
 *
 * Security property:
 *   For each of the 4 digits, the 10 digits (0–9) are shuffled and
 *   re-assigned across the 3 zones. An observer who sees zone=MID on
 *   digit 2 cannot know which digit was entered without the mapping,
 *   because the mapping changes every entry attempt.
 */
class PinEngine {

    // The secret PIN — in production this would be stored in Keystore
    private val secretPin = listOf(1, 5, 3, 7)  // placeholder; set via enrollment

    // Current mapping: digit → zone index. Reshuffled per digit.
    private var currentMapping: Map<Int, Int> = emptyMap()

    // Reverse mapping: zone → list of digits assigned to it
    var currentZoneToDigits: Map<Int, List<Int>> = emptyMap()
        private set

    // Entered digits so far
    private val enteredDigits = mutableListOf<Int>()

    /**
     * Shuffle digit→zone assignment for the next digit entry.
     * Call this before showing the UI for each digit position.
     *
     * Distribution: 4 digits to zone 0, 3 to zone 1, 3 to zone 2
     * (or any balanced partition — adjust as needed).
     */
    fun shuffleMapping() {
        val digits = (0..9).shuffled()
        val mapping = mutableMapOf<Int, Int>()
        val zoneToDigits = mutableMapOf<Int, MutableList<Int>>()

        // Distribute: first 4 → zone 0, next 3 → zone 1, last 3 → zone 2
        val distribution = listOf(4, 3, 3)
        var idx = 0
        for (zone in 0..2) {
            zoneToDigits[zone] = mutableListOf()
            repeat(distribution[zone]) {
                val digit = digits[idx++]
                mapping[digit] = zone
                zoneToDigits[zone]!!.add(digit)
            }
        }

        currentMapping = mapping
        currentZoneToDigits = zoneToDigits.mapValues { it.value.sorted() }
    }

    /**
     * Register a zone tap. When a zone contains only one digit, that digit
     * is immediately confirmed. When multiple digits share the zone, a
     * second micro-gesture (position within zone) disambiguates.
     *
     * Returns the confirmed digit, or null if disambiguation still needed.
     */
    fun registerZoneTap(zone: Int, intraZonePosition: Float): Int? {
        val candidates = currentZoneToDigits[zone] ?: return null

        val confirmedDigit = when {
            candidates.size == 1 -> candidates.first()
            else -> disambiguate(candidates, intraZonePosition)
        }

        enteredDigits.add(confirmedDigit)
        return confirmedDigit
    }

    /**
     * Disambiguate between multiple candidates in the same zone using
     * the intra-zone finger position (0.0 = zone start, 1.0 = zone end).
     */
    private fun disambiguate(candidates: List<Int>, position: Float): Int {
        // Divide zone evenly among candidates by position
        val idx = (position * candidates.size).toInt().coerceIn(0, candidates.size - 1)
        return candidates[idx]
    }

    /** True when all PIN_LENGTH digits have been entered. */
    val isComplete: Boolean get() = enteredDigits.size >= BandPinConstants.PIN_LENGTH

    /** Validate the entered PIN against the stored secret. */
    fun validate(): Boolean = enteredDigits == secretPin

    /** Reset for a new entry attempt. */
    fun reset() {
        enteredDigits.clear()
        shuffleMapping()
    }

    val enteredCount: Int get() = enteredDigits.size
}


// ── BLE manager ────────────────────────────────────────────────────────────

/**
 * GATT client that connects to the ESP32 BandPin peripheral and
 * subscribes to tap notifications.
 */
class BleManager(
    private val context: Context,
    private val onTap: (zone: Int, position: Float, durationMs: Int) -> Unit,
    private val onConnectionChange: (connected: Boolean) -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager).adapter

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == BandPinConstants.DEVICE_NAME) {
                stopScan()
                connect(device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onConnectionChange(true)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onConnectionChange(false)
                    gatt = null
                    startScan()   // auto-reconnect
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BandPinConstants.SERVICE_UUID) ?: return
            val char = service.getCharacteristic(BandPinConstants.TAP_CHAR_UUID) ?: return

            // Enable notifications
            g.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(BandPinConstants.CCCD_UUID)
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BandPinConstants.TAP_CHAR_UUID) return
            val bytes = characteristic.value
            if (bytes.size < 4) return

            val zone        = bytes[0].toInt() and 0xFF
            val position    = (bytes[1].toInt() and 0xFF) / 100f
            val durationMs  = ((bytes[2].toInt() and 0xFF) shl 8) or
                              (bytes[3].toInt() and 0xFF)

            onTap(zone, position, durationMs)
        }
    }

    fun startScan() {
        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setDeviceName(BandPinConstants.DEVICE_NAME)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}


// ── ViewModel ──────────────────────────────────────────────────────────────

sealed class PinUiState {
    object Idle              : PinUiState()
    object Scanning          : PinUiState()
    object Connected         : PinUiState()
    data class Entering(
        val digitIndex: Int,
        val zoneToDigits: Map<Int, List<Int>>,
    ) : PinUiState()
    object Success           : PinUiState()
    object Failure           : PinUiState()
}

class BandPinViewModel : ViewModel() {

    private val engine = PinEngine()

    private val _uiState = MutableStateFlow<PinUiState>(PinUiState.Idle)
    val uiState: StateFlow<PinUiState> = _uiState

    // Called by BleManager on connection change
    fun onConnectionChange(connected: Boolean) {
        if (connected) {
            engine.reset()
            _uiState.value = PinUiState.Entering(
                digitIndex   = 0,
                zoneToDigits = engine.currentZoneToDigits,
            )
        } else {
            _uiState.value = PinUiState.Scanning
        }
    }

    // Called by BleManager on each tap notification
    fun onTap(zone: Int, position: Float, durationMs: Int) {
        val state = _uiState.value
        if (state !is PinUiState.Entering) return

        val digit = engine.registerZoneTap(zone, position) ?: return

        if (engine.isComplete) {
            _uiState.value = if (engine.validate()) PinUiState.Success
                             else                   PinUiState.Failure
        } else {
            // Next digit — reshuffle mapping
            engine.shuffleMapping()
            _uiState.value = PinUiState.Entering(
                digitIndex   = engine.enteredCount,
                zoneToDigits = engine.currentZoneToDigits,
            )
        }
    }

    fun retry() {
        engine.reset()
        _uiState.value = PinUiState.Entering(
            digitIndex   = 0,
            zoneToDigits = engine.currentZoneToDigits,
        )
    }
}


// ── Composable UI ──────────────────────────────────────────────────────────

@Composable
fun BandPinApp(viewModel: BandPinViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A)),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is PinUiState.Scanning  -> ScanningScreen()
            is PinUiState.Entering  -> EnteringScreen(s)
            is PinUiState.Success   -> ResultScreen(success = true)
            is PinUiState.Failure   -> ResultScreen(success = false) {
                viewModel.retry()
            }
            else -> {}
        }
    }
}

@Composable
fun ScanningScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Connecting…", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        CircularProgressIndicator(indicatorColor = Color(0xFF7F77DD))
    }
}

@Composable
fun EnteringScreen(state: PinUiState.Entering) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Enter PIN", color = Color(0xFF888888), fontSize = 11.sp)

        // PIN progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(BandPinConstants.PIN_LENGTH) { i ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (i < state.digitIndex) Color(0xFF7F77DD)
                                    else Color(0xFF333333),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Zone labels — show which digits are in each zone
        Text("Tap the band zone:", color = Color(0xFF666666), fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.zoneToDigits.forEach { (zone, digits) ->
                ZoneChip(
                    label     = BandPinConstants.ZONE_NAMES[zone],
                    digits    = digits,
                )
            }
        }
    }
}

@Composable
fun ZoneChip(label: String, digits: List<Int>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color(0xFF1A1A2E), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color(0xFF7F77DD), fontSize = 10.sp)
        Text(digits.joinToString(" "), color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun ResultScreen(success: Boolean, onRetry: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (success) "✓ Unlocked" else "✗ Wrong PIN",
            color = if (success) Color(0xFF1D9E75) else Color(0xFFD85A30),
            fontSize = 16.sp,
        )
        if (!success && onRetry != null) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}


// ── Activity ───────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var viewModel: BandPinViewModel
    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        setContent {
            viewModel = viewModel()
            BandPinApp(viewModel)
        }

        bleManager = BleManager(
            context = this,
            onTap = { zone, position, durationMs ->
                runOnUiThread {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                    viewModel.onTap(zone, position, durationMs)
                }
            },
            onConnectionChange = { connected ->
                runOnUiThread { viewModel.onConnectionChange(connected) }
            },
        )

        bleManager.startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}
