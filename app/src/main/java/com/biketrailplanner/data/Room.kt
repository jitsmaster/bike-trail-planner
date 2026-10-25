package com.biketrailplanner.data

import android.content.Context
import androidx.room.migration.Migration
import java.io.File
import java.io.RandomAccessFile
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.ReusableRoute
import com.biketrailplanner.domain.RideHistoryStore
import com.biketrailplanner.domain.RideTrack
import com.biketrailplanner.domain.RideTrackCodec
import com.biketrailplanner.domain.RideTrackStore
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.domain.RouteStore
import com.biketrailplanner.domain.SavedRouteCodec
import com.biketrailplanner.domain.SavedRouteRecord

@Entity(tableName = "ride_history")
data class RideHistoryEntity(
    @PrimaryKey val signature: String,
    val trailNames: String,
    val totalMiles: Double,
    val riddenAt: Long,
)

@Entity(tableName = "route_cache")
data class RouteCacheEntity(
    @PrimaryKey val cacheKey: String,
    val payload: String,
    val cachedAt: Long,
)

@Dao
interface RideHistoryDao {
    @Query("SELECT * FROM ride_history ORDER BY riddenAt DESC")
    suspend fun all(): List<RideHistoryEntity>

    @Query("SELECT * FROM ride_history ORDER BY riddenAt DESC LIMIT 1")
    suspend fun mostRecent(): RideHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RideHistoryEntity)

    @Query("SELECT COUNT(*) FROM ride_history")
    suspend fun count(): Int

    @Query("DELETE FROM ride_history WHERE signature = :signature")
    suspend fun delete(signature: String)
}

@Dao
interface RouteCacheDao {
    @Query("SELECT * FROM route_cache WHERE cacheKey = :key")
    suspend fun get(key: String): RouteCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: RouteCacheEntity)

    @Query("DELETE FROM route_cache WHERE cachedAt < :olderThan")
    suspend fun evictOlderThan(olderThan: Long)
}

/**
 * A saved trip (docs/ROUTE-STORE.md section 2). The scalars are columns so a lookup never has to
 * decompress a payload; `payload` holds the gzipped geometry, waypoints, way ids and named segments.
 */
@Entity(
    tableName = "saved_route",
    indices = [
        Index(value = ["bucket", "target_miles"]),
        Index(value = ["last_used_at"]),
    ],
)
data class SavedRouteEntity(
    @PrimaryKey val signature: String,
    val bucket: String,
    @ColumnInfo(name = "origin_lat") val originLat: Double,
    @ColumnInfo(name = "origin_lon") val originLon: Double,
    @ColumnInfo(name = "target_miles") val targetMiles: Double,
    @ColumnInfo(name = "trail_label") val trailLabel: String,
    @ColumnInfo(name = "total_m") val totalM: Double,
    @ColumnInfo(name = "trail_round_m") val trailRoundM: Double,
    @ColumnInfo(name = "time_s") val timeS: Double,
    @ColumnInfo(name = "graded_m") val gradedM: Double,
    @ColumnInfo(name = "ungraded_m") val ungradedM: Double,
    @ColumnInfo(name = "breakdown_total_m") val breakdownTotalM: Double,
    @ColumnInfo(name = "d_m") val dM: Double,
    @ColumnInfo(name = "e_m") val eM: Double,
    @ColumnInfo(name = "road_way_count") val roadWayCount: Int,
    @ColumnInfo(name = "data_fingerprint") val dataFingerprint: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    @ColumnInfo(name = "used_count") val usedCount: Int,
    @ColumnInfo(name = "ridden_at") val riddenAt: Long?,
    val payload: ByteArray,
) {
    fun toRecord(): SavedRouteRecord = SavedRouteRecord(
        signature, bucket, originLat, originLon, targetMiles, trailLabel, totalM, trailRoundM, timeS,
        gradedM, ungradedM, breakdownTotalM, dM, eM, roadWayCount, dataFingerprint, createdAt,
        lastUsedAt, usedCount, riddenAt, payload,
    )

    companion object {
        fun of(record: SavedRouteRecord): SavedRouteEntity = SavedRouteEntity(
            record.signature, record.bucket, record.originLat, record.originLon, record.targetMiles,
            record.trailLabel, record.totalM, record.trailRoundM, record.timeS, record.gradedM,
            record.ungradedM, record.breakdownTotalM, record.dM, record.eM, record.roadWayCount,
            record.dataFingerprint, record.createdAt, record.lastUsedAt, record.usedCount,
            record.riddenAt, record.payload,
        )
    }
}

@Dao
interface SavedRouteDao {
    /** The index probe. The 250 m circle is applied by the store, not by SQL. */
    @Query("SELECT * FROM saved_route WHERE bucket = :bucket AND target_miles = :miles ORDER BY last_used_at DESC")
    suspend fun byBucket(bucket: String, miles: Double): List<SavedRouteEntity>

    /**
     * The same probe, minus the rows that cannot possibly contain a new route's way-id set: a set
     * with fewer members than the new one is not a superset of it.
     */
    @Query(
        "SELECT * FROM saved_route WHERE bucket = :bucket AND target_miles = :miles " +
            "AND road_way_count >= :minWayCount",
    )
    suspend fun byBucketAtLeastWays(bucket: String, miles: Double, minWayCount: Int): List<SavedRouteEntity>

    @Query("SELECT COUNT(*) FROM saved_route")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SavedRouteEntity)

    @Query("UPDATE saved_route SET last_used_at = :atMs, used_count = used_count + 1 WHERE signature = :signature")
    suspend fun touch(signature: String, atMs: Long)

    @Query("UPDATE saved_route SET ridden_at = :atMs WHERE signature = :signature")
    suspend fun markRidden(signature: String, atMs: Long)

    /** Keeps the [keep] most recently used rows for one request and drops the rest. */
    @Query(
        "DELETE FROM saved_route WHERE signature IN (" +
            "SELECT signature FROM saved_route WHERE bucket = :bucket AND target_miles = :miles " +
            "ORDER BY last_used_at DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun trimForRequest(bucket: String, miles: Double, keep: Int)

    /** Keeps the [keep] most recently used rows overall. */
    @Query(
        "DELETE FROM saved_route WHERE signature IN (" +
            "SELECT signature FROM saved_route ORDER BY last_used_at DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun trimOverall(keep: Int)
}

/**
 * D51: one recorded ride (docs/RIDE-RECORDER.md). Scalars are columns so the ride list never decodes
 * a payload; `payload` is the encoded polyline plus the named segments, grade breakdown and way ids.
 */
@Entity(
    tableName = "ride_track",
    indices = [Index(value = ["started_at"])],
)
data class RideTrackEntity(
    @PrimaryKey val signature: String,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtMs: Long,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "moving_ms") val movingMs: Long,
    @ColumnInfo(name = "max_speed_mps") val maxSpeedMps: Double?,
    val gaps: Int,
    @ColumnInfo(name = "rejected_fixes") val rejectedFixes: Int,
    val label: String,
    @ColumnInfo(name = "data_fingerprint") val dataFingerprint: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "graded_m") val gradedM: Double,
    @ColumnInfo(name = "ungraded_m") val ungradedM: Double,
    @ColumnInfo(name = "rejected_m") val rejectedM: Double,
    @ColumnInfo(name = "breakdown_total_m") val breakdownTotalM: Double,
    @ColumnInfo(name = "d_m") val dM: Double,
    @ColumnInfo(name = "e_m") val eM: Double,
    @ColumnInfo(name = "way_count") val wayCount: Int,
    val payload: String,
) {
    fun toTrack(currentFingerprint: String? = null): RideTrack {
        val bulky = RideTrackCodec.decode(payload)
        return RideTrack(
            signature = signature,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            distanceM = distanceM,
            movingMs = movingMs,
            maxSpeedMps = maxSpeedMps,
            gaps = gaps,
            rejectedFixes = rejectedFixes,
            points = bulky.points,
            wayIds = bulky.wayIds,
            roads = bulky.roads,
            named = bulky.named,
            gradedM = gradedM,
            ungradedM = ungradedM,
            rejectedM = rejectedM,
            breakdownTotalM = breakdownTotalM,
            byGrade = bulky.byGrade,
            safetyStale = currentFingerprint != null && currentFingerprint != dataFingerprint,
            dataFingerprint = dataFingerprint,
            label = label,
            createdAt = createdAt,
        )
    }

    companion object {
        fun of(track: RideTrack): RideTrackEntity = RideTrackEntity(
            signature = track.signature,
            startedAtMs = track.startedAtMs,
            endedAtMs = track.endedAtMs,
            distanceM = track.distanceM,
            movingMs = track.movingMs,
            maxSpeedMps = track.maxSpeedMps,
            gaps = track.gaps,
            rejectedFixes = track.rejectedFixes,
            label = track.label,
            dataFingerprint = track.dataFingerprint,
            createdAt = track.createdAt,
            gradedM = track.gradedM,
            ungradedM = track.ungradedM,
            rejectedM = track.rejectedM,
            breakdownTotalM = track.breakdownTotalM,
            dM = track.byGrade[Grade.D] ?: 0.0,
            eM = track.byGrade[Grade.E] ?: 0.0,
            wayCount = track.wayIds.size,
            payload = RideTrackCodec.encode(track),
        )
    }
}

@Dao
interface RideTrackDao {
    @Query("SELECT * FROM ride_track ORDER BY started_at DESC LIMIT :limit")
    suspend fun all(limit: Int): List<RideTrackEntity>

    @Query("SELECT COUNT(*) FROM ride_track")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RideTrackEntity)

    @Query("DELETE FROM ride_track WHERE signature = :signature")
    suspend fun delete(signature: String)

    @Query(
        "DELETE FROM ride_track WHERE signature IN (" +
            "SELECT signature FROM ride_track ORDER BY started_at DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun trim(keep: Int)
}

@Database(
    entities = [
        RideHistoryEntity::class,
        RouteCacheEntity::class,
        SavedRouteEntity::class,
        // D23: the live trail-discovery cache (docs/TRAIL-DISCOVERY.md).
        DiscoveredTrailEntity::class,
        // D51: recorded rides (docs/RIDE-RECORDER.md).
        RideTrackEntity::class,
    ],
    version = 5,
    // D63: schemas are exported from now on. Without them a migration can only be guessed at, which is
    // how a version bump came to be answered with "drop everything" instead.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideHistoryDao(): RideHistoryDao
    abstract fun routeCacheDao(): RouteCacheDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun discoveredTrailDao(): DiscoveredTrailDao
    abstract fun rideTrackDao(): RideTrackDao

    companion object {
        private const val DB_NAME = "bike-trail-planner.db"

        /** Keep in step with `@Database(version = ...)`. */
        private const val VERSION = 5

        /**
         * D63: every version bump must ship its migration here.
         *
         * v2 added `saved_route`, v3 `discovered_trail`, v4 `ride_track` - all pure additions, so an
         * `ALTER TABLE ... ADD COLUMN`/`CREATE TABLE` would have carried the older rows across. Instead
         * the database fell back to dropping everything, and the rider's recorded rides were erased by
         * an update. Shape-only changes are still not free: write the migration.
         */
        private val MIGRATIONS = emptyArray<Migration>()

        fun build(context: Context): AppDatabase {
            // D63: keep a copy of a database written by an older version BEFORE Room opens it. If a
            // migration is ever missing, the records still exist in the backup instead of being dropped.
            backupIfUpgrading(context)
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(*MIGRATIONS)
                // Downgrades only: a dev build pointing at a newer file. Upgrades must migrate.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        /**
         * Copy the database aside when the file on disk was written by an older version. Cheap on a
         * normal launch: it only reads the version from the SQLite header and does nothing when it
         * matches.
         */
        private fun backupIfUpgrading(context: Context) {
            runCatching {
                val db = context.getDatabasePath(DB_NAME)
                if (!db.exists()) return
                val onDisk = userVersion(db)
                if (onDisk == 0 || onDisk >= VERSION) return
                val backup = File(db.parentFile, "$DB_NAME.bak-v$onDisk")
                if (backup.exists()) return
                db.copyTo(backup, overwrite = false)
                // The newest rows live in the write-ahead log; a backup without it can be missing the
                // very records it exists to protect.
                File(db.path + "-wal").takeIf { it.exists() }?.copyTo(File(backup.path + "-wal"), overwrite = true)
            }
        }

        /** `PRAGMA user_version`, read straight from the SQLite header without opening the database. */
        private fun userVersion(db: File): Int = runCatching {
            RandomAccessFile(db, "r").use { raf ->
                raf.seek(60)
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            }
        }.getOrDefault(0)
    }
}

/** Room-backed saved trips. One row + one gunzip per hit; no full-table scans (docs/ROUTE-STORE.md). */
class RoomRouteStore(private val dao: SavedRouteDao) : RouteStore {

    override suspend fun count(): Int = dao.count()

    override suspend fun matching(origin: LatLon, targetMiles: Double, currentFingerprint: String?): List<ReusableRoute> =
        dao.byBucket(RouteKey.bucket(origin), targetMiles)
            .filter { Geo.distanceM(LatLon(it.originLat, it.originLon), origin) <= RouteKey.ORIGIN_MATCH_M }
            .map { SavedRouteCodec.decode(it.toRecord(), currentFingerprint) }

    override suspend fun overlapContext(origin: LatLon, targetMiles: Double, newWayCount: Int): List<Set<Long>> =
        dao.byBucketAtLeastWays(RouteKey.bucket(origin), targetMiles, newWayCount)
            .filter { Geo.distanceM(LatLon(it.originLat, it.originLon), origin) <= RouteKey.ORIGIN_MATCH_M }
            .map { SavedRouteCodec.decode(it.toRecord()).wayIds }

    override suspend fun save(
        route: PlannedRoute,
        origin: LatLon,
        targetMiles: Double,
        waypoints: List<LatLon>,
        fingerprint: String,
        nowMs: Long,
        perKeyCap: Int,
        globalCap: Int,
    ) {
        val record = SavedRouteCodec.encode(route, origin, targetMiles, waypoints, fingerprint, nowMs)
        val previous = dao.byBucket(record.bucket, targetMiles).firstOrNull { it.signature == record.signature }
        dao.upsert(
            SavedRouteEntity.of(
                if (previous == null) {
                    record
                } else {
                    // re-saving the same ride keeps when it was first planned and how often it was used
                    record.copy(
                        createdAt = previous.createdAt,
                        lastUsedAt = previous.lastUsedAt,
                        usedCount = previous.usedCount,
                        riddenAt = previous.riddenAt,
                    )
                },
            ),
        )
        dao.trimForRequest(record.bucket, targetMiles, perKeyCap)
        dao.trimOverall(globalCap)
    }

    override suspend fun markUsed(signature: String, atMs: Long) = dao.touch(signature, atMs)

    override suspend fun markRidden(signature: String, atMs: Long) = dao.markRidden(signature, atMs)
}

class RoomRouteCacheStore(private val dao: RouteCacheDao) : RouteCacheStore {
    override suspend fun get(key: String): String? = dao.get(key)?.payload

    override suspend fun put(key: String, payload: String) {
        dao.put(RouteCacheEntity(key, payload, System.currentTimeMillis()))
        dao.evictOlderThan(System.currentTimeMillis() - CACHE_TTL_MS)
    }

    private companion object {
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

class RoomRideHistory(private val dao: RideHistoryDao) : RideHistoryStore {
    override suspend fun lastRiddenSignature(): String? = dao.mostRecent()?.signature

    override suspend fun riddenAt(): Map<String, Long> = dao.all().associate { it.signature to it.riddenAt }

    override suspend fun record(signature: String, trailNames: List<String>, totalMiles: Double, atEpochMs: Long) {
        dao.upsert(RideHistoryEntity(signature, trailNames.joinToString(" + "), totalMiles, atEpochMs))
    }

    override suspend fun count(): Int = dao.count()

    override suspend fun forget(signature: String) = dao.delete(signature)
}

/** D51: recorded rides, one row + one payload decode per opened row (docs/RIDE-RECORDER.md). */
class RoomRideTrackStore(
    private val dao: RideTrackDao,
    private val currentFingerprint: String? = null,
) : RideTrackStore {

    override suspend fun count(): Int = dao.count()

    override suspend fun all(limit: Int): List<RideTrack> =
        dao.all(limit).map { it.toTrack(currentFingerprint) }

    override suspend fun save(track: RideTrack) {
        dao.upsert(RideTrackEntity.of(track))
        dao.trim(RideTrackStore.GLOBAL_CAP)
    }

    override suspend fun delete(signature: String) = dao.delete(signature)
}
