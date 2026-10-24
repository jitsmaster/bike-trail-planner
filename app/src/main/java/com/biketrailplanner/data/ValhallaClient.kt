package com.biketrailplanner.data

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.fmt
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.RawEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Maneuver(
    val instruction: String,
    val lengthM: Double,
    val turnDegree: Int,
    val begin: LatLon?,
)

data class RouteResult(
    val shape: String,
    val points: List<LatLon>,
    val lengthM: Double,
    val timeS: Double,
    val maneuvers: List<Maneuver>,
    val baseUrl: String,
    val usedFallback: Boolean,
    val sanityProblem: String? = null,
) {
    val unverified: Boolean get() = sanityProblem != null
}

enum class TraceMode { EDGE_WALK, MAP_SNAP, FAILED }

data class TraceResult(
    val edges: List<RawEdge>,
    val mode: TraceMode,
    val baseUrl: String,
    val message: String? = null,
) {
    val ok: Boolean get() = mode != TraceMode.FAILED
}

class ValhallaException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * The Valhalla HTTP client (docs/SERVER.md).
 *
 * D5: the primary endpoint is the self-hosted server on the Mac, reached over the tailnet.
 * D12: if that is unreachable, [publicBase] is tried, but only when the caller passes
 * `allowFallback` (the user opts in on the Plan screen; the fallback sends home
 * coordinates to a third-party volunteer server - docs/SECURITY.md SEC-7).
 */
class ValhallaClient(
    private val primaryBase: String,
    private val publicBase: String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : RouteService {

    /** True when the primary endpoint answers /status. */
    suspend fun primaryReachable(): Boolean = try {
        status(primaryBase)
    } catch (e: Exception) {
        false
    }

    suspend fun status(baseUrl: String): Boolean = withRetry("status") {
        val request = Request.Builder().url("$baseUrl/status").get().build()
        http.newCall(request).execute().use { resp -> resp.isSuccessful }
    }

    /** POST /route, home -> [to], with the SPEC section 7 step 3 costing. */
    override suspend fun route(
        from: LatLon,
        to: LatLon,
        via: List<LatLon>,
        allowFallback: Boolean,
    ): RouteResult {
        val attempts = ArrayList<Pair<String, Boolean>>()
        attempts.add(primaryBase to false)
        if (allowFallback && publicBase != primaryBase) attempts.add(publicBase to true)
        var lastError: Exception? = null
        for ((base, isFallback) in attempts) {
            try {
                val body = routeRequest(from, to, via)
                val text = post("$base/route", body)
                return parseRoute(text, base, isFallback, from, to)
            } catch (e: ValhallaException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: ValhallaException("no Valhalla endpoint answered")
    }

    /**
     * POST /trace_attributes on a shape, with the SERVER.md edge filters.
     * Tries `edge_walk` first, then `map_snap` (SPEC section 12). If both fail the caller
     * must mark the route unverified - never safe.
     *
     * D12: [allowFallback] lets a trace with **no route behind it** - a recorded ride - try the public
     * server when the primary is unreachable, exactly the way [route] does. A planned route always
     * passes the endpoint that served it and leaves this false, because a trace must be read against
     * the same graph the route came from.
     */
    override suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean): TraceResult {
        val attempts = ArrayList<String>()
        attempts.add(baseUrl)
        if (allowFallback && publicBase != baseUrl) attempts.add(publicBase)
        var last: TraceResult? = null
        for (base in attempts) {
            val result = traceOn(base, shape)
            if (result.edges.isNotEmpty()) return result
            last = result
        }
        return last ?: TraceResult(emptyList(), TraceMode.FAILED, baseUrl, "no Valhalla endpoint answered")
    }

    /** One /trace_attributes attempt against one endpoint. Never throws; reports failure in the result. */
    private suspend fun traceOn(baseUrl: String, shape: String): TraceResult {
        var lastMessage: String? = null
        for (mode in listOf("edge_walk", "map_snap")) {
            try {
                val text = post("$baseUrl/trace_attributes", traceRequest(shape, mode))
                val edges = parseEdges(text)
                if (edges.isNotEmpty()) {
                    return TraceResult(
                        edges = edges,
                        mode = if (mode == "edge_walk") TraceMode.EDGE_WALK else TraceMode.MAP_SNAP,
                        baseUrl = baseUrl,
                    )
                }
                lastMessage = "$mode returned no edges"
            } catch (e: Exception) {
                lastMessage = "$mode failed: ${e.message}"
            }
        }
        return TraceResult(emptyList(), TraceMode.FAILED, baseUrl, lastMessage)
    }

    // ---------------------------------------------------------------- request bodies

    internal fun routeRequest(from: LatLon, to: LatLon, via: List<LatLon>): JsonObject = buildJsonObject {
        putJsonArray("locations") {
            add(point(from))
            via.forEach { add(point(it)) }
            add(point(to))
        }
        put("costing", "bicycle")
        putJsonObject("costing_options") {
            putJsonObject("bicycle") {
                put("bicycle_type", "hybrid")
                put("use_roads", 0.1)
                put("avoid_bad_surfaces", 0.3)
                put("use_hills", 0.25)
            }
        }
        put("units", "kilometers")
        putJsonObject("directions_options") { put("units", "miles") }
    }

    internal fun traceRequest(shape: String, shapeMatch: String): JsonObject = buildJsonObject {
        put("encoded_polyline", shape)
        put("shape_match", shapeMatch)
        put("costing", "bicycle")
        put("units", "kilometers")
        putJsonObject("filters") {
            put("action", "include")
            putJsonArray("attributes") {
                listOf(
                    "edge.road_class", "edge.use", "edge.surface", "edge.unpaved", "edge.cycle_lane",
                    "edge.bicycle_network", "edge.shoulder", "edge.speed_limit", "edge.sac_scale",
                    "edge.length", "edge.names", "edge.way_id",
                ).forEach { add(it) }
            }
        }
    }

    private fun point(p: LatLon) = buildJsonObject {
        put("lat", p.lat)
        put("lon", p.lon)
    }

    // ---------------------------------------------------------------- parsing

    internal fun parseRoute(text: String, baseUrl: String, fallback: Boolean, from: LatLon, to: LatLon): RouteResult {
        val root = json.parseToJsonElement(text).jsonObject
        val trip = root["trip"]?.jsonObject
            ?: throw ValhallaException("no trip in response: ${text.take(200)}")
        val summary = trip["summary"]?.jsonObject
        val lengthKm = summary?.get("length")?.jsonPrimitive?.content?.toDoubleOrNull()
        val timeS = summary?.get("time")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val legs = trip["legs"]?.jsonArray ?: JsonArray(emptyList())
        if (legs.isEmpty()) throw ValhallaException("no legs in response")
        // DEFECT FIXED 2026-09-26 (D46). A route with via points comes back as ONE LEG PER BREAK, and
        // this used to take `legs.first()` for the SHAPE while taking `trip.summary.length` for the
        // LENGTH. So every route that carried a via point - each D13 quiet detour, each D40/D44
        // small-street proposal - was drawn and graded from its first leg alone while its length, its
        // distance factor and its clock counted every leg. Measured live (build/live-loop-before.txt):
        // entrance -> via -> home returns two legs, and tracing leg 0 by itself reports 1 of the 12
        // outbound ways coming back (8%) where tracing the whole return reports 11 of 12. The legs are
        // therefore concatenated into the single shape the rest of the app treats as "the route", and
        // that shape is re-encoded because `shape` is exactly what `trace_attributes` is handed.
        val points = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        for (legJson in legs) {
            val leg = legJson.jsonObject
            val legPoints = PolylineCodec.decode(leg["shape"]?.jsonPrimitive?.content ?: continue)
            // Consecutive legs share the break vertex: keep it once.
            val skip = if (points.isNotEmpty() && legPoints.isNotEmpty() &&
                Geo.distanceM(points.last(), legPoints.first()) < 0.5
            ) {
                1
            } else {
                0
            }
            for (i in skip until legPoints.size) points.add(legPoints[i])
            // `begin_shape_index` is an index into ITS OWN leg's shape, so each maneuver is resolved
            // against that leg's points rather than against the combined list.
            (leg["maneuvers"] as? JsonArray).orEmpty().mapNotNullTo(maneuvers) { m ->
                val o = m.jsonObject
                val idx = o["begin_shape_index"]?.jsonPrimitive?.content?.toIntOrNull()
                Maneuver(
                    instruction = o["instruction"]?.jsonPrimitive?.content ?: "",
                    lengthM = (o["length"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * 1000.0,
                    turnDegree = o["turn_degree"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    begin = idx?.takeIf { it in legPoints.indices }?.let { legPoints[it] },
                )
            }
        }
        if (points.isEmpty()) throw ValhallaException("no shape in response")
        val shape = PolylineCodec.encode(points)
        val lengthM = (lengthKm ?: 0.0) * 1000.0
        val decodedLength = if (points.size >= 2) Polyline(points).lengthM else 0.0
        val effective = if (lengthM > 0) lengthM else decodedLength
        return RouteResult(
            shape = shape,
            points = points,
            lengthM = effective,
            timeS = timeS,
            maneuvers = maneuvers,
            baseUrl = baseUrl,
            usedFallback = fallback,
            sanityProblem = RouteSanity.check(from, to, effective),
        )
    }

    internal fun parseEdges(text: String): List<RawEdge> {
        val root = json.parseToJsonElement(text).jsonObject
        val edges = root["edges"] as? JsonArray ?: return emptyList()
        return edges.mapNotNull { e ->
            val o = e.jsonObject
            val lengthKm = o["length"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            RawEdge(
                wayId = o["way_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                roadClass = o["road_class"]?.jsonPrimitive?.content,
                use = o["use"]?.jsonPrimitive?.content,
                surface = o["surface"]?.jsonPrimitive?.content,
                unpaved = o["unpaved"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                cycleLane = o["cycle_lane"]?.jsonPrimitive?.content,
                bicycleNetwork = o["bicycle_network"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                shoulder = o["shoulder"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                speedLimitKph = o["speed_limit"]?.jsonPrimitive?.content?.toIntOrNull(),
                lengthM = lengthKm * 1000.0,
                names = (o["names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
            )
        }
    }

    // ---------------------------------------------------------------- transport

    private suspend fun post(url: String, body: JsonObject): String = withRetry("POST $url") {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ValhallaException("HTTP ${resp.code} from $url: ${text.take(200)}", resp.code >= 500)
            text
        }
    }

    private suspend fun <T> withRetry(what: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return@withContext block()
            } catch (e: ValhallaException) {
                lastError = e
                if (!e.retryable && attempt == 0) throw e
            } catch (e: IOException) {
                lastError = e
            }
            delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)])
        }
        throw lastError ?: ValhallaException("$what failed")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(250, 750, 2000)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** SEC-14: do not trust the geometry blindly. A route that fails these checks is unverified, never safe. */
object RouteSanity {
    fun check(from: LatLon, to: LatLon, lengthM: Double): String? {
        val straight = Geo.distanceM(from, to)
        if (lengthM <= 0.0) return "the server returned a zero-length route"
        if (lengthM < straight * 0.95) {
            return "the returned route (${fmt(lengthM, 0)} m) is shorter than the straight line between the points (${fmt(straight, 0)} m)"
        }
        val ceiling = maxOf(straight * 25.0, 60_000.0)
        if (lengthM > ceiling) {
            return "the returned route (${fmt(lengthM, 0)} m) is implausibly long for these points"
        }
        return null
    }
}
