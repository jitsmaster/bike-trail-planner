package com.biketrailplanner

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideJournal
import com.biketrailplanner.domain.RideRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D62: a ride can last hours, and the rider asked for it to survive that long.
 *
 * The old 6-hour cap is gone, so nothing may quietly truncate a long ride: not the recorder, not the
 * journal. These run the real thing over four hours of fixes - the shape a multi-hour trip actually has.
 */
class RideLongRideTest {

    private val start = 1_792_449_196_905L

    /** 7,200 fixes at one every 2 s, about 8 m apart: four hours and roughly 52 km. */
    private fun fourHours(): List<RideFix> = (0 until 7_200).map { i ->
        RideFix(
            point = LatLon(47.24 + i * 0.00007, -122.262),
            atEpochMs = start + i * 2_000L,
            accuracyM = 5.0,
            speedMps = 3.5,
        )
    }

    @Test
    fun `a four hour ride is recorded in full, with no time cap`() {
        val fixes = fourHours()
        val recorder = RideRecorder()
        recorder.start(fixes.first().atEpochMs)
        fixes.forEach(recorder::onFix)
        val ride = recorder.finish(fixes.last().atEpochMs)

        requireNotNull(ride)
        assertEquals("every fix is kept", 7_200, ride.points.size)
        assertTrue("four hours of travel is kept", ride.distanceM > 50_000.0)
        assertEquals("no gaps in a continuous stream", 0, ride.gaps)
    }

    @Test
    fun `four hours of fixes fit the journal with room to spare`() {
        val fixes = fourHours()
        val lines = fixes.map(RideJournal::encode)
        val decoded = RideJournal.decodeAll(lines)

        assertEquals(fixes.size, decoded.size)
        assertEquals("the first fix survives", fixes.first(), decoded.first())
        assertEquals("the last fix survives", fixes.last(), decoded.last())
        assertTrue(
            "four hours is ~0.5 MB, far inside the 8 MB journal cap",
            lines.sumOf { it.length.toLong() } < 8L * 1024L * 1024L,
        )
    }
}
