package com.biketrailplanner

import com.biketrailplanner.data.AiFailure
import com.biketrailplanner.data.AiKeyStore
import com.biketrailplanner.data.AiReply
import com.biketrailplanner.data.ChatCall
import com.biketrailplanner.data.DeepSeekClient
import com.biketrailplanner.data.InMemoryAiKeyStore
import com.biketrailplanner.data.InMemoryStrategyCache
import com.biketrailplanner.data.StrategyCache
import com.biketrailplanner.data.StrategyCacheKey
import com.biketrailplanner.domain.CachedStrategist
import com.biketrailplanner.domain.DeterministicStrategist
import com.biketrailplanner.domain.DeepSeekStrategist
import com.biketrailplanner.domain.EntryOption
import com.biketrailplanner.domain.FallbackStrategist
import com.biketrailplanner.domain.RouteStrategist
import com.biketrailplanner.domain.Strategy
import com.biketrailplanner.domain.StrategyContext
import com.biketrailplanner.domain.StrategyDirection
import com.biketrailplanner.domain.StrategyReply
import com.biketrailplanner.domain.StrategyText
import com.biketrailplanner.domain.StrategyValidator
import com.biketrailplanner.domain.TrailOption
import com.biketrailplanner.domain.ValidatingStrategist
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The AI seam (D27, docs/AI-PLANNING.md): the contract, the guardrails, the source validation and the
 * degradation ladder. Every test here uses fakes - no network, no Android, no API key.
 */
class AiStrategistTest {

    // ---- fakes ---------------------------------------------------------------------------------

    /** The one transport seam, faked: it counts calls, records the caps it was given, and can fail. */
    private class FakeCall(
        var text: String = "{}",
        var error: Throwable? = null,
        var delayMs: Long = 0L,
    ) : ChatCall {
        var calls = 0
        var maxTokensSeen = -1
        var lastSystem = ""
        var lastUser = ""

        override suspend fun call(system: String, user: String, maxOutputTokens: Int): String {
            calls++
            lastSystem = system
            lastUser = user
            maxTokensSeen = maxOutputTokens
            if (delayMs > 0) delay(delayMs)
            error?.let { throw it }
            return text
        }
    }

    /** The options the app already enumerated, ranked by the app. This is all the model ever sees. */
    private val context = StrategyContext(
        targetMiles = 10.0,
        candidates = listOf(
            TrailOption(
                trail = "Sumner Link Trail",
                trailMiles = 3.8,
                surface = "paved",
                entries = listOf(
                    EntryOption(6486202530L, 1, "from 18th St E"),
                    EntryOption(2670828792L, 2, "from A St SE"),
                ),
                verified = true,
            ),
            TrailOption(
                trail = "Interurban Trail Milton",
                trailMiles = 2.9,
                surface = "compacted",
                entries = listOf(EntryOption(111L, 1, "from Milton Way")),
                verified = true,
            ),
        ),
        quietLinks = listOf("A St SE", "24th St E"),
        rules = listOf("prefer trails over roads", "keep the trail portion at or above 2 miles"),
    )

    private val deterministic = DeterministicStrategist()

    private fun json(vararg strategies: String, rationale: String = "Sumner Link is the longer trail") =
        """{"rationale":"$rationale","strategies":[${strategies.joinToString(",")}]}"""

    /** One strategy as the model would write it. A null via road is the JSON literal `null`. */
    private fun one(
        trail: String,
        entryId: Long,
        direction: String = StrategyDirection.LONGER_SIDE_FIRST,
        via: String? = null,
    ): String {
        val viaJson = if (via == null) "null" else "\"" + via + "\""
        return """{"trail":"$trail","entryId":$entryId,"direction":"$direction","viaRoad":$viaJson,"reason":"test"}"""
    }

    /** The production shape of the seam: AI (cached, validated) with the deterministic order behind it. */
    private fun ladder(
        call: FakeCall,
        keyStore: AiKeyStore,
        cache: StrategyCache? = null,
    ): RouteStrategist {
        val ai: RouteStrategist = DeepSeekStrategist(DeepSeekClient(call), keyStore)
        val validated = ValidatingStrategist(ai)
        val cached = cache?.let {
            CachedStrategist(validated, it, { ctx -> StrategyCacheKey.of("47.24,-122.26", ctx.targetMiles) })
        } ?: validated
        return FallbackStrategist(cached, deterministic)
    }

    private val keySet = { InMemoryAiKeyStore("sk-test-key-not-a-real-key") }

    // ---- the contract: a good reply is used ----------------------------------------------------

    @Test
    fun `a valid reply is parsed into strategies, names and ids intact`() = runTest {
        val call = FakeCall(
            text = json(
                one("Sumner Link Trail", 6486202530L, StrategyDirection.OTHER_SIDE_FIRST, "A St SE"),
                one("Interurban Trail Milton", 111L),
            ),
        )
        val strategist = ladder(call, keySet())
        val reply = strategist.propose(context)

        assertEquals(2, reply.strategies.size)
        assertEquals("Sumner Link Trail", reply.strategies[0].trail)
        assertEquals(6486202530L, reply.strategies[0].entryId)
        assertEquals(StrategyDirection.OTHER_SIDE_FIRST, reply.strategies[0].direction)
        assertEquals("A St SE", reply.strategies[0].viaRoad)
        assertEquals("Interurban Trail Milton", reply.strategies[1].trail)
        assertEquals(111L, reply.strategies[1].entryId)
        assertTrue(reply.rationale.contains("Sumner Link is the longer trail"))
        assertEquals("one model call for one plan", 1, call.calls)
    }

    @Test
    fun `the message sent names the options and the rules and asks for JSON only`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        ladder(call, keySet()).propose(context)

        assertTrue(DeepSeekStrategist.SYSTEM.contains("Reply with JSON only"))
        assertTrue("the trails the app found are named", call.lastUser.contains("Sumner Link Trail"))
        assertTrue("the way in is named by id", call.lastUser.contains("6486202530"))
        assertTrue("the quiet links are named", call.lastUser.contains("A St SE"))
        assertTrue("the rider's rules are sent", call.lastUser.contains("prefer trails over roads"))
        assertTrue("the target mileage is sent", call.lastUser.contains("10.0"))
        // No coordinates may ever be sent: the context carries none by construction.
        assertTrue(!call.lastUser.contains("47.") && !call.lastSystem.contains("47."))
    }

    // ---- the important one: a hallucinated name is dropped, never routed ------------------------

    @Test
    fun `a strategy naming an unknown trail is dropped`() {
        val reply = StrategyReply(
            strategies = listOf(
                Strategy("Ghost Trail", 111L, StrategyDirection.LONGER_SIDE_FIRST, null, "invented"),
                Strategy("Interurban Trail Milton", 111L, StrategyDirection.LONGER_SIDE_FIRST, null, "real"),
            ),
            rationale = "because",
        )
        val validated = StrategyValidator.validate(reply, context)

        assertEquals(listOf("Interurban Trail Milton"), validated.strategies.map { it.trail })
        assertTrue(validated.rationale.contains("Ghost Trail"))
        assertTrue(
            "the rider is told why it was ignored",
            validated.rationale.contains("not a trail this app found"),
        )
    }

    @Test
    fun `a strategy naming an entry id the app did not offer is dropped`() {
        val reply = StrategyReply(
            listOf(Strategy("Sumner Link Trail", 999999L, StrategyDirection.LONGER_SIDE_FIRST, null, "made up")),
            "because",
        )
        val validated = StrategyValidator.validate(reply, context)

        assertTrue("a made-up entrance must never be routed", validated.strategies.isEmpty())
        assertTrue(validated.rationale.contains("did not offer"))
    }

    @Test
    fun `a strategy with a made-up via road is dropped`() {
        val reply = StrategyReply(
            strategies = listOf(
                Strategy("Sumner Link Trail", 6486202530L, StrategyDirection.LONGER_SIDE_FIRST, "Quiet Made Up Way", "?"),
                Strategy("Sumner Link Trail", 2670828792L, StrategyDirection.LONGER_SIDE_FIRST, "24th St E", "ok"),
            ),
            rationale = "because",
        )
        val validated = StrategyValidator.validate(reply, context)

        assertEquals(1, validated.strategies.size)
        assertEquals("24th St E", validated.strategies.single().viaRoad)
        assertTrue(validated.rationale.contains("Quiet Made Up Way"))
        assertTrue(validated.rationale.contains("no measured low traffic"))
    }

    @Test
    fun `an unknown direction or a duplicate is dropped and a valid entry survives`() {
        val reply = StrategyReply(
            strategies = listOf(
                Strategy("Sumner Link Trail", 6486202530L, "sideways", null, "?"),
                Strategy("Sumner Link Trail", 6486202530L, StrategyDirection.LONGER_SIDE_FIRST, null, "first"),
                Strategy("sumner link trail", 6486202530L, StrategyDirection.LONGER_SIDE_FIRST, null, "same ride again"),
            ),
            rationale = "because",
        )
        val validated = StrategyValidator.validate(reply, context)

        assertEquals("the same ride twice is one strategy", 1, validated.strategies.size)
        assertEquals("the app's own spelling is used", "Sumner Link Trail", validated.strategies.single().trail)
    }

    // ---- the ladder: a failure is never worse than no AI ---------------------------------------

    @Test
    fun `a malformed reply is retried once and then falls back to the deterministic order`() = runTest {
        val call = FakeCall(text = "I am sorry, I cannot answer that.")
        val reply = ladder(call, keySet()).propose(context)

        assertEquals(2, call.calls)
        assertEquals(deterministic.propose(context).strategies, reply.strategies)
        assertTrue(reply.rationale.contains(DeterministicStrategist.RATIONALE))
        assertTrue(reply.rationale.contains(StrategyText.UNREADABLE))
    }

    @Test
    fun `a transport failure falls back to the deterministic order after exactly one retry`() = runTest {
        val call = FakeCall(error = IOException("connection reset"))
        val reply = ladder(call, keySet()).propose(context)

        assertEquals("one initial attempt plus exactly one retry", 2, call.calls)
        assertEquals(deterministic.propose(context).strategies, reply.strategies)
        assertTrue(reply.rationale.contains(StrategyText.UNREACHABLE))
    }

    @Test
    fun `a timeout is reported as a failure and falls back, with no extra attempt`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)), delayMs = 60_000L)
        val client = DeepSeekClient(call, timeoutMs = 20L)

        val reply = client.completeJson(DeepSeekStrategist.SYSTEM, "hello")

        assertTrue(reply is AiReply.Failed)
        assertEquals(AiFailure.TIMEOUT, (reply as AiReply.Failed).reason)
        assertEquals("20 s in the app, 20 ms here: one attempt plus one retry", 2, call.calls)
    }

    @Test
    fun `no key means the deterministic strategist is used and the model is never called`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        val reply = ladder(call, InMemoryAiKeyStore("")).propose(context)

        assertEquals("the model must not be called without a key", 0, call.calls)
        assertEquals(deterministic.propose(context).strategies, reply.strategies)
        assertTrue(reply.rationale.contains(StrategyText.NO_KEY))
    }

    @Test
    fun `every strategy rejected by validation falls back with the reasons kept`() = runTest {
        val call = FakeCall(text = json(one("Ghost Trail", 42L, via = "Made Up Way")))
        val reply = ladder(call, keySet()).propose(context)

        assertEquals(deterministic.propose(context).strategies, reply.strategies)
        assertTrue(reply.rationale.contains("Ghost Trail"))
        assertTrue(reply.rationale.contains(DeterministicStrategist.RATIONALE))
    }

    @Test
    fun `the deterministic order is the app's own ranking, best entrance first`() = runTest {
        val reply = deterministic.propose(context)

        assertEquals(
            listOf("Sumner Link Trail" to 6486202530L, "Sumner Link Trail" to 2670828792L, "Interurban Trail Milton" to 111L),
            reply.strategies.map { it.trail to it.entryId },
        )
        assertTrue(reply.strategies.all { it.viaRoad == null })
        assertTrue(reply.strategies.all { it.direction == StrategyDirection.LONGER_SIDE_FIRST })
    }

    // ---- the cache: the same request never pays twice ------------------------------------------

    @Test
    fun `a cached reply means the model is called exactly once across two identical requests`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        val cache = InMemoryStrategyCache()
        val strategist = ladder(call, keySet(), cache)

        val first = strategist.propose(context)
        val second = strategist.propose(context)

        assertEquals(1, call.calls)
        assertEquals(first, second)
        assertEquals(1, cache.size())
        assertEquals("Sumner Link Trail", second.strategies.single().trail)
    }

    @Test
    fun `a different mileage is a different request and does pay again`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        val strategist = ladder(call, keySet(), InMemoryStrategyCache())

        strategist.propose(context)
        strategist.propose(context.copy(targetMiles = 15.0))

        assertEquals(2, call.calls)
    }

    @Test
    fun `a failed reply is not cached, so the next plan may still use the AI`() = runTest {
        val call = FakeCall(error = IOException("offline"))
        val cache = InMemoryStrategyCache()
        ladder(call, keySet(), cache).propose(context)

        assertEquals(0, cache.size())
    }

    @Test
    fun `the cache key is a hash of the origin bucket and the mileage, and jitter keeps the same key`() {
        val home = com.biketrailplanner.domain.LatLon(47.240371, -122.262019)
        val jittered = com.biketrailplanner.domain.LatLon(47.240999, -122.262019)

        assertEquals(StrategyCacheKey.of(home, 10.0), StrategyCacheKey.of(jittered, 10.0))
        assertNotEquals(StrategyCacheKey.of(home, 10.0), StrategyCacheKey.of(home, 15.0))
        assertNotEquals(StrategyCacheKey.of(home, 10.0), StrategyCacheKey.of(com.biketrailplanner.domain.LatLon(47.30, -122.40), 10.0))
        assertTrue("no coordinate is stored", !StrategyCacheKey.of(home, 10.0).contains("47."))
    }

    @Test
    fun `the cache keeps at most twenty replies`() = runTest {
        var tick = 0L
        val cache = InMemoryStrategyCache(now = { tick++ })
        repeat(StrategyCache.CAP + 5) { index ->
            cache.put("key-$index", StrategyReply(listOf(Strategy("Sumner Link Trail", 1L, StrategyDirection.LONGER_SIDE_FIRST, null, "r")), "why"))
        }
        assertEquals(StrategyCache.CAP, cache.size())
        assertEquals("the oldest entries go first", null, cache.get("key-0"))
        assertTrue(cache.get("key-${StrategyCache.CAP + 4}") != null)
    }

    // ---- the guardrails, asserted on the wire --------------------------------------------------

    @Test
    fun `the output token cap is 700 for a plan and 5 for a key test`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        val client = DeepSeekClient(call)

        client.completeJson(DeepSeekStrategist.SYSTEM, "user")
        assertEquals(700, call.maxTokensSeen)
        assertEquals(DeepSeekClient.MAX_OUTPUT_TOKENS, call.maxTokensSeen)

        client.ping()
        assertEquals(5, call.maxTokensSeen)
        assertEquals("no retry after a good answer", 2, call.calls)
    }

    @Test
    fun `a valid reply is never retried`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        DeepSeekClient(call).completeJson(DeepSeekStrategist.SYSTEM, "user")

        assertEquals(1, call.calls)
    }

    @Test
    fun `a fenced or chatty reply is still read`() = runTest {
        val fenced = "Sure! Here is the JSON:\n\n```json\n" + json(one("Sumner Link Trail", 6486202530L)) + "\n```\n"
        val reply = DeepSeekClient(FakeCall(text = fenced)).completeJson(DeepSeekStrategist.SYSTEM, "user")

        assertTrue(reply is AiReply.Ok)
    }

    @Test
    fun `the app reports a missing key as a missing key, not as a network failure`() = runTest {
        val call = FakeCall(text = json(one("Sumner Link Trail", 6486202530L)))
        val strategist = DeepSeekStrategist(DeepSeekClient(call), InMemoryAiKeyStore(""))

        val reply = strategist.propose(context)

        assertTrue(reply.strategies.isEmpty())
        assertEquals(StrategyText.NO_KEY, reply.rationale)
        assertEquals(0, call.calls)
    }

    @Test
    fun `the model id comes from the rider's setting and defaults to the reference default`() {
        // data/AiKeyStore.kt holds the CANONICAL id the API lists (checked against GET /models on
        // 2026-09-16); the reference implementation's alias resolves to it but is not the name to
        // build on.
        assertEquals("deepseek-flash", AiKeyStore.DEFAULT_MODEL)
        assertEquals(AiKeyStore.DEFAULT_MODEL, InMemoryAiKeyStore().model)
        assertEquals(AiKeyStore.DEFAULT_MODEL, DeepSeekStrategist(DeepSeekClient(FakeCall()), InMemoryAiKeyStore()).modelId)
        assertEquals(
            "my-model",
            DeepSeekStrategist(DeepSeekClient(FakeCall()), InMemoryAiKeyStore(), "my-model").modelId,
        )
    }

    @Test
    fun `the key is stored trimmed, never shown whole, and can be cleared`() {
        val store = InMemoryAiKeyStore("  sk-abcdef123456  ")
        assertEquals("sk-abcdef123456", store.deepSeekKey)
        assertEquals(8 + AiKeyStore.MASK_VISIBLE_CHARS, store.maskedKey().length)
        assertTrue("the whole key is never painted", !store.maskedKey().contains("abcdef"))
        assertTrue(store.maskedKey().endsWith("3456"))
        assertEquals("", InMemoryAiKeyStore("").maskedKey())

        store.clearKey()
        assertEquals("", store.deepSeekKey)
        assertTrue(!store.hasKey())
    }
}
