package com.biketrailplanner.data

import android.content.Context
import com.biketrailplanner.domain.RideWatch

/**
 * D36: where the ride watch lives between the hand-off and the end of navigation.
 *
 * This has to be on disk, not in the ViewModel: a ride lasts 30-60 minutes, and Android will almost
 * certainly kill our process while the rider is in Google Maps. The NotificationListenerService is
 * restarted by the system for the next notification event, and this is how it knows a ride is being
 * watched.
 */
class RideWatchStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bike-trail-planner-ride-watch", Context.MODE_PRIVATE)

    fun load(): RideWatch.State = RideWatch.State(
        armedAtMs = prefs.getLong(KEY_ARMED_AT, NO_VALUE).takeIf { it > 0L },
        sawNavigation = prefs.getBoolean(KEY_SAW_NAV, false),
        velotricStartedAtMs = prefs.getLong(KEY_VELOTRIC_STARTED, NO_VALUE).takeIf { it > 0L },
    )

    fun save(state: RideWatch.State) {
        prefs.edit()
            .putLong(KEY_ARMED_AT, state.armedAtMs ?: NO_VALUE)
            .putBoolean(KEY_SAW_NAV, state.sawNavigation)
            .putLong(KEY_VELOTRIC_STARTED, state.velotricStartedAtMs ?: NO_VALUE)
            .apply()
    }

    fun clear() = save(RideWatch.disarm())

    private companion object {
        const val KEY_ARMED_AT = "armed_at_ms"
        const val KEY_SAW_NAV = "saw_navigation"
        const val KEY_VELOTRIC_STARTED = "velotric_started_at_ms"
        const val NO_VALUE = -1L
    }
}
