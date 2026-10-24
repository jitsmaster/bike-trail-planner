package com.biketrailplanner.domain

import com.biketrailplanner.data.AiFailure
import com.biketrailplanner.data.AiKeyStore
import com.biketrailplanner.data.AiReply
import com.biketrailplanner.data.DeepSeekClient
import com.biketrailplanner.data.StrategyCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The AI seam of the trip planner (D27, docs/AI-PLANNING.md section 2).
 *
 * The model proposes WHICH of the options the app has already enumerated to try, and why. It never
 * emits geometry, never ranks roads, and never declares anything safe: Valhalla computes the route
 * and the deterministic D10 grader decides. The whole contract below is therefore names and ids,
 * never coordinates - no latitude or longitude is ever sent (docs/AI-PLANNING.md section 2).
 */
interface RouteStrategist {
    suspend fun propose(context: StrategyContext): StrategyReply
}

/** Everything the model is given: what the app already found, and the rider's own rules. */
data class StrategyContext(
    val targetMiles: Double,
    /** Trails the app already considers reachable, in the order the app ranked them. */
    val candidates: List<TrailOption>,
    /** Road names with a MEASURED low or medium volume: the only legal D13 via-points. */
    val quietLinks: List<String>,
    /** The rider's own rules, in words, so the reply is judged against them. */
    val rules: List<String>,
)

/** One trail the app offers, with what it knows about it. */
data class TrailOption(
    val trail: String,
    val trailMiles: Double,
    val surface: String,
    val entries: List<EntryOption>,
    /** false for a live-discovered trail: no closure check, no surface audit (D23). */
    val verified: Boolean,
)

/** One way onto a trail. [id] is the OSM node id, [rank] is the app's own order (1 = best). */
data class EntryOption(val id: Long, val rank: Int, val hint: String)

/** What the strategist answered: the strategies in the order they should be tried, plus why. */
@Serializable
data class StrategyReply(val strategies: List<Strategy>, val rationale: String)

/** One strategy: a trail, a way in, a direction, and optionally one quiet link. No geometry. */
@Serializable
data class Strategy(
    val trail: String,
    val entryId: Long,
    /** "longer-side-first" or "other-side-first". */
    val direction: String,
    /** A NAME from [StrategyContext.quietLinks], or null. Never a made-up street. */
    val viaRoad: String? = null,
    /** One rider-facing sentence. */
    val reason: String,
)

/** The two directions D3 defines for a linear trail. */
object StrategyDirection {
    const val LONGER_SIDE_FIRST = "longer-side-first"
    const val OTHER_SIDE_FIRST = "other-side-first"
    val ALL = listOf(LONGER_SIDE_FIRST, OTHER_SIDE_FIRST)
}

/**
 * What the app says when the AI could not be used (docs/AI-PLANNING.md section 7, steps 1 and 2).
 * Rider language only: no HTTP codes, no stack traces, no internal vocabulary.
 */
object StrategyText {
    const val NO_KEY = "The AI is switched off until a key is added, so the app planned with its own order."
    const val TIMEOUT = "The AI did not answer in time, so the app planned with its own order."
    const val UNREACHABLE = "The AI could not be reached, so the app planned with its own order."
    const val UNREADABLE = "The AI's answer could not be read, so the app planned with its own order."
    const val NO_STRATEGIES = "The AI did not suggest anything this app can use, so it planned with its own order."

    fun forFailure(reason: AiFailure): String = when (reason) {
        AiFailure.NO_KEY -> NO_KEY
        AiFailure.TIMEOUT -> TIMEOUT
        AiFailure.TRANSPORT -> UNREACHABLE
        AiFailure.UNPARSEABLE -> UNREADABLE
    }
}

/**
 * The validation of section 3, "the important one": a strategy may only name a trail that is in the
 * context, an entry that belongs to that trail, a via road that has a measured low volume, and a
 * direction D3 defines. Anything else is DROPPED, with a reason the rider can read - a hallucinated
 * trail name or a made-up street must never reach Valhalla.
 */
object StrategyValidator {

    /** The reply with only the strategies that survive the check, and the drops explained. */
    fun validate(reply: StrategyReply, context: StrategyContext): StrategyReply {
        val trails = context.candidates.associateBy { normalize(it.trail) }
        val quiet = context.quietLinks.map { normalize(it) }.toSet()
        val kept = ArrayList<Strategy>()
        val dropped = ArrayList<String>()
        val seen = HashSet<String>()

        reply.strategies.forEach { strategy ->
            val option = trails[normalize(strategy.trail)]
            when {
                option == null -> dropped +=
                    "The AI suggested \u201c" + strategy.trail + "\u201d, which is not a trail this app found."
                option.entries.none { it.id == strategy.entryId } -> dropped +=
                    "The AI suggested a way onto " + option.trail + " that this app did not offer."
                strategy.viaRoad != null && normalize(strategy.viaRoad) !in quiet -> dropped +=
                    "The AI suggested " + strategy.viaRoad + ", which has no measured low traffic, so it was ignored."
                strategy.direction !in StrategyDirection.ALL -> dropped +=
                    "The AI suggested a direction this app cannot ride on " + option.trail + "."
                !seen.add(key(strategy)) -> Unit
                else -> kept += strategy.copy(trail = option.trail)
            }
        }

        val rationale = buildString {
            append(reply.rationale.trim())
            if (dropped.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(dropped.distinct().joinToString(" "))
            }
        }.trim()
        return StrategyReply(kept, rationale)
    }

    /** A trail name is matched on case and spacing, so a capital letter cannot lose a good ride. */
    fun normalize(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun key(strategy: Strategy): String =
        normalize(strategy.trail) + "|" + strategy.entryId + "|" + strategy.direction
}

/**
 * The validating decorator, for callers that want a strategist that is always safe to use. It is the
 * same check as [StrategyValidator.validate]; wrapping is the way to make it impossible to forget.
 */
class ValidatingStrategist(private val inner: RouteStrategist) : RouteStrategist {
    override suspend fun propose(context: StrategyContext): StrategyReply =
        StrategyValidator.validate(inner.propose(context), context)
}

/**
 * The app without a key: the candidates in the order the app itself already ranked them, best first.
 *
 * This is what every failure path returns, so the app is never worse off without the AI (section 7,
 * rule 4), and it is also what makes "no key" and "the AI failed" the same, well-tested code path.
 */
class DeterministicStrategist : RouteStrategist {

    override suspend fun propose(context: StrategyContext): StrategyReply {
        val strategies = context.candidates.flatMap { option ->
            option.entries.sortedBy { it.rank }.map { entry ->
                Strategy(
                    trail = option.trail,
                    entryId = entry.id,
                    direction = StrategyDirection.LONGER_SIDE_FIRST,
                    viaRoad = null,
                    reason = reasonFor(option, entry),
                )
            }
        }
        return StrategyReply(strategies, RATIONALE)
    }

    private fun reasonFor(option: TrailOption, entry: EntryOption): String =
        "The app's own order: " + option.trail + ", in at entrance " + entry.rank + " of " +
            option.entries.size + "."

    companion object {
        const val RATIONALE: String =
            "The app's own order: the trails and entrances it already ranks best, tried in that order."
    }
}

/**
 * The model-backed strategist: ONE single-shot JSON call, built from the context, parsed back into
 * [Strategy] values (docs/AI-PLANNING.md section 4).
 *
 * A missing key, a failed call, or a reply that cannot be read all return an EMPTY strategy list with
 * a rider-facing sentence in the rationale. Empty means "no AI advice", which the ladder turns into
 * the deterministic order - so a bad day at DeepSeek can never break a plan.
 */
class DeepSeekStrategist(
    private val client: DeepSeekClient,
    private val keyStore: AiKeyStore,
    /** The model this strategist asks for. The transport is already bound to it; recorded here so a
     *  caller (and a test) can say which model produced a reply, without logging anything. */
    model: String? = null,
) : RouteStrategist {

    /** The model id in force: the one given, otherwise the rider's setting, otherwise the default. */
    val modelId: String = model?.takeIf { it.isNotBlank() } ?: keyStore.model

    override suspend fun propose(context: StrategyContext): StrategyReply {
        if (!keyStore.hasKey()) return StrategyReply(emptyList(), StrategyText.NO_KEY)
        val reply = client.completeJson(SYSTEM, userMessage(context))
        return when (reply) {
            is AiReply.Failed -> StrategyReply(emptyList(), StrategyText.forFailure(reply.reason))
            is AiReply.Ok -> parse(reply.json)
        }
    }

    /** The single user message: the target, the candidates, the quiet links, the rules, the shape. */
    fun userMessage(context: StrategyContext): String = buildString {
        append("A bicycle round trip from where the rider is now, about ")
        append(String.format(java.util.Locale.US, "%.1f", context.targetMiles))
        append(" miles in total. Every trail below has already been checked by this app; you are ")
        append("choosing between them, not inventing anything.\n\n")
        append("Trails, in the order the app ranks them:\n")
        if (context.candidates.isEmpty()) append("(none)\n")
        context.candidates.forEachIndexed { index, option ->
            append(index + 1).append(". ").append(option.trail)
            append(" - ").append(String.format(java.util.Locale.US, "%.1f", option.trailMiles))
            append(" mi of trail, surface ").append(option.surface)
            // Same wording rule as the screen: "not verified" is the D10 ride verdict, so a trail
            // with no closure check and no surface audit (D23) is described by what is missing.
            if (!option.verified) append(", no closure or surface check yet")
            append(", ").append(option.entries.size).append(" way(s) in\n")
            option.entries.sortedBy { it.rank }.forEach { entry ->
                append("   entrance ").append(entry.id).append(" (choice ").append(entry.rank).append(')')
                if (entry.hint.isNotBlank()) append(": ").append(entry.hint)
                append('\n')
            }
        }
        append("\nStreets with a measured low traffic volume, the only ones you may name as a ")
        append("quiet link: ")
        append(if (context.quietLinks.isEmpty()) "(none)" else context.quietLinks.joinToString(", "))
        append("\n\nThe rider's rules:\n")
        val rules = context.rules.ifEmpty { DEFAULT_RULES }
        rules.forEach { append("- ").append(it).append('\n') }
        append("\nReply with JSON only, in exactly this shape, with at most 3 strategies:\n")
        append("{\"rationale\":\"one sentence\",\"strategies\":[{\"trail\":\"exact trail name\",")
        append("\"entryId\":123456,\"direction\":\"longer-side-first\",\"viaRoad\":null,")
        append("\"reason\":\"one sentence for the rider\"}]}")
    }

    /** Tolerant parse of the reply object. Nothing readable means empty, so the ladder takes over. */
    fun parse(json: JsonObject): StrategyReply {
        val rationale = (json["rationale"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty()
        val array = json["strategies"] as? JsonArray
        val raw: List<JsonObject> = when {
            array != null -> array.mapNotNull { it as? JsonObject }
            json["trail"] != null -> listOf(json)   // a single strategy, not wrapped in a list
            else -> return StrategyReply(emptyList(), listOf(rationale, StrategyText.NO_STRATEGIES).joinNonBlank())
        }
        val strategies = raw.mapNotNull { one ->
            val trail = (one["trail"] as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val entryId = (one["entryId"] as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.toLongOrNull()
                ?: return@mapNotNull null
            Strategy(
                trail = trail,
                entryId = entryId,
                direction = (one["direction"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty(),
                viaRoad = (one["viaRoad"] as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() },
                reason = (one["reason"] as? JsonPrimitive)?.contentOrNullSafe().orEmpty(),
            )
        }
        if (strategies.isEmpty()) {
            return StrategyReply(emptyList(), listOf(rationale, StrategyText.NO_STRATEGIES).joinNonBlank())
        }
        val unreadable = raw.size - strategies.size
        val note = if (unreadable > 0) "Some of the AI's suggestions could not be read and were ignored." else ""
        return StrategyReply(strategies, listOf(rationale, note).joinNonBlank())
    }

    private fun List<String>.joinNonBlank(): String = filter { it.isNotBlank() }.joinToString(" ")

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    companion object {
        /** The short, stable system message of docs/AI-PLANNING.md section 4. */
        const val SYSTEM: String =
            "You are choosing between bicycle trips that another program has already checked for " +
                "safety. You never invent routes, streets or coordinates. Reply with JSON only."

        /** The rider's rules, exactly as section 4 words them, when the caller supplies none. */
        val DEFAULT_RULES = listOf(
            "prefer trails over roads",
            "keep the trail portion at or above 2 miles",
            "a ride within the mileage asked for is not a detour",
            "never repeat the ride taken last time",
            "if the rider is away from home say so",
        )
    }
}

/**
 * The cache decorator (docs/AI-PLANNING.md section 5): a reply already stored for this request is
 * returned WITHOUT calling the model, so planning the same ride twice costs nothing.
 *
 * A reply that offers nothing is deliberately not cached: there is no reason to remember a failure,
 * and the next attempt is allowed to succeed.
 */
class CachedStrategist(
    private val inner: RouteStrategist,
    private val cache: StrategyCache,
    private val keyOf: (StrategyContext) -> String,
) : RouteStrategist {

    override suspend fun propose(context: StrategyContext): StrategyReply {
        val key = keyOf(context)
        cache.get(key)?.let { return it }
        val reply = inner.propose(context)
        if (reply.strategies.isNotEmpty()) runCatching { cache.put(key, reply) }
        return reply
    }
}

/**
 * The whole degradation ladder of docs/AI-PLANNING.md section 7, in one place:
 *
 * 1. no key -> the deterministic order ([DeepSeekStrategist] answers empty without calling out);
 * 2. the call failed or timed out -> the deterministic order;
 * 3. the reply was invalid, or every strategy was rejected by validation -> the deterministic order,
 *    with the rejected suggestions and the reason kept in the rationale;
 * 4. the reply was valid but the routes fail D10 -> the caller tries the deterministic order as well,
 *    which this class makes possible by never removing the deterministic strategies from reach.
 *
 * An exception from the AI side is a failure like any other: a strategist must never break a plan.
 */
class FallbackStrategist(
    private val ai: RouteStrategist,
    private val deterministic: RouteStrategist = DeterministicStrategist(),
) : RouteStrategist {

    override suspend fun propose(context: StrategyContext): StrategyReply {
        val fromAi = runCatching { ai.propose(context) }.getOrElse { StrategyReply(emptyList(), StrategyText.UNREADABLE) }
        if (fromAi.strategies.isNotEmpty()) return fromAi
        val fallback = deterministic.propose(context)
        return StrategyReply(
            strategies = fallback.strategies,
            rationale = listOf(fromAi.rationale.trim(), fallback.rationale).filter { it.isNotBlank() }
                .joinToString(" "),
        )
    }
}
