package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.GradedSegment
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.QuietLinks
import com.biketrailplanner.domain.QuietRoute
import com.biketrailplanner.domain.QuietSearch
import com.biketrailplanner.domain.QuietWay
import com.biketrailplanner.domain.SpeedSource
import com.biketrailplanner.domain.VolumeBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D44: the rider's own complaint — "too fixed on the big roads… there are a lot of smaller road under
 * 25 miles it's not using at all" — turned into a number and a bounded search.
 */
class QuietRouteTest {

    private val here = LatLon(47.24, -122.26)

    private fun seg(
        grade: Grade,
        metres: Double,
        roadClass: String? = null,
        use: String? = null,
        speedMph: Int? = null,
        name: String = "A Street",
        cycleLane: String? = null,
        bicycleNetwork: Int = 0,
        shoulder: Boolean = false,
    ) = GradedSegment(
        grade = grade,
        lengthM = metres,
        names = listOf(name),
        wayIds = listOf(1L),
        roadClass = roadClass,
        use = use,
        speedMph = speedMph,
        speedSource = if (speedMph != null) SpeedSource.OSM else SpeedSource.NONE,
        reason = "test",
        cycleLane = cycleLane,
        bicycleNetwork = bicycleNetwork,
        shoulder = shoulder,
    )

    private fun way(wayId: Long, label: String = "St $wayId") = QuietWay(
        wayId = wayId,
        point = here,
        label = label,
        band = VolumeBand.UNKNOWN,
        aadt = null,
        aadtYear = null,
        lts = null,
        speedMph = null,
        speedSource = SpeedSource.NONE,
        roadClass = "residential",
        use = null,
    )

    @Test
    fun `a small street with no posted limit counts, and is counted separately`() {
        // The whole point of the rider's request: an unposted residential street is the GOOD case here,
        // but the app must be able to say how much of the ride rests on that assumption.
        val score = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "residential")))
        assertEquals(1_000.0, score.goodM, 1e-9)
        assertEquals(1_000.0, score.smallUnknownM, 1e-9)
        assertEquals(1.0, score.goodShare, 1e-9)
    }

    @Test
    fun `an unposted arterial is not good, and neither is a measured 35 mph street`() {
        val tertiary = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "tertiary")))
        assertEquals("tertiary defaults to 39 mph, so it is not a quiet street", 0.0, tertiary.goodM, 1e-9)
        val signed = QuietRoute.score(listOf(seg(Grade.D, 1_000.0, roadClass = "residential", speedMph = 35)))
        assertEquals(0.0, signed.goodM, 1e-9)
        assertEquals(1_000.0, signed.over25M, 1e-9)
        assertEquals(1_000.0, signed.busyM, 1e-9)
        assertEquals(1.0, signed.over25Share, 1e-9)
    }

    @Test
    fun `evidence grades count without anything else`() {
        val trail = QuietRoute.score(listOf(seg(Grade.A, 5_000.0, use = "cycleway")))
        assertEquals(5_000.0, trail.goodM, 1e-9)
        assertEquals("a trail is not an assumption", 0.0, trail.smallUnknownM, 1e-9)
        val quietRoad = QuietRoute.score(listOf(seg(Grade.C, 2_000.0, roadClass = "residential", speedMph = 25)))
        assertEquals(2_000.0, quietRoad.goodM, 1e-9)
        assertEquals(0.0, quietRoad.smallUnknownM, 1e-9)
    }

    @Test
    fun `the quieter of two rides wins even when it is longer`() {
        // The rider's rule: "take the longest route instead of shortest" - but only where the extra
        // distance buys quiet streets.
        val arterial = QuietRoute.score(listOf(seg(Grade.D, 3_000.0, roadClass = "tertiary", speedMph = 35)))
        val quiet = QuietRoute.score(listOf(seg(Grade.UNGRADED, 4_000.0, roadClass = "residential")))
        assertTrue(
            "2 mi of small streets beats 1.9 mi of 35 mph arterial",
            QuietRoute.quieter(quiet, arterial, 6_400.0, 6_100.0, "b", "a"),
        )
        assertFalse(
            "and the arterial never wins on being shorter",
            QuietRoute.quieter(arterial, quiet, 6_100.0, 6_400.0, "a", "b"),
        )
    }

    @Test
    fun `distance decides only when the quiet shares are the same`() {
        val a = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "residential")))
        val b = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "service")))
        assertTrue(QuietRoute.quieter(a, b, 5_000.0, 5_200.0, "sig-a", "sig-b"))
        assertFalse(QuietRoute.quieter(a, b, 5_200.0, 5_000.0, "sig-a", "sig-b"))
        // identical in every way: the signature breaks the tie, so the choice is stable
        assertTrue(QuietRoute.quieter(a, b, 5_000.0, 5_000.0, "sig-a", "sig-b"))
        assertFalse(QuietRoute.quieter(a, b, 5_000.0, 5_000.0, "sig-b", "sig-a"))
    }

    @Test
    fun `the search is bounded, deterministic and never repeats a set`() {
        val pool = (1L..8L).map { way(it) }
        val proposals = QuietSearch.propose(pool, maxVias = 2, maxEvaluations = 6)
        assertEquals(6, proposals.size)
        assertEquals("singles come first, in the pool's own order", listOf(1L, 2L, 3L), proposals.take(3).map { it.single().wayId })
        // The budget is spent on singles first: with 8 streets in the pool and a budget of 6, the run
        // never reaches a pair. A pair only appears once the singles are exhausted.
        assertTrue("no pair fits in a budget of 6 over 8 streets", proposals.none { it.size > 1 })
        val withPairs = QuietSearch.propose(pool, maxVias = 2, maxEvaluations = 9)
        assertEquals("then pairs from the best streets", listOf(1L, 2L), withPairs[8].map { it.wayId })
        val keys = proposals.map { p -> p.map { it.wayId }.sorted().joinToString("+") }
        assertEquals("no set is proposed twice", keys.size, keys.toSet().size)
        assertEquals(
            "the same pool always proposes the same order",
            keys,
            QuietSearch.propose(pool, maxVias = 2, maxEvaluations = 6).map { p -> p.map { it.wayId }.sorted().joinToString("+") },
        )
    }

    @Test
    fun `a street the route already rides is not proposed, and the budget is respected`() {
        val pool = (1L..8L).map { way(it) }
        val proposals = QuietSearch.propose(pool, maxVias = 2, maxEvaluations = 11, skipWayIds = setOf(1L, 2L))
        assertTrue("streets already on the route are skipped", proposals.none { p -> p.any { it.wayId in setOf(1L, 2L) } })
        assertTrue(proposals.size <= 11)
        assertTrue(QuietSearch.propose(pool, maxVias = 2, maxEvaluations = 0).isEmpty())
        assertTrue(QuietSearch.propose(pool, maxVias = 0, maxEvaluations = 6).isEmpty())
        assertTrue(QuietSearch.propose(emptyList(), maxVias = 2, maxEvaluations = 6).isEmpty())
    }

    @Test
    fun `the quiet threshold is the one D10 already uses`() {
        assertEquals(25, QuietRoute.QUIET_MPH)
        assertEquals(25, QuietLinks.MAX_CLASS_MPH)
        assertTrue(Geo.metresToMiles(1609.344) > 0.99)
    }

    // ---- D50: "avoid major streets without bike lane" ----------------------------------------

    @Test
    fun `an unposted major road with no bike lane is charged the objection`() {
        // Before D50 this was neutral: UNGRADED on a tertiary is neither good nor busy, so a ride made
        // mostly of these sailed through the quiet gate. It is exactly the local pathology measured in
        // docs/SERVER.md (87.4% cycle_lane=none, 83.1% tertiary).
        val arterial = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "tertiary")))
        assertEquals(0.0, arterial.goodM, 1e-9)
        assertEquals(1_000.0, arterial.majorNoLaneM, 1e-9)
        assertTrue(QuietRoute.isMajor("tertiary"))
        assertFalse(QuietRoute.isMajor("residential"))
        assertFalse(QuietRoute.isMajor("unclassified"))
    }

    @Test
    fun `a bike facility makes an unposted major road good and drops the objection`() {
        // Decision A: dedicated, separated or shared lane, a signed network, or a shoulder.
        val dedicated = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "tertiary", cycleLane = "dedicated")))
        assertEquals(1_000.0, dedicated.goodM, 1e-9)
        assertEquals(0.0, dedicated.majorNoLaneM, 1e-9)
        val network = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "secondary", bicycleNetwork = 1)))
        assertEquals(1_000.0, network.goodM, 1e-9)
        assertEquals(0.0, network.majorNoLaneM, 1e-9)
        val shared = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "tertiary", cycleLane = "shared")))
        assertEquals(1_000.0, shared.goodM, 1e-9)
        // A residential street is never a "major street", so it is never charged the objection.
        val small = QuietRoute.score(listOf(seg(Grade.UNGRADED, 1_000.0, roadClass = "residential")))
        assertEquals(0.0, small.majorNoLaneM, 1e-9)
    }

    @Test
    fun `a major the rider can legally ride at 25 mph is slow, not objectionable`() {
        // The ladder's own hole grades a 25 mph tertiary D; decision B says it is still good, and it is
        // not a "major street without a bike lane" objection.
        val slow = QuietRoute.score(listOf(seg(Grade.D, 1_000.0, roadClass = "tertiary", speedMph = 25)))
        assertEquals(1_000.0, slow.goodM, 1e-9)
        assertEquals(0.0, slow.busyM, 1e-9)
        assertEquals(0.0, slow.majorNoLaneM, 1e-9)
    }

    @Test
    fun `at an equal quiet share the ride with less major-no-lane wins, before over-25`() {
        // Both scores have a good share of 0.5, but the first must ride 1 km of unlaned tertiary and
        // the second only 0.5 km, paying for the rest with a 35 mph service road (over-25, but not a
        // major street). Decision C ranks the major-no-lane objection ABOVE the over-25 measure.
        val worseObjection = QuietRoute.score(
            listOf(
                seg(Grade.C, 1_000.0, roadClass = "residential", speedMph = 25),
                seg(Grade.UNGRADED, 1_000.0, roadClass = "tertiary"),
            ),
        )
        val betterObjection = QuietRoute.score(
            listOf(
                seg(Grade.C, 1_000.0, roadClass = "residential", speedMph = 25),
                seg(Grade.UNGRADED, 500.0, roadClass = "tertiary"),
                seg(Grade.D, 500.0, roadClass = "service", speedMph = 35),
            ),
        )
        assertEquals(0.5, worseObjection.goodShare, 1e-9)
        assertEquals(0.5, betterObjection.goodShare, 1e-9)
        assertTrue(worseObjection.majorNoLaneM > betterObjection.majorNoLaneM)
        assertTrue(QuietRoute.quieter(betterObjection, worseObjection, 5_000.0, 5_000.0, "b", "a"))
        assertFalse(QuietRoute.quieter(worseObjection, betterObjection, 5_000.0, 5_000.0, "a", "b"))
    }
}
