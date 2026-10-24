package com.biketrailplanner.data

import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.Corridor
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.RoadLink
import com.biketrailplanner.domain.SpeedOverride
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.domain.TrailClosure
import com.biketrailplanner.domain.TrailJoin
import com.biketrailplanner.domain.TrailNetwork
import com.biketrailplanner.domain.TrafficCount
import com.biketrailplanner.domain.TrafficTable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled trail dataset and builds the network the planner uses:
 * trails (split into rideable spans by the documented closures), the corridor joins,
 * the road links that need one, and the measured access points.
 */
class TrailRepository(
    private val assets: AssetReader,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** Where the rider starts and ends every ride (assets/config.json, not hardcoded). */
    data class Config(
        val home: LatLon,
        val homeLabel: String,
        val planner: PlannerDto,
    )

    data class Loaded(
        val network: TrailNetwork,
        val accessPoints: List<AccessPoint>,
        val limits: LimitsTable,
        val traffic: TrafficTable,
        val dataFlags: List<String>,
        val knownUnpostedRoads: List<KnownUnpostedDto>,
        val config: Config,
    )

    /** Reads an asset, returning null when it is absent (a missing table just means "no data"). */
    private fun readOptional(name: String): String? = runCatching { assets.read(name) }.getOrNull()

    /**
     * @param reopenedWayIds ways an OSM re-check proved are rideable again (SPEC section 7b item 4);
     *   their closures are dropped, so the trail becomes continuous again.
     */
    fun load(reopenedWayIds: Set<Long> = emptySet()): Loaded {
        val configDto = json.decodeFromString(AppConfigDto.serializer(), assets.read("config.json"))
        val trailsDto = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TrailDto.serializer()), assets.read("trails.json"))
        val apDto = json.decodeFromString(AccessPointsDto.serializer(), assets.read("access_points.json"))
        val linksDto = json.decodeFromString(TrailLinksDto.serializer(), assets.read("trail_links.json"))
        val closuresDto = json.decodeFromString(ClosuresDto.serializer(), assets.read("trail_closures.json"))
        // assets/limits.json and assets/traffic.json are written by the data producer; either
        // being missing is fine, and the app then falls back to OSM only (SPEC section 6b).
        val limitsText = readOptional("limits.json")
        val trafficText = readOptional("traffic.json") ?: readOptional("traffic-volume.json")
        val limitsTable = LimitsLoader.load(json, limitsText)
        val overrideRows = limitsTable.rows
        val limits = LimitsTable(overrideRows)
        val dataFlags = limitsTable.flags
        val knownUnposted = limitsText
            ?.let { runCatching { json.decodeFromString(LimitsDto.serializer(), it) }.getOrNull() }
            ?.knownUnpostedRoads ?: emptyList()

        val closuresByTrail = closuresDto.closures
            .filterNot { it.osmWayId in reopenedWayIds }
            .map { c ->
                TrailClosure(
                    trailName = c.trail,
                    osmWayId = c.osmWayId,
                    wayLengthM = c.wayLengthM,
                    tags = c.tags,
                    northEnd = LatLon(c.northEnd[0], c.northEnd[1]),
                    southEnd = LatLon(c.southEnd[0], c.southEnd[1]),
                    effect = c.effect,
                    closedSince = c.closedSince,
                    changeset = c.changeset,
                    changedBy = c.changedBy,
                    authority = c.authority,
                    previousTags = c.previousTags,
                    checkedOn = c.checkedOn,
                    permanent = c.permanent,
                    permanentReason = c.permanentReason,
                )
            }
            .groupBy { it.trailName }

        val trails = trailsDto.map { dto ->
            val geometry = dto.geometry.map { LatLon(it[0], it[1]) }
            val full = Polyline(geometry)
            val closures = closuresByTrail[dto.name].orEmpty()
            val forbidden = forbiddenIntervals(full, closures)
            val rideable = complementSpans(full, forbidden)
            val closedM = forbidden.sumOf { it.second - it.first }
            Trail(
                name = dto.name,
                osmRelationId = dto.osmRelationId,
                osmUrl = dto.osmUrl,
                lengthM = dto.lengthKm * 1000,
                rideableM = dto.rideableLengthKm * 1000,
                paved = dto.paved,
                loop = dto.loop,
                verified = dto.verified,
                surface = dto.surface,
                untaggedSurfaceM = dto.surfaceM["(untagged)"] ?: 0.0,
                nearestFromHomeM = dto.nearestFromHomeKm * 1000,
                farthestFromHomeM = dto.farthestFromHomeKm * 1000,
                closedM = closedM,
                closures = closures,
                fullGeometry = full,
                rideableSpans = rideable,
            )
        }

        val joins = buildJoins(trails, linksDto)
        val network = TrailNetwork(
            trails = trails,
            joins = joins,
            corridors = linksDto.corridors.map { Corridor(it.id, it.name, it.trails) },
            roadLinks = linksDto.roadLinks.map {
                RoadLink(it.id, it.trailA, it.trailB, it.straightLineGapM, it.roadLinkM, it.d10Status, it.reason)
            },
        )
        val accessPoints = apDto.accessPoints.map {
            AccessPoint(
                trailName = it.trail,
                osmRelationId = it.osmRelationId,
                osmNodeId = it.osmNodeId,
                point = LatLon(it.lat, it.lon),
                onRoad = it.onRoad,
                highway = it.highway,
                sharesTrailNode = it.sharesTrailNode,
                straightLineMFromHome = it.straightLineMFromHome,
                probeRouteKmFromHome = it.measuredRouteKmFromHome,
            )
        }
        val trafficCounts = ArrayList<TrafficCount>(TrafficLoader.load(json, trafficText))
        // Volume rows that arrived inside limits.json are merged in as well, keyed by street name.
        overrideRows.forEach { road ->
            if (road.aadt != null) {
                trafficCounts.add(
                    TrafficCount(
                        roadName = road.name ?: "way ${road.osmWayId}",
                        source = road.source ?: "assets/limits.json",
                        aadt = road.aadt.takeIf { it > 0 },
                        aadtYear = road.aadtYear,
                        confidence = com.biketrailplanner.domain.ConfidenceNames.parse(road.aadtConfidence ?: "measured"),
                        lts = road.lts,
                        volumeBand = TrafficTable.bandFor(road.aadt.takeIf { it > 0 }),
                        wayIds = listOfNotNull(road.osmWayId),
                        osmNames = listOfNotNull(road.name),
                    ),
                )
            }
        }
        return Loaded(
            network = network,
            accessPoints = accessPoints,
            limits = limits,
            traffic = TrafficTable(trafficCounts),
            dataFlags = dataFlags,
            knownUnpostedRoads = knownUnposted,
            config = Config(
                home = LatLon(configDto.home.lat, configDto.home.lon),
                homeLabel = configDto.home.label,
                planner = configDto.planner,
            ),
        )
    }

    /** Arc-length intervals of the trail that a bicycle cannot ride. */
    internal fun forbiddenIntervals(polyline: Polyline, closures: List<TrailClosure>): List<Pair<Double, Double>> =
        closures.map { c ->
            val a = polyline.project(c.northEnd).arcM
            val b = polyline.project(c.southEnd).arcM
            minOf(a, b) to maxOf(a, b)
        }.sortedBy { it.first }

    /** Merge overlapping forbidden intervals and invert them. */
    internal fun complementSpans(polyline: Polyline, forbidden: List<Pair<Double, Double>>): List<Polyline> {
        if (forbidden.isEmpty()) return listOf(polyline)
        val spans = ArrayList<Polyline>()
        var cursor = 0.0
        for ((lo, hi) in forbidden.sortedBy { it.first }) {
            if (lo - cursor > MIN_SPAN_M) spans.add(Polyline(polyline.subLine(cursor, lo)))
            cursor = maxOf(cursor, hi)
        }
        if (polyline.lengthM - cursor > MIN_SPAN_M) {
            spans.add(Polyline(polyline.subLine(cursor, polyline.lengthM)))
        }
        return if (spans.isEmpty()) listOf(polyline) else spans
    }

    /**
     * Joins come from two sources and are merged:
     *  - the bundled table (shared OSM nodes, from docs/data/trail-corridor.md),
     *  - trails whose endpoints coincide within [ENDPOINT_TOLERANCE_M].
     */
    private fun buildJoins(trails: List<Trail>, links: TrailLinksDto): List<TrailJoin> {
        val joins = ArrayList<TrailJoin>()
        links.joins.forEach { j ->
            joins.add(
                TrailJoin(
                    id = j.id,
                    trailA = j.trailA,
                    trailB = j.trailB,
                    point = LatLon(j.point[0], j.point[1]),
                    gapM = j.gapM,
                    kind = j.kind,
                ),
            )
        }
        for (a in trails) {
            for (b in trails) {
                if (a.name >= b.name) continue
                for (endA in listOf(a.fullGeometry.points.first(), a.fullGeometry.points.last())) {
                    for (endB in listOf(b.fullGeometry.points.first(), b.fullGeometry.points.last())) {
                        val d = Geo.distanceM(endA, endB)
                        if (d <= ENDPOINT_TOLERANCE_M) {
                            val already = joins.any {
                                (it.trailA == a.name && it.trailB == b.name) || (it.trailA == b.name && it.trailB == a.name)
                            }
                            if (!already) {
                                joins.add(
                                    TrailJoin(
                                        id = "auto-${a.osmRelationId}-${b.osmRelationId}",
                                        trailA = a.name,
                                        trailB = b.name,
                                        point = endA,
                                        gapM = 0.0,
                                        kind = "shared_geometry_endpoint",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        return joins
    }

    companion object {
        internal const val MIN_SPAN_M = 25.0
        internal const val ENDPOINT_TOLERANCE_M = 5.0
    }
}
