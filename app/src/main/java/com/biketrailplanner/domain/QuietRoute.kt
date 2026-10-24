package com.biketrailplanner.domain

/**
 * D44: how much of a ride is actually on the small, quiet streets.
 *
 * The rider's own complaint, 2026-09-20: *"the route determination part is too fixed on the big roads.
 * There are a lot of smaller road under 25 miles it's not using at all. Try backtracing algo, or gold
 * start algo, but instead take the longest route instead of shortest."*
 *
 * D13's via-point mechanism can only ever force ONE street, and it only ran when nothing else was
 * compliant. So a plan that rode 3 km of arterial to reach a trail was "compliant" and was never asked
 * whether a quieter way existed. This module answers the missing question: given a route's traced
 * edges, how much of it is the kind of street the rider wants, and which of two routes is the quieter?
 *
 * What counts as GOOD, and why:
 *  - grade A/B/C - a trail or separated lane, or a road measured at 25 mph or less. This is evidence.
 *  - UNGRADED on a small road class (residential / service / service_other / living street) - no
 *    `maxspeed` in OSM, but the class default is under 25 mph (Valhalla's Washington table puts
 *    residential at 18). This is the D40 assumption at segment level, and it is counted SEPARATELY in
 *    [smallUnknownM] so the screens can say how much of the ride rests on it. It is never presented as
 *    measured: the ride's "N% measured" line already reports the graded share, and an unposted street
 *    still grades UNGRADED.
 *
 * What counts against: grade D (caution), grade E, a rejected surface, and any known speed above
 * 25 mph. Everything is in metres, so the numbers can be read straight off the traced edges.
 */
data class QuietScore(
    val totalM: Double,
    /** A/B/C plus the class-quiet ungraded metres: the part of the ride on streets that suit a bike. */
    val goodM: Double,
    /** The part of [goodM] that rests on the road class alone, not on a posted limit or a count. */
    val smallUnknownM: Double,
    /** Metres where a speed IS known and is above [QuietRoute.QUIET_MPH]. */
    val over25M: Double,
    /** Grade D, grade E and rejected surfaces. */
    val busyM: Double,
    /**
     * D50: metres on a MAJOR road ([QuietRoute.isMajor]) that carries no bike facility at all
     * ([QuietRoute.hasBikeFacility]). This is the rider's "avoid major streets without bike lane",
     * made countable. It is a routing signal, never a grade: an unposted major road counted here is
     * still UNGRADED, and a ride that uses it is still not verified.
     */
    val majorNoLaneM: Double = 0.0,
) {
    /** Share of the ride on streets that suit a bike, 0..1. */
    val goodShare: Double get() = if (totalM <= 0.0) 0.0 else goodM / totalM

    /** Share of the ride where a speed is known and exceeds the quiet threshold, 0..1. */
    val over25Share: Double get() = if (totalM <= 0.0) 0.0 else over25M / totalM

    /** Human-sized numbers for a probe report or a log line. */
    fun report(): String =
        "good=%.2fkm (%.0f%%, %.2fkm of it by class) over25=%.2fkm majorNoLane=%.2fkm busy=%.2fkm of %.2fkm"
            .format(
                goodM / 1000, goodShare * 100, smallUnknownM / 1000,
                over25M / 1000, majorNoLaneM / 1000, busyM / 1000, totalM / 1000,
            )
}

object QuietRoute {

    /** D10's own quiet threshold: at or below this, a shared road is grade C. */
    const val QUIET_MPH: Int = 25

    /**
     * D50: the road classes the rider means by "major street". These are exactly the classes whose
     * Valhalla default speed is over the quiet threshold (tertiary 39 mph and up), so an unposted one
     * is the road the rider asked to avoid unless it carries a bike lane. `unclassified` is left out
     * deliberately: it is a rural lane-scale class here, not one of the arterials the complaint names.
     */
    val MAJOR_CLASSES: Set<String> = setOf("tertiary", "secondary", "primary", "trunk", "motorway")

    /** Valhalla `cycle_lane` values that count as an actual on-street bike facility. */
    private val FACILITY_LANES: Set<String> = setOf("dedicated", "separated", "shared")

    /**
     * D50 (rider, decision A): does this segment carry a bike facility? A dedicated, separated or
     * shared lane, a signed bicycle network, or a shoulder. `shoulder` is included for completeness
     * but is measured `false` on 100% of the local edges (docs/SERVER.md section 8), so it carries no
     * signal here.
     */
    fun hasBikeFacility(s: GradedSegment): Boolean =
        (s.cycleLane != null && s.cycleLane in FACILITY_LANES) || s.bicycleNetwork > 0 || s.shoulder

    /** True for the major classes (D50). */
    fun isMajor(roadClass: String?): Boolean = roadClass != null && roadClass in MAJOR_CLASSES

    /**
     * The grades that are evidence in themselves: a trail, a separated lane, or a road measured at
     * 25 mph or less. UNGRADED is deliberately NOT here - see [score], where it counts only on a small
     * road class and is tracked separately.
     */
    private val EVIDENCE_GRADES = setOf(Grade.A, Grade.B, Grade.C)

    /**
     * Score one route's traced edges. [segments] should be the ROAD legs of the round trip (the trail
     * itself is grade A and would flatter the number); pass the trail too if the caller wants the whole
     * picture, it changes nothing but the denominator.
     */
    fun score(segments: List<GradedSegment>): QuietScore {
        var total = 0.0
        var good = 0.0
        var unknown = 0.0
        var over = 0.0
        var busy = 0.0
        var majorNoLane = 0.0
        for (s in segments) {
            val m = s.lengthM
            if (m <= 0.0) continue
            total += m
            val speed = s.speedMph
            if (speed != null && speed > QUIET_MPH) over += m
            val facility = hasBikeFacility(s)
            // D50: a major road is "without a bike lane" unless it carries a facility, and a major the
            // rider can legally ride slowly (<= 25 mph) is not the road they objected to.
            if (isMajor(s.roadClass) && !facility && !(speed != null && speed <= QUIET_MPH)) majorNoLane += m
            when {
                s.grade in EVIDENCE_GRADES -> good += m
                s.grade == Grade.UNGRADED && QuietLinks.isSmallRoad(s.roadClass, s.use) -> {
                    good += m
                    unknown += m
                }
                // D50: with no posted limit, a real bike facility is the evidence the class could not
                // give - a lane or a signed network is infrastructure, so that stretch counts as good.
                s.grade == Grade.UNGRADED && facility -> good += m
                // D50 (rider's decision B): a major the rider can legally ride at 25 mph or less is a
                // slow street, not a busy one, even when the ladder's own hole graded it D.
                s.grade == Grade.D && speed != null && speed <= QUIET_MPH -> good += m
                s.grade == Grade.D || s.grade == Grade.E || s.grade == Grade.REJECTED -> busy += m
            }
        }
        return QuietScore(total, good, unknown, over, busy, majorNoLane)
    }

    /**
     * Is [candidate] a quieter ride than [current]? The order is deliberate:
     *  1. more of the ride on streets that suit a bike ([QuietScore.goodShare]),
     *  2. then less of it on major roads with no bike lane ([QuietScore.majorNoLaneM], D50),
     *  3. then less of it where a speed IS known and is over 25 mph,
     *  4. then the shorter ride,
     *  5. then the signature, so two identical scores always resolve the same way.
     *
     * "Longest, not shortest" is the rider's phrasing; this is what it means in a rule that still has
     * to terminate. Distance is the LAST tiebreak, never the first: a route that is 0.3 mi longer and
     * 20 points quieter is the one they described wanting.
     */
    fun quieter(candidate: QuietScore, current: QuietScore, candidateM: Double, currentM: Double, candidateId: String, currentId: String): Boolean {
        if (candidate.goodShare > current.goodShare + SHARE_MARGIN) return true
        if (candidate.goodShare < current.goodShare - SHARE_MARGIN) return false
        // D50: next the rider's explicit objection - major roads with no bike lane.
        if (candidate.majorNoLaneM < current.majorNoLaneM - DISTANCE_MARGIN_M) return true
        if (candidate.majorNoLaneM > current.majorNoLaneM + DISTANCE_MARGIN_M) return false
        if (candidate.over25M < current.over25M - DISTANCE_MARGIN_M) return true
        if (candidate.over25M > current.over25M + DISTANCE_MARGIN_M) return false
        if (candidateM < currentM - DISTANCE_MARGIN_M) return true
        if (candidateM > currentM + DISTANCE_MARGIN_M) return false
        return candidateId < currentId
    }

    /** A quiet-share difference smaller than this is noise, not an improvement. */
    const val SHARE_MARGIN: Double = 0.02

    /** A metre difference smaller than this is noise. */
    const val DISTANCE_MARGIN_M: Double = 25.0
}

/**
 * D44: the bounded search over quiet-street via-point combinations.
 *
 * The rider asked for a "backtracking" search, and a longest-path search is NP-hard, so the shape here
 * is the honest version of that: a small, deterministic, BOUNDED enumeration that the planner can
 * afford (every proposal costs two HTTP calls: `/route` then `trace_attributes`).
 *
 *  - single streets first, in the pool's own order (best evidence first, see [QuietLinks.select]);
 *  - then pairs, taken only from the first [PAIR_POOL] streets, because a pair of two mediocre streets
 *    is rarely the answer and the combinations grow quadratically;
 *  - never the same set twice ([propose] dedupes on the sorted way ids);
 *  - never a street the route already rides (those are passed in as [skipWayIds]);
 *  - and never more than [maxEvaluations] proposals in total.
 *
 * The planner stops early on success, so the budget is a ceiling, not a fixed cost.
 */
object QuietSearch {

    /** Distinct streets a single proposal may force the route through. */
    const val DEFAULT_MAX_VIAS: Int = 2

    /** How many of the pool's best streets are considered for pairs. */
    const val PAIR_POOL: Int = 5

    fun propose(
        pool: List<QuietWay>,
        maxVias: Int = DEFAULT_MAX_VIAS,
        maxEvaluations: Int = 6,
        skipWayIds: Set<Long> = emptySet(),
    ): List<List<QuietWay>> {
        if (maxEvaluations <= 0 || maxVias <= 0) return emptyList()
        val usable = pool.filter { it.wayId !in skipWayIds }.distinctBy { it.wayId }
        if (usable.isEmpty()) return emptyList()
        val out = ArrayList<List<QuietWay>>()
        val seen = HashSet<String>()
        fun add(combo: List<QuietWay>) {
            if (out.size >= maxEvaluations) return
            val key = combo.map { it.wayId }.sorted().joinToString("+")
            if (!seen.add(key)) return
            out.add(combo)
        }
        for (w in usable) add(listOf(w))
        if (maxVias >= 2) {
            val pairPool = usable.take(PAIR_POOL)
            for (i in pairPool.indices) {
                for (j in i + 1 until pairPool.size) {
                    add(listOf(pairPool[i], pairPool[j]))
                }
            }
        }
        return out
    }
}
