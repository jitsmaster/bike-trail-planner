package com.biketrailplanner.data

import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.domain.TrailNetwork
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** A discovered relation that a bundled trail already covers, and why it was left out. */
data class CoveredTrail(val name: String, val osmRelationId: Long, val reason: String)

/**
 * What one discovery call produced: the trails, the access points sampled from them, and the
 * provenance of the answer they came from (D23, docs/TRAIL-DISCOVERY.md).
 *
 * [mirror] and [timestampOsmBase] are the freshness story the UI shows: found from that instance,
 * with OpenStreetMap data of that age. Every discovered trail is `verified == false` and stays that
 * way - a bundled regional trail is unverified too, and neither may be presented as one of the
 * verified near-home six.
 */
data class DiscoveryResult(
    val origin: LatLon,
    val radiusKm: Double,
    val trails: List<Trail>,
    /** Access points for [trails] only, nearest to [origin] first. */
    val accessPoints: List<AccessPoint>,
    /** Discovered relations a bundled trail already covers. Reported, never deleted silently. */
    val coveredByBundled: List<CoveredTrail>,
    /** Relations dropped because another relation in the same answer had the same geometry. */
    val duplicatesCollapsed: List<String>,
    val fromCache: Boolean,
    val mirror: String?,
    val timestampOsmBase: String?,
    val fetchedAtMs: Long,
    val jsonBytes: Int,
    /** How many route relations the answer carried, before any filter or merge. */
    val relationsInAnswer: Int = 0,
    /** How long the accepted mirror took to answer, in milliseconds. */
    val queryMs: Long = 0,
    /**
     * Every mirror that was tried for this answer, with the timestamp each one served and whether it
     * was accepted. Empty for a cached answer: no mirror was asked.
     */
    val attempts: List<MirrorAttempt> = emptyList(),
    /** Mirrors that answered with fresh data carrying no route relation at all. */
    val emptyMirrors: List<String> = emptyList(),
    /**
     * True when two mirrors were asked and both found nothing here. Only then is "no trails found"
     * a verdict the app may repeat; one mirror saying nothing is not (see OverpassClient).
     */
    val emptyConfirmed: Boolean = false,
    /**
     * True when every mirror failed and this is the cached answer for the bucket, kept and shown
     * rather than failing - marked with its age and with why Overpass could not be reached.
     */
    val fromStaleCache: Boolean = false,
    /** Why the mirrors failed, when this is a [fromStaleCache] answer. Never contains coordinates. */
    val staleReason: String? = null,
) {
    val trailNames: List<String> get() = trails.map { it.name }

    /** How long ago this answer was fetched. Null provenance only when it came from nowhere. */
    fun fetchedAgeMs(nowMs: Long): Long = nowMs - fetchedAtMs

    val empty: Boolean get() = trails.isEmpty()
}

/**
 * Turns an Overpass answer into the app's own model: [Trail] objects and the [AccessPoint]s sampled
 * from them (D23).
 *
 * Honesty rules this parser keeps, all of them visible in the returned [Trail]:
 *  - `verified = false` always. Nothing here has been closure-checked or surface-audited.
 *  - no closures at all (`closures = emptyList()`, `closedM = 0`): none are known for a discovered
 *    trail, and inventing them would be worse than not having them.
 *  - `paved = false`, `surface = emptyList()` and the whole length counted as untagged surface: the
 *    app has no surface audit for a discovered trail, so it must not claim one.
 *  - `rideableM` equals the geometry length, because no closure is known to subtract.
 *  - `loop` is true only when the geometry really closes on itself.
 *  - `probeRouteKmFromHome = null` on every access point: the approach distance is genuinely
 *    unknown, and the planner MEASURES it live from the rider's position (Planner.liveApproachM).
 *  - Access point OSM node ids are NEGATIVE synthetic ids: a sampled point is not an OSM node, and a
 *    negative id can never collide with a real node id (always positive).
 */
internal object DiscoveryParser {

    /** A relation whose geometry is shorter than this is a mapping fragment, not a ride. */
    const val MIN_TRAIL_M = 200.0

    /** Interior access-point spacing along a discovered trail. */
    const val ACCESS_SPACING_M = 300.0

    /** Most access points kept per trail, endpoints included. */
    const val MAX_ACCESS_POINTS_PER_TRAIL = 12

    /** A geometry that comes back to within this of its start is a loop. */
    const val LOOP_CLOSE_M = 120.0

    /** Two relations are the same trail when this share of their member ways is the same. */
    const val DUPLICATE_WAY_JACCARD = 0.8

    /** SAME-NAME relations: this share of the shorter one's geometry lying on the longer, within 50 m. */
    const val DUPLICATE_COVERAGE = 0.9

    /** A discovered trail is "the same corridor" as a bundled one within this distance. */
    const val BUNDLED_NEAR_M = 300.0

    /** ... when this share of the discovered geometry lies that close to the bundled geometry. */
    const val BUNDLED_COVERAGE = 0.6

    /** Sampling step used by the geometry checks above. */
    const val CHECK_STEP_M = 300.0

    /** Stride that packs a relation id and a sample index into one synthetic access-point id. */
    private const val SYNTHETIC_ID_STRIDE = 100_000L

    /** One relation, before the duplicate/merge/name decisions. */
    private data class Candidate(
        val relationId: Long,
        val rawName: String?,
        val wayIds: List<Long>,
        val points: List<LatLon>,
    )

    data class Parsed(
        val trails: List<Trail>,
        val accessPoints: List<AccessPoint>,
        val coveredByBundled: List<CoveredTrail>,
        val duplicatesCollapsed: List<String>,
        /** Route relations the answer carried (routeRelationCount), before the length filter. */
        val relationsInAnswer: Int,
    )

    fun parse(root: JsonObject, origin: LatLon, bundled: List<Trail>): Parsed {
        val candidates = candidates(root)
        val kept = ArrayList<Candidate>()
        val keptPolylines = ArrayList<Polyline>()
        val duplicates = ArrayList<String>()
        val byLength = candidates.sortedWith(
            compareByDescending<Candidate> { lengthM(it.points) }.thenBy { it.relationId },
        )
        for (candidate in byLength) {
            val dupe = kept.indices.firstOrNull { i ->
                wayJaccard(kept[i].wayIds, candidate.wayIds) >= DUPLICATE_WAY_JACCARD ||
                    (
                        sameName(kept[i].rawName, candidate.rawName) &&
                            coverage(candidate.points, keptPolylines[i], 50.0) >= DUPLICATE_COVERAGE
                        )
            }
            if (dupe != null) {
                duplicates.add("r${candidate.relationId} (${candidate.rawName ?: "unnamed"}) repeats r${kept[dupe].relationId}")
                continue
            }
            kept.add(candidate)
            keptPolylines.add(Polyline(candidate.points))
        }

        val covered = ArrayList<CoveredTrail>()
        val bundledByName = bundled.map { it.name.trim().lowercase(Locale.US) to it.fullGeometry }
        val distinct = ArrayList<Pair<Candidate, Polyline>>()
        for (i in kept.indices) {
            val candidate = kept[i]
            val name = candidate.rawName?.trim()?.lowercase(Locale.US)
            val hit = if (name.isNullOrBlank()) {
                null
            } else {
                bundledByName.firstOrNull { (bundledName, geometry) ->
                    bundledName == name && coverage(candidate.points, geometry, BUNDLED_NEAR_M) >= BUNDLED_COVERAGE
                }
            }
            if (hit != null) {
                covered.add(
                    CoveredTrail(
                        name = candidate.rawName ?: "unnamed",
                        osmRelationId = candidate.relationId,
                        reason = "a bundled trail of the same name already covers this corridor " +
                            "(geometry within ${BUNDLED_NEAR_M.toInt()} m)",
                    ),
                )
                continue
            }
            distinct.add(candidate to keptPolylines[i])
        }

        // A name is the app's trail identity (TrailNetwork.trail(name), corridors, joins), so names
        // must be unique. A relation whose name is already in use gets its OSM id appended: never a
        // silent rename, and never one name for two different corridors.
        val used = HashSet<String>()
        bundled.forEach { used.add(it.name) }
        val trails = ArrayList<Trail>()
        val accessPoints = ArrayList<AccessPoint>()
        for ((candidate, polyline) in distinct) {
            val base = candidate.rawName?.takeIf { it.isNotBlank() } ?: "Unnamed bike route"
            var name = base
            if (!used.add(name)) {
                name = "$base (r${candidate.relationId})"
                used.add(name)
            }
            trails.add(
                Trail(
                    name = name,
                    osmRelationId = candidate.relationId,
                    osmUrl = "https://www.openstreetmap.org/relation/${candidate.relationId}",
                    lengthM = polyline.lengthM,
                    rideableM = polyline.lengthM,
                    paved = false,
                    loop = Geo.distanceM(polyline.points.first(), polyline.points.last()) <= LOOP_CLOSE_M,
                    verified = false,
                    surface = emptyList(),
                    untaggedSurfaceM = polyline.lengthM,
                    nearestFromHomeM = polyline.points.minOf { Geo.distanceM(origin, it) },
                    farthestFromHomeM = polyline.points.maxOf { Geo.distanceM(origin, it) },
                    closedM = 0.0,
                    closures = emptyList(),
                    fullGeometry = polyline,
                    rideableSpans = listOf(polyline),
                ),
            )
            accessPoints.addAll(accessPointsFor(name, candidate, polyline, origin))
        }
        return Parsed(trails, accessPoints, covered, duplicates, routeRelationCount(root))
    }

    /** The relations in one answer that carry rideable geometry. */
    private fun candidates(root: JsonObject): List<Candidate> {
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (element in elements) {
            val rel = element as? JsonObject ?: continue
            if (rel["type"]?.jsonPrimitive?.content != "relation") continue
            val tags = rel["tags"]?.jsonObject ?: continue
            val route = tags["route"]?.jsonPrimitive?.content ?: continue
            if (route != "bicycle" && route != "mtb") continue
            val id = rel["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val wayIds = ArrayList<Long>()
            val points = ArrayList<LatLon>()
            // Members arrive in relation order; a way that starts where the previous one ended
            // shares that node, so the shared point is not repeated.
            for (member in (rel["members"] as? JsonArray).orEmpty()) {
                val m = member as? JsonObject ?: continue
                if (m["type"]?.jsonPrimitive?.content != "way") continue
                m["ref"]?.jsonPrimitive?.content?.toLongOrNull()?.let { wayIds.add(it) }
                for (g in (m["geometry"] as? JsonArray).orEmpty()) {
                    val o = g as? JsonObject ?: continue
                    val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
                    val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
                    val p = LatLon(lat, lon)
                    if (points.isNotEmpty() && samePoint(points.last(), p)) continue
                    points.add(p)
                }
            }
            if (points.size < 2) continue
            if (lengthM(points) < MIN_TRAIL_M) continue
            out.add(Candidate(id, tags["name"]?.jsonPrimitive?.content, wayIds, points))
        }
        return out
    }

    /**
     * Access points for one discovered trail: the two endpoints ALWAYS, plus interior points every
     * [ACCESS_SPACING_M], capped at [MAX_ACCESS_POINTS_PER_TRAIL] by crow-flies distance from
     * [origin] so a long trail far from the rider does not flood the planner with entrances.
     *
     * The id is synthetic and always negative: `-(relationId * 100000 + sampleIndex)`. A sampled
     * point is NOT an OSM node, and a negative id can never collide with a real one, so nothing
     * downstream can mistake it for OSM data. `probeRouteKmFromHome` is null by design.
     */
    private fun accessPointsFor(
        trailName: String,
        candidate: Candidate,
        polyline: Polyline,
        origin: LatLon,
    ): List<AccessPoint> {
        val sampled = sample(polyline, ACCESS_SPACING_M)
        if (sampled.isEmpty()) return emptyList()
        val keep = LinkedHashSet<Int>()
        keep.add(sampled.first().first)
        keep.add(sampled.last().first)
        val interior = if (sampled.size <= 2) emptyList() else sampled.subList(1, sampled.size - 1)
        for (s in interior.sortedBy { Geo.distanceM(origin, it.second) }) {
            if (keep.size >= MAX_ACCESS_POINTS_PER_TRAIL) break
            keep.add(s.first)
        }
        return sampled
            .filter { it.first in keep }
            .sortedBy { Geo.distanceM(origin, it.second) }
            .map { (index, point) ->
                AccessPoint(
                    trailName = trailName,
                    osmRelationId = candidate.relationId,
                    osmNodeId = -(candidate.relationId * SYNTHETIC_ID_STRIDE + index),
                    point = point,
                    onRoad = null,
                    highway = null,
                    sharesTrailNode = true,
                    straightLineMFromHome = Geo.distanceM(origin, point),
                    probeRouteKmFromHome = null,
                )
            }
    }

    /** Points at arc 0, step, 2*step, ..., lengthM, each with its sample index. Ends always present. */
    internal fun sample(polyline: Polyline, stepM: Double): List<Pair<Int, LatLon>> {
        val out = ArrayList<Pair<Int, LatLon>>()
        val length = polyline.lengthM
        out.add(0 to polyline.points.first())
        var arc = stepM
        var index = 1
        while (arc < length - 1.0) {
            out.add(index to polyline.pointAt(arc))
            arc += stepM
            index++
        }
        if (!samePoint(out.last().second, polyline.points.last())) {
            out.add(index to polyline.points.last())
        }
        return out
    }

    /** The share of [points] (sampled every [CHECK_STEP_M]) within [toleranceM] of [target]. */
    internal fun coverage(points: List<LatLon>, target: Polyline, toleranceM: Double): Double {
        if (points.size < 2) return 0.0
        val sampled = sample(Polyline(points), CHECK_STEP_M)
        if (sampled.isEmpty()) return 0.0
        val near = sampled.count { target.project(it.second).offsetM <= toleranceM }
        return near.toDouble() / sampled.size
    }

    private fun lengthM(points: List<LatLon>): Double {
        var acc = 0.0
        for (i in 1 until points.size) acc += Geo.distanceM(points[i - 1], points[i])
        return acc
    }

    /**
     * Same trail, same name. Two relations with different names that merely overlap (a long
     * superrelation and a named piece of it) are NOT duplicates: the names are different rides to
     * the planner, and dropping one would silently delete a trail the rider can see on the map.
     */
    private fun sameName(a: String?, b: String?): Boolean {
        val na = a?.trim()?.lowercase(Locale.US) ?: return false
        val nb = b?.trim()?.lowercase(Locale.US) ?: return false
        return na.isNotEmpty() && na == nb
    }

    private fun wayJaccard(a: List<Long>, b: List<Long>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toHashSet()
        val sb = b.toHashSet()
        val common = sa.count { it in sb }
        val union = sa.size + sb.size - common
        return if (union == 0) 0.0 else common.toDouble() / union
    }

    /** ~1 cm at these latitudes: the same OSM node, not merely a nearby one. */
    private fun samePoint(a: LatLon, b: LatLon): Boolean =
        Math.abs(a.lat - b.lat) < 1e-7 && Math.abs(a.lon - b.lon) < 1e-7
}

/**
 * Live trail discovery (D23). One call answers "are there bike trails near here?": a fresh cached
 * answer for the ~1 km bucket is used as is, otherwise Overpass is asked with [DiscoveryQuery] and
 * the answer is cached for next time.
 *
 * The result MERGES with the bundled trails, it does not replace them: relations that a bundled trail
 * already covers are reported in [DiscoveryResult.coveredByBundled] and left out. Use [DiscoveryMerge]
 * to build what the planner sees.
 */
class TrailDiscovery(
    private val client: OverpassClient,
    private val cache: DiscoveryCache? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DiscoveryCachePolicy.TTL_MS,
) {

    /** @param forceRefresh the rider asked again: skip the cache, but still write the answer back. */
    suspend fun discover(
        origin: LatLon,
        radiusKm: Double,
        forceRefresh: Boolean = false,
        bundled: List<Trail> = emptyList(),
    ): DiscoveryResult {
        val bucket = RouteKey.bucket(origin)
        val cached = cache?.get(bucket)
        if (!forceRefresh) {
            val root = cached?.takeIf { usable(it, radiusKm) }?.let { runCatching { client.parse(it.payload) }.getOrNull() }
            if (cached != null && root != null) {
                return build(
                    root = root,
                    origin = origin,
                    radiusKm = radiusKm,
                    bundled = bundled,
                    fromCache = true,
                    mirror = cached.mirror,
                    stamp = cached.timestampOsmBase,
                    fetchedAt = cached.fetchedAt,
                    bytes = cached.payload.toByteArray(Charsets.UTF_8).size,
                    queryMs = 0,
                    emptyMirrors = emptyList(),
                    emptyConfirmed = routeRelationCount(root) == 0,
                )
            }
        }
        val answer = try {
            client.query(DiscoveryQuery.text(origin, radiusKm))
        } catch (e: OverpassException) {
            // An old answer the app can date beats no answer at all, but only an answer that really
            // covers the search: a cached 5 km row must not pretend to answer a 20 km request.
            val root = cached
                ?.takeIf { it.radiusKm >= radiusKm }
                ?.let { runCatching { client.parse(it.payload) }.getOrNull() }
                ?: throw e
            return build(
                root = root,
                origin = origin,
                radiusKm = radiusKm,
                bundled = bundled,
                fromCache = true,
                mirror = cached!!.mirror,
                stamp = cached.timestampOsmBase,
                fetchedAt = cached.fetchedAt,
                bytes = cached.payload.toByteArray(Charsets.UTF_8).size,
                queryMs = 0,
                emptyMirrors = emptyList(),
                emptyConfirmed = routeRelationCount(root) == 0,
                fromStaleCache = true,
                staleReason = e.summary,
            )
        }
        cache?.put(
            DiscoveredTrailEntity(
                bucket = bucket,
                originLat = origin.lat,
                originLon = origin.lon,
                radiusKm = radiusKm,
                payload = answer.json,
                fetchedAt = answer.fetchedAtMs,
                timestampOsmBase = answer.timestampOsmBase,
                mirror = answer.mirror,
            ),
        )
        return build(
            root = answer.root,
            origin = origin,
            radiusKm = radiusKm,
            bundled = bundled,
            fromCache = false,
            mirror = answer.mirror,
            stamp = answer.timestampOsmBase,
            fetchedAt = answer.fetchedAtMs,
            bytes = answer.jsonBytes,
            queryMs = answer.elapsedMs,
            attempts = answer.attempts,
            emptyMirrors = answer.emptyMirrors,
            emptyConfirmed = answer.emptyConfirmed,
        )
    }

    /** A cached row is usable when it is inside the TTL and was searched at least this wide. */
    private fun usable(row: DiscoveredTrailEntity, radiusKm: Double): Boolean =
        clock() - row.fetchedAt <= ttlMs && row.radiusKm >= radiusKm

    private fun build(
        root: JsonObject,
        origin: LatLon,
        radiusKm: Double,
        bundled: List<Trail>,
        fromCache: Boolean,
        mirror: String?,
        stamp: String?,
        fetchedAt: Long,
        bytes: Int,
        queryMs: Long,
        attempts: List<MirrorAttempt> = emptyList(),
        emptyMirrors: List<String> = emptyList(),
        emptyConfirmed: Boolean = false,
        fromStaleCache: Boolean = false,
        staleReason: String? = null,
    ): DiscoveryResult {
        val parsed = DiscoveryParser.parse(root, origin, bundled)
        return DiscoveryResult(
            origin = origin,
            radiusKm = radiusKm,
            trails = parsed.trails,
            accessPoints = parsed.accessPoints,
            coveredByBundled = parsed.coveredByBundled,
            duplicatesCollapsed = parsed.duplicatesCollapsed,
            fromCache = fromCache,
            mirror = mirror,
            timestampOsmBase = stamp,
            fetchedAtMs = fetchedAt,
            jsonBytes = bytes,
            relationsInAnswer = parsed.relationsInAnswer,
            queryMs = queryMs,
            attempts = attempts,
            emptyMirrors = emptyMirrors,
            emptyConfirmed = emptyConfirmed,
            fromStaleCache = fromStaleCache,
            staleReason = staleReason,
        )
    }
}

/**
 * How discovery joins the bundled data. A discovered trail never replaces a bundled one: the bundled
 * six carry measured traffic counts and closure checks, a discovered relation carries neither, and a
 * bundled REGIONAL trail is unverified as well. The planner is handed the union, with the discovered
 * trails marked `verified = false` so the two can never be confused.
 */
object DiscoveryMerge {

    /** The bundled network plus the discovered trails. Discovered trails bring no joins or corridors. */
    fun network(bundled: TrailNetwork, discovered: DiscoveryResult): TrailNetwork = TrailNetwork(
        trails = bundled.trails + discovered.trails,
        joins = bundled.joins,
        corridors = bundled.corridors,
        roadLinks = bundled.roadLinks,
    )

    /** The bundled access points plus the discovered ones. */
    fun accessPoints(bundled: List<AccessPoint>, discovered: DiscoveryResult): List<AccessPoint> =
        bundled + discovered.accessPoints

    /**
     * One rider-facing line: what was found, from which mirror, dated how old - and, when nothing was
     * found, how strong that "nothing" is. An empty answer another mirror never seconded says so
     * instead of reading like a verdict.
     */
    fun summary(discovered: DiscoveryResult, nowMs: Long): String = buildString {
        if (discovered.empty) {
            append("no trails found within ")
            append(km(discovered.radiusKm))
            append(" km")
            when {
                discovered.emptyConfirmed ->
                    append("; confirmed by ").append(discovered.emptyMirrors.joinToString(" and ") { host(it) })
                discovered.emptyMirrors.isNotEmpty() ->
                    append("; only ").append(host(discovered.emptyMirrors.first()))
                        .append(" answered, so another try may still find some")
                else -> append("; the cached answer found none")
            }
        } else {
            append(discovered.trails.size)
            append(if (discovered.trails.size == 1) " trail" else " trails")
            append(" within ")
            append(km(discovered.radiusKm))
            append(" km")
            if (discovered.coveredByBundled.isNotEmpty()) {
                append(", ").append(discovered.coveredByBundled.size).append(" already in the bundled set")
            }
        }
        append("; ")
        append(provenance(discovered, nowMs))
        if (!discovered.empty) append(", unverified")
    }

    /** Where the answer came from and how old it is - including the "Overpass is down" case. */
    private fun provenance(discovered: DiscoveryResult, nowMs: Long): String = buildString {
        if (discovered.fromStaleCache) {
            append("Overpass could not be reached (")
            append(discovered.staleReason ?: "no mirror answered")
            append("), so this is the cached answer")
        } else {
            append(if (discovered.fromCache) "from the cached answer" else "fetched just now")
        }
        discovered.mirror?.let { append(", from ").append(host(it)) }
        discovered.timestampOsmBase?.let { append(", OpenStreetMap data of ").append(it) }
            ?: append(", undated")
        append(", fetched ")
        append(ageText(discovered.fetchedAgeMs(nowMs)))
        append(" ago")
        if (discovered.fromStaleCache) append(", which may be out of date")
    }

    /** "overpass-api.de", not the whole URL. */
    private fun host(mirror: String): String = mirror.substringAfter("//").substringBefore("/")

    private fun km(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun ageText(ageMs: Long): String = when {
        ageMs < 90_000L -> "${ageMs / 1000L} s"
        ageMs < 90L * 60_000L -> "${ageMs / 60_000L} min"
        ageMs < 48L * 3_600_000L -> "${ageMs / 3_600_000L} h"
        else -> "${ageMs / (24L * 3_600_000L)} days"
    }
}
