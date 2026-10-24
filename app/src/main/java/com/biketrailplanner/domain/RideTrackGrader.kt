package com.biketrailplanner.domain

import com.biketrailplanner.data.TraceResult

/**
 * D51: turn a recorded ride plus its Valhalla trace into a graded [RideTrack].
 *
 * It reuses the same edge-to-point mapping the planner uses (`Planner.gradeEdges`, minus the
 * observed-way bookkeeping the planner needs), so a recorded ride is graded by exactly the same
 * ladder as a planned one. It never claims safety: an unposted road is still UNGRADED, and a ride
 * that could not be traced at all is stored ungraded with the reason.
 */
object RideTrackGrader {

    /** Under this many metres, a shortfall is measurement noise between GPS and OSM, not a real gap. */
    private const val UNMATCHED_FLOOR_M = 1.0

    fun grade(
        ride: RecordedRide,
        trace: TraceResult?,
        grader: SafetyGrader,
        fingerprint: String,
        currentFingerprint: String? = null,
        label: String? = null,
        nowMs: Long,
    ): RideTrack {
        val segments = if (trace == null || !trace.ok || ride.points.size < 2) {
            listOf(ungradedAll(ride))
        } else {
            val graded = mapEdges(trace, ride.points, grader)
            // A recorded track only partly matches the road network (a GPS trace cuts corners, crosses
            // a yard, runs along a path the router does not know). The part that did not match was never
            // assessed, so it is counted as UNGRADED rather than quietly dropped: on a real device a
            // 1.66 km ride whose trace matched 843 m was reporting itself as "73% measured" when only
            // 37% of what the rider rode had actually been looked at (docs/RIDE-RECORDER.md section 7).
            val matchedM = graded.sumOf { it.lengthM }
            val unmatchedM = ride.distanceM - matchedM
            if (unmatchedM > UNMATCHED_FLOOR_M) graded + unmatchedRemainder(unmatchedM) else graded
        }
        val breakdown = grader.breakdown(segments)
        val named = grader.namedSegments(segments)
        return RideTrack(
            signature = RideTrackSignature.of(ride.points, ride.startedAtMs),
            startedAtMs = ride.startedAtMs,
            endedAtMs = ride.endedAtMs,
            distanceM = ride.distanceM,
            movingMs = ride.movingMs,
            maxSpeedMps = ride.maxSpeedMps,
            gaps = ride.gaps,
            rejectedFixes = ride.rejectedFixes,
            points = ride.points,
            wayIds = segments.flatMap { it.wayIds }.filter { it > 0L }.toSet(),
            named = named,
            gradedM = breakdown.gradedM,
            ungradedM = breakdown.ungradedM,
            rejectedM = breakdown.rejectedM,
            breakdownTotalM = breakdown.totalM,
            byGrade = breakdown.byGrade,
            safetyStale = currentFingerprint != null && currentFingerprint != fingerprint,
            dataFingerprint = fingerprint,
            label = label ?: defaultLabel(named),
            createdAt = nowMs,
        )
    }

    private fun mapEdges(trace: TraceResult, points: List<LatLon>, grader: SafetyGrader): List<GradedSegment> {
        if (points.size < 2) return trace.edges.map { grader.gradeEdge(it) }
        val poly = Polyline(points)
        val edgesTotal = trace.edges.sumOf { it.lengthM }
        val scale = if (edgesTotal > 0.0) poly.lengthM / edgesTotal else 1.0
        var cursor = 0.0
        return trace.edges.map { edge ->
            val mid = ((cursor + edge.lengthM / 2.0) * scale).coerceIn(0.0, poly.lengthM)
            cursor += edge.lengthM
            grader.gradeEdge(edge, poly.pointAt(mid))
        }
    }

    private fun ungradedAll(ride: RecordedRide): GradedSegment = GradedSegment(
        grade = Grade.UNGRADED,
        lengthM = ride.distanceM,
        names = listOf("(recorded ride)"),
        wayIds = emptyList(),
        roadClass = null,
        use = null,
        speedMph = null,
        speedSource = SpeedSource.NONE,
        reason = "the recording could not be traced, so it cannot be graded",
    )

    /**
     * The metres of the ride the trace did not match to any road. Kept as an explicit UNGRADED segment
     * so the breakdown adds up to the distance the rider actually rode, and the measured share is the
     * share of the RIDE that was assessed - not the share of the roads we happened to match.
     */
    private fun unmatchedRemainder(metres: Double): GradedSegment = GradedSegment(
        grade = Grade.UNGRADED,
        lengthM = metres,
        names = listOf("(not matched to a road)"),
        wayIds = emptyList(),
        roadClass = null,
        use = null,
        speedMph = null,
        speedSource = SpeedSource.NONE,
        reason = "this part of the track did not match a road, so it was not assessed",
    )

    /** The first couple of street names the rider would recognise, or a plain fallback. */
    fun defaultLabel(named: List<NamedSegment>): String {
        val names = named.map { it.name }
            .filter { it.isNotBlank() && !it.startsWith("(") }
            .distinct()
        return if (names.isNotEmpty()) names.take(2).joinToString(" + ") else "Recorded ride"
    }
}
