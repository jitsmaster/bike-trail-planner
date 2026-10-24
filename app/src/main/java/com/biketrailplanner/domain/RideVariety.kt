package com.biketrailplanner.domain

import kotlin.math.abs

/**
 * D6: show up to five options (the rider's number, 2026-09-16), and their rule that a new ride must not
 * be the same trip again.
 *
 * Measured problem (2026-09-16): the planner's option list is built from the raw candidate set, and
 * adjacent entrances on one trail are separate candidates. From home, all three offered options were
 * Interurban Trail Milton entering at entrances ~50 m apart, with the same 3.35 mi of trail and the
 * same 10.00 mi total - *the same ride three times*. The rotation then "varied" between them, which
 * is variety on paper and repetition on the road.
 *
 * A ride is treated as the SAME ride when all three of these hold:
 *  - the same trail or trails, in the same order;
 *  - the trail portion is the same length (to within [TRAIL_MILES_TOLERANCE]);
 *  - the entrance is the same place (within [ENTRY_TOLERANCE_M]).
 *
 * Anything else is a genuinely different ride and is kept, so a real alternative is never hidden.
 */
object RideVariety {

    /** Entrances this close are the same place to start from: ~110 m is the GPS-jitter scale. */
    const val ENTRY_TOLERANCE_M = 110.0

    /** Trail portions this close are the same ride along the trail. */
    const val TRAIL_MILES_TOLERANCE = 0.1

    /** True when [a] and [b] would be the same ride on the road. */
    fun sameRide(a: PlannedRoute, b: PlannedRoute): Boolean =
        a.trailNames == b.trailNames &&
            abs(Geo.metresToMiles(a.trailRoundTripM) - Geo.metresToMiles(b.trailRoundTripM)) < TRAIL_MILES_TOLERANCE &&
            Geo.distanceM(a.accessPoint.point, b.accessPoint.point) <= ENTRY_TOLERANCE_M

    /**
     * Keeps the first of each group of equivalent rides, in the order given, so a caller that has
     * already ranked its candidates (by rotation, by novelty, by D13 band) keeps that ranking.
     */
    fun distinct(routes: List<PlannedRoute>): List<PlannedRoute> {
        val kept = ArrayList<PlannedRoute>(routes.size)
        for (route in routes) {
            if (kept.none { sameRide(it, route) }) kept.add(route)
        }
        return kept
    }

    /** The rides that are NOT already represented in [kept], in order. */
    fun newRides(routes: List<PlannedRoute>, kept: List<PlannedRoute>): List<PlannedRoute> =
        routes.filter { route -> kept.none { sameRide(it, route) } }
}
