package com.biketrailplanner.data

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Effort
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The single network call this app makes to a model: one chat completion, returning the assistant
 * text. It is a seam, so a test can drive every failure mode (timeout, transport error, malformed
 * reply) with no network at all, and so [DeepSeekClient]'s guardrails can be asserted on call counts.
 *
 * [maxOutputTokens] is a hard cap the transport must put on the request; it is passed in rather than
 * read from a field so the cap is visible at every call site.
 */
fun interface ChatCall {
    suspend fun call(system: String, user: String, maxOutputTokens: Int): String
}

/** Why a call did not produce a usable reply. Rider wording lives in the UI layer, not here. */
enum class AiFailure {
    /** No key is stored, so nothing was sent. */
    NO_KEY,

    /** No answer arrived inside the wall-clock limit. */
    TIMEOUT,

    /** The connection failed (DNS, TLS, refused, socket reset). */
    TRANSPORT,

    /** An answer arrived, but it was not usable JSON. */
    UNPARSEABLE,
}

/** The outcome of one model call. [Failed.detail] is for diagnosis only: never painted on a screen. */
sealed interface AiReply {
    data class Ok(val json: JsonObject) : AiReply

    data class Failed(val reason: AiFailure, val detail: String? = null) : AiReply
}

/**
 * One single-shot DeepSeek chat completion, with the guardrails of docs/AI-PLANNING.md section 3
 * enforced IN CODE rather than trusted to a prompt:
 *
 * | Guard | Where |
 * |---|---|
 * | One round, never a tool loop | there is no loop: one [ChatCall] per attempt, no tools are ever declared |
 * | 20 s wall clock, then cancel | `withTimeout(timeoutMs)` around every attempt |
 * | Max output tokens 700 | the [maxOutputTokens] cap passed to every [ChatCall] |
 * | Exactly one retry, only after a non-answer | [RETRIES] attempt budget, spent on a transport error, a timeout, or a reply that will not parse |
 * | A valid reply is never retried | the retry budget is only reached when the attempt failed |
 *
 * The reference implementation this copies (docs/research/deepseek-android.md section 4, gotcha 1)
 * is a `while(true)` with no wall-clock limit and no round cap. That shape is deliberately NOT
 * copied: a planning screen waits at most two attempts of at most 20 s.
 *
 * Nothing here logs anything. The key lives in the [ChatCall], and the reply text is never printed.
 */
class DeepSeekClient(
    private val call: ChatCall,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
    private val retries: Int = RETRIES,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * One chat completion whose reply must be a JSON object. A malformed reply is retried once (the
     * one shared retry budget) and then reported as [AiFailure.UNPARSEABLE], which the caller treats
     * exactly like any other failure: plan without AI.
     */
    suspend fun completeJson(system: String, user: String): AiReply =
        oneShot(system, user, maxOutputTokens)

    /**
     * The "Test key" action in Settings: ONE minimal call, capped at [PING_TOKENS] output tokens, so
     * checking a key costs as close to nothing as a chat completion can. It deliberately does not
     * demand JSON, because a key can be perfectly good while a model refuses to answer in JSON.
     */
    suspend fun ping(): AiReply = oneShot(PING_SYSTEM, PING_USER, PING_TOKENS, requireJson = false)

    private suspend fun oneShot(
        system: String,
        user: String,
        maxTokens: Int,
        requireJson: Boolean = true,
    ): AiReply {
        var spent = 0
        while (true) {
            val outcome = attempt(system, user, maxTokens, requireJson)
            if (outcome is AiReply.Ok) return outcome
            // One retry only, and only after a non-answer. A valid reply never reaches this line.
            if (spent >= retries) return outcome
            spent++
        }
    }

    private suspend fun attempt(
        system: String,
        user: String,
        maxTokens: Int,
        requireJson: Boolean,
    ): AiReply {
        val raw = try {
            withTimeout(timeoutMs) { call.call(system, user, maxTokens) }
        } catch (timeout: TimeoutCancellationException) {
            return AiReply.Failed(AiFailure.TIMEOUT, "no answer within " + timeoutMs + " ms")
        } catch (cancelled: CancellationException) {
            // The rider left the screen. Never retry, never report a failure that the rider caused.
            throw cancelled
        } catch (error: Throwable) {
            val reason = if (isTransportTimeout(error)) AiFailure.TIMEOUT else AiFailure.TRANSPORT
            return AiReply.Failed(reason, error.message?.take(DETAIL_LIMIT))
        }
        if (!requireJson) return AiReply.Ok(JsonObject(emptyMap()))
        return parse(raw)
    }

    /** Tolerant parse: bare JSON, a fenced block, or JSON with prose around it are all accepted. */
    private fun parse(raw: String): AiReply {
        val body = extractJsonObject(raw) ?: return AiReply.Failed(AiFailure.UNPARSEABLE, "no JSON object")
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        return parsed?.let { AiReply.Ok(it) } ?: AiReply.Failed(AiFailure.UNPARSEABLE, "not a JSON object")
    }

    private fun isTransportTimeout(error: Throwable): Boolean {
        if (error is IOException) return error.message?.contains("timeout", ignoreCase = true) == true
        return error::class.java.simpleName.contains("Timeout", ignoreCase = true) ||
            error.message?.contains("timeout", ignoreCase = true) == true
    }

    companion object {
        /** docs/AI-PLANNING.md section 3: a planning screen must not wait on a model. */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /** docs/AI-PLANNING.md section 3: the reply is a short JSON list. */
        const val MAX_OUTPUT_TOKENS = 700

        /** docs/AI-PLANNING.md section 3: exactly one retry. */
        const val RETRIES = 1

        /** The key test asks for one word, not a plan. */
        const val PING_TOKENS = 5

        private const val DETAIL_LIMIT = 120

        private const val PING_SYSTEM = "Reply with the single word OK."

        private const val PING_USER = "Reply with the single word OK."

        /**
         * The first balanced `{...}` block in a reply. The model may wrap its JSON in a fence or add
         * a sentence; both are accepted rather than throwing the whole call away
         * (docs/research/deepseek-android.md section 8, tolerant extraction).
         */
        fun extractJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until raw.length) {
                val ch = raw[index]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    inString -> Unit
                    ch == '{' -> depth++
                    ch == '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, index + 1)
                    }
                }
            }
            return null
        }
    }
}

/**
 * The production transport: the aallam OpenAI Kotlin SDK over the Ktor OkHttp engine, pointed at
 * DeepSeek's OpenAI-compatible host (docs/research/deepseek-android.md section 1).
 *
 * The client is built per call and closed afterwards, exactly as the reference does, so a changed
 * key or model applies at once and no OkHttp thread pool is left behind. Streaming is not used.
 */
class DeepSeekHttpCall(
    private val key: String,
    private val model: String,
    private val host: String = HOST,
) : ChatCall {

    override suspend fun call(system: String, user: String, maxOutputTokens: Int): String {
        val client = OpenAI(
            token = key,
            host = OpenAIHost(host),
            timeout = Timeout(
                request = REQUEST_TIMEOUT_S.seconds,
                connect = CONNECT_TIMEOUT_S.seconds,
                socket = SOCKET_TIMEOUT_S.seconds,
            ),
        )
        try {
            val request = ChatCompletionRequest(
                model = ModelId(model.ifBlank { AiKeyStore.DEFAULT_MODEL }),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = system),
                    ChatMessage(role = ChatRole.User, content = user),
                ),
                temperature = TEMPERATURE,
                maxTokens = maxOutputTokens,
                // MEASURED 2026-09-16: this model THINKS before it answers, and with the default
                // reasoning budget it spent the ENTIRE max_tokens allowance on thinking and returned
                // an EMPTY content string - at 700 tokens and again at 2000 - so the planner could
                // never read a strategy and always fell back to its own order. Measured with the
                // app's real prompt: `reasoning_effort` "low" still returned empty, "none" returned
                // the JSON, and `thinking: {"type":"disabled"}` returned it too. The planner asks a
                // small, closed question with the candidates already ranked for it, so the thinking
                // budget buys nothing here; turning it off is what makes the AI path work at all.
                reasoningEffort = Effort(REASONING_EFFORT),
            )
            val completion = client.chatCompletion(request)
            return completion.choices.firstOrNull()?.message?.content.orEmpty()
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        /** OpenAI-compatible base URL (docs/research/deepseek-android.md section 1). */
        const val HOST = "https://api.deepseek.com"

        private const val TEMPERATURE = 0.2

        /**
         * "none" turns the model's thinking off. Without it the reply comes back EMPTY with
         * `finish_reason = length`, because the reasoning consumes the whole output allowance
         * (measured; see the comment at the request). `low` is not enough.
         */
        private const val REASONING_EFFORT = "none"
        private const val CONNECT_TIMEOUT_S = 10L
        private const val SOCKET_TIMEOUT_S = 20L
        private const val REQUEST_TIMEOUT_S = 20L
    }
}
