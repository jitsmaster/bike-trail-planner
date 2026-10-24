package com.biketrailplanner.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 point. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_M = 6371008.8
    const val METRES_PER_MILE = 1609.344

    fun metresPerMile(): Double = METRES_PER_MILE

    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Perpendicular distance in metres from [p] to the segment [a]-[b], plus the fraction along it. */
    fun pointToSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        // Local equirectangular projection: good to well under a metre over the lengths used here.
        val latRef = Math.toRadians(p.lat)
        val kx = EARTH_RADIUS_M * cos(latRef)
        val ky = EARTH_RADIUS_M
        fun x(q: LatLon) = Math.toRadians(q.lon) * kx
        fun y(q: LatLon) = Math.toRadians(q.lat) * ky
        val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
        val px = x(p); val py = y(p)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 <= 1e-9) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        t = max(0.0, min(1.0, t))
        val cx = ax + t * dx; val cy = ay + t * dy
        return Pair(sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)), t)
    }

    fun milesToMetres(miles: Double): Double = miles * METRES_PER_MILE

    fun metresToMiles(metres: Double): Double = metres / METRES_PER_MILE
}
