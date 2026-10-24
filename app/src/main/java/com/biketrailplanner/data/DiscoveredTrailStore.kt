package com.biketrailplanner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One cached Overpass answer, keyed by the same ~1 km location bucket the saved trips use
 * ([com.biketrailplanner.domain.RouteKey.bucket]). The payload is the RAW Overpass JSON: parsing it
 * again costs milliseconds and means the cache cannot drift from the parser
 * (docs/TRAIL-DISCOVERY.md).
 *
 * The row keeps the provenance with the payload, so a cached answer can still say which mirror
 * served it and how old that mirror's OpenStreetMap data was (`timestamp_osm_base`).
 */
@Entity(
    tableName = "discovered_trail",
    indices = [Index(value = ["fetched_at"])],
)
data class DiscoveredTrailEntity(
    @PrimaryKey val bucket: String,
    @ColumnInfo(name = "origin_lat") val originLat: Double,
    @ColumnInfo(name = "origin_lon") val originLon: Double,
    /** The search radius this answer covers, so a wider request re-queries instead of under-answering. */
    @ColumnInfo(name = "radius_km") val radiusKm: Double,
    val payload: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "timestamp_osm_base") val timestampOsmBase: String?,
    val mirror: String,
)

/** The one indexed lookup the cache read uses, plus the write, the TTL sweep and the row cap. */
@Dao
interface DiscoveredTrailDao {
    /** One row per bucket: a single indexed primary-key probe, never a table scan. */
    @Query("SELECT * FROM discovered_trail WHERE bucket = :bucket")
    suspend fun get(bucket: String): DiscoveredTrailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DiscoveredTrailEntity)

    @Query("SELECT COUNT(*) FROM discovered_trail")
    suspend fun count(): Int

    /**
     * Keeps the [keep] most recently fetched buckets and drops the rest.
     *
     * There is deliberately NO delete-by-age query. The TTL decides when the app asks Overpass again;
     * it must not decide when the row disappears, because an old answer is exactly what the app needs
     * when every mirror is down (see TrailDiscovery.discover). The row cap is what bounds the table.
     */
    @Query(
        "DELETE FROM discovered_trail WHERE bucket IN (" +
            "SELECT bucket FROM discovered_trail ORDER BY fetched_at DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun trimToNewest(keep: Int)
}

/**
 * The cache the discovery layer talks to. Room-backed in the app ([RoomDiscoveryCache]), in-memory in
 * tests ([InMemoryDiscoveryCache]).
 */
interface DiscoveryCache {
    suspend fun get(bucket: String): DiscoveredTrailEntity?
    suspend fun put(row: DiscoveredTrailEntity)
    suspend fun count(): Int
}

/** The TTL and the row cap, in one place so the doc and the code cannot drift. */
object DiscoveryCachePolicy {
    /**
     * 30 days - the age at which an answer stops being authoritative and the app asks Overpass again.
     * A discovered trail needs no closure check to be useful, but it must not become a permanent claim
     * about the world either: after a month the app re-asks rather than showing a trail that may have
     * been rerouted or closed since.
     *
     * This is a **use-again** rule, not a delete rule: the row stays in the table past the TTL so that
     * a mirror outage can still be answered with the old data, aged and labelled (see
     * `DiscoveryResult.fromStaleCache`).
     */
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * At most this many buckets are kept, newest fetched first - the one thing that bounds the table.
     * One row is one raw Overpass answer: measured 102,827 bytes for the shipped 5 km radius
     * (docs/TRAIL-DISCOVERY.md), which puts 24 rows at about 2.5 MB. The cap is enforced on every
     * write, so the table can never grow without limit however old its rows are.
     */
    const val MAX_ROWS = 24
}

/**
 * Room-backed discovery cache: one indexed lookup per plan, and a row cap on every write. Rows are
 * never deleted for being old - only pushed out by newer ones - so an outage can still be answered.
 */
class RoomDiscoveryCache(
    private val dao: DiscoveredTrailDao,
    private val maxRows: Int = DiscoveryCachePolicy.MAX_ROWS,
) : DiscoveryCache {

    override suspend fun get(bucket: String): DiscoveredTrailEntity? = dao.get(bucket)

    override suspend fun put(row: DiscoveredTrailEntity) {
        dao.upsert(row)
        dao.trimToNewest(maxRows)
    }

    override suspend fun count(): Int = dao.count()
}

/** The same cache without a database, for tests and for a run with no Android stack. */
class InMemoryDiscoveryCache(
    private val maxRows: Int = DiscoveryCachePolicy.MAX_ROWS,
) : DiscoveryCache {

    private val rows = LinkedHashMap<String, DiscoveredTrailEntity>()

    override suspend fun get(bucket: String): DiscoveredTrailEntity? = rows[bucket]

    override suspend fun put(row: DiscoveredTrailEntity) {
        rows.remove(row.bucket)
        rows[row.bucket] = row
        while (rows.size > maxRows) {
            val oldest = rows.entries.minByOrNull { it.value.fetchedAt } ?: break
            rows.remove(oldest.key)
        }
    }

    override suspend fun count(): Int = rows.size
}
