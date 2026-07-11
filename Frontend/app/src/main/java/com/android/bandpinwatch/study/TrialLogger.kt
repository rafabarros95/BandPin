package com.android.bandpinwatch.study

import android.content.Context
import com.android.bandpinwatch.ble.BandEvent
import java.io.File
import java.util.Locale

/**
 * On-watch study logger. Writes two CSV files per app session into the
 * app-private files dir, ready for pandas / R:
 *
 *   files/bandpin_study/<session>/events.csv  — one row per band event
 *   files/bandpin_study/<session>/trials.csv  — one row per completed trial
 *
 * Pull them after a session with:
 *   adb pull /data/data/com.android.bandpinwatch/files/bandpin_study .
 * (or `adb shell run-as com.android.bandpinwatch ...` on production builds)
 */

class TrialLogger(context: Context) {

    private val baseDir = File(context.filesDir, "bandpin_study")
    private val eventsFile = File(baseDir, "events.csv")
    private val trialsFile = File(baseDir, "trials.csv")

    init {
        baseDir.mkdirs()

        if (!eventsFile.exists()) {
            eventsFile.writeText(
                "participantId;trialNumber;timestamp;eventType;strip;zone;digit;position;boardTimeMs;receivedAtMs\n"
            )
        }

        if (!trialsFile.exists()) {
            trialsFile.writeText(
                "participantId;trialNumber;targetPin;enteredPin;correct;selectionErrorCount;neighborErrorCount;entryTimeMs;completionTimeMs;numSelects;numDeletes;numTicks;condition\n"
            )
        }
    }

    fun logEvent(
        participant: String,
        trial: Int,
        event: BandEvent
    ) {
        val stripName = if (event.strip == 0) "A" else "B"
        val zone = event.digit % 5

        val row = listOf(
            participant,
            trial,
            event.receivedAtMs,
            event.type.name,
            stripName,
            zone,
            event.digit,
            "%.2f".format(Locale.US, event.position),
            event.boardTimeMs,
            event.receivedAtMs
        ).joinToString(";") + "\n"

        eventsFile.appendText(row)
    }

    fun logTrial(
        participant: String,
        trial: Int,
        target: List<Int>,
        entered: List<Int>,
        correct: Boolean,
        selectionErrorCount: Int,
        neighborErrorCount: Int,
        entryTimeMs: Long,
        completionTimeMs: Long,
        numSelects: Int,
        numDeletes: Int,
        numTicks: Int,
        condition: String
    ) {
        val row = listOf(
            participant,
            trial,
            target.joinToString(""),
            entered.joinToString(""),
            correct,
            selectionErrorCount,
            neighborErrorCount,
            entryTimeMs,
            completionTimeMs,
            numSelects,
            numDeletes,
            numTicks,
            condition
        ).joinToString(";") + "\n"

        trialsFile.appendText(row)
    }


    fun logSessionEvent(
        participant: String,
        trial: Int,
        eventName: String,
    ) {
        val now = System.currentTimeMillis()

        val line = listOf(
            participant,
            trial.toString(),
            now.toString(),
            eventName,
            "",
            "",
            "",
            "",
            "",
            now.toString()
        ).joinToString(";")

        eventsFile.appendText(line + "\n")
    }


}