package com.biketrailplanner.domain

import com.biketrailplanner.data.PlannerDto
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.data.ValhallaException

/**
 * SPEC section 7. Builds candidate itineraries from the near trail set, routes the road legs
 * with Valhalla, grades every edge, enforces D10, and picks the route the rider has ridden
 * longest ago (D6). If nothing survives D10 it reports the best attempt with the exact
 * disqualifying segments - it never silently ships an unsafe route.
 *
 * SPEC section 7c (D13): before refusing, the planner looks for a COMPLIANT LONGER ROUTE. The
 * costing sweep proved that `use_roads`, `use_living_streets` and `bicycle_type` return
 * byte-identical geometry, so the only lever is forcing the route through quiet via-points
 * (band low, or medium with a real count - never a road whose volume is unknown), combined with
 * the alternate access points along the corridor. A compliant candidate is acceptable up to
 * 1.40x the shortest route; up to 1.80x it is offered as an EXPLICIT detour with the extra
 * distance and time stated. Under 2.0 mi of trail it is not delivered as a token ride.
 *
 * SPEC section 7b: a closed trail segment is not the end of the ride - the walk stops there, the
 * planner routes the gap on roads, grades those roads, and continues on the far stub.
 */
class Planner(
    private val network: TrailNetwork,
    private val accessPoints: List<AccessPoint>,
    private val grader: SafetyGrader,
    private val client: RouteService,
    private val config: PlannerDto,
    /**
     * The **data home**: the place the bundled data was measured from.
     *
     * `assets/access_points.json` carries `measured_route_km_from_home` per entrance, and the traffic
     * counts cover roads around this point. That is why it is used for exactly two things - the 2 km
     * tolerance inside which those probe distances still describe the rider's approach, and the
     * data-coverage warning - and for nothing else. The ride itself starts at `request.origin`.
     *
     * Do NOT wire the rider's own "saved home" in here (see the note in docs/DECISIONS.md D28): moving
     * this point would claim the bundled probe distances were measured somewhere they never were, and
     * would mis-state which area has measured traffic.
     */
    private val home: LatLon,
    private val history: RideHistoryStore = InMemoryRideHistory(),
    /**
     * D27 (docs/AI-PLANNING.md): the AI seam. Null means "no AI at all" and the planner then behaves
     * exactly as it did before this seam existed - which is what makes the "no key" path provably the
     * old path. When present, it is asked ONCE per plan, its reply is validated, and its surviving
     * order is applied to the candidate list before anything is evaluated. Its suggestions can only
     * ADD candidates: every spec the app enumerated is still evaluated, so D10 decides what is ridden
     * and rule 4 of section 7 holds.
     */
    private val strategist: RouteStrategist? = null,
    /**
     * D26 (docs/ROUTE-STORE.md section 3.3): the trips already saved for this request. When present,
     * a candidate whose road way-id set is 100% contained in a saved one is dropped, and the
     * survivors are ranked by how much NEW road they use. Null is the old behaviour.
     */
    private val routeStore: RouteStore? = null,
) {

    // The via-point pool used to be a private type here, which is why "which streets may be a
    // via-point" could not be tested without a network. It is now [QuietWay]/[QuietLinks], where
    // D13 rule 1 and D40 live together.

    /** Everything one outbound walk produced, before the return leg is mirrored. */
    private class Outbound(
        val walk: TrailWalker.TrailWalk,
        val trailMetres: Double,
        val detourMetres: Double,
        val detours: List<DetourInfo>,
        val pieces: List<List<LatLon>>,
        val notes: List<String>,
    )

    /** One return leg the D31 loop pass actually routed, traced and graded. */
    private class LoopAttempt(
        val choice: LoopPolicy.Candidate,
        val leg: RouteResult,
        val returnSegments: List<GradedSegment>,
        val all: List<GradedSegment>,
        val breakdown: GradeBreakdown,
        val verdict: PolicyVerdict,
        val totalM: Double,
        val factor: Double,
        val safetyNotes: List<String>,
    )

    /** One road detour around a closed trail segment. */
    data class DetourInfo(
        val closure: TrailClosure,
        val from: LatLon,
        val to: LatLon,
        val roadMetres: Double,
        val segments: List<GradedSegment>,
    ) {
        val streetNames: List<String> get() = segments.map { it.displayName }.distinct()
    }

    /** Ways seen on routes so far in this plan run, keyed by OSM way id. */
    private val observedWays = LinkedHashMap<Long, QuietWay>()

    /** Ways used by a candidate's own commute, so a via-point cannot be a no-op. */
    private val waysPerCandidate = HashMap<String, Set<Long>>()

    suspend fun plan(request: PlanRequest): PlanResult {
        val warnings = ArrayList<String>()
        val dataGaps = ArrayList<String>()

        // D22: the origin is wherever the rider is now. Home is only a saved place.
        val outsideDataArea = Geo.distanceM(request.origin, home) > config.trafficCoverageKm * 1000
        val trailsInRange = network.trails.count { trail ->
            trail.fullGeometry.points.minOf { Geo.distanceM(it, request.origin) } <= config.trailRadiusKm * 1000
        }
        if (outsideDataArea) {
            warnings.add(
                "No local traffic data for this area: the bundled counts cover the roads near home only, " +
                    "so most streets here cannot be graded.",
            )
        }
        if (trailsInRange == 0) {
            warnings.add("No bundled trail is within " + fmt(config.trailRadiusKm, 0) + " km of where you are.")
        }
        network.trails.flatMap { it.closures }.forEach {
            dataGaps.add(
                it.trailName + " is not continuous: " + it.provenance +
                    ". The plan routes around it on roads and grades those roads.",
            )
        }
        if (network.trails.any { it.untaggedSurfaceM > 500 }) {
            dataGaps.add("Some trail metres carry no OSM surface tag; paved is an inference there.")
        }

        val attempts = ArrayList<CandidateAttempt>()
        val produced = ArrayList<PlannedRoute>()

        // Phase 1: the direct candidates.
        val directSpecs = candidateSpecs(request, config.accessPointsPerTrail)
        // D27: ONE model call, before any candidate is evaluated. The planner builds the context
        // itself - it already knows the trails, their entries and the quiet links it may offer - and
        // the surviving order is applied to the candidate specs below.
        val ai = askStrategist(directSpecs, request)
        val tried = HashSet<String>()
        if (ai != null && ai.used) {
            for (spec in ai.ordered) {
                if (!tried.add(signatureOf(spec, emptyList()))) continue
                evaluateInto(spec, emptyList(), request, warnings, attempts, produced)
            }
        }
        // The app's own order is ALWAYS evaluated as well (docs/AI-PLANNING.md section 7 rule 4):
        // the model can only add options and can never remove one, and with no key - where the reply
        // is the app's own order - this loop is the only one that runs and the plan is byte-identical
        // to the plan the app made before the AI existed.
        for (spec in directSpecs) {
            if (!tried.add(signatureOf(spec, emptyList()))) continue
            evaluateInto(spec, emptyList(), request, warnings, attempts, produced)
        }

        // Phase 2 (D13): no compliant direct candidate, so look for a longer compliant one.
        var detourSearched = false
        var detourTried = 0
        if (produced.none { it.verdict.hasNoKnownViolation }) {
            detourSearched = true
            // 2a. Alternate access points along the corridor: cheapest diversity, and their
            //     traces are what fill the quiet-link pool.
            // The alternates must be access points phase 1 did NOT try: passing the tried set in
            // makes this walk further down each trail's ranked list instead of repeating it.
            val alreadyTried = HashSet<String>()
            directSpecs.forEach { alreadyTried.add(signatureOf(it, emptyList())) }
            val detourPool = candidateSpecs(request, config.detourAccessPointsPerTrail, alreadyTried)
            val detourSpecs = if (ai == null) detourPool else ai.orderFirst(detourPool)
            if (System.getenv("PLANNER_DEBUG") != null) {
                println("DBG phase2a directSpecs=" + directSpecs.map { signatureOf(it, emptyList()) } +
                    " detourSpecs=" + detourSpecs.map { signatureOf(it, emptyList()) } +
                    " produced=" + produced.map { it.signature + ":" + it.verdict.outcome })
            }
            for (spec in detourSpecs) {
                if (!alreadyTried.add(signatureOf(spec, emptyList()))) continue
                evaluateInto(spec, emptyList(), request, warnings, attempts, produced)
            }
            // 2b. Force the route through quiet links (band low, or medium with a real count).
            val quietLinks = selectQuietLinks()
            if (quietLinks.isNotEmpty()) {
                warnings.add(
                    "The direct candidates break D10. Trying detours through " +
                        QuietLinks.summary(quietLinks) + " (D13).",
                )
            }
            for (spec in detourSpecs) {
                if (detourTried >= config.maxViaCandidates) break
                val usedBySpec = waysPerCandidate[signatureOf(spec, emptyList())] ?: emptySet()
                // The model may name one quiet link per trail; when it names one the app can see on
                // the traced approaches, that link is tried first here, inside the same budget.
                val viaList = quietLinks.filterNot { it.wayId in usedBySpec }
                    .sortedBy { if (ai?.namedVia(spec.trail.name) == it.label) 0 else 1 }
                    .take(2)
                if (System.getenv("PLANNER_DEBUG") != null) {
                    println("DBG 2b spec=" + signatureOf(spec, emptyList()) + " quiet=" + quietLinks.map { it.wayId } +
                        " used=" + usedBySpec + " viaList=" + viaList.map { it.wayId })
                }
                for (via in viaList) {
                    detourTried++
                    val before = produced.size
                    evaluateInto(spec, listOf(via), request, warnings, attempts, produced)
                    val latest = produced.getOrNull(before)
                    val needsFloor = latest == null || DetourPolicy.belowTrailFloor(latest.trailRoundTripM, config.trailFloorMiles)
                    if (needsFloor) {
                        // keep the mileage target by shortening the trail, but never below the
                        // floor: plan the longer total instead and let the bands decide (D13).
                        evaluateInto(spec, listOf(via), request, warnings, attempts, produced, pinTrailFloor = true)
                    }
                }
            }
            if (detourTried == 0) {
                warnings.add(
                    "No quiet link was available to force a detour through: no traced road with a low or " +
                        "medium measured volume could be reached from a different access point (D13).",
                )
            }
        }

        // Phase 3 (D44): the rider's own complaint - "the route determination part is too fixed on the
        // big roads. There are a lot of smaller road under 25 miles it's not using at all." A plan can
        // be perfectly compliant and still be a ride down the arterials, because phase 1 asks Valhalla
        // for the shortest way and phase 2 only runs when NOTHING is compliant. So when the best
        // compliant ride on the table is mostly big roads, this phase searches a BOUNDED set of quiet
        // street combinations (single streets, then pairs) and keeps a quieter ride if one is found.
        //
        // Bounded on purpose: each proposal costs two HTTP calls, so the budget lives in the config
        // (`max_quiet_evals`), the search stops early on the first good-enough improvement, and the
        // rider can see the clock and stop it (D39).
        // NOTE: `belowTrailFloor` is only filled in later, when `scored` re-copies every candidate, so
        // on a freshly produced route it is still the default `false`. The floor must be computed HERE
        // from the trail mileage, or the search happily picks a 0.6 mi token trail ride and then cannot
        // promote it (measured 2026-09-21: exactly that, and the quiet search's winner was thrown away).
        val quietBenchmark = produced
            .filter { it.verdict.hasNoKnownViolation && !DetourPolicy.belowTrailFloor(it.trailRoundTripM, config.trailFloorMiles) }
            .minByOrNull { penalty(it) }
            ?: produced.filter { it.verdict.hasNoKnownViolation }.minByOrNull { penalty(it) }
        val benchmarkScore = quietBenchmark?.let { QuietRoute.score(it.roadSegments) }
        var quietChosen: PlannedRoute? = null
        var quietTried = 0
        // Two ways to decide the ride is not quiet enough: too little of it on streets that suit a bike,
        // OR a real stretch of the roads the rider objects to (grade D, a known speed over 25 mph, a
        // rejected surface, or - D50 - a major road with no bike lane). The second test matters because
        // a ride can be 42% "good" and still send the rider down 600 m of arterial - which is exactly
        // the complaint. The D50 term is what makes a mostly-tertiary, mostly-unposted ride trigger the
        // search at all: those segments are UNGRADED, so before D50 they counted as neither good nor
        // busy and the ride sailed through the gate.
        val busyEnoughToSearch = benchmarkScore != null &&
            (benchmarkScore.busyM + benchmarkScore.over25M + benchmarkScore.majorNoLaneM) >= config.quietBusyFloorM &&
            benchmarkScore.goodShare < 0.90
        if (quietBenchmark != null && benchmarkScore != null &&
            (benchmarkScore.goodShare < config.quietShareTarget || busyEnoughToSearch)
        ) {
            val spec = directSpecs.firstOrNull { it.ap.osmNodeId == quietBenchmark.accessPoint.osmNodeId }
                ?: directSpecs.firstOrNull()
            if (spec != null) {
                val ridden = quietBenchmark.roadSegments.flatMap { it.wayIds }.toSet()
                val pool = QuietLinks.select(observedWays.values, config.maxViaCandidates)
                val proposals = QuietSearch.propose(
                    pool = pool,
                    maxVias = config.quietMaxVias,
                    maxEvaluations = config.maxQuietEvals,
                    skipWayIds = ridden,
                )
                if (proposals.isNotEmpty()) {
                    warnings.add(
                        "The shortest safe way to " + quietBenchmark.trailLabel + " is mostly on bigger " +
                            "roads, so " + proposals.size + " quieter way(s) through small streets were tried (D44).",
                    )
                }
                for (via in proposals) {
                    quietTried++
                    val before = produced.size
                    evaluateInto(spec, via, request, warnings, attempts, produced)
                    val candidate = produced.getOrNull(before) ?: continue
                    if (!candidate.verdict.hasNoKnownViolation) continue
                    if (DetourPolicy.belowTrailFloor(candidate.trailRoundTripM, config.trailFloorMiles)) continue
                    val scored = QuietRoute.score(candidate.roadSegments)
                    if (quietChosen == null ||
                        QuietRoute.quieter(
                            scored, QuietRoute.score(quietChosen.roadSegments),
                            candidate.totalM, quietChosen.totalM,
                            candidate.signature, quietChosen.signature,
                        )
                    ) {
                        quietChosen = candidate
                    }
                }
                if (System.getenv("PLANNER_DEBUG") != null) {
                    println(
                        "DBG 3 quiet benchmark=" + benchmarkScore.report() +
                            " tried=" + quietTried + " chosen=" + quietChosen?.signature +
                            " after=" + quietChosen?.let { QuietRoute.score(it.roadSegments).report() } +
                            " totalKm=" + quietChosen?.let { "%.2f".format(it.totalM / 1000) } +
                            " factor=" + quietChosen?.distanceFactor +
                            " trailMi=" + quietChosen?.let { "%.2f".format(Geo.metresToMiles(it.trailRoundTripM)) } +
                            " violations=" + quietChosen?.verdict?.violations,
                    )
                }
            }
        }

        if (trailsInRange == 0) {
            val nearest = network.trails.minByOrNull { trail ->
                trail.fullGeometry.points.minOf { Geo.distanceM(it, request.origin) }
            }
            val nearestKm = nearest?.let { trail ->
                trail.fullGeometry.points.minOf { Geo.distanceM(it, request.origin) } / 1000.0
            }
            return PlanResult(
                targetMiles = request.targetMiles,
                dCapMiles = config.dCapMiles,
                home = home,
                chosen = null,
                options = emptyList(),
                bestAttempt = null,
                attempts = emptyList(),
                warnings = warnings,
                dataGaps = dataGaps,
                baseUrlUsed = null,
                usedFallback = false,
                safeRouteFound = false,
                refusal = "No trails in the app cover this area" +
                    (nearestKm?.let { ": the nearest one is " + fmt(it, 1) + " km away" } ?: ""),
                outcome = PlanOutcome.NO_TRAILS,
                outsideDataArea = outsideDataArea,
                trailsInRange = 0,
                aiUsed = ai?.used == true,
                strategyNotes = ai?.notes.orEmpty(),
            )
        }

        if (produced.isEmpty()) {
            val reason = attempts.mapNotNull { it.failure }.firstOrNull() ?: "no candidate could be routed"
            warnings.add("Valhalla returned no usable route. Last error: $reason")
            return PlanResult(
                targetMiles = request.targetMiles,
                dCapMiles = config.dCapMiles,
                home = home,
                chosen = null,
                options = emptyList(),
                bestAttempt = null,
                attempts = attempts,
                warnings = warnings,
                dataGaps = dataGaps,
                baseUrlUsed = null,
                usedFallback = false,
                safeRouteFound = false,
                refusal = "No route could be computed: $reason",
                aiUsed = ai?.used == true,
                strategyNotes = ai?.notes.orEmpty(),
            )
        }

        // D26 (docs/ROUTE-STORE.md section 3.3): a new ride may share roads with a saved one, but it
        // may not be 100% of it. Identity is the OSM road way-id set, read through the SAME
        // RouteOverlap the store ships - there is no second implementation of this rule.
        val savedWayIds = savedWayIdsFor(request, produced)
        val repeatsDropped = if (savedWayIds.isEmpty()) 0 else produced.count { RouteOverlap.fullyRepeats(it.roadWayIdSet(), savedWayIds) }
        // SAFETY FIRST, NOVELTY SECOND (precedence fixed 2026-09-16). The novelty filter used to run
        // HERE, before the D10 classification. With 12 rides saved for one request that meant it
        // removed every COMPLIANT ride the rider already had and left only novel-but-unsafe
        // candidates, so the plan showed its refusal card while the same screen offered five saved
        // rides for that very request (measured on the phone). A preference must never delete the last
        // ride that is actually safe, so the classification below sees EVERYTHING the app produced and
        // novelty is applied afterwards, among the safe rides only (see candidatesForPool).
        //
        // D13: measure every candidate against the shortest one, then accept in bands.
        val shortestTotalM = produced.minOf { it.totalM }
        // D13's bands bound DETOURS, so the reference has to be a distance the rider would accept
        // as "the plan" - and the shortest candidate is not always that. Measured from home, the
        // shortest compliant candidate is a capped 5.16 mi ride (the trail runs out), so measuring
        // against it labelled the compliant 10.00 mi routes that hit the request exactly as
        // "+94% over the shortest route" and discarded them as past the 1.80x limit. A compliant
        // route within the mileage the rider asked for is never a detour, so the reference is the
        // longer of the two.
        val detourReferenceM = maxOf(shortestTotalM, Geo.milesToMetres(request.targetMiles))
        val scored = produced.map { route ->
            val policy = DetourPolicy.evaluate(
                totalM = route.totalM,
                shortestTotalM = detourReferenceM,
                compliant = route.verdict.hasNoKnownViolation,
                acceptFactor = config.detourAcceptFactor,
                maxFactor = config.detourMaxFactor,
                bands = config.detourBands,
            )
            route.copy(
                distanceFactor = policy.factor,
                explicitDetour = policy.explicitDetour,
                belowTrailFloor = DetourPolicy.belowTrailFloor(route.trailRoundTripM, config.trailFloorMiles),
            )
        }

        val acceptable = scored.filter { it.verdict.hasNoKnownViolation && it.distanceFactor <= config.detourAcceptFactor + 1e-9 && !it.belowTrailFloor }
        val explicit = scored.filter {
            it.verdict.hasNoKnownViolation && !it.belowTrailFloor &&
                it.distanceFactor > config.detourAcceptFactor + 1e-9 && it.distanceFactor <= config.detourMaxFactor + 1e-9
        }
        val belowFloorCompliant = scored.filter { it.verdict.hasNoKnownViolation && it.belowTrailFloor }

        // D6's options have to be other RIDES, not one ride entered at several entrances. Adjacent
        // entrances on one trail are separate candidates but the same trip, so collapse them before
        // rotating: without this, every option was one ride entered at three entrances 50 m apart.
        // How many are offered is MAX_OPTIONS (D6, raised to five by the rider on 2026-09-16).
        // Among the survivors, novelty decides which representative of a repeated ride survives
        // RideVariety.distinct (it keeps the first of each group), so a rider who has already ridden
        // some of these roads is offered the ride that adds the most new ones.
        val candidatesForPool = acceptable.ifEmpty { explicit }.ifEmpty { belowFloorCompliant }
        // Novelty is a PREFERENCE among the safe rides, never a filter that can delete them. When every
        // safe ride here is one the rider already has saved, they are offered again with a note -
        // refusing them would report "no safe route" while the saved ones sit on the same screen.
        val novelPool = if (savedWayIds.isEmpty()) {
            candidatesForPool
        } else {
            candidatesForPool
                .filterNot { RouteOverlap.fullyRepeats(it.roadWayIdSet(), savedWayIds) }
                .ifEmpty { candidatesForPool }
        }
        if (repeatsDropped > 0) {
            warnings.add(
                if (novelPool === candidatesForPool) {
                    "Every safe ride the app found today is one you already have saved, so it is shown again."
                } else {
                    repeatsDropped.toString() + " option(s) were rides you already have saved, " +
                        "so only rides using at least one new road are offered."
                },
            )
        }
        // Order: what we can vouch for first, then the better-covered ride, then new roads. Coverage is
        // a PREFERENCE, never a filter - an UNVERIFIED ride is still offered (measured: 48.53% of the
        // local network is ungraded, so filtering on coverage would refuse almost everything).
        // This also nudges the planner off the arterials on its own: near home the tertiary roads are
        // 92.2% unposted while the residential streets are 50.7%, so coverage tracks street size.
        // D44 joins the order: among rides the app can vouch for, the one on the smaller, quieter
        // streets comes first - that is the rider's request in one line ("there are a lot of smaller
        // road under 25 miles it's not using at all"). Coverage still follows, so a quieter ride that
        // rests mostly on ungraded streets does not silently outrank a measured one, and novelty stays
        // the last preference. Quietness is a PREFERENCE, never a filter: nothing is removed here.
        // D44 rule that measurement forced: the ride the quiet search actually FOUND must survive the
        // grouping. Candidates on one trail with one trail mileage and one entrance are "the same ride"
        // (RideVariety), so the search's quieter variant and the arterial variant collapse into one, and
        // the survivor is whichever sorts first. The search's winner is therefore promoted to the front
        // of its group - but only when it is an `acceptable` ride, so an over-long detour still has to
        // be accepted by the rider (D13 rule 2) instead of being slipped in silently.
        // Judged here from the same numbers the bands use, rather than by looking the winner up in
        // `acceptable`: measured 2026-09-21, the winner qualified on every count (16.09 km against a
        // 16.09 km reference, no violations, above the trail floor) and still was not in that list, so
        // the lookup silently promoted nothing at all.
        val promoted = quietChosen?.takeIf { w ->
            w.verdict.hasNoKnownViolation &&
                w.totalM <= detourReferenceM * (config.detourAcceptFactor + 1e-9) &&
                !DetourPolicy.belowTrailFloor(w.trailRoundTripM, config.trailFloorMiles)
        }?.signature
        val varietyInput = novelPool.sortedWith(
                // The search's winner leads its group (see `promoted` above). Then D45: quietness,
                // because on a bike the smaller road is the safer road (the rider: "We are riding bike
                // on these road. Smaller roads normally means less traffic and safer"). A KNOWN
                // violation still sorts last and is filtered out of this pool entirely; PASS vs
                // UNVERIFIED is about how much was measured, not about safety.
                compareBy<PlannedRoute> { if (promoted != null && it.signature == promoted) 0 else 1 }
                    .thenBy { if (it.verdict.hasNoKnownViolation) 0 else 1 }
                    .thenByDescending { QuietRoute.score(it.roadSegments).goodShare }
                    .thenByDescending { it.verdict.gradedShare }
                    .thenByDescending { RouteOverlap.noveltyVsMostSimilar(it.roadWayIdSet(), savedWayIds) },
        )
        // `RideVariety.distinct` keeps the FIRST of each group of equivalent rides, so the search's
        // winner is placed first in the list it sees. Sorting alone was not enough: measured on the
        // real network (2026-09-21, away origin) the search found a ride that was 68% on good streets
        // while the ride offered was 42%, because the two are "the same ride" by trail, trail mileage
        // and entrance, and the grouping kept the other one.
        val pool = RideVariety.distinct(
            if (promoted == null) {
                varietyInput
            } else {
                listOfNotNull(varietyInput.firstOrNull { it.signature == promoted }) + varietyInput
            },
        )
        // D45: the planner's preference travels WITH the candidate, so the rotator cannot discard it.
        // Quietness (D44) is the key; recency only separates rides the planner rates the same, and the
        // "never the same ride twice in a row" rule is applied on top as the hard constraint.
        val ordered = Rotator(history.riddenAt(), history.lastRiddenSignature())
            .order(
                pool.map {
                    RotationCandidate(
                        signature = it.signature,
                        label = it.trailLabel,
                        preference = QuietRoute.score(it.roadSegments).goodShare,
                    )
                },
            )
        val bySignature = pool.associateBy { it.signature }
        if (System.getenv("PLANNER_DEBUG") != null) {
            println(
                "DBG 4 promoted=" + promoted + " inAcceptable=" +
                    (promoted != null && acceptable.any { it.signature == promoted }) +
                    " acceptable=" + acceptable.map { it.signature.takeLast(20) + "@%.2f".format(it.distanceFactor) } +
                    " pool=" + pool.map {
                    it.signature.takeLast(24) + ":" + "%.2f".format(QuietRoute.score(it.roadSegments).goodShare) +
                        "@" + "%.2f".format(it.distanceFactor) + (if (it.belowTrailFloor) "!floor" else "") +
                        (if (it.signature == promoted) "*promoted" else "")
                },
            )
        }
        val chosenOptions = ordered.mapNotNull { bySignature[it.signature] }
        // A compliant route that leaves a token trail ride is still a ROUTE: it is state
        // TRAIL_FLOOR (amber), never the red refusal. Only "no compliant candidate at all" refuses.
        val chosenBeforeLoop = chosenOptions.firstOrNull()
        // D31 (`docs/LOOPS.md`): the rider asked not to come back the same way. This runs ONLY for the
        // ride that would be chosen, and only when a ride is being handed over at all - and it may
        // only ever REPLACE that ride with a loop the same D10 rules still pass. `chosen` below is
        // therefore the looped ride when one was found, and the same object otherwise.
        val chosen = if (chosenBeforeLoop != null && config.preferLoop) {
            loopPass(chosenBeforeLoop, request, detourReferenceM, warnings)
        } else {
            chosenBeforeLoop
        }
        // The option row must show the ride the rider is actually being offered, not the pre-loop
        // version of it.
        val optionsAfterLoop = if (chosen != null && chosen !== chosenBeforeLoop) {
            listOf(chosen) + chosenOptions.drop(1)
        } else {
            chosenOptions
        }
        val requiresAcceptance = chosen != null && chosen.distanceFactor > config.detourAcceptFactor + 1e-9

        if (chosen != null) {
            if (requiresAcceptance) {
                val versus = if (detourReferenceM > shortestTotalM + 1.0) {
                    "the " + fmt(Geo.metresToMiles(detourReferenceM), 2) + " mi you asked for"
                } else {
                    "the shortest route (" + fmt(Geo.metresToMiles(shortestTotalM), 2) + " mi)"
                }
                warnings.add(
                    "The only compliant route is an explicit detour (D13): " + fmt(chosen.totalMiles, 2) +
                        " mi in " + fmt(chosen.estimatedTotalTimeS / 60.0, 0) + " min, +" +
                        fmt((chosen.distanceFactor - 1.0) * 100, 0) + "% over " + versus +
                        ". Accept it before navigating.",
                )
            }
            if (chosen.totalM > Geo.milesToMetres(request.targetMiles) + 1.0) {
                warnings.add(
                    "This ride is longer than you asked for (D13 rule 4): " + fmt(chosen.totalMiles, 2) +
                        " mi instead of " + fmt(request.targetMiles, 2) + " mi.",
                )
            }
        }

        val repeatOfLast = chosen != null && optionsAfterLoop.size == 1 &&
            Rotator(history.riddenAt(), history.lastRiddenSignature()).isRepeatOfLast(chosen.signature)
        val bestAttempt = if (chosen == null) scored.minByOrNull { penalty(it) } else null
        val trailFloorOffer = if (belowFloorCompliant.isNotEmpty() && (chosen == null || chosen.belowTrailFloor)) {
            val best = belowFloorCompliant.minByOrNull { it.distanceFactor }!!
            TrailFloorOffer(
                floorMiles = config.trailFloorMiles,
                bestTrailMiles = best.trailMiles,
                suggestedTotalMiles = Geo.metresToMiles(best.totalM) + (config.trailFloorMiles - best.trailMiles),
                shortestRouteMiles = Geo.metresToMiles(shortestTotalM),
                trailName = best.trailLabel,
                requestedMiles = request.targetMiles,
                // More mileage only helps if the corridor can actually supply the floor: a capped
                // walk cannot, and neither can a corridor whose whole rideable length is short.
                canReachFloor = !best.capped && 2 * corridorReachM(best) >= DetourPolicy.trailFloorM(),
            )
        } else {
            null
        }
        val disqualifying = bestAttempt?.let { (it.cautions + it.rejections).distinctBy { s -> s.name } } ?: emptyList()
        // D42: ADVISE, never disable. When no candidate is compliant, the best attempt becomes the
        // ride the app offers - drawn, handed off and navigable like any other - and the screen says
        // plainly that it breaks the rider's own rule, name by name. The app's job is to tell the
        // rider the truth about a road, not to take the decision away from them. ("I need you not to
        // disable the route for navigation, instead, just advise it's not safe, still allow us to
        // route.")
        val offered = chosen ?: bestAttempt
        val outcome = when {
            chosen != null && chosen.belowTrailFloor -> PlanOutcome.TRAIL_FLOOR
            chosen != null && chosen.capped -> PlanOutcome.CAPPED
            chosen != null -> PlanOutcome.PLAN_OK
            offered != null -> PlanOutcome.UNSAFE
            else -> PlanOutcome.REFUSED
        }
        val refusal = when {
            chosen != null -> null
            offered != null -> "Nothing we found today meets your safety limits, so this is the best " +
                "route we have. It is not safe as planned - the streets below are why."
            trailFloorOffer != null -> "The only compliant routes leave less than " + fmt(config.trailFloorMiles, 1) +
                " mi of trail, so the app will not deliver a token trail ride (D13 rule 3)."
            detourSearched -> "No compliant candidate exists in any band, including detours up to " +
                fmt((config.detourMaxFactor - 1.0) * 100, 0) + "% longer than the shortest route."
            else -> "No compliant candidate exists."
        }
        val fallbackUsed = produced.any { it.commute.usedFallback }

        return PlanResult(
            targetMiles = request.targetMiles,
            dCapMiles = config.dCapMiles,
            home = home,
            chosen = offered,
            // D42: with nothing compliant there is no pool to choose from, but there IS a ride, so the
            // option row shows the one the app is offering instead of an empty picker.
            options = if (chosen != null) optionsAfterLoop.take(MAX_OPTIONS) else listOfNotNull(offered),
            bestAttempt = bestAttempt,
            attempts = attempts,
            warnings = warnings,
            dataGaps = dataGaps,
            baseUrlUsed = produced.first().commute.baseUrl,
            usedFallback = fallbackUsed,
            safeRouteFound = chosen != null,
            repeatOfLast = chosen != null && repeatOfLast,
            outsideDataArea = outsideDataArea,
            trailsInRange = trailsInRange,
            shortestTotalM = shortestTotalM,
            detourReferenceM = detourReferenceM,
            requiresDetourAcceptance = requiresAcceptance,
            exceedsTarget = chosen != null && chosen.totalM > Geo.milesToMetres(request.targetMiles) + 1.0,
            refusal = refusal,
            disqualifying = disqualifying,
            trailFloorOffer = trailFloorOffer,
            outcome = outcome,
            aiUsed = ai?.used == true,
            strategyNotes = ai?.notes.orEmpty(),
        )
    }

    /**
     * D27: everything the model said, after validation, in the shape the planner can act on.
     *
     * [ordered] is the surviving suggestions mapped onto candidates the app actually enumerated, in
     * the model's order. [names] is the same order in words, for the rider-facing line.
     */
    private class AiAdvice(
        val ordered: List<Spec>,
        val names: List<String>,
        val viaNames: Map<String, String>,
        /** True when the model's own reply survived; false when the app's own order did the work. */
        val used: Boolean,
        val notes: List<String>,
    ) {
        /** The app's own order, with the model's preferred trails first. Nothing is ever dropped. */
        fun orderFirst(specs: List<Spec>): List<Spec> {
            if (specs.isEmpty() || !used) return specs
            val preferred = ordered.map { it.trail.name }.distinct()
            return specs.sortedBy { spec -> preferred.indexOf(spec.trail.name).let { if (it < 0) preferred.size else it } }
        }

        /** The quiet link the model named for this trail, if it named one. */
        fun namedVia(trailName: String): String? = viaNames[StrategyValidator.normalize(trailName)]
    }

    /**
     * The ONE model call of this plan (docs/AI-PLANNING.md section 7).
     *
     * The planner builds the context itself: the trails it already ranked as reachable, the entrances
     * it is about to try, and the quiet links it is prepared to force a route through. It never sends a
     * coordinate - the model chooses among names and ids, and its reply is validated against that very
     * context before anything it asked for can reach Valhalla.
     *
     * A strategist that throws is a failure like any other: the app's own order is used and the plan
     * is the plan it would have made without any AI.
     */
    private suspend fun askStrategist(specs: List<Spec>, request: PlanRequest): AiAdvice? {
        // The rider's gate (D27): an explicit "Plan ride" tap is the ONLY thing that may spend tokens.
        // Nothing automatic - a screen refresh, a location change, a re-plan, an adopted saved ride -
        // reaches the model, and a caller that forgets the flag gets no AI rather than a surprise bill.
        if (!request.allowAi) return null
        val strategist = strategist ?: return null
        val context = strategyContext(specs, request)
        val reply = runCatching { strategist.propose(context) }
            .getOrElse { StrategyReply(emptyList(), StrategyText.UNREADABLE) }
        // The validation of section 3, run again here: a strategist that is not wrapped in
        // ValidatingStrategist must not be able to send a hallucinated name to Valhalla.
        val validated = StrategyValidator.validate(reply, context)
        val byKey = HashMap<String, Spec>(specs.size)
        specs.forEach { byKey[entryKey(it.trail.name, it.ap.osmNodeId)] = it }
        val ordered = LinkedHashSet<Spec>()
        val reasons = ArrayList<String>()
        val viaNames = HashMap<String, String>()
        validated.strategies.forEach { strategy ->
            val spec = byKey[entryKey(strategy.trail, strategy.entryId)] ?: return@forEach
            if (ordered.add(spec) && strategy.reason.isNotBlank()) reasons.add(strategy.reason.trim())
            strategy.viaRoad?.let { viaNames.putIfAbsent(StrategyValidator.normalize(strategy.trail), it) }
        }
        val appOrder = DeterministicStrategist().propose(context).strategies
        val used = validated.strategies.isNotEmpty() && validated.strategies != appOrder
        return AiAdvice(
            ordered = ordered.toList(),
            names = ordered.map { it.trail.name }.distinct(),
            viaNames = viaNames,
            used = used,
            notes = strategyNotes(validated, ordered.map { it.trail.name }.distinct(), reasons, used),
        )
    }

    /**
     * The rider-facing lines about the model. Rider language only (docs/UI.md section 1): what it
     * suggested, what was thrown out and why, or why the app did not use it.
     */
    private fun strategyNotes(
        reply: StrategyReply,
        names: List<String>,
        reasons: List<String>,
        used: Boolean,
    ): List<String> {
        val lines = ArrayList<String>()
        if (used && names.isNotEmpty()) {
            lines.add("The AI suggested trying " + names.joinToString(", ") + " first.")
            // The model's own one-line reasons, and only when the model's reply is what is being
            // acted on: with no AI in play these strings are the app describing its own order, which
            // is noise on a screen that is already showing the ride.
            lines.addAll(reasons.filter { it.isNotBlank() }.take(MAX_AI_REASONS))
        }
        lines.addAll(sentences(reply.rationale))
        return lines.filter { it.isNotBlank() }.distinct().take(MAX_AI_NOTES)
    }

    /** Splits a rationale into whole sentences, so the screen shows lines a rider can read. */
    private fun sentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val current = StringBuilder()
        text.trim().forEach { ch ->
            current.append(ch)
            if (ch == '.' || ch == '!' || ch == '?') {
                out.add(current.toString().trim())
                current.setLength(0)
            }
        }
        if (current.toString().isNotBlank()) out.add(current.toString().trim())
        return out
    }

    /**
     * What the model is given: the trails the app already considers reachable, their entrances in the
     * app's own rank order, the quiet links it may name, and the rider's rules. No coordinates.
     */
    private fun strategyContext(specs: List<Spec>, request: PlanRequest): StrategyContext {
        val byTrail = LinkedHashMap<String, MutableList<Spec>>()
        specs.forEach { spec -> byTrail.getOrPut(spec.trail.name) { ArrayList() }.add(spec) }
        val candidates = byTrail.map { (trailName, trailSpecs) ->
            val trail = trailSpecs.first().trail
            TrailOption(
                trail = trailName,
                trailMiles = Geo.metresToMiles(trail.rideableM),
                surface = trail.surface.firstOrNull { it.isNotBlank() }
                    ?: if (trail.paved) "paved" else "not audited",
                entries = trailSpecs.mapIndexed { index, spec ->
                    EntryOption(
                        id = spec.ap.osmNodeId,
                        rank = index + 1,
                        hint = spec.ap.onRoad?.let { "from " + it }.orEmpty(),
                    )
                },
                verified = trail.verified,
            )
        }
        return StrategyContext(
            targetMiles = request.targetMiles,
            candidates = candidates,
            // The planner's own definition of a legal D13 via-point: a road it has MEASURED at a low
            // or medium volume. Before any route is traced it has none, so the model is told the
            // truth - "(none)" - and the validator drops any street it invents.
            quietLinks = selectQuietLinks().map { it.label }.distinct(),
            rules = DeepSeekStrategist.DEFAULT_RULES,
        )
    }

    private fun entryKey(trail: String, entryId: Long): String =
        StrategyValidator.normalize(trail) + "|" + entryId

    /**
     * The road way-id sets of the trips already saved for THIS request, or an empty list when there is
     * no store or nothing saved. The store skips rows too small to contain the shortest new candidate,
     * so no payload is decompressed that cannot answer the question.
     */
    private suspend fun savedWayIdsFor(request: PlanRequest, produced: List<PlannedRoute>): List<Set<Long>> {
        val store = routeStore ?: return emptyList()
        val shortestNewCount = produced.map { it.roadWayIdSet().size }.filter { it > 0 }.minOrNull() ?: return emptyList()
        return runCatching { store.overlapContext(request.origin, request.targetMiles, shortestNewCount) }
            .getOrDefault(emptyList())
    }

    /** The OSM road way ids this ride uses: the identity the overlap rule is defined on. */
    private fun PlannedRoute.roadWayIdSet(): Set<Long> = segments.flatMap { it.wayIds }.toSet()

    /** The index of the nearest shipped trail vertex: a stable identity for the turnaround. */
    private fun turnaroundVertexIndex(trail: Trail, turnaround: LatLon): Int? {
        val points = trail.fullGeometry.points
        if (points.isEmpty()) return null
        var best = 0
        var bestDistance = Double.MAX_VALUE
        points.forEachIndexed { index, point ->
            val d = Geo.distanceM(point, turnaround)
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        // only trust it when the turnaround really is on the trail geometry
        return if (bestDistance <= TURNAROUND_SNAP_TOLERANCE_M) best else null
    }

    /** Rideable trail metres reachable from the entry trail, along its corridor. */
    private fun corridorReachM(route: PlannedRoute): Double {
        val entry = route.trailNames.firstOrNull() ?: return 0.0
        val corridor = network.corridorOf(entry)
        val names = corridor?.trailNames ?: listOf(entry)
        return names.sumOf { network.trail(it)?.rideableM ?: 0.0 }
    }

    /** Lower is better. Used only to pick which failing attempt to show. */
    private fun penalty(r: PlannedRoute): Double {
        val b = r.breakdown
        val eM = b.metres(Grade.E)
        val dOver = (b.metres(Grade.D) / Geo.METRES_PER_MILE) - config.dCapMiles
        return eM * 10 + maxOf(0.0, dOver) * 1000 + b.dCapPenalty() + b.ungradedM * 0.001
    }

    private fun GradeBreakdown.dCapPenalty(): Double {
        if (gradedM <= 1.0) return 0.0
        val over = (metres(Grade.D) / gradedM) - config.dCapShare
        return maxOf(0.0, over) * 10_000
    }

    private data class Spec(val trail: Trail, val ap: AccessPoint)

    /**
     * How far the rider actually has to ride to reach this access point, in metres - the key the
     * candidate list is ordered by.
     *
     * Crow-flies distance is a bad proxy for "can I start the ride here", and it was measurably
     * spending the whole candidate budget on entrances that cannot work. On the Sumner Link
     * corridor the nearest access points by crow-flies sit 2.1 km away but 6.0 km away by road,
     * and every one of them is walled off behind Jovita Blvd E and 142nd Ave E (measured round
     * trip: 3.3-5.2 km of grade D). The entrances 4.3 km away by crow-flies are 5.9 km away by
     * road and are reachable with 158 m of grade D each way - comfortably inside D10.
     *
     * `assets/access_points.json` already carries that measured number per candidate
     * (`measured_route_km_from_home`, one POST /route each at build time). It is measured from the
     * SAVED HOME, so it only describes the rider's approach while they are near it (D22 makes the
     * origin the live position). Away from home the app MEASURES the approach instead of guessing -
     * see [liveApproachM].
     *
     * @param measured approach distances measured from the live origin, keyed by access-point node
     *   id. Empty near the saved home, where the bundled table is already the right answer.
     */
    private fun approachM(ap: AccessPoint, origin: LatLon, measured: Map<Long, Double> = emptyMap()): Double {
        measured[ap.osmNodeId]?.let { return it }
        val probe = ap.probeRouteKmFromHome
        val nearHome = Geo.distanceM(origin, home) <= PROBE_ORIGIN_TOLERANCE_M
        return if (nearHome && probe != null && probe > 0.0) probe * 1000.0 else Geo.distanceM(ap.point, origin)
    }

    /**
     * Measures the real approach to the entrances the rider might actually use, from the LIVE origin
     * (D22), when the bundled probe table cannot answer for them.
     *
     * Why this exists: with the origin away from the saved home, [approachM] falls back to crow-flies,
     * and D24 measured exactly how bad that proxy is - on the Sumner Link corridor the nearest
     * entrances by crow-flies are all walled off behind Jovita Blvd E and 142nd Ave E, while the ones
     * the rider can reach are further away in a straight line. Ordering by crow-flies there spends the
     * whole candidate budget on entrances that cannot work.
     *
     * Cost control: only the [APPROACH_PROBE_PER_TRAIL] closest entrances per trail are measured, one
     * POST /route each, and those calls go through the same cached [RouteService] as everything else,
     * so a repeated plan from the same place pays nothing. The shortlist is chosen by crow-flies,
     * which is cheap and cannot miss an entrance that is genuinely close.
     */
    private suspend fun liveApproachM(shortlist: List<AccessPoint>, origin: LatLon): Map<Long, Double> {
        val measured = HashMap<Long, Double>(shortlist.size)
        for (ap in shortlist) {
            val metres = runCatching {
                client.route(from = origin, to = ap.point, via = emptyList(), allowFallback = false).lengthM
            }.getOrNull()
            if (metres != null && metres > 0.0) measured[ap.osmNodeId] = metres
        }
        return measured
    }

    /**
     * Nearest accessible trails first; at most [perTrail] access points each.
     *
     * [skip] holds the signatures already tried. A second call then walks FURTHER down the same
     * list instead of re-offering the access points the first call already rejected, which is what
     * "try the alternate access points" has to mean to add anything.
     */
    private suspend fun candidateSpecs(request: PlanRequest, perTrail: Int, skip: Set<String> = emptySet()): List<Spec> {
        val byTrail = accessPoints.groupBy { it.trailName }
        val pool = if (request.preferTrails.isEmpty()) {
            network.trails
        } else {
            // D13 option (ii): the rider asked for specific trail(s); rank only those.
            network.trails.filter { it.name in request.preferTrails }.ifEmpty { network.trails }
        }
        val inRange = pool.filter { trail ->
            byTrail[trail.name].orEmpty()
                .any { Geo.distanceM(it.point, request.origin) <= config.trailRadiusKm * 1000 }
        }
        // Near the saved home the bundled probe table already answers "how far is this entrance"
        // for the entrances it carries. It carries none for a REGIONAL or discovered trail, so those
        // are measured live instead of being ranked by crow-flies - the proxy D24 disproved. Away
        // from the saved home nothing describes the approach, so everything measured is measured.
        val nearHome = Geo.distanceM(request.origin, home) <= PROBE_ORIGIN_TOLERANCE_M
        val shortlist = inRange.flatMap { trail ->
            byTrail[trail.name].orEmpty()
                .sortedBy { Geo.distanceM(it.point, request.origin) }
                .take(APPROACH_PROBE_PER_TRAIL)
        }.filterNot { nearHome && it.probeRouteKmFromHome != null }
        val measured: Map<Long, Double> = liveApproachM(shortlist, request.origin)
        // Measured approach first, then crow-flies (which still separates entrances the probe
        // table cannot distinguish), then the node id, so the order is always deterministic.
        fun ranked(trailName: String): List<AccessPoint> = byTrail[trailName].orEmpty()
            .sortedWith(
                compareBy(
                    { approachM(it, request.origin, measured) },
                    { Geo.distanceM(it.point, request.origin) },
                    { it.osmNodeId },
                ),
            )
        val orderedTrails = inRange
            .sortedBy { trail ->
                ranked(trail.name).firstOrNull()?.let { approachM(it, request.origin, measured) }
                    ?: Double.MAX_VALUE
            }
        // One block of ranked entrances per trail, best entrance first, already-tried ones removed.
        val blocks = orderedTrails.map { trail ->
            ranked(trail.name)
                .filterNot { signatureOf(Spec(trail, it), emptyList()) in skip }
                .take(perTrail)
        }
        // Then the candidate budget is spent ROUND ROBIN across the trails, so it goes on other
        // TRAILS before it goes on more entrances of the same one. That is the same principle D29
        // applies to the options the rider sees, applied one step earlier: without it, a single long
        // trail with several entrances near the rider fills every slot, and the rider is never
        // offered the second trail at all. The ranking is untouched - the best trail's best entrance
        // is still tried first, and the first entrance of every reachable trail comes before the
        // second entrance of any of them.
        val specs = ArrayList<Spec>(request.maxCandidates)
        var round = 0
        while (specs.size < request.maxCandidates) {
            var added = false
            blocks.forEachIndexed { index, block ->
                if (specs.size >= request.maxCandidates) return@forEachIndexed
                block.getOrNull(round)?.let {
                    specs.add(Spec(orderedTrails[index], it))
                    added = true
                }
            }
            if (!added) break
            round++
        }
        return specs
    }

    private fun signatureOf(spec: Spec, via: List<QuietWay>): String =
        spec.trail.name + "@" + spec.ap.osmNodeId + (if (via.isEmpty()) "" else "#via" + via.joinToString("+") { it.wayId.toString() })

    /**
     * The via-points for this run: D13 rule 1 (measured low, then medium) plus D40 (small streets by
     * road class, which is how the small residential streets the rider asked for can finally be
     * tried). See [QuietLinks] for the rules and the ordering.
     */
    private fun selectQuietLinks(): List<QuietWay> =
        QuietLinks.select(observedWays.values, config.maxViaCandidates)

    private suspend fun evaluateInto(
        spec: Spec,
        via: List<QuietWay>,
        request: PlanRequest,
        warnings: MutableList<String>,
        attempts: MutableList<CandidateAttempt>,
        produced: MutableList<PlannedRoute>,
        pinTrailFloor: Boolean = false,
    ) {
        val label = spec.trail.name + " @ " + Signature.round(spec.ap.point.lat, spec.ap.point.lon) +
            (if (via.isEmpty()) "" else " via " + via.joinToString(", ") { it.viaLabel }) +
            (if (pinTrailFloor) " (longer total, trail floor kept)" else "")
        try {
            val route = evaluate(spec, request, warnings, via, pinTrailFloor)
            if (route == null) {
                attempts.add(CandidateAttempt(label, null, "candidate produced no usable route"))
            } else {
                produced.add(route)
                attempts.add(CandidateAttempt(label, route, null))
            }
        } catch (e: ValhallaException) {
            attempts.add(CandidateAttempt(label, null, "no route from Valhalla: " + e.message))
        } catch (e: Exception) {
            attempts.add(CandidateAttempt(label, null, "candidate failed: " + e.message))
        }
    }

    /**
     * Walk one trail from the access point, routing every closed segment on roads and grading
     * those roads. [preferDirection] null means "ride the longer side first".
     */
    private suspend fun walkOutbound(
        spec: Spec,
        budgetOneWayM: Double,
        preferDirection: Int?,
        request: PlanRequest,
        safetyNotes: MutableList<String>,
    ): Outbound {
        val walker = TrailWalker(network)
        val localNotes = ArrayList<String>()
        val detours = ArrayList<DetourInfo>()
        val pieces = ArrayList<List<LatLon>>()
        var walk = walker.walk(spec.trail, spec.ap.point, budgetOneWayM, preferDirection)
        var trailMetres = walk.trailMetres
        var detourMetres = 0.0
        walk.legs.forEach { pieces.add(it.points) }
        localNotes.addAll(walk.notes)
        var gapsRouted = 0
        while (walk.openGap != null && gapsRouted < MAX_DETOURS) {
            val gap = walk.openGap
            if (budgetOneWayM - (trailMetres + detourMetres) <= 1.0) break
            val resumedTrail = network.trail(gap.resumeTrail) ?: break
            val detourRoute = client.route(gap.from, gap.to, allowFallback = request.allowFallback)
            val detourTrace = client.trace(detourRoute.shape, detourRoute.baseUrl)
            val detourSegments = gradeEdges(detourTrace, detourRoute)
            if (!detourTrace.ok) {
                safetyNotes.add(
                    "trace_attributes could not match the road detour around " + gap.closure.trailName +
                        " way " + gap.closure.osmWayId + ", so those metres are shown as ungraded.",
                )
            }
            detours.add(DetourInfo(gap.closure, gap.from, gap.to, detourRoute.lengthM, detourSegments))
            detourMetres += detourRoute.lengthM
            pieces.add(detourRoute.points)
            localNotes.add(
                "routed around the closed segment on roads: " + fmt(detourRoute.lengthM / 1000, 2) +
                    " km via " + detourSegments.map { it.displayName }.distinct().joinToString(", ") + ".",
            )
            gapsRouted++
            val remaining = budgetOneWayM - (trailMetres + detourMetres)
            if (remaining <= 1.0) {
                walk = walk.copy(openGap = null, exhausted = true)
                break
            }
            walk = walker.walk(resumedTrail, gap.resumeAt, remaining)
            trailMetres += walk.trailMetres
            walk.legs.forEach { pieces.add(it.points) }
            localNotes.addAll(walk.notes)
        }
        return Outbound(walk, trailMetres, detourMetres, detours, pieces, localNotes)
    }

    private suspend fun evaluate(
        spec: Spec,
        request: PlanRequest,
        warnings: MutableList<String>,
        via: List<QuietWay> = emptyList(),
        pinTrailFloor: Boolean = false,
    ): PlannedRoute? {
        val commute = client.route(request.origin, spec.ap.point, via = via.map { it.point }, allowFallback = request.allowFallback)
        val targetPortion = MileageMath.trailPortion(request.targetMiles, commute.lengthM, config.minTrailMiles)
        // D13 rule 3: when the detour eats the trail portion, the ride may be planned LONGER so
        // that at least the floor of trail remains. The total then exceeds the target, which the
        // app says out loud before confirmation.
        val floorOneWayM = DetourPolicy.trailFloorM() / 2
        val portion = when {
            targetPortion.feasible && (!pinTrailFloor || targetPortion.oneWayM >= floorOneWayM) -> targetPortion
            pinTrailFloor -> MileageMath.TrailPortion(
                roundTripM = 2 * floorOneWayM,
                oneWayM = floorOneWayM,
                feasible = true,
                reason = null,
            )
            else -> return null
        }

        val safetyNotes = ArrayList<String>()
        val notes = ArrayList<String>()
        if (commute.unverified) safetyNotes.add(commute.sanityProblem ?: "the route failed the plausibility check")
        if (pinTrailFloor) {
            notes.add(
                "Longer total (D13 rule 3): the detour eats into the trail, so the ride is planned " +
                    "longer than " + fmt(request.targetMiles, 2) + " mi to keep " +
                    fmt(config.trailFloorMiles, 1) + " mi of trail.",
            )
        }
        if (via.isNotEmpty()) {
            notes.add(
                "Detour (D13): the commute was forced through " + via.joinToString(", ") {
                    it.label + (it.aadt?.let { a -> " ($a vpd" + (it.aadtYear?.let { y -> ", $y" } ?: "") + ")" } ?: "")
                } + " instead of the direct line.",
            )
        }

        // 1. Walk the trail, routing around any closed segment on roads (SPEC section 7b).
        //    Both starting directions are tried: the longer side can dead-end at a trail's far
        //    end, while the other side reaches the closure and its road detour.
        val walks = ArrayList<Outbound>()
        for (direction in listOf(null, -1, 1)) {
            val attempt = walkOutbound(spec, portion.oneWayM, direction, request, safetyNotes)
            if (attempt.trailMetres > 1.0) walks.add(attempt)
            if (attempt.detours.isNotEmpty()) break
        }
        val outbound = walks.maxByOrNull { it.trailMetres + it.detourMetres } ?: return null
        val walk = outbound.walk
        val trailMetres = outbound.trailMetres
        val detourMetres = outbound.detourMetres
        val detours = outbound.detours
        val outboundPieces = outbound.pieces
        notes.addAll(outbound.notes)
        val trailPortionOneWayM = trailMetres + detourMetres
        if (trailPortionOneWayM <= 1.0) return null

        // 2. Grade the road legs. The trail legs are grade A by construction.
        val trace = client.trace(commute.shape, commute.baseUrl)
        val roadSegments = gradeEdges(trace, commute)
        waysPerCandidate[signatureOf(spec, via)] = roadSegments.flatMap { it.wayIds }.toSet()
        if (!trace.ok) {
            safetyNotes.add(
                "trace_attributes could not match the route shape with edge_walk or map_snap (" +
                    (trace.message ?: "no detail") + "). The road legs are NOT graded and are shown as ungraded.",
            )
        }
        if (trace.mode.name == "MAP_SNAP") {
            safetyNotes.add("trace_attributes matched the shape with map_snap, not edge_walk: edges may be approximate.")
        }
        val trailSegments = walk.legs.map { grader.gradeTrailLeg(it) }
        val detourSegmentsAll = detours.flatMap { it.segments }
        // The commute and the detour are ridden out and back, so they count twice.
        val allSegments = roadSegments + roadSegments + detourSegmentsAll + detourSegmentsAll + trailSegments
        val breakdown = grader.breakdown(allSegments)
        val named = grader.namedSegments(allSegments)
        val verdict = grader.evaluateD10(breakdown, config.dCapShare, config.dCapMiles, config.unverifiedUngradedShare)

        val capped = walk.exhausted
        if (capped) {
            notes.add(
                "Capped (D9): the ride is " + miles2(2 * commute.lengthM + 2 * trailPortionOneWayM) +
                    ", not the " + fmt(request.targetMiles, 2) + " mi target, because there is no more rideable trail.",
            )
        }
        if (trailPortionOneWayM * 2 < portion.roundTripM * 0.97) {
            notes.add(
                "Trail portion is " + miles2(2 * trailPortionOneWayM) + " of the " +
                    fmt(portion.roundTripMiles, 2) + " mi you asked for.",
            )
        }
        if (detours.isNotEmpty()) {
            notes.add(
                "Trail mileage is measured on the rideable trail only: " + miles2(2 * trailMetres) +
                    " of trail plus " + miles2(2 * detourMetres) + " of road detour.",
            )
        }

        val roundTrip = buildRoundTrip(commute.points, outboundPieces)
        return PlannedRoute(
            signature = Signature.of(
                trailNames = walk.trailNames,
                origin = request.origin,
                accessPoint = spec.ap,
                turnaround = walk.turnaround,
                turnaroundVertex = turnaroundVertexIndex(spec.trail, walk.turnaround),
            ),
            trailNames = walk.trailNames,
            accessPoint = spec.ap,
            turnaround = walk.turnaround,
            commute = commute,
            trailLegs = walk.legs,
            trailOneWayM = trailMetres,
            trailRoundTripM = 2 * trailPortionOneWayM,
            targetMiles = request.targetMiles,
            capped = capped,
            capReason = if (capped) notes.lastOrNull() else null,
            roundTrip = roundTrip,
            segments = allSegments,
            roadSegments = roadSegments,
            breakdown = breakdown,
            verdict = verdict,
            named = named,
            cautions = named.filter { it.grade == Grade.D },
            ungraded = named.filter { it.grade == Grade.UNGRADED },
            rejections = named.filter { it.grade == Grade.E || it.grade == Grade.REJECTED },
            detours = detours,
            detourOneWayM = detourMetres,
            safetyNotes = safetyNotes,
            notes = notes,
            via = via.map { ViaRef(it.point, it.viaLabel, it.band, it.aadt, it.aadtYear, it.lts) },
        )
    }

    /**
     * Trace edges in route order, giving each edge its midpoint on the route shape so the
     * traffic table can join by geometry, and remembering the quiet ways for D13 via-points.
     */
    private fun gradeEdges(trace: TraceResult, route: RouteResult): List<GradedSegment> {
        if (!trace.ok) {
            return listOf(
                GradedSegment(
                    grade = Grade.UNGRADED,
                    lengthM = route.lengthM,
                    names = listOf("(road leg - trace_attributes failed)"),
                    wayIds = emptyList(),
                    roadClass = null,
                    use = null,
                    speedMph = null,
                    speedSource = SpeedSource.NONE,
                    reason = "trace_attributes failed; this leg cannot be graded",
                ),
            )
        }
        if (route.points.size < 2) return trace.edges.map { grader.gradeEdge(it) }
        val poly = Polyline(route.points)
        val edgesTotal = trace.edges.sumOf { it.lengthM }
        val scale = if (edgesTotal > 0) poly.lengthM / edgesTotal else 1.0
        var cursor = 0.0
        return trace.edges.map { e ->
            val mid = ((cursor + e.lengthM / 2) * scale).coerceIn(0.0, poly.lengthM)
            cursor += e.lengthM
            val at = poly.pointAt(mid)
            val segment = grader.gradeEdge(e, at)
            // D40: a street is observed when it has a MEASURED count (D13 rule 1, unchanged) or when
            // it is a small road by class whose own default speed is under the D10 threshold. The
            // second half is what was missing: near home the counted roads are the big ones, so the
            // small streets could never enter the pool and the generator never tried them.
            val match = segment.traffic
            if (e.wayId > 0L && (match != null || QuietLinks.isSmallRoad(segment.roadClass, segment.use))) {
                val seen = QuietWay(
                    wayId = e.wayId,
                    point = at,
                    label = segment.displayName,
                    band = match?.band ?: VolumeBand.UNKNOWN,
                    aadt = match?.count?.aadt,
                    aadtYear = match?.count?.aadtYear,
                    lts = match?.count?.lts,
                    speedMph = segment.speedMph,
                    speedSource = segment.speedSource,
                    roadClass = segment.roadClass,
                    use = segment.use,
                )
                // Keep the BEST observation of a way, not the first: D40 admits a small street on its
                // class, and a later trace of the same street may carry a real count. Holding the
                // first (count-less) sighting would keep the weaker evidence and print the weaker
                // reason next to a better one that the app had already seen.
                observedWays[e.wayId] = QuietLinks.best(observedWays[e.wayId], seen)
            }
            segment
        }
    }

    /**
     * The drawn round trip: out along the commute and the trail, then the SAME way back.
     *
     * `outbound` is origin -> access point -> the trail pieces. `outbound.reversed()` is the trail
     * pieces backwards followed by the commute backwards, which already finishes at the origin - so
     * the return leg is exactly one reversal and the loop is closed once. (Appending `commuteOut`
     * again would add a second access-point-to-origin leg preceded by a straight jump from the origin
     * back out to the access point: the drawn shape was 2x the commute longer than `totalM`.)
     */
    private fun buildRoundTrip(commuteOut: List<LatLon>, outboundPieces: List<List<LatLon>>): List<LatLon> {
        val full = ArrayList<LatLon>()
        fun add(p: LatLon) {
            if (full.isEmpty() || Geo.distanceM(full.last(), p) > 0.5) full.add(p)
        }
        val outbound = ArrayList<LatLon>(commuteOut.size + outboundPieces.sumOf { it.size })
        commuteOut.forEach { outbound.add(it) }
        outboundPieces.forEach { piece -> piece.forEach { outbound.add(it) } }
        outbound.forEach { add(it) }
        outbound.reversed().forEach { add(it) }
        return full
    }

    /**
     * D31 (`docs/LOOPS.md`): look for a way back that is not the way out, and take it only when it
     * still passes the rider's own safety rule, the rider's own distance budget, and D13's ceiling.
     *
     * WHY THE RETURN IS ROUTED SEPARATELY. The return used to be the outbound shape reversed, so the
     * rider rode the same roads back (docs/LOOPS.md measured 92% of the outbound ways coming back).
     * Valhalla's exclusion tools cannot fix that here: `exclude_polygons` caps at 100 vertices and
     * 10,000 m of circumference, and the outbound corridor is 198 points over 5.9 km, so both caps
     * are hit (HTTP 400, error_code 176/167). Forcing the return through a via point placed to the
     * SIDE of the corridor does work: measured on the real network, a 1336 m offset drops the re-used
     * share to 8% for about +1 km. Three limits from the rider, kept: it is a PREFERENCE and never a
     * promise ("if the roads allows"), the budget is 5 km ("under 5km is acceptable"), and a loop is
     * NEVER bought with safety - a compliant out-and-back beats a non-compliant loop.
     *
     * COST. Every candidate is two HTTP calls (`/route` then `trace_attributes`), so the fan is
     * bounded by `loop_max_candidates` + `loop_max_quiet_vias`, the loop stops the moment one
     * candidate is good enough, and this whole pass runs once per plan, on the one ride that would be
     * delivered - never per candidate.
     */
    private suspend fun loopPass(
        chosen: PlannedRoute,
        request: PlanRequest,
        detourReferenceM: Double,
        warnings: MutableList<String>,
    ): PlannedRoute {
        val origin = request.origin
        val entrance = chosen.accessPoint.point
        val mirroredReturnM = chosen.commute.lengthM
        val maxExtraM = config.loopMaxExtraKm * 1000.0
        val outboundWayIds = chosen.roadSegments.flatMap { it.wayIds }.filter { it > 0L }.toSet()
        if (outboundWayIds.isEmpty()) {
            // Nothing was traced, so there is nothing to compare a return against and no honest way to
            // claim a loop. Recorded rather than silently skipped: this is a search that did not run.
            return chosen.copy(
                loopSearched = true,
                notes = chosen.notes + "D31: no loop was searched - the outbound road legs carry no OSM way ids to compare a return against.",
            )
        }

        val trailSegments = chosen.trailLegs.map { grader.gradeTrailLeg(it) }
        val detourSegments = chosen.detours.flatMap { it.segments }

        // The candidates: the DIRECT return first (routing the return on its own is sometimes already
        // a different way back, at no extra distance - docs/LOOPS.md measured 58% that way), then the
        // perpendicular fan, then the small streets this plan already observed (D13/D40), which are
        // the quiet connectors the rider asked for and cost nothing to name.
        val proposals = ArrayList<LoopPolicy.ViaCandidate?>()
        proposals.add(null)
        proposals.addAll(
            LoopPolicy.viaCandidates(
                chosen.commute.points,
                config.loopViaOffsetsM,
                limit = config.loopMaxCandidates,
            ),
        )
        proposals.addAll(
            QuietLinks.select(
                observedWays.values.filter { it.wayId !in outboundWayIds },
                config.loopMaxQuietVias,
            ).map { LoopPolicy.ViaCandidate(it.point, it.viaLabel, 0.0, LoopPolicy.SIDE_LEFT, 0.0) },
        )

        val attempts = ArrayList<LoopAttempt>()
        var stoppedEarly = false
        for (proposal in proposals) {
            val leg = runCatching {
                client.route(entrance, origin, via = proposal?.let { listOf(it.point) } ?: emptyList())
            }.getOrNull() ?: continue
            val trace = runCatching { client.trace(leg.shape, leg.baseUrl) }.getOrNull() ?: continue
            val returnSegments = gradeEdges(trace, leg)
            val returnWayIds = returnSegments.flatMap { it.wayIds }.filter { it > 0L }.toSet()
            // D50: how quiet this particular way back is. The outbound is fixed, so this is the only
            // quiet number that differs between return candidates.
            val returnQuiet = QuietRoute.score(returnSegments)
            // Identical composition to the delivered ride: outbound roads, the real return roads, the
            // closure detours (ridden both ways) and the trail legs. Nothing is graded twice.
            val all = chosen.roadSegments + returnSegments + detourSegments + detourSegments + trailSegments
            val breakdown = grader.breakdown(all)
            val verdict = grader.evaluateD10(breakdown, config.dCapShare, config.dCapMiles, config.unverifiedUngradedShare)
            val totalM = chosen.commute.lengthM + leg.lengthM + chosen.trailRoundTripM
            val factor = if (detourReferenceM > 0.0) totalM / detourReferenceM else 1.0
            val overlap = LoopPolicy.overlapShare(outboundWayIds, returnWayIds)
            val notes = ArrayList<String>()
            if (!trace.ok) {
                notes.add("trace_attributes could not match the return leg with edge_walk or map_snap (" + (trace.message ?: "no detail") + "), so that leg is shown as ungraded.")
            }
            if (trace.mode.name == "MAP_SNAP") {
                notes.add("the return leg was matched with map_snap, not edge_walk: its edges may be approximate.")
            }
            val via = proposal ?: LoopPolicy.ViaCandidate(
                point = leg.points[leg.points.size / 2],
                label = "the direct way back",
                offsetM = 0.0,
                side = LoopPolicy.SIDE_LEFT,
                fraction = 0.0,
            )
            if (System.getenv("PLANNER_DEBUG") != null) {
                println(
                    "DBG loop attempt via=" + via.label + " side=" + via.side +
                        " overlap=" + "%.3f".format(overlap) +
                        " extraKm=" + "%.2f".format((leg.lengthM - mirroredReturnM) / 1000.0) +
                        " factor=" + "%.2f".format(factor) +
                        " compliant=" + (verdict.hasNoKnownViolation && factor <= config.detourMaxFactor + 1e-9) +
                        " outcome=" + verdict.outcome +
                        " ways=" + returnWayIds.size + " shared=" + outboundWayIds.count { it in returnWayIds } +
                        " quiet=" + returnQuiet.report(),
                )
            }
            attempts.add(
                LoopAttempt(
                    choice = LoopPolicy.Candidate(
                        via = via,
                        returnM = leg.lengthM,
                        returnWayIds = returnWayIds,
                        overlapShare = overlap,
                        extraM = leg.lengthM - mirroredReturnM,
                        // D31 rule: a loop is never bought with safety, and never with mileage either -
                        // D13's ceiling still bounds the whole trip, exactly as it bounds a detour.
                        compliant = verdict.hasNoKnownViolation && factor <= config.detourMaxFactor + 1e-9,
                        // D50 decision C: among compliant returns the quietest way back wins.
                        quiet = returnQuiet,
                    ),
                    leg = leg,
                    returnSegments = returnSegments,
                    all = all,
                    breakdown = breakdown,
                    verdict = verdict,
                    totalM = totalM,
                    factor = factor,
                    safetyNotes = notes,
                ),
            )
            // D50: stop early only for a return that is both clearly different AND quiet enough, so a
            // noisy low-overlap return cannot cut the search short before a quieter one is tried.
            if (attempts.last().choice.compliant &&
                overlap <= LoopPolicy.GOOD_ENOUGH_OVERLAP &&
                returnQuiet.goodShare >= config.quietShareTarget &&
                returnQuiet.majorNoLaneM <= config.quietBusyFloorM
            ) {
                stoppedEarly = true
                break
            }
        }

        // The mirrored return IS the outbound, so the baseline it has to beat is a full overlap: any
        // return that re-uses every outbound road is the same way back by the rider's own definition.
        val winner = LoopPolicy.choose(
            candidates = attempts.map { it.choice },
            baselineOverlap = 1.0,
            maxExtraM = maxExtraM,
        )
        val win = winner?.let { w -> attempts.firstOrNull { it.choice === w } }
        if (win == null) {
            return chosen.copy(
                loopSearched = true,
                notes = chosen.notes + ("D31: no different way back was found. " + attempts.size +
                    " return legs were routed and traced; none both kept D10 clean and stayed within " +
                    fmt(config.loopMaxExtraKm, 1) + " km of coming back the same way."),
            )
        }

        // Rebuild the drawn shape: everything up to the point the rider is back at the entrance (the
        // trail ridden back), then the REAL return leg instead of the mirrored commute. Indices come
        // from the shape the plan already built, so no new field has to be carried on PlannedRoute.
        val turnaroundIdx = indexOfNearest(chosen.roundTrip, chosen.turnaround)
        val entranceIdx = if (turnaroundIdx < 0) -1 else indexOfNearest(chosen.roundTrip, entrance, turnaroundIdx)
        if (turnaroundIdx < 0 || entranceIdx < 0) {
            return chosen.copy(
                loopSearched = true,
                notes = chosen.notes + "D31: a different way back was found but the drawn round trip could not be re-cut around it, so the ride is unchanged.",
            )
        }
        val shape = stitch(chosen.roundTrip.subList(0, entranceIdx + 1), win.leg.points)
        val named = grader.namedSegments(win.all)
        val share = fmt(win.choice.overlapShare * 100, 0)
        val extraKm = win.choice.extraM / 1000.0
        return chosen.copy(
            roundTrip = shape,
            returnCommute = win.leg,
            returnSegments = win.returnSegments,
            loop = LoopInfo(
                viaPoint = win.choice.via.point,
                label = win.choice.via.label,
                overlapShare = win.choice.overlapShare,
                extraM = win.choice.extraM,
            ),
            loopSearched = true,
            segments = win.all,
            breakdown = win.breakdown,
            verdict = win.verdict,
            named = named,
            cautions = named.filter { it.grade == Grade.D },
            ungraded = named.filter { it.grade == Grade.UNGRADED },
            rejections = named.filter { it.grade == Grade.E || it.grade == Grade.REJECTED },
            distanceFactor = win.factor,
            explicitDetour = win.factor > config.detourAcceptFactor + 1e-9,
            safetyNotes = chosen.safetyNotes + win.safetyNotes,
            notes = chosen.notes + ("D31 loop: the return is routed separately through " + win.choice.via.label +
                ", " + share + "% of the outbound roads come back, " +
                (if (extraKm >= 0) "+" else "") + fmt(extraKm, 2) + " km, " + attempts.size +
                " return legs tried" + (if (stoppedEarly) " (stopped early)" else "") + "."),
        )
    }

    /** [head] then [tail], dropping points that repeat within 0.5 m (the [buildRoundTrip] rule). */
    private fun stitch(head: List<LatLon>, tail: List<LatLon>): List<LatLon> {
        val full = ArrayList<LatLon>(head.size + tail.size)
        fun add(p: LatLon) {
            if (full.isEmpty() || Geo.distanceM(full.last(), p) > 0.5) full.add(p)
        }
        head.forEach { add(it) }
        tail.forEach { add(it) }
        return full
    }

    /** Index of the point nearest [target] at or after [from], or -1 when the list is empty. */
    private fun indexOfNearest(points: List<LatLon>, target: LatLon, from: Int = 0): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in maxOf(0, from) until points.size) {
            val d = Geo.distanceM(points[i], target)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private companion object {
        const val MAX_DETOURS = 3
        const val TURNAROUND_SNAP_TOLERANCE_M = 120.0

        /**
         * D6: how many rides the result screen offers. Raised from three to five by the rider on
         * 2026-09-16 ("increase the recent trips on UI to 5", which also covers the saved-trip
         * suggestions). Deliberately NOT `PlanPolicy.DEFAULT_MAX_SUGGESTIONS`: that constant is about
         * how many ALREADY-SAVED trips the plan screen offers, this one is about how many rides a new
         * plan produces. They are the same number today and are different ideas.
         */
        const val MAX_OPTIONS = 5

        /** At most this many of the model's own one-line reasons are shown, and this many lines total. */
        const val MAX_AI_REASONS = 3
        const val MAX_AI_NOTES = 4

        /**
         * How close to the saved home the live origin must be before the bundled per-access-point
         * approach distances (measured from home) still describe the rider's approach.
         */
        const val PROBE_ORIGIN_TOLERANCE_M = 2_000.0

        /**
         * How many entrances per trail get a real approach measurement when the rider is away from
         * the saved home. Eight is enough to cover the entrances a rider would actually use, and it
         * bounds the extra routing at a few calls per trail (all of them cached).
         */
        const val APPROACH_PROBE_PER_TRAIL = 8
    }
}
