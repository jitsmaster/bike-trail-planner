package com.biketrailplanner.domain

/**
 * D36 (docs/VELOTRIC-LINK.md): the "end your ride when you stop navigating" watcher, plus the safety
 * net for a Velotric ride the navigation check never sees.
 *
 * The rider's ask was to be told to stop their Velotric ride when they **stop navigation**, not when
 * they happen to come back to the start point. Google Maps exposes no "navigation stopped" event, so
 * the only signal is its navigation notification: it appears when navigation starts and is removed
 * when navigation ends (measured on the phone: package `com.google.android.apps.maps`,
 * `category=navigation`, `NO_CLEAR`, so it cannot be swiped away by mistake).
 *
 * The safety net exists because that signal needs Google Maps navigation to actually run. If the
 * rider tapped "Start Velotric ride" but no navigation was ever seen, there is nothing to watch the
 * end of, so the app reminds them when it is next opened instead. Without it, a started ride could be
 * left running in silence.
 *
 * Everything here is pure and has no Android type in it, so the whole state machine is unit-testable
 * on the JVM (`RideWatchTest`) instead of only on a phone. The Android half lives in
 * `nav/RideWatchService.kt`, which feeds [onNavigationSeen] / [onNavigationEnded] and persists the
 * state, because Android will kill our process during a long ride.
 */
object RideWatch {

    /**
     * A hand-off arms the watcher for this long. Long enough for any real ride; short enough that an
     * unrelated Google Maps trip later the same day is unlikely to land inside the window.
     */
    const val ARM_WINDOW_MS = 6L * 60L * 60_000L

    /**
     * A ride shorter than this cannot have gone anywhere and come back. Both the navigation check and
     * the safety net wait this long before they will call a ride finished.
     */
    const val MIN_RIDE_MS = 10L * 60_000L

    /**
     * Whether a ride is being watched, whether a real navigation has actually been seen since it was
     * armed, and when the rider last started a Velotric ride through the app.
     *
     * [sawNavigation] is what stops a route that was only previewed in Google Maps (never navigated)
     * from firing. [velotricStartedAtMs] is the safety net: it is set when the rider taps "Start
     * Velotric ride" and cleared as soon as any reminder has been raised for that ride.
     */
    data class State(
        val armedAtMs: Long? = null,
        val sawNavigation: Boolean = false,
        val velotricStartedAtMs: Long? = null,
    )

    sealed interface Effect {
        /** Nothing to do. */
        data object None : Effect

        /** Show the "tap to open Velotric" prompt, and persist [state] (which is disarmed). */
        data class Fire(val state: State) : Effect
    }

    /** A hand-off to Google Maps arms a fresh watch, keeping any Velotric ride already started. */
    fun arm(state: State, nowMs: Long): State =
        state.copy(armedAtMs = nowMs, sawNavigation = false)

    /** The rider tapped "Start Velotric ride": remember it for the safety net. */
    fun onVelotricRideStarted(state: State, nowMs: Long): State =
        state.copy(velotricStartedAtMs = nowMs)

    /** A reminder has been raised for this ride, so the safety net must not raise another. */
    fun onRideReminded(state: State): State = state.copy(velotricStartedAtMs = null)

    /** True while a hand-off is still being watched (armed and inside the window). */
    fun isArmed(state: State, nowMs: Long): Boolean {
        val at = state.armedAtMs ?: return false
        return nowMs - at in 0..ARM_WINDOW_MS
    }

    /**
     * Google Maps navigation has been seen. Returns the state to persist, or null when there is
     * nothing to change (not armed, expired, or already seen).
     */
    fun onNavigationSeen(state: State, nowMs: Long): State? {
        if (!isArmed(state, nowMs)) return null
        if (state.sawNavigation) return null
        return state.copy(sawNavigation = true)
    }

    /**
     * Google Maps navigation has ended. Fires only for a ride that was armed, is inside the window,
     * and where navigation was really seen: a route that was only previewed never fires.
     */
    fun onNavigationEnded(state: State, nowMs: Long): Effect {
        if (!isArmed(state, nowMs)) return Effect.None
        if (!state.sawNavigation) return Effect.None
        return Effect.Fire(State())
    }

    /**
     * The safety net: a Velotric ride was started through the app, no navigation was ever seen for it
     * (a running navigation is the navigation check's job), and at least a ride's worth of time has
     * passed. The rider is reminded when the app is next opened.
     */
    fun safetyNetReminder(state: State, nowMs: Long): Boolean {
        val started = state.velotricStartedAtMs ?: return false
        val since = nowMs - started
        if (since < MIN_RIDE_MS) return false
        if (since > ARM_WINDOW_MS) return false
        // A navigation that was seen means the end is watched by the navigation check instead.
        return !state.sawNavigation
    }

    /** A new plan is a new ride, so any watch left over from the last one is dropped. */
    fun disarm(): State = State()
}
