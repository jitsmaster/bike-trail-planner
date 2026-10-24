package com.biketrailplanner

import com.biketrailplanner.data.RegionalMerge
import com.biketrailplanner.data.RegionalTrailLoader
import com.biketrailplanner.data.ValhallaClient
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.SafetyGrader
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * LIVE PROBE - not a pass/fail test. Runs the real [Planner] against the real bundled assets, the real
 * bundled REGIONAL set and the self-hosted Valhalla server, from the saved home origin, and reports
 * what adding the regional trails does to the plan the rider sees (D23 answer (a)).
 *
 * Skipped automatically when the server is not up. Writes build/live-regional-probe.txt.
 *
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *     --tests 'com.biketrailplanner.LiveRegionalProbeTest'
 */
class LiveRegionalProbeTest {

    private val base = System.getProperty("probe.base", "http://127.0.0.1:8002")
    private val out = File(System.getProperty("probe.out", "build/live-regional-probe.txt"))
    private val miles = System.getProperty("probe.miles")?.toDouble() ?: 10.0

    private val client by lazy { ValhallaClient(primaryBase = base, publicBase = base) }

    @Test
    fun probePlanWithTheRegionalSet() {
        val loaded = Assets.repository()
        Assume.assumeTrue(runBlocking { client.primaryReachable() })
        val home = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        val radiusM = loaded.config.planner.trailRadiusKm * 1000.0
        val regional = RegionalTrailLoader(Assets.reader())
            .load(home, loaded.network.trails)
        val merged = RegionalMerge.network(loaded.network, regional)
        val accessPoints = RegionalMerge.accessPoints(loaded.accessPoints, regional)

        val inRange = merged.trails.count { trail ->
            trail.fullGeometry.points.minOf { com.biketrailplanner.domain.Geo.distanceM(it, home) } <= radiusM
        }
        val inRangeRegional = regional.trails.count { trail ->
            trail.fullGeometry.points.minOf { com.biketrailplanner.domain.Geo.distanceM(it, home) } <= radiusM
        }

        val report = StringBuilder()
        report.appendLine("LIVE REGIONAL PROBE (D23 a: bundled six + 191 regional trails)")
        report.appendLine("regional trails ${regional.trails.size}, of which ${regional.multiPiece} multi-piece")
        report.appendLine("regional access points ${regional.accessPoints.size}")
        report.appendLine("trails within ${loaded.config.planner.trailRadiusKm} km of home: $inRange (of which regional $inRangeRegional)")

        for (useRegional in listOf(false, true)) {
            val planner = Planner(
                network = if (useRegional) merged else loaded.network,
                accessPoints = if (useRegional) accessPoints else loaded.accessPoints,
                grader = SafetyGrader(loaded.limits, loaded.traffic),
                client = client,
                config = loaded.config.planner,
                home = home,
            )
            val started = System.currentTimeMillis()
            val result = runBlocking { planner.plan(PlanRequest(targetMiles = miles, origin = home)) }
            val ms = System.currentTimeMillis() - started
            report.appendLine()
            report.appendLine(if (useRegional) "=== WITH the regional set ===" else "=== WITHOUT (the bundled six) ===")
            report.appendLine("outcome=${result.outcome} chosen=${result.chosen?.trailLabel} in ${ms} ms")
            report.appendLine(
                "  ${result.chosen?.let { "%.2f mi total, %.2f mi trail, passes=${it.verdict.hasNoKnownViolation}" } ?: "no route"}",
            )
            report.appendLine("  options=" + result.options.map { it.trailLabel + " (" + "%.2f".format(it.totalMiles) + " mi)" })
            report.appendLine("  attempts=${result.attempts.size} produced=${result.attempts.count { it.produced }}")
            (result.chosen ?: result.bestAttempt)?.let { route ->
                val drawn = com.biketrailplanner.domain.Polyline(route.roundTrip).lengthM
                val worst = route.roundTrip.zipWithNext { a, b -> com.biketrailplanner.domain.Geo.distanceM(a, b) }.max()
                val atHome = route.roundTrip.count { com.biketrailplanner.domain.Geo.distanceM(it, home) <= 5.0 }
                report.appendLine(
                    "  geometry: drawn=%.0f m declared=%.0f m (%.1f%%), steps=%d, largest step=%.0f m, points at the start=%d"
                        .format(drawn, route.totalM, 100.0 * (drawn - route.totalM) / route.totalM, route.roundTrip.size, worst, atHome),
                )
            }
            report.appendLine("  refusal=${result.refusal}")
            result.attempts.forEach { attempt ->
                report.appendLine("    - " + attempt.label + " -> " + (attempt.route?.let { r ->
                    "%.2f mi, %.2f mi trail, passes=${r.verdict.hasNoKnownViolation}, capped=${r.capped}".format(r.totalMiles, r.trailMiles)
                } ?: attempt.failure))
            }
        }
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)
    }
}
