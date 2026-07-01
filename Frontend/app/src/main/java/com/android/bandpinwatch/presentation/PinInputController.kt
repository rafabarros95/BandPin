package com.android.bandpinwatch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PinInputController {
    var enteredDigits by mutableStateOf(0)
        private set

    var pinStatus by mutableStateOf(PinStatus.NONE)
        private set

    fun onInput(input: Int) {
        //Ignore invalid inputs
        if (input !in 0..3) {
            return

        }

        if (enteredDigits >= 4) {
            return
        }

        enteredDigits++

        // Call Validiation
        validatePin()
        if (enteredDigits == 4) {
           // delay(200)
            //reset()
        }
        println(enteredDigits)
    }

    fun reset() {
        enteredDigits = 0
        pinStatus = PinStatus.NONE
    }

    fun validatePin() {
        if( enteredDigits < 4) {
            return
        }
        pinStatus = PinStatus.SUCCESS


    }
}