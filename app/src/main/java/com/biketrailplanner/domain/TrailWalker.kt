package com.biketrailplanner.domain

/**
 * Rides a trail (and, where the trail runs out, the trails joined to it with a 0 m gap) for a
 * given one-way distance, then reports where to turn around (D3/D9).
 *
 * Every trail in the dataset is linear, so the shape is out-and-back: the rider goes [limitM]
 * metres one way and comes back the same way. Chaining happens when the walk reaches the end of
 * a trail and a documented join leads onto another trail.
 *
 * SPEC section 7b: a closed segment is not the end of the ride. The walk stops at the closure and
 * returns an [OpenGap]; the planner routes that gap on the road network, grades those roads, and
 * calls [walk] again from the far side. A closed middle therefore turns one trail into two stubs
 * joined by a measured road detour, which is what the rider actually has to do.
 */
class TrailWalker(private val network: TrailNetwork) {

    /** A closure the rider has to get around on roads. */
    data class OpenGap(
        val from: LatLon,
        val to: LatLon,
        val closure: TrailClosure,
        val resumeTrail: String,
        val resumeAt: LatLon,
        val note: String,
    )

    data class TrailWalk(
        val legs: List<TrailRideLeg>,
        /** Trail metres actually ridden, along the geometry the plan uses. */
        val trailMetres: Double,
        val turnaround: LatLon,
        val exhausted: Boolean,
        val notes: List<String>,
        val openGap: OpenGap? = null,
    ) {
        val trailNames: List<String> get() = legs.map { it.trailName }.distinct()

        /** Kept for callers that only want the distance ridden one way. */
        val oneWayM: Double get() = trailMetres
    }

    /**
     * @param preferDirection +1/-1 forces the initial riding direction. Null means "ride the
     *   longer side first". The planner tries both, because the longer side first can dead-end at
     *   a trail's far end and miss a closure detour that the other direction reaches.
     */
    fun walk(entryTrail: Trail, entry: LatLon, limitM: Double, preferDirection: Int? = null): TrailWalk {
        val notes = ArrayList<String>()
        val legs = ArrayList<TrailRideLeg>()

        var trail = entryTrail
        var spanIndex = spanContaining(entryTrail, entry)
        var span = entryTrail.rideableSpans[spanIndex]
        var arc = span.project(entry).arcM
        var dir = preferDirection ?: if (span.lengthM - arc >= arc) 1 else -1
        var remaining = limitM
        var hops = 0

        // D3: a trail that loops is ridden once around, not out and back.
        if (trail.loop && span.lengthM >= limitM - 1.0) {
            val points = span.subLine(arc, arc + span.lengthM) + span.subLine(0.0, arc).drop(1)
            legs.add(TrailRideLeg(trail.name, arc, arc, points))
            notes.add("${trail.name} is a loop: the ride goes once around it.")
            return TrailWalk(legs, Polyline(points).lengthM, points.last(), true, notes)
        }

        while (remaining > 1.0 && hops <= MAX_HOPS) {
            // A documented join can sit in the middle of a span (Lake Tapps joins Sumner Link
            // mid-line at OSM node 2670828792), so look for the next join ahead, not only at ends.
            val joinAhead = nextJoinAhead(trail, span, arc, dir)
            val available = if (dir > 0) span.lengthM - arc else arc
            val toJoin = joinAhead?.distanceM ?: Double.MAX_VALUE
            val take = minOf(available, remaining, toJoin)
            if (take > 1.0) {
                val from = arc
                val to = arc + dir * take
                legs.add(TrailRideLeg(trail.name, from, to, span.subLine(from, to)))
            }
            remaining -= take
            if (remaining <= 1.0) break

            val reached = arc + dir * take
            val reachedPoint = span.pointAt(reached)
            arc = reached
            val atSpanEnd = reached <= 0.5 || reached >= span.lengthM - 0.5

            if (!atSpanEnd && joinAhead != null && kotlin.math.abs(reached - joinAhead.arcM) < 2.0) {
                val next = network.trail(joinAhead.otherTrail)
                if (next != null) {
                    val (nextSpanIndex, nextArc) = containingSpan(next, reachedPoint)
                    trail = next
                    spanIndex = nextSpanIndex
                    span = next.rideableSpans[nextSpanIndex]
                    arc = nextArc
                    dir = if (span.lengthM - arc >= arc) 1 else -1
                    hops++
                    notes.add(
                        "chained onto ${next.name} at ${Signature.round(reachedPoint.lat, reachedPoint.lon)} " +
                            "(0 m gap, ${joinAhead.join.kind}).",
                    )
                    continue
                }
            }

            val closure = closureAt(trail, reachedPoint) ?: closureBlockingSpanEnd(trail, span, reachedPoint)
            if (closure != null) {
                val ridden = limitM - remaining
                val farSide = farSideOf(trail, closure, reachedPoint)
                if (farSide != null) {
                    notes.add(gapNote(trail, closure, farSide.first))
                    return TrailWalk(
                        legs = legs,
                        trailMetres = ridden,
                        turnaround = lastPointOr(legs, entry),
                        exhausted = false,
                        notes = notes,
                        openGap = OpenGap(
                            from = lastPointOr(legs, entry),
                            to = farSide.second,
                            closure = closure,
                            resumeTrail = trail.name,
                            resumeAt = farSide.second,
                            note = gapNote(trail, closure, farSide.first),
                        ),
                    )
                }
                notes.add(
                    "the corridor is severed here and there is no far side to reach: ${trail.name} way " +
                        "${closure.osmWayId} (${fmt(closure.wayLengthM / 1000, 2)} km) is closed.",
                )
                return TrailWalk(legs, ridden, lastPointOr(legs, entry), true, notes)
            }

            val join = network.neighbours(trail.name)
                .filter { it.gapM <= 1.0 }
                .firstOrNull { Geo.distanceM(it.point, reachedPoint) <= JOIN_TOLERANCE_M }
            val nextName = join?.let { if (it.trailA == trail.name) it.trailB else it.trailA }
            val next = nextName?.let { network.trail(it) }
            if (join != null && next != null && next.name != trail.name) {
                val (nextSpanIndex, nextArc) = containingSpan(next, join.point)
                trail = next
                spanIndex = nextSpanIndex
                span = next.rideableSpans[nextSpanIndex]
                arc = nextArc
                dir = if (span.lengthM - arc >= arc) 1 else -1
                hops++
                notes.add("chained onto ${next.name} at ${Signature.round(join.point.lat, join.point.lon)} (0 m gap, ${join.kind}).")
            } else {
                val why = if (trail.hasClosure) {
                    "the ride stops at the mapped end of ${trail.name} nearest that closure"
                } else {
                    "${trail.name} ends here and no documented link continues the corridor"
                }
                notes.add("corridor exhausted: $why.")
                return TrailWalk(legs, limitM - remaining, lastPointOr(legs, entry), true, notes)
            }
        }

        val ridden = limitM - remaining
        if (hops > MAX_HOPS) notes.add("chaining stopped after $MAX_HOPS trail joins")
        return TrailWalk(legs, ridden, lastPointOr(legs, entry), exhausted = false, notes = notes)
    }

    private data class JoinAhead(val join: TrailJoin, val otherTrail: String, val arcM: Double, val distanceM: Double)

    /** The next documented join along the direction of travel, in or at the end of this span. */
    private fun nextJoinAhead(trail: Trail, span: Polyline, arc: Double, dir: Int): JoinAhead? =
        network.neighbours(trail.name)
            .filter { it.gapM <= 1.0 }
            .mapNotNull { join ->
                val other = if (join.trailA == trail.name) join.trailB else join.trailA
                if (other == trail.name) return@mapNotNull null
                val projection = span.project(join.point)
                if (projection.offsetM > JOIN_ON_SPAN_TOLERANCE_M) return@mapNotNull null
                val ahead = if (dir > 0) projection.arcM > arc + 1.0 else projection.arcM < arc - 1.0
                if (!ahead) null else JoinAhead(join, other, projection.arcM, kotlin.math.abs(projection.arcM - arc))
            }
            .minByOrNull { it.distanceM }

    /** The other side of a closure: the nearest rideable span on the far end. */
    private fun farSideOf(trail: Trail, closure: TrailClosure, stoppedAt: LatLon): Pair<Double, LatLon>? {
        val farEnd = if (Geo.distanceM(stoppedAt, closure.northEnd) <= Geo.distanceM(stoppedAt, closure.southEnd)) {
            closure.southEnd
        } else {
            closure.northEnd
        }
        val span = trail.rideableSpans.minByOrNull { it.project(farEnd).offsetM } ?: return null
        val offset = span.project(farEnd).offsetM
        if (offset > FAR_SIDE_TOLERANCE_M) return null
        return offset to span.project(farEnd).point
    }

    private fun gapNote(trail: Trail, closure: TrailClosure, farOffsetM: Double): String = buildString {
        append("closed segment: ${trail.name} way ${closure.osmWayId} ")
        append("(${fmt(closure.wayLengthM / 1000, 2)} km) is pedestrian- and bicycle-inaccessible in OSM")
        closure.closedSince?.let { append(", closed since $it") }
        closure.authority?.let { append(" ($it)") }
        append(". The plan routes around it on roads and grades those roads.")
    }

    private fun lastPointOr(legs: List<TrailRideLeg>, fallback: LatLon): LatLon =
        legs.lastOrNull()?.points?.lastOrNull() ?: fallback

    /**
     * A rideable span exists only because a closure cuts the trail, so reaching the interior
     * end of a span (rather than one of the trail's own ends) means a closure stopped the ride.
     * This does not depend on the shipped geometry matching the closure node exactly.
     */
    private fun closureBlockingSpanEnd(trail: Trail, span: Polyline, endPoint: LatLon): TrailClosure? {
        if (!trail.hasClosure) return null
        val geometry = trail.fullGeometry
        val atOwnEnd = Geo.distanceM(endPoint, geometry.points.first()) < GEOMETRY_END_TOLERANCE_M ||
            Geo.distanceM(endPoint, geometry.points.last()) < GEOMETRY_END_TOLERANCE_M
        if (atOwnEnd) return null
        return if (span.lengthM < geometry.lengthM - 1.0) trail.closures.first() else null
    }

    private fun closureAt(trail: Trail, point: LatLon): TrailClosure? =
        trail.closures.firstOrNull {
            Geo.distanceM(point, it.northEnd) <= CLOSURE_TOLERANCE_M ||
                Geo.distanceM(point, it.southEnd) <= CLOSURE_TOLERANCE_M
        }

    private fun spanContaining(trail: Trail, p: LatLon): Int {
        var best = 0
        var bestOffset = Double.MAX_VALUE
        trail.rideableSpans.forEachIndexed { i, span ->
            val o = span.project(p).offsetM
            if (o < bestOffset) {
                bestOffset = o
                best = i
            }
        }
        return best
    }

    private fun containingSpan(trail: Trail, p: LatLon): Pair<Int, Double> {
        val i = spanContaining(trail, p)
        return i to trail.rideableSpans[i].project(p).arcM
    }

    companion object {
        const val MAX_HOPS = 8
        const val JOIN_TOLERANCE_M = 30.0
        const val CLOSURE_TOLERANCE_M = 40.0
        const val GEOMETRY_END_TOLERANCE_M = 5.0
        const val FAR_SIDE_TOLERANCE_M = 60.0
        const val JOIN_ON_SPAN_TOLERANCE_M = 25.0
    }
}
