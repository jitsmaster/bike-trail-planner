package com.biketrailplanner.data

import android.content.Context
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PolylineCodec
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

    /** Used only when the phone has no last-known fix at all (a bare emulator). */
    val DEFAULT_ORIGIN = LatLon(47.240371, -122.262019)

    /**
     * Where the baked loop begins - the front door of the house the whole app was built around.
     *
     * The simulator used to ride a **straight line** out and back (rider, 2026-10-26: *"in your
     * simulation, do some turns instead of going a straight line!"*). A straight line cannot exercise
     * the part that matters: every turn it produced was the one 180 degrees at the turnaround, so the
     * stop picker was judged on a ride with no junctions, no name changes and nothing to choose
     * between - and the Google Maps hand-off it produced named stops that were all on one street.
     */
    val LOOP_START = LatLon(47.240674, -122.261953)

    /**
     * The loop, as a Google-style encoded polyline at 1e-6 (the same form Valhalla returns, decoded by
     * `PolylineCodec`).
     *
     * Real geometry, taken from OSM, so the trace at the end of a simulated ride matches the streets the
     * loop actually rides: **18th Street East** west, **122nd Avenue East** south, **24th Street East**
     * east, then **125th Avenue Court East** north back to 18th and home. 2.6 km, four ~90-degree
     * corners and six smaller bends, all recorded from the real ways rather than drawn by hand - a
     * hand-drawn rectangle would sit in the middle of people's back gardens.
     */
    private const val LOOP_POLYLINE =
        "c}ibyA`{gehFLp`@vAbUIf|@uBjHqRi@oAlF\\`aB_AlUtjIfbAKmeHcCwQmE}B}N{A_rAsD_uA{HiKB}HtFyBrPSvj@Zj|E_mBiU~@mU]aaBnAmFpRh@tBkHHg|@wAcUMq`@"

    /** The loop's own length, for anything that wants to know how long a simulated ride is. */
    const val LOOP_LENGTH_M = 2_597.0

    /**
     * How close the phone has to be to [LOOP_START] for the simulator to ride the real loop.
     *
     * At home it does, which is the case the rider uses it in. Anywhere else the same shape is moved to
     * the phone ([shapeFor]) so a simulator run still has corners - it is a debug tool, and corners are
     * the whole point - but the streets are then whatever OSM happens to have there.
     */
    const val LOOP_SNAP_M = 3_000.0

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
     * The synthetic track: a real street loop with real corners, ridden from [origin].
     *
     * It closes on itself, which is what a round trip looks like and what the loop rules in
     * `GoogleMapsLink` are written for. The ride is deterministic, one fix per simulated second, so a
     * run can be replayed and compared.
     */
    fun track(origin: LatLon, startAtMs: Long): List<RideFix> =
        TrackSimulator.along(
            shape = shapeFor(origin),
            startAtMs = startAtMs,
            speedMps = SPEED_MPS,
            intervalMs = FIX_INTERVAL_MS,
            accuracyM = 6.0,
            jitterM = 2.5,
        )

    /** The baked loop, exactly where it was recorded. */
    fun loop(): List<LatLon> = PolylineCodec.decode(LOOP_POLYLINE)

    /**
     * The shape to ride from [origin]: the real loop when the phone is near it, otherwise the same
     * loop shifted so that its start is [origin].
     *
     * The shift is a plain degrees translation. At 3 km the metres-per-degree distortion between the
     * two latitudes is well under a metre, and this is a debug simulator, not a measurement.
     */
    fun shapeFor(origin: LatLon): List<LatLon> {
        val loop = loop()
        if (Geo.distanceM(origin, LOOP_START) <= LOOP_SNAP_M) return loop
        val dLat = origin.lat - LOOP_START.lat
        val dLon = origin.lon - LOOP_START.lon
        return loop.map { LatLon(it.lat + dLat, it.lon + dLon) }
    }
}
