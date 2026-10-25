package com.biketrailplanner.domain

import java.util.Locale

/**
 * D61/D64: hand a recorded ride to Google Maps as a navigation request.
 *
 * The rider's words: *"the recorded route must allow me use it to start a new navigation on google
 * map, with the exact stop points"* and, for D64, *"add the points to key turns and let google map
 * handle the more natural stops automatically."*
 *
 * What Google Maps can and cannot do is not negotiable, so this is built around the limit rather than
 * pretending it away:
 *
 *  - Maps accepts a **destination** and a bounded list of **waypoints** through its documented URL
 *    API (`/maps/dir/?api=1`). It has no parameter for a polyline, a GPX or a KML: no public interface
 *    accepts recorded geometry (docs/research/google-maps-handoff.md section 3).
 *  - Maps honours at most 9 intermediate stops. A ride has hundreds of junctions; a URL has nine
 *    slots. **Which** nine is the whole design problem, and D64 answers it: the junctions where the
 *    ride turns, changes street, or enters or leaves a separated path - chosen by [StopPicker] from
 *    the ride's road list - plus the far point. Everything between them is left to Google, which is
 *    the "natural stops" the rider asked for.
 *  - A ride that could not be traced has no road list. It falls back to evenly spaced recorded points,
 *    which pins the shape without claiming to know where the turns are.
 *
 * **A loop is not a route with two ends.** An out-and-back starts and ends at the same place, and Maps
 * rejects a route whose stops repeat. So when the ride comes back to where it started, `origin` is
 * left OFF - Maps then starts from the live position (verified on a device, D18; `MapsUrlBuilder`
 * makes the same choice for planned rides) - and the waypoints carry the outbound turns plus the
 * turnaround. The return leg is Google's own choice: the same nine corners cannot be named twice, and
 * on an out-and-back they are the same corners.
 *
 * This is a pure function of the ride, so the URL is unit-tested without a phone.
 */
object GoogleMapsLink {

    /** Documented URL-API limit for intermediate stops. Origin and destination are separate. */
    const val MAX_INTERMEDIATE = StopPicker.MAX_INTERMEDIATE

    /** Origin + intermediates + destination. */
    const val MAX_STOPS = MAX_INTERMEDIATE + 2

    /** A ride that ends this close to where it started is a loop, not a there-and-back. */
    const val LOOP_TOLERANCE_M = 150.0

    /** Two chosen stops closer than this are the same place; Maps rejects a repeated stop. */
    private const val SAME_STOP_M = 40.0

    /**
     * The intermediate stops to name, in riding order - the key turns of the ride.
     *
     * A ride with a road list gets its junctions ([StopPicker]); a ride without one falls back to
     * evenly spaced recorded points, which at least keeps the shape from being replaced wholesale.
     */
    fun interiorStops(ride: RideTrack): List<LatLon> {
        val fromRoads = StopPicker.stops(ride.points, ride.roads, MAX_INTERMEDIATE).map { it.point }
        val chosen = if (fromRoads.isNotEmpty()) fromRoads else fallbackInterior(ride.points)
        // A stop on the ride's own first point is the origin by another name, and Maps rejects a
        // route that repeats a stop.
        val start = ride.points.firstOrNull() ?: return chosen
        return chosen.filterNot { Geo.distanceM(it, start) < SAME_STOP_M }
    }

    /** The same choice as [interiorStops], with the reason each point was chosen (for the screen). */
    fun chosenStops(ride: RideTrack): List<RideStop> =
        StopPicker.stops(ride.points, ride.roads, MAX_INTERMEDIATE)

    /**
     * Evenly spaced fallback for a ride with no road list: the exact first and last point, and
     * recorded points between them. Returns fewer than [MAX_STOPS] when the ride has fewer points, and
     * an empty list when there is nothing drawable - a ride with one fix has no route to hand over.
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

    /**
     * The Maps navigation URL, or null when the ride has no route to hand over.
     *
     * `travelmode=bicycling` is the honest mode - this is a ride, not a drive.
     */
    fun url(ride: RideTrack): String? {
        if (ride.points.size < 2) return null
        val isLoop = Geo.distanceM(ride.points.first(), ride.points.last()) <= LOOP_TOLERANCE_M
        return build(
            origin = if (isLoop) null else ride.points.first(),
            destination = ride.points.last(),
            waypoints = interiorStops(ride),
        )
    }

    /** Kept for callers that already hold a chosen stop list (tests, and the fallback path). */
    fun url(stops: List<LatLon>): String? {
        if (stops.size < 2) return null
        val isLoop = Geo.distanceM(stops.first(), stops.last()) <= LOOP_TOLERANCE_M
        return build(
            origin = if (isLoop) null else stops.first(),
            destination = stops.last(),
            waypoints = stops.subList(1, stops.size - 1),
        )
    }

    /**
     * @param origin null for a loop: Maps then starts from the rider's live position, which is the
     *   right behaviour for a ride that begins and ends at the same place.
     */
    private fun build(origin: LatLon?, destination: LatLon, waypoints: List<LatLon>): String? {
        val cleaned = waypoints.fold(ArrayList<LatLon>()) { kept, point ->
            val duplicate = kept.any { Geo.distanceM(it, point) < SAME_STOP_M } ||
                Geo.distanceM(point, destination) < SAME_STOP_M ||
                (origin != null && Geo.distanceM(point, origin) < SAME_STOP_M)
            if (!duplicate) kept.add(point)
            kept
        }.take(MAX_INTERMEDIATE)
        if (origin != null && Geo.distanceM(origin, destination) < SAME_STOP_M && cleaned.isEmpty()) return null

        val link = StringBuilder("https://www.google.com/maps/dir/?api=1")
        if (origin != null) link.append("&origin=").append(format(origin))
        link.append("&destination=").append(format(destination))
        if (cleaned.isNotEmpty()) {
            link.append("&waypoints=").append(cleaned.joinToString("%7C") { format(it) })
        }
        return link.append("&travelmode=bicycling").toString()
    }

    /** The evenly spaced middle of a ride, without its two ends. */
    private fun fallbackInterior(points: List<LatLon>): List<LatLon> {
        val all = stops(points)
        if (all.size <= 2) return emptyList()
        return all.subList(1, all.size - 1)
    }

    private fun near(point: LatLon, points: List<LatLon>): Boolean =
        points.isNotEmpty() && Geo.distanceM(point, points.first()) < SAME_STOP_M
    /** `lat,lon` at six decimals (about 10 cm), with a dot for the decimal point in every locale. */
    private fun format(point: LatLon): String =
        String.format(Locale.US, "%.6f,%.6f", point.lat, point.lon)
}
