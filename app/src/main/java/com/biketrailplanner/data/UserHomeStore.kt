package com.biketrailplanner.data

import android.content.Context
import com.biketrailplanner.domain.LatLon

/**
 * The rider's own saved home (docs/LOCATION-CONTROL.md section 6, layer 3 item 3).
 *
 * The home in `assets/config.json` is READ-ONLY at runtime: the app cannot rewrite its own APK, and
 * that record is also the only note of where the bundled trail and traffic data is valid. So the
 * rider's own home is kept here instead, next to the app's other settings in the same
 * "bike-trail-planner" SharedPreferences (`allow_public_fallback`, `reopened_way_ids`,
 * `last_target_miles`).
 *
 * It is the rider's explicit choice, never a side effect of refreshing a position, and it is used
 * only where it is genuinely needed: the bundled access-point probe distances and the
 * trail/traffic coverage wording. Nothing writes into the bundled assets.
 *
 * Coordinates are stored as strings so the double precision survives: a float would move the point
 * by about half a metre. Nothing is ever logged (docs/SECURITY.md SEC-10).
 */
class UserHomeStore(context: Context) {

    data class SavedHome(val point: LatLon, val label: String)

    private val prefs = context.applicationContext
        .getSharedPreferences("bike-trail-planner", Context.MODE_PRIVATE)

    /** The rider's saved home, or null when they have never saved one. */
    fun load(): SavedHome? {
        val label = prefs.getString(KEY_LABEL, null) ?: return null
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        return SavedHome(LatLon(lat, lon), label)
    }

    fun save(point: LatLon, label: String) {
        prefs.edit()
            .putString(KEY_LAT, point.lat.toString())
            .putString(KEY_LON, point.lon.toString())
            .putString(KEY_LABEL, label)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_LAT).remove(KEY_LON).remove(KEY_LABEL).apply()
    }

    companion object {
        private const val KEY_LAT = "user_home_lat"
        private const val KEY_LON = "user_home_lon"
        private const val KEY_LABEL = "user_home_label"
    }
}
