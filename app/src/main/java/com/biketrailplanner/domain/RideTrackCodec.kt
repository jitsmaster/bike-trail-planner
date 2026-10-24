package com.biketrailplanner.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * D51: the bulky half of a [RideTrack] (geometry, named segments, grade breakdown) as one JSON string.
 *
 * The scalars live in Room columns so a list query never decodes a payload; only the rows the rider
 * actually opens are decoded. The shape is an encoded polyline (about 3 bytes a point), so a long
 * recorded ride is a few KB, not a photo.
 */
object RideTrackCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Bulky(
        val points: List<LatLon>,
        val named: List<NamedSegment>,
        val byGrade: Map<Grade, Double>,
        val wayIds: Set<Long>,
        /** Metres the ladder refused. 0.0 for a payload written before this was carried. */
        val rejectedM: Double = 0.0,
    )

    fun encode(track: RideTrack): String = buildJsonObject {
        put("points", PolylineCodec.encode(track.points))
        putJsonArray("wayIds") { track.wayIds.sorted().forEach { add(JsonPrimitive(it)) } }
        putJsonArray("named") {
            track.named.forEach { n ->
                addJsonObject {
                    put("name", n.name)
                    put("metres", n.metres)
                    put("grade", n.grade.name)
                    n.roadClass?.let { put("roadClass", it) }
                    put("speedText", n.speedText)
                    n.volumeText?.let { put("volumeText", it) }
                    put("volumeUsable", n.volumeUsable)
                    n.lts?.let { put("lts", it) }
                    n.trafficNote?.let { put("trafficNote", it) }
                }
            }
        }
        putJsonObject("byGrade") {
            track.byGrade.forEach { (grade, metres) -> if (metres > 0.0) put(grade.name, metres) }
        }
        put("rejectedM", track.rejectedM)
    }.toString()

    fun decode(payload: String): Bulky {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return Bulky(emptyList(), emptyList(), emptyMap(), emptySet())
        val points = root["points"]?.jsonPrimitive?.content?.let { runCatching { PolylineCodec.decode(it) }.getOrNull() }
            ?: emptyList()
        val named = root["named"]?.jsonArray?.mapNotNull { element ->
            runCatching {
                val n = element.jsonObject
                NamedSegment(
                    name = n["name"]!!.jsonPrimitive.content,
                    metres = n["metres"]!!.jsonPrimitive.content.toDouble(),
                    grade = Grade.valueOf(n["grade"]!!.jsonPrimitive.content),
                    roadClass = n["roadClass"]?.jsonPrimitive?.content,
                    speedText = n["speedText"]?.jsonPrimitive?.content ?: "",
                    volumeText = n["volumeText"]?.jsonPrimitive?.content,
                    volumeUsable = n["volumeUsable"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    lts = n["lts"]?.jsonPrimitive?.content?.toIntOrNull(),
                    trafficNote = n["trafficNote"]?.jsonPrimitive?.content,
                )
            }.getOrNull()
        } ?: emptyList()
        val byGrade = root["byGrade"]?.jsonObject?.mapNotNull { (key, value) ->
            runCatching { Grade.valueOf(key) to value.jsonPrimitive.content.toDouble() }.getOrNull()
        }?.toMap() ?: emptyMap()
        val wayIds = root["wayIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }?.toSet()
            ?: emptySet()
        val rejectedM = root["rejectedM"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        return Bulky(points, named, byGrade, wayIds, rejectedM)
    }
}
