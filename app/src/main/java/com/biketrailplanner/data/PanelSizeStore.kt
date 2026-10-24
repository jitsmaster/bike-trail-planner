package com.biketrailplanner.data

import android.content.Context
import com.biketrailplanner.domain.PanelSize

/**
 * D53: the chosen bottom-panel size, remembered between launches.
 *
 * A rider who has pushed the panel down to see the map does not want to do it again on the next run,
 * and this is exactly the kind of one-value preference the small stores next to it hold.
 */
class PanelSizeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bike-trail-planner-panel", Context.MODE_PRIVATE)

    /** The remembered fraction, always a legal detent. */
    fun load(): Float = PanelSize.nearest(prefs.getFloat(KEY_FRACTION, PanelSize.HALF))

    fun save(fraction: Float) {
        prefs.edit().putFloat(KEY_FRACTION, PanelSize.nearest(fraction)).apply()
    }

    private companion object {
        const val KEY_FRACTION = "fraction"
    }
}
