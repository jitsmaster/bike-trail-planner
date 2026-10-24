package com.biketrailplanner

import com.biketrailplanner.data.ValhallaClient
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.PolylineCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D46: a route with via points comes back from Valhalla as ONE LEG PER BREAK, and the app used to
 * read only the first one for the shape while taking the whole trip's length from the summary.
 *
 * Every route that carries a via point was therefore drawn and graded from part of itself - the D13
 * quiet detours, the D40/D44 small-street proposals and the D31 loop's return leg. Measured live
 * (build/live-loop-before.txt): `entrance -> via -> home` returns two legs, and tracing leg 0 alone
 * reports 1 of the 12 outbound ways coming back (8%) where the whole return reports 11 of 12.
 */
class ValhallaRouteParsingTest {

    /** Parsing is a method on the client; nothing here touches the network. */
    private val client = ValhallaClient(primaryBase = "http://fake", publicBase = "http://fake")

    private val home = LatLon(47.240371, -122.262019)
    private val via = LatLon(47.2222, -122.2555)
    private val entrance = LatLon(47.2033999, -122.2445285)

    private fun shapeOf(from: LatLon, to: LatLon, steps: Int = 8): String =
        PolylineCodec.encode(
            (0..steps).map { i ->
                val t = i.toDouble() / steps
                LatLon(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
            },
        )

    /** A Valhalla /route answer with one leg per break, exactly as the server shapes it. */
    private fun response(vararg legs: String, lengthKm: Double): String {
        val body = legs.joinToString(",") { it }
        return """
        {"trip":{"language":"en-US","summary":{"length":$lengthKm,"time":900.0},
        "legs":[$body],"status":0,"units":"kilometers"}}
        """.trimIndent()
    }

    private fun leg(from: LatLon, to: LatLon, maneuvers: String = "[]"): String =
        """{"maneuvers":$maneuvers,"shape":"${shapeOf(from, to)}","summary":{"length":0.5,"time":450.0}}"""

    @Test
    fun `a route with a via point keeps every leg, not only the first`() {
        val text = response(
            leg(home, via),
            leg(via, entrance),
            lengthKm = 6.27,
        )
        val route = client.parseRoute(text, "http://fake", false, home, entrance)
        assertTrue("both legs must be in the shape: ${route.points.size} points", route.points.size >= 10)
        assertEquals("the shape must start where the ride starts", home, route.points.first())
        assertTrue(
            "and it must end where the ride ends",
            Geo.distanceM(route.points.last(), entrance) < 1.0,
        )
        // The joint vertex is one point, not two.
        assertEquals(1, route.points.count { Geo.distanceM(it, via) < 0.5 })
        // The route's own length, not just the first leg's.
        assertEquals(6.27, route.lengthM / 1000.0, 1e-6)
        assertTrue(
            "the shape must be the whole ride, not the first leg",
            Polyline(route.points).lengthM > Polyline(PolylineCodec.decode(shapeOf(home, via))).lengthM * 1.5,
        )
    }

    @Test
    fun `the re-encoded shape is the route the app traced, so grading sees the same roads`() {
        val text = response(leg(home, via), leg(via, entrance), lengthKm = 6.27)
        val route = client.parseRoute(text, "http://fake", false, home, entrance)
        // `shape` is what trace_attributes is given: it must describe exactly the points the planner
        // draws and measures, or the grades belong to a different ride.
        assertEquals(route.points, PolylineCodec.decode(route.shape))
    }

    @Test
    fun `turns from every leg survive, each resolved against its own leg`() {
        val turn1 = """[{"instruction":"Ride south","length":0.5,"turn_degree":0,"begin_shape_index":0}]"""
        val turn2 = """[{"instruction":"Turn left onto Bridge Street","length":0.3,"turn_degree":-45,"begin_shape_index":2}]"""
        val text = response(leg(home, via, turn1), leg(via, entrance, turn2), lengthKm = 6.27)
        val route = client.parseRoute(text, "http://fake", false, home, entrance)
        assertEquals("both legs' turns must be kept", 2, route.maneuvers.size)
        assertEquals("Ride south", route.maneuvers[0].instruction)
        assertEquals("Turn left onto Bridge Street", route.maneuvers[1].instruction)
        // An index into a leg is an index into that leg's own shape, not into the combined one.
        val secondLeg = PolylineCodec.decode(shapeOf(via, entrance))
        assertEquals(secondLeg[2], route.maneuvers[1].begin)
    }

    @Test
    fun `a route with no via point is unchanged`() {
        val text = response(leg(home, entrance), lengthKm = 5.867)
        val route = client.parseRoute(text, "http://fake", false, home, entrance)
        assertEquals(PolylineCodec.decode(shapeOf(home, entrance)), route.points)
        assertEquals(5.867, route.lengthM / 1000.0, 1e-6)
        assertEquals(PolylineCodec.encode(route.points), route.shape)
    }
}
