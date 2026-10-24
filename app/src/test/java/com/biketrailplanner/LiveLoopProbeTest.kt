package com.biketrailplanner

import com.biketrailplanner.data.ValhallaClient
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PlanRequest
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.fmt
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * LIVE PROBE - not a pass/fail test (D31, docs/LOOPS.md; D46).
 *
 * Runs the real [Planner] against the real bundled assets and the self-hosted Valhalla server and
 * writes what the shipped configuration actually does to a report file:
 *
 *  1. whether the rider comes back a different way, and by how much (the measured overlap);
 *  2. what the loop costs (extra km) and what the plan costs (time, with the loop on and off);
 *  3. whether the DRAWN trip agrees with the numbers - the check that caught the D46 multi-leg
 *     defect, where a routed via point made Valhalla return two legs and the app drew only the first.
 *
 * Skipped automatically when the server is not up.
 *
 * Usage:
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
 *     --tests 'com.biketrailplanner.LiveLoopProbeTest' -Dprobe.out=build/live-loop-after.txt
 */
class LiveLoopProbeTest {

    private val base = System.getProperty("probe.base", "http://127.0.0.1:8002")
    private val out = File(System.getProperty("probe.out", "build/live-loop-after.txt"))
    private val miles = System.getProperty("probe.miles")?.toDouble() ?: 10.0
    private val client by lazy { ValhallaClient(primaryBase = base, publicBase = base) }

    private data class Run(val result: PlanResult, val elapsedMs: Long)

    private fun plan(origin: LatLon, preferLoop: Boolean): Run {
        val loaded = Assets.repository()
        val planner = Planner(
            network = loaded.network,
            accessPoints = loaded.accessPoints,
            grader = SafetyGrader(loaded.limits, loaded.traffic),
            client = client,
            config = loaded.config.planner.copy(preferLoop = preferLoop),
            home = LatLon(loaded.config.home.lat, loaded.config.home.lon),
        )
        val started = System.currentTimeMillis()
        val result = runBlocking { planner.plan(PlanRequest(targetMiles = miles, origin = origin)) }
        return Run(result, System.currentTimeMillis() - started)
    }

    @Test
    fun probeLoop() {
        Assume.assumeTrue(runBlocking { client.primaryReachable() })
        val loaded = Assets.repository()
        val home = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        val away = LatLon(47.271600, -122.244000)
        val out = StringBuilder()
        out.appendLine("LIVE LOOP PROBE (D31) - what the shipped configuration actually does")
        out.appendLine("=".repeat(78))
        out.appendLine("server      : $base")
        out.appendLine("target      : ${miles} mi, prefer_loop=${loaded.config.planner.preferLoop}")
        out.appendLine("loop knobs  : max_extra=${loaded.config.planner.loopMaxExtraKm} km, " +
            "offsets=${loaded.config.planner.loopViaOffsetsM}, max_candidates=${loaded.config.planner.loopMaxCandidates}, " +
            "quiet_vias=${loaded.config.planner.loopMaxQuietVias}")
        out.appendLine()
        for ((label, origin) in listOf("FROM HOME" to home, "AWAY (47.2716,-122.244)" to away)) {
            val withLoop = plan(origin, preferLoop = true)
            val without = plan(origin, preferLoop = false)
            out.appendLine("-".repeat(78))
            out.appendLine("$label  origin=$origin")
            report(out, withLoop, without)
        }
        outFile(out)
    }

    private fun report(out: StringBuilder, withLoop: Run, without: Run) {
        val route = withLoop.result.chosen
        out.appendLine("outcome     : ${withLoop.result.outcome}, safeRouteFound=${withLoop.result.safeRouteFound}")
        out.appendLine("plan time   : ${withLoop.elapsedMs} ms with the loop search, ${without.elapsedMs} ms with it off" +
            " (+${withLoop.elapsedMs - without.elapsedMs} ms)")
        if (route == null) {
            out.appendLine("no ride was offered, so there is nothing to loop")
            out.appendLine()
            return
        }
        out.appendLine("ride        : ${route.trailLabel} - ${fmt(route.totalMiles, 2)} mi, ${fmt(route.trailMiles, 2)} mi on trail")
        val loop = route.loop
        if (loop == null) {
            out.appendLine("loop        : NONE - loopSearched=${route.loopSearched}" +
                " (the ride comes back the same way, and the screen says so)")
        } else {
            out.appendLine("loop        : overlap=${fmt(loop.overlapShare * 100, 1)}% of the outbound roads come back" +
                " (${fmt(loop.newRoadShare * 100, 1)}% new), extra=${fmt(loop.extraM / 1000.0, 2)} km, via=${loop.label}")
        }
        out.appendLine("drawn trip  : ${shapeCheck(route)}")
        out.appendLine("parts       : outbound ${fmt(route.commute.lengthM / 1000.0, 3)} km" +
            " + return ${fmt((route.returnCommute?.lengthM ?: route.commute.lengthM) / 1000.0, 3)} km" +
            " + trail ${fmt(route.trailRoundTripM / 1000.0, 3)} km" +
            " (return legs routed: ${if (route.returnCommute != null) "separate" else "mirrored"})")
        out.appendLine("rider copy  : ${com.biketrailplanner.ui.RiderText.loopSentence(route) ?: "(none)"}")
        out.appendLine("grade       : ${route.breakdown.byGrade.entries.joinToString(", ") { it.key.name + "=" + fmt(it.value / 1000.0, 2) + "km" }}")
        out.appendLine("verdict     : ${route.verdict.outcome}")
        out.appendLine()
    }

    /**
     * D46's regression check on real data: the trip that is DRAWN must be as long as the trip the
     * numbers describe. Before the multi-leg fix a routed via point made the drawn shape about one
     * leg short while `totalM` counted every leg, and this ratio was far below 1.
     */
    private fun shapeCheck(route: PlannedRoute): String {
        val drawn = if (route.roundTrip.size >= 2) Polyline(route.roundTrip).lengthM else 0.0
        val stated = route.totalM
        val ratio = if (stated > 0) drawn / stated else 0.0
        val verdict = if (ratio >= 0.97) "OK" else "MISMATCH"
        return "drawn ${fmt(drawn / 1000.0, 3)} km vs stated ${fmt(stated / 1000.0, 3)} km = ratio ${fmt(ratio, 3)} [$verdict]"
    }

    private fun outFile(body: StringBuilder) {
        out.writeText(body.toString())
        println(body)
    }
}
