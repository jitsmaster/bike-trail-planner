package com.biketrailplanner.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * D51: where the ride recorder gets its GPS fixes.
 *
 * The recorder never talks to `LocationManager` directly. It consumes this seam, which is what makes
 * the whole feature testable without riding: a JVM test replays a scripted track, the debug simulator
 * replays the same track on the phone, and `tools/simulate_ride.py` drives the REAL
 * [AndroidLocationSource] through the emulator's own location provider. All three go through the same
 * `RideRecorder`.
 */
interface LocationSource {
    /** A stream of fixes. Cancelling the collection unregisters the platform listener. */
    fun fixes(): Flow<RideFix>
}

/**
 * The production source: the platform GPS provider.
 *
 * This reports `location.time` as the fix time, which is what the recorder compares for gaps, and the
 * platform's own speed when present. It is only ever bound by the foreground recording service, so the
 * OS keeps it alive while the rider is in Google Maps or Velotric - no background-location permission
 * is involved (D51, and the D35/D36 stance).
 */
class AndroidLocationSource(
    context: Context,
    private val intervalMs: Long = 2_000L,
    private val minDistanceM: Float = 0f,
) : LocationSource {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<RideFix> = callbackFlow {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            close()
            return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(
                    RideFix(
                        point = LatLon(location.latitude, location.longitude),
                        atEpochMs = location.time,
                        accuracyM = location.accuracy.toDouble(),
                        speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    ),
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                minDistanceM,
                listener,
                Looper.getMainLooper(),
            )
        }.onFailure { close(it); return@callbackFlow }
        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }
}

/**
 * A scripted track, the JVM-test and debug-simulator source. With [realtime] it emits one fix every
 * [intervalMs] so the foreground service and its notification can be watched on the phone; with the
 * default it replays instantly for a unit test.
 */
class ScriptedLocationSource(
    private val fixes: List<RideFix>,
    private val realtime: Boolean = false,
    private val intervalMs: Long = 200L,
) : LocationSource {

    override fun fixes(): Flow<RideFix> = flow {
        for (fix in fixes) {
            emit(fix)
            if (realtime) delay(intervalMs)
        }
    }
}
