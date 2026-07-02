package com.android.bandpinwatch.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import com.android.bandpinwatch.ble.BandBleClient
import com.android.bandpinwatch.presentation.theme.BandPinWatchTheme
import com.android.bandpinwatch.study.TrialLogger

private val Turquoise = Color(64, 224, 208)
private val DimGray = Color(0xFF666666)
private val SuccessGreen = Color(0xFF1D9E75)
private val FailureRed = Color(0xFFD85A30)

class MainActivity : ComponentActivity() {

    private lateinit var controller: PinInputController
    private lateinit var vibrator: Vibrator
    private var bleClient: BandBleClient? = null
    private val bandConnected = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startBle()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        controller = PinInputController(
            logger = TrialLogger(this),
            playHaptic = ::playHaptic,
        )

        setContent {
            WearApp(controller, bandConnected.value)
        }

        ensureBlePermissions()
    }

    // ── BLE ────────────────────────────────────────────────────────────────

    private fun ensureBlePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Wear OS 3 (API 30): BLE scanning still requires location
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBle() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startBle() {
        if (bleClient != null) return
        bleClient = BandBleClient(
            context = this,
            onEvent = { event -> runOnUiThread { controller.onBandEvent(event) } },
            onConnectionChange = { connected -> runOnUiThread { bandConnected.value = connected } },
        ).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient?.stop()
    }

    // ── Haptics — each cue must feel distinct (eyes-free!) ─────────────────

    private fun playHaptic(cue: HapticCue) {
        val effect = when (cue) {
            // one crisp tick per zone boundary while sliding
            HapticCue.TICK -> VibrationEffect.createOneShot(25, 180)
            // digit accepted
            HapticCue.SELECT -> VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
            // last digit deleted: two short pulses
            HapticCue.DELETE -> VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1)
            // PIN correct: rising triple pulse
            HapticCue.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 50, 70, 50, 70, 120), -1)
            // PIN wrong: single long buzz
            HapticCue.FAILURE -> VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }
}

// ── UI ─────────────────────────────────────────────────────────────────────

@Composable
fun WearApp(controller: PinInputController, bandConnected: Boolean) {
    BandPinWatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            TimeText(timeTextStyle = TimeTextDefaults.timeTextStyle(color = Turquoise))

            ConnectionDot(bandConnected)

            when (controller.phase) {
                StudyPhase.SETUP -> SetupScreen(controller)
                StudyPhase.ENTERING -> EntryScreen(controller.enteredCount)
                StudyPhase.RESULT -> ResultScreen(controller)
            }
        }
    }
}

/** Small status dot at the bottom: green = band connected, red = not. */
@Composable
fun ConnectionDot(connected: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(8.dp)
                .background(if (connected) SuccessGreen else FailureRed, CircleShape)
        )
    }
}

/**
 * SETUP: the only moment the code is visible. The participant memorises it,
 * taps Start — from then on the display shows dots only (eyes-free).
 */
@Composable
fun SetupScreen(controller: PinInputController) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallChip("−") { controller.changeParticipant(-1) }
            Text(
                "P%02d · T%d".format(controller.participantNumber, controller.trialNumber),
                color = DimGray, fontSize = 12.sp,
            )
            SmallChip("+") { controller.changeParticipant(+1) }
        }

        Text("Memorise the code", color = DimGray, fontSize = 11.sp)

        Text(
            controller.targetPin.joinToString("  "),
            color = Turquoise,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip(if (controller.maxDigit == 4) "digits 0–4" else "digits 0–9") {
                controller.toggleDigitRange()
            }
            SmallChip("Start", background = Turquoise, textColor = Color.Black) {
                controller.startTrial()
            }
        }
    }
}

/** ENTERING: dots only — never the digits. Feedback comes through the wrist. */
@Composable
fun EntryScreen(enteredCount: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PinIndicator(enteredDigits = enteredCount)
        Text("enter on the band", color = DimGray, fontSize = 10.sp)
    }
}

@Composable
fun ResultScreen(controller: PinInputController) {
    val correct = controller.lastTrialCorrect
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (correct) "✓ Correct" else "✗ Wrong",
            color = if (correct) SuccessGreen else FailureRed,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "%.1f s".format(controller.lastEntryTimeMs / 1000f),
            color = DimGray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        SmallChip("Next trial", background = Turquoise, textColor = Color.Black) {
            controller.nextTrial()
        }
    }
}

// ── Shared pieces ──────────────────────────────────────────────────────────

@Composable
fun SmallChip(
    label: String,
    background: Color = Color(0xFF1A1A2E),
    textColor: Color = Turquoise,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = textColor, fontSize = 12.sp)
    }
}

// PIN progress circles (kept from the original prototype UI)
@Composable
fun PinIndicator(
    enteredDigits: Int,
    pinLength: Int = PinInputController.PIN_LENGTH,
    colorCircle: Color = Turquoise,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(pinLength) { index ->
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .then(
                        if (index < enteredDigits) {
                            Modifier.background(colorCircle, CircleShape)
                        } else {
                            Modifier.border(2.dp, colorCircle, CircleShape)
                        }
                    )
            )
        }
    }
}
