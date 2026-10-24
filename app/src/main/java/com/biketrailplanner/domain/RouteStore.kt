package com.biketrailplanner.domain

import java.security.MessageDigest

/**
 * One saved trip exactly as it sits in the database (docs/ROUTE-STORE.md section 2).
 *
 * The scalars are columns so they can be queried without touching [payload]; [payload] is the
 * gzipped JSON produced by [SavedRouteCodec] and holds the geometry, the hand-off waypoints, the
 * road way ids and the named segments.
 */
data class SavedRouteRecord(
    val signature: String,
    /** Origin rounded to 2 decimal places (~1 km). The index key, never the matching rule. */
    val bucket: String,
    val originLat: Double,
    val originLon: Double,
    val targetMiles: Double,
    val trailLabel: String,
    val totalM: Double,
    val trailRoundM: Double,
    val timeS: Double,
    val gradedM: Double,
    val ungradedM: Double,
    /** The graded-metre total (graded + ungraded + rejected). The share denominator. */
    val breakdownTotalM: Double,
    val dM: Double,
    val eM: Double,
    /** Guard: a new route with more road ways than this can never be a subset of it. */
    val roadWayCount: Int,
    /** Hash of the safety data this route was graded with (docs/ROUTE-STORE.md section 3.4). */
    val dataFingerprint: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val usedCount: Int,
    /** When the rider actually rode it, or null. Drives the D6 rotation. */
    val riddenAt: Long?,
    val payload: ByteArray,
) {
    // ByteArray is compared by reference by the generated equals; the signature is the identity.
    override fun equals(other: Any?): Boolean = other is SavedRouteRecord && other.signature == signature
    override fun hashCode(): Int = signature.hashCode()
}

/**
 * A saved trip decoded and ready to be shown, handed off, or tested for overlap. Everything the
 * rider-facing screens need, and nothing that can be re-derived (docs/ROUTE-STORE.md section 1).
 */
data class ReusableRoute(
    val signature: String,
    val trailLabel: String,
    val trailNames: List<String>,
    /** The whole round trip, decoded from the saved polyline. */
    val points: List<LatLon>,
    /** The Google Maps hand-off stops (max 9), saved so the URL needs no re-planning. */
    val waypoints: List<LatLon>,
    /** OSM way ids of the road legs: the identity the overlap rule uses. */
    val wayIds: Set<Long>,
    val named: List<NamedSegment>,
    val targetMiles: Double,
    val totalM: Double,
    val trailRoundM: Double,
    val timeS: Double,
    val gradedM: Double,
    val ungradedM: Double,
    val breakdownTotalM: Double,
    val byGrade: Map<Grade, Double>,
    val accessNode: Long,
    val turnaround: LatLon,
    val createdAt: Long,
    val lastUsedAt: Long,
    val usedCount: Int,
    val riddenAt: Long?,
    /**
     * True when the safety data changed since this route was graded. The geometry is still good, so
     * reusing it saves the AI call and the routing calls, but the verdict must be recomputed locally
     * before it is offered as ready to ride.
     */
    val safetyStale: Boolean,
) {
    val totalMiles: Double get() = Geo.metresToMiles(totalM)
    val trailMiles: Double get() = Geo.metresToMiles(trailRoundM)
    // The shares are of the GRADED metre total, exactly as GradeBreakdown reports them, so the
    // saved trip says the same "70% of this ride is measured" the live plan said.
    val ungradedShare: Double get() = if (breakdownTotalM <= 0.0) 0.0 else ungradedM / breakdownTotalM
    val measuredShare: Double get() = if (breakdownTotalM <= 0.0) 0.0 else gradedM / breakdownTotalM
}

/** The request key: a coarse bucket for the index, and an exact circle for the rule. */
object RouteKey {
    /** A saved trip only counts as "from here" when its origin is this close to the request's. */
    const val ORIGIN_MATCH_M = 250.0

    /** ~1 km at this latitude. Only a filter: the 250 m circle below is what decides. */
    fun bucket(origin: LatLon): String =
        String.format(java.util.Locale.US, "%.2f,%.2f", origin.lat, origin.lon)
}

/**
 * The overlap rule (docs/ROUTE-STORE.md section 3.3): a new trip may share roads with saved ones,
 * but it may not be 100% of one of them - it has to add at least one road the rider has not ridden
 * on that saved trip.
 */
object RouteOverlap {

    /** True when [newWayIds] is entirely contained in one of the saved sets: a pure repeat. */
    fun fullyRepeats(newWayIds: Set<Long>, saved: Collection<Set<Long>>): Boolean =
        newWayIds.isNotEmpty() && saved.any { it.isNotEmpty() && it.containsAll(newWayIds) }

    /**
     * How much of the new trip is road the MOST SIMILAR saved trip does not use: 0.0 means a pure
     * repeat, 1.0 means nothing in common. Worst case on purpose - a ride is only as new as its
     * least novel comparison.
     */
    fun noveltyVsMostSimilar(newWayIds: Set<Long>, saved: Collection<Set<Long>>): Double {
        if (newWayIds.isEmpty()) return 0.0
        val comparable = saved.filter { it.isNotEmpty() }
        if (comparable.isEmpty()) return 1.0
        return comparable.minOf { old ->
            newWayIds.count { it !in old }.toDouble() / newWayIds.size
        }
    }

    /** Convenience for the planner: keep only the candidates that are not a pure repeat. */
    fun <T> keepNovel(candidates: List<T>, saved: Collection<Set<Long>>, wayIdsOf: (T) -> Set<Long>): List<T> =
        candidates.filterNot { fullyRepeats(wayIdsOf(it), saved) }
}

/**
 * The reuse rule the rider asked for: "If there are always more than 2 saved trips, planning should
 * suggest them first. Otherwise, plan a new route."
 */
object PlanPolicy {
    /** "more than 2 saved trips" */
    const val SUGGEST_THRESHOLD = 2

    /** How many saved trips the screen offers at once (the rider's request, 2026-09-16). */
    const val DEFAULT_MAX_SUGGESTIONS = 5

    sealed interface Decision {
        /** Offer these saved trips. No Valhalla call and no AI call is needed to plan them. */
        data class Suggest(val routes: List<ReusableRoute>) : Decision

        /** Nothing reusable: plan a new route (AI + Valhalla), then save it. */
        data object PlanNew : Decision
    }

    /**
     * The count is of trips saved **for this request** - the same start area and the same mileage -
     * not of the whole table (user's answer, 2026-09-16: "only this request"). Two rides saved for
     * somewhere else, or for a different distance, must not stop the app planning a new one here.
     *
     * @param matches the saved trips that match THIS request (same start area and mileage)
     */
    fun decide(matches: List<ReusableRoute>, maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS): Decision =
        if (matches.size > SUGGEST_THRESHOLD) {
            Decision.Suggest(matches.sortedByDescending { it.lastUsedAt }.take(maxSuggestions))
        } else {
            Decision.PlanNew
        }
}

/** Where saved trips live (Room in the app, a map in tests), mirroring [RideHistoryStore]. */
interface RouteStore {
    suspend fun count(): Int

    /** Saved trips for this start area and mileage, newest-used first. Decodes the payloads. */
    suspend fun matching(origin: LatLon, targetMiles: Double, currentFingerprint: String? = null): List<ReusableRoute>

    /**
     * The road way-id sets of the saved trips for this request, for the overlap rule. Rows whose
     * `road_way_count` is below [newWayCount] are skipped without decompressing: a set with fewer
     * members than the new one cannot contain it.
     */
    suspend fun overlapContext(origin: LatLon, targetMiles: Double, newWayCount: Int): List<Set<Long>>

    /** Saves one option. Upserts by signature, then trims by the two caps. */
    suspend fun save(
        route: PlannedRoute,
        origin: LatLon,
        targetMiles: Double,
        waypoints: List<LatLon>,
        fingerprint: String,
        nowMs: Long,
        perKeyCap: Int = PER_KEY_CAP,
        globalCap: Int = GLOBAL_CAP,
    )

    suspend fun markUsed(signature: String, atMs: Long)

    suspend fun markRidden(signature: String, atMs: Long)

    companion object {
        /** Trips kept per (start area, mileage): enough for real variety, still a small table. */
        const val PER_KEY_CAP = 20
        /** Trips kept in total. At ~7 KB each this is a few MB (docs/ROUTE-STORE.md section 1). */
        const val GLOBAL_CAP = 500
    }
}

/** In-memory store: tests and any caller that has no database. */
class InMemoryRouteStore : RouteStore {
    private val rows = LinkedHashMap<String, SavedRouteRecord>()

    override suspend fun count(): Int = rows.size

    override suspend fun matching(origin: LatLon, targetMiles: Double, currentFingerprint: String?): List<ReusableRoute> =
        rows.values
            .filter { it.bucket == RouteKey.bucket(origin) }
            .filter { kotlin.math.abs(it.targetMiles - targetMiles) < 0.01 }
            .filter { Geo.distanceM(LatLon(it.originLat, it.originLon), origin) <= RouteKey.ORIGIN_MATCH_M }
            .sortedByDescending { it.lastUsedAt }
            .map { SavedRouteCodec.decode(it, currentFingerprint) }

    override suspend fun overlapContext(origin: LatLon, targetMiles: Double, newWayCount: Int): List<Set<Long>> =
        rows.values
            .filter { it.bucket == RouteKey.bucket(origin) }
            .filter { kotlin.math.abs(it.targetMiles - targetMiles) < 0.01 }
            .filter { Geo.distanceM(LatLon(it.originLat, it.originLon), origin) <= RouteKey.ORIGIN_MATCH_M }
            .filter { it.roadWayCount >= newWayCount }
            .map { SavedRouteCodec.decode(it).wayIds }

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
        val previous = rows[record.signature]
        rows[record.signature] = if (previous == null) {
            record
        } else {
            // re-saving the same ride keeps its history and usage
            record.copy(
                createdAt = previous.createdAt,
                lastUsedAt = previous.lastUsedAt,
                usedCount = previous.usedCount,
                riddenAt = previous.riddenAt,
            )
        }
        trim(perKeyCap, globalCap)
    }

    override suspend fun markUsed(signature: String, atMs: Long) {
        rows[signature]?.let { rows[signature] = it.copy(lastUsedAt = atMs, usedCount = it.usedCount + 1) }
    }

    override suspend fun markRidden(signature: String, atMs: Long) {
        rows[signature]?.let { rows[signature] = it.copy(riddenAt = atMs) }
    }

    private fun trim(perKeyCap: Int, globalCap: Int) {
        rows.values.groupBy { it.bucket to it.targetMiles }.forEach { (_, group) ->
            group.sortedByDescending { it.lastUsedAt }.drop(perKeyCap).forEach { rows.remove(it.signature) }
        }
        rows.values.sortedByDescending { it.lastUsedAt }.drop(globalCap).forEach { rows.remove(it.signature) }
    }
}

/** The safety data a stored verdict was computed against (docs/ROUTE-STORE.md section 3.4). */
object DataFingerprint {
    fun of(assetTexts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assetTexts.forEach { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** The bundled files that decide a grade. A change in any of them invalidates a stored verdict. */
    val ASSETS = listOf("traffic.json", "limits.json", "trails.json")
}
