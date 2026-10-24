package com.biketrailplanner.data

import com.biketrailplanner.domain.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * D60 (docs/UI.md section 3.6): the postal address of one point, for the rider who taps a recorded
 * ride and asks "where is this?".
 *
 * The app has no geocoder and Valhalla is not one, so this is OpenStreetMap's own Nominatim. That is a
 * **third party**, and it means sending the rider's exact coordinates to somebody else's server - the
 * same class of disclosure as the public routing fallback (SEC-7). So it is behind the rider's EXISTING
 * switch, `allowPublicFallback`: whoever has not agreed to send their coordinates to a public router is
 * not silently sending them to a public geocoder either. With the switch off, [reverse] does nothing
 * and the screen shows the street name the app already knows instead.
 *
 * Everything here is best-effort. No address is never an error: the map, the street names and the ride
 * itself all work without it.
 */
class AddressLookup(
    private val allowPublicLookup: Boolean,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** Why there is no address, so the screen can say something true instead of nothing. */
    enum class Refusal { NOT_ALLOWED }

    private val cache = LinkedHashMap<String, String?>()

    /**
     * The address of [point], or null when there is none to show. [Refusal] is returned only when the
     * caller is not allowed to ask at all, which is a different thing from "asked and got nothing".
     */
    suspend fun reverse(point: LatLon): Result<String?> {
        if (!allowPublicLookup) return Result.failure(IllegalStateException(Refusal.NOT_ALLOWED.name))
        val key = "%.5f,%.5f".format(point.lat, point.lon)
        cache[key]?.let { return Result.success(it) }
        if (cache.containsKey(key)) return Result.success(null)

        val address = runCatching {
            withContext(Dispatchers.IO) { request(key) }
        }.getOrNull()
        if (cache.size > CACHE_LIMIT) cache.clear()
        cache[key] = address
        return Result.success(address)
    }

    private fun request(key: String): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=${key.substringBefore(',')}&lon=${key.substringAfter(',')}"
        val request = Request.Builder()
            .url(url)
            // Nominatim's usage policy requires a real User-Agent that identifies the app.
            .header("User-Agent", "BikeTrailPlanner/0.1 (Android; recorded-ride map)")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val name = root["display_name"]?.jsonPrimitive?.content?.trim()
            return name?.takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        const val CACHE_LIMIT = 64
    }
}
