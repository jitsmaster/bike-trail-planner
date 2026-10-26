package com.biketrailplanner.data

import android.util.Log
import com.biketrailplanner.BuildConfig
import com.biketrailplanner.domain.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * D64: the ride being recorded RIGHT NOW, for the map.
 *
 * The rider asked to *"real time draw the trip out on the map as we go"*. Until this existed the map
 * could only draw a finished plan or a finished ride - the recorder accumulated points into a private
 * list inside a foreground service, and nothing outside it could see them until the ride had been
 * traced, graded and stored.
 *
 * This is a one-way pipe: the service publishes the accepted points as they arrive, the UI observes
 * them. It holds no ride, no recording state and no history - the recorder and the journal remain the
 * truth (D62), and this can be dropped at any moment without losing anything. A process death clears
 * it, which is exactly right: after a kill the map must not claim a live track it cannot extend.
 */
class LiveRideTrack {

    private val state = MutableStateFlow<List<LatLon>>(emptyList())

    /** The accepted points of the recording in progress; empty when nothing is being recorded. */
    val points: StateFlow<List<LatLon>> = state

    private var published = 0

    /**
     * Publish the track so far. Called once per accepted fix.
     *
     * The recorder only grows its list at the end, so the common case is "one more point": publish a
     * new list only when the count changed, and never copy the whole track for a fix that the
     * recorder's own 3 m step filter dropped.
     */
    fun publish(track: List<LatLon>) {
        if (track.size == published) return
        published = track.size
        state.value = track.toList()
        // D61/D65: the same "leave evidence" rule as the fix log. Two lines per recording say whether a
        // live track ever left the service, and the first fix is the one that decides whether the map
        // has anything to draw. Without this, "the map did not draw" and "nothing was published" look
        // identical from outside - which is exactly what happened on the phone on 2026-10-25.
        if (BuildConfig.DEBUG && (published <= 2 || published % PUBLISH_LOG_EVERY == 0)) {
            Log.d(TAG, "publish $published point(s)")
        }
    }

    private companion object {
        const val TAG = "RideLive"
        const val PUBLISH_LOG_EVERY = 100
    }

    fun clear() {
        published = 0
        state.value = emptyList()
    }
}
