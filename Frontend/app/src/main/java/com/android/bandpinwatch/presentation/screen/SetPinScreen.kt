package com.android.bandpinwatch.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.android.bandpinwatch.presentation.PinIndicator
import androidx.compose.foundation.layout.Box

@Composable
fun SetPinScreen (
    enteredDigits: Int,
    isRepeatStep: Boolean
) {
    val title = if (isRepeatStep) {
        "Repeat PIN"
    } else {
            "Set new PIN"
        }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = MaterialTheme.colors.primary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        )

        PinIndicator(
            enteredDigits = enteredDigits
        )
    }
}