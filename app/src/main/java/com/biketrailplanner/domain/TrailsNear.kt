package com.biketrailplanner.domain

/**
 * One trail the app can reach from where the rider is standing.
 *
 * The two numbers a rider chooses with are how much trail there is ([trailMiles]) and how far away it
 * starts ([distanceMiles]), so both are carried here. Neither is guessed: the length is the rideable
 * metres the trail data already holds, and the distance is measured to the NEAREST VERTEX of the
 * trail's own geometry - the same measure the planner uses to decide what is within range.
 */
data class TrailNear(
    val name: String,
    /** Rideable trail metres, i.e. the trail minus any closed span. */
    val rideableM: Double,
    /** Straight-line metres from the origin to the nearest point of the trail. */
    val distanceM: Double,
    /** False for a live-discovered trail: no closure check and no surface audit (D23). */
    val verified: Boolean = true,
) {
    val trailMiles: Double get() = Geo.metresToMiles(rideableM)
    val distanceMiles: Double get() = Geo.metresToMiles(distanceM)
}

/**
 * The trails near a rider, **closest first**.
 *
 * The ordering is the whole point: a rider picks the trail they can actually get to, so the list is
 * sorted by distance alone. Two trails equally close are ordered by name, so the list is stable
 * between two identical fixes instead of shuffling under the rider's finger.
 *
 * Three rules, all of them honesty rules:
 *  - the distance is measured to the nearest point of the trail, never to the first node of the
 *    relation, so a trail that runs past the rider reads as close rather than far;
 *  - the same trail can arrive from the bundled set, the regional bundle AND live discovery under one
 *    name, and the rider must not see it twice - the closest copy wins;
 *  - [limit] is a real cap. Past it the sheet stops being a choice and becomes a browse, and the
 *    trails that fall off the end are the furthest ones, which is the right thing to drop.
 *
 * Pure geometry and no Android types, so it is unit-tested directly (TrailsNearTest).
 */
object TrailsNear {

    /** How many trails the sheet lists. */
    const val DEFAULT_LIMIT = 8

    fun closest(trails: List<Trail>, origin: LatLon, limit: Int = DEFAULT_LIMIT): List<TrailNear> {
        if (limit <= 0) return emptyList()
        val best = LinkedHashMap<String, TrailNear>()
        for (trail in trails) {
            // Polyline requires two points, so a trail always has geometry to measure against: there
            // is no "unknown distance" case to report here.
            val distanceM = trail.fullGeometry.points.minOf { Geo.distanceM(it, origin) }
            val candidate = TrailNear(
                name = trail.name,
                rideableM = trail.rideableM,
                distanceM = distanceM,
                verified = trail.verified,
            )
            val previous = best[candidate.name]
            if (previous == null || candidate.distanceM < previous.distanceM) best[candidate.name] = candidate
        }
        return best.values
            .sortedWith(compareBy({ it.distanceM }, { it.name }))
            .take(limit)
    }
}
