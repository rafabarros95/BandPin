package com.android.bandpinwatch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.bandpinwatch.ble.BandEvent
import com.android.bandpinwatch.ble.BandEventType
import com.android.bandpinwatch.study.TrialLogger
import kotlin.random.Random

/** Haptic cues the controller asks the watch to play. */
enum class HapticCue { TICK, SELECT, DELETE, SUCCESS, FAILURE }

/** Study flow phases. */
enum class StudyPhase { SETUP, ENTERING, RESULT }

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

    var trialNumber by mutableStateOf(1)
        private set

    var targetPin by mutableStateOf(generatePin())
        private set

    /** Number of digits entered — the UI only ever sees the count, never the digits. */
    var enteredCount by mutableStateOf(0)
        private set

    var lastTrialCorrect by mutableStateOf(false)
        private set

    var lastEntryTimeMs by mutableStateOf(0L)
        private set

    private val enteredDigits = mutableListOf<Int>()
    private var firstContactMs: Long = 0
    private var numTicks = 0
    private var numDeletes = 0
    private var numSelects = 0

    private val participantId: String
        get() = "P%02d".format(participantNumber)

    // ── Setup screen actions ────────────────────────────────────────────────

    fun changeParticipant(delta: Int) {
        if (phase != StudyPhase.SETUP) return
        participantNumber = (participantNumber + delta).coerceIn(1, 99)
        trialNumber = 1
        targetPin = generatePin()
    }

    /** Toggle between single-strip (0-4) and two-strip (0-9) code generation. */
    fun toggleDigitRange() {
        if (phase != StudyPhase.SETUP) return
        maxDigit = if (maxDigit == 4) 9 else 4
        targetPin = generatePin()
    }

    /** Participant has memorised the code — hide it and start the trial. */
    fun startTrial() {
        enteredDigits.clear()
        enteredCount = 0
        firstContactMs = 0
        numTicks = 0
        numDeletes = 0
        numSelects = 0
        phase = StudyPhase.ENTERING
    }

    /** Move on to the next trial with a fresh random code. */
    fun nextTrial() {
        trialNumber++
        targetPin = generatePin()
        phase = StudyPhase.SETUP
    }

    // ── Band events ─────────────────────────────────────────────────────────

    fun onBandEvent(event: BandEvent) {
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
                if (enteredDigits.size >= PIN_LENGTH) return
                numSelects++
                enteredDigits.add(event.digit)
                enteredCount = enteredDigits.size
                playHaptic(HapticCue.SELECT)
                if (enteredDigits.size == PIN_LENGTH) confirmEntry(event.receivedAtMs)
            }

            BandEventType.DELETE -> {
                if (enteredDigits.isNotEmpty()) {
                    numDeletes++
                    enteredDigits.removeAt(enteredDigits.lastIndex)
                    enteredCount = enteredDigits.size
                    playHaptic(HapticCue.DELETE)
                }
            }

            BandEventType.DOWN, BandEventType.UP -> Unit // logged only
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** 4th digit entered → auto-confirm, no separate confirm gesture. */
    private fun confirmEntry(nowMs: Long) {
        val correct = enteredDigits == targetPin
        lastTrialCorrect = correct
        lastEntryTimeMs = if (firstContactMs > 0) nowMs - firstContactMs else 0

        logger?.logTrial(
            participant = participantId,
            trial = trialNumber,
            target = targetPin,
            entered = enteredDigits.toList(),
            correct = correct,
            entryTimeMs = lastEntryTimeMs,
            numSelects = numSelects,
            numDeletes = numDeletes,
            numTicks = numTicks,
        )

        playHaptic(if (correct) HapticCue.SUCCESS else HapticCue.FAILURE)
        phase = StudyPhase.RESULT
    }

    private fun generatePin(): List<Int> = List(PIN_LENGTH) { Random.nextInt(0, maxDigit + 1) }
}
