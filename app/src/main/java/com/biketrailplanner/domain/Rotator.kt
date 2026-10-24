package com.biketrailplanner.domain

import kotlin.math.roundToInt

/**
 * The route signature (D6): the ordered trails ridden, the access point, and the turnaround,
 * rounded so that trivial GPS jitter does not create a new signature.
 */
object Signature {
    /**
     * A route signature is WHAT the ride is, not where the GPS happened to be:
     *  - the trails in order and the access point,
     *  - the origin (D22), rounded to ~110 m so ordinary jitter does not invent a new trip,
     *  - the turnaround, identified by its trail vertex index when we have one, and otherwise
     *    rounded to ~110 m.
     */
    fun of(
        trailNames: List<String>,
        origin: LatLon,
        accessPoint: AccessPoint,
        turnaround: LatLon,
        turnaroundVertex: Int? = null,
    ): String {
        val trails = "%s@%d".format(trailNames.joinToString("+"), accessPoint.osmNodeId)
        val from = "o:" + round(origin.lat, origin.lon)
        val back = if (turnaroundVertex != null) {
            "t:v$turnaroundVertex"
        } else {
            "t:" + round(turnaround.lat, turnaround.lon)
        }
        return "$trails#$from#$back"
    }

    /** Coarse rounding: 3 decimal places is about 110 m. */
    fun round(lat: Double, lon: Double): String = "%.3f,%.3f".format(lat, lon)
}

/**
 * A candidate the rotator can order.
 *
 * [preference] is the planner's own ranking of the ride, higher being better: the share of it on
 * streets that suit a bike (D44's [QuietRoute.QuietScore.goodShare]). It defaults to 0.0 so callers
 * that only care about rotation - tests, older call sites - keep working unchanged.
 */
data class RotationCandidate(
    val signature: String,
    val label: String,
    val preference: Double = 0.0,
)

/**
 * D6: "alternate" means rotation with memory and never the same signature twice in a row.
 *
 * D45 changes WHAT is rotated, not whether. The rider, 2026-09-21: *"We are riding bike on these
 * road. Smaller roads normally means less traffic and safer."* So the ride with the higher
 * [RotationCandidate.preference] - the one on the smaller, quieter streets - is the one offered, and
 * recency only breaks ties between rides the planner rates equally. The old rule sorted by recency
 * FIRST, which quietly discarded the planner's whole opinion of the ride: the pool arrived ordered
 * quietest-first and was re-ordered to "whatever was ridden longest ago", so a quiet ride through
 * small streets could lose to an arterial ridden last week.
 *
 * The hard constraint is unchanged and still comes first: the last signature is never offered first
 * (unless it is the only candidate), so two days in a row never hand over the same ride.
 */
class Rotator(
    private val lastRiddenEpochMs: Map<String, Long> = emptyMap(),
    private val lastRiddenSignature: String? = null,
) {

    fun order(candidates: List<RotationCandidate>): List<RotationCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(
            compareByDescending<RotationCandidate> { it.preference }
                // Among rides the planner rates the same, the one ridden longest ago goes first;
                // never-ridden ones (MIN_VALUE) go before those, as D6 always said.
                .thenBy { lastRiddenEpochMs[it.signature] ?: Long.MIN_VALUE }
                // And if two rides are identical on both, the signature decides, so the answer is
                // stable between two runs of the same plan.
                .thenBy { it.signature },
        )
        if (sorted.size == 1) return sorted
        val first = sorted.first()
        return if (first.signature == lastRiddenSignature) sorted.drop(1) + first else sorted
    }

    fun isRepeatOfLast(signature: String): Boolean = signature == lastRiddenSignature

    companion object {
        /** Convenience for tests and callers that only track the previous ride. */
        fun mostRecent(history: List<Pair<String, Long>>): String? =
            history.maxByOrNull { it.second }?.first
    }
}

/** Extra helpers used by the summary card. */
object Formatting {
    fun miles(metres: Double): String = fmt(Geo.metresToMiles(metres), 2) + " mi"

    fun miles1(metres: Double): String = fmt(Geo.metresToMiles(metres), 1) + " mi"

    fun metres(m: Double): String = if (m >= 1000) fmt(m / 1000, 2) + " km" else fmt(m, 0) + " m"

    fun percent(fraction: Double): String = fmt((fraction * 100).roundToInt().toDouble(), 0) + "%"

    fun minutes(seconds: Double): String = fmt(seconds / 60.0, 0) + " min"
}
