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
        WearApp()
    }