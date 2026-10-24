package com.biketrailplanner.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * SPEC section 7 step 8 / D18: fire the Maps URL with ACTION_VIEW.
 *
 * SEC-17: the intent is PINNED to the Google Maps package. Without that pin the URL (which
 * carries the home coordinates and the route) was delivered to whatever app happened to be on
 * top, because any app may register for google.com/maps URLs. If Google Maps is not installed we
 * do NOT fall back to an unpinned intent: the rider gets a message instead, so the route cannot
 * leak to an unrelated app.
 */
class GoogleMapsLauncher(private val context: Context) {

    sealed interface Result {
        data class Launched(val url: String, val packageName: String) : Result
        data class Failed(val url: String, val reason: String) : Result
    }

    fun launch(url: String): Result {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // SEC-17: never let an unrelated app receive our home coordinates.
            setPackage(GOOGLE_MAPS_PACKAGE)
        }
        return try {
            context.startActivity(intent)
            Log.i(TAG, "handed the route to $GOOGLE_MAPS_PACKAGE")
            // Debug builds only: the URL is logged so the hand-off shape can be verified on a
            // device. Release builds never log it (docs/SECURITY.md SEC-10).
            if (com.biketrailplanner.BuildConfig.DEBUG) Log.d(TAG, "hand-off url: $url")
            Result.Launched(url, GOOGLE_MAPS_PACKAGE)
        } catch (e: ActivityNotFoundException) {
            Result.Failed(
                url,
                "Google Maps ($GOOGLE_MAPS_PACKAGE) is not installed, and the hand-off is pinned to it " +
                    "on purpose (SEC-17), so the route was not sent to any other app. The summary above " +
                    "is the full plan; the URL is shown so you can open it yourself.",
            )
        } catch (e: Exception) {
            Result.Failed(url, "Could not open Google Maps: " + e.message)
        }
    }

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val TAG = "GoogleMapsLauncher"
    }
}
