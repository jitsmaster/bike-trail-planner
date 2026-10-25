package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideRoad
import com.biketrailplanner.domain.StopPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D64: which points of a ride are worth naming as Google Maps stops.
 *
 * The rider's rule was *"add the points to key turns and let google map handle the more natural stops
 * automatically"*, so these tests are about the rule, not about a particular ride: a corner is chosen,
 * a straight stretch is not, the same corner visited twice is ONE stop, and the far end always
 * survives the cap.
 */
class StopPickerTest {

    private fun metres(north: Double, east: Double = 0.0, from: LatLon = LatLon(47.2400, -122.2600)): LatLon =
        LatLon(from.lat + north / 111_320.0, from.lon + east / (111_320.0 * Math.cos(Math.toRadians(from.lat))))

    /** A straight north run of [northM] metres, sampled every [stepM]. */
    private fun straight(northM: Double, stepM: Double = 10.0, start: LatLon = LatLon(47.2400, -122.2600)): List<LatLon> {
        val n = (northM / stepM).toInt()
        return (0..n).map { metres(it * stepM, 0.0, start) }
    }

    @Test
    fun `a straight ride with one road has no stops worth naming`() {
        val points = straight(2_000.0)
        val roads = listOf(RideRoad(1L, "Straight Street", "residential", "road", 0.0, 2_000.0))
        assertEquals(emptyList<Any>(), StopPicker.stops(points, roads))
    }

    @Test
    fun `the far point of an out-and-back survives even with no road change`() {
        // North 1 km, then back south: same road, no junction in the list, one obvious turnaround.
        val up = straight(1_000.0)
        val points = up + up.reversed().drop(1)
        val roads = listOf(RideRoad(1L, "Straight Street", "residential", "road", 0.0, points.size.toDouble()))
        val stops = StopPicker.stops(points, roads)
        assertEquals(1, stops.size)
        assertTrue("the turnaround is the far point", stops.single().why.contains("far point"))
        assertTrue(
            "and it is the northern end",
            Geo.distanceM(stops.single().point, points.first()) > 950.0,
        )
    }

    @Test
    fun `a street-name change is a stop even when the road runs straight`() {
        // One straight line, two roads: the name changes half way, on a road that does not bend.
        val points = straight(2_000.0)
        val roads = listOf(
            RideRoad(1L, "First Street", "residential", "road", 0.0, 1_000.0),
            RideRoad(2L, "Second Street", "residential", "road", 1_000.0, 1_000.0),
        )
        val stops = StopPicker.stops(points, roads)
        assertEquals(1, stops.size)
        assertTrue(stops.single().why.contains("Second Street"))
        assertEquals("the stop sits on the junction", 1_000.0, stops.single().atM, 60.0)
    }

    @Test
    fun `a right-angle turn onto a separated path outranks a name change`() {
        // East 800 m, then north 800 m, onto a cycleway.
        val corner = LatLon(47.2400, -122.2600)
        val east = (0..80).map { metres(0.0, it * 10.0, corner) }
        val north = (1..80).map { metres(it * 10.0, 800.0, corner) }
        val points = east + north
        val roads = listOf(
            RideRoad(1L, "Main Street", "tertiary", "road", 0.0, 800.0),
            RideRoad(2L, "Trail", "service_other", "cycleway", 800.0, 800.0),
        )
        val stops = StopPicker.stops(points, roads)
        assertTrue("the corner is chosen", stops.isNotEmpty())
        val first = stops.first()
        assertTrue("it names the path", first.why.contains("Trail") || first.why.contains("separated path"))
        assertEquals(800.0, first.atM, 60.0)
        assertEquals("the turn is measured, not assumed", 90.0, first.turnDeg, 12.0)
    }

    @Test
    fun `a corner ridden twice is one stop, not two`() {
        // Out east 800 m, back to the corner, then north: the same physical corner appears twice.
        val corner = LatLon(47.2400, -122.2600)
        val east = (0..80).map { metres(0.0, it * 10.0, corner) }
        val back = east.reversed().drop(1)
        val north = (1..40).map { metres(it * 10.0, 0.0, corner) }
        val points = east + back + north
        // The arc positions follow the ridden distance: 800 m out, 800 m back, then north.
        val roads = listOf(
            RideRoad(1L, "Main Street", "tertiary", "road", 0.0, 800.0),
            RideRoad(2L, "Main Street", "tertiary", "road", 800.0, 800.0),
            RideRoad(3L, "North Street", "residential", "road", 1_600.0, 400.0),
        )
        val stops = StopPicker.stops(points, roads)
        val atCorner = stops.filter { Geo.distanceM(it.point, corner) < 60.0 }
        assertEquals("one place, one stop", 1, atCorner.size)
    }

    @Test
    fun `the cap is honoured and the answer stays in riding order`() {
        // Twelve named turns in a line, each 300 m apart, so every one is a candidate.
        val corners = (0..12).map { metres(it * 300.0 * 0.0, it * 300.0) }
        val roads = corners.dropLast(1).mapIndexed { index, _ ->
            RideRoad(index.toLong(), "Road ${index + 1}", "residential", "road", index * 300.0, 300.0)
        }
        val stops = StopPicker.stops(corners, roads, maxStops = 4)
        assertTrue("never more than asked for", stops.size <= 4)
        assertEquals("and in riding order", stops.sortedBy { it.atM }, stops)
    }

    @Test
    fun `no roads means no invented stops`() {
        assertEquals(emptyList<Any>(), StopPicker.stops(straight(1_000.0), emptyList()))
        assertEquals(emptyList<Any>(), StopPicker.stops(listOf(LatLon(47.0, -122.0)), emptyList()))
        assertEquals(emptyList<Any>(), StopPicker.stops(straight(1_000.0), emptyList(), maxStops = 0))
    }

    // ------------------------------------------------- the two rules the 2026-10-25 ride forced

    @Test
    fun `a name change inside sight of the front door is not a stop`() {
        // Measured: on the 19.5 km ride the highest-scoring name change was 60 m from the start, at the
        // junction where the driveway becomes 18th Street East. The origin already pins that, and a
        // waypoint there spends one of nine slots on a place Google is routing to anyway.
        val points = straight(2_000.0)
        val roads = listOf(
            RideRoad(1L, "(unnamed)", "residential", "road", 0.0, 60.0),
            RideRoad(2L, "18th Street East", "residential", "road", 60.0, 1_940.0),
        )
        assertEquals(emptyList<Any>(), StopPicker.stops(points, roads))
    }

    @Test
    fun `a name change half way along is still a stop`() {
        // The end rule must not throw away the pins that make the hand-off name the roads ridden.
        val points = straight(2_000.0)
        val roads = listOf(
            RideRoad(1L, "First Street", "residential", "road", 0.0, 1_000.0),
            RideRoad(2L, "Second Street", "residential", "road", 1_000.0, 1_000.0),
        )
        assertEquals(1, StopPicker.stops(points, roads).size)
    }

    @Test
    fun `a bend where nothing changed is one road drawn twice, not a stop`() {
        // A rail-trail is twenty OSM ways with one name. The 30° "turn" at a way boundary is the
        // trail's own curve, and naming it stopped the rider twice within 300 m at the turnaround.
        val start = LatLon(47.2400, -122.2600)
        val north = (0..100).map { metres(it * 10.0, 0.0, start) }
        val turn = 30.0
        val bent = (1..100).map { i ->
            val d = i * 10.0
            metres(1_000.0 + d * Math.cos(Math.toRadians(turn)), d * Math.sin(Math.toRadians(turn)), start)
        }
        val points = north + bent
        val roads = listOf(
            RideRoad(1L, "Rail Trail", "service_other", "cycleway", 0.0, 1_000.0),
            RideRoad(2L, "Rail Trail", "service_other", "cycleway", 1_000.0, 1_000.0),
        )
        assertEquals(emptyList<Any>(), StopPicker.stops(points, roads))
    }

    @Test
    fun `a ride has one far point, not two`() {
        // Measured: two candidates 287 m apart near the turnaround both scored as the far point, and
        // the hand-off asked Google for the same turn twice. One ride has one far point.
        val up = straight(1_000.0)
        val points = up + up.reversed().drop(1)
        val roads = listOf(
            RideRoad(1L, "Trail", "service_other", "cycleway", 0.0, 900.0),
            RideRoad(2L, "Trail", "service_other", "cycleway", 900.0, 300.0),
            RideRoad(3L, "Trail", "service_other", "cycleway", 1_200.0, 800.0),
        )
        val stops = StopPicker.stops(points, roads)
        assertEquals("the turnaround is named once", 1, stops.count { it.why.contains("far point") })
    }

    @Test
    fun `a rename with no turn is described as a rename, not as a turn`() {
        val points = straight(2_000.0)
        val roads = listOf(
            RideRoad(1L, "First Street", "residential", "road", 0.0, 1_000.0),
            RideRoad(2L, "Second Street", "residential", "road", 1_000.0, 1_000.0),
        )
        val why = StopPicker.stops(points, roads).single().why
        assertTrue("it says what happened: $why", why.contains("becomes"))
        assertTrue("and does not pretend there was a turn: $why", !why.contains("turn onto"))
    }

    @Test
    fun `a real corner on one long road is still a stop`() {
        // Military Road East bends 90.6° and 28th Avenue South 89.3° inside a single name, and those are
        // corners a rider has to get right - the bend rule must keep them.
        val corner = LatLon(47.2400, -122.2600)
        val north = (0..100).map { metres(it * 10.0, 0.0, corner) }
        val east = (1..100).map { metres(1_000.0, it * 10.0, corner) }
        val points = north + east
        val roads = listOf(
            RideRoad(1L, "Military Road East", "secondary", "road", 0.0, 1_000.0),
            RideRoad(2L, "Military Road East", "secondary", "road", 1_000.0, 1_000.0),
        )
        val stops = StopPicker.stops(points, roads)
        assertEquals(1, stops.size)
        assertEquals("the corner, measured", 90.0, stops.single().turnDeg, 12.0)
        assertEquals(1_000.0, stops.single().atM, 60.0)
    }
}
