package com.biketrailplanner.data

import android.content.Context
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.domain.StrategyReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

/**
 * The cache of model replies, keyed by the REQUEST (docs/AI-PLANNING.md sections 5 and 7).
 *
 * The rider asked for "no tokens replanning the same routes": planning the same request a second
 * time must be a lookup, not a network call. So a reply is stored under [StrategyCacheKey] and a hit
 * means [DeepSeekClient] is never called.
 *
 * It is deliberately tiny and self-contained: a handful of small JSON replies in the same
 * SharedPreferences the app's other settings live in. It is NOT a Room table, because the saved-trip
 * table (D26) already owns the "same request, no work" story for whole routes; this only covers the
 * few hundred bytes the model said about a request it could not answer with a saved trip.
 *
 * Nothing here stores a coordinate: the key is a hash (the same rule as the route cache, SEC-10).
 */
interface StrategyCache {

    /** The reply stored for [key], or null. A hit means the model must not be called again. */
    suspend fun get(key: String): StrategyReply?

    /** Stores [key] -> [reply], evicting the least recently used entry past [CAP]. */
    suspend fun put(key: String, reply: StrategyReply)

    /** How many replies are held. Used by the tests and by nothing on the screens. */
    suspend fun size(): Int

    fun clear()

    companion object {
        /** ~20 replies: enough for a season of "from here, this far", still a tiny file. */
        const val CAP = 20
    }
}

/**
 * The request key: a hash of the origin BUCKET and the target mileage, reusing the bucketing rule the
 * saved-trip store already uses ([RouteKey.bucket], ~1 km), so a few metres of GPS jitter cannot buy
 * a second model call for the same ride. No coordinates are stored, only the hash.
 */
object StrategyCacheKey {

    /** The key for a request that already knows its origin bucket. */
    fun of(bucket: String, targetMiles: Double): String =
        hash(bucket + "|" + String.format(Locale.US, "%.1f", targetMiles))

    /** The key for a request given as a position and a mileage. */
    fun of(origin: LatLon, targetMiles: Double): String = of(RouteKey.bucket(origin), targetMiles)

    private fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
}

/** One stored reply, with the time it was last used so the oldest entry can be evicted. */
@Serializable
data class StrategyCacheEntry(val key: String, val usedAt: Long, val reply: StrategyReply)

/** The file layout, so the cap and the eviction are visible in one place. */
@Serializable
data class StrategyCacheFile(val entries: List<StrategyCacheEntry> = emptyList())

/** The cache a test uses: same cap, same eviction, no disk. */
class InMemoryStrategyCache(
    private val cap: Int = StrategyCache.CAP,
    private val now: () -> Long = { System.currentTimeMillis() },
) : StrategyCache {

    private val entries = LinkedHashMap<String, StrategyCacheEntry>()
    private val lock = Mutex()

    override suspend fun get(key: String): StrategyReply? = lock.withLock {
        val hit = entries[key] ?: return@withLock null
        entries[key] = hit.copy(usedAt = now())
        hit.reply
    }

    override suspend fun put(key: String, reply: StrategyReply) = lock.withLock {
        entries[key] = StrategyCacheEntry(key, now(), reply)
        evict()
    }

    override suspend fun size(): Int = lock.withLock { entries.size }

    override fun clear() {
        entries.clear()
    }

    private fun evict() {
        if (entries.size <= cap) return
        // Keep the [cap] most recently used; the rest go, oldest first.
        entries.entries.sortedByDescending { it.value.usedAt }.drop(cap).forEach { entries.remove(it.key) }
    }
}

/**
 * The real cache: one SharedPreferences entry holding the whole (capped) file as JSON.
 *
 * A read or write that fails is not a failure of the plan: the cache is an optimisation, so a
 * corrupt entry is treated as a miss and dropped writes are ignored.
 */
class PrefsStrategyCache(
    context: Context,
    private val cap: Int = StrategyCache.CAP,
    private val now: () -> Long = { System.currentTimeMillis() },
) : StrategyCache {

    private val prefs = context.applicationContext
        .getSharedPreferences("bike-trail-planner", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(key: String): StrategyReply? = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = read()
            val hit = file.entries.firstOrNull { it.key == key } ?: return@withContext null
            // Reading counts as use, so the entry the rider actually plans with is kept.
            val refreshed = file.entries.map { if (it.key == key) it.copy(usedAt = now()) else it }
            write(StrategyCacheFile(refreshed))
            hit.reply
        }
    }

    override suspend fun put(key: String, reply: StrategyReply) = lock.withLock {
        withContext(Dispatchers.IO) {
            val kept = read().entries.filterNot { it.key == key }
            val next = (kept + StrategyCacheEntry(key, now(), reply))
                .sortedByDescending { it.usedAt }
                .take(cap)
            write(StrategyCacheFile(next))
        }
    }

    override suspend fun size(): Int = lock.withLock { withContext(Dispatchers.IO) { read().entries.size } }

    override fun clear() {
        prefs.edit().remove(KEY_CACHE).apply()
    }

    private fun read(): StrategyCacheFile {
        val raw = prefs.getString(KEY_CACHE, null) ?: return StrategyCacheFile()
        return runCatching { json.decodeFromString(StrategyCacheFile.serializer(), raw) }
            .getOrElse { StrategyCacheFile() }
    }

    private fun write(file: StrategyCacheFile) {
        runCatching { prefs.edit().putString(KEY_CACHE, json.encodeToString(StrategyCacheFile.serializer(), file)).apply() }
    }

    companion object {
        /** Where the file lives inside the app's own settings. */
        const val KEY_CACHE = "ai_strategy_cache"
    }
}
