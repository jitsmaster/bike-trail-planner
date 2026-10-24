package com.biketrailplanner.domain

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * D31, `docs/LOOPS.md`: the rider asked not to come back the same way ("the preference not to take
 * the same way back, so always a loop"), with two of their own limits - it is a PREFERENCE and never
 * a promise ("if the roads allows"), and it may cost at most 5 km of extra distance ("under 5km is
 * acceptable").
 *
 * This file is the whole decision, and it is pure: it measures how much of the way back is new,
 * places the via points that make a loop possible, and picks the winner. The planner does the
 * network work (route, trace, grade) and the screens do the talking.
 *
 * The one fact that makes it possible, measured on the real network (docs/LOOPS.md section 2): this
 * Valhalla build CANNOT exclude the outbound corridor (`exclude_polygons` caps at 100 vertices and
 * 10,000 m circumference; the corridor is 198 points and 5.9 km), but routing the return through a
 * via point placed to the SIDE of the corridor does work - measured 8%-92% overlap depending on the
 * offset. So a loop is bought with via points, never with exclusions.
 */
object LoopPolicy {

    /**
     * How far the re-used share must fall before the ride is called a loop at all.
     *
     * Without this floor, "the lowest overlap wins" would let a 90%-identical return be presented as
     * "you come back a different way" - which is exactly the claim the rider must be able to trust.
     */
    const val MIN_OVERLAP_IMPROVEMENT = 0.10

    /** Once a candidate this good turns up, stop paying for more proposals (each one is 2 calls). */
    const val GOOD_ENOUGH_OVERLAP = 0.25

    /** Local equirectangular scale, the same projection `Geo.pointToSegment` uses. */
    private const val METRES_PER_DEG_LAT = 111_320.0

    /** Metres either side of the corridor sampled by [offset], so the side is well defined. */
    private const val SIDE_SAMPLE_M = 25.0

    /** Which side of the travel direction an offset is placed on. */
    const val SIDE_LEFT = 1
    const val SIDE_RIGHT = -1

    /**
     * How much of the outbound's roads the return leg uses again (docs/LOOPS.md section 3).
     *
     * 1.0 means the same roads back; 0.0 means a completely different way. It is measured on OSM way
     * ids taken from the same `trace_attributes` call the planner already makes to grade the route,
     * so it needs no new data, and an empty outbound (nothing traced) reports 0.0 rather than a
     * meaningless 1.0.
     */
    fun overlapShare(outboundWayIds: Set<Long>, returnWayIds: Set<Long>): Double {
        if (outboundWayIds.isEmpty()) return 0.0
        val shared = outboundWayIds.count { it in returnWayIds }
        return shared.toDouble() / outboundWayIds.size
    }

    /**
     * A point placed perpendicular to the corridor, [distanceM] to one side of it.
     *
     * [a] and [b] are two points on the corridor that define the direction of travel at the point
     * being offset; [side] is [SIDE_LEFT] or [SIDE_RIGHT] relative to that direction. The offset is
     * computed in local metres and converted back to degrees at the local longitude scale, so a
     * 1336 m offset really is 1336 m on the ground.
     */
    fun offset(from: LatLon, a: LatLon, b: LatLon, distanceM: Double, side: Int): LatLon {
        val kx = METRES_PER_DEG_LAT * cos(Math.toRadians(from.lat))
        val dx = Math.toRadians(b.lon - a.lon) * kx
        val dy = Math.toRadians(b.lat - a.lat) * METRES_PER_DEG_LAT
        val len = hypot(dx, dy)
        if (len <= 1e-6) return from
        // Rotate the travel direction by 90 degrees: (-dy, dx) is one side, (dy, -dx) the other.
        val perpX = -dy / len
        val perpY = dx / len
        val dLat = perpY * side * distanceM / METRES_PER_DEG_LAT
        val dLon = perpX * side * distanceM / max(1.0, kx)
        return LatLon(from.lat + dLat, from.lon + dLon)
    }

    /** A point offset from [shape] at [fraction] along it, or null when the shape is too short. */
    fun perpendicularPoint(
        shape: List<LatLon>,
        fraction: Double,
        distanceM: Double,
        side: Int,
    ): LatLon? {
        if (shape.size < 2) return null
        val poly = Polyline(shape)
        if (poly.lengthM <= 2 * SIDE_SAMPLE_M) return null
        val arc = (poly.lengthM * fraction).coerceIn(0.0, poly.lengthM)
        val a = poly.pointAt(max(0.0, arc - SIDE_SAMPLE_M))
        val b = poly.pointAt(min(poly.lengthM, arc + SIDE_SAMPLE_M))
        return offset(poly.pointAt(arc), a, b, distanceM, side)
    }

    /** One via point the planner will try, with the words the screen and the debug line use. */
    data class ViaCandidate(
        val point: LatLon,
        val label: String,
        val offsetM: Double,
        val side: Int,
        val fraction: Double,
    )

    /**
     * The perpendicular fan: every offset in [offsetsM], on both sides, at each fraction in
     * [fractions]. Interleaved by offset (closest first) so the attempt budget is spent from the
     * middle outwards, then capped at [limit].
     */
    fun viaCandidates(
        shape: List<LatLon>,
        offsetsM: List<Double>,
        fractions: List<Double> = listOf(0.5),
        limit: Int = Int.MAX_VALUE,
    ): List<ViaCandidate> {
        val out = ArrayList<ViaCandidate>()
        for (fraction in fractions) {
            for (offsetM in offsetsM) {
                for (side in listOf(SIDE_LEFT, SIDE_RIGHT)) {
                    val p = perpendicularPoint(shape, fraction, offsetM, side) ?: continue
                    out.add(
                        ViaCandidate(
                            point = p,
                            label = fmt(offsetM, 0) + " m off the outbound line",
                            offsetM = offsetM,
                            side = side,
                            fraction = fraction,
                        ),
                    )
                }
            }
        }
        // Closest offsets first across the whole fan: the measured table says a near-complete loop
        // costs about +1 km, so the small offsets are the cheapest candidates to prove out.
        return out.sortedBy { it.offsetM }.take(limit)
    }

    /** One return leg the planner actually routed and traced. */
    data class Candidate(
        val via: ViaCandidate,
        val returnM: Double,
        val returnWayIds: Set<Long>,
        val overlapShare: Double,
        /** Metres this return costs over the direct (mirrored) return. */
        val extraM: Double,
        /** True when the WHOLE trip still has no known violation under D10. */
        val compliant: Boolean,
        /**
         * D50: how quiet the RETURN leg itself is. The outbound is fixed, so among compliant returns
         * this is the rider's preference (decision C: safety, then quietness, then a different way
         * back, then distance). Defaults to an empty score, so a caller that does not measure it keeps
         * the old overlap-first ordering exactly.
         */
        val quiet: QuietScore = QuietScore(0.0, 0.0, 0.0, 0.0, 0.0),
    )

    /**
     * The winner, or null to ride the mirrored out-and-back and say so.
     *
     * Rules, in the order docs/LOOPS.md section 4 sets them plus D50 decision C: safety first (only a
     * ride with no known violation is a candidate), then the rider's own 5 km budget, then it must
     * actually be a different way back ([MIN_OVERLAP_IMPROVEMENT]); of those survivors the QUIETEST way
     * back wins, then the most different, then the shortest extra distance, so the choice is
     * deterministic.
     */
    fun choose(
        candidates: List<Candidate>,
        baselineOverlap: Double,
        maxExtraM: Double,
        minImprovement: Double = MIN_OVERLAP_IMPROVEMENT,
    ): Candidate? = candidates
        .filter {
            it.compliant &&
                it.extraM <= maxExtraM + 1e-9 &&
                it.overlapShare <= baselineOverlap - minImprovement + 1e-9
        }
        .minWithOrNull(
            compareBy(
                // D50 decision C: safety is already filtered out; among safe loops the quietest way
                // back wins, then the one that is most different, then the cheapest.
                { -it.quiet.goodShare },
                { it.quiet.majorNoLaneM },
                { it.quiet.over25M },
                { it.overlapShare },
                { it.extraM },
            ),
        )
}
