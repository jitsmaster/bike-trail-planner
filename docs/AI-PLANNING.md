# AI-PLANNING — DeepSeek in the trip planner

Status: design for the role the user approved on 2026-09-16 (D27): **the model proposes strategies and
never draws geometry.** Valhalla computes the route and the existing deterministic grader enforces D10,
so the rider's safety rule is never delegated to a model.

Reuse guide for the client itself (exact coordinates, retry behaviour, the two gotchas):
`docs/research/deepseek-android.md`.

---

## 1. Where the model sits in the pipeline

Today `Planner.plan()` does: build candidate entrance/trail combinations -> route each with Valhalla
-> grade with `trace_attributes` -> enforce D10 -> rank (D13 bands, D6 rotation). The expensive and
non-deterministic part is the **middle**: which combination to try at all.

```
request (origin, target miles)
        |
        v
[1] saved-trip lookup (D26)  --hit--> reuse: no Valhalla call, NO MODEL CALL
        | miss
        v
[2] deterministic candidate enumeration  (what the planner already does: entrances ranked by
        |                                  measured approach, trails, corridor chains)
        v
[3] RouteStrategist.propose(candidates)  <-- the ONE model call, single-shot JSON, cached by request
        |
        v
[4] each strategy becomes a candidate -> Valhalla route -> grader -> D10
        |
        v
[5] ranked result; if every AI-proposed strategy fails D10, fall back to the deterministic order
```

Two properties this shape buys, both of which the user asked for:

* **Reuse beats the model.** A request that a saved trip answers never reaches step 3 (D26), so the
  same ride is never planned - or paid for - twice.
* **Safety is not the model's job.** Step 4 is unchanged code. A strategy that the model likes and
  D10 dislikes is discarded exactly like any other candidate that breaks the rule.

## 2. The contract

```kotlin
/** Proposes which of the already-enumerated strategies to try, and why. Never emits geometry. */
interface RouteStrategist {
    suspend fun propose(context: StrategyContext): StrategyReply
}

data class StrategyContext(
    val targetMiles: Double,
    /** Trails the app already considers reachable, with what it knows about each. */
    val candidates: List<TrailOption>,
    /** Road names with a MEASURED low or medium volume: the only legal D13 via-points. */
    val quietLinks: List<String>,
    /** The rider's own rules, in words, so the reply is judged against them. */
    val rules: List<String>,
)

data class TrailOption(
    val trail: String,          // "Sumner Link Trail"
    val trailMiles: Double,     // rideable length
    val surface: String,        // "paved" / "compacted" / "gravel"
    val entries: List<EntryOption>,
    val verified: Boolean,      // false for a live-discovered trail (D23)
)

data class EntryOption(val id: Long, val rank: Int, val hint: String)  // id = OSM node id

data class StrategyReply(val strategies: List<Strategy>, val rationale: String)

data class Strategy(
    val trail: String,
    val entryId: Long,
    val direction: String,      // "longer-side-first" | "other-side-first"
    val viaRoad: String?,       // a NAME from quietLinks, or null
    val reason: String,         // one rider-facing sentence
)
```

**No coordinates are sent.** The app has already enumerated and ranked the options; the model chooses
among them by name, and reasoning about a named trail and a named quiet street does not need a
latitude. That is a deliberate privacy choice: the exact position never leaves the device, only the
names of the trails near it. (State this in `docs/SECURITY.md` when it ships.)

## 3. Guardrails, and why each one exists

| Guard | Value | Why |
|---|---|---|
| Rounds | **1** (single-shot JSON, no tool loop) | The reference implementation is a `while(true)` with only a tool-call cap and no wall-clock limit (`docs/research/deepseek-android.md` gotcha 1). This task needs one decision, not an agent loop. |
| Timeout | 20 s, then cancel | A planning screen must not wait on a model. |
| Max output tokens | 700 | The reply is a short JSON list. |
| Retries | 1, only on a transport error | A malformed reply is retried once, then the deterministic order is used. |
| Call budget | at most 1 call per NEW plan | Reused trips cost zero; a re-grade after a data change costs zero. |
| Offline / no key | deterministic planner | The app must work with no key and no network (D12/D23 already make it degrade). |
| Validation | every strategy is re-checked before use | `trail` must be one of `candidates`, `entryId` one of that trail's entries, `viaRoad` one of `quietLinks`. Anything else is dropped, not trusted. |

The validation row is the important one: the model is a **ranking hint**, so a hallucinated trail name
or a made-up street costs nothing - it is filtered before it can reach Valhalla.

## 4. What the model is asked, in words

A single system message (short, stable) plus one user message:

* system: "You are choosing between bicycle trips that another program has already checked for safety.
  You never invent routes, streets or coordinates. Reply with JSON only."
* user: the target mileage, the candidate list above, the quiet-link names, and the rules:
  prefer trails over roads; keep the trail portion at or above 2 miles; a ride within the mileage asked
  for is not a detour; never repeat the ride taken last time; if the rider is away from home say so.

## 5. Cache, storage and cost

The model's reply is cached under the same request key as the saved trip (origin bucket + target
miles, `docs/ROUTE-STORE.md`), so:

* planning the same request again = **one indexed lookup, no network, no tokens**;
* a changed safety-data fingerprint forces a free local re-grade and **still no model call**, because
  the strategy (which trail, which entrance) did not change - only the verdict did.

## 6. What this does NOT do

* It does not let the model produce, edit, or rank road geometry.
* It does not let the model declare anything safe. D10 remains the only authority.
* It does not run when a saved trip already answers the request.
* It does not send the rider's coordinates or their saved home.

## 7. Degradation ladder (must all be implemented)

1. No key set -> deterministic planner, and Settings says why.
2. Key set, call fails or times out -> deterministic planner, one line in the plan warnings.
3. Reply invalid or every strategy rejected by validation -> deterministic planner, and the rejected
   strategies are listed for the rider with the validation reason.
4. Reply valid but every strategy fails D10 -> deterministic order is tried as well, so the model can
   never make the app *worse* than it is today.

Rule 4 is what makes this safe to ship: the AI can only add options, never remove the deterministic
ones.
