package com.biketrailplanner.domain

/**
 * D64: one road the rider actually rode, in the order it was ridden.
 *
 * The rider's instruction was *"the only thing you need to record are edges (roads)"*, and this is
 * that record: the ordered list of OSM ways a recorded ride matched, with the distance at which each
 * one starts. It is produced by the same `trace_attributes` call that already grades a ride
 * (`RideTrackGrader`), so it costs no extra network call and needs no extra server.
 *
 * Why a list of roads rather than of GPS points:
 *  - a point list says "you were here", a road list says **what you rode** - the thing the rider
 *    names a trip by, and the thing a second ride can be compared against;
 *  - stops for Google Maps are chosen at **junctions between roads**, and a junction is only knowable
 *    from the road list. Choosing them from raw points (evenly spaced, as `GoogleMapsLink` used to do)
 *    puts stops in the middle of a block instead of on the turns;
 *  - it is a fraction of the size of the fix list, so it survives every storage decision cheaply.
 *
 * The fixes are still kept (the map draws them, and D59/D60 answer taps from them). This is the
 * *identity* of the trip, not a replacement for its geometry.
 */
data class RideRoad(
    /** OSM way id. 0 when the trace matched geometry but had no way id to give. */
    val wayId: Long,
    /** The road or trail name as OSM carries it; "(unnamed)" when it carries none. */
    val name: String,
    val roadClass: String? = null,
    /** Valhalla's `use`, e.g. `road`, `cycleway`, `driveway`. */
    val use: String? = null,
    /** Metres from the start of the ride to the first metre of this road. */
    val startM: Double,
    val lengthM: Double,
) {
    val endM: Double get() = startM + lengthM

    /** A separated path rather than a road with traffic - the part of a ride worth naming. */
    val isSeparatedPath: Boolean
        get() = use == "cycleway" || use == "path" || use == "footway" || use == "pedestrian"

    /** True when the road has a name a rider would recognise (not the placeholders). */
    val isNamed: Boolean get() = name.isNotBlank() && !name.startsWith("(")
}

/** A place a ride is worth stopping at when it is handed to Google Maps (D64). */
data class RideStop(
    val point: LatLon,
    /** Metres from the start of the ride. */
    val atM: Double,
    /** How far the road turns here, in degrees (0 = straight on). */
    val turnDeg: Double,
    /** Why this stop was chosen - shown to the rider, so the choice is never a mystery. */
    val why: String,
    val priority: Int,
)
