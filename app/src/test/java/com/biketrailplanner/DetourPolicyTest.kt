package com.biketrailplanner

import com.biketrailplanner.domain.DetourPolicy
import com.biketrailplanner.domain.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D13 (SPEC section 7c): acceptance bands, the explicit detour, and the trail floor. */
class DetourPolicyTest {

    private val shortest = 19_312.0 // 12 mi

    @Test
    fun `a compliant route at the shortest length is acceptable`() {
        val v = DetourPolicy.evaluate(shortest, shortest, compliant = true)
        assertTrue(v.acceptable)
        assertFalse(v.explicitDetour)
        assertEquals(1.0, v.factor, 1e-9)
        assertEquals(0.0, v.band!!, 1e-9)
    }

    @Test
    fun `the 1_40 acceptance boundary is inclusive and everything above it is an explicit detour`() {
        val atBoundary = DetourPolicy.evaluate(shortest * 1.40, shortest, compliant = true)
        assertTrue("exactly 1.40x must be acceptable", atBoundary.acceptable)
        assertFalse(atBoundary.explicitDetour)

        val justOver = DetourPolicy.evaluate(shortest * 1.401, shortest, compliant = true)
        assertFalse(justOver.acceptable)
        assertTrue(justOver.explicitDetour)
        assertTrue(justOver.reason.contains("explicit detour"))

        // just past the acceptance limit the candidate falls into the +80% band, which is the
        // explicit-detour band; +40% is the last band inside the limit (asserted below)
        assertEquals(0.80, justOver.band!!, 1e-9)
    }

    @Test
    fun `the +25 and +40 percent bands are inside the limit and +80 is the explicit band`() {
        assertEquals(0.25, DetourPolicy.evaluate(shortest * 1.25, shortest, true).band!!, 1e-9)
        assertEquals(0.40, DetourPolicy.evaluate(shortest * 1.40, shortest, true).band!!, 1e-9)
        val eighty = DetourPolicy.evaluate(shortest * 1.80, shortest, true)
        assertEquals(0.80, eighty.band!!, 1e-9)
        assertTrue(eighty.explicitDetour)
    }

    @Test
    fun `the 1_80 explicit-detour limit is inclusive and beyond it the route is refused`() {
        val atLimit = DetourPolicy.evaluate(shortest * 1.80, shortest, compliant = true)
        assertTrue(atLimit.explicitDetour)

        val tooLong = DetourPolicy.evaluate(shortest * 1.81, shortest, compliant = true)
        assertTrue(tooLong.compliant)
        assertFalse(tooLong.acceptable)
        assertFalse(tooLong.explicitDetour)
        assertNull(tooLong.band)
        assertTrue(tooLong.reason.contains("past the"))
    }

    @Test
    fun `a route that breaks D10 is never acceptable, however short`() {
        val v = DetourPolicy.evaluate(shortest, shortest * 2, compliant = false)
        assertFalse(v.acceptable)
        assertFalse(v.explicitDetour)
        assertTrue(v.reason.contains("D10"))
    }

    @Test
    fun `the trail floor is two miles and it is inclusive`() {
        assertEquals(2.0, DetourPolicy.TRAIL_FLOOR_MILES, 1e-9)
        assertEquals(2 * Geo.METRES_PER_MILE, DetourPolicy.trailFloorM(), 1e-6)
        assertTrue(DetourPolicy.belowTrailFloor(Geo.milesToMetres(1.99)))
        assertFalse(DetourPolicy.belowTrailFloor(Geo.milesToMetres(2.0)))
        assertFalse(DetourPolicy.belowTrailFloor(Geo.milesToMetres(2.5)))
    }
}
