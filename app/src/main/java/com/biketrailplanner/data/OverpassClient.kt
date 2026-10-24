package com.biketrailplanner.data

import com.biketrailplanner.domain.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Overpass mirrors the app talks to, in the order they are tried (D23, docs/TRAIL-DISCOVERY.md).
 *
 * All three are volunteer-run public instances. `overpass-api.de` is the reference instance and is
 * tried first, kumi.systems is a well-known mirror on separate hardware, and overpass.osm.ch is the
 * Swiss instance. A mirror that answers with stale data, an error or nothing is skipped and the next
 * one is tried, so one slow or lagging instance does not take the feature down.
 */
object OverpassMirrors {
    const val DE = "https://overpass-api.de/api/interpreter"
    const val KUMI = "https://overpass.kumi.systems/api/interpreter"
    const val OSM_CH = "https://overpass.osm.ch/api/interpreter"

    /**
     * The mirrors tried, in order. **Measured 2026-09-20**, from this network, for this app's own
     * `around:10000` route-relation query:
     *
     * | mirror | result |
     * |---|---|
     * | `overpass-api.de` | HTTP 200 in 1.5 s (400 KB); **HTTP 429 on the 3rd of 5 rapid queries**, after 14 s |
     * | `overpass.kumi.systems` | HTTP 504 after 32 s |
     * | `overpass.osm.ch` | HTTP 200 in 0.6 s, 272 B, **always 0 elements**, `timestamp_osm_base` of `"117158"` |
     *
     * So `osm.ch` is REMOVED from the rotation: it does not serve this data, and it answers with a
     * well-formed empty envelope - one stamp-format change away from being read as a confirmed
     * "no trails here", which is the worst wrong answer this feature can give. The constant is kept
     * because the recorded fixture and its test still describe that failure.
     *
     * `kumi.systems` is kept last: it is a real mirror having a bad day (504), not a wrong one, and it
     * is only reached after the reference instance has already failed.
     */
    val DEFAULT: List<String> = listOf(DE, KUMI)
}

/** One Overpass call attempt, kept so the caller can report which mirror served what. */
data class MirrorAttempt(
    val mirror: String,
    /** `osm3s.timestamp_osm_base` as the mirror reported it, or null when it reported none. */
    val timestampOsmBase: String?,
    val ageMs: Long?,
    /** "OK", "STALE: ..." or "FAILED: ...". Never contains coordinates. */
    val outcome: String,
)

/**
 * One usable Overpass answer: the raw body, the parsed tree, and the provenance of the data.
 *
 * [mirror] and [timestampOsmBase] together are the freshness story: this answer came from that
 * instance, and its OSM data was that old when it was fetched.
 */
data class OverpassAnswer(
    val json: String,
    val root: JsonObject,
    val mirror: String,
    val timestampOsmBase: String?,
    val ageMs: Long?,
    val fetchedAtMs: Long,
    /** How long the accepted mirror took to answer, in milliseconds. */
    val elapsedMs: Long,
    /** Every mirror that was tried, in order, with its outcome. */
    val attempts: List<MirrorAttempt>,
) {
    /** The mirrors that were rejected for serving stale data before this answer was accepted. */
    val staleMirrors: List<String> get() = attempts.filter { it.outcome.startsWith("STALE") }.map { it.mirror }

    /** The route relations this answer actually carries. */
    val relationsInAnswer: Int get() = routeRelationCount(root)

    /**
     * The mirrors that answered with FRESH data that carried no route relation at all. One of these
     * is not a verdict - a mirror can answer "nothing here" for a place that has trails - so the
     * client asks another mirror before it accepts an empty answer.
     */
    val emptyMirrors: List<String> get() = attempts.filter { it.outcome.startsWith("EMPTY") }.map { it.mirror }

    /** True when two mirrors were asked and BOTH found no route relation: "none here" is then real. */
    val emptyConfirmed: Boolean
        get() = relationsInAnswer == 0 && emptyMirrors.size >= OverpassClient.MAX_EMPTY_ATTEMPTS

    val jsonBytes: Int get() = json.toByteArray(Charsets.UTF_8).size
}

/**
 * The route relations one Overpass answer carries (`route=bicycle` or `route=mtb`, which is all the
 * discovery query asks for). Zero means "this answer says there is nothing here", which is a claim
 * that has to be checked against a second mirror before the app repeats it to a rider.
 */
fun routeRelationCount(root: JsonObject): Int =
    (root["elements"] as? JsonArray).orEmpty().count { element ->
        val rel = element as? JsonObject ?: return@count false
        rel["type"]?.jsonPrimitive?.content == "relation" &&
            rel["tags"]?.jsonObject?.get("route")?.jsonPrimitive?.content in setOf("bicycle", "mtb")
    }

/** Discovery failed on every mirror that was tried. */
class OverpassException(val attempts: List<MirrorAttempt>, message: String) : Exception(message) {
    /** One line per mirror, for a log line or a rider-facing "discovery is unavailable" note. */
    val summary: String get() = attempts.joinToString("; ") { "${it.mirror} -> ${it.outcome}" }

    /**
     * True when at least one mirror refused the query for asking too often. That is a different
     * thing from a broken network and gets different words: the rider can simply try again shortly.
     */
    val rateLimited: Boolean get() = attempts.any { it.outcome.contains("HTTP ${OverpassClient.RATE_LIMIT}") }
}

/**
 * A mirror answered, and the answer was an HTTP error status.
 *
 * Its own type so the status survives into the note: the failure that prompted this was
 * `overpass-api.de` answering **429** after two rapid queries, which means "wait a minute" - and it
 * used to be flattened into a bare `IOException`, which means nothing at all.
 */
class OverpassHttpException(val code: Int, val retryAfterS: Long? = null) :
    IOException("HTTP $code from Overpass")

/** The HTTP seam, so the rotation and freshness logic is testable without a network. */
fun interface OverpassTransport {
    /** POST the Overpass form body to [url] and return the response body, or throw [IOException]. */
    suspend fun post(url: String, body: String): String
}

/**
 * The real transport. One attempt is bounded at [OverpassClient.ATTEMPT_TIMEOUT_S]; the query text
 * carries its own `[timeout:25]` as well, so a stuck query cannot hold the call open forever.
 */
class OkHttpOverpassTransport(
    private val http: OkHttpClient = defaultHttpClient(),
) : OverpassTransport {
    override suspend fun post(url: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // The public instances reject requests with no User-Agent (Apache answers HTTP 406),
            // and their usage policy asks for an identifiable client. No coordinates in this header.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                // The status IS the diagnosis (429 = asked too often, 504 = the instance is
                // overloaded, 403 = the request was refused), so it is carried out as its own type.
                throw OverpassHttpException(
                    code = resp.code,
                    retryAfterS = resp.header("Retry-After")?.trim()?.toLongOrNull(),
                )
            }
            text
        }
    }

    companion object {
        /** Identifies the app to the volunteer instances (their usage policy asks for this). */
        const val USER_AGENT = "bike-trail-planner/0.1 (Overpass live trail discovery)"

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(OverpassClient.ATTEMPT_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(OverpassClient.ATTEMPT_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * The Overpass client: one query text in, one parsed JSON tree out, with mirror rotation and a
 * freshness check on every response (D23).
 *
 * Two rules, both mandatory:
 *  1. **Age.** `osm3s.timestamp_osm_base` is read from every response. A response whose OSM data is
 *     older than [staleAgeMs] is refused. The shipped stale age is 48 h ([STALE_AGE_MS]) because
 *     mirrors normally lag live OSM by minutes-to-hours: two days behind means replication has
 *     stalled or the instance is not being updated, while a shorter window would throw away an
 *     instance that merely paused over a maintenance window. A response with NO parseable stamp
 *     cannot prove its freshness at all, so it is refused too - the app never presents undated data.
 *  2. **Monotonicity.** A response older than the newest stamp already served by ANY mirror in this
 *     process is refused even when it is inside the age window: having seen fresher data already
 *     proves that instance is lagging.
 *
 * A refused response means the mirror is skipped and the next one is tried, at most one attempt per
 * mirror and never more than [MAX_ATTEMPTS] in total. Nothing here logs or embeds coordinates:
 * [OverpassException] carries mirror URLs and reasons only.
 */
class OverpassClient(
    private val mirrors: List<String> = OverpassMirrors.DEFAULT,
    private val transport: OverpassTransport = OkHttpOverpassTransport(),
    private val staleAgeMs: Long = STALE_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** The newest `timestamp_osm_base` any mirror has served in this process (the monotonic guard). */
    var newestSeenOsmBase: String? = null
        private set

    private var newestSeenMs: Long? = null

    /** Runs [ql] against the mirrors in order and returns the first answer that proves fresh. */
    suspend fun query(ql: String): OverpassAnswer {
        val attempts = ArrayList<MirrorAttempt>()
        val body = formBody(ql)
        var index = 0
        var emptySeen = 0
        while (index < mirrors.size && attempts.size < MAX_ATTEMPTS) {
            val mirror = mirrors[index]
            index++
            val started = clock()
            val text = try {
                transport.post(mirror, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts.add(MirrorAttempt(mirror, null, null, "FAILED: " + failureReason(e)))
                // A rate limit ends the lookup. Every public instance applies the same kind of policy,
                // and re-asking after a 429 is precisely what gets the app blocked (measured: the
                // reference instance 429s on the 3rd query in a burst). The cache, if there is one, is
                // the honest answer; if there is not, the rider is told the service is busy.
                if (e is OverpassHttpException && e.code == RATE_LIMIT) break
                continue
            }
            val root = try {
                parse(text)
            } catch (e: Exception) {
                attempts.add(MirrorAttempt(mirror, null, null, "FAILED: not JSON"))
                continue
            }
            val stamp = stampOf(root)
            val now = clock()
            when (val verdict = freshness(stamp, now)) {
                is Freshness.Stale -> {
                    attempts.add(
                        MirrorAttempt(mirror, stamp, verdict.epochMs?.let { now - it }, "STALE: ${verdict.reason}"),
                    )
                    continue
                }

                is Freshness.Fresh -> {
                    // "Nothing here" is a verdict about the world, not just about this mirror, and it
                    // is the worst wrong answer this feature can give: a rider standing next to a
                    // trail would be told there is none. So an empty answer is second class - it is
                    // only accepted after one more mirror has been asked and agreed (or when the
                    // mirror list has nobody left to ask).
                    if (routeRelationCount(root) == 0) {
                        emptySeen++
                        val askAnother = emptySeen < MAX_EMPTY_ATTEMPTS && index < mirrors.size &&
                            attempts.size < MAX_ATTEMPTS
                        attempts.add(
                            MirrorAttempt(
                                mirror,
                                stamp,
                                verdict.ageMs,
                                if (askAnother) {
                                    "EMPTY: fresh data, but no route relations near this position"
                                } else {
                                    "EMPTY: confirmed, no route relations near this position"
                                },
                            ),
                        )
                        if (askAnother) continue
                    } else {
                        attempts.add(MirrorAttempt(mirror, stamp, verdict.ageMs, "OK"))
                    }
                    newestSeenOsmBase = stamp
                    newestSeenMs = verdict.epochMs
                    return OverpassAnswer(
                        json = text,
                        root = root,
                        mirror = mirror,
                        timestampOsmBase = stamp,
                        ageMs = verdict.ageMs,
                        fetchedAtMs = now,
                        elapsedMs = now - started,
                        attempts = attempts,
                    )
                }
            }
        }
        throw OverpassException(
            attempts,
            "no Overpass mirror answered with fresh data after ${attempts.size} attempt(s)",
        )
    }

    /** `osm3s.timestamp_osm_base` of a parsed response, or null when it carries none. */
    internal fun stampOf(root: JsonObject): String? =
        runCatching { root["osm3s"]?.jsonObject?.get("timestamp_osm_base")?.jsonPrimitive?.content }.getOrNull()

    /**
     * Why one attempt failed, in a few words that say something. The class name alone did not: the
     * measured failure was an HTTP 429, and the note showed only "IOException" - which sent the
     * search for the cause into the network stack when the answer was "the mirror is rate-limiting us".
     */
    internal fun failureReason(e: Exception): String = when (e) {
        // Must come before IOException: it is one.
        is OverpassHttpException ->
            "HTTP " + e.code + (e.retryAfterS?.let { ", retry after " + it + "s" } ?: "")
        is SocketTimeoutException -> "timed out"
        is InterruptedIOException -> "timed out"
        is UnknownHostException -> "no address for the mirror"
        is IOException -> e.message?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "network error"
        else -> e.javaClass.simpleName
    }

    internal fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    /** Applies both freshness rules to one stamp. See the class note for why each one exists. */
    internal fun freshness(stamp: String?, nowMs: Long): Freshness {
        val epoch = parseStamp(stamp)
            ?: return Freshness.Stale(
                if (stamp.isNullOrBlank()) {
                    "the response carries no osm3s.timestamp_osm_base, so its age cannot be proven"
                } else {
                    "osm3s.timestamp_osm_base '$stamp' is not an ISO-8601 instant"
                },
                null,
            )
        val age = nowMs - epoch
        val previous = newestSeenMs
        if (previous != null && epoch < previous) {
            return Freshness.Stale(
                "its data (${stamp}) is older than the newest data already seen from another mirror",
                epoch,
            )
        }
        if (age > staleAgeMs) {
            return Freshness.Stale(
                "its data is ${age / 3_600_000L} h old, past the ${staleAgeMs / 3_600_000L} h stale age",
                epoch,
            )
        }
        if (age < -MAX_CLOCK_SKEW_MS) {
            return Freshness.Stale("its data is dated in the future (${stamp})", epoch)
        }
        return Freshness.Fresh(epoch, age)
    }

    private fun parseStamp(stamp: String?): Long? {
        if (stamp.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(stamp.trim()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** The verdict for one mirror's `timestamp_osm_base`. */
    sealed interface Freshness {
        data class Fresh(val epochMs: Long, val ageMs: Long) : Freshness
        data class Stale(val reason: String, val epochMs: Long?) : Freshness
    }

    companion object {
        /** 48 h. See the class note for the reasoning behind the number. */
        const val STALE_AGE_MS = 2L * 24 * 60 * 60 * 1000

        /**
         * Per-attempt ceiling. Measured on 2026-09-20: a loaded answer from the reference instance
         * took 12.5 s, and a rate-limit refusal arrived after 14.4 s - both inside the old 25 s
         * budget only by luck. The query still carries its own `[timeout:25]`, so the server is the
         * one that gives up first on a genuinely stuck query.
         */
        const val ATTEMPT_TIMEOUT_S = 40L

        /** The one status that means "stop asking": every public instance applies the same policy. */
        const val RATE_LIMIT = 429

        /** At most one attempt per mirror; this is the hard ceiling if the mirror list ever grows. */
        const val MAX_ATTEMPTS = 4

        /**
         * How many mirrors must agree before an empty answer is accepted: 2, so exactly ONE extra
         * mirror is asked. Bounded on purpose - the API quota is real (HTTP 429 measured after three
         * rapid queries), and the extra question is worth it only for the answer that would otherwise
         * be silently wrong.
         */
        const val MAX_EMPTY_ATTEMPTS = 2

        /** A stamp a few minutes ahead of the phone clock is clock skew, not a bad mirror. */
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

        /** The POST body for Overpass `/api/interpreter`: `data=<ql>`. */
        fun formBody(ql: String): String = "data=" + URLEncoder.encode(ql, "UTF-8")
    }
}

/**
 * The one query text this feature sends (docs/TRAIL-DISCOVERY.md). Kept in one place so the doc, the
 * tests and the app cannot drift apart.
 */
object DiscoveryQuery {
    /** `route=bicycle` and `route=mtb` relations within [radiusKm] of [origin], geometry inline. */
    fun text(origin: LatLon, radiusKm: Double): String = buildString {
        val metres = Math.round(radiusKm * 1000.0)
        val lat = String.format(Locale.US, "%.6f", origin.lat)
        val lon = String.format(Locale.US, "%.6f", origin.lon)
        appendLine("[out:json][timeout:25];")
        appendLine("(")
        appendLine("  relation(around:$metres,$lat,$lon)[\"route\"=\"bicycle\"];")
        appendLine("  relation(around:$metres,$lat,$lon)[\"route\"=\"mtb\"];")
        appendLine(");")
        append("out geom;\n")
    }
}
