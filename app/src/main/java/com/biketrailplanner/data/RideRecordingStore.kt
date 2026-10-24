package com.biketrailplanner.data

import android.content.Context

/**
 * D51: where the "a ride is being recorded" flag lives between the service and the UI.
 *
 * It is persisted (not held in a ViewModel) for the same reason `RideWatchStore` is: a ride lasts
 * 30-60 minutes and Android will kill our process while the rider is in Google Maps. The foreground
 * service is restarted by the system and must know a recording is in progress, and the screens must
 * be able to say so after a process death.
 */
class RideRecordingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bike-trail-planner-recording", Context.MODE_PRIVATE)

    data class State(
        val active: Boolean,
        val startedAtMs: Long?,
        val paused: Boolean,
    )

    fun load(): State = State(
        active = prefs.getBoolean(KEY_ACTIVE, false),
        startedAtMs = prefs.getLong(KEY_STARTED, NO_VALUE).takeIf { it > 0L },
        paused = prefs.getBoolean(KEY_PAUSED, false),
    )

    fun save(state: State) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, state.active)
            .putLong(KEY_STARTED, state.startedAtMs ?: NO_VALUE)
            .putBoolean(KEY_PAUSED, state.paused)
            .apply()
    }

    fun clear() = save(State(active = false, startedAtMs = null, paused = false))

    companion object {
        /**
         * D57: is a recording really running?
         *
         * The persisted flag means "a recording was started". It is only the truth when the service
         * that owns it is alive in this process: the service is `START_NOT_STICKY`, so if the process
         * is killed mid-ride the flag stays set and the app goes on saying *"Recording your ride"*
         * while nothing records and no ride is ever saved. A flag without a live service is a dead
         * recording, and the caller must clear it.
         */
        fun isReallyRecording(state: State, serviceRunning: Boolean): Boolean =
            state.active && serviceRunning

        private const val KEY_ACTIVE = "active"
        private const val KEY_STARTED = "started_at_ms"
        private const val KEY_PAUSED = "paused"
        private const val NO_VALUE = -1L
    }
}
