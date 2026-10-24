package com.biketrailplanner

import com.biketrailplanner.domain.PlanRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D27 as the rider narrowed it on 2026-09-16: "don't directly use ai, only use it when I click on
 * Plan ride button. Don't use it up front or for existing rides."
 *
 * The gate lives on the request, so it is enforced by the planner rather than by every call site
 * remembering to behave. The default matters most: a caller that forgets the flag gets no AI at all,
 * rather than spending the rider's tokens.
 */
class AiGateTest {

    private val origin = com.biketrailplanner.domain.LatLon(47.240371, -122.262019)

    @Test
    fun `a plan request asks for no AI unless it says so`() {
        assertFalse(
            "the AI must be opt-in per plan: the default is no model call",
            PlanRequest(targetMiles = 10.0, origin = origin).allowAi,
        )
        assertTrue(
            "and the explicit button path can ask for it",
            PlanRequest(targetMiles = 10.0, origin = origin, allowAi = true).allowAi,
        )
    }
}
