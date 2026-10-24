package com.biketrailplanner.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * D35 (docs/VELOTRIC-LINK.md option A): the Velotric **assist**. The app never presses a Velotric
 * button; it only opens the Velotric app so the rider taps START RIDING / Hold to Stop themselves.
 *
 * This is the whole integration surface: there is no deep link, no exported ride component, no
 * notification and no cloud API to call (all five doors were measured shut — docs/VELOTRIC-LINK.md
 * section 2), so opening the app is the only thing that is possible.
 *
 * SEC-17 style, like [GoogleMapsLauncher]: the intent is PINNED to the Velotric package and there is
 * no unpinned fallback. A plain launch intent for one package cannot leak anything, but pinning keeps
 * the behaviour predictable and the code honest about which app it is addressing. When the app is
 * absent [available] is false and the UI hides the button instead of failing a tap.
 */
class VelotricLauncher(private val context: Context) {

    sealed interface Result {
        data object Opened : Result
        data object Missing : Result
        data class Failed(val reason: String) : Result
    }

    /** True when the Velotric app is installed, so the UI can hide the button when it is not. */
    fun available(): Boolean = try {
        context.packageManager.getPackageInfo(VELOTRIC_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Open the Velotric app on its home screen ("START RIDING"). The rider starts and stops the ride
     * there; this call does not, and must never appear to.
     */
    fun open(): Result {
        val intent = context.packageManager.getLaunchIntentForPackage(VELOTRIC_PACKAGE)
            ?: return Result.Missing
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "opened the Velotric app ($VELOTRIC_PACKAGE) for the rider to start the ride")
            Result.Opened
        } catch (e: ActivityNotFoundException) {
            Result.Missing
        } catch (e: Exception) {
            Result.Failed("Could not open the Velotric app: " + e.message)
        }
    }

    companion object {
        /** The Velotric app package, measured on the phone (version 4.5.5). */
        const val VELOTRIC_PACKAGE = "com.mddoscar.velotricbike"
        private const val TAG = "VelotricLauncher"
    }
}
