package com.biketrailplanner.domain

/**
 * Splits the planned round trip into coloured runs, one per safety grade.
 *
 * The road edges come back from trace_attributes in route order, so each edge is mapped to
 * its arc-length range on the route shape. The trail legs are grade A by construction, and
 * the return half of the round trip mirrors the outbound half.
 */
object RouteOverlay {

    data class ColoredRun(val grade: Grade, val points: List<LatLon>, val label: String)

    fun build(route: PlannedRoute): List<ColoredRun> {
        val runs = ArrayList<ColoredRun>()
        val outbound = route.commute.points
        if (outbound.size >= 2 && route.roadSegments.isNotEmpty()) {
            val outboundRuns = runsFor(outbound, route.roadSegments, "outbound")
            runs.addAll(outboundRuns)
            // D31 (docs/LOOPS.md): when the rider comes back a different way, the return has its OWN
            // traced edges and must be coloured from them. Only a mirrored return (no loop) is drawn
            // by reversing the outbound runs - see PlannedRoute.returnCommute.
            val realReturn = route.returnCommute
            if (realReturn != null && route.returnSegments.isNotEmpty()) {
                runs.addAll(runsFor(realReturn.points, route.returnSegments, "return"))
            } else {
                for (run in outboundRuns.reversed()) {
                    runs.add(ColoredRun(run.grade, run.points.reversed(), "return"))
                }
            }
        } else if (outbound.size >= 2) {
            runs.add(ColoredRun(Grade.UNGRADED, outbound, "outbound"))
            runs.add(ColoredRun(Grade.UNGRADED, outbound.reversed(), "return"))
        }
        val trailOut = ArrayList<LatLon>()
        route.trailLegs.forEach { leg -> trailOut.addAll(leg.points) }
        if (trailOut.size >= 2) {
            runs.add(ColoredRun(Grade.A, trailOut, "trail out"))
            runs.add(ColoredRun(Grade.A, trailOut.reversed(), "trail back"))
        }
        return runs
    }

    /** One coloured run per graded segment, mapped onto [shape] by cumulative edge length. */
    private fun runsFor(shape: List<LatLon>, segments: List<GradedSegment>, label: String): List<ColoredRun> {
        if (shape.size < 2 || segments.isEmpty()) return emptyList()
        val poly = Polyline(shape)
        val total = segments.sumOf { it.lengthM }
        val scale = if (total > 0) poly.lengthM / total else 1.0
        var cursor = 0.0
        val out = ArrayList<ColoredRun>()
        for (seg in segments) {
            val from = (cursor * scale).coerceIn(0.0, poly.lengthM)
            cursor += seg.lengthM
            val to = (cursor * scale).coerceIn(0.0, poly.lengthM)
            if (to - from < 1.0) continue
            out.add(ColoredRun(seg.grade, poly.subLine(from, to), label))
        }
        return out
    }

    fun bounds(points: List<LatLon>): Pair<LatLon, LatLon>? {
        if (points.isEmpty()) return null
        return LatLon(points.minOf { it.lat }, points.minOf { it.lon }) to
            LatLon(points.maxOf { it.lat }, points.maxOf { it.lon })
    }
}
