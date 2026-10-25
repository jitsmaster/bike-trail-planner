package com.biketrailplanner.data

import android.util.Log
import com.biketrailplanner.BuildConfig
import com.biketrailplanner.domain.GoogleMapsLink
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.RideTrack
import java.util.Locale

/**
 * D64: a recorded ride's raw material, in logcat, in debug builds only.
 *
 * The road list, the stops chosen from it and the exact link handed to Google Maps were never
 * readable off the device before: Maps truncates the URL in its own log, and the ride list shows a
 * summary. Without these lines the only way to judge the stop algorithm was to ride again for an hour.
 *
 * Two call sites use it, and both matter:
 *  - **when a ride is saved** ([com.biketrailplanner.AppContainer.saveRecordedRide]), so a fresh
 *    recording's result is on record;
 *  - **when a saved ride is selected** ([com.biketrailplanner.ui.PlanViewModel.selectRide]), so an
 *    algorithm change can be re-checked against an existing ride instead of a new one.
 *
 * `BuildConfig.DEBUG`-gated at the callers' convenience: the tag and the wording are internal, so
 * nothing here is a rider-facing promise.
 */
object RideDebugLog {

    const val TAG = "RideRoads"

    /** One summary line per ride: the roads it matched, in riding order, with where each starts. */
    fun save(why: String, ride: RideTrack) {
        if (!BuildConfig.DEBUG) return
        val miles = String.format(Locale.US, "%.2f", ride.distanceMiles)
        Log.i(TAG, "$why: ${ride.roads.size} roads, $miles mi: " + roads(ride))
        Log.i(TAG, "$why maps: " + GoogleMapsLink.url(ride))
    }

    /** The road list, so an algorithm change can be judged against the ride it was measured on. */
    fun roads(ride: RideTrack): String =
        ride.roads.joinToString(" | ") { "${it.name}@${it.startM.toInt()}m" }

    /** The chosen stops with the reason each one earned a slot - the algorithm's own explanation. */
    fun stops(ride: RideTrack): String =
        GoogleMapsLink.chosenStops(ride).joinToString(" | ") {
            "${it.atM.toInt()}m ${it.turnDeg.toInt()}deg ${it.point.lat},${it.point.lon} ${it.why}"
        }

    /** Same ride, both halves: what was ridden, and what is about to be handed to Google. */
    fun ride(why: String, ride: RideTrack) {
        if (!BuildConfig.DEBUG) return
        val polyM = Polyline(ride.points).lengthM
        Log.i(
            TAG,
            "$why geometry: ${ride.points.size} points, polyline ${polyM.toInt()}m, " +
                "ride ${ride.distanceM.toInt()}m",
        )
        save(why, ride)
        Log.i(TAG, "$why stops: " + stops(ride))
    }
}
