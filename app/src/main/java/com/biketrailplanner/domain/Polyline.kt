package com.biketrailplanner.domain

/** An ordered list of points with cached arc lengths, used for both trail geometry and route shapes. */
class Polyline(val points: List<LatLon>) {

    val cumulative: DoubleArray = DoubleArray(points.size)

    init {
        require(points.size >= 2) { "a polyline needs at least two points, got ${points.size}" }
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += Geo.distanceM(points[i - 1], points[i])
            cumulative[i] = acc
        }
    }

    val lengthM: Double get() = cumulative.last()

    data class Projection(val arcM: Double, val offsetM: Double, val segmentIndex: Int, val point: LatLon)

    /** Nearest point on the polyline to [p]. */
    fun project(p: LatLon): Projection {
        var bestOffset = Double.MAX_VALUE
        var bestArc = 0.0
        var bestIndex = 0
        var bestPoint = points[0]
        for (i in 0 until points.size - 1) {
            val (offset, t) = Geo.pointToSegment(p, points[i], points[i + 1])
            if (offset < bestOffset) {
                bestOffset = offset
                bestArc = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t
                bestIndex = i
                bestPoint = LatLon(
                    points[i].lat + (points[i + 1].lat - points[i].lat) * t,
                    points[i].lon + (points[i + 1].lon - points[i].lon) * t,
                )
            }
        }
        return Projection(bestArc, bestOffset, bestIndex, bestPoint)
    }

    /** The point [arcM] metres along the polyline. */
    fun pointAt(arcM: Double): LatLon {
        if (arcM <= 0) return points.first()
        if (arcM >= lengthM) return points.last()
        var i = 1
        while (i < cumulative.size && cumulative[i] < arcM) i++
        val prev = cumulative[i - 1]
        val segLen = cumulative[i] - prev
        val t = if (segLen <= 1e-9) 0.0 else (arcM - prev) / segLen
        return LatLon(
            points[i - 1].lat + (points[i].lat - points[i - 1].lat) * t,
            points[i - 1].lon + (points[i].lon - points[i - 1].lon) * t,
        )
    }

    /** The vertices from [fromM] to [toM], with interpolated ends. */
    fun subLine(fromM: Double, toM: Double): List<LatLon> {
        val lo = minOf(fromM, toM)
        val hi = maxOf(fromM, toM)
        val out = ArrayList<LatLon>()
        out.add(pointAt(lo))
        for (i in points.indices) {
            val c = cumulative[i]
            if (c > lo && c < hi) out.add(points[i])
        }
        out.add(pointAt(hi))
        val result = if (fromM <= toM) out else out.reversed()
        return if (result.size < 2) listOf(pointAt(lo), pointAt(hi)) else result
    }

    fun reversedPoints(): List<LatLon> = points.reversed()
}
