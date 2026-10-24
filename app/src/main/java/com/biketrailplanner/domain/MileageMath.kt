package com.biketrailplanner.domain

/**
 * D2: the mileage the user types is TOTAL ROUND-TRIP MILES.
 * Trail portion = target - round-trip commute. Everything here is metres internally.
 */
object MileageMath {

    data class TrailPortion(
        val roundTripM: Double,
        val oneWayM: Double,
        val feasible: Boolean,
        val reason: String?,
    ) {
        val roundTripMiles: Double get() = Geo.metresToMiles(roundTripM)
        val oneWayMiles: Double get() = Geo.metresToMiles(oneWayM)
    }

    /** The trail mileage that is left after the round-trip commute is subtracted. */
    fun trailPortion(targetMiles: Double, commuteOneWayM: Double, minTrailMiles: Double = 0.25): TrailPortion {
        val targetM = Geo.milesToMetres(targetMiles)
        val commuteRoundTripM = 2 * commuteOneWayM
        val roundTripM = targetM - commuteRoundTripM
        val minM = Geo.milesToMetres(minTrailMiles)
        if (roundTripM < minM) {
            return TrailPortion(
                roundTripM = 0.0,
                oneWayM = 0.0,
                feasible = false,
                reason = "the round-trip commute is " + miles2(commuteRoundTripM) +
                    ", which leaves " + miles2(maxOf(0.0, roundTripM)) + " of a " +
                    fmt(targetMiles, 2) + " mi target for the trail",
            )
        }
        return TrailPortion(roundTripM = roundTripM, oneWayM = roundTripM / 2, feasible = true, reason = null)
    }

    /** Total round-trip distance for a given commute and trail portion. */
    fun roundTripTotalM(commuteOneWayM: Double, trailRoundTripM: Double): Double = 2 * commuteOneWayM + trailRoundTripM

    fun miles(metres: Double): Double = Geo.metresToMiles(metres)

    fun metres(miles: Double): Double = Geo.milesToMetres(miles)
}
