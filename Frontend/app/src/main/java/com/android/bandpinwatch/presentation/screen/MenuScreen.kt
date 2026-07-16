package com.android.bandpinwatch.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import java.nio.file.WatchEvent


@Composable
fun MenuScreen (
    onEnterPinClick: () -> Unit,
    onSetPinClick: () -> Unit
) {
    Column (
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    )
    {
        Text(
            text = "BandPin",
            color = MaterialTheme.colors.primary,
            modifier = Modifier.padding(top= 8.dp)
        )
        Spacer(modifier = Modifier.height(5.dp))

        Button(
            modifier = Modifier.width(95.dp),
            onClick = onEnterPinClick
        ) {
            Text("Enter PIN")
        }
        Button(
            modifier = Modifier.width(95.dp),
            onClick = onSetPinClick
        ) {
            Text("Set Pin")
        }

    }
}