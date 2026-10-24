package com.biketrailplanner.domain

/** Lenient parsing of the confidence and band spellings the traffic producer may use. */
object ConfidenceNames {
    fun parse(text: String): TrafficConfidence = when (text.trim().lowercase()) {
        "measured", "count", "counted", "current", "verified" -> TrafficConfidence.MEASURED
        "stale", "old", "historic", "historical" -> TrafficConfidence.STALE
        "estimate", "estimated", "modelled", "modeled", "extrapolated" -> TrafficConfidence.ESTIMATE
        else -> TrafficConfidence.UNKNOWN
    }

    fun band(text: String): VolumeBand = when (text.trim().lowercase()) {
        "low" -> VolumeBand.LOW
        "medium", "med", "moderate" -> VolumeBand.MEDIUM
        "high" -> VolumeBand.HIGH
        else -> VolumeBand.UNKNOWN
    }
}
