package com.biketrailplanner

import com.biketrailplanner.data.PlannerDto
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.RouteSanity
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.TraceMode
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.RawEdge
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.TrailClosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC section 7b end to end: a closed trail segment must not end the ride. The planner routes
 * around it on roads, grades those roads, and says what is closed, since when, and on whose
 * authority.
 */
class PlannerTest {

    private val home = LatLon(47.240371, -122.262019)
    private val start = LatLon(47.20, -122.26)
    private val dLatPerStep = 8_000.0 / 20 / 111_320.0

    private val closure = TrailClosure(
        trailName = "Severed Trail",
        osmWayId = 419747831L,
        wayLengthM = 2_369.7,
        tags = mapOf("access" to "no", "bicycle" to "no", "highway" to "unclassified"),
        northEnd = LatLon(start.lat + 8 * dLatPerStep, start.lon),
        southEnd = LatLon(start.lat + 12 * dLatPerStep, start.lon),
        effect = "no bicycle can ride through it",
        closedSince = "2026-06-01",
        changeset = 183511084,
        changedBy = "fencebuilder2000",
        authority = "City of Sumner trail-closure notice",
        previousTags = mapOf("highway" to "cycleway", "bicycle" to "designated"),
    )

    private class FakeService(
        private val commuteM: Double,
        private val detourM: Double,
        private val commuteEdges: List<RawEdge>,
        private val detourEdges: List<RawEdge>,
        private val home: LatLon,
    ) : RouteService {
        val routed = ArrayList<Pair<LatLon, LatLon>>()
        /** Which shapes were traced: "commute" or "detour", by their first point. */
        val traced = ArrayList<String>()

        override suspend fun route(from: LatLon, to: LatLon, via: List<LatLon>, allowFallback: Boolean): RouteResult {
            val first = routed.isEmpty()
            routed.add(from to to)
            val length = if (first) commuteM else detourM
            val points = listOf(from, to)
            return RouteResult(
                shape = PolylineCodec.encode(points),
                points = points,
                lengthM = length,
                timeS = 0.0,
                maneuvers = emptyList(),
                baseUrl = "http://fake",
                usedFallback = false,
                sanityProblem = RouteSanity.check(from, to, length),
            )
        }

        override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
            val first = PolylineCodec.decode(shape).firstOrNull()
            val isCommute = first != null && Geo.distanceM(first, home) < 5.0
            traced.add(if (isCommute) "commute" else "detour")
            val edges = if (isCommute) commuteEdges else detourEdges
            return TraceResult(edges, TraceMode.EDGE_WALK, baseUrl)
        }
    }

    private fun fake(commuteM: Double, detourM: Double, detourName: String? = null, detourSpeedKph: Int? = 40, detourClass: String = "tertiary", detourGradeName: Boolean = true) = FakeService(
        commuteM = commuteM,
        detourM = detourM,
        commuteEdges = listOf(Fixtures.edge(roadClass = "residential", speedLimitKph = 40, name = "18th Street East", lengthM = commuteM)),
        detourEdges = listOf(
            Fixtures.edge(
                roadClass = detourClass,
                speedLimitKph = detourSpeedKph,
                name = detourName ?: "Detour Road",
                lengthM = detourM,
            ),
        ),
        home = home,
    )

    private fun plan(service: RouteService, targetMiles: Double = 12.0): com.biketrailplanner.domain.PlanResult {
        val trail = Fixtures.trail("Severed Trail", start, 8_000.0, closures = listOf(closure))
        val ap = Fixtures.accessPoint("Severed Trail", start)
        return kotlinx.coroutines.runBlocking {
            Planner(
                network = Fixtures.network(listOf(trail)),
                accessPoints = listOf(ap),
                grader = SafetyGrader(LimitsTable.EMPTY),
                client = service,
                // D31: this file is about the closure detour and the trail walk, so the loop search
                // is switched off here. It has its own end-to-end test (`PlannerLoopTest`), a live
                // probe (`LiveLoopProbeTest`) and it is ON by default in `assets/config.json`.
                config = PlannerDto(accessPointsPerTrail = 1, maxCandidates = 1, preferLoop = false),
                home = home,
            ).plan(PlanRequest(targetMiles = targetMiles, origin = home, maxCandidates = 1, accessPointsPerTrail = 1))
        }
    }

    @Test
    fun `the planner routes around a closed segment and grades the detour roads`() {
        val service = FakeService(
            commuteM = 2_824.0,
            detourM = 2_500.0,
            home = home,
            commuteEdges = listOf(Fixtures.edge(roadClass = "residential", speedLimitKph = 40, name = "18th Street East", lengthM = 2_824.0)),
            detourEdges = listOf(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "142nd Avenue East", lengthM = 2_500.0)),
        )
        val result = plan(service)
        val route = result.chosen ?: result.bestAttempt
        assertNotNull(route)
        route!!

        // the commute, then the road detour around the closure
        assertEquals(2, service.routed.size)
        assertEquals(1, route.detours.size)
        val detour = route.detours.single()
        assertEquals(419747831L, detour.closure.osmWayId)
        assertEquals(2_500.0, detour.roadMetres, 1.0)
        assertEquals(listOf("142nd Avenue East"), detour.streetNames)
        // the detour roads are scored by the normal ladder, and this one has no speed limit
        assertEquals(Grade.UNGRADED, detour.segments.single().grade)
        assertTrue(route.named.any { it.name == "142nd Avenue East" })
        assertTrue(route.ungraded.any { it.name == "142nd Avenue East" })
    }

    @Test
    fun `trail mileage is measured on the rideable trail, not the relation length`() {
        val service = fake(commuteM = 2_824.0, detourM = 2_500.0, detourName = "Detour Road", detourSpeedKph = 40)
        val route = plan(service, targetMiles = 12.0).let { it.chosen ?: it.bestAttempt }!!
        // 7 380 m of trail budget one way = 3 200 m to the closure + 1 123 m on the far stub
        assertTrue("trail metres were ${route.trailOneWayM}", route.trailOneWayM in 4_000.0..4_500.0)
        assertEquals(2_500.0, route.detourOneWayM, 1.0)
        // the closed 2.37 km is never part of the ride
        assertTrue(route.trailOneWayM < 8_000.0 - 2_370.0)
        assertTrue(route.notes.any { it.contains("measured on the rideable trail only") })
        // both halves of the round trip are present: trail + detour, twice
        assertEquals(
            2 * route.commute.lengthM + 2 * (route.trailOneWayM + route.detourOneWayM),
            route.totalM,
            1.0,
        )
    }

    @Test
    fun `the summary states what is closed, since when and on whose authority`() {
        val service = fake(commuteM = 2_824.0, detourM = 1_000.0, detourName = null, detourSpeedKph = 40)
        val result = plan(service)
        val provenance = result.dataGaps.first { it.contains("not continuous") }
        assertTrue(provenance.contains("419747831"))
        assertTrue(provenance.contains("2026-06-01"))
        assertTrue(provenance.contains("City of Sumner trail-closure notice"))
        assertTrue(provenance.contains("changeset 183511084"))
        assertTrue(provenance.contains("highway=cycleway"))
        val route = result.chosen ?: result.bestAttempt
        assertTrue(route!!.notes.any { it.contains("routed around the closed segment on roads") })
    }

    @Test
    fun `a route that needs a detour is still evaluated against D10 with the detour included`() {
        // a 2 km 35 mph tertiary detour: grade D, and far more than 10% / 0.5 mi allows
        val service = fake(commuteM = 2_824.0, detourM = 2_000.0, detourName = "142nd Avenue East", detourSpeedKph = 56)
        val result = plan(service)
        val route = result.chosen ?: result.bestAttempt
        assertNotNull(route)
        assertTrue(route!!.breakdown.metres(Grade.D) > 0.0)
        // 2 km each way = 4 km of grade D, so D10 fails and the app says so instead of shipping it
        assertFalse(route.verdict.hasNoKnownViolation)
        assertTrue(route.verdict.violations.any { it.contains("grade D") })
        assertFalse(result.safeRouteFound)
    }

    @Test
    fun `the round trip geometry starts and ends at home and covers the detour`() {
        val service = fake(commuteM = 2_824.0, detourM = 1_200.0, detourName = null, detourSpeedKph = 40)
        val route = plan(service).let { it.chosen ?: it.bestAttempt }!!
        assertEquals(home.lat, route.roundTrip.first().lat, 1e-6)
        assertEquals(home.lat, route.roundTrip.last().lat, 1e-6)
        val length = Polyline(route.roundTrip).lengthM
        assertTrue("round trip geometry was $length m", length > route.commuteM)
        // The shape that is drawn and stored must be the ride that is REPORTED: the commute out and
        // back, plus the trail and its detour out and back, measured on the shape's own points. One
        // extra return leg (the defect this asserts against) added a whole commute and a straight
        // jump from the origin back out to the access point.
        val detourGeometryM = route.detours.sumOf { Geo.distanceM(it.from, it.to) }
        val expected = 2 * (Polyline(route.commute.points).lengthM + route.trailOneWayM + detourGeometryM)
        assertEquals("drawn geometry vs the ride it claims", expected, length, expected * 0.01)
        // A valid out-and-back touches the start only at its two ends. A stray extra return leg puts
        // the origin in the MIDDLE of the shape, which is the jump this asserts against.
        val atHome = route.roundTrip.count { Geo.distanceM(it, home) <= 5.0 }
        assertEquals("the shape returns to the start exactly once", 2, atHome)
    }
}
