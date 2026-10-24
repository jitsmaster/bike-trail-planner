package com.biketrailplanner.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.biketrailplanner.domain.LatLon
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the device's position. No coordinates are ever logged (docs/SECURITY.md SEC-10).
 *
 * [lastKnown] returns a cached fix; [current] asks the platform for a fresh one (API 30+).
 * On older devices it degrades to the cached fix, which is enough for the origin warning.
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun manager(): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** A fix plus the two numbers that decide whether it is good enough to plan from (D22). */
    data class Fix(val point: LatLon, val accuracyM: Float, val ageSeconds: Long) {
        fun isGood(maxAgeS: Long, maxAccuracyM: Double): Boolean =
            ageSeconds <= maxAgeS && accuracyM <= maxAccuracyM
    }

    fun lastFix(): Fix? {
        if (!hasPermission()) return null
        val manager = manager() ?: return null
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        val best = candidates.maxByOrNull { it.time } ?: return null
        val ageNanos = now - best.elapsedRealtimeNanos
        val ageSeconds = if (ageNanos > 0) ageNanos / 1_000_000_000 else 0
        return Fix(LatLon(best.latitude, best.longitude), best.accuracy, ageSeconds)
    }

    fun lastKnown(): LatLon? {
        if (!hasPermission()) return null
        val manager = manager() ?: return null
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        val best = candidates.maxByOrNull { it.time } ?: return null
        return LatLon(best.latitude, best.longitude)
    }

    /** D22 rule 1: always read a FRESH fix; a cached one is only a fallback with its age shown. */
    suspend fun freshFix(timeoutMs: Long = 12_000): Fix? {
        if (!hasPermission()) return null
        val manager = manager() ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lastFix()
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                try {
                    manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, context.mainExecutor) { location ->
                        if (cont.isActive) {
                            cont.resume(
                                location?.let {
                                    Fix(LatLon(it.latitude, it.longitude), it.accuracy, 0)
                                },
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { signal.cancel() }
            }
        }
        return fix ?: lastFix()
    }

    suspend fun current(timeoutMs: Long = 10_000): LatLon? {
        lastKnown()?.let { return it }
        if (!hasPermission()) return null
        val manager = manager() ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                try {
                    manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, context.mainExecutor) { location ->
                        if (cont.isActive) {
                            cont.resume(location?.let { LatLon(it.latitude, it.longitude) })
                        }
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { signal.cancel() }
            }
        }
    }
}
