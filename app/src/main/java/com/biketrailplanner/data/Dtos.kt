package com.biketrailplanner.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfigDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val home: HomeDto,
    val planner: PlannerDto,
)

@Serializable
data class HomeDto(val lat: Double, val lon: Double, val label: String)

@Serializable
data class PlannerDto(
    @SerialName("origin_warn_km") val originWarnKm: Double = 0.5,
    // D10 as the rider restated it on 2026-09-16: "caution street under 1.1 miles is good enough,
    // allow them". The absolute caution distance is the rule; the SHARE no longer binds (1.0 = no
    // limit) but is still computed and shown, so the proportion is never hidden. Never grade E.
    @SerialName("d_cap_share") val dCapShare: Double = 1.0,
    @SerialName("d_cap_miles") val dCapMiles: Double = 1.1,
    @SerialName("unverified_ungraded_share") val unverifiedUngradedShare: Double = 0.5,
    @SerialName("max_waypoints") val maxWaypoints: Int = 9,
    @SerialName("max_url_length") val maxUrlLength: Int = 2048,
    @SerialName("access_points_per_trail") val accessPointsPerTrail: Int = 6,
    @SerialName("min_trail_miles") val minTrailMiles: Double = 0.25,
    @SerialName("max_candidates") val maxCandidates: Int = 12,
    // D13 (SPEC section 7c): look for a compliant longer route before refusing.
    @SerialName("detour_accept_factor") val detourAcceptFactor: Double = 1.40,
    @SerialName("detour_max_factor") val detourMaxFactor: Double = 1.80,
    @SerialName("detour_bands") val detourBands: List<Double> = listOf(0.0, 0.25, 0.40, 0.80),
    @SerialName("trail_floor_miles") val trailFloorMiles: Double = 2.0,
    @SerialName("detour_access_points_per_trail") val detourAccessPointsPerTrail: Int = 4,
    @SerialName("max_via_candidates") val maxViaCandidates: Int = 8,
    // D44: the quiet-street search. It runs only when the best compliant ride is mostly bigger roads,
    // and it is bounded because every proposal costs two HTTP calls (/route then trace_attributes).
    // `quiet_share_target` is the share of the ride that should be on streets that suit a bike before
    // the search is considered unnecessary; `max_quiet_evals` is the ceiling on proposals to try;
    // `quiet_max_vias` is how many distinct streets one proposal may force the route through.
    @SerialName("quiet_share_target") val quietShareTarget: Double = 0.35,
    @SerialName("max_quiet_evals") val maxQuietEvals: Int = 6,
    @SerialName("quiet_max_vias") val quietMaxVias: Int = 2,
    // D44: a ride with this much of it on roads the rider objects to (grade D, a known speed over
    // 25 mph, or a rejected surface) is worth searching again for, even when most of it is fine.
    @SerialName("quiet_busy_floor_m") val quietBusyFloorM: Double = 300.0,
    // D31 (docs/LOOPS.md): "the preference not to take the same way back, so always a loop", with the
    // rider's own two limits - it is a preference and never a promise ("if the roads allows"), and it
    // may cost at most 5 km of extra distance ("under 5km is acceptable"). `loop_via_offsets_m` is the
    // perpendicular fan the separate return leg is forced through; `loop_max_candidates` and
    // `loop_max_quiet_vias` bound the attempts because each one costs two HTTP calls (/route + trace).
    @SerialName("prefer_loop") val preferLoop: Boolean = true,
    @SerialName("loop_max_extra_km") val loopMaxExtraKm: Double = 5.0,
    @SerialName("loop_via_offsets_m") val loopViaOffsetsM: List<Double> =
        listOf(500.0, 1000.0, 1336.0, 2000.0, 3000.0),
    @SerialName("loop_max_candidates") val loopMaxCandidates: Int = 6,
    @SerialName("loop_max_quiet_vias") val loopMaxQuietVias: Int = 2,
    // D22: the ride starts from the live GPS position.
    @SerialName("trail_radius_km") val trailRadiusKm: Double = 5.0,
    @SerialName("traffic_coverage_km") val trafficCoverageKm: Double = 15.0,
    @SerialName("max_fix_age_s") val maxFixAgeS: Long = 60,
    @SerialName("max_fix_accuracy_m") val maxFixAccuracyM: Double = 50.0,
)

@Serializable
data class TrailDto(
    val name: String,
    @SerialName("osm_relation_id") val osmRelationId: Long,
    @SerialName("osm_url") val osmUrl: String = "",
    @SerialName("length_km") val lengthKm: Double,
    @SerialName("rideable_length_km") val rideableLengthKm: Double,
    val surface: List<String> = emptyList(),
    @SerialName("surface_m") val surfaceM: Map<String, Double> = emptyMap(),
    val paved: Boolean = true,
    val loop: Boolean = false,
    val geometry: List<List<Double>>,
    val endpoints: List<List<Double>> = emptyList(),
    @SerialName("nearest_distance_from_home_km") val nearestFromHomeKm: Double = 0.0,
    @SerialName("farthest_distance_from_home_km") val farthestFromHomeKm: Double = 0.0,
    @SerialName("member_way_count") val memberWayCount: Int = 0,
    @SerialName("geometry_gaps") val geometryGaps: List<GeometryGapDto> = emptyList(),
    @SerialName("bike_access_conflicts") val bikeAccessConflicts: List<BikeConflictDto> = emptyList(),
    val verified: Boolean = false,
    val notes: String = "",
)

@Serializable
data class GeometryGapDto(@SerialName("gap_m") val gapM: Double = 0.0)

@Serializable
data class BikeConflictDto(
    @SerialName("osm_way_id") val osmWayId: Long,
    @SerialName("length_m") val lengthM: Double = 0.0,
    val highway: String? = null,
    val name: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class AccessPointsDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val count: Int = 0,
    @SerialName("access_points") val accessPoints: List<AccessPointDto> = emptyList(),
)

@Serializable
data class AccessPointDto(
    val trail: String,
    @SerialName("osm_relation_id") val osmRelationId: Long,
    @SerialName("osm_node_id") val osmNodeId: Long,
    val lat: Double,
    val lon: Double,
    @SerialName("on_road") val onRoad: String? = null,
    val highway: String? = null,
    @SerialName("shares_trail_node") val sharesTrailNode: Boolean = false,
    @SerialName("straight_line_m_from_home") val straightLineMFromHome: Double = 0.0,
    @SerialName("measured_route_km_from_home") val measuredRouteKmFromHome: Double? = null,
    @SerialName("snap_offset_m") val snapOffsetM: Double? = null,
)

@Serializable
data class TrailLinksDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val joins: List<JoinDto> = emptyList(),
    val corridors: List<CorridorDto> = emptyList(),
    @SerialName("road_links") val roadLinks: List<RoadLinkDto> = emptyList(),
)

@Serializable
data class JoinDto(
    val id: String,
    @SerialName("trail_a") val trailA: String,
    @SerialName("trail_b") val trailB: String,
    @SerialName("kind") val kind: String = "shared_osm_node",
    @SerialName("osm_node_id") val osmNodeId: Long? = null,
    val point: List<Double>,
    @SerialName("gap_m") val gapM: Double = 0.0,
    @SerialName("evidence") val evidence: String = "",
)

@Serializable
data class CorridorDto(val id: String, val name: String, val trails: List<String>)

@Serializable
data class RoadLinkDto(
    val id: String,
    @SerialName("trail_a") val trailA: String,
    @SerialName("trail_b") val trailB: String,
    @SerialName("straight_line_gap_m") val straightLineGapM: Double,
    @SerialName("road_link_m") val roadLinkM: Double,
    @SerialName("d10_status") val d10Status: String,
    val reason: String = "",
)

@Serializable
data class ClosuresDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val closures: List<ClosureDto> = emptyList(),
)

@Serializable
data class ClosureDto(
    val trail: String,
    @SerialName("osm_way_id") val osmWayId: Long,
    @SerialName("way_length_m") val wayLengthM: Double,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("previous_tags") val previousTags: Map<String, String> = emptyMap(),
    @SerialName("closed_since") val closedSince: String? = null,
    val changeset: Long? = null,
    @SerialName("changed_by") val changedBy: String? = null,
    @SerialName("changeset_comment") val changesetComment: String? = null,
    val authority: String? = null,
    @SerialName("checked_on") val checkedOn: String? = null,
    val permanent: Boolean = false,
    @SerialName("permanent_reason") val permanentReason: String? = null,
    @SerialName("north_end") val northEnd: List<Double>,
    @SerialName("south_end") val southEnd: List<Double>,
    val effect: String = "",
    @SerialName("app_behaviour") val appBehaviour: String = "",
)

@Serializable
data class LimitsDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val roads: List<SpeedOverrideDto> = emptyList(),
    @SerialName("known_unposted_roads") val knownUnpostedRoads: List<KnownUnpostedDto> = emptyList(),
    val note: String = "",
)

@Serializable
data class SpeedOverrideDto(
    @SerialName("osm_way_id") val osmWayId: Long? = null,
    val name: String? = null,
    @SerialName("speed_mph") val speedMph: Int? = null,
    val aadt: Int? = null,
    val lanes: Int? = null,
    val source: String? = null,
    @SerialName("aadt_year") val aadtYear: Int? = null,
    @SerialName("aadt_confidence") val aadtConfidence: String? = null,
    val lts: Int? = null,
)

@Serializable
data class KnownUnpostedDto(
    val name: String,
    @SerialName("road_class") val roadClass: String? = null,
    @SerialName("osm_way_ids") val osmWayIds: List<Long> = emptyList(),
    val role: String = "",
    @SerialName("speed_mph") val speedMph: Int? = null,
    val source: String = "",
)
