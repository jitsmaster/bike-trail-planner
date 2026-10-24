package com.biketrailplanner.domain

/** SPEC section 6 grade ladder plus the UNGRADED state added after docs/SAFETY-PROBE.md. */
enum class Grade(val label: String) {
    A("A - trail / separated"),
    B("B - protected road"),
    C("C - shared quiet road"),
    D("D - caution"),
    E("E - reject"),
    UNGRADED("UNGRADED - no speed limit and no override"),
    REJECTED("REJECTED - surface (D11)"),
}

/** Where a speed limit came from. NONE means the segment cannot be graded. */
enum class SpeedSource { OSM, OVERRIDE, NONE }

/** One Valhalla trace_attributes edge, already normalised. */
data class RawEdge(
    val wayId: Long,
    val roadClass: String?,
    val use: String?,
    val surface: String?,
    val unpaved: Boolean,
    val cycleLane: String?,
    val bicycleNetwork: Int,
    val shoulder: Boolean,
    val speedLimitKph: Int?,
    val lengthM: Double,
    val names: List<String>,
)

/** One graded piece of the route. */
data class GradedSegment(
    val grade: Grade,
    val lengthM: Double,
    val names: List<String>,
    val wayIds: List<Long>,
    val roadClass: String?,
    val use: String?,
    val speedMph: Int?,
    val speedSource: SpeedSource,
    val reason: String,
    val ladderHole: Boolean = false,
    val onTrailGeometry: Boolean = false,
    /** Measured traffic volume, when one exists for this road (SPEC section 6b step 1). */
    val traffic: TrafficMatch? = null,
    /** Volume band that actually decided this grade, if any. */
    val volumeBandApplied: VolumeBand = VolumeBand.UNKNOWN,
    /**
     * The OSM bike facility the traced edge carried, carried through for the quiet-street scoring
     * (D50). The grader already reads these to decide the grade; keeping them on the segment lets the
     * routing preference ask "is this a major road with no lane?" without a second trace.
     */
    val cycleLane: String? = null,
    val bicycleNetwork: Int = 0,
    val shoulder: Boolean = false,
) {
    val displayName: String
        get() = names.firstOrNull()?.takeIf { it.isNotBlank() } ?: "(unnamed)"

    val ltsAdvisory: Int? get() = traffic?.lts

    val volumeText: String? get() = traffic?.count?.text
}

/** A named stretch, aggregated by street name for the summary card. */
data class NamedSegment(
    val name: String,
    val metres: Double,
    val grade: Grade,
    val roadClass: String?,
    val speedText: String,
    val volumeText: String? = null,
    /** True only when a real, usable count exists (not an estimate, not "Not Known"). */
    val volumeUsable: Boolean = false,
    val lts: Int? = null,
    val trafficNote: String? = null,
) {
    val miles: Double get() = metres / Geo.METRES_PER_MILE
}

data class GradeBreakdown(
    val totalM: Double,
    val byGrade: Map<Grade, Double>,
    val gradedM: Double,
    val ungradedM: Double,
    val rejectedM: Double,
) {
    /**
     * Metres for a grade. UNGRADED and REJECTED are counted separately from the graded metres, so
     * they are read from [ungradedM] and [rejectedM] - a caller that asked for every grade must not
     * silently get zero for them.
     */
    fun metres(grade: Grade): Double = when (grade) {
        Grade.UNGRADED -> ungradedM
        Grade.REJECTED -> rejectedM
        else -> byGrade[grade] ?: 0.0
    }
    val gradedShare: Double get() = if (totalM <= 0) 0.0 else gradedM / totalM
    val ungradedShare: Double get() = if (totalM <= 0) 0.0 else ungradedM / totalM
    val dShare: Double get() = if (totalM <= 0) 0.0 else metres(Grade.D) / totalM
    val hasE: Boolean get() = metres(Grade.E) > 0.0
    val hasRejected: Boolean get() = rejectedM > 0.0

    /**
     * True when any metre is ungraded. A plain fact about the breakdown, deliberately NOT a verdict:
     * "is any of it ungraded" and "is the answer UNVERIFIED" are different questions, and the second
     * one depends on the coverage threshold. [PolicyVerdict.outcome] owns the verdict. This used to be
     * named `unverified`, which is why two different types answered to the same word.
     */
    val hasUngraded: Boolean get() = ungradedM > 0.0
}

/**
 * The three outcomes of applying D10 to one route (docs/SPEC.md section 6, D10).
 *
 * - [PASS]: no known violation, and enough of the ride was graded to stand behind that.
 * - [UNVERIFIED]: no known violation, but too much of the ride is UNGRADED to vouch for it.
 * - [FAIL]: a known violation - grade E, a rejected surface, or grade D past the cap.
 *
 * UNVERIFIED is deliberately NOT a refusal. Measured over 203 traces / 1,351.5 km of the local
 * network, 48.53% of it is UNGRADED, so treating "not assessed" as "failed" would refuse nearly
 * every ride from home. It is a real outcome the screens must show, which is why it is a state here
 * and not a boolean footnote.
 */
enum class SafetyOutcome {
    PASS,
    UNVERIFIED,
    FAIL,
    ;

    /** Preference order for the option picker: PASS, then UNVERIFIED, then FAIL. */
    val rank: Int get() = ordinal
}

/** The result of applying D10 to one route. */
data class PolicyVerdict(
    val outcome: SafetyOutcome,
    val violations: List<String>,
    /** Share of the ride that could actually be graded, 0..1. */
    val gradedShare: Double,
) {
    /**
     * No KNOWN violation. Deliberately TRUE for [SafetyOutcome.UNVERIFIED]: an unassessed ride is not
     * a proven failure, and the planner must still offer it.
     *
     * This was called `passes`, which read as "safe" and is exactly why D10 could report a pass on a
     * route where almost nothing had been graded (`gradedM` gated the whole cap check, and an empty
     * graded set produced no violations). Read [outcome] to decide what to tell the rider.
     */
    val hasNoKnownViolation: Boolean get() = outcome != SafetyOutcome.FAIL

    /** True when the ride is not fully vouched for, so the screens must say so. */
    val unverified: Boolean get() = outcome == SafetyOutcome.UNVERIFIED
}

/** One ride along the trail(s): a single direction, then the same way back (D3). */
data class TrailRideLeg(
    val trailName: String,
    val fromM: Double,
    val toM: Double,
    val points: List<LatLon>,
) {
    val lengthM: Double get() = if (points.size < 2) 0.0 else Polyline(points).lengthM
}

data class AccessPoint(
    val trailName: String,
    val osmRelationId: Long,
    val osmNodeId: Long,
    val point: LatLon,
    val onRoad: String?,
    val highway: String?,
    val sharesTrailNode: Boolean,
    val straightLineMFromHome: Double,
    val probeRouteKmFromHome: Double?,
)

/** A measured end-to-end closure of a trail (SPEC section 12, docs/SAFETY-PROBE.md 6.4). */
data class TrailClosure(
    val trailName: String,
    val osmWayId: Long,
    val wayLengthM: Double,
    val tags: Map<String, String>,
    val northEnd: LatLon,
    val southEnd: LatLon,
    val effect: String,
    /** Provenance (SPEC section 7b): when it closed, who says so, and what it used to be. */
    val closedSince: String? = null,
    val changeset: Long? = null,
    val changedBy: String? = null,
    val authority: String? = null,
    val previousTags: Map<String, String> = emptyMap(),
    val checkedOn: String? = null,
    /** D15: true when the closure is a hard, permanent one (private property). */
    val permanent: Boolean = false,
    val permanentReason: String? = null,
) {
    /** One line the UI can show without inventing anything. */
    val provenance: String
        get() = buildString {
            append("way $osmWayId (${fmt(wayLengthM / 1000, 2)} km)")
            closedSince?.let { append(", closed in OpenStreetMap since $it") }
            changeset?.let { append(" (changeset $it") }
            if (changeset != null) {
                changedBy?.let { append(", $it") }
                append(")")
            }
            authority?.let { append("; authority: $it") }
            if (previousTags.isNotEmpty()) {
                append("; was ${previousTags.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            if (permanent) {
                append(permanentReason?.let { "; permanent: $it" } ?: "; permanent (D15)")
            }
        }
}

/** A trail, split into the pieces a bicycle can actually ride. */
data class Trail(
    val name: String,
    val osmRelationId: Long,
    val osmUrl: String,
    val lengthM: Double,
    val rideableM: Double,
    val paved: Boolean,
    val loop: Boolean,
    val verified: Boolean,
    val surface: List<String>,
    val untaggedSurfaceM: Double,
    val nearestFromHomeM: Double,
    val farthestFromHomeM: Double,
    val closedM: Double,
    val closures: List<TrailClosure>,
    val fullGeometry: Polyline,
    /** Rideable, contiguous pieces of [fullGeometry]; closed spans are excluded. */
    val rideableSpans: List<Polyline>,
) {
    /** The longest rideable stretch you can enter and ride away from. */
    val longestRideableSpanM: Double get() = rideableSpans.maxOfOrNull { it.lengthM } ?: 0.0
    val hasClosure: Boolean get() = closures.isNotEmpty()
}

data class TrailJoin(
    val id: String,
    val trailA: String,
    val trailB: String,
    val point: LatLon,
    val gapM: Double,
    val kind: String,
)

data class RoadLink(
    val id: String,
    val trailA: String,
    val trailB: String,
    val straightLineM: Double,
    val roadLinkM: Double,
    val d10Status: String,
    val reason: String,
)

data class Corridor(
    val id: String,
    val name: String,
    val trailNames: List<String>,
)

data class TrailNetwork(
    val trails: List<Trail>,
    val joins: List<TrailJoin>,
    val corridors: List<Corridor>,
    val roadLinks: List<RoadLink>,
) {
    fun trail(name: String): Trail? = trails.firstOrNull { it.name == name }

    /** Trails joined to [name] with a 0 m gap. */
    fun neighbours(name: String): List<TrailJoin> =
        joins.filter { (it.trailA == name || it.trailB == name) && it.gapM <= 1.0 }

    fun corridorOf(name: String): Corridor? = corridors.firstOrNull { name in it.trailNames }
}
