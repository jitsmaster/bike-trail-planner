# DeepSeek (LLM) integration on Android — reuse guide

Source of every claim: `/Volumes/Satechi 1/Dev/android-product-research-agent` (read-only), working
tree at commit `615e096`. The integration is verified working: a completed on-device run is recorded
in README.md:104-112, and the JVM prototype ran live queries (IMPLEMENTATION.md:114-125).

Path aliases (all under the source repo root): `app/.../agent/X.kt` means
`app/src/main/java/com/productresearch/agent/agent/X.kt`; the same shortening applies to the
`settings/`, `data/`, `work/`, `db/` and `ui/` packages. `prototype/...` is the earlier JVM spike.

Docs vs code: deviations are flagged inline (model id, key-test wording, `maxExtractPages`
enforcement). Where docs and code disagree, the code line is the fact.

---

## 1. Client + transport

| Item | Value | Citation |
|---|---|---|
| HTTP/SDK library | aallam OpenAI Kotlin SDK (KMP), `com.aallam.openai:openai-client:4.1.0` | gradle/libs.versions.toml:12,36; app/build.gradle.kts:61 |
| Transport engine | Ktor OkHttp engine, `io.ktor:ktor-client-okhttp:3.0.0` | gradle/libs.versions.toml:13,37; app/build.gradle.kts:62 |
| Plain OkHttp (other client) | `com.squareup.okhttp3:okhttp:4.12.0` — used by the Tavily client, not by DeepSeek | gradle/libs.versions.toml:14,38; app/build.gradle.kts:63; app/.../data/TavilyClient.kt:7,17 |
| Base URL | `https://api.deepseek.com` | app/.../ResearchApplication.kt:69 (`OpenAIHost`), and :90 |
| Endpoint | `chat/completions` (SDK constant; the app never writes the path) | verified in resolved artifact `com.aallam.openai:openai-core-jvm:4.1.0`, class `com/aallam/openai/api/core/Endpoint.class` |
| Key attachment | passed as the SDK constructor argument `token`; the app builds no HTTP headers | app/.../ResearchApplication.kt:67-71 |
| Authorization header | `Authorization: Bearer <token>`, added inside the SDK | verified: `Bearer` + `Authorization` strings live in `com/aallam/openai/client/internal/HttpClientKt.class` in the same artifact |

The client is rebuilt per run, so a changed key or model applies at once
(ResearchApplication.kt:25, :64-80; ResearchService.kt:56-57):

```kotlin
val openAI = OpenAI(
    token = settings.deepSeekKey,
    host = OpenAIHost("https://api.deepseek.com"),
    timeout = Timeout(socket = 120.seconds),
)
```

Request shape: standard OpenAI-compatible chat completions, in two forms: the DSL builder
`chatCompletionRequest { model = ...; messages = ...; temperature = 0.2; tools { ... }; toolChoice = ToolChoice.Auto }`
(app/.../agent/ResearchAgent.kt:51-74), and the data class
`ChatCompletionRequest(model = ModelId(...), messages = listOf(...), maxTokens = 5)`
(app/.../ResearchApplication.kt:92-97, the key-test ping).

**Streaming: not used.** There is no `stream` flag, no streaming callback, no Flow-based call
anywhere in the DeepSeek path (repo-wide search for `stream` returns no call site). Every request is
the blocking `openAI.chatCompletion(request)` — ResearchAgent.kt:142, ResearchApplication.kt:91.

---

## 2. Model selection

- Default model id: **`deepseek-v4-flash`** — `AppSettings.DEFAULT_MODEL`, app/.../settings/AppSettings.kt:39.
- It **is** read from settings at runtime: `ModelId(settings.model.ifBlank { AppSettings.DEFAULT_MODEL })`
  — app/.../ResearchApplication.kt:75 (agent) and :93 (key test); settings key `"model"`
  (AppSettings.kt:28-30, :42).
- The Settings UI exposes one option only: `val models = listOf("deepseek-v4-flash")`
  (app/.../ui/SettingsScreen.kt:159; comment on :158), confirmed by README.md:75.
- Prototype default was `ModelId("deepseek-chat")` — prototype/.../agent/DeepSeekLoop.kt:21.
- **Deviation:** SPEC.md:50 and :97, and IMPLEMENTATION.md:52, still document `deepseek-chat` default
  plus `deepseek-reasoner` as selectable in settings. The shipped code does not offer `deepseek-reasoner`.
- No model fallback, no per-task routing, no dynamic discovery. A wrong model id fails the chat call and
  therefore the whole run (ResearchAgent.kt:142 -> ResearchService.kt:87-91).

---

## 3. Key storage

- Class: **`AppSettings`** — app/.../settings/AppSettings.kt:8.
- Scheme: `EncryptedSharedPreferences.create(context, "secure_settings", ...)` (prefs file
  `"secure_settings"`, AppSettings.kt:12); master key
  `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM)` (AppSettings.kt:13-15);
  key encryption `AES256_SIV`, value encryption `AES256_GCM` (AppSettings.kt:16-17).
- Accessors: `deepSeekKey` (prefs key `"deepseek_api_key"`, AppSettings.kt:20-22, :40), `tavilyKey`
  (`"tavily_api_key"`, :24-26, :41), `model` (:28-30, :42), `region` (:32-34, :43). Writes `.trim()` the key.
- Guard: `hasRequiredKeys() = deepSeekKey.isNotBlank() && tavilyKey.isNotBlank()` (AppSettings.kt:36).
- Where the user types it: Settings screen text field labelled "DeepSeek API key"
  (app/.../ui/SettingsScreen.kt:75-83). Saved keys are masked with the last 4 characters visible
  (`maskKey`, SettingsScreen.kt:148-152) and revealed while the field has focus (:82). "Save" writes all
  fields (:105-113); "Save & test" writes then calls `app.testKeys()` (:115-127).
- Key test: `ResearchApplication.testKeys()` (ResearchApplication.kt:83-105) sends one
  `maxTokens = 5` "ping" chat completion (:92-97) plus a Tavily search (:84-86); messages look like
  `"DeepSeek key failed; Tavily OK."` (:100-103). The UI says "Save & test"; SPEC FR-10.3 (:99) says
  "Check connection".
- When the key is missing: the run is blocked **before** any network call, in the ViewModel, with
  `"Add your DeepSeek and Tavily API keys in Settings first."` (app/.../ui/ResearchViewModel.kt:63).
  The foreground service does not re-check keys: a blank key simply fails at the API call and the run is
  marked FAILED with the exception text (ResearchService.kt:87-91).
- Debug-only seeding: with `BuildConfig.DEBUG`, empty key slots are filled from the plaintext file
  `filesDir/pra_keys.properties` (ResearchApplication.kt:38, :46-62; AGENTS.md:50-51). Release builds
  have no plaintext key source.

---

## 4. Error handling, retries, caps

- Timeouts: the DeepSeek client sets **only** a socket timeout, `Timeout(socket = 120.seconds)`
  (ResearchApplication.kt:70). No connect or request timeout is set in this repo for the OpenAI client
  (the SDK defaults apply and are not configured here). For contrast, the Tavily client uses OkHttp
  `connectTimeout(20s)` / `readTimeout(45s)` (app/.../data/TavilyClient.kt:17-20).
- Retry, with backoff: `chatWithRetry` — app/.../agent/ResearchAgent.kt:137-154.
  - Attempts: `repeat(3)` (ResearchAgent.kt:140) — i.e. 2 retries.
  - Retryable: `java.io.IOException`, or exception class name containing `"Timeout"`, or message
    containing `"timeout"` (ResearchAgent.kt:145-147). Everything else is rethrown at once (:148).
  - `CancellationException` is rethrown immediately, so user cancel is never retried (:144).
  - Backoff: `delay(2_000L * (attempt + 1))` between attempts — 2 s, then 4 s (ResearchAgent.kt:150).
  - Exhausted: `throw last ?: RuntimeException("chat failed")` (:153).
  - Spec match: SPEC.md:104 says "retry up to 2 times per HTTP call". The code comment on :137 says
    "up to 3 attempts" — same behaviour, counted differently.
- Tool-call failures are **not** retried. `ToolExecutor.safe` (app/.../agent/Tools.kt:231-236) catches
  the exception and returns `{"error":"..."}`, which the model reads as tool output (Tools.kt:41).
- Budget caps, quoted:
  - `maxToolCalls: Int = 8` — ResearchAgent.kt:26. Enforced, and it drives the bail-out below.
  - `maxExtractPages: Int = 6` — ResearchAgent.kt:27. **Not enforced in code**: it is only substituted
    into the system prompt placeholder `{{MAX_EXTRACT_PAGES}}` (ResearchAgent.kt:37-38;
    app/src/main/assets/agent_prompt.md:6).
  - `temperature = 0.2` on every request (ResearchAgent.kt:54, :60).
- Caps that are absent (state them plainly):
  - No `maxTokens` on agent requests (only the key-test ping uses `maxTokens = 5`,
    ResearchApplication.kt:95), so a single reply length is unbounded.
  - No token or cost accounting: the response `usage` field is never read anywhere.
  - No wall-clock cap: `withTimeout` appears nowhere in the repo, although SPEC FR-8.3 (:89) targets a
    10-minute run.
  - No loop-iteration cap (see section 5).
- Budget bail-out ("stop calling tools, write the report now"): ResearchAgent.kt:92-106.
  When the next round would exceed `maxToolCalls`, the loop sets `toolBudgetExceeded = true`, drops the
  dangling assistant `tool_calls` message (:96-99), appends the fixed user message
  `"Tool budget exhausted. Produce the final JSON report now using only the data already gathered. Do not call more tools."`
  (:100-103), and re-issues the request **without** `tools` (:50-55).
- What the UI/notification shows on failure:
  - Unparseable report: the run is marked FAILED and the notification says
    `"Research finished but produced no usable report."` (ResearchService.kt:68-72).
  - Any other exception: `notifier.failed(runId, e.message?.take(120) ?: "Research failed.")`
    (ResearchService.kt:87-91). The notification shows that reason as its text and deep-links to the
    run's results (app/.../work/ResearchNotifier.kt:69-79).
  - History shows the status label `"Failed"` (app/.../ui/ResearchScreen.kt:258). Pre-run validation
    errors are shown inline on the Research screen (ResearchViewModel.kt:61-64; ResearchScreen.kt:172).

---

## 5. The agent/tool loop

Tools are declared **inline in each request** with the SDK DSL — no separate registration:

- `tools { function(name = "search_products", description = "...", parameters = ToolSchemas.searchProducts) ... }`
  plus `toolChoice = ToolChoice.Auto` — ResearchAgent.kt:61-74.
- Parameters are JSON-Schema builder lambdas (`JsonObjectBuilder.() -> Unit`) declared in
  app/.../agent/Tools.kt:13-39 (`ToolSchemas.searchProducts`, `ToolSchemas.extractPage`), each with
  `type: object`, `properties`, and a `required` array.
- Dispatch is a `when (name)` in `ToolExecutor.execute(name, args)`; an unknown name returns
  `{"error":"unknown tool: $name"}` (Tools.kt:48-52).
- Arguments arrive as a JSON **string** on the tool call and are parsed with
  `json.parseToJsonElement(call.function.arguments).jsonObject` (ResearchAgent.kt:113).
- Each result is appended as a `ChatRole.Tool` message carrying `toolCallId` and the function `name`
  (ResearchAgent.kt:129-135).

Annotated core loop (trimmed from ResearchAgent.kt:49-127):

```kotlin
while (true) {                                                   // no iteration counter
    val request = if (toolBudgetExceeded) {                      // bail-out state: no tools sent
        chatCompletionRequest { model = this@ResearchAgent.model; messages = chatMessages; temperature = 0.2 }
    } else {
        chatCompletionRequest {
            model = this@ResearchAgent.model
            messages = chatMessages
            temperature = 0.2
            tools {                                              // declared per request
                function(name = "search_products", description = "...", parameters = ToolSchemas.searchProducts)
                function(name = "extract_page", description = "...", parameters = ToolSchemas.extractPage)
            }
            toolChoice = ToolChoice.Auto
        }
    }
    val response = chatWithRetry(request)                        // 3 attempts, 2 s/4 s backoff
    val message = response.choices.first().message
    chatMessages += ChatMessage(role = message.role, content = message.content.orEmpty(),
        toolCalls = message.toolCalls, toolCallId = message.toolCallId)

    val calls = message.toolCalls.orEmpty()
    if (calls.isEmpty()) return message.content.orEmpty()        // <-- the only normal exit

    if (toolCalls + calls.size > maxToolCalls) {                 // 8-call budget -> forced synthesis
        toolBudgetExceeded = true
        val last = chatMessages.removeAt(chatMessages.size - 1)  // drop dangling assistant tool_calls
        if (last.role == ChatRole.Assistant) chatMessages += ChatMessage(role = ChatRole.Assistant, content = last.content.orEmpty())
        chatMessages += ChatMessage(role = ChatRole.User, content = "Tool budget exhausted. ...")
        continue
    }
    for (call in calls) {                                        // execute this round's tool calls
        if (call !is ToolCall.Function) continue
        toolCalls++
        val args = json.parseToJsonElement(call.function.arguments).jsonObject
        chatMessages += toolResult(call, executor.execute(call.function.name, args))
    }
}
```

Iteration cap: **there is none.** The loop is `while (true)` (ResearchAgent.kt:49) and the only exit is
an empty `toolCalls` list (:87-90). The budget gate bounds *executed* tool calls, not rounds, and no
counter would stop a model that kept emitting tool calls.

Final structured output: the model is instructed to answer with only a JSON object
(app/src/main/assets/agent_prompt.md:20-27; the built-in fallback prompt is AgentPrompt.kt:49-59).
Parsing is tolerant, in three stages (`ResearchRepository.extractReport`,
app/.../data/ResearchRepository.kt:194-225): (1) direct `json.decodeFromString<Report>(raw)`,
(2) first ```` ```json ```` fence via regex, (3) balanced-brace scan that tracks strings and escapes.
The target type is `@Serializable data class Report` with a default for every field
(app/.../data/Models.kt:6-14). If all three stages fail, `completeRun` returns null and the run is
marked FAILED (ResearchRepository.kt:51-56; ResearchService.kt:68-72).

---

## 6. Minimal reusable sketch (what the bike app copies)

Domain-free: a stored key, one request, a parsed reply. Everything below is an API name taken from the
source files; comments cite the origin line. Retry, tools and streaming are deliberately omitted.

```kotlin
// deps: com.aallam.openai:openai-client:4.1.0, io.ktor:ktor-client-okhttp:3.0.0,
//       kotlinx-serialization-json:1.7.3, androidx.security:security-crypto:1.1.0-alpha06

class SecureKeyStore(context: Context) {                        // app/.../settings/AppSettings.kt:8-22
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",                                      // AppSettings.kt:12
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    var deepSeekKey: String
        get() = prefs.getString("deepseek_api_key", "") ?: ""
        set(value) = prefs.edit().putString("deepseek_api_key", value.trim()).apply()
    fun hasKey(): Boolean = deepSeekKey.isNotBlank()             // cf. AppSettings.kt:36
}

class DeepSeek(
    private val keys: SecureKeyStore,
    private val modelId: String = "deepseek-v4-flash",           // AppSettings.kt:39
) {
    private val json = Json { ignoreUnknownKeys = true }         // ResearchAgent.kt:30

    private fun client(): OpenAI = OpenAI(                       // ResearchApplication.kt:67-71
        token = keys.deepSeekKey,
        host = OpenAIHost("https://api.deepseek.com"),
        timeout = Timeout(socket = 120.seconds),
    )

    /** One non-streaming chat completion; returns the assistant text. */
    suspend fun complete(system: String, user: String): String {
        val request = chatCompletionRequest {                    // ResearchAgent.kt:57-60
            model = ModelId(modelId)
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = system),
                ChatMessage(role = ChatRole.User, content = user),
            )
            temperature = 0.2
        }
        val response = client().chatCompletion(request)          // ResearchAgent.kt:142
        return response.choices.first().message.content.orEmpty() // ResearchAgent.kt:78, :89
    }

    /** Ask for JSON, then parse it (tolerant parsing: ResearchRepository.kt:194-225). */
    suspend fun completeJson(system: String, user: String): JsonObject =
        json.parseToJsonElement(complete(system, user)).jsonObject // ResearchAgent.kt:113
}
// Call site: guard the key first (ResearchViewModel.kt:63), then run off the main thread.
```

To make it production-grade, add: the 3-attempt backoff from `chatWithRetry`
(ResearchAgent.kt:137-154), and — new, not in the source — a wall-clock timeout and a `maxTokens` value,
because the source app has neither (see section 4).

---

## 7. Security / privacy notes actually found in that repo

- Stated requirements: keys encrypted and "SHALL NOT be hardcoded or logged" — SPEC.md:98 (FR-10.2);
  "no data leaves the device except API calls ...; no analytics; no accounts" — SPEC.md:114-115.
- Keys in source: none in `app/`. `local.properties` and `prototype/local.properties` are gitignored
  (.gitignore:5-7), and `git ls-files --error-unmatch prototype/local.properties` reports the file is
  **not tracked**. Caveat for anyone copying files: that file exists on disk and holds a live DeepSeek
  key (prototype/local.properties:3) — never copy it, and treat the key as compromised if it ever
  leaves the machine. The prototype reads keys from env vars first, then that file
  (prototype/.../Main.kt:76-92).
- Debug-only plaintext path: `filesDir/pra_keys.properties` seeding is gated on `BuildConfig.DEBUG`
  (ResearchApplication.kt:38, :47-51) and documented in AGENTS.md:50-51.
- Logging: keys are never logged; the prompt loader logs character counts only (AgentPrompt.kt:32,
  :41). Run logs record sizes and counts (ResearchRepository.kt:54-63; ResearchAgent.kt:121-122). One
  line does include the first 500 characters of an unparseable model reply (ResearchRepository.kt:54) —
  that text can contain scraped page content.
- What is sent to the DeepSeek API: the system prompt, the user query, and the whole running
  conversation including tool results — i.e. search snippets and extracted page text, truncated to
  3500 characters per page (Tools.kt:195-196). Nothing else leaves the device for the LLM.
- What is sent to Tavily: the search query and the URLs to extract, with the Tavily key in the JSON
  body (`put("api_key", apiKey)` — TavilyClient.kt:31, :48; endpoint `https://api.tavily.com`, :58).
- Gaps found (do not copy them):
  - `android:allowBackup="true"` with no backup rules (app/src/main/AndroidManifest.xml:11), while the
    app's own security section (SPEC.md:115) treats on-device data as sensitive. The bike app's
    SECURITY.md SEC-10 already asks for `allowBackup="false"`.
  - No certificate pinning, no `network_security_config` (HTTPS-only by Android default).
  - The debug key-seeding path stores API keys as plaintext in app-private storage.

---

## 8. What to copy vs what to rewrite

| Piece | Verdict | Source | Note |
|---|---|---|---|
| SDK choice + coordinates (`openai-client:4.1.0` + `ktor-client-okhttp:3.0.0`) | Copy | libs.versions.toml:12-13,36-37 | The bike app has okhttp only; ktor + aallam must be added |
| Client construction (token, `OpenAIHost`, `Timeout(socket=120s)`) | Copy, add connect/request timeouts | ResearchApplication.kt:67-71 | Source sets only the socket timeout |
| Base URL `https://api.deepseek.com` | Copy | ResearchApplication.kt:69 | OpenAI-compatible; any OpenAI-compatible host works |
| Model id from settings with a default | Copy | ResearchApplication.kt:75; AppSettings.kt:39 | Keep the default in one constant |
| `AppSettings` encrypted-prefs class | Copy (rename) | AppSettings.kt:8-45 | Bike app has no security-crypto dependency yet |
| Settings UI field + mask + Save & test | Copy the pattern, rewrite the screen | SettingsScreen.kt:75-152 | Masking helper is 5 lines |
| `chatWithRetry` (3 attempts, 2 s/4 s, IOException/Timeout only) | Copy | ResearchAgent.kt:137-154 | Keep the CancellationException rethrow |
| Tool declaration/dispatch pattern (`ToolSchemas` + `when` executor + `safe`) | Copy the shape, write your own tools | Tools.kt:13-52, :231-236 | JSON-Schema builders are easy to add |
| Agent loop with tool-budget bail-out | Copy the bail-out idea | ResearchAgent.kt:92-106 | Add a round cap and a wall-clock timeout — source has neither |
| Tolerant JSON extraction (parse -> fence -> balanced braces) | Copy | ResearchRepository.kt:194-225 | Reusable as-is, no domain types needed |
| `@Serializable` report DTO with defaults on every field | Rewrite | data/Models.kt:6-39 | This is the research project's schema |
| System prompt in an asset file + on-device override | Copy the idea | AgentPrompt.kt:22-46 | Nice for tuning without a rebuild |
| Foreground service, notifications, Room history, DealRanking, Tavily client, AmazonParser, image sanitising | Rewrite or drop | work/, db/, agent/DealRanking.kt, data/TavilyClient.kt, agent/Tools.kt:228-268 | Domain-specific to product research |

Adoption checklist for the bike app. Paths in this list are **bike-app** paths; the source app is
marked "source". Current bike-app state: `gradle/libs.versions.toml:37-39` has okhttp +
kotlinx-serialization and no ktor/aallam/security-crypto; secrets today come from `local.properties`
into `BuildConfig` (bike `app/build.gradle.kts:1-13`).

1. Add `ktor = "3.0.0"` and `aallamOpenai = "4.1.0"` to `[versions]`, plus `ktor-client-okhttp` and
   `aallam-openai` entries to `[libraries]` (mirror source `gradle/libs.versions.toml:13,36-37`).
2. Add `securityCrypto = "1.1.0-alpha06"` + `androidx-security-crypto` (source `libs.versions.toml:11,35`)
   if the user types the key on-device.
3. Add `implementation(libs.aallam.openai)` and `implementation(libs.ktor.client.okhttp)`
   (mirror source `app/build.gradle.kts:61-62`).
4. If R8 minification is ever enabled, copy the `-dontwarn com.aallam.**` / `-dontwarn io.ktor.**` and
   kotlinx-serialization keep rules (source `app/proguard-rules.pro:1-10`). The bike app is currently
   `isMinifyEnabled = false` (bike `app/build.gradle.kts:43`).
5. Prefer EncryptedSharedPreferences over `BuildConfig` injection for a user-supplied key; keep the rule
   that secrets never enter source.

---

## Files read for this guide

`agent/ResearchAgent.kt`, `agent/Tools.kt`, `agent/AgentPrompt.kt`, `agent/DealRanking.kt` (skim),
`settings/AppSettings.kt`, `ResearchApplication.kt`, `ui/SettingsScreen.kt`, `ui/ResearchViewModel.kt`,
`ui/ResearchScreen.kt`, `work/ResearchService.kt`, `work/ResearchNotifier.kt`,
`data/ResearchRepository.kt`, `data/Models.kt`, `data/TavilyClient.kt`, `db/Entities.kt`,
`db/ResearchDao.kt`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`,
`app/src/main/assets/agent_prompt.md`, `gradle/libs.versions.toml`, `SPEC.md`, `IMPLEMENTATION.md`,
`README.md`, `AGENTS.md`, `.gitignore`, `prototype/.../agent/DeepSeekLoop.kt`, `prototype/.../Main.kt`,
`prototype/build.gradle.kts`; plus a byte-level check of the resolved
`com.aallam.openai:openai-client-jvm:4.1.0` and `openai-core-jvm:4.1.0` artifacts in the Gradle cache
for the endpoint path and the authorization header.
