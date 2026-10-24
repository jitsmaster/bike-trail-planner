package com.biketrailplanner.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** D36: the "Dismiss" action on the end-of-ride prompt. Just clears the notification. */
class RideWatchDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        RideReminder.cancel(context)
    }
}
