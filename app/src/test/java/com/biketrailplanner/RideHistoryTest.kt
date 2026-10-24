package com.biketrailplanner

import com.biketrailplanner.domain.InMemoryRideHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D58: history rows must not outlive the ride they describe.
 *
 * Found on a real phone: `ride_history` had four rows and `ride_track` three, and the orphan was the
 * *oldest* one so nothing broke. Delete the *newest* ride instead and it does: `lastRiddenSignature()`
 * is the most recent history row with no join, so the D6 rotation ends up told that the last ride was
 * one the rider has thrown away - and "do not repeat your last ride" quietly stops working.
 */
class RideHistoryTest {

    @Test
    fun `forgetting the newest ride hands the title to the one before it`() = runBlocking {
        val history = InMemoryRideHistory()
        history.record("older", listOf("Sumner Link Trail"), 12.0, atEpochMs = 1_000L)
        history.record("newer", listOf("Riverwalk Trail"), 8.0, atEpochMs = 5_000L)
        assertEquals("newer", history.lastRiddenSignature())

        // The rider deletes the newest ride from Past rides.
        history.forget("newer")

        assertEquals(1, history.count())
        assertEquals("older", history.lastRiddenSignature())
        assertEquals(mapOf("older" to 1_000L), history.riddenAt())
    }

    @Test
    fun `forgetting the only ride leaves no last ride at all`() = runBlocking {
        val history = InMemoryRideHistory()
        history.record("only", listOf("Sumner Link Trail"), 12.0, atEpochMs = 1_000L)
        history.forget("only")
        assertEquals(0, history.count())
        assertNull(history.lastRiddenSignature())
    }

    @Test
    fun `forgetting something that was never there is harmless`() = runBlocking {
        val history = InMemoryRideHistory()
        history.record("kept", listOf("Riverwalk Trail"), 8.0, atEpochMs = 5_000L)
        history.forget("never-recorded")
        assertEquals(1, history.count())
        assertEquals("kept", history.lastRiddenSignature())
    }
}
