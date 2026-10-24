package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.RideVariety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6: the options shown must be other RIDES, not the same ride entered at a neighbouring entrance.
 * Measured bug (2026-09-16): all three options from home were Interurban Trail Milton at entrances
 * ~50 m apart, same trail length, same total.
 */
class RideVarietyTest {

    private val entryA = LatLon(47.248000, -122.325000)

    /** ~55 m north of [entryA]: the next entrance along the same stretch of trail. */
    private val entryNearA = LatLon(47.248500, -122.325000)

    /** ~1.4 km away: a different place to start from. */
    private val entryFar = LatLon(47.258000, -122.293000)

    private fun ride(
        trail: String = "Interurban Trail Milton",
        entry: LatLon = entryA,
        trailMiles: Double = 3.35,
        signature: String = "sig-$trail-$trailMiles-${entry.lat}",
    ): PlannedRoute = Fixtures.plannedRoute(trailOneWayM = Geo.milesToMetres(trailMiles) / 2.0).copy(
        signature = signature,
        trailNames = listOf(trail),
        accessPoint = Fixtures.accessPoint(trail, entry),
        trailRoundTripM = Geo.milesToMetres(trailMiles),
    )

    @Test
    fun `the same trail entered 50 m away with the same trail length is the same ride`() {
        assertTrue(RideVariety.sameRide(ride(entry = entryA), ride(entry = entryNearA)))
        assertEquals(1, RideVariety.distinct(listOf(ride(entry = entryA), ride(entry = entryNearA))).size)
    }

    @Test
    fun `a different trail length is a different ride`() {
        assertFalse(RideVariety.sameRide(ride(trailMiles = 3.35), ride(trailMiles = 2.10)))
        assertEquals(2, RideVariety.distinct(listOf(ride(trailMiles = 3.35), ride(trailMiles = 2.10))).size)
    }

    @Test
    fun `a different entrance area is a different ride`() {
        assertFalse(RideVariety.sameRide(ride(entry = entryA), ride(entry = entryFar)))
        assertEquals(2, RideVariety.distinct(listOf(ride(entry = entryA), ride(entry = entryFar))).size)
    }

    @Test
    fun `a different trail is a different ride`() {
        val milton = ride(trail = "Interurban Trail Milton")
        val sumner = ride(trail = "Sumner Link Trail")
        assertFalse(RideVariety.sameRide(milton, sumner))
        assertEquals(2, RideVariety.distinct(listOf(milton, sumner)).size)
    }

    @Test
    fun `distinct keeps the first of each group and the order it was given`() {
        val first = ride(entry = entryA, signature = "first")
        val neighbour = ride(entry = entryNearA, signature = "neighbour")
        val other = ride(trail = "Sumner Link Trail", signature = "other")
        val kept = RideVariety.distinct(listOf(first, neighbour, other))
        assertEquals(listOf("first", "other"), kept.map { it.signature })
    }

    @Test
    fun `newRides reports only the rides that are not already represented`() {
        val kept = listOf(ride(entry = entryA, signature = "kept"))
        val candidates = listOf(
            ride(entry = entryNearA, signature = "duplicate"),
            ride(trail = "Sumner Link Trail", signature = "fresh"),
        )
        assertEquals(listOf("fresh"), RideVariety.newRides(candidates, kept).map { it.signature })
    }

    @Test
    fun `the tolerance is the GPS-jitter scale, so real alternatives are never hidden`() {
        // just inside the tolerance is the same ride, just outside is a different one
        val justInside = LatLon(entryA.lat + (RideVariety.ENTRY_TOLERANCE_M * 0.8) / 111_320.0, entryA.lon)
        val justOutside = LatLon(entryA.lat + (RideVariety.ENTRY_TOLERANCE_M * 1.5) / 111_320.0, entryA.lon)
        assertEquals(1, RideVariety.distinct(listOf(ride(entry = entryA), ride(entry = justInside))).size)
        assertEquals(2, RideVariety.distinct(listOf(ride(entry = entryA), ride(entry = justOutside))).size)
    }
}
