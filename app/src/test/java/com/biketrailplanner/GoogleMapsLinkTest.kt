package com.biketrailplanner

import com.biketrailplanner.domain.GoogleMapsLink
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideRoad
import com.biketrailplanner.domain.RideTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsLinkTest {

    private fun line(n: Int): List<LatLon> =
        (0 until n).map { LatLon(47.240 + it * 0.001, -122.262) }

    @Test
    fun `a ride with one fix has nothing to hand over`() {
        assertNull(GoogleMapsLink.url(line(1)))
        assertNull("no points at all", GoogleMapsLink.url(emptyList()))
        assertTrue(GoogleMapsLink.stops(line(1)).isEmpty())
    }

    @Test
    fun `the ends are exact and the middle is evenly spaced`() {
        val points = line(500)
        val stops = GoogleMapsLink.stops(points)
        assertEquals(GoogleMapsLink.MAX_STOPS, stops.size)
        // Exact, not approximated: the rider's start and finish are the two points Maps must have.
        assertEquals(points.first(), stops.first())
        assertEquals(points.last(), stops.last())
        // Every stop is a point the rider actually recorded - never an invented one.
        assertTrue(stops.all { it in points })
    }

    @Test
    fun `a short ride keeps all of its points`() {
        val points = line(4)
        assertEquals(points, GoogleMapsLink.stops(points))
    }

    @Test
    fun `the url names origin destination and the intermediate stops`() {
        val url = GoogleMapsLink.url(line(12))
        assertNotNull(url)
        requireNotNull(url)
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
        assertTrue("ride starts here", url.contains("origin=47.240000,-122.262000"))
        assertTrue("take me back here", url.contains("destination=47.251000,-122.262000"))
        assertTrue("intermediate points ride as waypoints", url.contains("&waypoints="))
        assertTrue("bicycling, not driving", url.contains("travelmode=bicycling"))
        // The pipe between waypoints must be encoded, or Maps reads it as one malformed point.
        assertTrue(url.contains("%7C"))
        assertFalse("a raw pipe would break the query", url.substringAfter("waypoints=").substringBefore("&travelmode").contains('|'))
    }

    @Test
    fun `two points need no waypoints at all`() {
        val url = GoogleMapsLink.url(line(2))
        assertNotNull(url)
        assertFalse(requireNotNull(url).contains("waypoints"))
    }

    @Test
    fun `repeated points are dropped because Maps rejects a route that repeats a stop`() {
        val repeated = List(20) { LatLon(47.24, -122.26) }
        val stops = GoogleMapsLink.stops(repeated)
        assertEquals("a stationary recording collapses to one stop", 1, stops.size)
        assertNull("and one stop is not a route", GoogleMapsLink.url(stops))
    }

    // ------------------------------------------------------------------ D64: key turns

    private fun ride(points: List<LatLon>, roads: List<RideRoad>): RideTrack = RideTrack(
        signature = "sig",
        startedAtMs = 0L,
        endedAtMs = 1_000L,
        distanceM = 1_000.0,
        movingMs = 1_000L,
        maxSpeedMps = 5.0,
        gaps = 0,
        rejectedFixes = 0,
        points = points,
        wayIds = roads.map { it.wayId }.toSet(),
        roads = roads,
        named = emptyList(),
        gradedM = 0.0,
        ungradedM = 1_000.0,
        rejectedM = 0.0,
        breakdownTotalM = 1_000.0,
        byGrade = emptyMap(),
        safetyStale = false,
        dataFingerprint = "fp",
        label = "a ride",
        createdAt = 0L,
    )

    private val home = LatLon(47.2400, -122.2600)

    private fun north(metres: Double) = LatLon(home.lat + metres / 111_320.0, home.lon)

    @Test
    fun `a ride with a road list names its turns, not evenly spaced points`() {
        // Straight north, then a distinct turn east: the corner is the only thing worth naming, and an
        // even stride would have put stops at 100 m and 900 m instead - on straight road.
        val up = (0..90).map { north(it * 10.0) }
        val east = (1..90).map {
            LatLon(up.last().lat, home.lon + it * 10.0 / (111_320.0 * Math.cos(Math.toRadians(home.lat))))
        }
        val points = up + east
        val roads = listOf(
            RideRoad(1L, "18th Street East", "tertiary", "road", 0.0, 900.0),
            RideRoad(2L, "114th Avenue East", "tertiary", "road", 900.0, 900.0),
        )
        val url = GoogleMapsLink.url(ride(points, roads))
        assertNotNull(url)
        requireNotNull(url)
        // One stop, and it is the corner: 47.2480...,-122.2600.
        assertEquals(1, url.split("&waypoints=").last().substringBefore("&travelmode").split("%7C").size)
        assertTrue(url, url.contains("waypoints=47.248086,-122.260000") || url.contains("47.248085"))
        assertTrue("a one-way ride still starts where it started", url.contains("origin="))
    }

    @Test
    fun `an out-and-back omits origin because Maps refuses a repeated stop`() {
        // Home -> 600 m north -> home. Origin and destination would be the same coordinate, which Maps
        // rejects; the URL leaves origin out (Maps then uses the live position) and names the
        // turnaround as a waypoint, which is the stop that makes the ride come back.
        val up = (0..60).map { north(it * 10.0) }
        val points = up + up.reversed().drop(1)
        val roads = listOf(
            RideRoad(1L, "18th Street East", "tertiary", "road", 0.0, 600.0),
            RideRoad(1L, "18th Street East", "tertiary", "road", 600.0, 600.0),
        )
        val url = GoogleMapsLink.url(ride(points, roads))
        assertNotNull(url)
        requireNotNull(url)
        assertFalse("Maps rejects origin == destination", url.contains("origin="))
        assertTrue("it still ends at home", url.contains("destination=47.240000,-122.260000"))
        assertTrue("and the turnaround is named", url.contains("waypoints="))
    }

    @Test
    fun `a loop never names the start twice`() {
        val up = (0..60).map { north(it * 10.0) }
        val points = up + up.reversed().drop(1)
        val roads = listOf(
            RideRoad(1L, "18th Street East", "tertiary", "road", 0.0, 600.0),
            RideRoad(1L, "18th Street East", "tertiary", "road", 600.0, 600.0),
        )
        val url = GoogleMapsLink.url(ride(points, roads))
        requireNotNull(url)
        val waypoints = url.substringAfter("waypoints=", "").substringBefore("&travelmode")
        assertFalse(waypoints, waypoints.contains("47.240000,-122.260000"))
    }
}
