package com.biketrailplanner.domain

import kotlin.math.cos

/**
 * D51: generate a plausible GPS track from any shape, so the recorder can be exercised without
 * riding. One generator serves the JVM tests, the debug in-app simulator, and (with the same shape)
 * `tools/simulate_ride.py` against the emulator's own location provider.
 *
 * Everything is deterministic in [seed], so a failing case can be replayed exactly.
 */
object TrackSimulator {

    /**
     * Walk [shape] at [speedMps], emitting one fix every [intervalMs].
     *
     * @param jitterM a small reproducible wobble added to every fix, in metres. A stationary rider
     *   still wobbles, so a recorder that trusts raw points inflates distance; tests use this to prove
     *   the recorder's own step filter handles it.
     * @param dropoutEvery when > 0, every Nth fix starts a period of [dropoutCount] missing fixes, so
     *   gap handling can be exercised.
     * @param accuracyWorseEvery when > 0, every Nth fix reports [poorAccuracyM] instead, so the
     *   accuracy filter can be exercised.
     */
    fun along(
        shape: List<LatLon>,
        startAtMs: Long,
        speedMps: Double = 5.0,
        intervalMs: Long = 2_000L,
        accuracyM: Double = 8.0,
        jitterM: Double = 0.0,
        dropoutEvery: Int = 0,
        dropoutCount: Int = 1,
        accuracyWorseEvery: Int = 0,
        poorAccuracyM: Double = 120.0,
        seed: Long = 7L,
    ): List<RideFix> {
        require(shape.size >= 2) { "a track needs at least two points" }
        require(speedMps > 0.0 && intervalMs > 0L) { "speed and interval must be positive" }
        val poly = Polyline(shape)
        val stepM = speedMps * intervalMs / 1000.0
        val fixes = ArrayList<RideFix>()
        var arc = 0.0
        var t = startAtMs
        var i = 0
        var rng = seed
        while (arc <= poly.lengthM) {
            i++
            val base = poly.pointAt(arc)
            val point = if (jitterM > 0.0) jitter(base, jitterM, rng) else base
            rng = next(rng)
            val dropped = dropoutEvery > 0 && dropoutCount > 0 && (i - 1) % dropoutEvery < dropoutCount
            if (!dropped) {
                val accuracy =
                    if (accuracyWorseEvery > 0 && i % accuracyWorseEvery == 0) poorAccuracyM else accuracyM
                fixes.add(RideFix(point, t, accuracy, speedMps))
            }
            arc += stepM
            t += intervalMs
        }
        return fixes
    }

    /** A reproducible wobble of up to [radiusM] around [p]. */
    private fun jitter(p: LatLon, radiusM: Double, seed: Long): LatLon {
        val angle = (next(seed) % 1_000_000L) / 1_000_000.0 * 2.0 * Math.PI
        val dLat = radiusM * Math.sin(angle) / 111_320.0
        val dLon = radiusM * Math.cos(angle) / (111_320.0 * cos(Math.toRadians(p.lat)))
        return LatLon(p.lat + dLat, p.lon + dLon)
    }

    /** xorshift64; small, deterministic, and good enough for a wobble. */
    private fun next(seed: Long): Long {
        var x = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        return x
    }
}
