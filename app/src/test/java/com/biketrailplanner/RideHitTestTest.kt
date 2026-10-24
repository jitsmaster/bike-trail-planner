package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideHit
import com.biketrailplanner.domain.RideHitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D60: tapping a recorded ride. The rule is "a point if you tapped a point, otherwise the street you
 * tapped", and it is arithmetic, so it is pinned here rather than eyeballed on a phone.
 */
class RideHitTest {

    private val start = LatLon(47.2400, -122.2620)
    private val middle = LatLon(47.2450, -122.2620)
    private val end = LatLon(47.2500, -122.2620)

    /** 1.1 km of straight road, split into two named streets of ~equal length. */
    private fun ride() = Fixtures.rideTrack(
        points = listOf(start, middle, end),
        named = listOf(
            Fixtures.rideSegment("126th Avenue Court East", 500.0, Grade.C),
            Fixtures.rideSegment("12th Street East", 600.0, Grade.D),
        ),
    )

    private fun metresNorth(from: LatLon, m: Double) = LatLon(from.lat + m / 111_320.0, from.lon)

    @Test
    fun `tapping a recorded point answers with that point`() {
        val hit = RideHitTest.at(ride(), metresNorth(start, 5.0))
        assertTrue("a tap on a point is a point", hit is RideHit.Node)
        hit as RideHit.Node
        assertEquals(0, hit.index)
        assertEquals(0.0, hit.riddenM, 1.0)
        assertEquals("126th Avenue Court East", hit.street)
    }

    @Test
    fun `tapping the road between two points answers with the street, not a point`() {
        // ~275 m up the first leg: nowhere near a recorded point, but squarely on the road.
        val hit = RideHitTest.at(ride(), metresNorth(start, 275.0))
        assertTrue("a tap on the road is an edge", hit is RideHit.Edge)
        hit as RideHit.Edge
        assertEquals("126th Avenue Court East", hit.name)
        assertEquals(Grade.C, hit.grade)
        assertEquals(500.0, hit.lengthM, 1.0)
        assertTrue("the answer says how far off the line the tap was", hit.distanceFromTapM < 1.0)
    }

    @Test
    fun `the second half of the ride answers with the second street`() {
        val hit = RideHitTest.at(ride(), metresNorth(start, 1000.0))
        assertTrue(hit is RideHit.Edge)
        assertEquals("12th Street East", (hit as RideHit.Edge).name)
        assertEquals(Grade.D, hit.grade)
    }

    @Test
    fun `a tap nowhere near the ride answers nothing`() {
        val faraway = LatLon(47.40, -122.10)
        assertNull(RideHitTest.at(ride(), faraway))
    }

    @Test
    fun `a ride that could not be traced still answers with its points, and names no street`() {
        // An UNGRADED-only ride: the point is real, the street is unknown, and the app must not invent
        // one. "(recorded ride)" is a placeholder, not a street name.
        val untraced = Fixtures.rideTrack(
            points = listOf(start, end),
            named = listOf(Fixtures.rideSegment("(recorded ride)", 0.0, Grade.UNGRADED)),
        )
        val hit = RideHitTest.at(untraced, metresNorth(start, 2.0))
        assertTrue(hit is RideHit.Node)
        assertNull("a placeholder is not a street name", (hit as RideHit.Node).street)
    }

    @Test
    fun `a one-fix ride answers only when tapped right on the point`() {
        val oneFix = Fixtures.rideTrack(points = listOf(start))
        assertTrue(RideHitTest.at(oneFix, metresNorth(start, 3.0)) is RideHit.Node)
        assertNull("nothing to be on when there is no track", RideHitTest.at(oneFix, metresNorth(start, 500.0)))
    }

    @Test
    fun `a ride with no points answers nothing`() {
        assertNull(RideHitTest.at(Fixtures.rideTrack(points = emptyList()), start))
    }

    @Test
    fun `the street a point sits on follows the ride, not the tap distance`() {
        // Guards the bucketing: at 40% of the way along, the bucket must still be the first street even
        // though the tap is nearer the middle point.
        val near = RideHitTest.segmentAt(ride(), 0.2)
        val far = RideHitTest.segmentAt(ride(), 0.9)
        assertEquals("126th Avenue Court East", near?.name)
        assertEquals("12th Street East", far?.name)
    }

    @Test
    fun `the point answer reports how far into the ride it is`() {
        val hit = RideHitTest.at(ride(), metresNorth(end, 4.0)) as RideHit.Node
        val total = Geo.distanceM(start, middle) + Geo.distanceM(middle, end)
        assertEquals("the last point is the whole ride in", total, hit.riddenM, 2.0)
    }
}
