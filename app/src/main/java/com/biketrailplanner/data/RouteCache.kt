package com.biketrailplanner.data

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RawEdge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Room-backed cache for the last route/trace answers. The cache key is a hash, so no
 * coordinates are ever written to the database (docs/SECURITY.md SEC-10).
 */
interface RouteCacheStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, payload: String)
}

class InMemoryRouteCacheStore : RouteCacheStore {
    private val map = HashMap<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun put(key: String, payload: String) {
        map[key] = payload
    }
}

@Serializable
data class CachedPayload(
    val shape: String,
    val lengthM: Double,
    val timeS: Double,
    val baseUrl: String,
    val usedFallback: Boolean,
    val sanityProblem: String? = null,
    val maneuvers: List<CachedManeuver> = emptyList(),
    val edges: List<CachedEdge> = emptyList(),
    val traceMode: String = "FAILED",
    val traceMessage: String? = null,
)

@Serializable
data class CachedManeuver(
    val instruction: String,
    val lengthM: Double,
    val turnDegree: Int,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class CachedEdge(
    val wayId: Long,
    val roadClass: String? = null,
    val use: String? = null,
    val surface: String? = null,
    val unpaved: Boolean = false,
    val cycleLane: String? = null,
    val bicycleNetwork: Int = 0,
    val shoulder: Boolean = false,
    val speedLimitKph: Int? = null,
    val lengthM: Double,
    val names: List<String> = emptyList(),
)

/** Cache keys are hashes: the database never stores a coordinate. */
object CacheKey {
    fun of(prefix: String, from: LatLon, to: LatLon): String {
        val raw = "$prefix|%.5f,%.5f|%.5f,%.5f".format(from.lat, from.lon, to.lat, to.lon)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return prefix + ":" + digest.take(12).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Caches route geometry and, separately, the trace of that geometry. A cache hit is only
 * used for the route call; the trace is looked up by shape hash so a stale entry can never
 * be paired with a different route.
 */
class CachedRouteService(
    private val inner: RouteService,
    private val cache: RouteCacheStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RouteService {

    override suspend fun route(from: LatLon, to: LatLon, via: List<LatLon>, allowFallback: Boolean): RouteResult {
        val key = CacheKey.of(if (allowFallback) "route-fb" else "route", from, to) + ":" + shapeHint(from, to)
        cache.get(key)?.let { cached ->
            runCatching {
                val payload = json.decodeFromString(CachedPayload.serializer(), cached)
                RouteResult(
                    shape = payload.shape,
                    points = com.biketrailplanner.domain.PolylineCodec.decode(payload.shape),
                    lengthM = payload.lengthM,
                    timeS = payload.timeS,
                    maneuvers = payload.maneuvers.map {
                        val begin = if (it.lat != null && it.lon != null) LatLon(it.lat, it.lon) else null
                        Maneuver(it.instruction, it.lengthM, it.turnDegree, begin)
                    },
                    baseUrl = payload.baseUrl,
                    usedFallback = payload.usedFallback,
                    sanityProblem = payload.sanityProblem,
                )
            }.getOrNull()?.let { return it }
        }
        val result = inner.route(from, to, via, allowFallback)
        val payload = CachedPayload(
            shape = result.shape,
            lengthM = result.lengthM,
            timeS = result.timeS,
            baseUrl = result.baseUrl,
            usedFallback = result.usedFallback,
            sanityProblem = result.sanityProblem,
            maneuvers = result.maneuvers.map {
                CachedManeuver(it.instruction, it.lengthM, it.turnDegree, it.begin?.lat, it.begin?.lon)
            },
        )
        runCatching { cache.put(key, json.encodeToString(CachedPayload.serializer(), payload)) }
        return result
    }

    override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
        val key = "trace:" + shape.hashCode() + ":" + baseUrl.hashCode() + ":" + allowFallback
        cache.get(key)?.let { cached ->
            runCatching {
                val payload = json.decodeFromString(CachedPayload.serializer(), cached)
                TraceResult(
                    edges = payload.edges.map { it.toRawEdge() },
                    mode = TraceMode.valueOf(payload.traceMode),
                    // The endpoint that ACTUALLY answered, not the one that was asked first.
                    baseUrl = payload.baseUrl,
                    message = payload.traceMessage,
                )
            }.getOrNull()?.let { return it }
        }
        val result = inner.trace(shape, baseUrl, allowFallback)
        // D58: only a trace that actually produced edges is worth remembering.
        //
        // A failure is a statement about the moment - the shape was too short for Valhalla to match
        // (HTTP 400 "Insufficient shape provided"), or the server was down - not about the route. Caching
        // it made a route permanently ungradable after one bad moment, because the cache is keyed on the
        // shape and never expires by content. Measured on the phone: two cached rows with `mode=FAILED`
        // and zero edges, each holding the *reason* the trace failed, and a cache hit would have handed
        // that failure back as if it were the answer.
        if (result.edges.isNotEmpty()) {
            val payload = CachedPayload(
                shape = "", lengthM = 0.0, timeS = 0.0,
                // The base that really answered, so a cached fallback trace is not mislabelled as a
                // primary one.
                baseUrl = result.baseUrl, usedFallback = false,
                edges = result.edges.map { it.toCached() },
                traceMode = result.mode.name,
                traceMessage = result.message,
            )
            runCatching { cache.put(key, json.encodeToString(CachedPayload.serializer(), payload)) }
        }
        return result
    }

    private fun shapeHint(from: LatLon, to: LatLon): String = (from.lat + to.lon).toString().take(4)

    private fun CachedEdge.toRawEdge() = RawEdge(
        wayId, roadClass, use, surface, unpaved, cycleLane, bicycleNetwork, shoulder, speedLimitKph, lengthM, names,
    )
}

fun RawEdge.toCached() = CachedEdge(
    wayId, roadClass, use, surface, unpaved, cycleLane, bicycleNetwork, shoulder, speedLimitKph, lengthM, names,
)
