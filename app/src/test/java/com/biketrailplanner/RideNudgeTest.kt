package com.biketrailplanner

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideNudge
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D35 (docs/VELOTRIC-LINK.md option A, piece 3a): the on-return arrival decision. The ride is a round
 * trip and the Google Maps hand-off ends where it started, so "arrived" means back near the START
 * point. The decision is a pure function so it can be tested here, without Android or a phone.
 */
class RideNudgeTest {

    private val start = LatLon(47.2000, -122.3000)

    /** ~111 m from [start]: inside the 250 m arrival radius. */
    private val near = LatLon(47.2010, -122.3000)

    /** ~1.1 km from [start]: nowhere near the start. */
    private val far = LatLon(47.2100, -122.3000)

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun handOff(atMs: Long = t0) = RideNudge.HandOff(start, atMs)

    @Test
    fun `no hand-off means no nudge`() {
        assertNull(RideNudge.decide(handOff = null, nowEpochMs = t0 + 30 * minute, currentFix = start))
    }

    @Test
    fun `a fresh hand-off is not an arrival`() {
        assertNull(RideNudge.decide(handOff = handOff(), nowEpochMs = t0 + 5 * minute, currentFix = start))
    }

    @Test
    fun `an old hand-off while still away is not an arrival`() {
        assertNull(RideNudge.decide(handOff = handOff(), nowEpochMs = t0 + 30 * minute, currentFix = far))
    }

    @Test
    fun `an old hand-off back near the start is an arrival`() {
        val nudge = RideNudge.decide(handOff = handOff(), nowEpochMs = t0 + 30 * minute, currentFix = near)
        assertNotNull(nudge)
        assertTrue(nudge!!.awayM < RideNudge.ARRIVAL_RADIUS_M)
        assertTrue(nudge.sinceHandOffMs >= RideNudge.MIN_RIDE_MS)
    }

    @Test
    fun `a dismissed nudge stays silent`() {
        assertNull(
            RideNudge.decide(
                handOff = handOff(),
                nowEpochMs = t0 + 30 * minute,
                currentFix = near,
                dismissed = true,
            ),
        )
    }

    @Test
    fun `no fix means no nudge`() {
        assertNull(RideNudge.decide(handOff = handOff(), nowEpochMs = t0 + 30 * minute, currentFix = null))
    }

    @Test
    fun `a hand-off older than the ride window is abandoned`() {
        assertNull(
            RideNudge.decide(
                handOff = handOff(),
                nowEpochMs = t0 + RideNudge.MAX_RIDE_MS + 1,
                currentFix = near,
            ),
        )
    }
}
