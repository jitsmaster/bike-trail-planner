package com.biketrailplanner

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.TrailClosure
import com.biketrailplanner.domain.TrailJoin
import com.biketrailplanner.domain.TrailWalker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3/D9: out-and-back on one trail, chaining when the corridor continues, stop at a closure. */
class TrailWalkerTest {

    private val start = LatLon(47.25, -122.25)

    @Test
    fun `a single trail is ridden out and back within its own length`() {
        val trail = Fixtures.trail("Solo Trail", start, 4_000.0)
        val network = Fixtures.network(listOf(trail))
        val walk = TrailWalker(network).walk(trail, start, 2_500.0)
        assertEquals(1, walk.legs.size)
        assertEquals(2_500.0, walk.oneWayM, 5.0)
        assertFalse(walk.exhausted)
        assertEquals("Solo Trail", walk.trailNames.single())
    }

    @Test
    fun `a short trail is capped instead of lapped`() {
        val trail = Fixtures.trail("Short Trail", start, 1_330.0)
        val network = Fixtures.network(listOf(trail))
        val walk = TrailWalker(network).walk(trail, start, 5_000.0)
        assertTrue(walk.exhausted)
        assertEquals(1_330.0, walk.oneWayM, 10.0)
        assertTrue(walk.notes.any { it.contains("corridor exhausted") })
        // never automatic lapping: the legs stay on one trail
        assertEquals(1, walk.trailNames.size)
    }

    @Test
    fun `the corridor continues onto a joined trail`() {
        val a = Fixtures.trail("Trail A", start, 2_000.0)
        val bStart = a.fullGeometry.points.last()
        val b = Fixtures.trail("Trail B", bStart, 3_000.0)
        val join = TrailJoin("J1", "Trail A", "Trail B", bStart, 0.0, "shared_osm_node")
        val network = Fixtures.network(listOf(a, b), listOf(join))
        val walk = TrailWalker(network).walk(a, start, 4_000.0)
        assertEquals(listOf("Trail A", "Trail B"), walk.trailNames)
        assertEquals(4_000.0, walk.oneWayM, 20.0)
        assertFalse(walk.exhausted)
        assertTrue(walk.notes.any { it.contains("chained onto Trail B") })
        // the legs are contiguous: end of one is the start of the next
        assertTrue(com.biketrailplanner.domain.Geo.distanceM(walk.legs[0].points.last(), walk.legs[1].points.first()) < 60.0)
    }

    @Test
    fun `a closure stops the walk and is reported by name`() {
        val closure = TrailClosure(
            trailName = "Severed Trail",
            osmWayId = 419747831L,
            wayLengthM = 2369.7,
            tags = mapOf("access" to "no", "bicycle" to "no"),
            // a far end that does not lie on this trail geometry: there is no far stub to reach
            northEnd = LatLon(47.25, -122.25),
            southEnd = LatLon(47.75, -122.75),
            effect = "a bicycle cannot ride through it",
        )
        val trail = Fixtures.trail("Severed Trail", start, 8_000.0, closures = listOf(closure))
        val network = Fixtures.network(listOf(trail))
        val walk = TrailWalker(network).walk(trail, start, 5_000.0)
        assertTrue(walk.exhausted)
        assertTrue(walk.notes.any { it.contains("severed") && it.contains("419747831") })
        assertTrue(walk.oneWayM < 5_000.0)
        assertTrue(walk.trailNames.single() == "Severed Trail")
    }

    @Test
    fun `a closure with a reachable far side returns a road gap instead of ending the ride`() {
        val steps = 20
        val dLat = 8_000.0 / steps / 111_320.0
        val closure = TrailClosure(
            trailName = "Severed Trail",
            osmWayId = 419747831L,
            wayLengthM = 2_369.7,
            tags = mapOf("access" to "no", "bicycle" to "no"),
            northEnd = LatLon(start.lat + 8 * dLat, start.lon),
            southEnd = LatLon(start.lat + 12 * dLat, start.lon),
            effect = "a bicycle cannot ride through it",
            closedSince = "2026-06-01",
            authority = "City of Sumner notice",
        )
        val trail = Fixtures.trail("Severed Trail", start, 8_000.0, closures = listOf(closure))
        val walk = TrailWalker(Fixtures.network(listOf(trail))).walk(trail, start, 5_000.0)
        assertFalse("the walk must not just stop", walk.exhausted)
        val gap = walk.openGap!!
        assertEquals(419747831L, gap.closure.osmWayId)
        assertEquals("Severed Trail", gap.resumeTrail)
        // it stopped at the north side of the closure and resumes at the south side
        assertTrue(com.biketrailplanner.domain.Geo.distanceM(gap.from, closure.northEnd) < 50.0)
        assertTrue(com.biketrailplanner.domain.Geo.distanceM(gap.to, closure.southEnd) < 80.0)
        // the note carries the provenance, not just the fact
        assertTrue(gap.note.contains("closed since 2026-06-01"))
        assertTrue(gap.note.contains("City of Sumner notice"))
        assertTrue(walk.trailMetres < 3_500.0)
    }

    @Test
    fun `walking again from the far side continues the trail`() {
        val steps = 20
        val dLat = 8_000.0 / steps / 111_320.0
        val closure = TrailClosure(
            trailName = "Severed Trail", osmWayId = 419747831L, wayLengthM = 2_369.7,
            tags = mapOf("bicycle" to "no"),
            northEnd = LatLon(start.lat + 8 * dLat, start.lon),
            southEnd = LatLon(start.lat + 12 * dLat, start.lon),
            effect = "",
        )
        val trail = Fixtures.trail("Severed Trail", start, 8_000.0, closures = listOf(closure))
        val walker = TrailWalker(Fixtures.network(listOf(trail)))
        val first = walker.walk(trail, start, 5_000.0)
        val second = walker.walk(trail, first.openGap!!.resumeAt, 2_000.0)
        assertEquals(1, second.legs.size)
        assertTrue(second.trailMetres > 1_000.0)
        // the continuation starts on the far side and does not re-enter the closed span
        assertTrue(com.biketrailplanner.domain.Geo.distanceM(second.legs.first().points.first(), closure.southEnd) < 80.0)
        assertNull(second.openGap)
    }

    @Test
    fun `a loop trail is ridden once around`() {
        val trail = Fixtures.trail("Loop Trail", start, 3_000.0, loop = true)
        val network = Fixtures.network(listOf(trail))
        val walk = TrailWalker(network).walk(trail, start, 2_900.0)
        assertTrue(walk.exhausted)
        assertEquals(3_000.0, walk.oneWayM, 50.0)
        assertTrue(walk.notes.any { it.contains("loop") })
    }

    @Test
    fun `chaining stops after the hop limit`() {
        val trails = (1..12).map { Fixtures.trail("T$it", start, 1_000.0) }
        // No joins at all: the walk must stop at the first trail end, not loop forever.
        val network = Fixtures.network(trails)
        val walk = TrailWalker(network).walk(trails.first(), start, 50_000.0)
        assertTrue(walk.exhausted)
        assertEquals(1_000.0, walk.oneWayM, 20.0)
    }
}
