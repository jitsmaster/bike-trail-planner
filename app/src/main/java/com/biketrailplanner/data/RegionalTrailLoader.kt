package com.biketrailplanner.data

import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.domain.TrailNetwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The bundled regional trail set (D23 answer (a), docs/REGIONAL-TRAILS.md).
 *
 * `app/src/main/assets/trails_regional.json` carries one entry per OpenStreetMap route relation for
 * Washington state plus the Portland metro - 191 relations, 0.39 MB, 14,550 geometry points - with
 * the ON-ROAD pieces already removed at build time, so every shipped metre is off-road geometry and
 * the all-or-nothing trail grader cannot grade a road metre as trail.
 *
 * What this loader is careful about, all of it visible in the returned [Trail]:
 *  - `verified = false`, always. None of the checks the near-home six went through were done for a
 *    regional trail: no closure check, no surface audit, no measured traffic.
 *  - `closures = emptyList()`, `closedM = 0.0`: nothing is known, and inventing a closure would be
 *    worse than not having one.
 *  - `rideableSpans` = the file's `segments`, one rideable piece each. 89 of the 191 relations ship
 *    as several pieces, because the build removed the road pieces between them and never joined
 *    across a removed piece. That is exactly the shape the app's own [Trail] already supports (the
 *    Sumner Link closure case), so the walker rides one piece and the planner routes no road.
 *  - `lengthM` / `rideableM` are the shipped, clipped, off-road, simplified length (`length_km_clipped`),
 *    measured to be within a few metres of the sum of the shipped segments.
 *  - `paved` is a claim from the relation's own surface tag only, and is false when the tag says
 *    nothing. `untaggedSurfaceM` is the whole trail in that case, so the app can keep saying "we have
 *    no surface audit here".
 *  - Access-point ids are NEGATIVE and synthetic: a sampled point is not an OSM node. They use a
 *    reserved range that no other sampler in the app can produce, so two access points can never be
 *    confused for one another. `probeRouteKmFromHome = null`: the approach distance is genuinely
 *    unknown, and the planner MEASURES it live from where the rider is ([Planner.liveApproachM]).
 *
 * Cost: the file is decoded ONCE per process ([rows]); the per-origin part (distance measurements and
 * access-point sampling) is a few milliseconds and is cached per ~1 km bucket by [AppContainer], so a
 * second plan from the same area re-parses nothing at all.
 */
class RegionalTrailLoader(
    private val assets: AssetReader,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val assetName: String = ASSET,
) {

    /** One shipped relation, parsed and kept in memory. No distance is measured yet. */
    class Row(
        val relationId: Long,
        val name: String,
        val route: String,
        val surface: String?,
        val region: String,
        val loop: Boolean,
        val lengthM: Double,
        /** The rideable pieces, in file order. Never joined across a removed road piece. */
        val spans: List<Polyline>,
        /** One sample candidate per point: (point, index), built once and reused for every origin. */
        val samples: List<Pair<LatLon, Int>>,
    ) {
        val multiPiece: Boolean get() = spans.size > 1
    }

    /** A regional relation a bundled trail of the same name already covers, and why it was left out. */
    data class CoveredRegional(val name: String, val relationId: Long, val reason: String)

    /** The regional trails and access points sampled for one origin. */
    data class Loaded(
        val trails: List<Trail>,
        val accessPoints: List<AccessPoint>,
        /** Trails shipped as more than one rideable piece (docs/REGIONAL-TRAILS.md). */
        val multiPiece: Int = 0,
        /** Geometry points in the whole file, for the honest "how big is this" line. */
        val points: Int = 0,
        /**
         * Regional relations left out because a bundled trail of the same name already covers that
         * corridor. Reported, never dropped silently: the bundled version is the VERIFIED one (it has
         * the closure check, the measured traffic and the measured approach table), so shipping both
         * would offer the rider the same ride twice, once without any of that.
         */
        val coveredByBundled: List<CoveredRegional> = emptyList(),
    ) {
        val empty: Boolean get() = trails.isEmpty()
        companion object {
            val NONE = Loaded(emptyList(), emptyList())
        }
    }

    private var rowsCache: List<Row>? = null

    /** The whole file, decoded once per process. */
    fun rows(): List<Row> {
        rowsCache?.let { return it }
        val parsed = parse(assets.read(assetName))
        rowsCache = parsed
        return parsed
    }

    /**
     * The regional trails plus the access points sampled around [origin].
     *
     * Two rules decide what actually reaches the planner, and both are the SAME rules the live
     * discovery merge already uses (docs/TRAIL-DISCOVERY.md section 4):
     *
     *  1. A regional relation whose name a bundled trail already carries, and 60% of whose geometry
     *     lies within 300 m of that bundled trail's geometry, is LEFT OUT and reported. It is the same
     *     corridor, and the bundled copy is the verified one.
     *  2. Otherwise, a name already in use (by a bundled trail or by an earlier regional one) gets
     *     `" (r<relation id>)"`. A name is the app's trail identity (`TrailNetwork.trail(name)`, the
     *     planner's access-point grouping), so two different corridors may never share one.
     */
    fun load(origin: LatLon, bundled: List<Trail> = emptyList()): Loaded {
        val rows = rows()
        if (rows.isEmpty()) return Loaded.NONE
        val bundledByName = bundled.map { it.name.trim().lowercase(Locale.US) to it.fullGeometry }
        val used = HashSet(bundled.map { it.name })
        val covered = ArrayList<CoveredRegional>()
        val trails = ArrayList<Trail>(rows.size)
        val accessPoints = ArrayList<AccessPoint>()
        var multiPiece = 0
        var points = 0
        for (row in rows) {
            val key = row.name.trim().lowercase(Locale.US)
            val alreadyCovered = bundledByName.firstOrNull { (name, geometry) ->
                name == key &&
                    DiscoveryParser.coverage(row.samples.map { it.first }, geometry, BUNDLED_NEAR_M) >= BUNDLED_COVERAGE
            }
            if (alreadyCovered != null) {
                covered.add(
                    CoveredRegional(
                        name = row.name,
                        relationId = row.relationId,
                        reason = "a bundled trail of the same name already covers this corridor " +
                            "(geometry within " + BUNDLED_NEAR_M.toInt() + " m), and the bundled copy is the verified one",
                    ),
                )
                continue
            }
            var name = row.name
            if (!used.add(name)) {
                name = row.name + " (r" + row.relationId + ")"
                used.add(name)
            }
            val geometry = concatenated(row.spans)
            trails.add(
                Trail(
                    name = name,
                    osmRelationId = row.relationId,
                    osmUrl = "https://www.openstreetmap.org/relation/" + row.relationId,
                    lengthM = row.lengthM,
                    rideableM = row.lengthM,
                    paved = PAVED_TAGS.contains(row.surface?.lowercase(Locale.US)),
                    loop = row.loop,
                    verified = false,
                    surface = listOfNotNull(row.surface),
                    untaggedSurfaceM = if (row.surface.isNullOrBlank()) row.lengthM else 0.0,
                    nearestFromHomeM = row.spans.minOf { span -> span.points.minOf { Geo.distanceM(origin, it) } },
                    farthestFromHomeM = row.spans.maxOf { span -> span.points.maxOf { Geo.distanceM(origin, it) } },
                    closedM = 0.0,
                    closures = emptyList(),
                    fullGeometry = geometry,
                    rideableSpans = row.spans,
                ),
            )
            accessPoints.addAll(accessPointsFor(name, row, origin))
            if (row.multiPiece) multiPiece++
            points += row.spans.sumOf { it.points.size }
        }
        return Loaded(trails, accessPoints, multiPiece, points, covered)
    }

    /**
     * Access points for one regional trail: every span endpoint, plus an interior point every
     * [ACCESS_SPACING_M], ranked by crow-flies distance from [origin] and capped at
     * [MAX_ACCESS_POINTS_PER_TRAIL].
     *
     * The cap is what stops a 388 km trail from handing the planner a thousand entrances: a rider is
     * offered the twelve they can actually reach, not the twelve hundred that exist. Endpoints are in
     * the same pool as the interior samples, so a trail whose far end is the near end for this rider
     * still gets an entrance there.
     */
    private fun accessPointsFor(trailName: String, row: Row, origin: LatLon): List<AccessPoint> {
        if (row.samples.isEmpty()) return emptyList()
        return row.samples
            .map { it to Geo.distanceM(origin, it.first) }
            .sortedWith(compareBy({ it.second }, { it.first.second }))
            .take(MAX_ACCESS_POINTS_PER_TRAIL)
            .sortedBy { it.second }
            .map { (sample, distance) ->
                AccessPoint(
                    trailName = trailName,
                    osmRelationId = row.relationId,
                    osmNodeId = syntheticId(row.relationId, sample.second),
                    point = sample.first,
                    onRoad = null,
                    highway = null,
                    sharesTrailNode = true,
                    straightLineMFromHome = distance,
                    probeRouteKmFromHome = null,
                )
            }
    }

    /** Decodes the file into rows. Never touches the network and never measures a distance. */
    private fun parse(text: String): List<Row> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val array = root["trails"] as? JsonArray ?: return emptyList()
        val out = ArrayList<Row>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val rawName = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: ("Unnamed bike route (r" + id + ")")
            val spans = (obj["segments"] as? JsonArray).orEmpty().mapNotNull { segment ->
                val points = (segment as? JsonArray).orEmpty().mapNotNull { point ->
                    val pair = point as? JsonArray ?: return@mapNotNull null
                    val lat = pair.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = pair.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                    LatLon(lat, lon)
                }
                // A one-point piece is not rideable geometry, and Polyline refuses fewer than two.
                if (points.size < 2) null else Polyline(points)
            }
            if (spans.isEmpty()) continue
            val samples = ArrayList<Pair<LatLon, Int>>()
            var index = 0
            spans.forEach { span ->
                samples.add(span.points.first() to index++)
                var arc = ACCESS_SPACING_M
                while (arc < span.lengthM - 1.0) {
                    samples.add(span.pointAt(arc) to index++)
                    arc += ACCESS_SPACING_M
                }
                samples.add(span.points.last() to index++)
            }
            out.add(
                Row(
                    relationId = id,
                    name = rawName,
                    route = obj["route"]?.jsonPrimitive?.content.orEmpty(),
                    surface = obj["surface"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    region = obj["region"]?.jsonPrimitive?.content.orEmpty(),
                    loop = obj["loop"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    // The shipped, clipped off-road length: measured to agree with the sum of the
                    // shipped segments within a few metres. Falls back to the geometry when absent.
                    lengthM = obj["length_km_clipped"]?.jsonPrimitive?.content?.toDoubleOrNull()?.times(1000.0)
                        ?: spans.sumOf { it.lengthM },
                    spans = spans,
                    samples = samples,
                ),
            )
        }
        return out
    }

    /** Every span's points in file order. Only used for "how close is any of this trail" questions. */
    private fun concatenated(spans: List<Polyline>): Polyline {
        val points = ArrayList<LatLon>()
        spans.forEach { span -> span.points.forEach { points.add(it) } }
        return if (points.size >= 2) Polyline(points) else spans.first()
    }

    /**
     * A synthetic, always-negative id in a reserved range: `-(BASE + relationId * STRIDE + index)`.
     *
     * The base is far above anything the live discoverer can produce (`relationId * 100_000 + index`),
     * so a regional sample and a discovered sample can never collide, and neither can a regional
     * sample and a real OSM node id (always positive).
     */
    internal fun syntheticId(relationId: Long, index: Int): Long =
        -(SYNTHETIC_ID_BASE + relationId * SYNTHETIC_ID_STRIDE + index)

    companion object {
        const val ASSET = "trails_regional.json"

        /** Interior access-point spacing along a regional trail; the discoverer uses the same step. */
        const val ACCESS_SPACING_M = 300.0

        /** Most access points kept per regional trail, endpoints included. */
        const val MAX_ACCESS_POINTS_PER_TRAIL = 12

        /** Surface tags the app is willing to call paved. Anything else is not a paved claim. */
        val PAVED_TAGS = setOf("paved", "asphalt", "concrete", "concrete:plates", "paving_stones")

        /** A regional relation this far onto a bundled corridor is the same corridor (section 4). */
        const val BUNDLED_NEAR_M = 300.0

        /** ... when this share of its geometry lies that close. */
        const val BUNDLED_COVERAGE = 0.6

        /** Reserved id range. Above every id the live discoverer can build, below nothing else's. */
        private const val SYNTHETIC_ID_BASE = 100_000_000_000_000L
        private const val SYNTHETIC_ID_STRIDE = 10_000L
    }
}

/**
 * How the regional set joins the bundled data (docs/REGIONAL-TRAILS.md). The bundled six stay the
 * only VERIFIED trails and keep their closures, their traffic counts and their corridors; the
 * regional trails are appended exactly as they are shipped, unverified and with no joins.
 */
object RegionalMerge {

    /** The bundled network plus the regional trails. Bundled joins and corridors are untouched. */
    fun network(bundled: TrailNetwork, regional: RegionalTrailLoader.Loaded): TrailNetwork =
        if (regional.empty) bundled else TrailNetwork(
            trails = bundled.trails + regional.trails,
            joins = bundled.joins,
            corridors = bundled.corridors,
            roadLinks = bundled.roadLinks,
        )

    /** The bundled access points plus the regional ones. */
    fun accessPoints(bundled: List<AccessPoint>, regional: RegionalTrailLoader.Loaded): List<AccessPoint> =
        if (regional.empty) bundled else bundled + regional.accessPoints
}
