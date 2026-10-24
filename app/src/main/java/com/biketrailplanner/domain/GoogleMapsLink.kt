package com.biketrailplanner.domain

import java.util.Locale

/**
 * D61: hand a recorded ride to Google Maps as a navigation request.
 *
 * The rider's words: *"the recorded route must allow me use it to start a new navigation on google
 * map, with the exact stop points."*
 *
 * What Google Maps can and cannot do is not negotiable, so this is built around the limit rather than
 * pretending it away:
 *
 *  - Maps accepts a **destination** and a bounded list of **waypoints** through its documented URL
 *    API (`/maps/dir/?api=1`). The recorded points are passed as exactly those stops, which is what
 *    "exact stop points" means here.
 *  - Maps honours at most [MAX_INTERMEDIATE] intermediate points. A ride has hundreds; a URL cannot.
 *    So the stops are the first and last point **exactly**, plus evenly spaced recorded points in
 *    between. We never move a stop off the ride - we only choose which of its points to name.
 *  - Maps then **routes its own streets** between consecutive stops. It does not follow the recorded
 *    polyline. Sending the ride's own street names to Maps is not possible through any public API.
 *    The screen says so; see `docs/UI.md` 3.6.
 *
 * This is a pure function of the ride's points, so the URL is unit-tested without a phone.
 */
object GoogleMapsLink {

    /** Documented URL-API limit for intermediate points. Origin and destination are separate. */
    const val MAX_INTERMEDIATE = 9

    /** Origin + intermediates + destination. */
    const val MAX_STOPS = MAX_INTERMEDIATE + 2

    /**
     * The stops to name, in riding order: the exact first and last point, and evenly spaced recorded
     * points between them. Returns fewer than [MAX_STOPS] when the ride has fewer points, and an empty
     * list when there is nothing drawable - a ride with one fix has no route to hand over.
     */
    fun stops(points: List<LatLon>): List<LatLon> {
        if (points.size < 2) return emptyList()
        val chosen = if (points.size <= MAX_STOPS) {
            points
        } else {
            // Keep the ends EXACT, then walk the ride at an even stride for the middle stops.
            val lastIndex = points.size - 1
            val middle = (1..MAX_INTERMEDIATE).map { i ->
                points[((lastIndex.toDouble() * i) / (MAX_INTERMEDIATE + 1)).toInt()]
            }
            listOf(points.first()) + middle + points.last()
        }
        // Maps rejects a route whose stops repeat, and rounding a stride can pick the same recorded
        // point twice on a very short ride.
        return chosen.distinct()
    }

    fun stops(ride: RideTrack): List<LatLon> = stops(ride.points)

    /**
     * The Maps navigation URL, or null when the ride has no route to hand over.
     *
     * `origin` is the ride's own first point: repeating a ride means starting where it started, and
     * leaving the origin out would make Maps start from wherever the rider happens to be standing.
     * `travelmode=bicycling` is the honest mode - this is a ride, not a drive.
     */
    fun url(ride: RideTrack): String? = url(stops(ride))

    fun url(stops: List<LatLon>): String? {
        if (stops.size < 2) return null
        val waypoints = stops.subList(1, stops.size - 1)
        val link = StringBuilder("https://www.google.com/maps/dir/?api=1")
            .append("&origin=").append(format(stops.first()))
            .append("&destination=").append(format(stops.last()))
        if (waypoints.isNotEmpty()) {
            link.append("&waypoints=").append(waypoints.joinToString("%7C") { format(it) })
        }
        return link.append("&travelmode=bicycling").toString()
    }

    /** `lat,lon` at six decimals (about 10 cm), with a dot for the decimal point in every locale. */
    private fun format(point: LatLon): String =
        String.format(Locale.US, "%.6f,%.6f", point.lat, point.lon)
}
