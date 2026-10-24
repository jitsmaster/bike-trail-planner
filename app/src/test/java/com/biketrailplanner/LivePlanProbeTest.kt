package com.biketrailplanner

import com.biketrailplanner.data.PlannerDto
import com.biketrailplanner.data.ValhallaClient
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PlanOutcome
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.SafetyGrader
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * LIVE PROBE - not a pass/fail test. Runs the real [Planner] against the real bundled assets and
 * the self-hosted Valhalla server (127.0.0.1:8002), from the saved home origin, and writes what the
 * shipped configuration actually finds to a report file.
 *
 * Purpose: the app reports that only one safe ride exists from home. This probe is the ground truth
 * for what the candidate generator sees. Skipped automatically when the server is not up.
 *
 * Usage:
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *     --tests 'com.biketrailplanner.LivePlanProbeTest' -Dprobe.out=build/live-plan-probe.txt
 * Optional: -Dprobe.perTrail=6 -Dprobe.maxCandidates=12 -Dprobe.miles=10
 */
class LivePlanProbeTest {

    private val base = System.getProperty("probe.base", "http://127.0.0.1:8002")
    private val out = File(System.getProperty("probe.out", "build/live-plan-probe.txt"))
    private val perTrail = System.getProperty("probe.perTrail")?.toInt()
    private val maxCandidates = System.getProperty("probe.maxCandidates")?.toInt()
    private val miles = System.getProperty("probe.miles")?.toDouble() ?: 10.0

    private val client by lazy { ValhallaClient(primaryBase = base, publicBase = base) }

    /**
     * D6: the point of the whole app - ride again tomorrow and get a DIFFERENT trip. Runs the plan
     * three times, recording each ride, and reports whether the chosen ride changed each time.
     */
    @Test
    fun probeRotationFromHome() {
        val loaded = Assets.repository()
        Assume.assumeTrue(runBlocking { client.primaryReachable() })
        val home = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        val history = com.biketrailplanner.domain.InMemoryRideHistory()
        val out = StringBuilder()
        out.appendLine("LIVE ROTATION PROBE (D6: never the same trip twice in a row)")
        val seen = ArrayList<String>()
        repeat(3) { ride ->
            val planner = Planner(
                network = loaded.network,
                accessPoints = loaded.accessPoints,
                grader = SafetyGrader(loaded.limits, loaded.traffic),
                client = client,
                config = loaded.config.planner,
                home = home,
                history = history,
            )
            val r = runBlocking { planner.plan(PlanRequest(targetMiles = miles, origin = home)) }
            val c = r.chosen
            out.appendLine(
                "ride ${ride + 1}: outcome=${r.outcome} chosen=${c?.trailLabel ?: "NONE"} " +
                    "@ node ${c?.accessPoint?.osmNodeId} ${c?.let { "%.2f mi, trail %.2f mi".format(it.totalMiles, it.trailMiles) } ?: ""} " +
                    "options=${r.options.map { it.trailLabel + "@" + it.accessPoint.osmNodeId }} repeatOfLast=${r.repeatOfLast}",
            )
            if (c != null) {
                seen.add(c.signature)
                runBlocking { history.record(c.signature, c.trailNames, c.totalMiles, System.currentTimeMillis() + ride * 1000L) }
            }
        }
        val distinct = seen.distinct().size
        out.appendLine("distinct rides offered over 3 days: $distinct")
        // D6 is "never the same ride twice in a row", not "always three of them": what matters is that
        // consecutive days differ, and that the options are different RIDES rather than the same ride
        // entered at neighbouring entrances.
        val consecutiveRepeat = seen.zipWithNext().any { (a, b) -> a == b }
        out.appendLine(
            if (!consecutiveRepeat) {
                "ROTATION OK: no ride repeated on consecutive days ($distinct distinct ride(s) available)"
            } else {
                "ROTATION PROBLEM: the same ride came up twice in a row"
            },
        )
        File(System.getProperty("probe.rotationOut", "build/live-rotation-probe.txt")).writeText(out.toString())
        println(out)
    }

    /**
     * D40 + D42 on the REAL network, in the one state where both of them act.
     *
     * From home the app finds compliant rides for every target it was asked for, so D13 phase 2 (the
     * via-point search) never runs and D40's wider pool changes nothing. This probe forces the state
     * by tightening the D10 caution cap to 0.05 mi, so that NOTHING can be compliant: phase 2 must
     * run, and the app has to decide what to offer.
     *
     * What it proves, on real OpenStreetMap data and the real Valhalla server:
     *  - D40: which streets become via-points when only their ROAD CLASS says they are quiet (before
     *    D40 a way needed a measured AADT, and near home the counted roads are the bigger ones);
     *  - D42: that the app still hands the rider a route in that state - outcome UNSAFE, a chosen
     *    ride, and the reasons - instead of refusing.
     */
    @Test
    fun probeQuietLinksUnderATightCap() {
        val loaded = Assets.repository()
        Assume.assumeTrue("Valhalla is not up; skipping the quiet-link probe", runBlocking { client.primaryReachable() })
        val shipped = loaded.config.planner
        val capMiles = System.getProperty("probe.dCapMiles")?.toDouble() ?: 0.05
        val config = PlannerDto(
            accessPointsPerTrail = 2,
            maxCandidates = 4,
            detourAccessPointsPerTrail = shipped.detourAccessPointsPerTrail,
            // A cap this tight is unachievable on this network: the point is to reach the state where
            // nothing is compliant, which is the only state where D13 phase 2 runs.
            dCapMiles = capMiles,
        )
        val originArg = System.getProperty("probe.origin")?.split(",")?.map { it.trim().toDouble() }
        val home = if (originArg != null && originArg.size == 2) {
            LatLon(originArg[0], originArg[1])
        } else {
            LatLon(loaded.config.home.lat, loaded.config.home.lon)
        }
        val out = StringBuilder()
        val result = runBlocking {
            Planner(
                network = loaded.network,
                accessPoints = loaded.accessPoints,
                grader = SafetyGrader(loaded.limits, loaded.traffic),
                client = client,
                config = config,
                home = home,
            ).plan(PlanRequest(targetMiles = 10.0, origin = home, maxCandidates = 4, accessPointsPerTrail = 2))
        }
        out.appendLine("D40 / D42 QUIET-LINK PROBE (D10 caution cap forced to 0.05 mi so phase 2 must run)")
        out.appendLine("base=$base origin=${home.lat},${home.lon} targetMiles=10.0")
        out.appendLine("outcome=${result.outcome} safeRouteFound=${result.safeRouteFound}")
        out.appendLine("chosen=${result.chosen?.trailLabel} options=${result.options.size}")
        out.appendLine("advisory=${result.refusal}")
        val viaAttempts = result.attempts.filter { it.label.contains(" via ") }
        out.appendLine("via attempts: ${viaAttempts.size} of ${result.attempts.size} attempts")
        viaAttempts.take(24).forEach { out.appendLine("  " + it.label + (it.failure?.let { f -> " -> " + f } ?: "")) }
        val byClass = viaAttempts.map { it.label }.filter { it.contains("no posted limit") || it.contains("no count") }
        out.appendLine("via-points chosen by ROAD CLASS rather than by a count: ${byClass.size}")
        byClass.forEach { out.appendLine("  " + it) }
        out.appendLine("disqualifying streets on the offered ride: " + (result.disqualifying.take(6).joinToString("; ") { it.name }))
        File(System.getProperty("probe.quietOut", "build/live-quiet-pool.txt")).writeText(out.toString())
        println(out)
    }

    /**
     * docs/ROUTE-STORE.md section 1 claims single-digit KB per saved trip. This measures it on the
     * real routes from home instead of leaving the claim as an estimate.
     */
    /**
     * D22 + D24 live: planning from somewhere that is NOT the saved home. The bundled probe table is
     * measured from home, so this is the path where the app now MEASURES the approach from the live
     * origin instead of falling back to crow-flies.
     */
    @Test
    fun probeAwayFromHome() {
        val loaded = Assets.repository()
        Assume.assumeTrue(runBlocking { client.primaryReachable() })
        val home = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        // "out riding": ~3.5 km north-east of the saved home, on the way to the Sumner corridor
        val away = LatLon(47.271600, -122.244000)
        val planner = Planner(
            network = loaded.network,
            accessPoints = loaded.accessPoints,
            grader = SafetyGrader(loaded.limits, loaded.traffic),
            client = client,
            config = loaded.config.planner,
            home = home,
        )
        val started = System.currentTimeMillis()
        val result = runBlocking { planner.plan(PlanRequest(targetMiles = miles, origin = away)) }
        val elapsed = System.currentTimeMillis() - started
        val out = StringBuilder()
        out.appendLine("LIVE PLAN AWAY FROM THE SAVED HOME")
        val awayKm = com.biketrailplanner.domain.Geo.distanceM(away, home) / 1000.0
        out.appendLine("origin=$away (${"%.2f".format(awayKm)} km from the saved home)")
        out.appendLine("plan took ${elapsed} ms")
        out.appendLine()
        out.append(render(result))
        File(System.getProperty("probe.awayOut", "build/live-plan-away.txt")).writeText(out.toString())
        println(out)
    }

    @Test
    fun probeSavedRouteSizes() {
        val loaded = Assets.repository()
        Assume.assumeTrue(runBlocking { client.primaryReachable() })
        val home = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        val planner = Planner(
            network = loaded.network,
            accessPoints = loaded.accessPoints,
            grader = SafetyGrader(loaded.limits, loaded.traffic),
            client = client,
            config = loaded.config.planner,
            home = home,
        )
        val result = runBlocking { planner.plan(PlanRequest(targetMiles = miles, origin = home)) }
        val store = com.biketrailplanner.domain.InMemoryRouteStore()
        val reader = Assets.reader()
        val fingerprint = com.biketrailplanner.domain.DataFingerprint.of(
            com.biketrailplanner.domain.DataFingerprint.ASSETS.mapNotNull {
                runCatching { reader.read(it) }.getOrNull()
            },
        )
        val now = System.currentTimeMillis()
        val compliant = result.attempts.mapNotNull { it.route }.filter { it.verdict.hasNoKnownViolation }.distinctBy { it.signature }
        runBlocking {
            compliant.forEach { route ->
                store.save(
                    route = route,
                    origin = home,
                    targetMiles = miles,
                    waypoints = com.biketrailplanner.domain.MapsUrlBuilder.selectWaypoints(route, home)
                        .map { it.point }.take(loaded.config.planner.maxWaypoints),
                    fingerprint = fingerprint,
                    nowMs = now,
                )
            }
        }
        val out = StringBuilder()
        out.appendLine("SAVED-TRIP SIZE PROBE (docs/ROUTE-STORE.md)")
        out.appendLine("compliant options for a $miles mi ride from home: ${compliant.size}")
        compliant.forEach { route ->
            val record = com.biketrailplanner.domain.SavedRouteCodec.encode(
                route, home, miles,
                com.biketrailplanner.domain.MapsUrlBuilder.selectWaypoints(route, home).map { it.point },
                fingerprint, now,
            )
            out.appendLine(
                "  ${route.trailLabel} @ ${route.accessPoint.osmNodeId}: points=${route.roundTrip.size} " +
                    "roadWays=${record.roadWayCount} payload=${record.payload.size} bytes",
            )
        }
        val sizes = compliant.map { r ->
            com.biketrailplanner.domain.SavedRouteCodec.encode(r, home, miles, emptyList(), fingerprint, now).payload.size
        }
        val saved = runBlocking { store.matching(home, miles, fingerprint) }
        val written = runBlocking { store.count() }
        out.appendLine("stored rows readable back: ${saved.size} (of $written written)")
        out.appendLine("payload bytes: min=${sizes.minOrNull()} max=${sizes.maxOrNull()} mean=${sizes.average().toInt()}")
        out.appendLine("projected for the 500-row cap: ${sizes.average().toInt() * 500 / 1024} KB")
        File(System.getProperty("probe.sizeOut", "build/live-store-size-probe.txt")).writeText(out.toString())
        println(out)
    }

    @Test
    fun probeFromHome() {
        val loaded = Assets.repository()
        val reachable = runBlocking { client.primaryReachable() }
        Assume.assumeTrue("Valhalla at $base is not up; skipping the live probe", reachable)

        val shipped = loaded.config.planner
        val config = PlannerDto(
            accessPointsPerTrail = perTrail ?: shipped.accessPointsPerTrail,
            maxCandidates = maxCandidates ?: shipped.maxCandidates,
            detourAccessPointsPerTrail = shipped.detourAccessPointsPerTrail,
        )
        val home = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        val planner = Planner(
            network = loaded.network,
            accessPoints = loaded.accessPoints,
            grader = SafetyGrader(loaded.limits, loaded.traffic),
            client = client,
            config = config,
            home = home,
        )
        val result = runBlocking {
            planner.plan(
                PlanRequest(
                    targetMiles = miles,
                    origin = home,
                    maxCandidates = config.maxCandidates,
                    accessPointsPerTrail = config.accessPointsPerTrail,
                    allowFallback = false,
                ),
            )
        }

        val text = StringBuilder()
        text.appendLine("LIVE PLAN PROBE")
        text.appendLine("base=$base origin=${home.lat},${home.lon} targetMiles=$miles")
        text.appendLine("perTrail=${config.accessPointsPerTrail} maxCandidates=${config.maxCandidates}")
        text.appendLine("network trails=${loaded.network.trails.size} accessPoints=${loaded.accessPoints.size}")
        text.appendLine()
        text.append(render(result))
        out.parentFile?.mkdirs()
        out.writeText(text.toString())
        println(text)
    }

    private fun render(r: PlanResult): String {
        val s = StringBuilder()
        s.appendLine("outcome=${r.outcome} safeRouteFound=${r.safeRouteFound}")
        s.appendLine("chosen=${r.chosen?.let { it.trailNames.joinToString("+") + " @ " + it.accessPoint.osmNodeId }}")
        // D44: how much of the ride the app CHOSE is on streets that suit a bike, and how much of that
        // rests on the road class alone rather than on a posted limit or a count. This is the number
        // the rider's complaint is about ("too fixed on the big roads"), so the probe reports it.
        r.chosen?.let { c ->
            val quiet = com.biketrailplanner.domain.QuietRoute.score(c.roadSegments)
            s.appendLine("quiet(chosen): ${quiet.report()}")
            s.appendLine("via(chosen): ${c.via.joinToString(", ") { it.text }.ifEmpty { "(none)" }}")
        }
        r.options.forEach { o ->
            s.appendLine("quiet(option ${o.signature}): ${com.biketrailplanner.domain.QuietRoute.score(o.roadSegments).report()}")
        }
        s.appendLine("options=${r.options.size} attempts=${r.attempts.size}")
        r.refusal?.let { s.appendLine("refusal=$it") }
        if (r.warnings.isNotEmpty()) {
            s.appendLine("warnings:")
            r.warnings.forEach { s.appendLine("  - $it") }
        }
        s.appendLine()
        s.appendLine("ATTEMPTS")
        r.attempts.forEachIndexed { i, a ->
            val route = a.route
            if (route == null) {
                s.appendLine("%3d FAILED  %s :: %s".format(i + 1, a.label, a.failure))
            } else {
                val b = route.breakdown
                val verdict = route.verdict.outcome.name
                s.appendLine(
                    "%3d %-4s %s :: total=%.2fkm trail=%.2fkm D=%.0fm E=%.0fm ungraded=%.0fm shareD=%.1f%% %s".format(
                        i + 1, verdict, a.label,
                        route.totalM / 1000.0,
                        route.trailRoundTripM / 1000.0,
                        b.metres(com.biketrailplanner.domain.Grade.D),
                        b.metres(com.biketrailplanner.domain.Grade.E),
                        b.ungradedM,
                        if (b.gradedM > 0) 100.0 * b.metres(com.biketrailplanner.domain.Grade.D) / b.gradedM else 0.0,
                        route.verdict.violations.joinToString("; ") { it },
                    ),
                )
                if (!route.verdict.hasNoKnownViolation) {
                    route.cautions.take(4).forEach { c ->
                        s.appendLine("        caution: ${c.name} ${c.metres.toInt()}m ${c.grade}")
                    }
                }
            }
        }
        s.appendLine()
        s.appendLine("PASSING CANDIDATES")
        val passing = r.attempts.mapNotNull { it.route }.filter { it.verdict.hasNoKnownViolation }
        if (passing.isEmpty()) s.appendLine("  none")
        passing.forEach { route ->
            s.appendLine(
                "  ${route.trailNames.joinToString("+")} @ node ${route.accessPoint.osmNodeId} " +
                    "total=%.2fkm trail=%.1fmi D=%.0fm".format(
                        route.totalM / 1000.0, route.trailRoundTripM / 1609.344,
                        route.breakdown.metres(com.biketrailplanner.domain.Grade.D),
                    ),
            )
        }
        return s.toString()
    }
}
