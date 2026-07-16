package com.android.bandpinwatch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.android.bandpinwatch.R
import com.android.bandpinwatch.presentation.theme.BandPinWatchTheme
<<<<<<< Updated upstream
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.activity.compose.LocalActivity
import android.content.Intent
import android.os.Handler
import android.os.Looper

import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.TimeTextDefaults

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearApp()
        }
    }
}

// Current PIN result state
enum class PinStatus {
    NONE,
    SUCCESS,
    FAILED
}

@Composable
fun WearApp() {

    // Demo values for preview
    // Demo values for preview
    val controller = remember { PinInputController() }
    val enteredDigits = controller.enteredDigits
    val pinStatus = controller.pinStatus

    val activity = LocalActivity.current

    LaunchedEffect(pinStatus) {
        when (pinStatus) {
            PinStatus.SUCCESS -> {
                delay(400)
                controller.reset()

                val intent = Intent(activity, MainActivity::class.java)

                activity?.finish()

                Handler(Looper.getMainLooper()).postDelayed({
                    activity?.startActivity(intent)
                }, 300)
            }

            PinStatus.FAILED -> {
                delay(600)
                controller.reset()
            }

            PinStatus.NONE -> return@LaunchedEffect
        }
    }
        BandPinWatchTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .clickable {
                        controller.onInput(0)
                    },
                contentAlignment = Alignment.Center
            ) {
                TimeText(
                    timeTextStyle = TimeTextDefaults.timeTextStyle(
                        color = Color(64, 224, 208)
                    )
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PinIndicator(
                        enteredDigits = enteredDigits
                    )

                    StatusMessage(
                        pinStatus = pinStatus
=======
import com.android.bandpinwatch.study.TrialLogger
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.android.bandpinwatch.ble.BandBleClient

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.wear.compose.material.TimeTextDefaults

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.android.bandpinwatch.presentation.screen.MenuScreen
import com.android.bandpinwatch.presentation.screen.SetPinScreen

import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_AFTER_UNLOCK = "after_unlock"

        /** Kept across unlock restarts so BLE stays connected for the next trial. */
        private var sharedBleClient: BandBleClient? = null
        private var restartingForUnlock = false
    }

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

        @Suppress("DEPRECATION") vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        controller = PinInputController(
            logger = TrialLogger(this),
            playHaptic = ::playHaptic,
        )

        /*if (intent.getBooleanExtra(EXTRA_AFTER_UNLOCK, false)) {
            controller.prepareNextTrial()
        } else {
            controller.startTrial()
        } */

        setContent {
            var currentScreen by remember {
                mutableStateOf(AppScreen.MENU)
            }

            BackHandler(enabled = currentScreen != AppScreen.MENU) {
                controller.cancelTrial()
                currentScreen = AppScreen.MENU
            }

            when (currentScreen) {
                AppScreen.MENU -> {
                    MenuScreen(
                        onEnterPinClick = {
                            controller.startTrial()
                            currentScreen = AppScreen.ENTER_PIN
                        },
                        onSetPinClick = {
                            controller.startSetPin()
                            currentScreen = AppScreen.SET_PIN
                        }
                    )
                }

                AppScreen.ENTER_PIN -> {
                    WearApp(controller, bandConnected)
                }

                AppScreen.SET_PIN -> {
                    SetPinScreen(
                        enteredDigits = controller.enteredCount,
                        isRepeatStep = controller.isRepeatStep
>>>>>>> Stashed changes
                    )
                }
            }
        }
<<<<<<< Updated upstream
    }

    // PIN progress circles
    @Composable
    fun PinIndicator(
        enteredDigits: Int,
        pinLength: Int = 4,
        colorCircle : Color = Color(64, 224, 208)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(pinLength) { index ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .then(
                            if (index < enteredDigits) {
                                Modifier.background(
                                    colorCircle,
                                    CircleShape
                                )
                            } else {
                                Modifier.border(
                                    2.dp,
                                    colorCircle,
                                    CircleShape
                                )
                            }
                        )
                )
=======


        ensureBlePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AFTER_UNLOCK, false)) {
            controller.prepareNextTrial()
        }
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
        val existing = sharedBleClient
        if (existing != null) {
            bleClient = existing
            existing.updateCallbacks(
                onEvent = { event -> runOnUiThread { controller.onBandEvent(event) } },
                onConnectionChange = { connected ->
                    runOnUiThread { bandConnected.value = connected }
                },
            )
            return
        }
        if (bleClient != null) return
        bleClient = BandBleClient(
            context = applicationContext,
            onEvent = { event -> runOnUiThread { controller.onBandEvent(event) } },
            onConnectionChange = { connected -> runOnUiThread { bandConnected.value = connected } },
        ).also {
            sharedBleClient = it
            it.start()
        }
    }

    /** Close the app (unlock), then reopen for the next PIN entry. BLE is kept alive. */
    fun closeAndReopenApp() {
        restartingForUnlock = true
        val appContext = applicationContext

        Handler(Looper.getMainLooper()).postDelayed({
            val restartIntent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_AFTER_UNLOCK, true)
            }
            appContext.startActivity(restartIntent)
        }, 500)

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (restartingForUnlock) {
            restartingForUnlock = false
        } else if (isFinishing) {
            sharedBleClient?.stop()
            sharedBleClient = null
        }
        bleClient = null
    }

    // ── Haptics — each cue must feel distinct (eyes-free!) ─────────────────

    private fun playHaptic(cue: HapticCue) {
        val effect = when (cue) {
            // Zone change: very short, weak, uniform
            HapticCue.TICK -> VibrationEffect.createWaveform(
                longArrayOf(0, 40),
                intArrayOf(0, 90),
                -1
            )

            // Select: clear, slightly stronger, still clean
            HapticCue.SELECT -> VibrationEffect.createWaveform(
                longArrayOf(0, 100),
                intArrayOf(0, 180),
                -1
            )

            // Delete: two short pulses
            HapticCue.DELETE -> VibrationEffect.createWaveform(
                longArrayOf(0, 60, 60, 60),
                intArrayOf(0, 180, 0, 180),
                -1
            )

            // Correct PIN: positive, three smooth pulses
            /*HapticCue.SUCCESS -> VibrationEffect.createWaveform(
                longArrayOf(0, 70, 50, 90, 50, 120),
                intArrayOf(0, 130, 0, 180, 0, 220),
                -1
            )*/
            HapticCue.SUCCESS -> VibrationEffect.createWaveform(
                longArrayOf(
                    0, 70, 50, 90, 50, 120,
                    50, 70, 50, 90, 50, 120
                ),
                intArrayOf(
                    0, 130, 0, 180, 0, 220,
                    0, 130, 0, 180, 0, 220
                ),
                -1
            )
            // Error: rough / repetitive / clearly different
            HapticCue.FAILURE ->
                VibrationEffect.createWaveform(
                    longArrayOf(0, 350),
                    intArrayOf(0, 255),
                    -1
                )
        }
        vibrator.vibrate(effect)
    }
}


// Current PIN result state
enum class PinStatus {
    NONE, SUCCESS, FAILED
}

@Composable
fun WearApp(
    controller: PinInputController,
    bandConnected: MutableState<Boolean>,
) {
    val enteredDigits = controller.enteredCount
    val phase = controller.phase
    val pinStatus = PinStatus.NONE
    val activity = LocalActivity.current as? MainActivity

    LaunchedEffect(phase, controller.lastTrialCorrect) {
        if (phase == StudyPhase.RESULT) {
            delay(700)

            if (controller.lastTrialCorrect) {
                activity?.closeAndReopenApp()
            } else {
                controller.retrySameTrial()
>>>>>>> Stashed changes
            }
        }
    }

<<<<<<< Updated upstream
    // Shows success or failure only after validation
    @Composable
    fun StatusMessage(
        pinStatus: PinStatus
    ) {
        val message = when (pinStatus) {
            PinStatus.SUCCESS -> stringResource(R.string.pin_success)
            PinStatus.FAILED -> stringResource(R.string.pin_failed)
            PinStatus.NONE -> null
        }

        if (message != null) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary,
                text = message
            )
        }
    }

    @Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
    @Composable
    fun DefaultPreview() {
        WearApp()
    }
=======
    BandPinWatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText(
                timeTextStyle = TimeTextDefaults.timeTextStyle(
                    color = if (bandConnected.value) Color.Green else Color.Red
                )
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PinIndicator(
                    enteredDigits = enteredDigits
                )

                StatusMessage(
                    pinStatus = pinStatus
                )
            }
        }
    }
}

// PIN progress circles
@Composable
fun PinIndicator(
    enteredDigits: Int,
    pinLength: Int = 4,
    colorCircle: Color = MaterialTheme.colors.primary //colorCircle: Color = Color(64, 224, 208)
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(pinLength) { index ->
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .then(
                        if (index < enteredDigits) {
                            Modifier.background(
                                colorCircle, CircleShape
                            )
                        } else {
                            Modifier.border(
                                2.dp, colorCircle, CircleShape
                            )
                        }
                    )
            )
        }
    }
}

// Shows success or failure only after validation
@Composable
fun StatusMessage(
    pinStatus: PinStatus
) {
    val message = when (pinStatus) {
        PinStatus.SUCCESS -> stringResource(R.string.pin_success)
        PinStatus.FAILED -> stringResource(R.string.pin_failed)
        PinStatus.NONE -> null
    }

    if (message != null) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary,
            text = message
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(
        controller = PinInputController(),
        bandConnected = remember { mutableStateOf(false) },
    )
}
>>>>>>> Stashed changes
