package com.biketrailplanner.data

import com.biketrailplanner.domain.ConfidenceNames
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.TrafficConfidence
import com.biketrailplanner.domain.TrafficCount
import com.biketrailplanner.domain.TrafficTable
import com.biketrailplanner.domain.VolumeBand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Loads the measured traffic-volume table (assets/traffic.json).
 *
 * The file is written by the traffic-data producer, so this loader accepts several spellings
 * of the same field instead of one rigid schema, and it accepts either an object with a
 * `roads` array or a bare array. A missing file is not an error: the table is simply empty
 * and every segment falls through to OSM and then to UNGRADED (SPEC section 6b).
 */
object TrafficLoader {

    fun load(json: Json, text: String?): List<TrafficCount> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject -> (root["roads"] ?: root["traffic"] ?: root["entries"])?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            parse(o)
        }
    }

    private fun parse(o: JsonObject): TrafficCount? {
        val wayIds = o.longList("osm_way_ids", "osm_way_id", "way_ids", "way_id", "osm_ids")
        val declaredName = o.string("road_name", "name", "osm_name", "street", "fullname", "road")
        // A row keyed only by OSM way id is still useful: match by id and label it by id.
        val name = declaredName ?: wayIds.firstOrNull()?.let { "way $it" } ?: return null
        val rawAadt = o.int("aadt", "aadt_vpd", "adtvolume", "volume", "volume_vpd", "vpd")
        val year = o.int("aadt_year", "adtyear", "year", "count_year", "reporting_year")
        val declaredConfidence = o.string("aadt_confidence", "confidence", "count_confidence")
        val lts = o.int("lts", "lts_bicycle", "lts_general", "lts_min")
        val declaredBand = o.string("volume_band", "band")

        // SPEC section 6b: a count of 0 means "Not Known" (Pierce County uses 0 that way).
        val aadt = rawAadt?.takeIf { it > 0 }
        val zeroMeansUnknown = rawAadt != null && rawAadt <= 0
        val confidence = when {
            aadt == null -> TrafficConfidence.UNKNOWN
            declaredConfidence != null -> ConfidenceNames.parse(declaredConfidence)
            else -> TrafficConfidence.MEASURED
        }
        val band = when {
            aadt == null -> VolumeBand.UNKNOWN
            declaredBand != null -> ConfidenceNames.band(declaredBand)
            else -> TrafficTable.bandFor(aadt)
        }
        val names = o.stringList("osm_names", "names", "aliases", "match_names")
            .ifEmpty { listOfNotNull(declaredName) }
        val note = o.string("note", "comment")
        return TrafficCount(
            roadName = name,
            source = o.string("source", "dataset", "provenance") ?: "unspecified",
            aadt = aadt,
            aadtYear = year,
            confidence = confidence,
            lts = lts,
            volumeBand = band,
            wayIds = wayIds,
            osmNames = names,
            polyline = o.polyline(),
            note = if (zeroMeansUnknown) {
                (note?.plus("; ") ?: "") + "published AADT is 0, which means Not Known - never treated as zero traffic"
            } else {
                note
            },
        )
    }

    // --------------------------------------------------------------- tolerant field readers

    private fun JsonObject.string(vararg keys: String): String? = primitive(*keys)?.takeIf { it.isString }?.content

    private fun JsonObject.int(vararg keys: String): Int? =
        primitive(*keys)?.content?.let { it.toDoubleOrNull()?.toInt() }

    private fun JsonObject.primitive(vararg keys: String): JsonPrimitive? {
        for (k in keys) {
            val v = this[k] ?: continue
            if (v is JsonPrimitive) return v
        }
        return null
    }

    private fun JsonObject.stringList(vararg keys: String): List<String> {
        for (k in keys) {
            val v = this[k] ?: continue
            if (v is JsonArray) return v.mapNotNull { (it as? JsonPrimitive)?.content }
        }
        return emptyList()
    }

    private fun JsonObject.longList(vararg keys: String): List<Long> {
        for (k in keys) {
            val v = this[k] ?: continue
            when (v) {
                is JsonArray -> return v.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                is JsonPrimitive -> v.content.toLongOrNull()?.let { return listOf(it) }
                else -> Unit
            }
        }
        return emptyList()
    }

    /** A simplified polyline in any of the shapes the producer may use. */
    private fun JsonObject.polyline(): Polyline? {
        val value = this["polyline"] ?: this["geometry"] ?: this["coords"] ?: this["coordinates"] ?: return null
        val points = when (value) {
            is JsonArray -> value.mapNotNull { element ->
                when (element) {
                    is JsonArray -> {
                        val nums = element.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                        if (nums.size >= 2) LatLon(nums[0], nums[1]) else null
                    }
                    is JsonObject -> {
                        val lat = element["lat"]?.jsonPrimitiveOrNull()?.toDoubleOrNull()
                        val lon = element["lon"]?.jsonPrimitiveOrNull()?.toDoubleOrNull()
                        if (lat != null && lon != null) LatLon(lat, lon) else null
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }
        return if (points.size >= 2) Polyline(points) else null
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? JsonPrimitive)?.content
}
