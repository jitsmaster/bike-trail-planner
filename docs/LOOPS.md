# LOOPS — prefer not to ride the same way back

Decision: D31. Requested by the user 2026-09-16 ("the preference not to take the same way back, so
always a loop"), with two clarifications from the user in the same conversation:

* **"If the roads allows"** - a preference, never a promise. When no safe loop exists, ride out and
  back and say so.
* **"under 5km is acceptable"** - the budget for going out of the way to make a loop is 5 km of extra
  distance over the direct return.

Everything below is measured against the real network (self-hosted Valhalla, the route the app plans
from home today: 5.867 km out to Sumner Link entrance 110713098).

---

## 0. Status, and the measurement that superseded section 2 (2026-09-26)

**IMPLEMENTED** (D47). The pass lives in `domain/LoopPolicy.kt` (the metric, the via fan, the choice)
and `Planner.loopPass` (the network work). Knobs: `prefer_loop` (on), `loop_max_extra_km` (5.0),
`loop_via_offsets_m`, `loop_max_candidates` (6), `loop_max_quiet_vias` (2).

Re-measured on the real network before shipping the feature
(`build/live-loop-before.txt`, raw request/response pairs in `build/loop-probe/`):

| return leg | length | re-used share of the outbound ways |
|---|---|---|
| **the mirrored outbound (what the app built before D47)** | 5.867 km | **1.000** (the identical 12-way set) |
| the return routed on its own, no via | 6.270 km | 0.917 (11 of 12) |

Perpendicular sweep, return routed `entrance -> via -> home`, extra measured against the 6.270 km
direct return:

| offset / side | return | extra | re-used share |
|---|---|---|---|
| 445 m W | 7.296 km | +1.03 km | 1.000 |
| 891 m W | 8.242 km | +1.97 km | 1.000 |
| 1336 m W | 9.186 km | +2.92 km | 1.000 |
| **2000 m W** | 11.248 km | **+4.98 km** | **0.500** |
| 3000 m W | 14.540 km | +8.27 km | 0.667 (past the 5 km budget) |
| 445 m E | 6.483 km | +0.21 km | 0.917 |
| 891-2000 m E | 9.8-11.0 km | +3.5-4.7 km | 0.917 |
| 3000 m E | 12.096 km | +5.83 km | 0.917 (past the budget) |

**Section 2's "+1336 m south-east -> 8% shared" row is SUPERSEDED and must not be quoted.** That 8%
was an artifact of the D46 defect: `parseRoute` took `trip.legs.first()` for the shape, and a routed
via point makes Valhalla return one leg per break, so tracing "the return" traced only its first half
(entrance -> via) while its length counted both halves. The table above traces whole return legs.
The honest consequence for the rider, measured with the SHIPPED configuration (`build/live-loop-after.txt`,
per-attempt detail with `PLANNER_DEBUG=1`):

* **from home there is no loop inside the rider's 5 km budget.** Nine return legs are routed and graded
  (the direct return, the six perpendicular offsets, and two small streets the plan observed). The only
  genuinely different way back - the 2000 m offsets, overlap 0.538 - costs +5.3 km (past the budget)
  **and fails D10**; the small-street candidate (overlap 0.692, +4.60 km) also fails D10. Everything
  compliant inside the budget re-uses 92.3% of the outbound roads, which is the same way back in the
  rider's terms. The screen says so.
* **away from the saved home (47.2716,-122.244) a loop is found and it is 1.11 km SHORTER**: the return
  routed on its own re-uses 30.0% of the outbound roads (70% new), and the ride stays PASS.

`loop_max_candidates` is 6 on that evidence: raising it to 8 adds four HTTP calls and the two extra
offsets (2000 m, both sides) both fail D10 from home.

---

## 1. The problem, measured

The planner mirrors the outbound commute to build the return. Traced road way ids for that route:

| | ways |
|---|---|
| outbound | 12 |
| return (as built today) | 15 |
| shared | 11 |

**The return re-uses 92% of the outbound roads.** So the ride really is the same way back. (The two
legs are not byte-identical - 5.87 km out vs 6.27 km back - but that is turn penalties and
one-way/access asymmetry, not a different route.)

## 2. What the server will and will not let us do

**Cannot: exclude the corridor.** `exclude_polygons` in this Valhalla build caps at **100 vertices per
polygon** and **10,000 m total circumference**. The outbound corridor is 198 shape points and 5.9 km,
so both limits are hit (verified: HTTP 400 `error_code 176` for vertices, `error_code 167` for
circumference). Do not try to make a loop this way.

**Can: route the return through a via point** placed off the outbound corridor. Measured, using the
midpoint of the outbound shape offset perpendicular, then `route(entrance -> via -> origin)`:

| via point | return length | roads shared with the outbound |
|---|---|---|
| 445 m south-east | 7.02 km | 58% |
| 891 m south-east | 6.27 km | 58% |
| **1,336 m south-east** | **7.20 km** | **8%** |
| 1,336 m north-west | 7.34 km | 75% |
| direct return (today) | 6.27 km | 92% |

So a near-complete loop costs about **+1 km**, well inside the user's 5 km budget.

## 3. The metric

Loop-ness is a number, not an opinion:

```
overlap(outbound, back) = |ways(outbound) ∩ ways(back)| / |ways(outbound)|
```

Way ids come from the same `trace_attributes` call the planner already makes to grade the route, so this
needs no new data. 1.0 = the same roads back; 0.0 = entirely new roads.

## 4. The algorithm

Runs only when `prefer_loop` is on AND a plan otherwise succeeds (so it can never turn a good plan
into no plan), and only for the candidate that would be chosen.

1. Route the return **separately** (`entrance -> origin`) instead of mirroring the outbound.
2. Build a bounded set of via candidates, up to `loop_max_candidates` (default 10):
   - perpendicular offsets from the outbound shape at ~0.5 / 1 / 2 / 3 km, both sides (this is what
     produced the 8% row above);
   - plus the quiet-link pool the planner already collects for D13 (low measured volume only).
3. For each: `route(entrance -> via -> origin)`, then trace and grade it like any other road leg.
4. Keep the ones that are **compliant under D10 over the whole trip** (outbound + trail + return) and
   whose extra distance is within `loop_max_extra_km` (5.0).
5. Choose by **lowest overlap**, then lowest extra distance. Report the winner's overlap percentage.
6. If nothing qualifies, keep the mirrored out-and-back and label the ride plainly.

Cost: at most `loop_max_candidates` route+trace pairs, and only on the chosen candidate - measure and
record the added plan time. Every call goes through the cached `RouteService`, so a repeat plan of the
same request is free.

## 5. Model and screens

* `PlannedRoute.returnCommute: RouteResult?` - null means "mirrored out-and-back" (today's behaviour),
  non-null means a real return leg. `totalM` becomes
  `commute.lengthM + (returnCommute ?: commute).lengthM + trailRoundTripM`.
* `Planner.buildRoundTrip` must build the shape from the REAL return leg when there is one, rather than
  reversing the outbound. (It was just fixed for a double-append bug; keep the regression tests green.)
* `RouteOverlay` colours the real return leg from its own traced edges instead of mirroring the
  outbound runs.
* `MapsUrlBuilder` must include the return leg's waypoints so Google follows the loop, not the
  outbound mirrored.
* The saved-trip store: the geometry it stores is the shape that is drawn, so it inherits the fix
  automatically. Bump the payload version so trips saved with the old mirrored geometry are re-planned.

## 6. What the rider is told

* Loop: "You come back a different way: X% of the roads on the way back are new."
* Out and back, no loop available: "No different safe way back was found, so this one comes back the
  same way."
* Never claim a loop that did not happen, and never trade D10 for one.

## 7. Limits that must stay visible (do not let the UI imply otherwise)

1. **A linear trail is still ridden both ways.** The loop applies to the road legs. Riding the trail
   back is the point of a trail ride; riding the access road twice is the annoyance.
2. **A two-entrance loop needs a second safely reachable entrance.** This corridor has exactly one
   (the Bridge Street cluster, measured 2026-09-16), so the corridor ride cannot loop end-to-end today.
   Where a second entrance is reachable (some regional trails, or a different corridor), entering at
   one and leaving at another is the strongest form of loop and should be preferred over a via-point
   detour.
3. **Safety decides.** A compliant out-and-back always beats a non-compliant loop.
