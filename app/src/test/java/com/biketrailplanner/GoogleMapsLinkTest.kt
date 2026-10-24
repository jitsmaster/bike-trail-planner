package com.biketrailplanner

import com.biketrailplanner.domain.GoogleMapsLink
import com.biketrailplanner.domain.LatLon
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
}
