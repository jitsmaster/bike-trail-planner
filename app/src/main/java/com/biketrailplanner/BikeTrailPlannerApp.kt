package com.biketrailplanner

import android.app.Application
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class BikeTrailPlannerApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // D21: MapLibre needs its singleton before any MapView is created. The key is empty on
        // purpose: no account, no billing (D17) - the tiles come from OpenFreeMap's public style.
        // If initialisation fails, the app still plans, grades and hands off; the map is decoration.
        mapReady = runCatching {
            MapLibre.getInstance(this, "", WellKnownTileServer.MapLibre)
            true
        }.onFailure { Log.w("BikeTrailPlanner", "basemap unavailable: " + it.message) }
            .getOrDefault(false)
    }

    companion object {
        /** False when the basemap could not be initialised at all. */
        var mapReady: Boolean = false
            private set
    }
}
