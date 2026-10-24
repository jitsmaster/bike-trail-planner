package com.biketrailplanner

import com.biketrailplanner.domain.RideWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D36: the end-of-navigation watcher and its safety net. The state machine is pure so it can be
 * tested here, without Android, a notification listener, or a phone.
 */
class RideWatchTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun armed(nowMs: Long = t0) = RideWatch.arm(RideWatch.State(), nowMs)

    @Test
    fun `a fresh arm is watching`() {
        assertTrue(RideWatch.isArmed(armed(), t0))
    }

    @Test
    fun `nothing happens before a hand-off`() {
        assertNull(RideWatch.onNavigationSeen(RideWatch.State(), t0))
        assertEquals(RideWatch.Effect.None, RideWatch.onNavigationEnded(RideWatch.State(), t0))
    }

    @Test
    fun `navigation seen is recorded once`() {
        val seen = RideWatch.onNavigationSeen(armed(), t0 + minute)
        assertTrue(seen!!.sawNavigation)
        assertNull(RideWatch.onNavigationSeen(seen, t0 + 2 * minute))
    }

    @Test
    fun `a preview that never navigated does not fire`() {
        assertEquals(
            RideWatch.Effect.None,
            RideWatch.onNavigationEnded(armed(), t0 + 30 * minute),
        )
    }

    @Test
    fun `navigation ending after being seen fires and disarms`() {
        val seen = RideWatch.onNavigationSeen(armed(), t0 + minute)!!
        val effect = RideWatch.onNavigationEnded(seen, t0 + 30 * minute)
        assertTrue(effect is RideWatch.Effect.Fire)
        val state = (effect as RideWatch.Effect.Fire).state
        assertFalse(RideWatch.isArmed(state, t0 + 30 * minute))
        assertFalse(state.sawNavigation)
    }

    @Test
    fun `a watch older than the window is abandoned`() {
        val seen = RideWatch.onNavigationSeen(armed(), t0 + minute)!!
        val expired = t0 + RideWatch.ARM_WINDOW_MS + 1
        assertNull(RideWatch.onNavigationSeen(armed(), expired))
        assertEquals(RideWatch.Effect.None, RideWatch.onNavigationEnded(seen, expired))
    }

    @Test
    fun `a new arm forgets the previous navigation`() {
        val seen = RideWatch.onNavigationSeen(armed(), t0 + minute)!!
        val rearmed = RideWatch.arm(seen, t0 + 2 * minute)
        assertFalse(rearmed.sawNavigation)
    }

    @Test
    fun `disarm ends the watch`() {
        assertFalse(RideWatch.isArmed(RideWatch.disarm(), t0))
        assertEquals(RideWatch.Effect.None, RideWatch.onNavigationEnded(RideWatch.disarm(), t0))
    }

    @Test
    fun `starting a velotric ride is remembered through a hand-off`() {
        val started = RideWatch.onVelotricRideStarted(RideWatch.State(), t0)
        assertEquals(t0, started.velotricStartedAtMs)
        // Arming for the hand-off must not forget that Velotric was started.
        val rearmed = RideWatch.arm(started, t0 + minute)
        assertEquals(t0, rearmed.velotricStartedAtMs)
        assertTrue(RideWatch.isArmed(rearmed, t0 + minute))
    }

    @Test
    fun `the safety net stays quiet for a short trip`() {
        val started = RideWatch.onVelotricRideStarted(RideWatch.State(), t0)
        assertFalse(RideWatch.safetyNetReminder(started, t0 + 5 * minute))
    }

    @Test
    fun `the safety net fires when a started ride was never watched`() {
        val started = RideWatch.onVelotricRideStarted(RideWatch.State(), t0)
        assertTrue(RideWatch.safetyNetReminder(started, t0 + 20 * minute))
    }

    @Test
    fun `the safety net stays quiet when navigation was seen`() {
        val started = RideWatch.onVelotricRideStarted(RideWatch.State(), t0)
        val rearmed = RideWatch.arm(started, t0 + minute)
        val seen = RideWatch.onNavigationSeen(rearmed, t0 + 2 * minute)!!
        assertFalse(RideWatch.safetyNetReminder(seen, t0 + 20 * minute))
    }

    @Test
    fun `the safety net expires with the watch window`() {
        val started = RideWatch.onVelotricRideStarted(RideWatch.State(), t0)
        assertFalse(RideWatch.safetyNetReminder(started, t0 + RideWatch.ARM_WINDOW_MS + 1))
    }

    @Test
    fun `a reminder clears the safety net marker`() {
        val started = RideWatch.onVelotricRideStarted(RideWatch.State(), t0)
        val reminded = RideWatch.onRideReminded(started)
        assertNull(reminded.velotricStartedAtMs)
        assertFalse(RideWatch.safetyNetReminder(reminded, t0 + 20 * minute))
    }
}
