package com.biketrailplanner.domain

/**
 * D59 (docs/UI.md section 3.6): what the one basemap is asked to draw.
 *
 * The app has exactly one `MapView`, owned by `MainActivity` and kept alive across screens (D21), so
 * the screens do not each own a map - they tell the shared one what to show. Before this, that could
 * only be a [PlannedRoute], so a recorded ride had nowhere to be drawn: the Past-rides list described a
 * ride it could not show, which is what the rider reported (*"I don't see any route at all"*).
 *
 * Keeping the choice here rather than in the composable makes it a pure function that can be unit
 * tested, which matters because "which shape is on screen" has a rule: a selected ride wins over the
 * plan, so that tapping a ride in the list actually changes what is behind the list.
 */
sealed interface MapShape {
    /** A planned route: grade-coloured runs, with its trail-entry and turnaround markers. */
    data class Planned(val route: PlannedRoute) : MapShape

    /** A ride the rider actually rode: one line, its own start and end. */
    data class Recorded(val ride: RideTrack) : MapShape

    /**
     * D64: a ride being recorded RIGHT NOW.
     *
     * It is a shape of its own rather than a [Recorded] ride because it must be drawn differently:
     * a recording grows once a second, so the map may not re-fit the camera on every fix (that was
     * the D60 bug in a different costume), and there is no grade, no label and no street list yet.
     */
    data class Live(val points: List<LatLon>) : MapShape
}

object MapShapeChoice {

    /**
     * The shape the map should draw, or null to just centre on the rider.
     *
     * A selected ride outranks a selected route on purpose. It also wins when it has no drawable
     * geometry: the rider tapped a specific ride, and quietly showing them a different one - the plan -
     * would be a worse answer than showing an empty map with the card explaining why.
     *
     * D64: a recording in progress outranks BOTH. While the rider is recording, the thing they are
     * doing is the ride they are on; the map showing last week's ride instead would be the D59
     * complaint again. It needs at least two points - one fix is not a line - and an empty [live] list
     * therefore falls through to the ordinary rules rather than blanking the map.
     */
    fun of(
        selectedRide: RideTrack?,
        selectedRoute: PlannedRoute?,
        live: List<LatLon> = emptyList(),
    ): MapShape? = when {
        live.size >= 2 -> MapShape.Live(live)
        selectedRide != null -> MapShape.Recorded(selectedRide)
        selectedRoute != null -> MapShape.Planned(selectedRoute)
        else -> null
    }
}
