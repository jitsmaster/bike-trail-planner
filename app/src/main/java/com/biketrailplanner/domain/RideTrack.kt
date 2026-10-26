package com.biketrailplanner.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import java.util.Locale

/**
 * D51: one finished, recorded ride - the ride the rider ACTUALLY rode, not the one that was planned.
 *
 * It is graded by the same `SafetyGrader` a planned route uses, so an unposted road on the real ride
 * is still UNGRADED and the ride is never called safe. There is no trail label, no target mileage and
 * no reuse bucket: a recording starts wherever the rider did and ends wherever they stopped.
 */
data class RideTrack(
    val signature: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double?,
    val gaps: Int,
    val rejectedFixes: Int,
    val points: List<LatLon>,
    /** OSM road way ids the trace found: the identity used for rotation and overlap. */
    val wayIds: Set<Long>,
    /**
     * D64: the roads this ride actually ran on, in the order it rode them, with the distance at which
     * each one starts. This is the ride's identity - "the roads I took" - and the raw material the
     * Google Maps stops are chosen from ([StopPicker]). Empty for a ride that could not be traced, and
     * for every ride stored before this field existed (the payload is read leniently).
     */
    val roads: List<RideRoad> = emptyList(),
    val named: List<NamedSegment>,
    val gradedM: Double,
    val ungradedM: Double,
    /** Metres the ladder refused outright (bad surface). Kept separately, like [GradeBreakdown]. */
    val rejectedM: Double,
    val breakdownTotalM: Double,
    val byGrade: Map<Grade, Double>,
    /** True when the safety data changed since this ride was graded. */
    val safetyStale: Boolean,
    val dataFingerprint: String,
    /** Rider-facing label, e.g. the first couple of street names, or "Recorded ride". */
    val label: String,
    val createdAt: Long,
) {
    val distanceMiles: Double get() = Geo.metresToMiles(distanceM)
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
    val ungradedShare: Double get() = if (breakdownTotalM <= 0.0) 0.0 else ungradedM / breakdownTotalM
    val measuredShare: Double get() = if (breakdownTotalM <= 0.0) 0.0 else gradedM / breakdownTotalM
    val hasTrack: Boolean get() = points.size >= 2

    /**
     * Metres for a grade, read exactly the way [GradeBreakdown.metres] reads it: [byGrade] carries only
     * the graded metres, so UNGRADED and REJECTED must come from their own fields. A caller that draws
     * every grade must use this, or those two silently draw nothing (which is how the Past-rides bar
     * rendered an empty bar for a 100%-ungraded ride).
     */
    fun metres(grade: Grade): Double = when (grade) {
        Grade.UNGRADED -> ungradedM
        Grade.REJECTED -> rejectedM
        else -> byGrade[grade] ?: 0.0
    }
}

/** Where recorded rides live (Room in the app, a map in tests), mirroring [RideHistoryStore]. */
interface RideTrackStore {
    suspend fun count(): Int

    /** Newest first. */
    suspend fun all(limit: Int = DEFAULT_LIMIT): List<RideTrack>

    suspend fun save(track: RideTrack)

    suspend fun delete(signature: String)

    /**
     * D67: the rides, as a stream, so a ride that is saved while the screen is open appears without
     * anything having to ask again.
     *
     * Measured on the phone 2026-10-26: the rider stopped a recording, the ride was saved, and the list
     * still said "No recorded rides yet" until the screen was left and re-entered - the screen loaded the
     * list once, on entry, and nothing reloaded it after the save.
     *
     * A store that cannot observe (the in-memory one, and any test double) still works: the default is
     * one read, which is exactly the old behaviour.
     */
    fun observe(limit: Int = DEFAULT_LIMIT): Flow<List<RideTrack>> = flow { emit(all(limit)) }

    companion object {
        const val DEFAULT_LIMIT = 50

        /** Rides kept. A recorded ride is small (a few KB), so this is a generous history. */
        const val GLOBAL_CAP = 200
    }
}

class InMemoryRideTrackStore : RideTrackStore {
    private val rows = LinkedHashMap<String, RideTrack>()

    override suspend fun count(): Int = rows.size

    override suspend fun all(limit: Int): List<RideTrack> =
        rows.values.sortedByDescending { it.startedAtMs }.take(limit)

    override suspend fun save(track: RideTrack) {
        rows[track.signature] = track
        rows.values.sortedByDescending { it.startedAtMs }
            .drop(RideTrackStore.GLOBAL_CAP)
            .forEach { rows.remove(it.signature) }
    }

    override suspend fun delete(signature: String) {
        rows.remove(signature)
    }
}

/**
 * A recording's identity: the shape it took plus when it started. Re-saving the same recording
 * upserts the same row; two rides on the same roads at different times are different rides.
 */
object RideTrackSignature {
    fun of(points: List<LatLon>, startedAtMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        points.forEach {
            digest.update(String.format(Locale.US, "%.6f,%.6f;", it.lat, it.lon).toByteArray())
        }
        digest.update(startedAtMs.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}
