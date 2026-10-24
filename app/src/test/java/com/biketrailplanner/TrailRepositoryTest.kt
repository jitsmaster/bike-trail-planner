package com.biketrailplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loads the real assets and checks the corridor connectivity the planner depends on. */
class TrailRepositoryTest {

    private val loaded = Assets.repository()

    @Test
    fun `all six trails load with geometry`() {
        assertEquals(6, loaded.network.trails.size)
        loaded.network.trails.forEach { t ->
            assertTrue("${t.name} has geometry", t.fullGeometry.lengthM > 500.0)
            assertTrue("${t.name} is paved", t.paved)
        }
    }

    @Test
    fun `the Sumner Link closure splits the trail into two rideable spans`() {
        val sumner = loaded.network.trail("Sumner Link Trail")!!
        assertTrue(sumner.hasClosure)
        assertEquals(2, sumner.rideableSpans.size)
        // docs/data/trails.json: way 419747831 is 2369.7 m; the shipped simplified geometry is
        // slightly longer between the two matching vertices, so allow a 20% band.
        assertTrue("closed span was ${sumner.closedM}", sumner.closedM in 2000.0..2800.0)
        assertEquals(8460.0, sumner.lengthM, 1.0)
        assertEquals(6090.0, sumner.rideableM, 1.0)
        assertEquals(419747831L, sumner.closures.first().osmWayId)
        assertTrue(sumner.closures.first().tags["bicycle"] == "no")
    }

    @Test
    fun `no other trail is closed and every trail has one rideable span`() {
        loaded.network.trails.filter { it.name != "Sumner Link Trail" }.forEach {
            assertEquals("${it.name}", 1, it.rideableSpans.size)
            assertEquals(0.0, it.closedM, 0.0)
        }
    }

    @Test
    fun `corridor A joins are present`() {
        val sumnerRiverwalk = loaded.network.joins.firstOrNull {
            setOf(it.trailA, it.trailB) == setOf("Sumner Link Trail", "Riverwalk Trail")
        }
        val sumnerLakeTapps = loaded.network.joins.firstOrNull {
            setOf(it.trailA, it.trailB) == setOf("Sumner Link Trail", "Lake Tapps Parkway Trail")
        }
        assertNotNull("Sumner Link <-> Riverwalk join", sumnerRiverwalk)
        assertNotNull("Sumner Link <-> Lake Tapps join", sumnerLakeTapps)
        assertEquals(0.0, sumnerRiverwalk!!.gapM, 0.0)
        assertEquals(0.0, sumnerLakeTapps!!.gapM, 0.0)
        assertTrue(loaded.network.neighbours("Sumner Link Trail").size >= 2)
    }

    @Test
    fun `road links record the gaps that are not D10 compliant`() {
        val edgewoodMilton = loaded.network.roadLinks.first { it.id == "B1" }
        assertEquals("not_compliant", edgewoodMilton.d10Status)
        assertTrue(edgewoodMilton.reason.contains("Enchanted Parkway"))
        val whiteRiver = loaded.network.roadLinks.first { it.id == "C1" }
        assertEquals("uncertain", whiteRiver.d10Status)
    }

    @Test
    fun `the access point table carries the measured candidates`() {
        assertEquals(143, loaded.accessPoints.size)
        assertEquals(6, loaded.accessPoints.map { it.trailName }.distinct().size)
        assertTrue(loaded.accessPoints.all { it.straightLineMFromHome > 1500.0 })
    }

    @Test
    fun `the override table carries the user's 25 mph postings for the local roads only`() {
        // D16: the user reported 25 mph on the local roads. The state highways deliberately have
        // no override (a 25 mph limit on SR-161 would be wrong), so they fall back to OSM.
        assertTrue("posted limits are filled in: ${loaded.limits.size}", loaded.limits.size >= 10)
        assertEquals(25, loaded.limits.row(names = listOf("122nd Avenue East"))!!.speedMph)
        assertEquals(25, loaded.limits.row(names = listOf("18th Street East"))!!.speedMph)
        assertNull("SR-161 must NOT carry a 25 mph override", loaded.limits.row(names = listOf("Meridian Avenue East"))?.speedMph)
        assertNull(loaded.limits.row(names = listOf("East Valley Highway East"))?.speedMph)
        assertNull(loaded.limits.row(names = listOf("Nonexistent Road")))
    }

    @Test
    fun `abbreviated table keys match the OSM spelling`() {
        // the producer writes "Jovita Blvd E"; OSM says "Jovita Boulevard East"
        val row = loaded.limits.row(names = listOf("Jovita Boulevard East"))
        assertNotNull("Jovita Boulevard East must match the abbreviated key", row)
        // D16a: the user corrected this road to 35 mph
        assertEquals(35, row!!.speedMph)
        assertTrue(com.biketrailplanner.domain.StreetNames.sameRoad("Jovita Blvd E", "Jovita Boulevard East"))
    }

    @Test
    fun `the producer's data flags are carried through, not suppressed`() {
        assertTrue("flags: ${loaded.dataFlags}", loaded.dataFlags.any { it.contains("jovita") })
        assertTrue(loaded.dataFlags.any { it.contains("stewart") })
        assertTrue(loaded.dataFlags.any { it.contains("stale") })
    }

    @Test
    fun `home config and thresholds come from the asset`() {
        assertEquals(47.240371, loaded.config.home.lat, 1e-6)
        assertEquals(-122.262019, loaded.config.home.lon, 1e-6)
        assertEquals(9, loaded.config.planner.maxWaypoints)
        assertEquals(2048, loaded.config.planner.maxUrlLength)
        // D10 as the rider restated it on 2026-09-16: the absolute caution distance (1.1 mi) is the
        // rule and the share no longer binds. Both numbers come from the asset, so this test is the
        // tripwire if either drifts.
        assertEquals(1.0, loaded.config.planner.dCapShare, 1e-9)
        assertEquals(1.1, loaded.config.planner.dCapMiles, 1e-9)
    }
}
