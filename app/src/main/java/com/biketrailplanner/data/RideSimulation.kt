package com.biketrailplanner.data

import android.content.Context
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.TrackSimulator

/**
 * D51 (docs/RIDE-RECORDER.md section 4): the debug in-app simulator.
 *
 * The rider cannot ride right now, so the recorder has to be exercisable on the phone without moving.
 * This replays a synthetic track through the REAL foreground service - the same `RideRecorder`, the
 * same notification, the same trace-and-grade save - so the debug build can be walked end to end on a
 * desk. It is consulted only when `BuildConfig.DEBUG` is true, and it is **one-shot**: [consumeEnabled]
 * clears the flag as it reads it, so a simulated ride can never leak into the next real recording.
 */
object RideSimulation {
    private const val PREFS = "bike-trail-planner-debug-sim"
    private const val KEY_ENABLED = "simulate_next_recording"

    /** One fix per simulated second, so the recorder's own timing math is exercised honestly. */
    const val FIX_INTERVAL_MS = 1_000L

    /** Replay faster than real time: a one-mile ride is watchable in about a minute. */
    const val EMIT_EVERY_MS = 160L

    const val SPEED_MPS = 5.0

    /** Half the out-and-back length. Half a mile out is over the recorder's return-to-start bar. */
    const val HALF_MILES = 0.5

    /** Used only when the phone has no last-known fix at all (a bare emulator). */
    val DEFAULT_ORIGIN = LatLon(47.240371, -122.262019)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Arm the simulator for exactly one recording. */
    fun enable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
    }

    /** Read and clear, so the simulation applies to one recording and never the next one. */
    fun consumeEnabled(context: Context): Boolean {
        val p = prefs(context)
        val on = p.getBoolean(KEY_ENABLED, false)
        if (on) p.edit().putBoolean(KEY_ENABLED, false).apply()
        return on
    }

    /**
     * The synthetic track: a straight line out and back from [origin]. Out-and-back is deliberate - it
     * makes the return-to-start auto-stop and the trace-and-grade save both fire in a single run, which
     * is exactly the path a real ride takes.
     */
    fun track(origin: LatLon, startAtMs: Long, halfMiles: Double = HALF_MILES): List<RideFix> =
        TrackSimulator.along(
            shape = outAndBack(origin, Geo.milesToMetres(halfMiles)),
            startAtMs = startAtMs,
            speedMps = SPEED_MPS,
            intervalMs = FIX_INTERVAL_MS,
            accuracyM = 6.0,
            jitterM = 2.5,
        )

    /** [origin] -> [halfDistanceM] due north -> [origin]. */
    fun outAndBack(origin: LatLon, halfDistanceM: Double): List<LatLon> =
        listOf(origin, LatLon(origin.lat + halfDistanceM / 111_320.0, origin.lon), origin)
}
