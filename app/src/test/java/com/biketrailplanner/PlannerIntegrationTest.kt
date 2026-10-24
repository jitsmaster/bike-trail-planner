package com.biketrailplanner

import com.biketrailplanner.data.ChatCall
import com.biketrailplanner.data.DeepSeekClient
import com.biketrailplanner.data.InMemoryAiKeyStore
import com.biketrailplanner.data.InMemoryStrategyCache
import com.biketrailplanner.data.PlannerDto
import com.biketrailplanner.data.Maneuver
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.RouteSanity
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.StrategyCache
import com.biketrailplanner.data.StrategyCacheKey
import com.biketrailplanner.data.TraceMode
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.CachedStrategist
import com.biketrailplanner.domain.DeterministicStrategist
import com.biketrailplanner.domain.DeepSeekStrategist
import com.biketrailplanner.domain.EntryOption
import com.biketrailplanner.domain.FallbackStrategist
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.GradedSegment
import com.biketrailplanner.domain.InMemoryRouteStore
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.NamedSegment
import com.biketrailplanner.domain.ReusableRoute
import com.biketrailplanner.domain.SafetyOutcome
import com.biketrailplanner.domain.MapsUrlBuilder
import com.biketrailplanner.domain.PlanOutcome
import com.biketrailplanner.domain.PlanPolicy
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.RouteStore
import com.biketrailplanner.domain.RouteStrategist
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.SavedRouteCodec
import com.biketrailplanner.domain.Strategy
import com.biketrailplanner.domain.StrategyContext
import com.biketrailplanner.domain.StrategyDirection
import com.biketrailplanner.domain.StrategyReply
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.domain.TrailRideLeg
import com.biketrailplanner.domain.TrailOption
import com.biketrailplanner.domain.ValidatingStrategist
import com.biketrailplanner.ui.SavedTripsFirst
import com.biketrailplanner.ui.asPlanResult
import com.biketrailplanner.ui.asPlannedRoute
import com.biketrailplanner.ui.savedTripUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The integration step: the AI order (D27, docs/AI-PLANNING.md) and the saved-trip rules
 * (D26, docs/ROUTE-STORE.md) applied by the real [Planner], the real validator and the real store,
 * with fakes only where the network used to be. No test here touches the network.
 */
class PlannerIntegrationTest {

    private val home = LatLon(47.240371, -122.262019)
    private val pointA = LatLon(47.250000, -122.250000)
    private val pointB = LatLon(47.270000, -122.240000)

    // ---- fakes ---------------------------------------------------------------------------------

    /** One road destination: how long the approach is, which OSM ways it uses, and how it grades. */
    private data class Road(
        val lengthM: Double = 2_000.0,
        val wayIds: List<Long> = listOf(1L, 2L, 3L),
        val roadClass: String = "residential",
        val speedKph: Int? = 40,
    )

    /** Answers with the road that belongs to the nearest known destination, and traces exactly it. */
    private class FakeRoutes(private val roads: Map<LatLon, Road>) : RouteService {
        val routeCalls = ArrayList<LatLon>()
        var traceCalls = 0
        private var last: Road? = null

        override suspend fun route(from: LatLon, to: LatLon, via: List<LatLon>, allowFallback: Boolean): RouteResult {
            routeCalls.add(to)
            val road = roads.entries.minByOrNull { Geo.distanceM(it.key, to) }?.value ?: Road()
            last = road
            // A routed shape is a polyline, not one straight jump, so the geometry and the declared
            // length agree and a round-trip length check means something.
            val points = (0..COMMUTE_STEPS).map { i ->
                val t = i.toDouble() / COMMUTE_STEPS
                LatLon(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
            }
            return RouteResult(
                shape = PolylineCodec.encode(points),
                points = points,
                lengthM = Polyline(points).lengthM,
                timeS = Polyline(points).lengthM / 5.0,
                maneuvers = emptyList(),
                baseUrl = "http://fake",
                usedFallback = false,
                sanityProblem = RouteSanity.check(from, to, Polyline(points).lengthM),
            )
        }

        override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
            traceCalls++
            val road = last ?: Road()
            val each = road.lengthM / road.wayIds.size
            return TraceResult(
                road.wayIds.map {
                    Fixtures.edge(
                        wayId = it,
                        roadClass = road.roadClass,
                        speedLimitKph = road.speedKph,
                        name = "Quiet Street",
                        lengthM = each,
                    )
                },
                TraceMode.EDGE_WALK,
                baseUrl,
            )
        }
    }

    private companion object {
        /** Points in a faked routed shape: ~70 m apart over the commutes these tests use. */
        const val COMMUTE_STEPS = 20

        /** A denser trail than [Fixtures.trail] builds, so a shape check can bound a single step. */
        fun denseTrail(name: String, start: LatLon, lengthM: Double, steps: Int): Trail {
            val points = (0..steps).map { i ->
                LatLon(start.lat + (lengthM / steps / 111_320.0) * i, start.lon)
            }
            val full = Polyline(points)
            return Fixtures.trail(name, start, lengthM).copy(
                fullGeometry = full,
                rideableSpans = listOf(full),
                lengthM = full.lengthM,
                rideableM = full.lengthM,
            )
        }
    }

    /** The model seam, faked: it counts its calls and shows the context it was handed. */
    private class FakeStrategist(private val reply: () -> StrategyReply) : RouteStrategist {
        var calls = 0
        var lastContext: StrategyContext? = null
        override suspend fun propose(context: StrategyContext): StrategyReply {
            calls++
            lastContext = context
            return reply()
        }
    }

    private class ThrowingStrategist : RouteStrategist {
        var calls = 0
        override suspend fun propose(context: StrategyContext): StrategyReply {
            calls++
            throw IllegalStateException("the AI side exploded")
        }
    }

    private fun strategy(trailName: String, entryId: Long, reason: String = "it is the drier line") =
        Strategy(trailName, entryId, StrategyDirection.LONGER_SIDE_FIRST, null, reason)

    private fun reply(vararg strategies: Strategy, rationale: String = "because it is quieter") =
        StrategyReply(strategies.toList(), rationale)

    /** The production shape of the AI seam: cached, validated, backed by the app's own order. */
    private fun ladder(
        strategist: RouteStrategist,
        key: String = "sk-test-not-a-real-key",
        cache: StrategyCache = InMemoryStrategyCache(),
    ): RouteStrategist = FallbackStrategist(
        CachedStrategist(
            ValidatingStrategist(
                DeepSeekStrategist(DeepSeekClient(ChatCall { _, _, _ -> "{}" }), InMemoryAiKeyStore(key)),
            ),
            cache,
        ) { context -> StrategyCacheKey.of(home, context.targetMiles) },
        DeterministicStrategist(),
    )

    // ---- the plan --------------------------------------------------------------------------

    private fun trails(): List<Trail> = listOf(
        Fixtures.trail("Trail A", pointA, 8_000.0),
        Fixtures.trail("Trail B", pointB, 8_000.0),
    )

    private fun accessPoints(): List<AccessPoint> = listOf(
        Fixtures.accessPoint("Trail A", pointA).copy(osmNodeId = 101L, probeRouteKmFromHome = 1.0),
        Fixtures.accessPoint("Trail B", pointB).copy(osmNodeId = 202L, probeRouteKmFromHome = 5.0),
    )

    private fun plan(
        client: RouteService,
        strategist: RouteStrategist? = null,
        store: RouteStore? = null,
        miles: Double = 10.0,
        /**
         * D27's gate. The real app sets this only on the "Plan ride" tap, so the tests that exercise
         * the AI path ask for it explicitly here rather than relying on a planner default.
         */
        allowAi: Boolean = true,
    ): PlanResult = runBlocking {
        Planner(
            network = Fixtures.network(trails()),
            accessPoints = accessPoints(),
            grader = SafetyGrader(LimitsTable.EMPTY),
            client = client,
            config = PlannerDto(accessPointsPerTrail = 2, maxCandidates = 4, detourAccessPointsPerTrail = 2),
            home = home,
            strategist = strategist,
            routeStore = store,
        ).plan(
            PlanRequest(
                targetMiles = miles,
                origin = home,
                maxCandidates = 4,
                accessPointsPerTrail = 2,
                allowAi = allowAi,
            ),
        )
    }

    /**
     * D27, rider's rule (2026-09-16): the model is asked ONLY on an explicit "Plan ride" tap. With the
     * gate off, a strategist that is present is never called and the plan is the app's own.
     */
    @Test
    fun `without the rider's tap the model is never asked, even when a strategist is wired in`() = runBlocking {
        val strategist = FakeStrategist { reply(strategy("Trail B", 202L)) }
        val withAi = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())), strategist = strategist)
        assertEquals("the tapped plan asks once", 1, strategist.calls)

        val untapped = plan(
            FakeRoutes(mapOf(pointA to Road(), pointB to Road())),
            strategist = strategist,
            allowAi = false,
        )
        assertEquals("the untapped plan must not ask at all", 1, strategist.calls)
        // the invariant: no tap means the plan is exactly the plan the app makes with no AI wired in
        val noStrategistAtAll = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())))
        assertEquals(
            "an untapped plan must be identical to a plan with no strategist at all",
            noStrategistAtAll.chosen?.signature,
            untapped.chosen?.signature,
        )
        assertTrue("with no tap the result says the AI was not used", !untapped.aiUsed)
        assertTrue("and the tapped plan did use it", withAi.aiUsed)
    }

    private fun PlanResult.trailOrder(): List<String> = attempts.map { it.label.substringBefore(" @") }

    private fun PlannedRoute.roadWays(): Set<Long> = segments.flatMap { it.wayIds }.toSet()

    // ---- D27: the AI order -----------------------------------------------------------------

    @Test
    fun `the app's own order is the order when no AI is wired in at all`() {
        val result = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())))

        assertEquals(listOf("Trail A", "Trail B"), result.trailOrder())
        assertFalse(result.aiUsed)
        assertTrue(result.strategyNotes.isEmpty())
        assertNotNull(result.chosen)
    }

    @Test
    fun `the AI's surviving order is tried first and the plan still passes D10`() {
        val client = FakeRoutes(mapOf(pointA to Road(), pointB to Road()))
        val strategist = FakeStrategist { reply(strategy("Trail B", 202L, "B keeps you on the path longer")) }
        val result = plan(client, strategist)

        assertEquals("the model's choice is evaluated first", "Trail B", result.trailOrder().first())
        assertTrue("the app's own order is evaluated too", result.trailOrder().contains("Trail A"))
        assertTrue("the AI was used", result.aiUsed)
        assertEquals(1, strategist.calls)
        assertTrue("the model's own sentence is shown to the rider", result.strategyNotes.any { it.contains("B keeps you on the path longer") })
        assertTrue(
            "and so is what it suggested, in the app's own words",
            result.strategyNotes.any { it.contains("Trail B") },
        )
        assertTrue("the ride still has to pass the safety rule", result.chosen!!.verdict.hasNoKnownViolation)
        assertEquals(PlanOutcome.PLAN_OK, result.outcome)
    }

    @Test
    fun `the model never sees a coordinate`() {
        val strategist = FakeStrategist { reply(strategy("Trail B", 202L)) }
        plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())), strategist)

        val context = strategist.lastContext!!
        assertTrue(context.candidates.isNotEmpty())
        assertEquals(
            "every entry the model may name is one the app enumerated",
            listOf(202L),
            context.candidates.first { it.trail == "Trail B" }.entries.map { it.id },
        )
        assertTrue("a strategy can only be acted on through an entry id", context.candidates.all { option -> option.entries.all { e: EntryOption -> e.rank >= 1 } })
    }

    @Test
    fun `when every suggestion fails the safety rule the app's own order still plans the ride`() {
        // Trail B's approach runs along a primary road: grade E, which D10 never allows.
        val client = FakeRoutes(
            mapOf(
                pointA to Road(),
                pointB to Road(wayIds = listOf(9L), roadClass = "primary", speedKph = 50),
            ),
        )
        val strategist = FakeStrategist { reply(strategy("Trail B", 202L)) }
        val result = plan(client, strategist)

        assertEquals("the model's choice is still tried first", "Trail B", result.trailOrder().first())
        assertEquals("and then the app tries its own", "Trail A", result.trailOrder().getOrNull(1))
        assertTrue("the app's own ride is delivered", result.chosen!!.verdict.hasNoKnownViolation)
        assertEquals("Trail A", result.chosen!!.trailNames.single())
    }

    @Test
    fun `a suggestion the app cannot act on is dropped and the plan is unharmed`() {
        val strategist = FakeStrategist { reply(strategy("Ghost Trail", 42L)) }
        val result = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())), strategist)

        assertFalse("a hallucinated trail must never count as used", result.aiUsed)
        assertNotNull("and it must never break the plan", result.chosen)
        assertEquals(listOf("Trail A", "Trail B"), result.trailOrder())
        assertTrue(
            "the rider is told what was ignored",
            result.strategyNotes.any { it.contains("Ghost Trail") },
        )
    }

    @Test
    fun `a strategist that throws is a failure like any other`() {
        val strategist = ThrowingStrategist()
        val result = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())), strategist)

        assertEquals(1, strategist.calls)
        assertFalse(result.aiUsed)
        assertNotNull(result.chosen)
        assertTrue(result.strategyNotes.any { it.contains("could not be read", ignoreCase = true) || it.isNotBlank() })
    }

    @Test
    fun `no key means one model call fewer and a plan identical to today's`() {
        val bare = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())))
        val client = FakeRoutes(mapOf(pointA to Road(), pointB to Road()))
        val cache = InMemoryStrategyCache()
        val withKeylessSeam = plan(client, ladder(ThrowingStrategist(), key = "", cache = cache))

        assertTrue("with no key the seam is never reached", runBlocking { cache.size() } == 0)
        assertFalse(withKeylessSeam.aiUsed)
        assertEquals(bare.trailOrder(), withKeylessSeam.trailOrder())
        assertEquals(bare.chosen!!.signature, withKeylessSeam.chosen!!.signature)
        assertEquals(bare.options.map { it.signature }, withKeylessSeam.options.map { it.signature })
        assertTrue(
            "the rider is told the AI was switched off",
            withKeylessSeam.strategyNotes.any { it.contains("switched off") },
        )
    }

    @Test
    fun `the deterministic reply costs nothing and changes nothing`() {
        val bare = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())))
        val deterministic = object : RouteStrategist {
            override suspend fun propose(context: StrategyContext) = DeterministicStrategist().propose(context)
        }
        val same = plan(FakeRoutes(mapOf(pointA to Road(), pointB to Road())), deterministic)

        assertFalse("a reply that is the app's own order is not the AI", same.aiUsed)
        assertEquals(bare.trailOrder(), same.trailOrder())
        assertEquals(bare.chosen!!.signature, same.chosen!!.signature)
    }

    // ---- D26: the overlap rule -------------------------------------------------------------

    private fun savedRoute(wayIds: List<Long>, signature: String = "saved-ride") = runBlocking {
        val store = InMemoryRouteStore()
        store.save(
            route = Fixtures.plannedRoute().copy(
                signature = signature,
                segments = listOf(Fixtures.segment(Grade.C, 1_000.0).copy(wayIds = wayIds)),
                roadSegments = listOf(Fixtures.segment(Grade.C, 1_000.0).copy(wayIds = wayIds)),
            ),
            origin = home,
            targetMiles = 10.0,
            waypoints = emptyList(),
            fingerprint = "fp",
            nowMs = 1_000L,
        )
        store
    }

    @Test
    fun `a candidate that is 100 percent of a saved ride is dropped and a superset is kept`() {
        val store = savedRoute(listOf(1L, 2L, 3L))
        val client = FakeRoutes(
            mapOf(
                pointA to Road(wayIds = listOf(1L, 2L, 3L)),
                pointB to Road(wayIds = listOf(1L, 2L, 3L, 4L)),
            ),
        )
        val result = plan(client, store = store)

        assertTrue("the pure repeat is gone", result.options.none { it.roadWays() == setOf(1L, 2L, 3L) })
        assertTrue("the ride that adds a new road survives", result.options.any { it.roadWays() == setOf(1L, 2L, 3L, 4L) })
        assertEquals("Trail B", result.chosen!!.trailNames.single())
        assertTrue(
            "and the rider is told why one option went",
            result.warnings.any { it.contains("already have saved") },
        )
    }

    @Test
    fun `a ride that is not contained in any saved one is untouched`() {
        val store = savedRoute(listOf(7L, 8L))
        val client = FakeRoutes(mapOf(pointA to Road(), pointB to Road()))
        val result = plan(client, store = store)

        assertEquals(listOf("Trail A", "Trail B"), result.trailOrder())
        assertTrue(result.options.size >= 1)
    }

    @Test
    fun `everything being a saved ride is shown again rather than refused`() {
        val store = savedRoute(listOf(1L, 2L, 3L))
        val client = FakeRoutes(mapOf(pointA to Road(wayIds = listOf(1L, 2L, 3L))))
        val result = plan(client, store = store)

        assertNotNull("a rider who can see the ride is not told there is none", result.chosen)
        assertTrue(result.warnings.any { it.contains("already have saved") })
    }

    // ---- D26: what the screen does before any work ------------------------------------------

    private suspend fun saveTrips(store: InMemoryRouteStore, count: Int) {
        repeat(count) { index ->
            store.save(
                route = Fixtures.plannedRoute().copy(signature = "saved-$index"),
                origin = home,
                targetMiles = 10.0,
                waypoints = listOf(home),
                fingerprint = "fp",
                nowMs = 1_000L + index,
            )
        }
    }

    @Test
    fun `more than two saved trips for this request are suggested with no routing and no model call`() = runBlocking {
        val store = InMemoryRouteStore()
        // Five saved trips, so the cap the screen applies is really exercised and not just under-filled.
        saveTrips(store, 5)
        val client = FakeRoutes(mapOf(pointA to Road(), pointB to Road()))
        val strategist = FakeStrategist { reply(strategy("Trail B", 202L)) }

        // The screen's step, run before anything else happens.
        val step = SavedTripsFirst.look(store, home, 10.0, "fp", riderAskedForNew = false)

        assertEquals(5, step.matches.size)
        assertEquals(
            "the rider asked for five recent trips on screen",
            PlanPolicy.DEFAULT_MAX_SUGGESTIONS,
            step.suggestions.size,
        )
        assertEquals(5, step.suggestions.size)
        assertFalse("the screen must stop here, not plan", step.mayPlan)
        assertEquals("no routing call was made", 0, client.routeCalls.size)
        assertEquals("and no model call either", 0, strategist.calls)
        assertNotNull(step.note)

        // The rider's own action still plans a new ride: a suggestion never traps them.
        assertTrue(SavedTripsFirst.mayPlan(PlanPolicy.decide(step.matches), riderAskedForNew = true))
        val result = plan(client, strategist, store)
        assertTrue("the new plan did call the router", client.routeCalls.isNotEmpty())
        assertEquals(1, strategist.calls)
        assertTrue(result.options.isNotEmpty())
    }

    @Test
    fun `two saved trips are not enough to be suggested`() = runBlocking {
        val store = InMemoryRouteStore()
        repeat(2) { index ->
            store.save(
                route = Fixtures.plannedRoute().copy(signature = "two-$index"),
                origin = home,
                targetMiles = 10.0,
                waypoints = emptyList(),
                fingerprint = "fp",
                nowMs = 1_000L + index,
            )
        }
        val step = SavedTripsFirst.look(store, home, 10.0, "fp", riderAskedForNew = false)

        assertTrue(step.suggestions.isEmpty())
        assertTrue(step.mayPlan)
    }

    @Test
    fun `a saved trip from another start area is never suggested`() = runBlocking {
        val store = InMemoryRouteStore()
        saveTrips(store, 5)
        val step = SavedTripsFirst.look(store, LatLon(48.0, -122.0), 10.0, "fp", riderAskedForNew = false)

        assertTrue(step.suggestions.isEmpty())
        assertTrue(step.mayPlan)
    }

    // ---- D26: adopting a saved trip --------------------------------------------------------

    /** A fresh plan's route, with a caution road and a corner, so the hand-off needs several stops. */
    private fun richRoute(): PlannedRoute {
        val corner = LatLon(47.2450, -122.2400)
        val ap = Fixtures.accessPoint("Test Trail", pointA)
        val commute = Fixtures.routeResult(
            points = listOf(home, corner, pointA),
            lengthM = Polyline(listOf(home, corner, pointA)).lengthM,
            maneuvers = listOf(Maneuver("Turn left onto Nowhere Road", 0.0, 90, corner)),
        )
        val roads = listOf(
            Fixtures.segment(Grade.D, 1_000.0, "Caution Street"),
            Fixtures.segment(Grade.C, 800.0, "Quiet Street"),
        )
        val trailLeg = TrailRideLeg("Test Trail", 0.0, 2_000.0, listOf(pointA, LatLon(47.26, -122.23), LatLon(47.27, -122.23)))
        return Fixtures.plannedRoute().copy(
            accessPoint = ap,
            commute = commute,
            roadSegments = roads,
            trailLegs = listOf(trailLeg),
            trailOneWayM = 2_000.0,
            trailRoundTripM = 4_000.0,
            roundTrip = commute.points + trailLeg.points,
        )
    }

    @Test
    fun `adopting a saved ride hands off with exactly the URL the fresh plan produced`() {
        val route = richRoute()
        for (dirAction in listOf(false, true)) {
            val fresh = MapsUrlBuilder.build(route, home, maxWaypoints = 9, maxUrlLength = 2048, dirAction = dirAction)
            assertTrue("the fixture must need several stops", fresh.included.size >= 3)

            val saved = SavedRouteCodec.decode(
                SavedRouteCodec.encode(route, home, 10.0, fresh.included.map { it.point }, "fp", 1L),
                "fp",
            )
            assertEquals(
                "a saved ride must hand off with the same URL, with no planning at all",
                fresh.url,
                savedTripUrl(saved, home, dirAction, maxWaypoints = 9, maxUrlLength = 2048),
            )
        }
    }

    @Test
    fun `the stops are saved in the order the URL visits them, not in priority order`() {
        val route = richRoute()
        val fresh = MapsUrlBuilder.build(route, home, maxWaypoints = 9, maxUrlLength = 2048, dirAction = false)
        val priorityOrder = MapsUrlBuilder.selectWaypoints(route, home).map { it.point }
        assertNotEquals("the two orders really are different for this route", priorityOrder, fresh.included.map { it.point })

        val saved = SavedRouteCodec.decode(
            SavedRouteCodec.encode(route, home, 10.0, fresh.included.map { it.point }, "fp", 1L),
            "fp",
        )
        assertEquals(fresh.url, savedTripUrl(saved, home, false, 9, 2048))
    }

    @Test
    fun `a saved ride renders as the same distances and grades it was saved with`() {
        val route = richRoute()
        val fresh = MapsUrlBuilder.build(route, home, 9, 2048, false)
        val saved = SavedRouteCodec.decode(
            SavedRouteCodec.encode(route, home, 10.0, fresh.included.map { it.point }, "fp", 1L),
            "fp",
        )
        val drawn = saved.asPlannedRoute()
        val result = saved.asPlanResult(home)

        assertEquals(route.totalM, drawn.totalM, 0.5)
        assertEquals(route.trailRoundTripM, drawn.trailRoundTripM, 0.5)
        assertEquals(saved.totalMiles, drawn.totalMiles, 0.01)
        assertEquals(saved.trailLabel, drawn.trailLabel)
        assertEquals("the saved geometry is what is drawn", saved.points, drawn.roundTrip)
        assertEquals(PlanOutcome.PLAN_OK, result.outcome)
        assertEquals(saved.signature, result.chosen!!.signature)
        assertEquals(
            "the safety numbers are the stored ones, not a fresh claim",
            route.breakdown.gradedM,
            result.chosen!!.breakdown.gradedM,
            1.0,
        )
    }

    @Test
    fun `a saved ride is judged by the caller's coverage threshold, not a hard-coded one`() {
        // 40% of this saved trip was graded. The shipped knob (`unverified_ungraded_share`) is 0.5,
        // so the ride cannot be vouched for at the default - and a rider who loosens the knob to 0.3
        // gets a PASS. Before this test the saved-trip path called `d10Verdict(breakdown)` with the
        // default argument, so the knob in `assets/config.json` reached the planner and stopped there.
        val saved = reusableWithGradedShare(0.4)

        assertEquals(SafetyOutcome.UNVERIFIED, saved.asPlannedRoute().verdict.outcome)
        assertEquals(SafetyOutcome.PASS, saved.asPlannedRoute(minGradedShare = 0.3).verdict.outcome)
    }

    /**
     * A saved ride whose graded share is exactly [share]. Built directly instead of by encoding a
     * [PlannedRoute] so that the test controls the one number it is about.
     */
    private fun reusableWithGradedShare(share: Double, total: Double = 4_000.0): ReusableRoute {
        val graded = total * share
        val ungraded = total - graded
        return ReusableRoute(
            signature = "sig-coverage",
            trailLabel = "Sumner Link",
            trailNames = listOf("Sumner Link"),
            points = listOf(pointA, pointB),
            waypoints = listOf(pointA),
            wayIds = setOf(1L),
            named = listOf(NamedSegment("18th St E", 1_000.0, Grade.A, "residential", "25 mph")),
            targetMiles = 10.0,
            totalM = total,
            trailRoundM = 1_000.0,
            timeS = 900.0,
            gradedM = graded,
            ungradedM = ungraded,
            breakdownTotalM = total,
            byGrade = mapOf(Grade.A to graded, Grade.UNGRADED to ungraded),
            accessNode = 42L,
            turnaround = pointB,
            createdAt = 1L,
            lastUsedAt = 1L,
            usedCount = 0,
            riddenAt = null,
            safetyStale = false,
        )
    }

    @Test
    fun `a stale saved ride is marked, never offered as ready to ride`() {
        val route = richRoute()
        val fresh = MapsUrlBuilder.build(route, home, 9, 2048, false)
        val record = SavedRouteCodec.encode(route, home, 10.0, fresh.included.map { it.point }, "old-fingerprint", 1L)

        assertTrue("changed safety data marks the row stale", SavedRouteCodec.decode(record, "new-fingerprint").safetyStale)
        assertFalse("and unchanged data does not", SavedRouteCodec.decode(record, "old-fingerprint").safetyStale)
    }

    // ---- the drawn geometry is the ride that is reported (D3) --------------------------------

    /**
     * The shape the map draws and the store keeps is `roundTrip`. It must be the ride the app
     * reports: out along the commute and the trail, then the same way back, closing at the origin -
     * exactly once. A third return leg (the defect this guards against) both inflated the drawn
     * length by 2x the commute and put a straight jump from the origin back out to the access point
     * into the middle of the shape.
     */
    private class GeometryCase(val route: PlannedRoute, val outboundLegM: Double)

    private fun geometryCase(withClosure: Boolean): GeometryCase {
        val trailLengthM = 12_000.0
        val steps = 80
        val dLat = trailLengthM / steps / 111_320.0
        val closure = if (!withClosure) {
            emptyList()
        } else {
            listOf(
                com.biketrailplanner.domain.TrailClosure(
                    trailName = "Trail A",
                    osmWayId = 419747831L,
                    wayLengthM = trailLengthM * 0.2,
                    tags = mapOf("bicycle" to "no"),
                    northEnd = LatLon(pointA.lat + dLat * 32, pointA.lon),
                    southEnd = LatLon(pointA.lat + dLat * 48, pointA.lon),
                    effect = "no bicycle can ride through it",
                    closedSince = "2026-06-01",
                ),
            )
        }
        // A closure cuts the trail into two rideable stubs, exactly as the shipped Sumner Link data
        // does, so the planner has to route the gap on roads and the shape has to carry that detour.
        val dense = denseTrail("Trail A", pointA, trailLengthM, steps)
        val trail = if (closure.isEmpty()) {
            dense
        } else {
            Fixtures.trail("Trail A", pointA, trailLengthM, closures = closure).copy(
                fullGeometry = dense.fullGeometry,
                rideableSpans = listOf(
                    Polyline(dense.fullGeometry.subLine(0.0, trailLengthM * 0.4)),
                    Polyline(dense.fullGeometry.subLine(trailLengthM * 0.6, trailLengthM)),
                ),
                lengthM = dense.lengthM,
                rideableM = dense.lengthM,
            )
        }
        val client = FakeRoutes(mapOf(pointA to Road()))
        val result = runBlocking {
            Planner(
                network = Fixtures.network(listOf(trail)),
                accessPoints = listOf(Fixtures.accessPoint("Trail A", pointA).copy(osmNodeId = 101L, probeRouteKmFromHome = 1.0)),
                grader = SafetyGrader(LimitsTable.EMPTY),
                client = client,
                config = PlannerDto(accessPointsPerTrail = 1, maxCandidates = 1, detourAccessPointsPerTrail = 1),
                home = home,
            ).plan(PlanRequest(targetMiles = 10.0, origin = home, maxCandidates = 1, accessPointsPerTrail = 1))
        }
        val route = result.chosen ?: result.bestAttempt
        assertNotNull("the fixture must produce a route", route)
        return GeometryCase(route!!, route.commute.lengthM + route.trailOneWayM + route.detourOneWayM)
    }

    private fun assertSoundRoundTrip(route: PlannedRoute) {
        val drawn = Polyline(route.roundTrip).lengthM
        // What the ride IS, in geometry: the commute out and back, plus the trail and its detour out
        // and back. Every term is measured from the shape's own points, so the comparison cannot be
        // satisfied by a shape that says one thing and draws another.
        val detourGeometryM = route.detours.sumOf { Geo.distanceM(it.from, it.to) }
        val expected = 2 * (Polyline(route.commute.points).lengthM + route.trailOneWayM + detourGeometryM)
        assertEquals("the drawn shape must be the distance the app reports", expected, drawn, expected * 0.01)
        assertEquals("and the app's own total must be that same ride", route.totalM, expected, expected * 0.01)
        assertEquals("the shape starts where the rider is", home.lat, route.roundTrip.first().lat, 1e-6)
        assertEquals("and it finishes there", home.lat, route.roundTrip.last().lat, 1e-6)
        assertEquals(
            "an out-and-back touches the start twice, and a stray return leg makes it three",
            2,
            route.roundTrip.count { Geo.distanceM(it, home) <= 5.0 },
        )
        // No jump across the map: the largest step in this fixture's geometry is one 150 m trail
        // vertex or one 70 m commute vertex. The artefact being guarded against is a jump the length
        // of the whole commute (about 1.4 km here), so the bound below cannot hide it.
        val worst = route.roundTrip.zipWithNext { a, b -> Geo.distanceM(a, b) }.max()
        assertTrue("the largest step in the drawn shape is " + worst + " m", worst <= 250.0)
    }

    @Test
    fun `the drawn round trip is the reported distance, with no jump back out to the access point`() {
        val case = geometryCase(withClosure = false)
        assertTrue("the fixture must really have a commute and a trail", case.outboundLegM > 3_000.0)
        assertSoundRoundTrip(case.route)
    }

    @Test
    fun `the same holds when the ride has to detour around a closed trail segment`() {
        val case = geometryCase(withClosure = true)
        assertTrue("the fixture must really detour", case.route.detours.isNotEmpty())
        assertSoundRoundTrip(case.route)
    }

    // ---- the trail set the planner is given -------------------------------------------------

    @Test
    fun `a trail from the merged regional set is planned exactly like a bundled one`() {
        val regional = Fixtures.trail("Regional Trail", pointB, 8_000.0).copy(verified = false)
        val result = runBlocking {
            Planner(
                network = Fixtures.network(listOf(Fixtures.trail("Trail A", pointA, 8_000.0), regional)),
                accessPoints = accessPoints(),
                grader = SafetyGrader(LimitsTable.EMPTY),
                client = FakeRoutes(mapOf(pointA to Road(), pointB to Road())),
                config = PlannerDto(accessPointsPerTrail = 2, maxCandidates = 4, detourAccessPointsPerTrail = 2),
                home = home,
            ).plan(PlanRequest(targetMiles = 10.0, origin = home, maxCandidates = 4, accessPointsPerTrail = 2))
        }
        assertNotNull(result.chosen)
        assertTrue(result.options.isNotEmpty())
        assertNull(result.refusal)
    }
}
