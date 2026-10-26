package com.biketrailplanner

import com.biketrailplanner.data.RideSimulation
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.RideRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * D51 (docs/RIDE-RECORDER.md section 4): the debug in-app simulator. Its whole job is to produce a
 * track that exercises the recorder the way a real ride does, so the test is "does this track come back
 * to where it started, does it contain real turns, and does the recorder see a real ride in it?".
 *
 * The turn tests exist because the simulator used to ride a straight line (rider, 2026-10-26: *"do some
 * turns instead of going a straight line!"*): one 180-degree turn at the turnaround, no junctions, so
 * the stop picker and the Google Maps hand-off were being judged on a ride that had nothing to choose
 * between.
 */
class RideSimulationTest {

    private val origin = LatLon(47.240, -122.262)

    /** Bearing in degrees from a to b, on a local flat-earth assumption - plenty for a 3 km loop. */
    private fun bearing(a: LatLon, b: LatLon): Double {
        val meanLat = Math.toRadians((a.lat + b.lat) / 2)
        return Math.toDegrees(atan2((b.lon - a.lon) * cos(meanLat), b.lat - a.lat))
    }

    /** Every corner in [shape], as the change of heading in degrees, ignoring anything under [floorDeg]. */
    private fun turns(shape: List<LatLon>, floorDeg: Double = 40.0): List<Double> =
        (1 until shape.size - 1).map { i ->
            val d = abs((bearing(shape[i], shape[i + 1]) - bearing(shape[i - 1], shape[i]) + 540.0) % 360.0 - 180.0)
            d
        }.filter { it >= floorDeg }

    @Test
    fun `the simulated loop starts at the front door, closes on itself and is a real ride long`() {
        val shape = RideSimulation.loop()
        assertTrue("a real street loop, not a triangle", shape.size >= 20)
        assertEquals(
            "a round trip ends where it starts",
            0.0,
            Geo.distanceM(shape.first(), shape.last()),
            25.0,
        )
        assertEquals("a loop from home starts at home", 0.0, Geo.distanceM(shape.first(), RideSimulation.LOOP_START), 30.0)
        assertEquals("the loop is the length the simulator says it is", RideSimulation.LOOP_LENGTH_M, Polyline(shape).lengthM, 120.0)
    }

    @Test
    fun `the simulated loop really turns, several times, and four of them are street corners`() {
        val corners = turns(RideSimulation.loop())
        assertTrue("a ride with corners has several: got ${corners.size}", corners.size >= 6)
        assertTrue(
            "and at least four of them are ~90-degree street corners, not gentle bends: $corners",
            corners.count { it >= 80.0 } >= 4,
        )
        assertTrue("no corner turns the rider around inside one segment", corners.none { it > 130.0 })
    }

    @Test
    fun `the recorded simulated ride has corners, so the stop picker has something to choose between`() {
        val fixes = RideSimulation.track(origin, 1_700_000_000_000L)
        val ride = runRecorder(fixes)
        val corners = turns(ride.points, floorDeg = 50.0)
        assertTrue(
            "the recorded ride must contain corners the stop picker can score: $corners",
            corners.size >= 4,
        )
    }

    @Test
    fun `a phone far from home still rides a loop with corners, shifted to where it is`() {
        val far = LatLon(47.900, -122.100)
        val shape = RideSimulation.shapeFor(far)
        assertEquals("the shifted loop starts where the phone is", 0.0, Geo.distanceM(shape.first(), far), 30.0)
        assertTrue("and it still turns", turns(shape).size >= 6)
    }

    private fun runRecorder(fixes: List<com.biketrailplanner.domain.RideFix>): com.biketrailplanner.domain.RecordedRide {
        val recorder = RideRecorder()
        recorder.start(fixes.first().atEpochMs)
        fixes.forEach { recorder.onFix(it) }
        return recorder.finish(fixes.last().atEpochMs)!!
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
