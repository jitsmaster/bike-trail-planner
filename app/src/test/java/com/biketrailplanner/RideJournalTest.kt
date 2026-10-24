package com.biketrailplanner

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideJournalTest {

    private val fix = RideFix(
        point = LatLon(47.24043489, -122.26194572),
        atEpochMs = 1_792_449_196_905L,
        accuracyM = 6.0,
        speedMps = 4.25,
    )

    @Test
    fun `a fix survives a round trip exactly`() {
        assertEquals(fix, RideJournal.decode(RideJournal.encode(fix)))
    }

    @Test
    fun `a fix without a speed keeps its null instead of inventing a zero`() {
        val noSpeed = fix.copy(speedMps = null)
        val decoded = RideJournal.decode(RideJournal.encode(noSpeed))
        assertEquals(noSpeed, decoded)
        assertNull("a missing speed must not become 0.0 m/s", decoded?.speedMps)
    }

    @Test
    fun `a truncated last line costs one fix, not the journal`() {
        val lines = listOf(
            RideJournal.encode(fix),
            RideJournal.encode(fix.copy(atEpochMs = 1_792_449_198_905L)),
            "47.2404,-122.26", // the write that was cut off by a kill
        )
        val decoded = RideJournal.decodeAll(lines)
        assertEquals("the two complete fixes survive", 2, decoded.size)
        assertEquals(1_792_449_198_905L, decoded.last().atEpochMs)
    }

    @Test
    fun `unparseable lines are skipped`() {
        assertEquals(0, RideJournal.decodeAll(listOf("", "garbage", "1,2,3")).size)
        assertEquals(1, RideJournal.decodeAll(listOf("garbage", RideJournal.encode(fix))).size)
    }

    @Test
    fun `negative coordinates keep their sign`() {
        val southWest = fix.copy(point = LatLon(-33.8688, -151.2093))
        assertEquals(southWest, RideJournal.decode(RideJournal.encode(southWest)))
    }
}
