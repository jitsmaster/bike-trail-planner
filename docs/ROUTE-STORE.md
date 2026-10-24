# ROUTE-STORE — saving planned trips so planning costs nothing the second time

Status: **PROPOSAL** (2026-09-16). Written in response to the user's request:

> "make sure to save the already planed trips in the app, so no ai token is usaged replaning the same
> routes. If there are always more than 2 saved trips, planning should suggest them first. Otherwise,
> plan a new route. Planned new routes can overlap with existing routes in some roads, but cannot be
> 100% of the old routes. Propose the data structure and algorithms on how we can save these routes.
> That is efficient on space and time for load and save."

Every number below is measured from this repo, not estimated: field inventory from
`domain/PlanModels.kt` (`PlannedRoute`), encoding from `domain/PolylineCodec.kt`, existing storage from
`data/Room.kt`.

---

## 1. What has to be remembered, and what can be thrown away

`PlannedRoute` today carries ~20 fields, and most of them are *derivation*, not identity. Only five
things are actually needed to reuse a trip:

| Need | Comes from | Notes |
|---|---|---|
| **Draw it again** | `roundTrip: List<LatLon>` | the only large field |
| **Hand it to Google Maps again** | the packed waypoints (`MapsUrlBuilder`, max 9) | tiny, and re-deriving them needs the whole route |
| **Tell the rider what it is** | `breakdown`, `named`, `totalM`, `trailRoundTripM`, `estimatedTotalTimeS`, `trailLabel` | `named` is already aggregated by street name |
| **Rotate / never repeat twice** | `signature`, plus when it was last ridden | `Signature.of(...)` already exists (D6) |
| **Enforce the overlap rule** | the set of OSM **road** way ids | `GradedSegment.wayIds` already exists |

Thrown away on save (recoverable by re-planning, not needed to reuse):
`segments`, `roadSegments`, `cautions`/`ungraded`/`rejections` (unions of `named`), `trailLegs`,
`commute` (a `RouteResult` with maneuvers and its own shape), `detours`, `attempts`, `via`,
`distanceFactor`, `explicitDetour`.

**Measured sizes** (`LivePlanProbeTest.probeSavedRouteSizes`, run against the real routes from home
on 2026-09-16 - these are measurements, not estimates):

| Ride (10 mi target) | geometry points | road ways | gzipped payload |
|---|---|---|---|
| Interurban Trail Edgewood (shortest option) | 240 | 5 | 1,079 B |
| Interurban Trail Milton (chosen) | 468 | 12 | 1,537 B |
| Sumner Link Trail (longest commute) | 624 | 13 | 1,918 B |

* 11 compliant options for that one request: **mean 1,442 B**, min 1,060 B, max 1,918 B.
* At the 500-row cap that is **~704 KB total**, well under a single phone photo.
* The geometry dominates (Valhalla returns roughly one point per 25 m, so a 16 km round trip is
  240-624 points at ~3 bytes each); the road-way-id set is only 5-13 ids, because the grader emits
  **one `GradedSegment` per traced edge with `wayIds = listOf(edge.wayId)`**
  (`SafetyGrader.segment`, SafetyGrader.kt:219-236).

The first draft of this document estimated 6-9 KB per trip. That was wrong by about 4x, and the probe
exists so the number stays measured.

## 2. Data structure

One new Room table. Scalars stay columns (so they are queryable and indexable); the two bulky fields
are gzipped into one `payload` BLOB, because they are always read together and never queried
separately.

```sql
CREATE TABLE saved_route (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  signature         TEXT NOT NULL UNIQUE,   -- Signature.of(...), the D6 ride identity
  bucket            TEXT NOT NULL,          -- origin rounded to 2 dp (~1 km), INDEXED
  origin_lat        REAL NOT NULL,          -- exact origin, for the 250 m re-check
  origin_lon        REAL NOT NULL,
  target_miles      REAL NOT NULL,
  trail_label       TEXT NOT NULL,
  total_m           REAL NOT NULL,
  trail_round_m     REAL NOT NULL,
  time_s            REAL NOT NULL,
  graded_m          REAL NOT NULL,          -- so the UI can show "x% measured" with no payload read
  ungraded_m        REAL NOT NULL,
  d_m               REAL NOT NULL,
  e_m               REAL NOT NULL,
  road_way_count    INTEGER NOT NULL,       -- cheap guard before decompressing
  data_fingerprint  TEXT NOT NULL,          -- hash of traffic.json + limits.json + trails.json
  created_at        INTEGER NOT NULL,
  last_used_at      INTEGER NOT NULL,
  used_count        INTEGER NOT NULL DEFAULT 0,
  ridden_at         INTEGER,                -- NULL until actually ridden (rotation)
  payload           BLOB NOT NULL           -- gzip(JSON { geometry, waypoints, wayIds, named, notes, verdict })
);
CREATE INDEX idx_saved_route_bucket ON saved_route(bucket, target_miles);
CREATE INDEX idx_saved_route_lru    ON saved_route(last_used_at);
```

Inside `payload` (JSON, then gzip, one blob, one write, one read):

```json
{
  "v": 1,
  "geometry": "<encoded polyline, 1e-6>",
  "waypoints": [[47.240371,-122.262019], ...],     // <= 9, the Maps hand-off stops
  "wayIds": [12345, 12346, ...],                    // sorted ascending, the road identity
  "named": [{"n":"Jovita Boulevard East","m":1703,"g":"D","s":"25 mph","v":"10,358 (1996)"}],
  "notes": ["chained onto Riverwalk Trail at 47.203,-122.245 (0 m gap)."],
  "verdict": {"passes": true, "violations": []},
  "accessNode": 110713098,
  "turnaround": [47.1965,-122.2511]
}
```

Why one blob and not columns: it halves the row count of the hot path, keeps Room's generated code
small, and makes "read a saved trip" exactly one row + one gunzip. Why not one blob for *everything*:
`bucket`, `target_miles`, `road_way_count` and `last_used_at` must be queryable without decompressing
any payload, or a lookup would decompress every row.

## 3. Algorithms

### 3.1 Request key and lookup (this is the whole performance story)

An exact-origin key would miss every time, because GPS jitters by tens of metres. So:

```
lookup(req):
  bucket = round2dp(req.origin)                        # ~1 km cell
  rows   = SELECT * FROM saved_route
           WHERE bucket = :bucket AND target_miles = :miles      # index probe
  return rows.filter { distance(it.origin, req.origin) <= 250 m }
```

* Index probe is `O(log n)`; the 1 km bucket holds a handful of rows, so the filter is `O(k)`.
* The exact-origin column exists so the coarse bucket cannot hand back a trip that starts a kilometre
  away: the bucket is only a filter, the 250 m circle is the rule.
* No full-table scan anywhere. Nothing decompresses until a candidate row has passed both filters.
* `distance` is the existing `Geo.distanceM`.

Rejected alternative: bucket-edge probing (check 4 neighbour buckets). It costs 4 probes to cover the
same cases the exact-origin re-check covers with one, and it still cannot express the 250 m rule.

### 3.2 The reuse decision (the user's ">2 saved trips" rule)

```
decide(req):
  total  = SELECT COUNT(*) FROM saved_route
  match  = lookup(req)
  if total > 2 and match.isNotEmpty():
      return Suggest(match.sortedByDescending { lastUsedAt }.take(5))   # zero Valhalla, zero AI
  return PlanNew()                                                     # AI + Valhalla, then save
```

* "More than 2 saved trips" is read as **the whole table**, exactly as worded. It is a one-line change
  to use `match.size` instead — see the open question in section 6.
* `Suggest` never blocks `PlanNew`: the screen offers the saved trips **and** a "plan me a new one"
  action, so the rider is never trapped by the cache.
* Reuse also bumps `last_used_at` and `used_count` (one `UPDATE`), which is what the LRU eviction and
  the "least recently used first" suggestion order both read.
* `PlanNew` saves every option that passed the safety gate, not just the chosen one — they cost ~7 KB
  each and they are what gives the suggestion list real variety later.

### 3.3 The overlap rule: "cannot be 100% of the old routes"

Identity for this rule is the **road way ids**, not the trail and not the geometry, because that is
what "the same roads" means:

```
noveltyRatio(newIds, oldIds) = |newIds \ oldIds| / |newIds|      # 0.0 .. 1.0
fullyRepeats(newIds, saved)  = saved.any { old -> newIds.isNotEmpty() && old.wayIds.containsAll(newIds) }
```

The identity is the set of OSM way ids the grader reports, one per traced edge
(`wayIds = listOf(edge.wayId)`, `SafetyGrader.segment`). Known limit, inherited from the grader and
not introduced here: if Valhalla merges several OSM ways into a single traced edge, only that edge's
way id is recorded, which makes the overlap test slightly more permissive - it can miss a repeat, it
never invents one.

* Reject a newly planned candidate when `fullyRepeats` is true for **any** saved route at the same
  request key. That is the user's rule, exactly.
* Among the survivors, rank by `noveltyRatio` (higher first), so "different ride" means as different
  as the trail network allows. A candidate with novelty 0 is already gone, so ranking starts above it.
* Cost: a set difference over a few hundred longs, against a handful of saved routes. Microseconds,
  no allocation beyond one `HashSet`.
* Comparison uses the *saved* rows only for the same `bucket` + `target_miles` — never the whole table.

### 3.4 Safety must not be cached blind

A stored route carries a safety verdict computed from `traffic.json`, `limits.json` and the trail set.
If any of those change, that verdict is stale and reusing it would silently ship a route graded by
older data. So:

```
data_fingerprint = sha256(traffic.json bytes + limits.json bytes + trails.json bytes)
```

* On suggest, rows whose `data_fingerprint` differs are shown as **needs a re-check** and are not
  offered as ready to ride; planning re-runs (Valhalla + grader) but still **skips the AI call**,
  because the AI's job (choosing a strategy) is the expensive, non-deterministic part.
* This is the honest split of "no tokens replanning the same route": geometry re-verification is free
  and local, the model call is not.

### 3.5 Eviction

```
on save:
  if rows for bucket+miles > PER_KEY_CAP(20): delete the oldest last_used_at among them
  if COUNT(*) > GLOBAL_CAP(500):             delete oldest last_used_at overall
```

Both are index-backed `DELETE ... ORDER BY last_used_at LIMIT` style operations on `idx_saved_route_lru`.
Upsert by `signature` means re-saving an identical ride replaces it instead of duplicating it.

### 3.6 Complexity summary

| Operation | Time | Space |
|---|---|---|
| Lookup for a request | `O(log n)` probe + `O(k)` filter, `k` = rows in one 1 km cell (usually < 10) | no payload read |
| Reuse a saved trip | + one gunzip + one polyline decode, `O(points)` | ~7 KB |
| Save a trip | `O(1)` insert (plus an amortised eviction check) | ~7 KB |
| Overlap test | `O(w)` per saved route, `w` = way ids on the new candidate | one `HashSet` |
| Eviction | `O(log n)` per delete | - |

## 4. Where this plugs into the code that exists

* `data/Room.kt` — add `SavedRouteEntity`, `SavedRouteDao`, register in `AppDatabase` and bump the
  schema version (destructive migration is acceptable: the store is a cache).
* New `domain/RouteStore.kt` — `RouteStore` interface (`matching`, `save`, `markRidden`, `count`,
  `evict`) plus `InMemoryRouteStore` for tests, mirroring the existing `RideHistoryStore` pattern.
* `domain/SavedRouteCodec.kt` — `encode(PlannedRoute): SavedRouteRecord` and `decode(...): ReusableRoute`,
  using the existing `PolylineCodec`; the gzip is `java.util.zip.GZIPOutputStream`.
* `domain/Planner.kt` — the candidate loop gains one filter (section 3.3) and one ranking key
  (novelty), both fed from the store, so the rule lives with the other D13/D6 ranking rules.
* `ui/PlanViewModel.kt` — `plan()` first calls `decide(...)`; `Suggest` publishes the saved trips as
  the options with a "plan a new one" action, and stamps `last_used_at`.
* Rotation is unchanged: `RideHistoryStore` already records what was ridden, and the saved store keeps
  its own `ridden_at` for the same purpose.

## 5. What this does **not** do

* It does not store Valhalla or DeepSeek *requests*; the existing `route_cache` table already caches
  raw route/trace JSON by hash with a 7-day TTL, and that stays separate.
* It does not make a saved route safe by itself: a `data_fingerprint` mismatch forces a re-grade
  (section 3.4).

## 6. Answers (user, 2026-09-16)

1. **">2 saved trips" means trips saved FOR THIS REQUEST only** - the same start area and the same
   mileage. Rides saved elsewhere, or for another distance, never stop a new plan.
   Implemented as `PlanPolicy.decide(matches)`: `matches.size > SUGGEST_THRESHOLD` (2).
2. **A new route that contains a saved route whole IS allowed.** Only a new route that is 100%
   *inside* a saved one is rejected (`RouteOverlap.fullyRepeats`); a superset does add new road. It is
   ranked below a more novel candidate by `noveltyVsMostSimilar`.
3. **The DeepSeek key is configurable from the app's own UI** - no build-time secret and no file
   editing. It goes in encrypted preferences (`EncryptedSharedPreferences`, the same approach as
   `android-product-research-agent` - see `docs/research/deepseek-android.md`), and the app must
   degrade to the current deterministic planner when no key is set or the call fails.
4. **The AI proposes strategies; it never draws geometry.** Valhalla computes the route and the
   existing deterministic grader applies D10, so the rider's safety rule is never delegated to a
   model. A plan that the model proposes is accepted only if it survives the same gate as any other
   candidate.

The "plan me a new one" action always stays available, so a suggestion never traps the rider.
