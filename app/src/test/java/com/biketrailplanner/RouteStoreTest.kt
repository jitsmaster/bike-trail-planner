package com.biketrailplanner

import com.biketrailplanner.domain.DataFingerprint
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.InMemoryRouteStore
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.NamedSegment
import com.biketrailplanner.domain.PlanPolicy
import com.biketrailplanner.domain.ReusableRoute
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.domain.RouteOverlap
import com.biketrailplanner.domain.RouteStore
import com.biketrailplanner.domain.SavedRouteCodec
import com.biketrailplanner.ui.asPlannedRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved-trip store (docs/ROUTE-STORE.md): what a saved trip keeps, how a request finds it, when
 * the app offers it instead of planning, and when a newly planned route is too much of a repeat.
 */
class RouteStoreTest {

    private val home = LatLon(47.240371, -122.262019)

    /** ~100 m north: inside the 250 m circle. */
    private val jittered = LatLon(47.241271, -122.262019)

    /** ~515 m north: same 1 km bucket, outside the circle. */
    private val elsewhere = LatLon(47.245000, -122.262019)

    private fun segment(grade: Grade, metres: Double, wayIds: List<Long>) =
        Fixtures.segment(grade, metres).copy(wayIds = wayIds)

    /** A planned route whose road identity is exactly [roadWayIds]. */
    private fun route(signature: String, roadWayIds: List<Long>, targetMiles: Double = 10.0) =
        Fixtures.plannedRoute().copy(
            signature = signature,
            targetMiles = targetMiles,
            segments = listOf(segment(Grade.C, 1000.0, roadWayIds), segment(Grade.A, 2000.0, listOf(900L))),
            roadSegments = listOf(segment(Grade.C, 1000.0, roadWayIds)),
        )

    /** Runs [block] against a fresh in-memory store. Returns Unit so JUnit accepts the test. */
    private fun store(block: suspend RouteStore.() -> Unit): Unit = runBlocking {
        val store = InMemoryRouteStore()
        store.block()
    }

    private suspend fun RouteStore.saveAll(vararg routes: Pair<String, List<Long>>, origin: LatLon = home, miles: Double = 10.0) {
        routes.forEachIndexed { index, (signature, ways) ->
            save(route(signature, ways, miles), origin, miles, emptyList(), FINGERPRINT, 1_000L + index)
        }
    }

    private val FINGERPRINT = "fp-current"

    // ---- what a saved trip keeps ---------------------------------------------------------------

    @Test
    fun `a saved trip keeps its geometry, its summary and its road identity`() = store {
        val original = route("sig-1", listOf(11L, 12L)).copy(
            named = listOf(NamedSegment("18th Street East", 1234.0, Grade.C, "tertiary", "25 mph")),
        )
        val record = SavedRouteCodec.encode(original, home, 10.0, listOf(home, original.turnaround), FINGERPRINT, 5L)
        val back = SavedRouteCodec.decode(record, FINGERPRINT)

        assertEquals(original.signature, back.signature)
        assertEquals(original.roundTrip.size, back.points.size)
        assertEquals(original.roundTrip.first().lat, back.points.first().lat, 1e-6)
        assertEquals(original.totalM, back.totalM, 1e-6)
        assertEquals(original.trailRoundTripM, back.trailRoundM, 1e-6)
        assertEquals(setOf(11L, 12L, 900L), back.wayIds)
        assertEquals(listOf(home, original.turnaround), back.waypoints)
        assertEquals("18th Street East", back.named.single().name)
        assertEquals(original.accessPoint.osmNodeId, back.accessNode)
        // The verdict is no longer stored: it is re-derived on read from the stored breakdown by the
        // same rule the planner uses, so a saved trip can never carry an older policy's answer.
        assertEquals(original.verdict.outcome, back.asPlannedRoute().verdict.outcome)
    }

    @Test
    fun `a saved trip is small enough to keep hundreds of them`() {
        val record = SavedRouteCodec.encode(route("sig-size", listOf(11L, 12L)), home, 10.0, emptyList(), FINGERPRINT, 5L)
        // docs/ROUTE-STORE.md section 1 claims single-digit KB; the cap is 500 rows.
        assertTrue("payload was ${record.payload.size} bytes", record.payload.size < 12_000)
    }

    // ---- finding it again ----------------------------------------------------------------------

    @Test
    fun `a request finds the trip saved near it and only near it`() = store {
        saveAll("sig-1" to listOf(11L))
        assertEquals(1, matching(home, 10.0).size)
        assertEquals("GPS jitter must not lose the saved trip", 1, matching(jittered, 10.0).size)
        assertEquals(1, matching(LatLon(47.241271, -122.262100), 10.0).size)
        assertTrue("515 m away is a different start point", matching(elsewhere, 10.0).isEmpty())
        assertTrue("a different mileage is a different request", matching(home, 12.0).isEmpty())
    }

    @Test
    fun `only compliant options are worth saving and a saved trip reports its measured share`() = store {
        val withUngraded = route("sig-ungraded", listOf(11L)).copy(
            breakdown = Fixtures.breakdown(Grade.C to 1000.0, Grade.UNGRADED to 3000.0),
        )
        save(withUngraded, home, 10.0, emptyList(), FINGERPRINT, 1L)
        val back = matching(home, 10.0).single()
        // the shares are of the GRADED total, exactly as the live summary card reports them
        assertEquals(1000.0 / 4000.0, back.measuredShare, 1e-9)
        assertEquals(3000.0 / 4000.0, back.ungradedShare, 1e-9)
    }

    // ---- when to suggest instead of planning ---------------------------------------------------

    @Test
    fun `two saved trips for this request are not enough to suggest instead of planning`() = store {
        saveAll("sig-1" to listOf(11L), "sig-2" to listOf(21L))
        val matches = matching(home, 10.0)
        assertEquals(2, matches.size)
        assertEquals(PlanPolicy.Decision.PlanNew, PlanPolicy.decide(matches))
    }

    @Test
    fun `more than two saved trips for this request are suggested, newest used first, at most five`() = store {
        // seven saved, so the cap is actually exercised rather than merely not reached
        saveAll(
            *Array(7) { i -> "sig-${i + 1}" to listOf((i + 1).toLong() * 10 + 1) },
        )
        markUsed("sig-1", 9_000L)
        val decision = PlanPolicy.decide(matching(home, 10.0))
        assertTrue(decision is PlanPolicy.Decision.Suggest)
        val suggested = (decision as PlanPolicy.Decision.Suggest).routes
        assertEquals("the rider wants up to five recent trips on screen", 5, suggested.size)
        assertEquals("the most recently used comes first", "sig-1", suggested.first().signature)
    }

    @Test
    fun `the suggestion cap is the rider's number, not a hardcoded one`() = store {
        saveAll(*Array(7) { i -> "sig-${i + 1}" to listOf((i + 1).toLong() * 10 + 1) })
        val matches = matching(home, 10.0)
        assertEquals(7, matches.size)
        assertEquals(5, (PlanPolicy.decide(matches) as PlanPolicy.Decision.Suggest).routes.size)
        assertEquals(2, (PlanPolicy.decide(matches, maxSuggestions = 2) as PlanPolicy.Decision.Suggest).routes.size)
        assertEquals(PlanPolicy.DEFAULT_MAX_SUGGESTIONS, 5)
    }

    @Test
    fun `saved trips for OTHER requests never stop a new plan`() = store {
        // three rides saved, but none of them starts where the rider is now
        saveAll("sig-1" to listOf(11L), "sig-2" to listOf(21L), "sig-3" to listOf(31L))
        assertTrue(matching(elsewhere, 10.0).isEmpty())
        assertEquals(PlanPolicy.Decision.PlanNew, PlanPolicy.decide(matching(elsewhere, 10.0)))

        // and three saved for this place, but at a different mileage, also do not count
        assertEquals(PlanPolicy.Decision.PlanNew, PlanPolicy.decide(matching(home, 25.0)))
    }

    // ---- the overlap rule ----------------------------------------------------------------------

    @Test
    fun `a new route may share roads but may not be made only of a saved route`() {
        val saved = listOf(setOf(11L, 12L, 13L))
        assertTrue("100% of an old route is not allowed", RouteOverlap.fullyRepeats(setOf(11L, 12L), saved))
        assertFalse("adding one new road is enough", RouteOverlap.fullyRepeats(setOf(11L, 12L, 99L), saved))
        assertFalse("a disjoint route is fine", RouteOverlap.fullyRepeats(setOf(77L), saved))
    }

    @Test
    fun `novelty is measured against the most similar saved trip`() {
        val saved = listOf(setOf(11L, 12L, 13L), setOf(80L, 81L))
        assertEquals(1.0 / 3.0, RouteOverlap.noveltyVsMostSimilar(setOf(11L, 12L, 99L), saved), 1e-9)
        assertEquals(0.0, RouteOverlap.noveltyVsMostSimilar(setOf(11L, 12L), saved), 1e-9)
        assertEquals(1.0, RouteOverlap.noveltyVsMostSimilar(setOf(50L, 51L), saved), 1e-9)
        assertEquals(1.0, RouteOverlap.noveltyVsMostSimilar(setOf(50L), emptyList()), 1e-9)
    }

    @Test
    fun `the overlap test can skip saved trips that cannot contain the new one`() = store {
        // saved-1 has 2 road ways, saved-2 has 5. A new route with 4 ways can only be inside saved-2.
        saveAll("saved-1" to listOf(11L, 12L), "saved-2" to listOf(11L, 12L, 13L, 14L, 15L))
        val context = overlapContext(home, 10.0, newWayCount = 4)
        assertEquals("only the row with at least 4 ways can contain a 4-way route", 1, context.size)
        assertTrue(context.single().containsAll(listOf(11L, 12L, 13L, 14L)))
    }

    // ---- upkeep --------------------------------------------------------------------------------

    @Test
    fun `re-saving the same ride keeps when it was first planned and how often it was used`() = store {
        save(route("sig-1", listOf(11L)), home, 10.0, emptyList(), FINGERPRINT, 1_000L)
        markUsed("sig-1", 5_000L)
        save(route("sig-1", listOf(11L)), home, 10.0, emptyList(), FINGERPRINT, 9_000L)
        val back = matching(home, 10.0).single()
        assertEquals(1_000L, back.createdAt)
        assertEquals(5_000L, back.lastUsedAt)
        assertEquals(1, back.usedCount)
        assertEquals(1, count())
    }

    @Test
    fun `eviction keeps the most recently used trips`() = store {
        (1..6).forEach { i ->
            save(route("sig-$i", listOf(i.toLong() + 100)), home, 10.0, emptyList(), FINGERPRINT, i * 100L)
        }
        markUsed("sig-1", 10_000L)
        // re-save to trigger the trim with a per-key cap of 3
        save(route("sig-7", listOf(700L)), home, 10.0, emptyList(), FINGERPRINT, 11_000L, perKeyCap = 3, globalCap = 100)
        val kept = matching(home, 10.0).map { it.signature }.toSet()
        assertEquals(3, kept.size)
        assertTrue("the most recently used must survive", "sig-1" in kept)
        assertTrue("the newest must survive", "sig-7" in kept)
        assertFalse("the least recently used must go", "sig-2" in kept)
    }

    @Test
    fun `a changed safety fingerprint marks a saved trip stale instead of hiding it`() = store {
        save(route("sig-1", listOf(11L)), home, 10.0, emptyList(), "fp-old", 1_000L)
        val fresh = matching(home, 10.0, currentFingerprint = "fp-old").single()
        val stale = matching(home, 10.0, currentFingerprint = "fp-new").single()
        assertFalse(fresh.safetyStale)
        assertTrue("the geometry is still usable, so it must still be found", stale.safetyStale)
        assertEquals(fresh.signature, stale.signature)
    }

    @Test
    fun `the fingerprint changes when any safety asset changes`() {
        val a = DataFingerprint.of(listOf("""{"a":1}""", """{"b":2}"""))
        assertNotEquals(a, DataFingerprint.of(listOf("""{"a":1}""", """{"b":3}""")))
        assertEquals(a, DataFingerprint.of(listOf("""{"a":1}""", """{"b":2}""")))
        assertTrue(DataFingerprint.ASSETS.containsAll(listOf("traffic.json", "limits.json", "trails.json")))
    }

    // ---- what the store must never do -----------------------------------------------------------

    @Test
    fun `a saved trip from another start area is never returned`() = store {
        save(route("sig-far", listOf(11L)), elsewhere, 10.0, emptyList(), FINGERPRINT, 1_000L)
        assertTrue(matching(home, 10.0).isEmpty())
        assertEquals(1, matching(elsewhere, 10.0).size)
    }

    @Test
    fun `a route stored under one mileage is not offered for another`() = store {
        save(route("sig-10", listOf(11L), targetMiles = 10.0), home, 10.0, emptyList(), FINGERPRINT, 1_000L)
        save(route("sig-20", listOf(21L), targetMiles = 20.0), home, 20.0, emptyList(), FINGERPRINT, 2_000L)
        assertEquals("sig-10", matching(home, 10.0).single().signature)
        assertEquals("sig-20", matching(home, 20.0).single().signature)
        assertEquals(RouteKey.bucket(home), RouteKey.bucket(jittered))
    }

    @Test
    fun `a decoded geometry keeps every point, not just the ends`() {
        // guards against a future "optimisation" that stores only the first and last point
        val round = route("sig-geo", listOf(11L))
        val decoded = SavedRouteCodec.decode(
            SavedRouteCodec.encode(round, home, 10.0, emptyList(), FINGERPRINT, 0L),
        )
        assertEquals(round.roundTrip.size, decoded.points.size)
        round.roundTrip.zip(decoded.points).forEach { (before, after) ->
            assertEquals(before.lat, after.lat, 1e-6)
            assertEquals(before.lon, after.lon, 1e-6)
        }
        val before = com.biketrailplanner.domain.Polyline(round.roundTrip).lengthM
        val after = com.biketrailplanner.domain.Polyline(decoded.points).lengthM
        assertEquals("the polyline must survive the round trip", before, after, 0.01)
    }
}
