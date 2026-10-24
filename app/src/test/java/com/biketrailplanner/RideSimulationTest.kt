package com.biketrailplanner

import com.biketrailplanner.data.RideSimulation
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D51 (docs/RIDE-RECORDER.md section 4): the debug in-app simulator. Its whole job is to produce a
 * track that exercises the recorder the way a real ride does, so the test is "does this track come back
 * to where it started, and does the recorder see a real ride in it?".
 */
class RideSimulationTest {

    private val origin = LatLon(47.240, -122.262)

    @Test
    fun `the synthetic shape goes out and comes back to the start`() {
        val shape = RideSimulation.outAndBack(origin, 800.0)
        assertEquals(3, shape.size)
        assertEquals(origin.lat, shape.first().lat, 1e-9)
        assertEquals(origin.lat, shape.last().lat, 1e-9)
        assertTrue("the middle point is away from the start", shape[1].lat > shape[0].lat)
    }

    @Test
    fun `the simulated track is deterministic and one fix per second`() {
        val start = 1_700_000_000_000L
        val a = RideSimulation.track(origin, start)
        val b = RideSimulation.track(origin, start)
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) -> assertEquals(x.point.lat, y.point.lat, 1e-12) }
        a.zipWithNext().forEach { (x, y) -> assertEquals(RideSimulation.FIX_INTERVAL_MS, y.atEpochMs - x.atEpochMs) }
        assertTrue("a default ride is a real ride, not a few metres", a.size > 100)
    }

    @Test
    fun `every simulated fix is good enough for the recorder to keep`() {
        val fixes = RideSimulation.track(origin, 1_700_000_000_000L)
        assertTrue("no fix is worse than the recorder's accuracy limit", fixes.all { it.accuracyM <= 50.0 })
    }

    @Test
    fun `the recorded simulated ride is a real round trip the recorder would end by itself`() {
        val fixes = RideSimulation.track(origin, 1_700_000_000_000L)
        val recorder = RideRecorder()
        recorder.start(fixes.first().atEpochMs)
        fixes.forEach { recorder.onFix(it) }
        val ride = recorder.finish(fixes.last().atEpochMs)!!

        assertTrue("the ride covers the out-and-back", ride.distanceM > 1_500.0)
        assertEquals(0, ride.gaps)
        // D61: the track still comes back to where it started. Nothing acts on that any more - the
        // rider stops the recording - but the simulator must keep producing a real round trip.
        assertTrue(
            "back at the start",
            Geo.distanceM(ride.points.first(), ride.points.last()) <= 200.0,
        )
    }
}
