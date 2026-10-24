package com.biketrailplanner

import com.biketrailplanner.data.Maneuver
import com.biketrailplanner.data.PlannerDto
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.RouteSanity
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.TraceMode
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.MapsUrlBuilder
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.RouteOverlay
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.ui.RiderText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D31 (`docs/LOOPS.md`) end to end through the real [Planner]: the rider asked not to come back the
 * same way, and the app may only do it when the loop still passes D10 and fits the 5 km budget.
 *
 * The network is faked (a return leg that carries a via point uses a different road, one that does
 * not is the mirrored outbound), so these tests run in milliseconds. The real server is exercised by
 * `LivePlanProbeTest`.
 */
class PlannerLoopTest {

    private val home = LatLon(47.240371, -122.262019)
    private val pointA = LatLon(47.250000, -122.250000)
    private val pointB = LatLon(47.270000, -122.240000)

    /** One road: how it grades and which OSM ways it uses (the identity the overlap metric reads). */
    private data class Road(
        val wayIds: List<Long> = listOf(1L, 2L, 3L),
        val roadClass: String = "residential",
        val speedKph: Int? = 40,
    )

    /**
     * The way back can be a different road, or the same one.
     *
     * Any route whose destination is home is a RETURN leg (the planner's outbound leg goes to the
     * access point). A return that carries a via point is answered with [loopRoad]; one that does not
     * is answered with [sameWayBack]. The return's shape runs THROUGH the via point, exactly as
     * Valhalla would route it, so the extra distance is the real cost of the detour.
     */
    private class LoopRoutes(
        private val home: LatLon,
        private val outbound: Road,
        private val sameWayBack: Road,
        private val loopRoad: Road,
    ) : RouteService {
        var viaCount = 0
        var returnCalls = 0
        private var last: Road = outbound

        override suspend fun route(
            from: LatLon,
            to: LatLon,
            via: List<LatLon>,
            allowFallback: Boolean,
        ): RouteResult {
            if (via.isNotEmpty()) viaCount++
            if (Geo.distanceM(to, home) < 200.0) returnCalls++
            val isReturn = Geo.distanceM(to, home) < 200.0
            val road = when {
                !isReturn -> outbound
                via.isEmpty() -> sameWayBack
                else -> loopRoad
            }
            last = road
            val points = if (via.isEmpty()) {
                straight(from, to)
            } else {
                // Through the via, which is what makes the loop longer than the direct way back.
                straight(from, via[0]) + straight(via[0], to).drop(1)
            }
            return RouteResult(
                shape = PolylineCodec.encode(points),
                points = points,
                lengthM = Polyline(points).lengthM,
                timeS = Polyline(points).lengthM / 5.0,
                maneuvers = emptyList<Maneuver>(),
                baseUrl = "http://fake",
                usedFallback = false,
                sanityProblem = RouteSanity.check(from, to, Polyline(points).lengthM),
            )
        }

        override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
            val road = last
            val each = Polyline(PolylineCodec.decode(shape)).lengthM / road.wayIds.size
            return TraceResult(
                road.wayIds.map {
                    Fixtures.edge(
                        wayId = it,
                        roadClass = road.roadClass,
                        speedLimitKph = road.speedKph,
                        name = "Street " + it,
                        lengthM = each,
                    )
                },
                TraceMode.EDGE_WALK,
                baseUrl,
            )
        }

        private companion object {
            const val STEPS = 20
            fun straight(from: LatLon, to: LatLon): List<LatLon> = (0..STEPS).map { i ->
                val t = i.toDouble() / STEPS
                LatLon(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
            }
        }
    }

    private fun trails(): List<Trail> = listOf(
        Fixtures.trail("Trail A", pointA, 8_000.0),
        Fixtures.trail("Trail B", pointB, 8_000.0),
    )

    private fun accessPoints(): List<AccessPoint> = listOf(
        Fixtures.accessPoint("Trail A", pointA).copy(osmNodeId = 101L, probeRouteKmFromHome = 1.0),
        Fixtures.accessPoint("Trail B", pointB).copy(osmNodeId = 202L, probeRouteKmFromHome = 5.0),
    )

    private fun plan(client: RouteService, config: PlannerDto = defaults()): PlanResult = runBlocking {
        Planner(
            network = Fixtures.network(trails()),
            accessPoints = accessPoints(),
            grader = SafetyGrader(LimitsTable.EMPTY),
            client = client,
            config = config,
            home = home,
        ).plan(
            PlanRequest(
                targetMiles = 10.0,
                origin = home,
                maxCandidates = 4,
                accessPointsPerTrail = 2,
                allowAi = false,
            ),
        )
    }

    private fun defaults(loopMaxExtraKm: Double = 5.0) = PlannerDto(
        accessPointsPerTrail = 2,
        maxCandidates = 4,
        detourAccessPointsPerTrail = 2,
        loopMaxExtraKm = loopMaxExtraKm,
    )

    private fun loopClient(loopRoad: Road = Road(wayIds = listOf(7L, 8L, 9L))) =
        LoopRoutes(home, Road(wayIds = listOf(1L, 2L, 3L)), Road(wayIds = listOf(1L, 2L, 3L)), loopRoad)

    // ---- the loop is taken ------------------------------------------------------------------

    @Test
    fun `the rider comes back a different way when a safe one exists`() {
        val client = loopClient()
        val result = plan(client)
        val route = result.chosen
        assertNotNull("a ride must still be planned", route)
        val loop = route!!.loop
        assertNotNull("a different way back was available and had to be taken", loop)
        assertNotNull("the real return leg must be carried on the ride", route.returnCommute)
        assertTrue("the return leg must have its own graded edges", route.returnSegments.isNotEmpty())
        assertTrue("the return must not use the outbound ways", loop!!.overlapShare < 0.01)
        assertEquals("nothing new is left when the return re-uses nothing", 1.0, loop.newRoadShare, 0.01)
        assertTrue("the loop must look for a way back", client.viaCount > 0)
    }

    @Test
    fun `the way back is drawn as it is ridden, not as the outbound mirrored`() {
        val result = plan(loopClient())
        val route = result.chosen!!
        val shape = Polyline(route.roundTrip)
        // The return leg really passes through the loop's via point...
        assertTrue(
            "the drawn trip must pass through the loop's via point",
            shape.project(route.loop!!.viaPoint).offsetM < 5.0,
        )
        // ... and the drawn trip still ends at the origin.
        assertTrue(Geo.distanceM(route.roundTrip.last(), home) < 5.0)
        // The trip is as long as its parts say: out + return + trail, and no leg is counted twice.
        val expected = route.commute.lengthM + route.returnCommute!!.lengthM + route.trailRoundTripM
        assertEquals(expected, route.totalM, 1.0)
        assertEquals(route.returnCommute!!.lengthM, route.commuteM - route.commute.lengthM, 0.5)
    }

    @Test
    fun `the map colours the return leg from its own edges`() {
        val looped = plan(loopClient()).chosen!!
        val runs = RouteOverlay.build(looped)
        val back = runs.filter { it.label == "return" }
        assertEquals(
            "one run per graded return edge, coloured from the return leg itself",
            looped.returnSegments.size,
            back.size,
        )
        assertTrue("the return runs must not be the outbound runs reversed", runs.any { it.label == "return" })
    }

    @Test
    fun `the different way back is handed to Google Maps as a waypoint`() {
        val looped = plan(loopClient()).chosen!!
        val url = MapsUrlBuilder.build(looped, home)
        assertTrue(
            "without the loop's via point Google re-routes the way back as the way out",
            url.included.any { it.kind == "loop via" },
        )
        assertTrue(url.url.contains("waypoints="))
    }

    @Test
    fun `the rider is told what came back new`() {
        val looped = plan(loopClient()).chosen!!
        val sentence = RiderText.loopSentence(looped)
        assertNotNull(sentence)
        assertTrue("the rider must be told the share that is new: $sentence", sentence!!.contains("% of the roads on the way back are new"))
    }

    // ---- and when it cannot be taken ---------------------------------------------------------

    @Test
    fun `the same way back is kept, and said plainly, when no different way back exists`() {
        // Every return leg is answered with the outbound's own ways, which is what the mirrored
        // return is: the same roads back.
        val client = LoopRoutes(home, Road(), Road(), Road())
        val result = plan(client)
        val route = result.chosen
        assertNotNull(route)
        assertNull("a return that re-uses the outbound is NOT a loop", route!!.loop)
        assertNull(route.returnCommute)
        assertTrue("the search must be recorded as having run", route.loopSearched)
        assertEquals(
            "No different safe way back was found, so this one comes back the same way.",
            RiderText.loopSentence(route),
        )
    }

    @Test
    fun `a loop is never bought with safety`() {
        // The only different way back runs on a primary road, which is grade E: D10 forbids it, so the
        // rider rides the mirrored out-and-back instead.
        val client = loopClient(Road(wayIds = listOf(7L, 8L, 9L), roadClass = "primary", speedKph = 90))
        val route = plan(client).chosen
        assertNotNull(route)
        assertNull("an unsafe way back must never be taken to make a loop", route!!.loop)
        assertTrue(route.loopSearched)
    }

    @Test
    fun `the riders five kilometre budget is a hard limit`() {
        // Same working loop as the first test, but with a budget too small for any offset in the fan:
        // every way back costs more than the rider allowed, so the ride comes back the same way.
        val client = loopClient()
        val route = plan(client, defaults(loopMaxExtraKm = 0.05)).chosen
        assertNotNull(route)
        assertNull("a loop past the rider's budget must not be taken", route!!.loop)
        assertTrue("and the app must say the search happened", route.loopSearched)
    }

    @Test
    fun `switching the preference off leaves the planner exactly as it was`() {
        val route = plan(loopClient(), PlannerDto(preferLoop = false, accessPointsPerTrail = 2, maxCandidates = 4, detourAccessPointsPerTrail = 2)).chosen
        assertNotNull(route)
        assertNull(route!!.loop)
        assertNull(route.returnCommute)
        // No search ran, so the app may NOT claim it looked: the sentence is only for a search that ran.
        assertTrue(!route.loopSearched)
        assertNull(RiderText.loopSentence(route))
    }
}
