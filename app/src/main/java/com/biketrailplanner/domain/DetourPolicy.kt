package com.biketrailplanner.domain

/**
 * D13 (SPEC section 7c): when the direct route breaks D10, look for a compliant alternative
 * before refusing.
 *
 * - A compliant candidate is acceptable up to [acceptFactor] (1.40x) the shortest route.
 * - Search in bands (+0%, +25%, +40%); if nothing compliant exists, one up to [maxFactor]
 *   (1.80x) may still be offered, but explicitly: the rider sees the extra distance and time
 *   and has to accept it. A detour is never taken silently.
 * - A compliant detour that leaves less than [TRAIL_FLOOR_MILES] of trail is not delivered as a
 *   token ride; the tradeoff is reported instead.
 */
object DetourPolicy {

    const val ACCEPT_FACTOR = 1.40
    const val MAX_FACTOR = 1.80
    const val TRAIL_FLOOR_MILES = 2.0

    /**
     * The wider budget used by ONE deliberate re-plan when the rider taps "Try a safer detour"
     * (docs/UI.md section 4). At this point the ordinary search has already failed on screen, so the
     * only honest thing the button can do is search further out and then say what it found - the
     * shipped 1.80x band is the automatic limit, not the limit of what the rider may ask for.
     */
    const val SEARCH_HARDER_FACTOR = 2.50

    /**
     * The max factor for a "search harder" attempt: [SEARCH_HARDER_FACTOR], never *less* than the
     * configured one, so a config already more generous than the constant is not narrowed by a tap.
     */
    fun harderSearchFactor(configured: Double): Double =
        if (configured > SEARCH_HARDER_FACTOR) configured else SEARCH_HARDER_FACTOR

    val BANDS: List<Double> = listOf(0.0, 0.25, 0.40, 0.80)

    data class Verdict(
        val compliant: Boolean,
        val factor: Double,
        /** Acceptable without any special warning. */
        val acceptable: Boolean,
        /** Compliant only as an explicit detour: the rider must accept the extra distance. */
        val explicitDetour: Boolean,
        /** The band whose allowance this candidate fits, when it is accepted. */
        val band: Double?,
        val reason: String,
    )

    /**
     * @param totalM the candidate's own total round-trip distance
     * @param shortestTotalM the shortest total seen across all candidates
     */
    fun evaluate(
        totalM: Double,
        shortestTotalM: Double,
        compliant: Boolean,
        acceptFactor: Double = ACCEPT_FACTOR,
        maxFactor: Double = MAX_FACTOR,
        bands: List<Double> = BANDS,
    ): Verdict {
        val factor = if (shortestTotalM <= 0.0) 1.0 else totalM / shortestTotalM
        val band = bands.firstOrNull { factor <= 1.0 + it + 1e-9 }
        if (!compliant) {
            return Verdict(false, factor, acceptable = false, explicitDetour = false, band = null, reason = "breaks D10")
        }
        return when {
            factor <= acceptFactor + 1e-9 -> Verdict(
                compliant = true, factor = factor, acceptable = true, explicitDetour = false, band = band,
                reason = if (factor > 1.0 + 1e-9) {
                    "compliant, " + fmt((factor - 1.0) * 100, 0) + "% longer than the shortest route"
                } else {
                    "compliant and no longer than the shortest route"
                },
            )
            factor <= maxFactor + 1e-9 -> Verdict(
                compliant = true, factor = factor, acceptable = false, explicitDetour = true, band = band,
                reason = "compliant only as an explicit detour: " + fmt((factor - 1.0) * 100, 0) +
                    "% longer than the shortest route (limit " + fmt((acceptFactor - 1.0) * 100, 0) + "%)",
            )
            else -> Verdict(
                compliant = true, factor = factor, acceptable = false, explicitDetour = false, band = null,
                reason = "compliant but " + fmt((factor - 1.0) * 100, 0) + "% longer than the shortest route, past the " +
                    fmt((maxFactor - 1.0) * 100, 0) + "% detour limit",
            )
        }
    }

    /** D2/D13 rule 3: below this much trail, the detour is not worth delivering. */
    fun trailFloorM() = Geo.milesToMetres(TRAIL_FLOOR_MILES)

    fun belowTrailFloor(trailRoundTripM: Double, floorMiles: Double = TRAIL_FLOOR_MILES): Boolean =
        trailRoundTripM < Geo.milesToMetres(floorMiles) - 1e-9
}
