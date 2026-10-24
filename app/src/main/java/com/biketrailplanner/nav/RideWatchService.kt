package com.biketrailplanner.nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.biketrailplanner.data.RideWatchStore
import com.biketrailplanner.domain.RideWatch

/**
 * D36 (docs/VELOTRIC-LINK.md): end the ride when navigation stops.
 *
 * Google Maps exposes no "navigation stopped" event, so this service watches its navigation
 * notification. Measured on the phone: the notification is `com.google.android.apps.maps`,
 * `category=navigation`, channel `1_foreground_1`, flags include `NO_CLEAR`, so it **cannot be swiped
 * away** — it disappears when navigation really ends.
 *
 * The whole decision is the pure state machine in `domain/RideWatch.kt`; this class only feeds it the
 * events and persists the outcome. It does NOT stop the Velotric ride: option A means the rider taps
 * *Hold to Stop* + *Confirm* themselves. The most it does is put a tappable notification in front of
 * them, because Android blocks us from opening an app from the background directly.
 */
class RideWatchService : NotificationListenerService() {

    private val store by lazy { RideWatchStore(this) }

    /**
     * When the system (re)connects the listener - after a reboot, or after restarting our process,
     * which Android will have killed during the ride - a navigation that is already running must still
     * count as "seen", or a navigation we did not watch begin would never be watched end.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        val now = System.currentTimeMillis()
        val state = store.load()
        if (!RideWatch.isArmed(state, now)) return
        val navigating = runCatching { activeNotifications?.any { isMapsNavigation(it) } == true }
            .getOrDefault(false)
        if (navigating) {
            RideWatch.onNavigationSeen(state, now)?.let(store::save)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (!isMapsNavigation(posted)) return
        val now = System.currentTimeMillis()
        RideWatch.onNavigationSeen(store.load(), now)?.let(store::save)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        if (!isMapsNavigation(removed)) return
        val now = System.currentTimeMillis()
        when (val effect = RideWatch.onNavigationEnded(store.load(), now)) {
            is RideWatch.Effect.Fire -> {
                // The ride has been prompted for, so the safety net must not prompt again for it.
                store.save(effect.state)
                RideReminder.post(this)
                // D61: navigation ending does NOT stop the recording. The rider's words: "I only want
                // to manually stop the ride recording, no auto stopping." This was the last automatic
                // stop in the app - the return-to-start rule and the six-hour cap are gone too - so a
                // recording now ends only when the rider taps Stop, which is also honest: the ride
                // does not stop being a ride just because the navigation did.
            }
            RideWatch.Effect.None -> Unit
        }
    }

    /**
     * The navigation notification, matched by category first. `NO_CLEAR` means it cannot be dismissed
     * by hand, so a removal is navigation ending. The id/tag fallback covers an SBN delivered without
     * its notification payload on removal.
     */
    private fun isMapsNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != MAPS_PACKAGE) return false
        if (sbn.notification?.category == Notification.CATEGORY_NAVIGATION) return true
        return sbn.notification?.category == null && sbn.id == MAPS_NAVIGATION_ID && sbn.tag == null
    }

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val MAPS_NAVIGATION_ID = 1
    }
}
