package com.biketrailplanner.data

import com.biketrailplanner.domain.SpeedOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Loads the local override table (assets/limits.json).
 *
 * The traffic-data producer writes `roads` as an OBJECT keyed by street name; the first draft of
 * this table used an array of records. Both are accepted, as are the different spellings of the
 * speed field. Every entry ships with a null speed on purpose: the posted limits of these roads
 * are not known, and guessing them would turn an assumption into a false safety claim.
 */
object LimitsLoader {

    /** Everything the table carries, including the producer's own flags about suspicious rows. */
    data class Table(val rows: List<SpeedOverride>, val flags: List<String>)

    fun load(json: Json, text: String?): Table {
        if (text.isNullOrBlank()) return Table(emptyList(), emptyList())
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return Table(emptyList(), emptyList())
        return Table(rows(json, root), flags(root))
    }

    /** The producer's `_flags` block: shown to the rider, never suppressed. */
    fun flags(root: JsonObject): List<String> {
        val flags = root["_flags"] ?: return emptyList()
        return when (flags) {
            is JsonObject -> flags.entries.map { (k, v) -> k.replace('_', ' ') + ": " + ((v as? JsonPrimitive)?.content ?: "") }
            is JsonArray -> flags.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
    }

    private fun rows(json: Json, root: JsonObject): List<SpeedOverride> {
        val roads = root["roads"] ?: return emptyList()
        return when (roads) {
            is JsonArray -> roads.mapNotNull { element ->
                val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                record(
                    name = o.str("name", "road_name", "street"),
                    speedMph = o.int("speed_mph", "posted_speed_mph", "posted_limit_mph", "speed"),
                    wayId = o.long("osm_way_id", "way_id"),
                    aadt = o.int("aadt"),
                    year = o.int("aadt_year", "year"),
                    confidence = o.str("aadt_confidence", "confidence"),
                    lts = o.int("lts"),
                    source = o.str("source"),
                )
            }
            is JsonObject -> roads.entries.mapNotNull { (key, value) ->
                val o = runCatching { value.jsonObject }.getOrNull() ?: return@mapNotNull null
                record(
                    name = o.str("road_name", "name") ?: key,
                    speedMph = o.int("posted_speed_mph", "speed_mph", "speed"),
                    wayId = o.long("osm_way_id", "way_id"),
                    aadt = o.int("aadt"),
                    year = o.int("aadt_year", "year"),
                    confidence = o.str("aadt_confidence", "confidence"),
                    lts = o.int("lts"),
                    source = o.str("source"),
                )
            }
            else -> emptyList()
        }
    }

    private fun record(
        name: String?,
        speedMph: Int?,
        wayId: Long?,
        aadt: Int?,
        year: Int?,
        confidence: String?,
        lts: Int?,
        source: String?,
    ) = SpeedOverride(
        osmWayId = wayId,
        name = name,
        speedMph = speedMph?.takeIf { it > 0 },
        aadt = aadt,
        aadtYear = year,
        aadtConfidence = confidence,
        lts = lts,
        source = source,
    )

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] ?: continue
            if (v is JsonPrimitive) return v.content
        }
        return null
    }

    private fun JsonObject.int(vararg keys: String): Int? = str(*keys)?.toDoubleOrNull()?.toInt()

    private fun JsonObject.long(vararg keys: String): Long? = str(*keys)?.toDoubleOrNull()?.toLong()
}
