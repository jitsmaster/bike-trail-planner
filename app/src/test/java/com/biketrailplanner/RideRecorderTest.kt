package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideRecorder
import com.biketrailplanner.domain.TrackSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D51: the pure half of the ride recorder, driven by a scripted track so the whole feature is tested
 * without riding (the rider's own constraint). See docs/RIDE-RECORDER.md section 4.
 */
class RideRecorderTest {

    /** ~111.19 m apart in latitude. */
    private fun at(stepTenth: Int) = LatLon(47.0 + stepTenth * 0.001, -122.0)

    private fun fix(stepTenth: Int, atMs: Long, accuracy: Double = 5.0, speed: Double? = null) =
        RideFix(at(stepTenth), atMs, accuracy, speed)

    @Test
    fun `a straight ride measures its own length`() {
        val rec = RideRecorder()
        rec.start(0)
        for (i in 0..10) rec.onFix(fix(i, i * 10_000L))
        val ride = rec.finish(100_000L)!!
        assertEquals(Geo.distanceM(at(0), at(10)), ride.distanceM, 1.0)
        assertEquals(11, ride.points.size)
        assertEquals(100_000L, ride.movingMs)
        assertEquals(0, ride.gaps)
        assertTrue(ride.hasTrack)
    }

    @Test
    fun `a repeated fix is jitter, not travel`() {
        val rec = RideRecorder()
        rec.start(0)
        assertTrue(rec.onFix(fix(0, 0)))
        assertFalse("the same point again is not movement", rec.onFix(fix(0, 1_000)))
        val snapshot = rec.snapshot(2_000L)
        assertEquals(0.0, snapshot.distanceM, 1e-9)
        assertEquals(1, snapshot.points.size)
    }

    @Test
    fun `a poor fix is rejected and counted, never added to the track`() {
        val rec = RideRecorder()
        rec.start(0)
        rec.onFix(fix(0, 0))
        assertFalse(rec.onFix(fix(1, 10_000L, accuracy = 120.0)))
        val snapshot = rec.snapshot(20_000L)
        assertEquals(1, snapshot.rejectedFixes)
        assertEquals(1, snapshot.points.size)
        assertEquals(0.0, snapshot.distanceM, 1e-9)
    }

    @Test
    fun `a silence is a gap, not moving time`() {
        val rec = RideRecorder()
        rec.start(0)
        rec.onFix(fix(0, 0))
        rec.onFix(fix(1, 10_000L))
        rec.onFix(fix(2, 120_000L)) // 110 s since the last fix
        val ride = rec.snapshot(130_000L)
        assertEquals(1, ride.gaps)
        assertEquals(10_000L, ride.movingMs)
        // The distance across the gap is still counted, but the gap is reported so it is never hidden.
        assertEquals(2 * Geo.distanceM(at(0), at(1)), ride.distanceM, 1.0)
    }

    @Test
    fun `a pause is never counted as travel`() {
        val rec = RideRecorder()
        rec.start(0)
        rec.onFix(fix(0, 0))
        rec.onFix(fix(1, 10_000L))
        rec.pause()
        assertFalse("no fix is accepted while paused", rec.onFix(fix(2, 20_000L)))
        rec.resume()
        rec.onFix(fix(3, 60_000L)) // anchors again after the pause
        rec.onFix(fix(4, 70_000L))
        val ride = rec.snapshot(80_000L)
        assertEquals(2 * Geo.distanceM(at(0), at(1)), ride.distanceM, 1.0)
        assertEquals(20_000L, ride.movingMs)
        assertEquals(0, ride.gaps)
    }

    @Test
    fun `a one-fix recording is still a ride, it is just not a track`() {
        // D55: the rider pressed record and went somewhere. Length is the map's problem, not the
        // recorder's - only a recording that caught no usable fix has nothing to keep.
        val rec = RideRecorder()
        rec.start(0)
        rec.onFix(fix(0, 0))
        val ride = rec.finish(1_000L)!!
        assertTrue("there is something to keep", ride.hasFixes)
        assertFalse("but nothing to draw", ride.hasTrack)
        assertEquals(1, ride.points.size)
        assertEquals(0.0, ride.distanceM, 1e-9)
    }

    @Test
    fun `a recording that caught no fix at all has nothing to keep`() {
        val rec = RideRecorder()
        rec.start(0)
        assertNull(rec.finish(1_000L))
    }

    @Test
    fun `a recording of nothing but rejected fixes has nothing to keep`() {
        val rec = RideRecorder(maxAccuracyM = 50.0)
        rec.start(0)
        for (i in 0 until 5) rec.onFix(RideFix(LatLon(47.0, -122.0), i * 1_000L, 400.0))
        assertNull(rec.finish(6_000L))
    }

    @Test
    fun `the simulator walks a shape at the requested speed`() {
        val shape = listOf(LatLon(47.0, -122.0), LatLon(47.009, -122.0)) // ~1000 m
        val fixes = TrackSimulator.along(shape, startAtMs = 0, speedMps = 5.0, intervalMs = 2_000L)
        assertEquals(101, fixes.size)
        assertEquals(0L, fixes.first().atEpochMs)
        assertEquals(2_000L, fixes[1].atEpochMs)

        val rec = RideRecorder()
        rec.start(0)
        fixes.forEach(rec::onFix)
        val ride = rec.finish(fixes.last().atEpochMs + 1_000L)!!
        val measured = fixes.zipWithNext { a, b -> Geo.distanceM(a.point, b.point) }.sum()
        assertEquals(measured, ride.distanceM, 1e-6)
        assertEquals(0, ride.gaps)
    }

    @Test
    fun `a dropout becomes a reported gap, and the distance across it is recovered`() {
        val shape = listOf(LatLon(47.0, -122.0), LatLon(47.009, -122.0))
        val fixes = TrackSimulator.along(
            shape,
            startAtMs = 0,
            speedMps = 5.0,
            intervalMs = 2_000L,
            dropoutEvery = 20,
            dropoutCount = 16,
        )
        val rec = RideRecorder()
        rec.start(0)
        fixes.forEach(rec::onFix)
        val ride = rec.finish(fixes.last().atEpochMs + 1_000L)!!
        assertTrue("a 32 s silence is a gap", ride.gaps >= 1)
        assertTrue("the chord across the gap is still measured", ride.distanceM > 800.0)
    }

    @Test
    fun `jitter alone does not invent a ride`() {
        // A parked rider wobbling in place: every fix is within the step filter (2 m apart, limit 3 m),
        // so no distance is added and no track is invented.
        val a = LatLon(47.0, -122.0)
        val b = LatLon(47.0 + 2.0 / 111_320.0, -122.0)
        val rec = RideRecorder(maxAccuracyM = 50.0, minStepM = 3.0)
        rec.start(0)
        for (i in 0 until 40) rec.onFix(RideFix(if (i % 2 == 0) a else b, i * 2_000L, 5.0))
        val ride = rec.snapshot(80_000L)
        assertEquals("no movement, so no distance", 0.0, ride.distanceM, 1e-9)
        assertEquals(1, ride.points.size)
    }
}
