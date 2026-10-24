package com.biketrailplanner

import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.MapsUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC section 8 waypoint packing and docs/SECURITY.md SEC-11. */
class WaypointPackingTest {

    private val home = LatLon(47.240371, -122.262019)

    private fun routeWithCautions() = Fixtures.plannedRoute(
        roadSegments = listOf(
            Fixtures.segment(Grade.UNGRADED, 800.0, "18th Street East"),
            Fixtures.segment(Grade.D, 600.0, "Tacoma Avenue", 35),
            Fixtures.segment(Grade.A, 400.0, "Fryar Avenue"),
            Fixtures.segment(Grade.E, 200.0, "Primary Road", 45),
        ),
        trailOneWayM = 3_000.0,
    )

    @Test
    fun `the url has no origin, uses bicycling and dir_action navigate`() {
        val url = MapsUrlBuilder.build(routeWithCautions(), home).url
        assertFalse("origin must stay off so Google starts from the live position", url.contains("origin="))
        assertTrue(url.contains("destination=47.240371,-122.262019"))
        assertTrue(url.contains("travelmode=bicycling"))
        assertTrue(url.contains("dir_action=navigate"))
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
    }

    @Test
    fun `at most nine waypoints and under 2048 characters`() {
        val maps = MapsUrlBuilder.build(routeWithCautions(), home)
        assertTrue(maps.included.size <= 9)
        assertTrue(maps.length < 2048)
        // 2 safety-critical anchors plus safety points, all numeric
        assertTrue(maps.included.any { it.kind == "access point" })
        assertTrue(maps.included.any { it.kind == "turnaround" })
    }

    @Test
    fun `the waypoints parameter carries only numeric coordinates`() {
        val maps = MapsUrlBuilder.build(routeWithCautions(), home)
        val waypoints = maps.url.substringAfter("&waypoints=").substringBefore("&travelmode")
        val tokens = waypoints.split("|")
        assertEquals(maps.included.size, tokens.size)
        tokens.forEach { token ->
            assertTrue("token '$token' must be lat,lon", Regex("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$").matches(token))
        }
        // no street names anywhere in the URL
        assertFalse(maps.url.contains("Tacoma"))
        assertFalse(maps.url.contains("Primary"))
    }

    @Test
    fun `the url lists waypoints in ride order and keeps the access point before the turnaround`() {
        val maps = MapsUrlBuilder.build(routeWithCautions(), home)
        // selection is by priority, but the list Google receives must follow the ride
        val indexes = maps.included.map { it.travelIndex }
        assertEquals(indexes.sorted(), indexes)
        val access = maps.included.indexOfFirst { it.kind == "access point" }
        val turnaround = maps.included.indexOfFirst { it.kind == "turnaround" }
        assertTrue(access < turnaround)
        // safety points on the commute come before the access point
        maps.included.filter { it.kind == "caution road" || it.kind == "hostile road" }.forEach {
            assertTrue(it.travelIndex < maps.included[access].travelIndex)
        }
        // and the list order is the order inside the waypoints parameter
        val waypoints = maps.url.substringAfter("&waypoints=").substringBefore("&travelmode").split("|")
        assertEquals(maps.included.map { MapsUrlBuilder.coordinate(it.point) }, waypoints)
    }

    @Test
    fun `a tight url limit drops the least important waypoints and says so`() {
        val route = routeWithCautions()
        val full = MapsUrlBuilder.build(route, home, maxUrlLength = 2048)
        val tight = MapsUrlBuilder.build(route, home, maxUrlLength = 120)
        assertTrue(tight.included.size < full.included.size)
        assertTrue(tight.length <= 120)
        assertTrue(tight.droppedSafetyCritical)
        assertTrue(tight.explanation!!.contains("not exact"))
    }

    @Test
    fun `the waypoint ceiling is enforced and the leftovers are reported`() {
        val maps = MapsUrlBuilder.build(routeWithCautions(), home, maxWaypoints = 3, maxUrlLength = 10_000)
        assertEquals(3, maps.included.size)
        assertTrue(maps.dropped.isNotEmpty())
        // and the default ceiling is nine
        val longRoute = Fixtures.plannedRoute(
            roadSegments = (1..20).map { Fixtures.segment(Grade.D, 200.0, "Street $it", 35) },
        )
        assertTrue(MapsUrlBuilder.build(longRoute, home).included.size <= 9)
    }

    @Test
    fun `a run of edges on one caution street makes a single waypoint`() {
        val route = Fixtures.plannedRoute(
            roadSegments = listOf(
                Fixtures.segment(Grade.C, 400.0, "Quiet Street", 25),
                Fixtures.segment(Grade.D, 150.0, "Tacoma Avenue", 35),
                Fixtures.segment(Grade.D, 150.0, "Tacoma Avenue", 35),
                Fixtures.segment(Grade.D, 150.0, "Tacoma Avenue", 35),
                Fixtures.segment(Grade.C, 400.0, "Quiet Street", 25),
            ),
        )
        val maps = MapsUrlBuilder.build(route, home, maxUrlLength = 10_000)
        assertEquals(1, maps.included.count { it.kind == "caution road" })
        assertEquals("grade D Tacoma Avenue", maps.included.first { it.kind == "caution road" }.label)
    }

    @Test
    fun `a coordinate is always formatted with a dot`() {
        assertEquals("47.240371,-122.262019", MapsUrlBuilder.coordinate(home))
    }
}
