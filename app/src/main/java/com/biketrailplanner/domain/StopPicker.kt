package com.biketrailplanner.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * D64: which points of a recorded ride are worth naming as Google Maps stops.
 *
 * The rider's instruction: *"figure out an algo to use to determine the best stop points (vertices)
 * when saving the trip ... add the points to key turns and let google map handle the more natural
 * stops automatically."*
 *
 * **Why the old rule was wrong.** `GoogleMapsLink` used to place its stops at an even stride through
 * the recorded points. That is uniform, not useful: on a 20 km ride with nine stops, most of them land
 * in the middle of a straight block - a place where naming a stop adds nothing, because Google would
 * have driven through it anyway - while the sharp turn onto the trail, and the turnaround that makes
 * the ride come back at all, are the two places worth pinning. A stop is only worth one of the nine
 * when Google would otherwise **choose differently**.
 *
 * **What a "key turn" is, concretely.** The road list ([RideRoad]) gives the junctions: every place
 * where the rider moved from one OSM way to another. At each of those we measure how far the ride
 * actually turns (the bearing change of the recorded geometry, ±[TURN_PROBE_M] either side, so a
 * single wandering fix cannot fake a corner) and add a bonus for the things that make a junction
 * matter to a rider: a change of street name, entering or leaving a separated path, and the far point
 * of the ride.
 *
 * Then the cheap part, which does the real work: **geometric dedupe**. An out-and-back passes the same
 * junction twice, and Google Maps visits its waypoints in list order. Naming the same corner twice
 * would burn two of the nine stops on one place and tell Google nothing new - so a candidate within
 * [MIN_STOP_GAP_M] of a better-scoring one is dropped, keeping the earlier visit.
 *
 * Everything here is a pure function of the ride's geometry and road list, so the algorithm is
 * unit-tested on the JVM (`StopPickerTest`) without a phone, a server or a bike.
 */
object StopPicker {

    /** Google's documented ceiling for intermediate waypoints, which is what this app has always sent. */
    const val MAX_INTERMEDIATE = 9

    /** Bearing is measured this far either side of a junction, so GPS wobble cannot fake a turn. */
    private const val TURN_PROBE_M = 12.0

    /** Two candidates closer than this are the SAME place (an out-and-back corner is visited twice). */
    const val MIN_STOP_GAP_M = 120.0

    /** Stops closer than this along the ride are one stop: nine stops in 300 m pin nothing. */
    const val MIN_STOP_ARC_M = 250.0

    /** A turn smaller than this is not a turn a stop is worth: Google will not diverge there. */
    const val MIN_TURN_DEG = 25.0

    /**
     * A junction where neither the name nor the facility changed needs a REAL corner to earn a stop.
     *
     * Measured on the 19.5 km ride of 2026-10-25: the Milton Interurban Trail's out-and-back is drawn
     * as twenty separate OSM ways, so the trace reported twenty "junctions" on it, and the trail's own
     * long curves put three of them over [MIN_TURN_DEG] - stopping the rider a second time 289 m from
     * the turnaround they had already been given. A `way_id` boundary in the middle of one trail is not
     * a junction to the person riding it; it is the same road drawn by a different mapper. A 60° bend
     * within one named road, on the other hand, is a real corner (the ride had two: Military Road East
     * at 90.6° and 28th Avenue South at 89.3°) and is kept.
     */
    const val MIN_BEND_DEG = 60.0

    private const val W_TURN = 1.0          // per degree of turn, so 90° = 90 points
    private const val W_NAME_CHANGE = 45.0  // a new street name is a real decision point
    private const val W_PATH_CHANGE = 60.0  // entering or leaving a separated path matters most
    private const val W_FAR_POINT = 140.0   // the far end of the ride: without it there is no return

    /**
     * The stops to name, in riding order.
     *
     * Returns an empty list when there is nothing to choose from - a ride with fewer than two points,
     * or one with no road list at all. The caller keeps its own fallback for that case, because a
     * recorded ride that could not be traced is still a ride the rider may want to repeat.
     */
    fun stops(
        points: List<LatLon>,
        roads: List<RideRoad>,
        maxStops: Int = MAX_INTERMEDIATE,
    ): List<RideStop> {
        if (points.size < 2 || roads.isEmpty() || maxStops <= 0) return emptyList()
        val poly = Polyline(points)
        if (poly.lengthM < MIN_STOP_ARC_M) return emptyList()

        val candidates = ArrayList<RideStop>()
        val farArc = farPointArc(poly, points)
        // The far point is only a STOP on a ride that comes back. On a one-way ride the destination
        // already is that point, and naming it twice would burn a slot on something Google has.
        val loops = Geo.distanceM(points.first(), points.last()) <= MIN_STOP_GAP_M

        roads.forEachIndexed { index, road ->
            val arc = road.startM.coerceIn(0.0, poly.lengthM)
            val previous = roads.getOrNull(index - 1)
            // The first road's start is the ride's own start: the origin covers it, and a waypoint on
            // top of the origin is a stop Google rejects.
            if (previous == null) return@forEachIndexed

            val turn = turnAt(poly, arc)
            val nameChanged = road.isNamed && road.name != previous.name
            val pathChanged = road.isSeparatedPath != previous.isSeparatedPath
            val isFar = loops && abs(arc - farArc) <= MIN_STOP_ARC_M

            // How near the ride's own ends this is. The origin and the destination already pin these,
            // so a waypoint here buys nothing: on the ride of 2026-10-25 the highest-scoring candidate
            // was 60 m from the front door, where the driveway becomes 18th Street East - one of the
            // nine slots spent inside sight of the start.
            val nearEnd = arc < MIN_STOP_ARC_M || arc > poly.lengthM - MIN_STOP_ARC_M
            if (nearEnd && !isFar) return@forEachIndexed

            // Nothing changed here: it is one road drawn as two OSM ways, and the geometry's own curve
            // is the road, not a decision. Such a seam must clear a real corner (see [MIN_BEND_DEG]).
            val changed = nameChanged || pathChanged
            if (!changed && !isFar && turn < MIN_BEND_DEG) return@forEachIndexed

            // Straight on, same road, no path change: nothing for Google to get wrong here.
            if (turn < MIN_TURN_DEG && !pathChanged && !nameChanged && !isFar) return@forEachIndexed

            var score = turn * W_TURN
            if (nameChanged) score += W_NAME_CHANGE
            if (pathChanged) score += W_PATH_CHANGE
            if (isFar) score += W_FAR_POINT

            val why = buildString {
                when {
                    isFar -> append("the far point of the ride")
                    pathChanged && road.isSeparatedPath -> append("onto ").append(label(road))
                    pathChanged -> append("off ").append(label(previous))
                    // A rename on a straight road is a pin, not a turn: saying "turn onto 18th Street
                    // East (0°)" would be the screen telling the rider something the ride did not do.
                    nameChanged && turn < MIN_TURN_DEG -> append("where the road becomes ").append(road.name)
                    nameChanged -> append("turn onto ").append(road.name)
                    else -> append("turn on ").append(label(road))
                }
                if (!isFar) append(" (").append(turn.toInt()).append("\u00b0)")
            }
            candidates += RideStop(
                point = poly.pointAt(arc),
                atM = arc,
                turnDeg = turn,
                why = why,
                priority = if (isFar) 1 else 2,
            )
        }

        // The far point is the one stop that MUST survive the cap on a loop: without it, Google is
        // asked for a round trip that never turns around.
        val far = candidates.filter { it.priority == 1 }.maxByOrNull { it.atM }
        if (far == null && loops) {
            candidates += RideStop(
                point = poly.pointAt(farArc),
                atM = farArc,
                turnDeg = 0.0,
                why = "the far point of the ride",
                priority = 1,
            )
        }

        return select(candidates, maxStops)
    }

    /**
     * Rank, dedupe and cap. The far point is placed first, then the best-scoring junctions, and any
     * candidate within [MIN_STOP_GAP_M] of one already chosen is dropped as the same place.
     *
     * **A ride has exactly one far point.** `isFar` marks every candidate within [MIN_STOP_ARC_M] of
     * it, which on the 2026-10-25 ride was two stops 287 m apart - just over [MIN_STOP_ARC_M], so the
     * geometric dedupe let both through and the URL asked Google for the turnaround twice. The second
     * far-point candidate is now dropped as the same place, whatever its distance.
     */
    private fun select(candidates: List<RideStop>, maxStops: Int): List<RideStop> {
        val chosen = ArrayList<RideStop>()
        val ordered = candidates.sortedWith(
            compareBy<RideStop> { it.priority }.thenByDescending { it.turnDeg }.thenBy { it.atM },
        )
        for (candidate in ordered) {
            if (chosen.size >= maxStops) break
            val sameFarPoint = candidate.priority == 1 && chosen.any { it.priority == 1 }
            if (sameFarPoint) continue
            val tooClose = chosen.any { other ->
                Geo.distanceM(other.point, candidate.point) < MIN_STOP_GAP_M ||
                    abs(other.atM - candidate.atM) < MIN_STOP_ARC_M
            }
            if (tooClose) continue
            chosen += candidate
        }
        // Google visits waypoints in list order, so the answer is in riding order - not score order.
        return chosen.sortedBy { it.atM }
    }

    /**
     * The point of the ride furthest from where it started, by straight-line distance.
     *
     * Straight-line rather than arc length on purpose: on a loop, the midpoint of the distance ridden
     * can be 200 m from home, and naming it as "the turnaround" would be a lie about the ride.
     */
    fun farPointArc(poly: Polyline, points: List<LatLon>): Double {
        val start = points.first()
        var bestArc = 0.0
        var best = -1.0
        for (i in points.indices) {
            val d = Geo.distanceM(start, points[i])
            if (d > best) {
                best = d
                bestArc = poly.cumulative[i]
            }
        }
        return bestArc
    }

    /**
     * How far the ride turns at [arcM], in degrees, 0..180.
     *
     * The bearing is taken over a real distance either side (not over one fix), so a 1 m wobble at a
     * traffic light does not read as a 90° corner.
     */
    fun turnAt(poly: Polyline, arcM: Double): Double {
        val before = bearingAt(poly, (arcM - TURN_PROBE_M).coerceAtLeast(0.0), (arcM - TURN_PROBE_M / 2).coerceAtLeast(0.0))
        val after = bearingAt(poly, (arcM + TURN_PROBE_M / 2).coerceAtMost(poly.lengthM), (arcM + TURN_PROBE_M).coerceAtMost(poly.lengthM))
        if (before == null || after == null) return 0.0
        var delta = abs(after - before) % 360.0
        if (delta > 180.0) delta = 360.0 - delta
        return delta
    }

    /** Compass bearing from one arc position to another, or null when the two collapse to a point. */
    private fun bearingAt(poly: Polyline, fromM: Double, toM: Double): Double? {
        val a = poly.pointAt(fromM.coerceIn(0.0, poly.lengthM))
        val b = poly.pointAt(toM.coerceIn(0.0, poly.lengthM))
        if (Geo.distanceM(a, b) < 1.0) return null
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    private fun label(road: RideRoad): String =
        if (road.isNamed) road.name else if (road.isSeparatedPath) "the separated path" else "the road"
}
