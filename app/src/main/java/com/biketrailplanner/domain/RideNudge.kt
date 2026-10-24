package com.biketrailplanner.domain

/**
 * D35 (docs/VELOTRIC-LINK.md option A, piece 3a): the on-return arrival nudge.
 *
 * The app never starts or stops a Velotric ride (option A). It only helps the rider *remember* to
 * stop one: after a ride has been handed to Google Maps for navigation, the rider is told to stop
 * their Velotric ride when they are back where they started.
 *
 * Every rule lives here, with no Android type in sight, so the decision is unit-testable on the
 * JVM (`RideNudgeTest`) instead of only on a phone.
 *
 * The ride is a round trip (`PlannedRoute`), and the Google Maps hand-off also ends where it
 * started, so "arrived" means **back near the start point**, not near the turnaround.
 */
object RideNudge {

    /**
     * A ride shorter than this cannot have gone anywhere and come back, so a hand-off this fresh is
     * never called an arrival. Ten minutes is also comfortably longer than the shortest ride the
     * planner will produce.
     */
    const val MIN_RIDE_MS = 10L * 60_000L

    /**
     * A hand-off this old is treated as abandoned rather than active: without it, opening the app
     * at home the next morning would show "stop your Velotric ride" for yesterday's trip.
     */
    const val MAX_RIDE_MS = 12L * 60L * 60_000L

    /**
     * "Back at the start" means within this many metres of the point the ride was planned from.
     * Generous on purpose: a phone's fix drifts, and a nudge that never fires is useless, while a
     * nudge that fires a street away is harmless because the rider still has to look at the app.
     */
    const val ARRIVAL_RADIUS_M = 250.0

    /** The stamp left by the last hand-off: where the ride started, and when. */
    data class HandOff(val startPoint: LatLon, val atEpochMs: Long)

    /** The prompt to show. Only the fact that it is non-null matters to the screen. */
    data class Nudge(
        val startPoint: LatLon,
        val awayM: Double,
        val sinceHandOffMs: Long,
    )

    /**
     * Decide whether to prompt the rider to stop their Velotric ride.
     *
     * @param handOff the stamp from the last hand-off, or null when no ride is pending
     * @param nowEpochMs the current wall clock (`System.currentTimeMillis()`)
     * @param currentFix the live position, or null when there is no good fix to judge from
     * @param dismissed true once the rider has dismissed this nudge
     */
    fun decide(
        handOff: HandOff?,
        nowEpochMs: Long,
        currentFix: LatLon?,
        dismissed: Boolean = false,
    ): Nudge? {
        if (dismissed) return null
        if (handOff == null) return null
        if (currentFix == null) return null
        val since = nowEpochMs - handOff.atEpochMs
        if (since < MIN_RIDE_MS) return null
        if (since > MAX_RIDE_MS) return null
        val awayM = Geo.distanceM(handOff.startPoint, currentFix)
        if (awayM > ARRIVAL_RADIUS_M) return null
        return Nudge(handOff.startPoint, awayM, since)
    }
}
