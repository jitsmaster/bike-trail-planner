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
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.SafetyGrader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D22 + D24: the ride starts from the live position, so the entrances must be ranked by the approach
 * measured FROM THAT POSITION - not by crow-flies. D24 measured what crow-flies costs: it picks the
 * entrances on the far side of a barrier, and the whole candidate budget goes on rides that cannot
 * work. Near the saved home the bundled probe table still answers and must be used unchanged.
 */
class PlannerOriginRankingTest {

    private val savedHome = LatLon(47.240371, -122.262019)

    /** The rider is out riding: far from the saved home, so the bundled probe table says nothing. */
    private val liveOrigin = LatLon(47.290000, -122.210000)

    /** Answers a road-route length per destination, nearest destination wins. */
    private class NodeLengthService(private val lengths: Map<LatLon, Double>) : RouteService {
        val destinations = ArrayList<LatLon>()

        override suspend fun route(from: LatLon, to: LatLon, via: List<LatLon>, allowFallback: Boolean): RouteResult {
            destinations.add(to)
            val length = lengths.entries.minByOrNull { Geo.distanceM(it.key, to) }?.value ?: 9_000.0
            return RouteResult(
                shape = PolylineCodec.encode(listOf(from, to)),
                points = listOf(from, to),
                lengthM = length,
                timeS = length / 5.0,
                maneuvers = emptyList(),
                baseUrl = "http://fake",
                usedFallback = false,
                sanityProblem = RouteSanity.check(from, to, length),
            )
        }

        override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult = TraceResult(
            listOf(
                Fixtures.edge(
                    roadClass = "residential",
                    speedLimitKph = 40,
                    name = "Quiet Street",
                    lengthM = 1_000.0,
                ),
            ),
            TraceMode.EDGE_WALK,
            baseUrl,
        )
    }

    /** The first candidate that actually produced a route, or null when every attempt failed. */
    private fun firstRouteNode(result: PlanResult): Long? =
        result.chosen?.accessPoint?.osmNodeId
            ?: result.attempts.mapNotNull { it.route }.firstOrNull()?.accessPoint?.osmNodeId

    /** A failure message that says WHICH attempts failed and why, so a red test explains itself. */
    private fun whyFailed(result: PlanResult): String =
        "chosen=${result.chosen?.accessPoint?.osmNodeId} refusal=${result.refusal} " +
            "attempts=" + result.attempts.joinToString(" | ") { it.label + " -> " + (it.failure ?: "ok") }

    private fun planWith(
        service: RouteService,
        origin: LatLon,
        a: LatLon,
        b: LatLon,
        probeA: Double?,
        probeB: Double?,
    ): PlanResult {
        val trail = Fixtures.trail("Test Trail", a, 8_000.0)
        val apA = Fixtures.accessPoint("Test Trail", a).copy(osmNodeId = 101L, probeRouteKmFromHome = probeA)
        val apB = Fixtures.accessPoint("Test Trail", b).copy(osmNodeId = 202L, probeRouteKmFromHome = probeB)
        return runBlocking {
            Planner(
                network = Fixtures.network(listOf(trail)),
                accessPoints = listOf(apA, apB),
                grader = SafetyGrader(LimitsTable.EMPTY),
                client = service,
                config = PlannerDto(accessPointsPerTrail = 1, maxCandidates = 1, detourAccessPointsPerTrail = 1),
                home = savedHome,
            ).plan(PlanRequest(targetMiles = 10.0, origin = origin, maxCandidates = 1, accessPointsPerTrail = 1))
        }
    }

    @Test
    fun `away from the saved home the entrance is chosen by the route length, not crow-flies`() {
        // A is ~100 m from the live origin; B is ~3 km away. Crow-flies picks A. By road A is 9 km
        // (the far side of a barrier) and B is 2 km, so B is the entrance the rider would use.
        val a = LatLon(liveOrigin.lat + 0.0009, liveOrigin.lon)
        val b = LatLon(liveOrigin.lat + 0.0270, liveOrigin.lon)
        assertTrue("A must be the crow-flies winner", Geo.distanceM(a, liveOrigin) < Geo.distanceM(b, liveOrigin))

        // Both lengths leave a usable trail portion (the 10 mi target is 16.09 km of round trip), so
        // the ONLY thing that can decide the order is the approach distance.
        val service = NodeLengthService(mapOf(a to 4_000.0, b to 2_000.0))
        val result = planWith(service, liveOrigin, a, b, probeA = 1.5, probeB = 1.5)

        val firstNode = result.attempts.firstOrNull()?.route?.accessPoint?.osmNodeId
        assertNotNull(whyFailed(result), firstNode)
        assertEquals("the 2 km entrance must be tried before the 100 m crow-flies one", 202L, firstNode)
        assertTrue("the approach must have been measured for both entrances", service.destinations.size >= 2)
    }

    @Test
    fun `near the saved home the bundled probe distances still decide`() {
        val a = LatLon(savedHome.lat + 0.004, savedHome.lon)
        val b = LatLon(savedHome.lat + 0.006, savedHome.lon)
        // the bundled table says A is the closer approach, while the live router would say B
        val service = NodeLengthService(mapOf(a to 4_000.0, b to 2_000.0))
        val result = planWith(service, savedHome, a, b, probeA = 1.0, probeB = 9.0)

        val firstNode = result.attempts.firstOrNull()?.route?.accessPoint?.osmNodeId
        assertNotNull(whyFailed(result), firstNode)
        assertEquals(
            "the bundled probe table must decide near home, not the routed length",
            101L,
            firstNode,
        )
    }

    @Test
    fun `an entrance that cannot be reached does not break the ranking`() {
        val a = LatLon(liveOrigin.lat + 0.0009, liveOrigin.lon)
        val b = LatLon(liveOrigin.lat + 0.0270, liveOrigin.lon)

        // the router throws for the crow-flies winner: the plan must fall back, not fail
        val service = object : RouteService {
            private val inner = NodeLengthService(mapOf(b to 2_000.0))
            override suspend fun route(from: LatLon, to: LatLon, via: List<LatLon>, allowFallback: Boolean): RouteResult {
                if (Geo.distanceM(to, a) < 200.0) throw IllegalStateException("no route to the near entrance")
                return inner.route(from, to, via, allowFallback)
            }

            override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult = inner.trace(shape, baseUrl, allowFallback)
        }
        val result = planWith(service, liveOrigin, a, b, probeA = 1.5, probeB = 1.5)
        // the near entrance fails, the far one is planned: a broken entrance must not break the plan
        assertEquals(whyFailed(result), 202L, result.chosen?.accessPoint?.osmNodeId)
        assertTrue(
            "the failure must be recorded against the entrance that failed",
            result.attempts.any { it.route == null },
        )
    }
}
