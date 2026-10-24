package com.biketrailplanner.domain

import com.biketrailplanner.data.RouteResult

data class PlanRequest(
    val targetMiles: Double,
    /** D22: the live GPS position. The ride starts and finishes here, never at the saved home. */
    val origin: LatLon,
    val originAccuracyM: Float? = null,
    val originFixAgeS: Long? = null,
    val maxCandidates: Int = 12,
    val accessPointsPerTrail: Int = 2,
    val allowFallback: Boolean = false,
    /** D13 option (ii): plan this trail instead of ranking every trail. */
    val preferTrails: List<String> = emptyList(),
    /**
     * D27, as the rider narrowed it on 2026-09-16: **the AI is used only when the rider taps "Plan
     * ride"**. "don't directly use ai, only use it when I click on Plan ride button. Don't use it up
     * front or for existing rides."
     *
     * So this defaults to FALSE and only the explicit button path sets it. Automatic work - reading a
     * fix, refreshing the screen, re-planning after the location moves, adopting a saved ride, any
     * retry or background run - must leave it false, and the plan is then exactly the plan the app
     * made before the AI existed. Defaulting to false also means a caller that forgets the flag errs
     * towards NOT spending the rider's tokens.
     */
    val allowAi: Boolean = false,
)

/** One candidate the planner tried, whether or not it produced a route. */
data class CandidateAttempt(
    val label: String,
    val route: PlannedRoute?,
    val failure: String?,
) {
    val produced: Boolean get() = route != null
}

/** A quiet intermediate point a detour candidate was forced through (D13). */
data class ViaRef(
    val point: LatLon,
    val label: String,
    val volumeBand: VolumeBand,
    val aadt: Int?,
    val aadtYear: Int?,
    val lts: Int? = null,
) {
    val text: String
        get() = buildString {
            append(label)
            aadt?.let { append(" ($it vpd") ; aadtYear?.let { y -> append(", $y") }; append(")") } ?: append(" (no count)")
            lts?.let { append(", LTS $it") }
        }
}

/** D13 rule 3: what to offer when the only compliant route leaves a token trail ride. */
data class TrailFloorOffer(
    val floorMiles: Double,
    val bestTrailMiles: Double,
    /** The total this candidate would have to be for the trail floor to be kept. */
    val suggestedTotalMiles: Double,
    val shortestRouteMiles: Double,
    val trailName: String = "",
    val requestedMiles: Double = 0.0,
    /** False when the trail itself cannot supply the floor, so extra mileage would not help. */
    val canReachFloor: Boolean = true,
) {
    val longerTotal: Boolean get() = suggestedTotalMiles > requestedMiles + 0.05

    val options: List<String>
        get() = listOf(
            if (!canReachFloor) {
                "no extra mileage helps: " + trailName + " can supply only about " + fmt(bestTrailMiles, 1) +
                    " mi of trail, under the " + fmt(floorMiles, 1) + " mi floor, so pick a different trail"
            } else if (longerTotal) {
                "accept a longer total: about " + fmt(suggestedTotalMiles, 1) + " mi round trip, which keeps " +
                    fmt(floorMiles, 1) + " mi of trail"
            } else {
                "accept a shorter round trip on this trail: about " + fmt(suggestedTotalMiles, 1) +
                    " mi, which keeps " + fmt(floorMiles, 1) + " mi of trail - " + trailName +
                    " cannot supply " + fmt(requestedMiles, 1) + " mi of ride"
            },
            "plan a different trail from the options list instead",
            "see why the direct route was refused (named segments with their counts)",
        )

    val buttonLabel: String
        get() = if (longerTotal) {
            "Accept a longer total (" + fmt(suggestedTotalMiles, 1) + " mi)"
        } else {
            "Plan the ride this trail supports (" + fmt(suggestedTotalMiles, 1) + " mi)"
        }
}

/**
 * D31 (docs/LOOPS.md): the measured loop the ride comes back on.
 *
 * It exists so the screen can say something checkable ("X% of the roads on the way back are new")
 * instead of something vague, and so a later session can see that a loop was a MEASURED decision
 * rather than a claim.
 */
data class LoopInfo(
    /** The via point the return leg was forced through. */
    val viaPoint: LatLon,
    /** How the planner describes that point to itself, e.g. "1336 m off the outbound line". */
    val label: String,
    /** Share of the outbound's road ways the return leg uses again. 0.0 = a completely new way back. */
    val overlapShare: Double,
    /** Metres this way back costs over the mirrored return. Never more than the rider's budget. */
    val extraM: Double,
) {
    /** The share of the way back that is roads the rider did not already ride out. */
    val newRoadShare: Double get() = (1.0 - overlapShare).coerceIn(0.0, 1.0)
}

data class PlannedRoute(
    val signature: String,
    val trailNames: List<String>,
    val accessPoint: AccessPoint,
    val turnaround: LatLon,
    val commute: RouteResult,
    val trailLegs: List<TrailRideLeg>,
    /** Trail metres (not counting road detours) ridden one way. */
    val trailOneWayM: Double,
    val trailRoundTripM: Double,
    val targetMiles: Double,
    val capped: Boolean,
    val capReason: String?,
    val roundTrip: List<LatLon>,
    val segments: List<GradedSegment>,
    /** The OUTBOUND road leg, in route order, one copy (not doubled). */
    val roadSegments: List<GradedSegment>,
    /**
     * D31: the return leg's own graded segments, when the return is not the mirrored outbound.
     *
     * Empty means the old behaviour (the return is the outbound reversed), which is why the map can
     * keep mirroring the outbound runs for every ride planned before this, and for every ride where no
     * loop was found. Kept separate from [roadSegments] on purpose: the hand-off and the map map those
     * edges onto `commute.points` by cumulative length, so mixing the two legs into one list would
     * colour the wrong half of the ride.
     */
    val returnSegments: List<GradedSegment> = emptyList(),
    val breakdown: GradeBreakdown,
    val verdict: PolicyVerdict,
    /** Every named stretch on the round trip, longest first. */
    val named: List<NamedSegment>,
    val cautions: List<NamedSegment>,
    val ungraded: List<NamedSegment>,
    val rejections: List<NamedSegment>,
    /** Road detours around closed trail segments (SPEC section 7b). */
    val detours: List<Planner.DetourInfo>,
    val detourOneWayM: Double,
    val safetyNotes: List<String>,
    val notes: List<String>,
    /** Quiet via-points this candidate was forced through (D13). */
    val via: List<ViaRef> = emptyList(),
    /** totalM / shortest candidate total, filled in once every candidate is known. */
    val distanceFactor: Double = 1.0,
    /** Compliant only past the 1.40x acceptance limit: the rider must accept it (D13). */
    val explicitDetour: Boolean = false,
    /** Rest of the trip without the trail; carried so the planner can shorten the ride (D2). */
    val belowTrailFloor: Boolean = false,
    /**
     * D31 (docs/LOOPS.md): the REAL return leg, when the rider comes back a different way.
     *
     * Null means the return is the mirrored outbound - the behaviour this app had before D31, and
     * still the honest answer when no different way back passed D10 or fitted the rider's 5 km
     * budget. Non-null means [roundTrip] was built from this leg rather than by reversing the
     * commute, and every total below counts it.
     */
    val returnCommute: RouteResult? = null,
    /** D31: the measured loop that was chosen, or null. */
    val loop: LoopInfo? = null,
    /**
     * D31: true when the planner LOOKED for a different way back this run.
     *
     * Carried separately from [loop] because "no loop was found" is something the rider must be told
     * ("this one comes back the same way"), and that sentence may only be said when the search
     * actually ran - not when the preference is switched off in `assets/config.json`.
     */
    val loopSearched: Boolean = false,
) {
    val totalM: Double get() = commute.lengthM + (returnCommute?.lengthM ?: commute.lengthM) + trailRoundTripM
    val commuteM: Double get() = commute.lengthM + (returnCommute?.lengthM ?: commute.lengthM)
    /**
     * True when the ride is not fully vouched for, so the screens must say so.
     *
     * This is the VERDICT's answer, not the breakdown's. It used to read
     * `safetyNotes.isNotEmpty() || breakdown.ungradedShare > 0.0`, which flagged a ride as soon as a
     * single metre was ungraded - so the screen would have contradicted the verdict the domain had
     * just computed, calling a PASS ride NOT VERIFIED.
     */
    val unverified: Boolean get() = safetyNotes.isNotEmpty() || verdict.outcome != SafetyOutcome.PASS

    /**
     * Round-trip time: both routed road legs, plus the trail at a conservative 18 km/h.
     *
     * The outbound leg twice when there is no loop (the old behaviour), otherwise the outbound plus
     * the real return - a loop can be longer than the mirrored ride, and the clock must say so.
     */
    val estimatedTotalTimeS: Double
        get() = commute.timeS + (returnCommute?.timeS ?: commute.timeS) + trailRoundTripM / 5.0

    val viaText: String? get() = via.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.text }

    val trailMiles: Double get() = Geo.metresToMiles(trailRoundTripM)

    val totalMiles: Double get() = Geo.metresToMiles(totalM)
    val trailLabel: String get() = trailNames.joinToString(" + ")
}

/** The four mutually exclusive outcomes of a plan (docs/UI.md section 4.5). */
enum class PlanOutcome {
    /** Compliant, and the trail portion is at or above the floor: no warning card at all. */
    PLAN_OK,
    /** The trail runs out, so the ride is shorter than asked (D9). */
    CAPPED,
    /** A compliant route exists, but it leaves less trail than the floor (D13 rule 3). */
    TRAIL_FLOOR,
    /**
     * D42 (rider, 2026-09-21: *"I need you not to disable the route for navigation, instead, just
     * advise it's not safe, still allow us to route"*): nothing compliant exists, so the ride on
     * screen is the BEST attempt found. It is offered and routable; the screen advises, and never
     * calls it safe. This is not a refusal - [REFUSED] is kept for the case where there is no route
     * to offer at all.
     */
    UNSAFE,
    /** No route at all could be computed: nothing to hand over. */
    REFUSED,
    /** D22 rule 3: no bundled trail is within reach of the live position. */
    NO_TRAILS,
}

data class PlanResult(
    val targetMiles: Double,
    val home: LatLon,
    val chosen: PlannedRoute?,
    val options: List<PlannedRoute>,
    val bestAttempt: PlannedRoute?,
    val attempts: List<CandidateAttempt>,
    val warnings: List<String>,
    val dataGaps: List<String>,
    val baseUrlUsed: String?,
    val usedFallback: Boolean,
    val safeRouteFound: Boolean,
    /**
     * The rider's own caution limit, in miles of grade D (D10; config `d_cap_miles`). Carried on the
     * result so the refusal sentence can state the real number instead of a copy of it - the two
     * drifted apart once already, when the cap moved to 1.1 mi and the sentence still said 0.5.
     */
    val dCapMiles: Double = 1.1,
    /** Shortest candidate total. The D13 acceptance bands are measured against
     * [detourReferenceM], which is this value unless the rider asked for more. */
    val shortestTotalM: Double = 0.0,
    /**
     * The distance the D13 acceptance bands were measured against: the longer of [shortestTotalM]
     * and the mileage the rider asked for. A compliant route within the requested mileage is what
     * the rider asked for, not a detour, so the bands must not be allowed to call it one.
     */
    val detourReferenceM: Double = 0.0,
    /** True when the chosen route is compliant only as an explicit detour (D13 rule 2). */
    val requiresDetourAcceptance: Boolean = false,
    /** True when the chosen route is longer than the requested mileage (D13 rule 4). */
    val exceedsTarget: Boolean = false,
    /** Why nothing compliant was found, when that is the outcome. */
    val refusal: String? = null,
    /** Segments that disqualified the best attempt, with their traffic counts and years. */
    val disqualifying: List<NamedSegment> = emptyList(),
    /** Populated when the only compliant route would leave a token trail ride (D13 rule 3). */
    val trailFloorOffer: TrailFloorOffer? = null,
    /** Which of the mutually exclusive states this plan is in. */
    val outcome: PlanOutcome = PlanOutcome.REFUSED,
    /** D22: true when the rider rode this same signature last time and it was the only option. */
    val repeatOfLast: Boolean = false,
    /** D22 rule 6: the traffic tables do not cover this area, so most roads cannot be graded. */
    val outsideDataArea: Boolean = false,
    /** D22 rule 3: how many bundled trails are within reach of the live origin. */
    val trailsInRange: Int = 0,
    /**
     * D27: true when the model's own suggestions survived validation and were acted on. False when
     * there is no strategist, no key, a failed call, or a reply the app already had - in every one of
     * those cases the plan is the plan the deterministic planner makes.
     */
    val aiUsed: Boolean = false,
    /**
     * D27 (docs/AI-PLANNING.md section 7): rider-language lines about the model - what it suggested,
     * what was rejected and why, or why it was not used at all. Empty when no AI was available.
     */
    val strategyNotes: List<String> = emptyList(),
) {
    val shortestTotalMiles: Double get() = Geo.metresToMiles(shortestTotalM)
    val chosenMiles: Double? get() = chosen?.totalMiles
    val extraMilesVsShortest: Double? get() = chosen?.let { it.totalM - shortestTotalM }.takeIf { shortestTotalM > 0.0 }?.let { Geo.metresToMiles(it) }

    /** What the D13 percentage is measured against, named so the rider is not misled by it. */
    val detourReferenceText: String
        get() = if (detourReferenceM > shortestTotalM + 1.0) {
            "the " + fmt(Geo.metresToMiles(detourReferenceM), 1) + " mi you asked for"
        } else {
            "the shortest route"
        }
    val extraMilesVsTarget: Double? get() = chosen?.let { Geo.metresToMiles(it.totalM) - targetMiles }
    val chosenMinutes: Double? get() = chosen?.let { it.estimatedTotalTimeS / 60.0 }

    val headline: String
        get() = when {
            chosen != null && chosen.explicitDetour ->
                "COMPLIANT DETOUR - LONGER ROUTE: " + fmt(chosen.totalMiles, 2) + " mi, +" +
                    fmt((chosen.distanceFactor - 1.0) * 100, 0) + "% over " + detourReferenceText
            chosen != null && chosen.unverified ->
                "ROUTE FOUND - NOT VERIFIED: " + Formatting.percent(chosen.breakdown.ungradedShare) + " of it is ungraded"
            chosen != null -> "Safe route found"
            trailFloorOffer != null -> "NO COMPLIANT ROUTE AT THIS MILEAGE"
            bestAttempt != null -> "NO SAFE ROUTE TODAY"
            else -> "No route could be computed"
        }
}

/** Where ride history lives (Room in the app, a map in tests). */
interface RideHistoryStore {
    suspend fun lastRiddenSignature(): String?
    suspend fun riddenAt(): Map<String, Long>
    suspend fun record(signature: String, trailNames: List<String>, totalMiles: Double, atEpochMs: Long)
    suspend fun count(): Int

    /**
     * D58: forget a ride entirely.
     *
     * Deleting a ride from Past rides used to remove only the ride, leaving its history row behind.
     * [lastRiddenSignature] reads the most recent history row with no join, so deleting the newest ride
     * pinned the D6 rotation to a signature that no longer exists - and "do not repeat your last ride"
     * then silently stopped working until another ride was recorded.
     */
    suspend fun forget(signature: String)
}

class InMemoryRideHistory : RideHistoryStore {
    private val entries = LinkedHashMap<String, Pair<Long, Double>>()
    override suspend fun lastRiddenSignature(): String? =
        entries.maxByOrNull { it.value.first }?.key
    override suspend fun riddenAt(): Map<String, Long> = entries.mapValues { it.value.first }
    override suspend fun record(signature: String, trailNames: List<String>, totalMiles: Double, atEpochMs: Long) {
        entries[signature] = atEpochMs to totalMiles
    }
    override suspend fun count(): Int = entries.size
    override suspend fun forget(signature: String) {
        entries.remove(signature)
    }
}
