package com.biketrailplanner.domain

/**
 * SPEC section 8 waypoint packing.
 *
 * Google re-computes the route through the waypoints, so the waypoints must pin the parts
 * that matter. At most 9 waypoints, packed in this priority order:
 *   1. the corridor access point,
 *   2. the turnaround point,
 *   3. corners where our safe route differs from the obvious direct route,
 *   4. points where our route uses a separated path or meets a hostile (grade D/E) road.
 *
 * The URL keeps `origin` OFF so Google Maps starts guidance from the live position
 * (verified on a device: docs/research/google-maps-device-test.md section 3.11), and the
 * waypoints parameter carries NUMERIC COORDINATES ONLY (docs/SECURITY.md SEC-11).
 */
object MapsUrlBuilder {

    const val BASE = "https://www.google.com/maps/dir/?api=1"

    data class Waypoint(
        val point: LatLon,
        val priority: Int,
        val kind: String,
        val label: String,
        val safetyCritical: Boolean,
        /**
         * Position along the ride, in metres: the commute shape first, then the access point,
         * then the turnaround. Points are SELECTED by [priority] but ORDERED by this, because
         * Google visits waypoints in list order and a priority-ordered list would zigzag.
         */
        val travelIndex: Double = 0.0,
    )

    data class MapsUrl(
        val url: String,
        val included: List<Waypoint>,
        val dropped: List<Waypoint>,
        val length: Int,
        val droppedSafetyCritical: Boolean,
    ) {
        val explanation: String?
            get() = when {
                !droppedSafetyCritical -> null
                else -> "The hand-off is not exact: ${dropped.filter { it.safetyCritical }.joinToString(", ") { it.label }} " +
                    "did not fit in the ${dropped.size + included.size} waypoints / 2048-character limit. " +
                    "Google may route that part differently."
            }
    }

    /** Picks the waypoints, in priority order, with nearby duplicates removed. */
    fun selectWaypoints(route: PlannedRoute, home: LatLon): List<Waypoint> {
        val out = ArrayList<Waypoint>()

        fun add(point: LatLon, priority: Int, kind: String, label: String, critical: Boolean, travelIndex: Double) {
            if (out.any { Geo.distanceM(it.point, point) < 60.0 }) return
            out.add(Waypoint(point, priority, kind, label, critical, travelIndex))
        }

        val commuteLength = Polyline(route.commute.points).lengthM

        // 1. the corridor access point - where the ride leaves the road network for the trail
        add(route.accessPoint.point, 1, "access point", "corridor access point", true, commuteLength + 1.0)
        // 2. the turnaround point
        add(route.turnaround, 2, "turnaround", "turnaround point", true, commuteLength + 2.0)
        // 2b. D31: the loop's via point. Without it Google re-computes the way back as the way out and
        //     the rider rides the mirror the app just avoided. Its travel index puts it after the
        //     turnaround and after the trail, which is where it is actually ridden.
        route.loop?.let { loop ->
            add(
                loop.viaPoint,
                2,
                "loop via",
                "the different way back",
                true,
                commuteLength + route.trailRoundTripM + 1.0,
            )
        }

        // 4. separated-facility entries and hostile-road contacts (mapped onto the commute shape
        //    by cumulative edge length, because trace_attributes returns edges in route order)
        val shape = route.commute.points
        if (shape.size >= 2 && route.roadSegments.isNotEmpty()) {
            val shapeLength = Polyline(shape).lengthM
            val edgesTotal = route.roadSegments.sumOf { it.lengthM }
            val scale = if (edgesTotal > 0) shapeLength / edgesTotal else 1.0
            val poly = Polyline(shape)
            var cursor = 0.0
            var previousGrade: Grade? = null
            var previousName: String? = null
            val facilityEntries = ArrayList<Waypoint>()
            val hostileEntries = ArrayList<Waypoint>()
            for (seg in route.roadSegments) {
                val startArc = cursor * scale
                cursor += seg.lengthM
                val point = poly.pointAt(startArc.coerceIn(0.0, shapeLength))
                val grade = seg.grade
                // One waypoint per hostile STRETCH, not per edge: a 600 m caution road is one
                // point, so the nine waypoints pin as many separate places as possible.
                val newStretch = grade != previousGrade || seg.displayName != previousName
                if (newStretch) {
                    when {
                        grade == Grade.E -> hostileEntries.add(Waypoint(point, 4, "hostile road", "grade E ${seg.displayName}", true, startArc))
                        grade == Grade.D -> hostileEntries.add(Waypoint(point, 4, "caution road", "grade D ${seg.displayName}", true, startArc))
                        (grade == Grade.A || grade == Grade.B) && previousGrade != null &&
                            previousGrade != Grade.A && previousGrade != Grade.B ->
                            facilityEntries.add(Waypoint(point, 4, "separated path", "separated facility on ${seg.displayName}", true, startArc))
                    }
                }
                previousGrade = grade
                previousName = seg.displayName
            }
            (hostileEntries + facilityEntries).take(6).forEach { w ->
                add(w.point, w.priority, w.kind, w.label, w.safetyCritical, w.travelIndex)
            }
        }

        // 3. corners where our route leaves the obvious direct line
        val straight = Polyline(listOf(home, route.accessPoint.point))
        val corners = route.commute.maneuvers
            .filter { kotlin.math.abs(it.turnDegree) >= 40 }
            .mapNotNull { m ->
                val p = m.begin ?: return@mapNotNull null
                val deviation = straight.project(p).offsetM
                if (deviation < 150.0) null else Triple(p, deviation, m.turnDegree)
            }
            .sortedByDescending { it.second }
            .take(3)
        corners.forEach { (p, deviation, _) ->
            add(p, 3, "corner", "corner ${fmt(deviation, 0)} m off the direct line", false, Polyline(route.commute.points).project(p).arcM)
        }

        return out.sortedBy { it.priority }
    }

    /** Builds the URL, adding waypoints in priority order and stopping at the limits. */
    fun build(
        route: PlannedRoute,
        home: LatLon,
        maxWaypoints: Int = 9,
        maxUrlLength: Int = 2048,
        dirAction: Boolean = true,
    ): MapsUrl {
        val candidates = selectWaypoints(route, home)
        val included = ArrayList<Waypoint>()
        var url = buildUrl(home, included, dirAction)
        for (w in candidates) {
            if (included.size >= maxWaypoints) break
            included.add(w)
            val candidateUrl = buildUrl(home, included, dirAction)
            if (candidateUrl.length > maxUrlLength) {
                included.removeAt(included.size - 1)
                break
            }
            url = candidateUrl
        }
        val includedPoints = included.toSet()
        val dropped = candidates.filter { it !in includedPoints }
        // Google visits waypoints in list order, so the final list follows the ride direction.
        val orderedIncluded = included.sortedBy { it.travelIndex }
        url = buildUrl(home, orderedIncluded, dirAction)
        return MapsUrl(
            url = url,
            included = orderedIncluded,
            dropped = dropped,
            length = url.length,
            droppedSafetyCritical = dropped.any { it.safetyCritical },
        )
    }

    /**
     * Only numeric coordinates ever reach the waypoints parameter.
     *
     * D18: without `dir_action` Google Maps shows the route with its stops and a Start button,
     * which is the confirmation step; with `dir_action=navigate` it starts guidance immediately.
     */
    fun buildUrl(home: LatLon, waypoints: List<Waypoint>, dirAction: Boolean = true): String {
        val destination = coordinate(home)
        val sb = StringBuilder(BASE)
        sb.append("&destination=").append(destination)
        if (waypoints.isNotEmpty()) {
            sb.append("&waypoints=")
            waypoints.forEachIndexed { i, w ->
                if (i > 0) sb.append('|')
                sb.append(coordinate(w.point))
            }
        }
        sb.append("&travelmode=bicycling")
        if (dirAction) sb.append("&dir_action=navigate")
        return sb.toString()
    }

    /** `lat,lon` with 6 decimals; throws if anything but numbers reaches this point. */
    internal fun coordinate(p: LatLon): String {
        val lat = fmt(p.lat, 6)
        val lon = fmt(p.lon, 6)
        val text = "$lat,$lon"
        require(NUMERIC.matches(text)) { "waypoints may contain numeric coordinates only, got '$text'" }
        return text
    }

    private val NUMERIC = Regex("^-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$")
}
