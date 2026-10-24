package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LoopPolicy
import com.biketrailplanner.domain.QuietScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D31 (docs/LOOPS.md): the overlap metric, the perpendicular via fan, and the choice rule.
 *
 * These tests are about the DECISION, not the network: the planner's own routing/tracing/grading is
 * exercised against the real server by `LivePlanProbeTest`, and by `PlannerLoopTest` with a fake
 * client. What is pinned here is the arithmetic the rider's promise rests on.
 */
class LoopPolicyTest {

    /** A straight eastward corridor at 47 N, ~1.5 km long. */
    private val eastward = listOf(
        LatLon(47.0, -122.000),
        LatLon(47.0, -121.990),
        LatLon(47.0, -121.980),
    )

    // ---- the metric -------------------------------------------------------------------------

    @Test
    fun `a return that re-uses every outbound road is a full overlap`() {
        assertEquals(1.0, LoopPolicy.overlapShare(setOf(1L, 2L, 3L), setOf(1L, 2L, 3L, 4L)), 1e-9)
    }

    @Test
    fun `a return with none of the outbound roads is zero overlap`() {
        assertEquals(0.0, LoopPolicy.overlapShare(setOf(1L, 2L), setOf(3L, 4L)), 1e-9)
    }

    @Test
    fun `the share is of the OUTBOUND roads, so extra return ways do not dilute it`() {
        // 2 of 4 outbound ways come back; the return also uses 6 roads the outbound never touched.
        // The rider's question is "how much of the way out am I repeating", so 0.5 is the answer.
        assertEquals(0.5, LoopPolicy.overlapShare(setOf(1L, 2L, 3L, 4L), setOf(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)), 1e-9)
    }

    @Test
    fun `an untraced outbound reports zero rather than a meaningless full overlap`() {
        assertEquals(0.0, LoopPolicy.overlapShare(emptySet(), setOf(1L)), 1e-9)
        assertEquals(0.0, LoopPolicy.overlapShare(emptySet(), emptySet()), 1e-9)
    }

    // ---- the geometry -----------------------------------------------------------------------

    @Test
    fun `a perpendicular offset lands the requested distance to the side of the corridor`() {
        val at = LoopPolicy.perpendicularPoint(eastward, 0.5, 1336.0, LoopPolicy.SIDE_LEFT)!!
        val on = Geo.distanceM(LatLon(47.0, -121.990), at)
        // The offset is perpendicular to an east-west line, so it is purely meridional.
        assertEquals(1336.0, on, 136.0) // within 10%: the local projection, not a geodetic solve
        assertEquals(at.lon, -121.990, 1e-6)
        assertTrue("SIDE_LEFT of an eastward ride is to the north", at.lat > 47.0)
    }

    @Test
    fun `the two sides are on opposite sides of the corridor`() {
        val left = LoopPolicy.perpendicularPoint(eastward, 0.5, 1000.0, LoopPolicy.SIDE_LEFT)!!
        val right = LoopPolicy.perpendicularPoint(eastward, 0.5, 1000.0, LoopPolicy.SIDE_RIGHT)!!
        assertTrue(left.lat > 47.0)
        assertTrue(right.lat < 47.0)
        assertEquals(2000.0, Geo.distanceM(left, right), 200.0)
    }

    @Test
    fun `an offset scales with the distance asked for`() {
        val near = LoopPolicy.perpendicularPoint(eastward, 0.5, 500.0, LoopPolicy.SIDE_LEFT)!!
        val far = LoopPolicy.perpendicularPoint(eastward, 0.5, 3000.0, LoopPolicy.SIDE_LEFT)!!
        assertEquals(500.0, Geo.distanceM(LatLon(47.0, -121.990), near), 50.0)
        assertEquals(3000.0, Geo.distanceM(LatLon(47.0, -121.990), far), 300.0)
    }

    @Test
    fun `a shape too short to have a side produces no via point`() {
        assertNull(LoopPolicy.perpendicularPoint(listOf(LatLon(47.0, -122.0)), 0.5, 500.0, LoopPolicy.SIDE_LEFT))
        assertNull(
            LoopPolicy.perpendicularPoint(
                listOf(LatLon(47.0, -122.000), LatLon(47.0, -121.99995)),
                0.5, 500.0, LoopPolicy.SIDE_LEFT,
            ),
        )
    }

    @Test
    fun `the fan is both sides of every offset, closest first, and honours the cap`() {
        val fan = LoopPolicy.viaCandidates(eastward, listOf(500.0, 1336.0, 3000.0))
        assertEquals(6, fan.size)
        assertEquals(listOf(500.0, 500.0, 1336.0, 1336.0, 3000.0, 3000.0), fan.map { it.offsetM })
        assertEquals(3, fan.count { it.side == LoopPolicy.SIDE_LEFT }) // one per offset
        assertEquals(2, fan.take(3).map { it.side }.distinct().size)

        val capped = LoopPolicy.viaCandidates(eastward, listOf(500.0, 1336.0, 3000.0), limit = 4)
        assertEquals(4, capped.size)
        assertEquals(listOf(500.0, 500.0, 1336.0, 1336.0), capped.map { it.offsetM })
    }

    // ---- the choice -------------------------------------------------------------------------

    private fun via(offsetM: Double) =
        LoopPolicy.ViaCandidate(eastward[1], "$offsetM m", offsetM, LoopPolicy.SIDE_LEFT, 0.5)

    private fun candidate(
        overlap: Double,
        extraM: Double = 500.0,
        compliant: Boolean = true,
        offsetM: Double = 1000.0,
        quiet: QuietScore = QuietScore(0.0, 0.0, 0.0, 0.0, 0.0),
    ) = LoopPolicy.Candidate(
        via = via(offsetM),
        returnM = 6270.0 + extraM,
        returnWayIds = emptySet(),
        overlapShare = overlap,
        extraM = extraM,
        compliant = compliant,
        quiet = quiet,
    )

    @Test
    fun `the lowest overlap wins`() {
        val chosen = LoopPolicy.choose(
            listOf(candidate(0.58, offsetM = 500.0), candidate(0.08, offsetM = 1336.0), candidate(0.75, offsetM = 3000.0)),
            baselineOverlap = 0.92,
            maxExtraM = 5000.0,
        )
        assertEquals(0.08, chosen!!.overlapShare, 1e-9)
    }

    @Test
    fun `among safe loops the quietest way back wins, before the most different`() {
        // D50 decision C: safety, then quietness, then a different way back, then distance. Both are
        // compliant and inside budget; the quieter one has the WORSE overlap and must still win.
        val noisy = candidate(
            overlap = 0.10,
            quiet = QuietScore(totalM = 1_000.0, goodM = 300.0, smallUnknownM = 0.0, over25M = 0.0, busyM = 700.0, majorNoLaneM = 700.0),
        )
        val quiet = candidate(
            overlap = 0.40,
            quiet = QuietScore(totalM = 1_000.0, goodM = 800.0, smallUnknownM = 0.0, over25M = 0.0, busyM = 200.0, majorNoLaneM = 50.0),
        )
        val chosen = LoopPolicy.choose(listOf(noisy, quiet), baselineOverlap = 0.92, maxExtraM = 5000.0)
        assertEquals("the quieter return wins even with the worse overlap", 0.40, chosen!!.overlapShare, 1e-9)
    }

    @Test
    fun `a loop is never bought with safety`() {
        val chosen = LoopPolicy.choose(
            listOf(candidate(0.03, compliant = false), candidate(0.60, compliant = true)),
            baselineOverlap = 0.92,
            maxExtraM = 5000.0,
        )
        assertEquals(0.60, chosen!!.overlapShare, 1e-9)
        assertNull(
            "nothing compliant means ride out and back",
            LoopPolicy.choose(listOf(candidate(0.03, compliant = false)), baselineOverlap = 0.92, maxExtraM = 5000.0),
        )
    }

    @Test
    fun `the rider's five kilometre budget is a hard limit`() {
        assertNull(LoopPolicy.choose(listOf(candidate(0.05, extraM = 5001.0)), 0.92, maxExtraM = 5000.0))
        assertTrue(LoopPolicy.choose(listOf(candidate(0.05, extraM = 5000.0)), 0.92, maxExtraM = 5000.0) != null)
    }

    @Test
    fun `a return that merely shaves a little off a mirrored ride is not called a loop`() {
        // 0.86 against a 0.92 baseline is a 6% improvement: still the same way back in the rider's
        // terms, and the screen would have to claim a loop that did not happen.
        assertNull(LoopPolicy.choose(listOf(candidate(0.86)), baselineOverlap = 0.92, maxExtraM = 5000.0))
        assertTrue(LoopPolicy.choose(listOf(candidate(0.81)), baselineOverlap = 0.92, maxExtraM = 5000.0) != null)
    }

    @Test
    fun `equal overlap is broken by the shorter extra distance, so the choice is deterministic`() {
        val chosen = LoopPolicy.choose(
            listOf(candidate(0.10, extraM = 1800.0, offsetM = 3000.0), candidate(0.10, extraM = 900.0, offsetM = 500.0)),
            baselineOverlap = 0.92,
            maxExtraM = 5000.0,
        )
        assertEquals(900.0, chosen!!.extraM, 1e-9)
    }

    @Test
    fun `no candidates means no loop`() {
        assertNull(LoopPolicy.choose(emptyList(), baselineOverlap = 0.92, maxExtraM = 5000.0))
    }
}
