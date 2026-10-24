package com.biketrailplanner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * SPEC section 7b item 4, kept as DATA HYGIENE only (D15): the Sumner Link closure is recorded as
 * permanent (the middle 2.37 km is cut off by private property), so the app never promises that a
 * segment will reopen and never silently unlocks one. This just re-reads the current tags and the
 * version history of the closed ways so the rider can see whether OSM still agrees with the record.
 *
 * Cheap: two GETs per closed way, no tile rebuild, no Valhalla involvement. A failure leaves the
 * closure in place, which is the safe direction.
 */
class OsmClosureChecker(
    private val http: OkHttpClient = ValhallaClient.defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    data class WayState(
        val wayId: Long,
        val reachable: Boolean,
        val tags: Map<String, String> = emptyMap(),
        val version: Int? = null,
        val timestamp: String? = null,
        val historyVersions: Int? = null,
        val error: String? = null,
    ) {
        val summary: String
            get() = when {
                error != null -> "could not check way $wayId: $error"
                reopened -> "way $wayId no longer carries bicycle=no / access=no in OSM " +
                    "(version $version, $timestamp; $historyVersions versions in history). " +
                    "The closure is recorded as permanent (D15), so the plan still routes around it. " +
                    "Only a human should change that record."
                else -> "way $wayId is still closed in OSM (version $version, $timestamp): " +
                    tags.entries.joinToString(", ") { "${it.key}=${it.value}" }
            }

        /** True only when the data proves the way is no longer excluded for bicycles. */
        val usable: Boolean
            get() = reachable && error == null && tags.isNotEmpty() && tags["bicycle"] != "no" && tags["access"] != "no"

        val reopened: Boolean get() = usable
    }

    suspend fun check(wayIds: List<Long>): List<WayState> = withContext(Dispatchers.IO) {
        wayIds.distinct().map { id ->
            try {
                val current = get(wayJsonUrl(id))
                val element = parseElement(current)
                val tags = parseTags(current)
                val history = runCatching { get(historyUrl(id)) }.getOrNull()
                WayState(
                    wayId = id,
                    reachable = true,
                    tags = tags,
                    version = element?.get("version")?.jsonPrimitive?.content?.toIntOrNull(),
                    timestamp = element?.get("timestamp")?.jsonPrimitive?.content,
                    historyVersions = history?.let { parseHistoryVersions(it) },
                )
            } catch (e: Exception) {
                WayState(wayId = id, reachable = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun get(url: String): JsonObject {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    /** The single way element the OSM API returns for `way/<id>.json`. */
    internal fun parseElement(wayJson: JsonObject): JsonObject? =
        wayJson["elements"]?.jsonArray?.firstOrNull()?.jsonObject

    internal fun parseTags(wayJson: JsonObject): Map<String, String> {
        val tags = parseElement(wayJson)?.get("tags") ?: return emptyMap()
        return (tags as? JsonObject)?.entries?.associate { it.key to it.value.jsonPrimitive.content } ?: emptyMap()
    }

    internal fun parseHistoryVersions(historyJson: JsonObject): Int? =
        (historyJson["elements"] as? JsonArray)?.size

    companion object {
        private const val USER_AGENT = "bike-trail-planner/0.1 (personal Android app; trail tag check)"
        fun wayJsonUrl(wayId: Long) = "https://api.openstreetmap.org/api/0.6/way/$wayId.json"
        fun historyUrl(wayId: Long) = "https://api.openstreetmap.org/api/0.6/way/$wayId/history.json"
    }
}
