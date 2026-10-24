# INTEGRATION — the app, wired end to end

Written 2026-09-16, after the integration step that put the trail data, the saved-trip store and the
AI seam into the shipped Android app. Every number below is measured on this machine or on the
user's phone (serial `R5CW30PD58H`); nothing here is an estimate. Evidence for the phone runs is in
`docs/phone-evidence/`.

Scope: what is wired NOW, the exact flow, what is still NOT done. The designs it implements are
`docs/ROUTE-STORE.md` (D26), `docs/AI-PLANNING.md` (D27), `docs/REGIONAL-TRAILS.md` (D23 a) and
`docs/TRAIL-DISCOVERY.md` (D23 live half).

---

## 1. What is wired now

| Piece | Where | State |
|---|---|---|
| Bundled regional trail set (191 OSM relations, WA + Portland) | `data/RegionalTrailLoader.kt` -> `AppContainer.regional(origin)` | WIRED: loaded into the planner's trail set and access points |
| Bundled six (verified, with closures and measured traffic) | `data/TrailRepository.kt` | UNCHANGED, and still first in the merged list |
| Live "find trails near here" (Overpass, mirrors, freshness, cache) | `AppContainer.discovery` -> `PlanViewModel.findTrailsNearHere()` -> plan screen button, and the "no trails near you" card | WIRED to the UI; the merged set is used by the next plan |
| Saved trips (Room, gzip payload, request key, overlap rule) | `domain/RouteStore.kt` (storage), `Planner(routeStore = ...)` (overlap), `PlanViewModel` (saved-first) | WIRED: saved on every compliant plan, offered before any work, adopted with one tap |
| AI seam (DeepSeek, validated, cached, fallback ladder) | `AppContainer.strategist(origin)` -> `Planner(strategist = ...)` | WIRED: one call per new plan, its order applied to the candidate list, its lines shown to the rider |
| Round-trip geometry (D3) | `Planner.buildRoundTrip` | FIXED this session: the drawn shape is the ride that is reported (see section 6) |

The trail set the planner is given is the MERGED one:

```
bundled six (verified=true where the data says so, closures, measured traffic)
  + regional set (verified=false, no closures, no surface audit, no measured traffic)
  + live-discovered trails (verified=false)   <- only after the rider taps "Find trails near here"
```

Joins, corridors and road links are the bundled ones; neither a regional nor a discovered trail
brings any, so the walker rides one rideable piece at a time and the planner routes no unmeasured
link between trails.

## 2. The flow, exactly

```
tap "Plan ride"
  |
  [0] saved-trip lookup        PlanPolicy.decide(routeStore.matching(origin, targetMiles, fingerprint))
  |                            -> read every time the origin or the distance changes, BEFORE any work.
  |                               >2 matches for THIS request -> the saved rides are published as
  |                               options (no Valhalla call, no model call). "Plan ride" is the
  |                               "plan a new ride" action and always remains available.
  |
  [1] candidate enumeration    Planner.candidateSpecs: every trail whose access points are within
  |                            trail_radius_km (5.0) of the LIVE origin; entrances ranked by measured
  |                            approach (bundled probe table near home, live POST /route measurement
  |                            otherwise, and for regional/discovered entrances, which have no probe);
  |                            the candidate budget is spent ROUND ROBIN across trails (section 5).
  |
  [2] ONE AI call              Planner.askStrategist: the planner builds the StrategyContext itself
  |                            (trails + their entrances + the quiet links it may offer + the rider's
  |                            rules, no coordinates), calls the strategist once, validates the reply
  |                            against that same context, and maps the survivors onto the candidate
  |                            specs. Cached by request (origin bucket + mileage) in
  |                            PrefsStrategyCache, so replanning the same request pays nothing.
  |
  [3] evaluation               the AI's surviving order first, then the app's own order, ALWAYS
  |                            (rule 4: the AI can only add options). Each candidate: one POST /route
  |                            for the commute, one trace_attributes for its edges, the trail walk,
  |                            and any closed-segment detours.
  |
  [4] grader + D10             unchanged: SafetyGrader, GradeBreakdown, evaluateD10. D10 is the only
  |                            authority on safety; the model never grades anything.
  |
  [5] D26 overlap rule         candidates whose road way-id set is 100% inside a saved trip's set for
  |                            this request are dropped (RouteOverlap, reused, not re-implemented);
  |                            survivors are ranked by novelty against the most similar saved trip.
  |
  [6] D13 bands + D6 rotation  unchanged: 1.40x acceptable, 1.80x explicit detour, 2.0 mi trail floor.
  |
  [7] save + publish           every compliant option is saved (geometry, hand-off waypoints in TRAVEL
                               order, road way ids, named segments, fingerprint); aiUsed and the AI
                               lines are published to the screen.
```

## 3. The AI seam, as wired

* `Planner(..., strategist: RouteStrategist? = null)`. Null is today's planner, exactly.
* `AppContainer.strategist(origin)` builds the documented production ladder:
  `FallbackStrategist(CachedStrategist(ValidatingStrategist(DeepSeekStrategist(...))))`.
  The key is read from encrypted preferences once per plan and is never logged or printed.
* The planner validates the reply a second time (`StrategyValidator.validate`) so a strategist that
  is not wrapped in the decorator still cannot send a hallucinated trail name or a made-up street to
  Valhalla.
* `aiUsed` is true only when the model's own surviving strategies differ from the app's own order.
  With no key, a failed call, an unreadable reply or a reply that IS the app's order, `aiUsed` is
  false and `strategyNotes` carries the reason in rider language.
* `strategyNotes` shows up to 4 lines: what the AI suggested (only when its reply is being acted on),
  the model's own one-line reasons, and the rationale, which already contains the validator's
  rejections ("...which is not a trail this app found", "...which has no measured low traffic").
  Every line passes `RiderText.aiLine`, which rewrites anything that would be internal vocabulary.

**A deliberate deviation from the literal brief, and why.** The brief said the context is built
before the candidate specs are evaluated, and that it "already knows the quiet links". It does not:
the planner's only legal D13 via-points are roads it has MEASURED at a low or medium volume, and it
learns those from the traces it makes while evaluating. So before the first evaluation the quiet-link
list is empty, the model is told `(none)`, and any `viaRoad` it names is dropped by the validator
with a rider-readable line. The model's effective lever today is WHICH trail, WHICH entrance and
WHICH order — which is exactly what the app enumerated — plus, when the direct pass finds nothing
compliant, the order the D13 alternate/link budget is spent in and which measured quiet link is tried
first. That is implemented and tested; it is the honest version of "the AI proposes strategies".

## 4. Measured numbers (this machine, then the phone)

### Regional set (offline, `RegionalTrailLoaderTest`)

| | value |
|---|---|
| relations in the shipped file | 191 |
| loaded as trails | **186** (5 repeat a bundled corridor: same name and >= 60% of the geometry within 300 m, so the VERIFIED bundled copy wins; reported in `Loaded.coveredByBundled`) |
| multi-piece trails loaded | 87 (the file ships 89 multi-piece relations; 2 of the 5 covered ones are multi-piece) |
| geometry points loaded | 14,430 (of 14,550 in the file) |
| access points sampled around home | 2,025, at most 12 per trail, all negative synthetic ids, all `probeRouteKmFromHome = null` |
| trails within 5 km of home | 7 (the bundled six + 1 regional: King County Interurban Trail, r1900725) |

### The merge does not change the ride from home (live, local Valhalla, 10 mi)

`LiveRegionalProbeTest` (probe, not a gate) plans the same request twice: with and without the
regional set.

| | chosen | options | attempts | time |
|---|---|---|---|---|
| bundled six only | Sumner Link Trail, 10.00 mi | Sumner Link Trail, Interurban Trail Milton | 12 | 113 ms |
| + regional set | Sumner Link Trail, 10.00 mi | Sumner Link Trail, Interurban Trail Milton | 12 | 116 ms |

The regional trail that IS reachable (King County Interurban Trail) is tried with 2 of the 12
candidate slots and fails D10 (1.22 mi of trail on a 10 mi request), so the delivered ride is the
same one, from the same two options. That is the behaviour D23 asked for: a wider trail set, the same
near-home answer.

### Round-trip geometry (D3)

Measured on the live route above: **drawn 16,084 m vs declared 16,093 m (-0.1%)**, 421 points,
largest single step 497 m (a real trail vertex; Valhalla's road shapes are ~25 m). Before this
session's fix the same shape carried an extra return commute plus a straight jump from the origin
back out to the access point, i.e. about 2x the commute (5,866 m here) too long.

### The phone (serial R5CW30PD58H, 2026-09-16)

All evidence in `docs/phone-evidence/` (`integration-*`).

| what | result |
|---|---|
| `python3 tools/acceptance.py R5CW30PD58H` | **PASS** — install, launch, theme (light in device night mode: light 88.2%, dark 1.1%), plan, no leaked vocabulary, hand-off reaches Google Maps |
| plan screen at launch | "Rides you already saved" with **5 cards** (the rider's new cap), the full-width "Find trails near here" control, "Plan ride", "Use my location", "Refresh trails" — every one of them on screen (`integration-plan-5-suggestions-2026-09-16.png`/`.xml`) |
| one live plan from home, 10 mi | chosen **Riverwalk Trail, 10.0 mi round trip, 1.4 mi on trail, 91% measured** (`integration-result-with-ai-2026-09-16`) |
| the AI, on that plan | ONE call was made. The reply could not be read, so the app planned with its own order and said so on the result screen: *"The AI's answer could not be read, so the app planned with its own order."* `aiUsed = false`. This is ladder rule 3 working, not a crash: the plan is the plan the app makes without any AI |
| adopting a saved ride | tapped its row: the result screen opened with the stored geometry and numbers and the line *"This is the ride you saved: Interurban Trail Edgewood, 6.8 mi round trip."* — **no Valhalla call and no model call** (`integration-adopted-saved-ride-2026-09-16`). The acceptance gate's own hand-off ran from exactly this state and reached Google Maps, which proves the hand-off URL built from the saved waypoints |
| "Find trails near here" | tapped; the answer came back from `overpass-api.de` with OSM data of 2026-09-16T19:39:51Z and **zero route relations**, reported as *"no trails found within 5.0 km; ... Nothing was added, so the next plan is unchanged."* (`integration-find-trails-near-here-2026-09-16`). The recorded fixture for the same query carries 7 relations (`docs/TRAIL-DISCOVERY.md`), so this is the empty-answer case, and the app said the honest thing about it rather than claiming there are no trails. Worth re-running before trusting the number |
| layout lesson | five suggestion cards and a three-button action row do not both fit under the distance chips on a 1440x3088 screen. Measured: a Material `TextButton` per card makes every row 180 px tall and pushed "Use my location"/"Refresh trails" off the bottom of the sheet. A clickable 41 px row and the discovery action on its own line fixed it, and the numbers are in the evidence tree above |

**The AI line DID appear, and it reported a failure.** The cause is inside the reply transport
(`data/DeepSeekClient.kt`, not part of this step): an answer that carries no JSON object is reported
as unreadable, and a model that returns prose, or that returns its answer in a reasoning field rather
than in `content`, produces exactly that. It is not a key problem: Settings says "A key is set" and
the stored model is `deepseek-v4-flash`. Reported to the parent rather than worked around here.

## 5. Two changes the merge forced (both measured, both deliberate)

1. **Entrances with no bundled probe are measured live, wherever the rider is.**
   `assets/access_points.json` carries a measured approach for the bundled entrances only. A regional
   or discovered entrance has none, so it used to be ranked by crow-flies - the proxy D24 disproved -
   even while the rider stood at the saved home. Measured: that alone put 12 of the 12 candidate slots
   on one 25 km regional trail, which pushed Sumner Link out of the options entirely and delivered a
   capped 12.05 mi ride instead of the 10.00 mi one. The planner now measures the approach for every
   entrance the probe table cannot answer for (8 per trail, through the same cache as everything
   else). Cost measured: 8 extra `POST /route` calls on the first plan from a new area, none after.
2. **The candidate budget is spent round robin across trails.**
   `candidateSpecs` used to take each trail's whole block of ranked entrances in turn, so one trail
   with several reachable entrances could fill `max_candidates` (12) by itself. It now takes the best
   entrance of every trail, then the second of every trail, and so on. The ranking rule is untouched:
   the best trail's best entrance is still tried first, and the first entrance of every reachable
   trail comes before the second entrance of any of them. This is the same principle D29 applies to
   the options the rider sees ("they have to be other RIDES"), applied one step earlier. Measured:
   options from home went from 1 back to 2, and the chosen ride from a 12.05 mi capped ride back to
   the 10.00 mi Sumner Link ride, once the regional set was loaded.

## 6. The round-trip geometry fix (D3)

`Planner.buildRoundTrip` used to append the return commute twice:

```
outbound           = commute out + trail pieces
full               = outbound + outbound.reversed()      <- already closes the loop at the origin
full              += commuteOut.reversed()                <- the extra leg, plus a jump back out
```

The drawn/stored shape was therefore 2x the commute longer than `PlannedRoute.totalM` and contained a
teleport from the origin back out to the access point. Fixed by dropping the third append. What is
drawn is now what is reported: the geometry equals `2 * (commute + trail one-way + detour)` within 1%,
asserted by name in `PlannerTest` and in two `PlannerIntegrationTest` tests (one of them on a plan
that really routes around a closed trail segment).

Correction worth keeping: the in-app MAP never read `roundTrip`. `RouteMap` draws
`RouteOverlay.build(route)`, which is built from `commute.points` + `roadSegments` + `trailLegs`, and
`MapsUrlBuilder` reads `commute.points`/`accessPoint`/`turnaround`. The surfaces that WERE affected
were the stored trip geometry (`SavedRouteCodec` -> `ReusableRoute.points`) and anything measuring it.

## 7. What is NOT done

1. **The AI's silent failure is not diagnosed to the line.** One live call was made and its reply was
   unreadable; the transport that reads it is `data/DeepSeekClient.kt`, which this step does not own.
   Until that is understood, the honest expectation on the phone is "the app plans its own way and
   says the AI's answer could not be read".
2. **`viaRoad` is never usable on a first plan.** The quiet-link list is empty until the planner has
   traced an approach (section 3). A later plan in the same area can use one.
3. **Saved trips are not re-graded in place.** A trip whose `data_fingerprint` differs is marked
   "Saved with older safety data" and adopting it plans the ride again instead of reusing the stored
   verdict. The free local re-grade the store design describes is not built.
4. **A regional trail's map colours are not drawn when it is adopted from the store.** A saved row
   keeps the geometry and the per-street summary, not the per-edge trace, so the adopted trip is drawn
   as one uncoloured outline; the numbers above the map are the stored ones.
5. **No per-street colouring for a saved trip, and no elevation/difficulty for any trail.** Unchanged
   from `docs/REGIONAL-TRAILS.md` section "What it is NOT".
6. **The discovery answer is not persisted between sessions** beyond the Room cache it already had,
   and the button re-queries with `forceRefresh = true` every time it is tapped (one Overpass call,
   ~1-10 s measured in `docs/TRAIL-DISCOVERY.md`).
7. **The empty discovery answer needs a second look.** The live lookup from home answered with a
   fresh stamp and zero relations, and the summary line then read "the cached answer found none" even
   though that answer was fetched live and not from the cache. The wording (and possibly the
   empty-answer handling) lives in `data/TrailDiscovery.kt`, which this step does not own. Nothing was
   added to the trail set, which is the safe outcome, but the sentence the rider reads is wrong.
8. **The regional set is not shown as "unverified" anywhere on the plan screen.** The planner's
   data-gap line about untagged surfaces and the "no local traffic data" warning still apply, but a
   rider cannot yet see that the trail they picked is a regional one. That belongs in the UI review
   the user has not asked for yet.

## 8. Tests

`app/src/test/java/com/biketrailplanner/`:

* `RegionalTrailLoaderTest` (10): the shipped file's counts, the parse-once rule, name uniqueness,
  multi-piece spans, the access-point rules (cap, spacing, negative unique ids, no probe claimed),
  the synthetic id range, the merge, and a missing file.
* `PlannerIntegrationTest` (21): the AI order applied first with the app's order still evaluated, the
  model never seeing a coordinate, every AI suggestion failing D10 and the app's order still
  delivering, a hallucinated trail dropped, a throwing strategist, no key and a deterministic reply
  both leaving the plan identical to today's, the overlap rule (100% dropped, superset kept, nothing
  novel kept rather than refusing), the saved-first gate with call counters, the adoption URL equality
  (including that the stops are saved in travel order), the stale row, and the two round-trip geometry
  tests.
* `LiveRegionalProbeTest` (1, probe): plans with and without the regional set and writes
  `app/build/live-regional-probe.txt`.
* `PlannerTest` / `AiStrategistTest` / `RouteStoreTest`: strengthened where this step changed a
  contract (round-trip geometry; the canonical model id).

Whole suite: **249 tests, 0 failures, 0 skipped** (the previous count was 216).
