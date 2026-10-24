package com.biketrailplanner.data

import android.content.Context
import android.util.Log
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideJournal
import java.io.File

/**
 * D62: the append-only journal of the fixes of the recording in progress.
 *
 * One file, one line per fix, written and closed on arrival. It is deliberately dumb: no database, no
 * buffering, no state worth losing. That is what makes it survive a process kill - the file is either
 * there or it is not, and everything it says has already been flushed to disk.
 *
 * It is NOT the saved ride. `ride_track` is what the rider sees; this is the raw material for it, and
 * it is deleted the moment the ride has been saved.
 */
class RideFixJournal(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private var capped = false

    /** Append one fix. A failure here must not stop the recording, so it is swallowed. */
    fun append(fix: RideFix) {
        if (file.length() > MAX_BYTES) {
            // Logged once per overrun rather than silently: a cap that quietly stops writing would look
            // exactly like a working recorder until the ride was saved short.
            if (!capped) Log.w(TAG, "journal reached $MAX_BYTES bytes; not writing further fixes")
            capped = true
            return
        }
        runCatching { file.appendText(RideJournal.encode(fix) + "\n") }
    }

    /**
     * Whether anything has been written, WITHOUT parsing a multi-hour file.
     *
     * The UI asks this on every state read; `readAll()` on a three-hour ride is 5,400 lines and on a
     * very long one far more, and doing that on the main thread is not acceptable.
     */
    fun hasFixes(): Boolean = file.length() > 0L

    /** Every fix written so far, oldest first. A missing file is simply an empty recording. */
    fun readAll(): List<RideFix> =
        runCatching { RideJournal.decodeAll(file.readLines()) }.getOrDefault(emptyList())

    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        private const val FILE_NAME = "ride-journal.txt"
        private const val TAG = "RideFixJournal"

        /** ~1 fix every 2 s at ~60 bytes each. 8 MB is about 74 hours of riding: a cap, not a target. */
        private const val MAX_BYTES = 8L * 1024L * 1024L
    }
}
