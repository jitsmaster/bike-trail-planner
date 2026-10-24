package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.MileageMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D2: the typed mileage is total round-trip miles; trail portion = target - round-trip commute. */
class MileageMathTest {

    @Test
    fun `trail portion is the target minus the round trip commute`() {
        val targetMiles = 12.0
        val commuteOneWayM = 2_824.0 // the measured home -> Interurban Edgewood access point
        val portion = MileageMath.trailPortion(targetMiles, commuteOneWayM)
        assertTrue(portion.feasible)
        val expectedTrailM = Geo.milesToMetres(targetMiles) - 2 * commuteOneWayM
        assertEquals(expectedTrailM, portion.roundTripM, 0.001)
        assertEquals(expectedTrailM / 2, portion.oneWayM, 0.001)
        assertEquals(8.49, portion.roundTripMiles, 0.01) // 12 mi - 3.51 mi of commute
    }

    @Test
    fun `round trip total adds the return commute`() {
        val total = MileageMath.roundTripTotalM(2_824.0, 12_053.0)
        assertEquals(2 * 2_824.0 + 12_053.0, total, 0.001)
    }

    @Test
    fun `a target smaller than the commute is not feasible and says why`() {
        val portion = MileageMath.trailPortion(2.0, 3_000.0)
        assertFalse(portion.feasible)
        assertTrue(portion.reason!!.contains("round-trip commute"))
        assertEquals(0.0, portion.roundTripM, 0.0)
    }

    @Test
    fun `a tiny trail portion is rejected by the minimum`() {
        val portion = MileageMath.trailPortion(4.2, 3_300.0, minTrailMiles = 0.25)
        assertFalse(portion.feasible)
    }

    @Test
    fun `miles and metres round trip consistently`() {
        assertEquals(1609.344, MileageMath.metres(1.0), 0.001)
        assertEquals(1.0, MileageMath.miles(1609.344), 0.0001)
    }
}
