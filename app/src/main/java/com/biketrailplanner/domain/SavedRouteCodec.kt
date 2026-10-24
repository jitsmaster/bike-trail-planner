package com.biketrailplanner.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Turns a [PlannedRoute] into a row that can be stored, and back (docs/ROUTE-STORE.md section 2).
 *
 * Only what is needed to DRAW the trip, HAND IT OFF again, SHOW its safety summary and TEST it for
 * overlap is kept. Everything else on [PlannedRoute] is derivation and is dropped: the per-edge
 * segment list, the raw commute result with its maneuvers, the trail legs and the detour detail.
 */
object SavedRouteCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The gzipped JSON body. Versioned so a later change can migrate rather than crash. */
    @Serializable
    private data class Payload(
        val v: Int = 1,
        @SerialName("geometry") val geometry: String,
        @SerialName("waypoints") val waypoints: List<List<Double>> = emptyList(),
        @SerialName("wayIds") val wayIds: List<Long> = emptyList(),
        @SerialName("named") val named: List<SavedNamedSegment> = emptyList(),
        @SerialName("notes") val notes: List<String> = emptyList(),
        @SerialName("accessNode") val accessNode: Long = 0L,
        @SerialName("turnaround") val turnaround: List<Double> = emptyList(),
        @SerialName("byGrade") val byGrade: Map<String, Double> = emptyMap(),
    )

    @Serializable
    private data class SavedNamedSegment(
        val n: String,
        val m: Double,
        val g: String,
        val s: String = "",
        val v: String? = null,
    )

    // ---- write -------------------------------------------------------------------------------

    fun encode(
        route: PlannedRoute,
        origin: LatLon,
        targetMiles: Double,
        waypoints: List<LatLon>,
        fingerprint: String,
        nowMs: Long,
    ): SavedRouteRecord {
        val payload = Payload(
            geometry = PolylineCodec.encode(route.roundTrip),
            waypoints = waypoints.map { listOf(it.lat, it.lon) },
            // sorted so the overlap test and any equality check are order-independent
            wayIds = route.segments.flatMap { it.wayIds }.distinct().sorted(),
            named = route.named.take(MAX_NAMED).map {
                SavedNamedSegment(it.name, it.metres, it.grade.name, it.speedText, it.volumeText)
            },
            notes = route.notes.take(MAX_NOTES),
            accessNode = route.accessPoint.osmNodeId,
            turnaround = listOf(route.turnaround.lat, route.turnaround.lon),
            byGrade = route.breakdown.byGrade.mapKeys { it.key.name },
        )
        return SavedRouteRecord(
            signature = route.signature,
            bucket = RouteKey.bucket(origin),
            originLat = origin.lat,
            originLon = origin.lon,
            targetMiles = targetMiles,
            trailLabel = route.trailLabel,
            totalM = route.totalM,
            trailRoundM = route.trailRoundTripM,
            timeS = route.estimatedTotalTimeS,
            gradedM = route.breakdown.gradedM,
            ungradedM = route.breakdown.ungradedM,
            breakdownTotalM = route.breakdown.totalM,
            dM = route.breakdown.metres(Grade.D),
            eM = route.breakdown.metres(Grade.E),
            roadWayCount = route.segments.flatMap { it.wayIds }.distinct().size,
            dataFingerprint = fingerprint,
            createdAt = nowMs,
            lastUsedAt = nowMs,
            usedCount = 0,
            riddenAt = null,
            payload = gzip(json.encodeToString(Payload.serializer(), payload).toByteArray()),
        )
    }

    // ---- read --------------------------------------------------------------------------------

    /**
     * @param currentFingerprint the fingerprint of the safety data in the app right now; when it
     *   differs from the row's, [ReusableRoute.safetyStale] is set and the caller must re-grade.
     */
    fun decode(record: SavedRouteRecord, currentFingerprint: String? = null): ReusableRoute {
        val payload = runCatching {
            json.decodeFromString(Payload.serializer(), String(gunzip(record.payload)))
        }.getOrNull()
        val points = payload?.geometry?.let { PolylineCodec.decode(it) }.orEmpty()
        val byGrade = payload?.byGrade.orEmpty().mapNotNull { (name, metres) ->
            Grade.entries.firstOrNull { it.name == name }?.let { it to metres }
        }.toMap()
        return ReusableRoute(
            signature = record.signature,
            trailLabel = record.trailLabel,
            trailNames = record.trailLabel.split(" + ").filter { it.isNotBlank() },
            points = points,
            waypoints = payload?.waypoints.orEmpty().mapNotNull { pair ->
                if (pair.size >= 2) LatLon(pair[0], pair[1]) else null
            },
            wayIds = payload?.wayIds.orEmpty().toSet(),
            named = payload?.named.orEmpty().map {
                NamedSegment(
                    name = it.n,
                    metres = it.m,
                    grade = Grade.entries.firstOrNull { g -> g.name == it.g } ?: Grade.UNGRADED,
                    roadClass = null,
                    speedText = it.s,
                    volumeText = it.v,
                )
            },
            targetMiles = record.targetMiles,
            totalM = record.totalM,
            trailRoundM = record.trailRoundM,
            timeS = record.timeS,
            gradedM = record.gradedM,
            ungradedM = record.ungradedM,
            breakdownTotalM = record.breakdownTotalM,
            byGrade = byGrade,
            accessNode = payload?.accessNode ?: 0L,
            turnaround = payload?.turnaround?.takeIf { it.size >= 2 }
                ?.let { LatLon(it[0], it[1]) } ?: LatLon(0.0, 0.0),
            createdAt = record.createdAt,
            lastUsedAt = record.lastUsedAt,
            usedCount = record.usedCount,
            riddenAt = record.riddenAt,
            safetyStale = currentFingerprint != null && record.dataFingerprint != currentFingerprint,
        )
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** The summary card names at most a screenful; the rest is re-derivable by re-planning. */
    private const val MAX_NAMED = 40
    private const val MAX_NOTES = 12
}
