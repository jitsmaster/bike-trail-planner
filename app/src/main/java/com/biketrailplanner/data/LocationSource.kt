package com.biketrailplanner.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
 * The production source: the platform's location providers.
 *
 * This reports `location.time` as the fix time, which is what the recorder compares for gaps, and the
 * platform's own speed when present. It is only ever bound by the foreground recording service, so the
 * OS keeps it alive while the rider is in Google Maps or Velotric - no background-location permission
 * is involved (D51, and the D35/D36 stance).
 *
 * **More than one provider is requested on purpose (D66).** GPS alone is the best geometry, and it is
 * all the ride needs - but it is also the provider that goes silent under a bridge, in a canyon, or
 * under heavy tree cover, and a silent provider logs *nothing*. A coarse network fix is useless as
 * geometry (the recorder's 50 m accuracy gate throws it away, exactly as it should) and still worth
 * having in the raw archive: it is the difference between "I rode through this neighbourhood" and a
 * blank line. `FUSED_PROVIDER` (API 31+) is requested when available because it already merges the
 * others; on older versions `NETWORK_PROVIDER` is asked for directly. Duplicates between providers are
 * harmless: the recorder's 3 m step filter drops them from the track, and the archive is a record of
 * what arrived, not a de-duplicated product. Measured on the S23 Ultra indoors, 2026-10-25: GPS-only
 * request, service running, **zero fixes in four minutes** - which is correct and also why the coarse
 * fallback is worth its noise.
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
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) add(LocationManager.NETWORK_PROVIDER)
        }
        val registered = providers.filter { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, intervalMs, minDistanceM, listener, Looper.getMainLooper())
            }.isSuccess
        }
        // GPS is the one that must work; if the platform refuses even that there is no recording to have.
        if (LocationManager.GPS_PROVIDER !in registered) {
            close()
            return@callbackFlow
        }
        awaitClose { registered.forEach { provider -> runCatching { manager.removeUpdates(listener) } } }
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
