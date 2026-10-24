package com.biketrailplanner.domain

import kotlin.math.abs

/** City of Puyallup 2044 Comprehensive Plan bands, used by SPEC section 6b. */
enum class VolumeBand { LOW, MEDIUM, HIGH, UNKNOWN }

/**
 * How good the count is.
 * - MEASURED: a published count, 10 years old or newer.
 * - STALE: a published count older than ~10 years. Shown with its year.
 * - ESTIMATE: a modelled/extrapolated number, not a count. Never upgrades a grade.
 * - UNKNOWN: nothing usable, including a published 0 (Pierce County uses 0 for "Not Known").
 */
enum class TrafficConfidence { MEASURED, STALE, ESTIMATE, UNKNOWN }

data class TrafficCount(
    val roadName: String,
    val source: String,
    val aadt: Int?,
    val aadtYear: Int?,
    val confidence: TrafficConfidence,
    val lts: Int?,
    val volumeBand: VolumeBand,
    val wayIds: List<Long> = emptyList(),
    val osmNames: List<String> = emptyList(),
    val polyline: Polyline? = null,
    val note: String? = null,
) {
    /** True when the count is a real measurement (fresh or stale), so band 1 of the ladder applies. */
    val usableVolume: Boolean
        get() = aadt != null && aadt > 0 && (confidence == TrafficConfidence.MEASURED || confidence == TrafficConfidence.STALE)

    val isStale: Boolean get() = confidence == TrafficConfidence.STALE

    val text: String
        get() = when {
            aadt == null && confidence == TrafficConfidence.UNKNOWN -> "volume not known"
            aadt == null -> "volume not known (${confidence.name.lowercase()})"
            else -> "$aadt vpd" + (aadtYear?.let { " ($it)" } ?: "") +
                when (confidence) {
                    TrafficConfidence.STALE -> " STALE"
                    TrafficConfidence.ESTIMATE -> " estimate, not a count"
                    else -> ""
                }
        }
}

data class TrafficMatch(
    val count: TrafficCount,
    val matchKind: String,
    val staleYears: Int?,
) {
    val usableVolume: Boolean get() = count.usableVolume
    val band: VolumeBand get() = if (usableVolume) count.volumeBand else VolumeBand.UNKNOWN
    val lts: Int? get() = count.lts
    val text: String
        get() = count.text + (if (count.usableVolume) " (${matchKind} match)" else "")
}

/**
 * The measured traffic-volume table (SPEC section 6b step 1).
 *
 * AADT 0 means "Not Known" (Pierce County uses 0 that way): it is loaded as null and is never
 * treated as zero traffic. Unknown never becomes "low traffic" and never becomes safe.
 */
class TrafficTable(
    val entries: List<TrafficCount> = emptyList(),
    private val nowYear: Int = CURRENT_YEAR,
) {

    private val byWayId: Map<Long, TrafficCount> =
        entries.flatMap { e -> e.wayIds.map { it to e } }.toMap()

    private val byName: Map<String, TrafficCount> =
        entries.flatMap { e -> (e.osmNames + e.roadName).map { normalise(it) to e } }.toMap()

    private val withGeometry: List<TrafficCount> = entries.filter { it.polyline != null }

    val size: Int get() = entries.count { it.usableVolume }

    fun lookup(wayIds: List<Long>, names: List<String>, at: LatLon? = null): TrafficMatch? {
        for (id in wayIds) byWayId[id]?.let { return match(it, "way id") }
        if (at != null) {
            var best: TrafficCount? = null
            var bestOffset = Double.MAX_VALUE
            // Join by geometry before falling back to a name: docs/SPEC.md section 6b, join rules.
            for (e in withGeometry) {
                val offset = e.polyline!!.project(at).offsetM
                if (offset < bestOffset) {
                    bestOffset = offset
                    best = e
                }
            }
            if (best != null && bestOffset <= GEOMETRY_TOLERANCE_M) return match(best, "geometry")
        }
        for (n in names) byName[normalise(n)]?.let { return match(it, "street name") }
        return null
    }

    private fun match(count: TrafficCount, kind: String): TrafficMatch {
        val staleYears = count.aadtYear?.let { (nowYear - it).takeIf { years -> years > STALE_YEARS } }
        // A declared "measured" count that is old becomes STALE. An ESTIMATE stays an estimate:
        // it is weaker evidence than a stale count and must never be promoted into one.
        val effective = if (count.confidence == TrafficConfidence.MEASURED && staleYears != null) {
            count.copy(confidence = TrafficConfidence.STALE)
        } else {
            count
        }
        return TrafficMatch(effective, kind, staleYears)
    }

    companion object {
        const val CURRENT_YEAR = 2026
        const val STALE_YEARS = 10
        const val GEOMETRY_TOLERANCE_M = 50.0

        val EMPTY = TrafficTable(emptyList())

        fun bandFor(aadt: Int?): VolumeBand = when {
            aadt == null || aadt <= 0 -> VolumeBand.UNKNOWN
            aadt < LOW_MAX -> VolumeBand.LOW
            aadt <= MEDIUM_MAX -> VolumeBand.MEDIUM
            else -> VolumeBand.HIGH
        }

        const val LOW_MAX = 1500
        const val MEDIUM_MAX = 7000

        /** Normalised street name: case, punctuation and abbreviation differences removed. */
        fun normalise(name: String): String = StreetNames.normalise(name)
    }
}

/** Small helper for tests and the UI: how stale a count is in whole years. */
fun yearsOld(year: Int?, nowYear: Int = TrafficTable.CURRENT_YEAR): Int? =
    year?.let { abs(nowYear - it) }
