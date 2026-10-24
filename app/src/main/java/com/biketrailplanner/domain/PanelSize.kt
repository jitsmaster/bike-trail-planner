package com.biketrailplanner.domain

/**
 * D53 (docs/UI.md section 3.4): how much of the frame the bottom panel may cover.
 *
 * The panel is where the plan is described, but the map is the point of the app, and the rider often
 * wants to look at the roads without losing the panel. So the panel is resizable: it snaps to a few
 * detents instead of being fixed, and the choice is remembered.
 *
 * Pure on purpose - the snapping rule is arithmetic, so it is unit-tested on the JVM rather than
 * eyeballed on a phone.
 */
object PanelSize {

    /** Just the handle and the title: almost the whole map, still a way back to the panel. */
    const val COLLAPSED = 0.30f

    /** The middle stop, so a drag does not have to be precise. */
    const val HALF = 0.62f

    /** Today's behaviour before D53: everything left below the chrome. */
    const val FULL = 1.0f

    val DETENTS: List<Float> = listOf(COLLAPSED, HALF, FULL)

    /** Never let the panel go below [COLLAPSED] - that is the rider's way back to the plan. */
    fun clamp(fraction: Float): Float =
        if (!fraction.isFinite()) FULL else fraction.coerceIn(COLLAPSED, FULL)

    /** The detent a released drag settles on. */
    fun nearest(fraction: Float): Float {
        val wanted = clamp(fraction)
        var best = FULL
        var bestDistance = Float.MAX_VALUE
        for (detent in DETENTS) {
            val distance = kotlin.math.abs(detent - wanted)
            if (distance < bestDistance) {
                bestDistance = distance
                best = detent
            }
        }
        return best
    }

    /** The detent after [fraction]'s, wrapping around: what a tap on the handle does. */
    fun next(fraction: Float): Float {
        val index = DETENTS.indexOf(nearest(fraction))
        return DETENTS[(index + 1) % DETENTS.size]
    }
}
