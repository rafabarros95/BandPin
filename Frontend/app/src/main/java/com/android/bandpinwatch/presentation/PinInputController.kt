package com.android.bandpinwatch.presentation

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.bandpinwatch.ble.BandEvent
import com.android.bandpinwatch.ble.BandEventType
import com.android.bandpinwatch.study.TrialLogger
import kotlin.math.abs


/** Haptic cues the controller asks the watch to play. */
enum class HapticCue { TICK, SELECT, DELETE, SUCCESS, FAILURE }

/** Study flow phases. */
enum class StudyPhase { SETUP, ENTERING, RESULT }

enum class PinMode { ENTER_PIN, SET_PIN }

enum class SetPinStep { FIRST_ENTRY, REPEAT_ENTRY, DONE, MISMATCH }

/**
 * State machine for one study session (study mode, eyes-free):
 *
 *   SETUP     target code is shown, participant memorises it, taps Start
 *   ENTERING  screen shows ONLY the progress dots — digits come in as
 *             SELECT events from the band, ticks are felt not seen
 *   RESULT    correct / wrong + entry time, then next trial
 *
 * All band events are forwarded here by MainActivity (on the UI thread).
 */
class PinInputController(
    private val logger: TrialLogger? = null,
    private val playHaptic: (HapticCue) -> Unit = {},
    private var lastSelectMs: Long = 0,
    initialTrialNumber: Int = 1,
    private val saveNextTrialNumber: (Int) -> Unit = {},

    // save new Pin
    initialPin: List<Int> = listOf(5, 5, 6, 7),
    private val savePin: (List<Int>) -> Unit = {},
    private val onSetPinFinished: () -> Unit = {},


    ) {
    companion object {
        const val PIN_LENGTH = 4
    }

    var phase by mutableStateOf(StudyPhase.SETUP)
        private set

    var participantNumber by mutableStateOf(1)
        private set

    /**
     * Highest digit used in generated codes. 4 = single-strip testing
     * (only strip A connected), 9 = full two-strip layout.
     */
    var maxDigit by mutableStateOf(4)
        private set

    var trialNumber by mutableStateOf(initialTrialNumber)
        private set

    /*var targetPin by mutableStateOf(generatePin())
        private set*/
    var targetPin by mutableStateOf(initialPin)
        private set

    /** Number of digits entered — the UI only ever sees the count, never the digits. */
    var enteredCount by mutableStateOf(0)
        private set

    var lastTrialCorrect by mutableStateOf(false)
        private set

    var lastEntryTimeMs by mutableStateOf(0L)
        private set

    var mode by mutableStateOf(PinMode.ENTER_PIN)
        private set

    var setPinStep by mutableStateOf(SetPinStep.FIRST_ENTRY)
        private set

    var isRepeatStep by mutableStateOf(false)
        private set

    private val firstSetPin = mutableListOf<Int>()
    private val enteredDigits = mutableListOf<Int>()
    private var firstContactMs: Long = 0
    private var trialStartMs: Long = 0

    private var lastDeleteMs: Long = 0

    private var setPinFinishing = false
    private val recentLoggedEvents = mutableMapOf<String, Long>()

    private var numTicks = 0
    private var numDeletes = 0
    private var numSelects = 0

    private var selectionErrorCount = 0
    private var neighborErrorCount = 0

    private val participantId: String
        get() = "P%02d".format(participantNumber)

    // ── Setup screen actions ────────────────────────────────────────────────

    fun changeParticipant(delta: Int) {
        if (phase != StudyPhase.SETUP) return
        participantNumber = (participantNumber + delta).coerceIn(1, 99)
        trialNumber = 1
    }

    /** Toggle between single-strip (0-4) and two-strip (0-9) code generation. */
    fun toggleDigitRange() {
        if (phase != StudyPhase.SETUP) return
        maxDigit = if (maxDigit == 4) 9 else 4
    }

    /** Participant has memorised the code — hide it and start the trial. */
    fun startTrial() {
        mode = PinMode.ENTER_PIN

        enteredDigits.clear()
        enteredCount = 0

        firstContactMs = 0
        trialStartMs = System.currentTimeMillis()

        numTicks = 0
        numDeletes = 0
        numSelects = 0

        selectionErrorCount = 0
        neighborErrorCount = 0

        lastSelectMs = 0
        lastDeleteMs = 0

        logger?.logSessionEvent(
            participant = participantId, trial = trialNumber, eventName = "TRIAL_START"
        )

        phase = StudyPhase.ENTERING
    }

    fun cancelTrial() {
        if (phase == StudyPhase.ENTERING) {
            logger?.logSessionEvent(
                participant = participantId, trial = trialNumber, eventName = "TRIAL_CANCELLED"
            )
        }

        finishTrial()
    }

    private fun finishTrial() {
        enteredDigits.clear()
        enteredCount = 0
        firstContactMs = 0
        lastSelectMs = 0
        lastDeleteMs = 0

        trialNumber++
        phase = StudyPhase.SETUP
    }

    fun startSetPin() {
        mode = PinMode.SET_PIN
        setPinStep = SetPinStep.FIRST_ENTRY
        isRepeatStep = false

        // اجازه شروع یک Set PIN جدید
        setPinFinishing = false

        firstSetPin.clear()
        enteredDigits.clear()
        enteredCount = 0

        firstContactMs = 0
        trialStartMs = System.currentTimeMillis()

        numTicks = 0
        numDeletes = 0
        numSelects = 0

        selectionErrorCount = 0
        neighborErrorCount = 0

        lastSelectMs = 0
        lastDeleteMs = 0

        phase = StudyPhase.ENTERING
    }

    /** Ready for another eyes-free entry — fresh code, same participant. */
    fun prepareNextTrial() {
        startTrial()
    }

    // ── Band events ─────────────────────────────────────────────────────────

    fun onBandEvent(event: BandEvent) {

        if (setPinFinishing) return


        val now = event.receivedAtMs
        val key = "${event.type};${event.strip};${event.digit};${event.boardTimeMs}"

        recentLoggedEvents.entries.removeAll { now - it.value > 500 }

        val lastSeen = recentLoggedEvents[key]
        if (lastSeen != null && now - lastSeen < 500) {
            return
        }

        recentLoggedEvents[key] = now

        logger?.logEvent(participantId, trialNumber, event)

        if (phase != StudyPhase.ENTERING) return

        // entry time runs from the first contact to the auto-confirm
        if (firstContactMs == 0L && event.type == BandEventType.DOWN) {
            firstContactMs = event.receivedAtMs
        }

        when (event.type) {
            BandEventType.TICK -> {
                numTicks++
                playHaptic(HapticCue.TICK)
            }

            BandEventType.SELECT -> {
                if (event.receivedAtMs - lastSelectMs < 300) return
                lastSelectMs = event.receivedAtMs

                if (enteredDigits.size >= PIN_LENGTH) return

                val expectedDigit = targetPin.getOrNull(enteredDigits.size)

                val isWrongDigit = expectedDigit != null && event.digit != expectedDigit

                if (isWrongDigit) {
                    selectionErrorCount++

                    if (abs(event.digit - expectedDigit) == 1) {
                        neighborErrorCount++
                    }
                }

                numSelects++
                enteredDigits.add(event.digit)
                enteredCount = enteredDigits.size


                if (enteredDigits.size == PIN_LENGTH) {
                    if (mode == PinMode.SET_PIN) {
                        handleSetPinCompleted()
                    } else {
                        confirmEntry(event.receivedAtMs)
                    }
                } else {
                    playHaptic(
                        if (mode == PinMode.SET_PIN) {
                            HapticCue.SELECT
                        } else {
                            if (isWrongDigit) HapticCue.DELETE else HapticCue.SELECT
                        }
                    )
                }
            }

            BandEventType.DELETE -> {
                if (event.receivedAtMs - lastDeleteMs < 400) return
                lastDeleteMs = event.receivedAtMs

                if (enteredDigits.isNotEmpty()) {
                    numDeletes++
                    enteredDigits.removeAt(enteredDigits.lastIndex)
                    enteredCount = enteredDigits.size
                    playHaptic(HapticCue.DELETE)
                }

                return
            }

            BandEventType.DOWN, BandEventType.UP -> Unit // logged only
        }
    }


    private fun handleSetPinCompleted() {


        if (setPinFinishing) return

        if (setPinStep == SetPinStep.FIRST_ENTRY) {


            firstSetPin.clear()
            firstSetPin.addAll(enteredDigits)

            enteredDigits.clear()
            enteredCount = 0


            isRepeatStep = true
            setPinStep = SetPinStep.REPEAT_ENTRY


            lastSelectMs = System.currentTimeMillis()
            lastDeleteMs = 0

            playHaptic(HapticCue.SUCCESS)

            return
        }

        if (setPinStep == SetPinStep.REPEAT_ENTRY) {


            val repeatedPin = enteredDigits.toList()
            val originalPin = firstSetPin.toList()

            if (repeatedPin == originalPin) {


                setPinFinishing = true

                targetPin = originalPin
                savePin(originalPin)

                enteredDigits.clear()
                enteredCount = 0

                setPinStep = SetPinStep.DONE
                isRepeatStep = false


                phase = StudyPhase.RESULT

                lastSelectMs = System.currentTimeMillis()
                lastDeleteMs = System.currentTimeMillis()

                playHaptic(HapticCue.SUCCESS)


                Handler(Looper.getMainLooper()).postDelayed({
                    onSetPinFinished()
                }, 600)

            } else {


                enteredDigits.clear()
                enteredCount = 0
                firstSetPin.clear()

                isRepeatStep = false
                setPinStep = SetPinStep.FIRST_ENTRY

                lastSelectMs = System.currentTimeMillis()
                lastDeleteMs = 0

                playHaptic(HapticCue.FAILURE)
            }
        }
    }

// ── Internals ───────────────────────────────────────────────────────────

    /** 4th digit entered → auto-confirm, no separate confirm gesture. */
    private fun confirmEntry(nowMs: Long) {
        lastEntryTimeMs = if (firstContactMs > 0) nowMs - firstContactMs else 0

        val correct = enteredDigits == targetPin
        val completionTimeMs = if (trialStartMs > 0) nowMs - trialStartMs else 0
        val condition = if (maxDigit == 4) "SingleStrip" else "DualStrip"

        lastTrialCorrect = correct

        logger?.logTrial(
            participant = participantId,
            trial = trialNumber,
            target = targetPin,
            entered = enteredDigits.toList(),
            correct = correct,
            selectionErrorCount = selectionErrorCount,
            neighborErrorCount = neighborErrorCount,
            entryTimeMs = lastEntryTimeMs,
            completionTimeMs = completionTimeMs,
            numSelects = numSelects,
            numDeletes = numDeletes,
            numTicks = numTicks,
            condition = condition
        )

        logger?.logSessionEvent(
            participant = participantId,
            trial = trialNumber,
            eventName = if (correct) "TRIAL_END_SUCCESS" else "TRIAL_END_FAILED"
        )

        playHaptic(if (correct) HapticCue.SUCCESS else HapticCue.FAILURE)

        trialNumber++
        saveNextTrialNumber(trialNumber)

        if (correct) {
            phase = StudyPhase.RESULT
        } else {
            retrySameTrial()
        }
    }

    fun retrySameTrial() {
        enteredDigits.clear()
        enteredCount = 0
        firstContactMs = 0
        lastSelectMs = 0

        numTicks = 0
        numDeletes = 0
        numSelects = 0

        selectionErrorCount = 0
        neighborErrorCount = 0

        trialStartMs = System.currentTimeMillis()
        lastDeleteMs = 0
        phase = StudyPhase.ENTERING
    }

    private fun generatePin(): List<Int> {
        return listOf(5, 5, 6, 7)
    }
}
