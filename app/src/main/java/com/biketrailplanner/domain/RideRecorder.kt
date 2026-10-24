package com.biketrailplanner.domain

/**
 * One GPS fix the recorder considered.
 *
 * [accuracyM] is the horizontal accuracy the platform reported (larger is worse), and [speedMps] is
 * the platform's own speed when it has one - it is only ever reported, never used to move the track.
 */
data class RideFix(
    val point: LatLon,
    val atEpochMs: Long,
    val accuracyM: Double,
    val speedMps: Double? = null,
)

/** A recorded ride, before it is traced and graded (D51 in docs/DECISIONS.md). */
data class RecordedRide(
    val points: List<LatLon>,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double?,
    /** Fixes thrown away because their accuracy was worse than the recorder's limit. */
    val rejectedFixes: Int,
    /**
     * Times the track jumped by more than [RideRecorder.gapMs] without a fix. A gap is surfaced to the
     * rider rather than hidden: the saved trip is only as complete as the recording was.
     */
    val gaps: Int,
) {
    val distanceMiles: Double get() = Geo.metresToMiles(distanceM)
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)

    /**
     * True when there is a line to draw and trace: at least two accepted points.
     *
     * This is deliberately NOT the gate on keeping a ride. Whether a recording is worth listing is a
     * separate question - see [hasFixes].
     */
    val hasTrack: Boolean get() = points.size >= 2

    /**
     * True when the recording captured at least one usable fix, so there is a ride to keep even when it
     * is far too short to draw. A rider who presses record and rides down the street has taken a ride,
     * and it belongs in the list whatever its length; only a recording that caught no fix at all has
     * nothing to store.
     */
    val hasFixes: Boolean get() = points.isNotEmpty()
}

/**
 * D51: the pure half of the in-app ride recorder.
 *
 * The rider explores streets that were never in the plan, so the only truthful record of a ride is
 * the one the app takes from GPS as it happens. This class turns a stream of fixes into a
 * [RecordedRide] and has no Android type in it at all, so the whole behaviour is unit-tested on the
 * JVM (`RideRecorderTest`) and driven four ways without riding (docs/RIDE-RECORDER.md section 4).
 *
 * What it does deliberately:
 *  - **drops poor fixes** rather than dragging the track across a block;
 *  - treats a fix closer than [minStepM] to the last accepted one as **jitter, not travel**;
 *  - never counts a silence longer than [gapMs] as moving time, and counts it as a **gap** instead,
 *    because a track with a hole in it must not be presented as complete;
 *  - anchors again after a pause, so a lunch stop is not recorded as a 30 km/h sprint.
 *
 * What it does NOT do: it never grades anything and never claims a road is safe. The recorded shape is
 * handed to the existing Valhalla `trace_attributes` + `SafetyGrader` path exactly like a planned
 * route, so an unposted road on the actual ride is still UNGRADED and the trip is still not verified.
 */
class RideRecorder(
    /** Fixes worse than this are dropped: a 200 m fix would drag the track across a block. */
    private val maxAccuracyM: Double = 50.0,
    /** A fix closer than this to the last accepted one is GPS jitter, not movement. */
    private val minStepM: Double = 3.0,
    /** A silence longer than this is a gap, not travel: never counted as moving time. */
    val gapMs: Long = 30_000L,
) {

    private val points = ArrayList<LatLon>()
    private var startedAtMs = 0L
    private var lastAtMs = 0L
    private var hasAnchor = false
    private var distanceM = 0.0
    private var movingMs = 0L
    private var maxSpeedMps: Double? = null
    private var rejectedFixes = 0
    private var gaps = 0
    private var recording = false
    private var paused = false

    val isRecording: Boolean get() = recording
    val isPaused: Boolean get() = paused

    /** Begin a ride. [atEpochMs] is when the rider pressed record, not necessarily the first fix. */
    fun start(atEpochMs: Long) {
        points.clear()
        startedAtMs = atEpochMs
        lastAtMs = 0L
        hasAnchor = false
        distanceM = 0.0
        movingMs = 0L
        maxSpeedMps = null
        rejectedFixes = 0
        gaps = 0
        recording = true
        paused = false
    }

    /** Stop accumulating until [resume]; fixes offered while paused are ignored. */
    fun pause() {
        if (recording) paused = true
    }

    /** Resume: the next accepted fix becomes the new anchor, so the pause is never counted as travel. */
    fun resume() {
        if (recording) {
            paused = false
            hasAnchor = false
        }
    }

    /** Offer one fix. Returns true when it was accepted into the track. */
    fun onFix(fix: RideFix): Boolean {
        if (!recording || paused) return false
        if (!fix.accuracyM.isFinite() || fix.accuracyM > maxAccuracyM) {
            rejectedFixes++
            return false
        }
        if (points.isEmpty()) {
            points.add(fix.point)
            lastAtMs = fix.atEpochMs
            hasAnchor = true
            recordSpeed(fix)
            return true
        }
        // A resume (or the first fix after start) anchors the track without inventing travel.
        if (!hasAnchor) {
            points.add(fix.point)
            lastAtMs = fix.atEpochMs
            hasAnchor = true
            recordSpeed(fix)
            return true
        }
        val dt = fix.atEpochMs - lastAtMs
        val stepM = Geo.distanceM(points.last(), fix.point)
        if (dt >= gapMs) gaps++ else movingMs += dt.coerceAtLeast(0L)
        if (stepM < minStepM) {
            // Too close to be real movement. Keep the anchor time fresh so a stationary rider does not
            // look like a gap, but add no distance and no point.
            lastAtMs = fix.atEpochMs
            recordSpeed(fix)
            return false
        }
        distanceM += stepM
        points.add(fix.point)
        lastAtMs = fix.atEpochMs
        recordSpeed(fix)
        return true
    }

    /**
     * Finish and return the ride, or null ONLY when the recording captured no usable fix at all.
     *
     * A one-point ride is still returned. The old gate was "two accepted points or you get nothing",
     * which silently discarded a short ride - or any ride whose fixes never got more than the step
     * filter's 3 m apart before the rider stopped. Length is the map's problem, not the recorder's.
     */
    fun finish(atEpochMs: Long): RecordedRide? {
        if (!recording) return null
        val ride = snapshot(atEpochMs)
        recording = false
        paused = false
        return ride.takeIf { it.hasFixes }
    }

    /** Peek at the ride as it stands, without ending it (for the live recording banner). */
    fun snapshot(atEpochMs: Long): RecordedRide = RecordedRide(
        points = points.toList(),
        startedAtMs = startedAtMs,
        endedAtMs = atEpochMs,
        distanceM = distanceM,
        movingMs = movingMs,
        maxSpeedMps = maxSpeedMps,
        rejectedFixes = rejectedFixes,
        gaps = gaps,
    )

    private fun recordSpeed(fix: RideFix) {
        val s = fix.speedMps ?: return
        if (s >= 0.0 && (maxSpeedMps == null || s > maxSpeedMps!!)) maxSpeedMps = s
    }
}
