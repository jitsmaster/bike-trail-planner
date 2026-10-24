package com.biketrailplanner

import com.biketrailplanner.data.CachedRouteService
import com.biketrailplanner.data.InMemoryRouteCacheStore
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.TraceMode
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RawEdge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D58: the route cache remembers answers, not failures.
 *
 * Found on a real phone: two `route_cache` rows held `mode=FAILED`, zero edges, and the reason
 * (*"map_snap failed: HTTP 400 ... Insufficient shape provided"*). A cache is keyed on the shape and
 * never expires by content, so remembering a failure makes that route permanently ungradable - the
 * rider sees "not measured" forever, even after the server is back.
 */
class RouteCacheTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun edge(wayId: Long = 42L) = RawEdge(
        wayId = wayId,
        roadClass = "residential",
        use = "road",
        surface = "paved_smooth",
        unpaved = false,
        cycleLane = "none",
        bicycleNetwork = 0,
        shoulder = false,
        speedLimitKph = 40,
        lengthM = 250.0,
        names = listOf("A Street"),
    )

    /** Answers with whatever it is told, and counts how many times it was actually asked. */
    private class FakeService(private val answer: () -> TraceResult) : RouteService {
        var calls = 0
        override suspend fun route(
            from: LatLon,
            to: LatLon,
            via: List<LatLon>,
            allowFallback: Boolean,
        ): RouteResult = throw UnsupportedOperationException("not used")

        override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
            calls++
            return answer()
        }
    }

    @Test
    fun `a failed trace is not remembered, so the next attempt really asks again`() = runBlocking {
        val inner = FakeService {
            TraceResult(emptyList(), TraceMode.FAILED, "http://primary", "both modes failed")
        }
        val service = CachedRouteService(inner, InMemoryRouteCacheStore(), json)

        val first = service.trace("shape", "http://primary", allowFallback = false)
        assertEquals(TraceMode.FAILED, first.mode)
        val second = service.trace("shape", "http://primary", allowFallback = false)

        assertEquals("the failure was cached and handed back as an answer", 2, inner.calls)
        assertTrue(second.edges.isEmpty())
    }

    @Test
    fun `a working trace is remembered and not asked for twice`() = runBlocking {
        val inner = FakeService { TraceResult(listOf(edge()), TraceMode.EDGE_WALK, "http://primary") }
        val service = CachedRouteService(inner, InMemoryRouteCacheStore(), json)

        service.trace("shape", "http://primary", allowFallback = false)
        val second = service.trace("shape", "http://primary", allowFallback = false)

        assertEquals("a good answer should be cached", 1, inner.calls)
        assertEquals(1, second.edges.size)
        assertEquals(42L, second.edges.first().wayId)
    }

    @Test
    fun `a cached trace reports the endpoint that actually answered`() = runBlocking {
        // The public fallback answered, so the cached copy must not claim the primary did.
        val inner = FakeService {
            TraceResult(listOf(edge()), TraceMode.MAP_SNAP, "https://valhalla1.openstreetmap.de")
        }
        val service = CachedRouteService(inner, InMemoryRouteCacheStore(), json)

        service.trace("shape", "http://primary", allowFallback = true)
        val cached = service.trace("shape", "http://primary", allowFallback = true)

        assertEquals("https://valhalla1.openstreetmap.de", cached.baseUrl)
        assertEquals(TraceMode.MAP_SNAP, cached.mode)
    }
}
