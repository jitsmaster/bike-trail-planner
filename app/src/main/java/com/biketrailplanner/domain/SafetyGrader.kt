package com.biketrailplanner.domain

import kotlin.math.roundToInt

/**
 * SPEC section 6 safety grading, plus the UNGRADED state and the measured-volume rules of
 * section 6b.
 *
 * The ladder is fed in this priority order:
 *   1. measured traffic volume, when a count exists and is usable (bands <1500 / 1500-7000 /
 *      >7000 AADT). Low volume cannot be grade D. A count of 0 means "Not Known", never zero
 *      traffic, and unknown never becomes "low traffic".
 *   2. published Level of Traffic Stress, as an ADVISORY badge only - it never silently
 *      upgrades or downgrades a grade.
 *   3. OpenStreetMap `maxspeed` and road class (plus the bundled speed override table).
 *   4. UNGRADED when nothing applies.
 *
 * A segment with neither a speed limit nor an override is UNGRADED: never safe, always named,
 * counted separately. Measured fact (docs/SAFETY-PROBE.md): 82-100% of every route from home
 * runs on roads with no `maxspeed` in OSM.
 *
 * The ladder in SPEC section 6 has holes (for example a tertiary road at 25 mph is neither C
 * nor D). This grader closes a hole the only safe way: a segment whose speed IS known but which
 * matches no rule is graded D (caution) and flagged `ladderHole`. It never closes a hole
 * downward into A/B/C.
 */
class SafetyGrader(
    private val limits: LimitsTable = LimitsTable.EMPTY,
    private val traffic: TrafficTable = TrafficTable.EMPTY,
) {

    private val trailUses = setOf("cycleway", "path", "mountain_bike")
    private val rejectSurfaces = setOf("dirt", "rough", "impassable")
    private val residentialClasses = setOf("residential", "service_other", "service")
    private val cautionClasses = setOf("tertiary", "secondary")
    private val neverClasses = setOf("primary", "trunk", "motorway")

    /** Grades one edge. [at] is the point on the route this edge covers, for geometry joins. */
    fun gradeEdge(edge: RawEdge, at: LatLon? = null): GradedSegment {
        val names = edge.names.ifEmpty { listOf() }
        val override = limits.override(listOf(edge.wayId), names)
        val osmMph: Int? = edge.speedLimitKph?.let { (it * 0.621371).roundToInt() }
        val speedMph: Int?
        val speedSource: SpeedSource
        if (override?.speedMph != null) {
            speedMph = override.speedMph
            speedSource = SpeedSource.OVERRIDE
        } else if (osmMph != null) {
            speedMph = osmMph
            speedSource = SpeedSource.OSM
        } else {
            speedMph = null
            speedSource = SpeedSource.NONE
        }

        val ladder = ladderGrade(edge, names, speedMph, speedSource)
        val match = traffic.lookup(listOf(edge.wayId), names, at)
        return applyMeasuredVolume(ladder, match, edge)
    }

    // ------------------------------------------------------------------ step 3: the OSM ladder

    private fun ladderGrade(
        edge: RawEdge,
        names: List<String>,
        speedMph: Int?,
        speedSource: SpeedSource,
    ): GradedSegment {
        val roadClass = edge.roadClass
        val use = edge.use
        val lane = edge.cycleLane ?: "none"
        val separateFacility = use != null && use in trailUses
        val protectedLane = lane == "dedicated"
        val separatedLane = lane == "separated"

        // D11 first: a rejected surface cannot be ridden at all.
        if (edge.surface != null && edge.surface in rejectSurfaces) {
            return segment(edge, Grade.REJECTED, speedMph, speedSource, "D11: surface=${edge.surface} is rejected")
        }

        // E: hard rejects, never A/B/C.
        if (roadClass != null && roadClass in neverClasses && !separateFacility && !separatedLane && !protectedLane) {
            return segment(edge, Grade.E, speedMph, speedSource, "road_class=$roadClass is a D10 never-E road")
        }
        if (speedMph != null && speedMph >= 40 && !separateFacility && !separatedLane && !protectedLane) {
            return segment(edge, Grade.E, speedMph, speedSource, "$speedMph mph with no dedicated lane or separated path")
        }

        // A: trail, separated lane, or a signed bicycle network.
        if (separateFacility) {
            return segment(edge, Grade.A, speedMph, speedSource, "use=$use is an off-street facility")
        }
        if (separatedLane) {
            return segment(edge, Grade.A, speedMph, speedSource, "cycle_lane=separated")
        }
        if (edge.bicycleNetwork > 0) {
            return segment(edge, Grade.A, speedMph, speedSource, "bicycle_network=${edge.bicycleNetwork}")
        }

        // B: dedicated on-street lane at 25 mph or less.
        if (protectedLane && speedMph != null && speedMph <= 25) {
            return segment(edge, Grade.B, speedMph, speedSource, "cycle_lane=dedicated at $speedMph mph")
        }

        // C: quiet shared road at 25 mph or less.
        if (speedMph != null && speedMph <= 25 &&
            ((roadClass != null && roadClass in residentialClasses) || use == "living_street")
        ) {
            return segment(edge, Grade.C, speedMph, speedSource, "${roadClass ?: use} at $speedMph mph")
        }

        // D: caution arterials at 30-35 mph with no dedicated lane.
        if (speedMph != null && speedMph in 30..35 && roadClass != null && roadClass in cautionClasses) {
            return segment(edge, Grade.D, speedMph, speedSource, "$roadClass at $speedMph mph with no dedicated lane")
        }

        // UNGRADED: no speed limit anywhere, and not a trail.
        if (speedMph == null) {
            return segment(
                edge, Grade.UNGRADED, null, SpeedSource.NONE,
                "no maxspeed in OSM and no override for ${names.firstOrNull() ?: "way ${edge.wayId}"}",
            )
        }

        // The ladder's own hole: a known speed that matches no rule. Close it as D, never C.
        return segment(
            edge, Grade.D, speedMph, speedSource,
            "ladder hole: ${roadClass ?: use} at $speedMph mph matches no rule, graded conservatively as D",
            hole = true,
        )
    }

    // ------------------------------------------------- step 1: measured volume beats road class

    /**
     * D14 (volume leads, with teeth) and the SPEC section 6b priority order. A usable measured
     * count can only ever make the grade more honest:
     *   - LOW (under 1500 AADT): cannot be grade D. A D or an UNGRADED segment becomes C.
     *   - MEDIUM (1500-7000): no promotion on its own, but a 25 mph road that only fell into the
     *     ladder hole is a shared quiet road, so it becomes C when the speed is known and <= 25.
     *   - HIGH (over 7000): never a quiet shared road - C, D or UNGRADED becomes D, and so does an
     *     A that came only from a signed bicycle network rather than a real off-street facility.
     *   - An ESTIMATE or UNKNOWN count is never used here, and a published 0 was loaded as null.
     * Caps (never upgrades):
     *   - a STALE count (older than ~10 years) cannot grade better than C, and never B;
     *   - a published LTS 4 cannot grade better than C.
     * A true off-street or separated facility is never moved by volume or LTS: the count belongs
     * to the road beside it, not to the path.
     */
    private fun applyMeasuredVolume(ladder: GradedSegment, match: TrafficMatch?, edge: RawEdge): GradedSegment {
        if (match == null) return ladder
        val trueFacility = (edge.use != null && edge.use in trailUses) || edge.cycleLane == "separated"
        if (!match.usableVolume) {
            // An advisory LTS rating on an ungraded road still tells us it is not a good place.
            return ladder.copy(traffic = match)
        }
        val count = match.count
        val aadt = count.aadt!!
        val volumeText = count.text
        val speedMph = ladder.speedMph
        var grade = ladder.grade
        var reason = ladder.reason
        var hole = ladder.ladderHole

        if (!trueFacility) {
            when (match.band) {
                VolumeBand.LOW -> if (grade == Grade.D || grade == Grade.UNGRADED) {
                    grade = Grade.C
                    reason = "measured $volumeText, under ${TrafficTable.LOW_MAX} vpd: low volume cannot be grade D"
                    hole = false
                }
                VolumeBand.MEDIUM -> if (grade == Grade.D && hole && speedMph != null && speedMph <= 25) {
                    grade = Grade.C
                    reason = "measured $volumeText with a known $speedMph mph limit: a shared quiet road"
                    hole = false
                }
                VolumeBand.HIGH -> when {
                    grade == Grade.E || grade == Grade.B -> Unit
                    grade == Grade.A && trueFacility -> Unit
                    else -> {
                        grade = Grade.D
                        reason = "measured $volumeText, over ${TrafficTable.MEDIUM_MAX} vpd: a road this busy is " +
                            (if (ladder.grade == Grade.A) "not a bicycle route" else "never a quiet shared road")
                        hole = false
                    }
                }
                VolumeBand.UNKNOWN -> Unit
            }
        }

        // D14 caps: a stale count and an LTS 4 both mean "no better than C".
        val capReasons = ArrayList<String>()
        if (match.staleYears != null || count.confidence == TrafficConfidence.STALE) {
            capReasons.add("the count is " + (match.staleYears?.let { "$it years old" } ?: "stale"))
        }
        if (count.lts != null && count.lts >= 4) capReasons.add("published LTS ${count.lts}")
        // The cap only ever pulls a grade DOWN to C (A or B); it never upgrades a D or an
        // UNGRADED segment, and it leaves E alone.
        if (capReasons.isNotEmpty() && !trueFacility && GradeRank.betterThan(grade, Grade.C)) {
            grade = Grade.C
            reason = "capped at C (D14): " + capReasons.joinToString(" and ")
        }

        return ladder.copy(
            grade = grade,
            reason = reason,
            ladderHole = hole,
            volumeBandApplied = if (trueFacility) VolumeBand.UNKNOWN else match.band,
            traffic = match,
        )
    }

    private fun segment(
        edge: RawEdge,
        grade: Grade,
        speedMph: Int?,
        speedSource: SpeedSource,
        reason: String,
        hole: Boolean = false,
        onTrail: Boolean = false,
    ) = GradedSegment(
        grade = grade,
        lengthM = edge.lengthM,
        names = edge.names,
        wayIds = listOf(edge.wayId),
        roadClass = edge.roadClass,
        use = edge.use,
        speedMph = speedMph,
        speedSource = speedSource,
        reason = reason,
        ladderHole = hole,
        onTrailGeometry = onTrail,
        cycleLane = edge.cycleLane,
        bicycleNetwork = edge.bicycleNetwork,
        shoulder = edge.shoulder,
    )

    /** The trail geometry is a mapped cycleway or path: grade A by construction. */
    fun gradeTrailLeg(leg: TrailRideLeg): GradedSegment = GradedSegment(
        grade = Grade.A,
        lengthM = leg.lengthM,
        names = listOf(leg.trailName),
        wayIds = emptyList(),
        roadClass = null,
        use = "cycleway",
        speedMph = null,
        speedSource = SpeedSource.NONE,
        reason = "trail geometry: ${leg.trailName} (OSM route relation, paved cycleway/path)",
        onTrailGeometry = true,
    )

    /**
     * Meter counts per grade. [repeat] multiplies every segment: the commute is ridden
     * out and back, so its edges count twice.
     */
    fun breakdown(segments: List<GradedSegment>, repeat: Int = 1): GradeBreakdown {
        val byGrade = LinkedHashMap<Grade, Double>()
        var graded = 0.0
        var ungraded = 0.0
        var rejected = 0.0
        var total = 0.0
        for (s in segments) {
            val m = s.lengthM * repeat
            total += m
            when (s.grade) {
                Grade.UNGRADED -> ungraded += m
                Grade.REJECTED -> rejected += m
                else -> {
                    graded += m
                    byGrade[s.grade] = (byGrade[s.grade] ?: 0.0) + m
                }
            }
        }
        return GradeBreakdown(
            totalM = total,
            byGrade = byGrade,
            gradedM = graded,
            ungradedM = ungraded,
            rejectedM = rejected,
        )
    }

    /** Named stretches per grade, longest first. Caution and ungraded stretches are always named. */
    fun namedSegments(segments: List<GradedSegment>, repeat: Int = 1): List<NamedSegment> {
        val acc = LinkedHashMap<Pair<String, Grade>, NamedSegment>()
        for (s in segments) {
            val key = Pair(s.displayName, s.grade)
            val metres = s.lengthM * repeat
            val speedText = when {
                s.speedSource == SpeedSource.NONE -> "no speed limit"
                s.speedSource == SpeedSource.OVERRIDE -> "${s.speedMph} mph (local override)"
                else -> "${s.speedMph} mph (OSM)"
            }
            val existing = acc[key]
            acc[key] = if (existing == null) {
                NamedSegment(
                    name = s.displayName,
                    metres = metres,
                    grade = s.grade,
                    roadClass = s.roadClass,
                    speedText = speedText,
                    volumeText = s.volumeText,
                    volumeUsable = s.traffic?.usableVolume == true,
                    lts = s.ltsAdvisory,
                    trafficNote = s.traffic?.count?.note,
                )
            } else {
                existing.copy(metres = existing.metres + metres)
            }
        }
        return acc.values.sortedByDescending { it.metres }
    }

    /**
     * D10, `docs/SPEC.md` section 6. A thin delegate: the rule lives in [d10Verdict] so the saved-route
     * path in the UI cannot drift from the planner's. Callers that already hold a grader read
     * naturally through here.
     */
    fun evaluateD10(
        breakdown: GradeBreakdown,
        dCapShare: Double = 1.0,
        dCapMiles: Double = 1.1,
        minGradedShare: Double = DEFAULT_MIN_GRADED_SHARE,
    ): PolicyVerdict = d10Verdict(breakdown, dCapShare, dCapMiles, minGradedShare)
}

/**
 * Coverage gate used when a caller has no config to hand (mirrors `unverified_ungraded_share`).
 * The knob in `assets/config.json` exists and was parsed, but was never passed to the rule.
 */
const val DEFAULT_MIN_GRADED_SHARE = 0.5

/**
 * D10: only A/B/C plus at most [dCapShare] and [dCapMiles] of grade D, and never grade E - and only
 * when enough of the ride was graded to say so.
 *
 * The cap is evaluated over GRADED metres. UNGRADED is not measured caution, it is *unknown*, so
 * charging it to the caution cap would be a category error; it is handled by the coverage gate
 * instead, which downgrades the verdict rather than inventing a violation.
 *
 * This is deliberately a pure function rather than a [SafetyGrader] method: two verdict constructors
 * existed (this one and a hand-rolled `PolicyVerdict(...)` in the UI's saved-trip path) and they had
 * already drifted - both used `ungradedShare > 0.0`, so a single ungraded metre flagged a ride while
 * 48.53% of the measured network is ungraded. One implementation, two callers.
 */
fun d10Verdict(
    breakdown: GradeBreakdown,
    dCapShare: Double = 1.0,
    dCapMiles: Double = 1.1,
    minGradedShare: Double = DEFAULT_MIN_GRADED_SHARE,
): PolicyVerdict {
    val violations = ArrayList<String>()
    val gradedM = breakdown.gradedM
    val dM = breakdown.metres(Grade.D)

    if (breakdown.hasRejected) {
        violations.add("rejected surface: " + fmt(breakdown.rejectedM, 0) + " m (D11)")
    }
    if (breakdown.hasE) {
        violations.add("grade E: " + fmt(breakdown.metres(Grade.E), 0) + " m, and D10 allows none")
    }
    // Any graded metre at all is enough to judge the cap. The old guard was `gradedM > 1.0`, which
    // silently skipped the whole check for a barely-graded ride - that is the vacuous pass.
    if (gradedM > 0.0) {
        val dShareOfGraded = dM / gradedM
        val dMiles = dM / Geo.METRES_PER_MILE
        if (dShareOfGraded > dCapShare) {
            violations.add("grade D is " + fmt(dShareOfGraded * 100, 1) + "% of the graded route (cap " + fmt(dCapShare * 100, 0) + "%)")
        }
        if (dMiles > dCapMiles) {
            violations.add("grade D is " + fmt(dMiles, 2) + " mi (cap " + fmt(dCapMiles, 2) + " mi)")
        }
    }

    val outcome = when {
        violations.isNotEmpty() -> SafetyOutcome.FAIL
        // Nothing to judge: never a pass, and never a refusal either.
        breakdown.totalM <= 0.0 -> SafetyOutcome.UNVERIFIED
        breakdown.gradedShare < minGradedShare -> SafetyOutcome.UNVERIFIED
        else -> SafetyOutcome.PASS
    }
    return PolicyVerdict(
        outcome = outcome,
        violations = violations,
        gradedShare = breakdown.gradedShare,
    )
}
