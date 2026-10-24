package com.biketrailplanner.nav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.biketrailplanner.MainActivity
import com.biketrailplanner.R

/**
 * D36 (docs/VELOTRIC-LINK.md): the one "Ride finished?" prompt, in one place.
 *
 * Two things can raise it: [RideWatchService] when Google Maps navigation stops, and the app itself
 * as a safety net when a Velotric ride was started but navigation was never seen. Both post the same
 * notification, and tapping it opens the Velotric app so the rider taps *Hold to Stop* + *Confirm*.
 *
 * A tap is user-initiated, which is the only kind of launch Android allows from the background, so
 * the app can offer the ride's end but never press Velotric's buttons itself (option A).
 */
object RideReminder {

    const val CHANNEL_ID = "ride_watch"
    const val PROMPT_ID = 4711

    private const val REQUEST_OPEN = 1
    private const val REQUEST_DISMISS = 2
    private const val TAG = "RideReminder"

    fun post(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ride finished?")
            .setContentText("Tap to open Velotric and stop your ride.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openVelotric(context))
            .addAction(0, "Dismiss", dismiss(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(PROMPT_ID, notification) }
            .onFailure { Log.w(TAG, "could not post the end-of-ride prompt: ${it.message}") }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(PROMPT_ID)

    /** A launch intent for Velotric, or for our own screen if Velotric has been uninstalled. */
    private fun openVelotric(context: Context): PendingIntent {
        val velotric = context.packageManager.getLaunchIntentForPackage(VelotricLauncher.VELOTRIC_PACKAGE)
        val intent = (velotric ?: Intent(context, MainActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismiss(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_DISMISS,
        Intent(context, RideWatchDismissReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "End of ride",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks whether to stop your Velotric ride when you finish navigating."
            },
        )
    }
}
