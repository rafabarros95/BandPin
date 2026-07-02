package com.android.bandpinwatch.study

import android.content.Context
import android.util.Log
import com.android.bandpinwatch.ble.BandEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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

    private val sessionDir: File
    private val eventsFile: File
    private val trialsFile: File

    init {
        val tag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionDir = File(File(context.filesDir, "bandpin_study"), "session_$tag")
        sessionDir.mkdirs()
        eventsFile = File(sessionDir, "events.csv")
        trialsFile = File(sessionDir, "trials.csv")

        eventsFile.appendText(
            "wall_ms,participant,trial,event,strip,digit,position,board_ms\n"
        )
        trialsFile.appendText(
            "participant,trial,target,entered,correct,entry_time_ms," +
                "num_selects,num_deletes,num_ticks,timestamp\n"
        )
        Log.i("TrialLogger", "logging to $sessionDir")
    }

    fun logEvent(participant: String, trial: Int, event: BandEvent) {
        eventsFile.appendText(
            "${event.receivedAtMs},$participant,$trial,${event.type}," +
                "${event.strip},${event.digit},${"%.2f".format(Locale.US, event.position)}," +
                "${event.boardTimeMs}\n"
        )
    }

    fun logTrial(
        participant: String,
        trial: Int,
        target: List<Int>,
        entered: List<Int>,
        correct: Boolean,
        entryTimeMs: Long,
        numSelects: Int,
        numDeletes: Int,
        numTicks: Int,
    ) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        trialsFile.appendText(
            "$participant,$trial,${target.joinToString("")}," +
                "${entered.joinToString("")},${if (correct) 1 else 0}," +
                "$entryTimeMs,$numSelects,$numDeletes,$numTicks,$iso\n"
        )
    }
}
