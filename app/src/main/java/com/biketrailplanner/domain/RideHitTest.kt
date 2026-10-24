package com.biketrailplanner.domain

/**
 * D60 (docs/UI.md section 3.6): what the rider tapped on a recorded ride.
 *
 * A recorded ride is a polyline of GPS points with the streets Valhalla matched to it. The rider asked
 * to tap the map and be told what is there: a **point** ("node") or a **stretch of street** ("edge").
 * Those are exactly the two things the recorder already knows - the fixes it kept, and the graded
 * segments taken from the trace - so nothing new is stored and nothing new is fetched to answer it.
 */
sealed interface RideHit {
    /** Where on the ride this is. */
    val at: LatLon

    /** How far the tap landed from it, so the UI can say "about 12 m away" rather than pretend. */
    val distanceFromTapM: Double

    /**
     * One recorded GPS point. [street] is the street the app matched at this point, when it matched
     * one - a point the trace could not place has no street, and the UI must not invent one.
     */
    data class Node(
        val index: Int,
        override val at: LatLon,
        val riddenM: Double,
        val street: String?,
        override val distanceFromTapM: Double,
    ) : RideHit

    /** A stretch of one street: the unit the safety ladder actually graded. */
    data class Edge(
        val name: String,
        val grade: Grade,
        val lengthM: Double,
        val speedText: String,
        override val at: LatLon,
        override val distanceFromTapM: Double,
    ) : RideHit
}

/**
 * The hit test. Pure, so the rule that decides "a node or a street?" is unit-tested rather than
 * discovered by tapping a phone at arm's length.
 */
object RideHitTest {

    /**
     * Within this of a recorded point, the tap means that point. Beyond it, the tap means the street it
     * is on. A recorded point is a thing the rider can see; a street is a thing they are on.
     */
    const val NODE_RADIUS_M = 25.0

    /**
     * How far off the ride a tap may land and still count. Bigger than the node radius so a tap on the
     * road beside the line still answers, and small enough that a tap on a different street does not.
     */
    const val EDGE_TOLERANCE_M = 70.0

    /**
     * What [tap] landed on, or null when it landed nowhere near the ride.
     *
     * The street is found from the ride's own graded segments, using the tap's position ALONG the ride:
     * each segment's share of the traced length picks the bucket. That is an approximation - the traced
     * edge lengths and the GPS length are two different measurements of the same ride - so it answers
     * "which street is this", which is what a tap is asking, and not "the exact metre a street starts".
     */
    fun at(ride: RideTrack, tap: LatLon, allowEdge: Boolean = true): RideHit? {
        val points = ride.points
        if (points.isEmpty()) return null
        if (points.size == 1) {
            val only = points.first()
            val distance = Geo.distanceM(only, tap)
            return if (distance <= NODE_RADIUS_M) {
                RideHit.Node(0, only, 0.0, streetAt(ride, 0.0), distance)
            } else {
                null
            }
        }

        val poly = Polyline(points)
        val projection = poly.project(tap)
        val before = points[projection.segmentIndex]
        val after = points.getOrNull(projection.segmentIndex + 1)
        val nearestIndex = if (after != null && Geo.distanceM(after, tap) < Geo.distanceM(before, tap)) {
            projection.segmentIndex + 1
        } else {
            projection.segmentIndex
        }
        val nearest = points[nearestIndex]
        val nearestM = Geo.distanceM(nearest, tap)

        val fraction = if (poly.lengthM > 0.0) (projection.arcM / poly.lengthM).coerceIn(0.0, 1.0) else 0.0

        if (nearestM <= NODE_RADIUS_M) {
            val riddenM = poly.cumulative.getOrElse(nearestIndex) { 0.0 }
            return RideHit.Node(nearestIndex, nearest, riddenM, streetAt(ride, fraction), nearestM)
        }
        if (!allowEdge || projection.offsetM > EDGE_TOLERANCE_M) return null

        val segment = segmentAt(ride, fraction) ?: return null
        return RideHit.Edge(
            name = segment.name,
            grade = segment.grade,
            lengthM = segment.metres,
            speedText = segment.speedText,
            at = projection.point,
            distanceFromTapM = projection.offsetM,
        )
    }

    /**
     * The street covering [fraction] of the ride, walking the graded segments in the order the trace
     * returned them. Null when the ride has no named segments at all (it could not be traced).
     */
    fun segmentAt(ride: RideTrack, fraction: Double): NamedSegment? {
        if (ride.named.isEmpty()) return null
        val total = ride.named.sumOf { it.metres }
        if (total <= 0.0) return ride.named.first()
        var walked = 0.0
        val wanted = fraction.coerceIn(0.0, 1.0) * total
        for (segment in ride.named) {
            walked += segment.metres
            if (wanted <= walked) return segment
        }
        return ride.named.last()
    }

    /** The street name at [fraction], or null when the app matched no street there. */
    fun streetAt(ride: RideTrack, fraction: Double): String? =
        segmentAt(ride, fraction)?.name?.takeIf { !it.startsWith("(") }
}
