package com.biketrailplanner

import com.biketrailplanner.data.RideRecordingStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D57: the rule that stops the app claiming it is recording a ride when nothing is.
 *
 * Measured on a phone (2026-10-11): the persisted flag said `active`, no `RideRecordingService` was in
 * `dumpsys activity services`, and the recording banner was up. The rider believes the ride is being
 * logged, and no ride is ever saved.
 */
class RideRecordingStoreTest {

    private fun state(active: Boolean) =
        RideRecordingStore.State(active = active, startedAtMs = if (active) 1_000L else null, paused = false)

    @Test
    fun `a flag with a live service is a real recording`() {
        assertTrue(RideRecordingStore.isReallyRecording(state(active = true), serviceRunning = true))
    }

    @Test
    fun `a flag with no live service is a dead recording, not a recording`() {
        // The process was killed mid-ride: the flag survived, the service did not.
        assertFalse(RideRecordingStore.isReallyRecording(state(active = true), serviceRunning = false))
    }

    @Test
    fun `a live service with no flag is not a recording either`() {
        assertFalse(RideRecordingStore.isReallyRecording(state(active = false), serviceRunning = true))
        assertFalse(RideRecordingStore.isReallyRecording(state(active = false), serviceRunning = false))
    }
}
