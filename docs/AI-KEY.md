# AI-KEY — the DeepSeek key, the AI strategist, and what it does and does not see

Status: **IMPLEMENTED (seam + key + Settings + tests), 2026-09-16.** The design is `docs/AI-PLANNING.md`
(user's answer D27); the client coordinates and the reuse traps are `docs/research/deepseek-android.md`.
The strategist is **not wired into `Planner.kt` yet** — this is the seam, the key, the screen, the cache
and the tests. Wiring is the next step.

---

## 1. How to get a key

1. Open `https://platform.deepseek.com` and create an account.
2. Add credit under **Top up** (DeepSeek bills per token; without credit the call fails).
3. Go to **API keys** -> **Create new API key**. The key looks like `sk-...` and is shown **once**.
   Copy it straight into the app; if it is lost, delete it and make a new one.
4. In the app: **Settings -> AI route planning**, paste it into the key field, tap **Save**, then tap
   **Test key**. A working key answers "That key works." in a second or two.

There is no build-time secret and nothing to edit by hand: D27 chose "configurable from the app's own
UI". No key is compiled into the APK, and none belongs in `local.properties`, `assets/`, or this repo.

## 2. Where the key is stored

| | |
|---|---|
| Container | `EncryptedSharedPreferences`, file name **`secure_settings`** (`data/AiKeyStore.kt`) |
| Master key | Android Keystore, `MasterKey` scheme **`AES256_GCM`** |
| Key encryption | **`AES256_SIV`** (preference *names*) |
| Value encryption | **`AES256_GCM`** (preference *values*) |
| Preference names | `deepseek_api_key`, `deepseek_model` |
| Backup | not backed up: the manifest already sets `android:allowBackup="false"` |
| Logging | none. The key is never logged, never printed, never put in a `BuildConfig` field, never written to an unencrypted file |
| On screen | masked: eight dots plus the last four characters (`AiKeyStore.maskKey`) |

`EncryptedAiKeyStore` degrades instead of crashing: if a phone cannot open its keystore (or the pref
file is corrupt), the AI section says so and every read answers "no key". There is deliberately **no
fallback to plain preferences** — the key is either encrypted or not stored at all.

`AiKeyStore` is an interface with an `InMemoryAiKeyStore`, because this project's unit tests are plain
JVM tests and Android's crypto cannot run there.

## 3. What is sent, and what is not

| Sent to `api.deepseek.com` | Never sent |
|---|---|
| The **names** of the trails the app already found near the rider ("Sumner Link Trail") | Any latitude or longitude. The app's own coordinates are not in the request |
| Trail length, surface, and whether the trail was verified | The rider's saved home, or the address in `assets/config.json` |
| The **names** of streets with a measured low traffic volume | The computed route geometry, the polyline, or the waypoints |
| How far the rider wants to ride, and their stated rules | Ride history, saved trips, or anything from the Room database |
| | The key itself is only ever in the `Authorization` header, added by the SDK |

This is the privacy line of `docs/AI-PLANNING.md` section 2: the model chooses among options the app
has already enumerated, and reasoning about a named trail and a named quiet street does not need a
position. The section is stated on the Settings screen in rider words.

Cost: **one request per NEW plan**. A reused saved trip (D26) never calls the model, and a cached
strategy reply (`data/StrategyCache.kt`) is not called again either, so replanning the same ride costs
nothing. Each request is billed to the rider's own DeepSeek account.

## 4. Guardrails, all enforced in code (`data/DeepSeekClient.kt`)

| Guard | Value | Where it lives |
|---|---|---|
| Rounds | **1**. No tool loop, no `while(true)`, no tools declared | there is no loop, only attempts |
| Timeout | **20 s** wall clock, then the call is cancelled | `withTimeout(DEFAULT_TIMEOUT_MS)` |
| Max output tokens | **700** | passed to every transport call; asserted by a test |
| Retries | **exactly 1**, and never after a valid reply | one shared retry budget |
| Retry triggers | a transport error, a timeout, or a reply that will not parse | the reference retries 3 times on transport only and caps nothing |
| Cache | the reply is stored by request key (origin bucket + miles) | `data/StrategyCache.kt`, cap 20 |
| Offline / no key | the deterministic planner | `FallbackStrategist` |

The reference implementation (`docs/research/deepseek-android.md` gotcha 1: a `while(true)` with a
tool-call cap and no wall-clock limit) is deliberately **not** copied.

Note on the one retry: `docs/AI-PLANNING.md` section 3 says "1, only on a transport error" and, in the
same row, "a malformed reply is retried once". Both are honoured by spending the **single** retry on
either condition, and never retrying a valid reply.

## 5. What the model may say, and what the app does with it

The reply is JSON: `{"rationale": "...", "strategies":[{"trail","entryId","direction","viaRoad","reason"}]}`.
Before a strategy can reach Valhalla it must survive `StrategyValidator`
(`domain/RouteStrategist.kt`), which DROPS it unless:

* `trail` is one of the candidate trails in the request (matched case-insensitively, whitespace-normalised),
* `entryId` is one of **that** trail's entries,
* `viaRoad` is null or one of the quiet-link names that have a measured low volume,
* `direction` is one of D3's two directions, and
* the same (trail, entrance, direction) has not already been suggested.

Every drop is explained to the rider in rider language, and the app falls back to its own order. A
hallucinated trail name or a made-up street is therefore free: it is filtered before it can be routed.

## 6. The degradation ladder (`FallbackStrategist`, docs/AI-PLANNING.md section 7)

1. **No key** -> the app's own order, and Settings says the app plans without AI.
2. **The call fails or times out** -> the app's own order, with one line explaining it.
3. **The reply is invalid, or every strategy is rejected** -> the app's own order, and the rejected
   suggestions are named with the reason.
4. **The reply is valid but the routes fail D10** -> the deterministic candidates are still tried, so
   the AI can only ever add options. It can never make the app worse than it is today.

## 7. How to clear the key

**Settings -> AI route planning -> Clear.** That removes `deepseek_api_key` from the encrypted
preferences; the model name stays. To remove it from the phone completely, also clear the app's data
(Android Settings -> Apps -> Bike Trail Planner -> Storage -> Clear storage), which deletes the
encrypted file and the Keystore entry with it. Rotating the key on the DeepSeek site invalidates the
old one immediately, which is the right response if the key is ever suspected of leaking.

There is no key in this repo, and the project's secret scan stays clean (`docs/SECURITY.md` SEC-12).

## 8. Code map

| File | What it is |
|---|---|
| `data/AiKeyStore.kt` | `AiKeyStore` interface, `EncryptedAiKeyStore`, `InMemoryAiKeyStore`, `maskKey` |
| `data/DeepSeekClient.kt` | `ChatCall` (the transport seam), `DeepSeekClient` (timeout, token cap, one retry, JSON parse), `DeepSeekHttpCall` (aallam SDK + Ktor OkHttp) |
| `data/StrategyCache.kt` | `StrategyCache`, `StrategyCacheKey` (SHA-256 of origin bucket + miles), `PrefsStrategyCache`, `InMemoryStrategyCache`, cap 20 |
| `domain/RouteStrategist.kt` | `RouteStrategist`, `StrategyContext`, `TrailOption`, `EntryOption`, `StrategyReply`, `Strategy`, `StrategyValidator`, `ValidatingStrategist`, `DeterministicStrategist`, `DeepSeekStrategist`, `CachedStrategist`, `FallbackStrategist` |
| `ui/SettingsScreen.kt` | the "AI route planning" section: masked key, Save, Clear, model, privacy line, Test key |
| `app/src/test/java/com/biketrailplanner/AiStrategistTest.kt` | 23 tests, all with fakes: parsing, validation drops, both fallbacks, no-key, cache, timeout and retry counts, token caps, masking |

Dependencies added (nothing else): `com.aallam.openai:openai-client:4.1.0`,
`io.ktor:ktor-client-okhttp:3.0.0`, `androidx.security:security-crypto:1.1.0-alpha06`.

## 9. The security note added to docs/SECURITY.md

Recorded there as **SEC-18**. Summary: this app now holds a bearer credential the rider typed, so the
threat is (a) the key leaking off the device and (b) the rider's local trail and street names going to a
third party. Mitigations: Android-Keystore-encrypted preferences, no plaintext fallback, no logging,
masked display, no coordinates in the request, one request per new plan, a 20 s timeout, a 700-token
cap, and a one-tap Clear. Residual risk: DeepSeek's own retention policy applies to what is sent, and
the key is only as safe as the phone's lockscreen.
