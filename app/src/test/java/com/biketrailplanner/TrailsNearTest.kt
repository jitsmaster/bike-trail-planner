package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.domain.TrailsNear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trails-near-you list. The two things that matter to a rider are the order (closest first, so
 * the trail they can actually reach is at the top) and that the two numbers shown are the real ones.
 */
class TrailsNearTest {

    private val origin = LatLon(47.240371, -122.262019)

    /** A trail starting [awayM] metres due north of the origin, running further north. */
    private fun trail(name: String, awayM: Double, lengthM: Double = 5_000.0): Trail =
        Fixtures.trail(name, LatLon(origin.lat + awayM / 111_320.0, origin.lon), lengthM)

    @Test
    fun `the closest trail comes first`() {
        val trails = listOf(
            trail("Far Trail", awayM = 6_000.0),
            trail("Near Trail", awayM = 700.0),
            trail("Middle Trail", awayM = 3_000.0),
        )

        val list = TrailsNear.closest(trails, origin)

        assertEquals(listOf("Near Trail", "Middle Trail", "Far Trail"), list.map { it.name })
    }

    @Test
    fun `the distance is measured to the nearest point of the trail, not its start`() {
        // A trail that BEGINS 3 km north of the rider and runs back down towards them: its nearest
        // vertex is ~500 m away. "How close is it?" means the nearest part of the trail a rider could
        // join, not wherever the relation's first node happens to be.
        val geometry = com.biketrailplanner.domain.Polyline(
            listOf(
                LatLon(origin.lat + 3_000.0 / 111_320.0, origin.lon),
                LatLon(origin.lat + 1_500.0 / 111_320.0, origin.lon),
                LatLon(origin.lat + 500.0 / 111_320.0, origin.lon),
            ),
        )
        val passing = trail("Passing Trail", awayM = 3_000.0).copy(fullGeometry = geometry)

        val list = TrailsNear.closest(listOf(passing), origin)

        assertEquals(1, list.size)
        assertTrue(
            "expected the nearest vertex (~500 m), got ${list.single().distanceM} m",
            list.single().distanceM < 700.0,
        )
    }

    @Test
    fun `the trail length shown is the rideable length the data holds`() {
        val list = TrailsNear.closest(listOf(trail("Long Trail", awayM = 500.0, lengthM = 12_000.0)), origin)

        assertEquals(1, list.size)
        assertEquals(12_000.0, list.single().rideableM, 1.0)
        assertEquals(Geo.metresToMiles(12_000.0), list.single().trailMiles, 1e-9)
    }

    @Test
    fun `one trail arriving from three sources is listed once, at its closest copy`() {
        // The bundled set, the regional bundle and live discovery can all carry the same name. The
        // rider must see one row, and it must be the closest geometry that names the distance.
        val bundled = trail("Interurban Trail Milton", awayM = 5_000.0)
        val regional = trail("Interurban Trail Milton", awayM = 2_000.0)
        val discovered = trail("Interurban Trail Milton", awayM = 3_000.0)

        val list = TrailsNear.closest(listOf(bundled, regional, discovered), origin)

        assertEquals(1, list.size)
        assertTrue("expected the closest copy, got ${list.single().distanceM}", list.single().distanceM < 2_100.0)
    }

    @Test
    fun `trails equally close are ordered by name so the list does not shuffle`() {
        val trails = listOf(
            trail("Zeta Trail", awayM = 1_000.0),
            trail("Alpha Trail", awayM = 1_000.0),
        )

        assertEquals(
            listOf("Alpha Trail", "Zeta Trail"),
            TrailsNear.closest(trails, origin).map { it.name },
        )
    }

    @Test
    fun `a trail whose geometry sits on top of the rider reads as zero metres away`() {
        // Polyline requires two points, so "no geometry" is impossible for a Trail; the honest edge
        // case is a trail the rider is standing on, which must read as 0 m, not be dropped.
        val underfoot = Fixtures.trail("Underfoot Trail", origin, 1_000.0)

        val list = TrailsNear.closest(listOf(underfoot), origin)

        assertEquals(1, list.size)
        assertEquals(0.0, list.single().distanceM, 1.0)
    }

    @Test
    fun `the list is capped, and the cap drops the furthest trails`() {
        val trails = (1..12).map { trail("Trail %02d".format(it), awayM = it * 500.0) }

        val list = TrailsNear.closest(trails, origin, limit = 3)

        assertEquals(3, list.size)
        assertEquals(listOf("Trail 01", "Trail 02", "Trail 03"), list.map { it.name })
    }

    @Test
    fun `a live-discovered trail is carried as unverified`() {
        val discovered = trail("Discovered Trail", awayM = 800.0).copy(verified = false)

        assertEquals(false, TrailsNear.closest(listOf(discovered), origin).single().verified)
    }
}
