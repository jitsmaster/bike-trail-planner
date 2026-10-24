package com.biketrailplanner.domain

/**
 * D62: the on-disk form of one GPS fix, so a ride survives the death of its process.
 *
 * The recorder accumulates in memory, and a 16-mile ride died because of it: Android killed the app
 * mid-ride (`lowmemorykiller` is in the log), every fix went with the process, and tapping Stop in the
 * new process found nothing to save. A ride is the rider's actual work; it must not depend on one
 * process staying alive for an hour.
 *
 * One fix per line, comma separated, so an append is a single write and a truncated last line costs at
 * most that one fix. A line that will not parse is skipped rather than losing the whole journal - the
 * alternative is throwing away a ride because of one bad character.
 *
 * `accuracyM` is always present (it is a non-null `Double` on [RideFix]); `speedMps` may be absent,
 * which is why the two are encoded differently.
 */
object RideJournal {

    fun encode(fix: RideFix): String =
        "${fix.point.lat},${fix.point.lon},${fix.atEpochMs},${fix.accuracyM}," + (fix.speedMps ?: "")

    fun decode(line: String): RideFix? {
        val parts = line.trim().split(',')
        if (parts.size < 4) return null
        return runCatching {
            RideFix(
                point = LatLon(parts[0].toDouble(), parts[1].toDouble()),
                atEpochMs = parts[2].toLong(),
                accuracyM = parts[3].toDouble(),
                speedMps = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.toDouble(),
            )
        }.getOrNull()
    }

    fun decodeAll(lines: List<String>): List<RideFix> = lines.mapNotNull(::decode)
}
