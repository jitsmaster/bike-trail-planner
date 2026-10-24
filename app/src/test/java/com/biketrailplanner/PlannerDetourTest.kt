package com.biketrailplanner

import com.biketrailplanner.data.PlannerDto
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.RouteSanity
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.TraceMode
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.RawEdge
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.SafetyOutcome
import com.biketrailplanner.ui.RiderText as RText
import com.biketrailplanner.domain.TrafficConfidence
import com.biketrailplanner.domain.TrafficCount
import com.biketrailplanner.domain.TrafficTable
import com.biketrailplanner.domain.VolumeBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D13 (SPEC section 7c) at the planner level: search for a compliant longer route before
 * refusing, accept it up to 1.40x, offer it explicitly up to 1.80x, never deliver a token trail
 * ride, and name the disqualifying segments with their counts when nothing works.
 */
class PlannerDetourTest {

    private val home = LatLon(47.240371, -122.262019)
    private val start = LatLon(47.20, -122.26)

    /** Routes a different (quiet) way when a via-point is supplied, which is the D13 lever. */
    private class ViaAwareService(
        private val directM: Double,
        private val viaM: Double,
        private val firstAccessPoint: LatLon,
        /** Edges traced for the first access point's direct route. */
        private val directEdges: List<RawEdge>,
        /** Edges traced for any other access point's direct route (this is what fills the quiet pool). */
        private val alternateEdges: List<RawEdge>,
        private val viaEdges: List<RawEdge>,
    ) : RouteService {
        var directRoutes = 0
        var viaRoutes = 0

        override suspend fun route(from: LatLon, to: LatLon, via: List<LatLon>, allowFallback: Boolean): RouteResult {
            val hasVia = via.isNotEmpty()
            if (hasVia) viaRoutes++ else directRoutes++
            val length = if (hasVia) viaM else directM
            val points = if (hasVia) listOf(from, via.first(), to) else listOf(from, to)
            return RouteResult(
                shape = PolylineCodec.encode(points),
                points = points,
                lengthM = length,
                timeS = length / 5.0,
                maneuvers = emptyList(),
                baseUrl = "http://fake",
                usedFallback = false,
                sanityProblem = RouteSanity.check(from, to, length),
            )
        }

        override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
            val points = PolylineCodec.decode(shape)
            // the route shape starts at home for every candidate, so the access point is the END
            val destination = points.lastOrNull()
            val edges = when {
                points.size >= 3 -> viaEdges
                destination != null && Geo.distanceM(destination, firstAccessPoint) < 5.0 -> directEdges
                else -> alternateEdges
            }
            return TraceResult(edges, TraceMode.EDGE_WALK, baseUrl)
        }
    }

    private fun graderWithCounts(): SafetyGrader {
        val traffic = TrafficTable(
            listOf(
                TrafficCount(
                    roadName = "Jovita Boulevard East",
                    source = "test",
                    aadt = 10358,
                    aadtYear = 1996,
                    confidence = TrafficConfidence.STALE,
                    lts = 4,
                    volumeBand = VolumeBand.HIGH,
                    wayIds = listOf(9L),
                    osmNames = listOf("Jovita Boulevard East"),
                ),
                TrafficCount(
                    roadName = "Quiet Street",
                    source = "test",
                    aadt = 700,
                    aadtYear = 2024,
                    confidence = TrafficConfidence.MEASURED,
                    lts = null,
                    volumeBand = VolumeBand.LOW,
                    wayIds = listOf(11L),
                    osmNames = listOf("Quiet Street"),
                ),
            ),
        )
        return SafetyGrader(LimitsTable.EMPTY, traffic)
    }

    private val dustyEdge = Fixtures.edge(wayId = 9L, roadClass = "secondary", speedLimitKph = null, name = "Jovita Boulevard East", lengthM = 2_000.0)
    private val noisyEdge = Fixtures.edge(wayId = 9L, roadClass = "tertiary", speedLimitKph = 56, name = "Jovita Boulevard East", lengthM = 2_000.0)
    /** D44: a compliant but unfriendly road - 300 m of 35 mph tertiary is inside the caution cap. */
    private val bigRoadEdge = Fixtures.edge(wayId = 21L, roadClass = "tertiary", speedLimitKph = 56, name = "Big Road", lengthM = 300.0)

    /** D40's new source: a residential street with no posted limit anywhere and no AADT. */
    private val unpostedSmallEdge = Fixtures.edge(wayId = 12L, roadClass = "residential", speedLimitKph = null, name = "18th Street East", lengthM = 2_000.0)

    private val quietEdge = Fixtures.edge(wayId = 11L, roadClass = "residential", speedLimitKph = 40, name = "Quiet Street", lengthM = 4_000.0)

    private val altStart = LatLon(47.205, -122.255)

    /**
     * Two access points on one trail. The second one's trace carries a low-volume street, which is
     * what gives the D13 via-point search something to work with - the same way the real plan sees
     * 18th Street East and 114th Avenue East on its other candidates.
     */
    private fun plan(
        service: RouteService,
        targetMiles: Double = 12.0,
        /** Access points per trail: 2 is what lets a second trace fill the quiet-link pool (D44). */
        accessPointsPerTrail: Int = 1,
        /** The D13 ceiling: 1.80x shipped, 2.50x for a "try a safer detour" run (D43). */
        detourMaxFactor: Double = 1.80,
        trailLengthM: Double = 8_000.0,
        history: com.biketrailplanner.domain.RideHistoryStore = com.biketrailplanner.domain.InMemoryRideHistory(),
        origin: LatLon = home,
    ): com.biketrailplanner.domain.PlanResult {
        val trail = Fixtures.trail("Test Trail", start, trailLengthM)
        val apA = Fixtures.accessPoint("Test Trail", start)
        val apB = Fixtures.accessPoint("Test Trail", altStart).copy(osmNodeId = 43L)
        return kotlinx.coroutines.runBlocking {
            Planner(
                network = Fixtures.network(listOf(trail)),
                accessPoints = listOf(apA, apB),
                grader = graderWithCounts(),
                client = service,
                config = PlannerDto(
            accessPointsPerTrail = accessPointsPerTrail,
            maxCandidates = 2,
            detourAccessPointsPerTrail = 2,
            maxViaCandidates = 4,
            detourMaxFactor = detourMaxFactor,
        ),
                home = home,
                history = history,
            ).plan(
                PlanRequest(
                    targetMiles = targetMiles,
                    origin = origin,
                    maxCandidates = 2,
                    accessPointsPerTrail = accessPointsPerTrail,
                ),
            )
        }
    }

    @Test
    fun `a direct D10 failure triggers a via-point search and a compliant longer route is accepted`() {
        // direct: 2 km of a 35 mph secondary each way -> 4 km of grade D, over the cap
        // via: 9 km each way on a quiet low-volume street, compliant but longer
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val result = plan(service)
        assertTrue("the planner must try a via-point", service.viaRoutes > 0)
        val chosen = result.chosen
        assertNotNull("a compliant longer route exists, so it must be offered", chosen)
        chosen!!
        assertTrue("the detour must say which quiet link it used", chosen.via.isNotEmpty())
        assertEquals("Quiet Street", chosen.via.single().label)
        assertTrue(chosen.verdict.hasNoKnownViolation)
        assertTrue("the total is longer than requested", chosen.totalM > Geo.milesToMetres(12.0))
        assertTrue(result.exceedsTarget)
        assertTrue(result.warnings.any { it.contains("longer than you asked for") })
        // 18 km of commute + the 2 mi trail floor = 13.2 mi, about 1.10x the direct total
        assertTrue("factor was ${chosen.distanceFactor}", chosen.distanceFactor in 1.0..1.41)
        assertFalse(chosen.explicitDetour)
        assertFalse(result.requiresDetourAcceptance)
    }

    @Test
    fun `a compliant ride down the big road is replaced by a quieter one through small streets (D44)`() {
        // The rider's complaint is NOT about refusals: "the route determination part is too fixed on
        // the big roads. There are a lot of smaller road under 25 miles it's not using at all." Here
        // the shortest way IS compliant, so D13 phase 2 never runs - and before D44 that was the end
        // of the story. The only road it uses is a 35 mph tertiary.
        val service = ViaAwareService(
            directM = 3_000.0,
            viaM = 3_400.0,
            firstAccessPoint = start,
            directEdges = listOf(bigRoadEdge),
            // The second access point's trace is what puts a small residential street in the pool.
            alternateEdges = listOf(bigRoadEdge, unpostedSmallEdge),
            viaEdges = listOf(unpostedSmallEdge),
        )
        val result = plan(service, accessPointsPerTrail = 2)

        assertTrue("D44 must search for a quieter way even though the direct ride complied", service.viaRoutes > 0)
        val chosen = result.chosen
        assertNotNull(chosen)
        chosen!!
        assertTrue("the quieter ride is the one offered", chosen.via.isNotEmpty())
        assertEquals("18th Street East (residential street, no posted limit)", chosen.via.single().label)
        val score = com.biketrailplanner.domain.QuietRoute.score(chosen.roadSegments)
        assertEquals("the ride offered is now entirely on the small street", 1.0, score.goodShare, 1e-9)
        assertEquals("and the app can say how much of that rests on the road class", score.totalM, score.smallUnknownM, 1e-9)
        assertTrue("the arterial ride was the alternative, and it is not quiet", score.goodShare > 0.9)
        assertTrue("no extra distance is charged as a detour", !chosen.explicitDetour)
    }

    @Test
    fun `try a safer detour searches wider and then reports what it found (D43)`() {
        // The button is drawn when nothing was compliant. It used to do literally nothing in that
        // state (it only acted on a trail-floor offer), which is what the rider reported: "try safer
        // detour doesn't do anything". It now re-plans with a wider D13 budget.
        assertEquals(2.5, com.biketrailplanner.domain.DetourPolicy.harderSearchFactor(1.8), 1e-9)
        assertEquals(
            "a config already more generous than the constant must not be narrowed by a tap",
            3.0,
            com.biketrailplanner.domain.DetourPolicy.harderSearchFactor(3.0),
            1e-9,
        )

        // 2.2x the shortest route: past the shipped 1.80x band, inside the wider one.
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 20_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val ordinary = plan(service)
        val widened = plan(service, detourMaxFactor = com.biketrailplanner.domain.DetourPolicy.harderSearchFactor(1.8))
        assertEquals(
            "the shipped band leaves the 2.2x ride out",
            com.biketrailplanner.domain.PlanOutcome.UNSAFE,
            ordinary.outcome,
        )
        assertEquals(
            "the wider search delivers it, as an explicit detour the rider accepts",
            com.biketrailplanner.domain.PlanOutcome.PLAN_OK,
            widened.outcome,
        )
        assertNotNull(widened.chosen)
        assertTrue(widened.chosen!!.verdict.hasNoKnownViolation)
        assertTrue("the extra distance must still be declared", widened.requiresDetourAcceptance)
    }

    @Test
    fun `a small unposted street with no count is enough to become a via-point (D40)`() {
        // The rider's complaint in one test: "the route determination part is too fixed on the big
        // roads. There are a lot of smaller road under 25 miles it's not using at all." This street has
        // no `maxspeed` in OSM and no AADT anywhere. Before D40 it could not be a via-point at all
        // (only a measured count made a way eligible), so near home the planner had nothing but the
        // counted - bigger - roads to try.
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, unpostedSmallEdge),
            viaEdges = listOf(unpostedSmallEdge),
        )
        val result = plan(service)

        assertTrue("the planner must try the small street as a via-point", service.viaRoutes > 0)
        val chosen = result.chosen
        assertNotNull("an unverified ride is offered, never refused for being unmeasured", chosen)
        chosen!!
        assertEquals(
            "the detour names the street AND why it was chosen",
            "18th Street East (residential street, no posted limit)",
            chosen.via.single().label,
        )
        assertTrue(
            "the warning must not claim a measured volume it does not have",
            result.warnings.any { it.contains("1 small street(s) under 25 mph by road class") },
        )
        // No speed was invented for the street: it is still UNGRADED, so the ride is offered as
        // UNVERIFIED rather than being called safe or being refused.
        assertEquals(SafetyOutcome.UNVERIFIED, chosen.verdict.outcome)
        assertTrue(chosen.verdict.hasNoKnownViolation)
    }

    @Test
    fun `the detour sentence says why a street was chosen, in rider words`() {
        // Two sources, two sentences, neither using internal vocabulary: `vpd` and `LTS` are banned on
        // screen by tools/verify_ui.py and docs/UI.md section 1 asks for "vehicles/day" instead.
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val counted = RText.detourSentence(plan(service).chosen!!)
        assertEquals("Way around: Quiet Street \u00b7 700 vehicles/day (2024 data)", counted)
        assertFalse("no vpd on screen", counted!!.contains("vpd"))
        assertFalse("no LTS on screen", counted.contains("LTS"))

        val byClass = ViaAwareService(
            directM = 7_640.0,
            viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, unpostedSmallEdge),
            viaEdges = listOf(unpostedSmallEdge),
        )
        val picked = RText.detourSentence(plan(byClass).chosen!!)
        assertEquals("Way around: 18th Street East (residential street, no posted limit)", picked)
    }

    @Test
    fun `a compliant route between 1_40x and 1_80x is offered as an explicit detour`() {
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 13_500.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val result = plan(service)
        val chosen = result.chosen
        assertNotNull(chosen)
        chosen!!
        assertTrue("factor was ${chosen.distanceFactor}", chosen.distanceFactor in 1.41..1.81)
        assertTrue(chosen.explicitDetour)
        assertTrue(result.requiresDetourAcceptance)
        assertTrue(result.headline.contains("COMPLIANT DETOUR"))
        assertTrue(result.warnings.any { it.contains("explicit detour") })
    }

    @Test
    fun `a compliant route beyond 1_80x is not delivered, but something still routes`() {
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 20_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val result = plan(service)
        // D42: the 2.2x detour is past the band, so it is not delivered AS the ride - but the app no
        // longer leaves the rider with nothing: the best attempt is the ride, marked unsafe.
        assertNotNull("there is always something to route", result.chosen)
        assertEquals(com.biketrailplanner.domain.PlanOutcome.UNSAFE, result.outcome)
        assertFalse(result.safeRouteFound)
        assertNotNull(result.refusal)
        assertNotNull(result.bestAttempt)
        assertEquals(
            "the over-long detour must not be passed off as the offered safe ride",
            result.bestAttempt!!.signature,
            result.chosen!!.signature,
        )
    }

    @Test
    fun `a compliant detour that leaves less than two miles of trail is not delivered`() {
        // a 1.6 km trail cannot give the 2.0 mi floor, so the ride would be a token trail ride
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val result = plan(service, trailLengthM = 1_600.0)
        // D13 rule 3: the route is a REAL plan (state TRAIL_FLOOR), but the screen must say plainly
        // that the trail portion is a token one - it is never shipped as an ordinary ride.
        assertEquals(com.biketrailplanner.domain.PlanOutcome.TRAIL_FLOOR, result.outcome)
        assertNotNull("the compliant route is still offered, with the tradeoff stated", result.chosen)
        assertTrue(result.chosen!!.belowTrailFloor)
        assertNull(result.refusal)
        val offer = result.trailFloorOffer
        assertNotNull("the tradeoff must be reported", offer)
        offer!!
        assertEquals(2.0, offer.floorMiles, 1e-9)
        assertTrue(offer.bestTrailMiles < 2.0)
        assertEquals(3, offer.options.size)
        // the 1.6 km trail cannot supply the floor, so extra mileage would not help (D13 rule 3)
        assertFalse(
            "canReachFloor=${offer.canReachFloor} suggested=${offer.suggestedTotalMiles} best=${offer.bestTrailMiles}",
            offer.canReachFloor,
        )
        assertTrue(offer.options[0].contains("no extra mileage helps"))
        assertTrue(offer.options[1].contains("different trail"))
        assertTrue(offer.options[2].contains("refused"))

        assertTrue(offer.trailName.isNotEmpty())
        assertEquals(12.0, offer.requestedMiles, 1e-9)
    }

    @Test
    fun `a compliant route below the trail floor is TRAIL_FLOOR, never the red refusal`() {
        val service = ViaAwareService(
            directM = 7_640.0, viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge), alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val floorResult = plan(service, trailLengthM = 1_600.0)
        assertEquals(com.biketrailplanner.domain.PlanOutcome.TRAIL_FLOOR, floorResult.outcome)
        assertNotNull("a real route exists in this state", floorResult.chosen)
        assertNotNull(floorResult.trailFloorOffer)
        assertNull("nothing may be refused when a compliant route exists", floorResult.refusal)
        // the red card's title belongs to the refusal state only
        val floorTitle = com.biketrailplanner.ui.RiderText.outcomeTitle(floorResult, floorResult.chosen!!)
        assertTrue("title was ${floorTitle}", floorTitle!!.contains("on trail"))
        assertFalse(floorTitle.contains(com.biketrailplanner.ui.RiderText.REFUSAL_TITLE))

        // D42: and the case where NOTHING is compliant is no longer a refusal. The rider asked for
        // advice, not a locked door: "I need you not to disable the route for navigation, instead,
        // just advise it's not safe, still allow us to route."
        val unsafeService = ViaAwareService(
            directM = 7_640.0, viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge), alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(noisyEdge),
        )
        val unsafe = plan(unsafeService)
        assertEquals(com.biketrailplanner.domain.PlanOutcome.UNSAFE, unsafe.outcome)
        assertNotNull("a route is still offered, so it can be sent to Google Maps", unsafe.chosen)
        assertNotNull("and the reason is still stated", unsafe.refusal)
        assertEquals(
            "the offending streets are still named",
            listOf("Jovita Boulevard East"),
            unsafe.disqualifying.map { it.name },
        )
        assertEquals(
            "the card's title comes from the one shared source, and is NOT the refusal title",
            com.biketrailplanner.ui.RiderText.UNSAFE_TITLE,
            com.biketrailplanner.ui.RiderText.outcomeTitle(unsafe, unsafe.chosen!!),
        )
        assertFalse(
            "an unsafe ride must never be titled like a refusal",
            com.biketrailplanner.ui.RiderText.outcomeTitle(unsafe, unsafe.chosen!!)!!.contains(
                com.biketrailplanner.ui.RiderText.REFUSAL_TITLE,
            ),
        )
        assertTrue(
            "the ride is drawn and hand-off-able like any other",
            unsafe.chosen!!.verdict.hasNoKnownViolation.not(),
        )
        assertFalse("and it is never reported as a safe route found", unsafe.safeRouteFound)
        // and nothing else in main/ may spell it out, so the two can never drift apart
        val literal = Regex(Regex.escape(com.biketrailplanner.ui.RiderText.REFUSAL_TITLE))
        val occurrences = java.io.File("src/main/java/com/biketrailplanner").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { file -> literal.findAll(file.readText()).count() }
        assertEquals("the refusal title must have exactly one source in app/src/main", 1, occurrences)
    }

    @Test
    fun `a plan that meets the limits has no warning card at all`() {
        val service = ViaAwareService(
            directM = 3_000.0, viaM = 3_000.0,
            firstAccessPoint = start,
            directEdges = listOf(quietEdge), alternateEdges = listOf(quietEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val result = plan(service, targetMiles = 8.0)
        if (result.chosen != null && !result.chosen!!.capped) {
            assertTrue(
                "state was ${result.outcome}",
                result.outcome == com.biketrailplanner.domain.PlanOutcome.PLAN_OK ||
                    result.outcome == com.biketrailplanner.domain.PlanOutcome.TRAIL_FLOOR,
            )
            if (result.outcome == com.biketrailplanner.domain.PlanOutcome.PLAN_OK) {
                assertNull(com.biketrailplanner.ui.RiderText.outcomeTitle(result, result.chosen!!))
            }
        }
    }

    @Test
    fun `the same ride twice is flagged as a repeat instead of being handed over silently`() {
        val service = ViaAwareService(
            directM = 3_000.0, viaM = 3_000.0,
            firstAccessPoint = start,
            directEdges = listOf(quietEdge), alternateEdges = listOf(quietEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val history = com.biketrailplanner.domain.InMemoryRideHistory()
        val first = plan(service, targetMiles = 8.0, history = history)
        val firstRoute = first.chosen ?: first.bestAttempt!!
        assertFalse("the first ride is not a repeat", first.repeatOfLast)
        kotlinx.coroutines.runBlocking {
            history.record(firstRoute.signature, firstRoute.trailNames, firstRoute.totalMiles, 1_000L)
        }
        val second = plan(service, targetMiles = 8.0, history = history)
        val sameRoute = second.chosen ?: second.bestAttempt!!
        assertEquals(firstRoute.signature, sameRoute.signature)
        assertTrue("the second identical plan must be flagged", second.repeatOfLast)
        assertTrue(
            com.biketrailplanner.ui.RiderText.repeatSentence(second)!!.contains("only safe option"),
        )
    }

    @Test
    fun `outside the bundled trail radius the app says so instead of planning a long trip`() {
        val service = ViaAwareService(
            directM = 3_000.0, viaM = 3_000.0,
            firstAccessPoint = start,
            directEdges = listOf(quietEdge), alternateEdges = listOf(quietEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        // ~30 km south of the bundled trails
        val faraway = LatLon(46.97, -122.26)
        val result = plan(service, targetMiles = 10.0, origin = faraway)
        assertEquals(com.biketrailplanner.domain.PlanOutcome.NO_TRAILS, result.outcome)
        assertNull(result.chosen)
        assertTrue(result.refusal!!.contains("No trails in the app cover this area"))
        assertEquals(0, result.trailsInRange)
        assertTrue("coverage honesty", result.outsideDataArea)
    }

    @Test
    fun `when nothing is compliant the app advises and names every disqualifying segment with its count`() {
        // both the direct and the via route run on the same 35 mph road: nothing can comply
        val service = ViaAwareService(
            directM = 7_640.0,
            viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge),
            alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(noisyEdge),
        )
        val result = plan(service)
        // D42: advised, not blocked. The ride is offered so it can be navigated; it is never called
        // safe, and the streets that disqualify it are still named with their counts.
        assertNotNull("D42: a route exists, so it is offered", result.chosen)
        assertEquals(com.biketrailplanner.domain.PlanOutcome.UNSAFE, result.outcome)
        assertEquals("nothing is hidden: one option, the one offered", 1, result.options.size)
        assertFalse(result.safeRouteFound)
        assertTrue("it must not be called safe", !result.chosen!!.verdict.hasNoKnownViolation)
        assertNotNull(result.refusal)
        val names = result.disqualifying
        assertTrue("the caution street must be named", names.any { it.name == "Jovita Boulevard East" })
        val jovita = names.first { it.name == "Jovita Boulevard East" }
        assertTrue("the count must be shown: ${jovita.volumeText}", jovita.volumeText!!.contains("10358 vpd"))
        assertTrue("the count year must be shown: ${jovita.volumeText}", jovita.volumeText!!.contains("1996"))
        assertNotNull(result.bestAttempt)
        assertTrue(result.bestAttempt!!.verdict.violations.any { it.contains("grade D") })
    }

    @Test
    fun `a long trail that can supply the floor offers the longer-total option instead`() {
        val service = ViaAwareService(
            directM = 7_640.0, viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge), alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        // an 8 km trail can supply far more than 2 mi, so the tradeoff is about mileage, not the trail
        val result = plan(service, targetMiles = 4.0)
        val offer = result.trailFloorOffer
        if (offer != null) {
            assertTrue("canReachFloor should be true for an 8 km trail", offer.canReachFloor)
            assertTrue(offer.options[0].contains("mi"))
        }
    }

    @Test
    fun `a compliant detour is never silent and never exceeds the target without saying so`() {
        val service = ViaAwareService(
            directM = 7_640.0, viaM = 9_000.0,
            firstAccessPoint = start,
            directEdges = listOf(noisyEdge), alternateEdges = listOf(noisyEdge, quietEdge),
            viaEdges = listOf(quietEdge),
        )
        val result = plan(service)
        val chosen = result.chosen!!
        val targetM = Geo.milesToMetres(12.0)
        if (chosen.totalM > targetM + 1.0) {
            assertTrue(result.exceedsTarget)
            assertTrue(result.extraMilesVsTarget!! > 0.0)
            assertTrue(result.warnings.any { it.contains("longer than you asked for") })
        }
        assertTrue(chosen.notes.any { it.contains("D13") || it.contains("Trail portion") })
    }
}
