package com.biketrailplanner.data

import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideJournal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The raw fixes of every finished ride, kept on disk after the ride has been saved.
 *
 * The rider asked for one guarantee before a real trip: *"make sure the recording at least logs all
 * places I have been to"*, with the shaping of that data (stops, a Google Maps hand-off) explicitly
 * left for later.
 *
 * Everything the app stores as a *ride* has already been filtered: [com.biketrailplanner.domain.RideRecorder]
 * drops fixes worse than 50 m, skips jitter closer than 3 m, and the tracer only matches what OSM
 * knows. That is right for a distance and a route, and wrong as an archive - a fix rejected under a
 * bridge, or a garden path the tracer could not match, is still somewhere the rider went. The journal
 * (D62) already holds every fix, unfiltered, but it is **deleted** once the ride is saved, so until now
 * the only complete copy of a ride lived for as long as the save took.
 *
 * So the journal is copied here, verbatim, first. The file is plain text - one fix per line in the same
 * form the journal uses ([RideJournal.encode]), with a header line that names the ride - so it can be
 * pulled off the phone (`adb pull`) and replayed months later without this app or its database. A
 * header line starts with `#` and therefore fails [RideJournal.decode], which skips it: the file is
 * also a valid journal, so the existing replay path can rebuild a ride from it unchanged.
 *
 * Written from a plain [File] rather than a `Context` so all of this is JVM-testable
 * (`RideRawArchiveTest`): the archiving is the one thing that must not quietly stop working.
 */
class RideRawArchive(private val dir: File) {

    /**
     * Copy [fixes] into a new file named after the ride's start, and return it.
     *
     * Returns null when there is nothing to archive or the write failed - a missing archive must never
     * take a ride with it, so every failure here is reported and swallowed by the caller.
     */
    fun archive(fixes: List<RideFix>, startedAtMs: Long): File? {
        if (fixes.isEmpty()) return null
        return runCatching {
            dir.mkdirs()
            val file = File(dir, fileName(startedAtMs))
            file.bufferedWriter().use { out ->
                out.write(header(startedAtMs, fixes))
                out.write("\n")
                for (fix in fixes) {
                    out.write(RideJournal.encode(fix))
                    out.write("\n")
                }
            }
            prune()
            file
        }.getOrNull()
    }

    /** Every archived ride, newest first - the UI-free way to find them from a shell. */
    fun files(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.name }

    private fun header(startedAtMs: Long, fixes: List<RideFix>): String {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAtMs))
        val span = ((fixes.last().atEpochMs - fixes.first().atEpochMs) / 1000L).coerceAtLeast(0L)
        return "# ride $at, ${fixes.size} raw fixes, ${span}s span, " +
            "columns: lat,lon,epochMs,accuracyM,speedMps"
    }

    /** Keep the archive bounded: this is a safety net, not a rides database. */
    private fun prune() {
        files().drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    companion object {
        /** 50 rides of a few hundred kilobytes each. Old ones are deleted oldest first. */
        const val MAX_FILES = 50

        /** The directory, relative to the app's files dir, so `adb pull` has a stable path. */
        const val DIR_NAME = "ride-raw"

        /** `2026-10-25-1359-ride.txt`, from the ride's start, so files sort by ride. */
        fun fileName(startedAtMs: Long): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(startedAtMs))
            return "$stamp-ride.txt"
        }
    }
}
