package com.biketrailplanner.domain

/** One row of the bundled local override table (assets/limits.json). */
data class SpeedOverride(
    val osmWayId: Long?,
    val name: String?,
    val speedMph: Int?,
    val aadt: Int? = null,
    val lanes: Int? = null,
    val source: String? = null,
    val aadtYear: Int? = null,
    val aadtConfidence: String? = null,
    val lts: Int? = null,
)

/**
 * The bundled override table, merged over OpenStreetMap values.
 *
 * SPEC section 6: an override wins for the way it names. A segment with neither an
 * override nor an OSM maxspeed is UNGRADED and is never reported as safe.
 */
class LimitsTable(val overrides: List<SpeedOverride>) {

    private val byWayId: Map<Long, SpeedOverride> =
        overrides.filter { it.osmWayId != null && it.speedMph != null }.associateBy { it.osmWayId!! }

    private val byName: Map<String, SpeedOverride> =
        overrides.filter { it.name != null && it.speedMph != null }
            .associateBy { StreetNames.normalise(it.name!!) }

    private val rowsByName: Map<String, SpeedOverride> =
        overrides.filter { it.name != null }.associateBy { StreetNames.normalise(it.name!!) }

    private val rowsByWayId: Map<Long, SpeedOverride> =
        overrides.filter { it.osmWayId != null }.associateBy { it.osmWayId!! }

    /** How many posted limits the rider has actually filled in. */
    val size: Int get() = byWayId.size + byName.size

    /** Every row in the table, including the ones whose speed is still unknown. */
    val rows: List<SpeedOverride> get() = overrides

    /** The row for a road, whether or not a speed has been filled in. */
    fun row(wayIds: List<Long> = emptyList(), names: List<String> = emptyList()): SpeedOverride? {
        for (id in wayIds) rowsByWayId[id]?.let { return it }
        for (n in names) rowsByName[StreetNames.normalise(n)]?.let { return it }
        return null
    }

    fun override(wayIds: List<Long>, names: List<String>): SpeedOverride? {
        for (id in wayIds) byWayId[id]?.let { return it }
        for (n in names) byName[StreetNames.normalise(n)]?.let { return it }
        return null
    }

    companion object {
        val EMPTY = LimitsTable(emptyList())
    }
}
