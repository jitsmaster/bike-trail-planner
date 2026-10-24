# Bike trail corridor — connectivity graph and data verification

**Origin:** home in Edgewood, WA — `47.240371, -122.262019` (straight-line distances below are great-circle
from this point). Every ride starts and ends here.

**Trail set (decision D4, near-only ~8 km):** Sumner Link Trail, Riverwalk Trail (Puyallup), Interurban Trail
Edgewood, Interurban Trail Milton, Lake Tapps Parkway Trail, White River Trail (Auburn).

**Sources:** OpenStreetMap, fetched live through the Overpass API on 2026-09-15. Every query is listed in
[Appendix A](#appendix-a--overpass-queries-run); raw responses are in `docs/data/raw/`.
Nothing in this file is copied from `docs/research/` — all numbers were re-derived in this session.

**Scope warning:** an earlier draft of the project assumed Portland, OR. That is wrong and is ignored here.

---

## 0. Data freshness and re-verification

This dataset was re-derived from scratch against **fresh** OpenStreetMap data on **2026-09-15**, after the first
pass was found to have used stale mirror snapshots. HTTP 200 was **not** treated as proof of freshness: for
every Overpass response the `osm3s.timestamp_osm_base` field was read and had to be within 3 days of
`2026-09-15T21:24Z`. Stale responses were rejected and the query retried.

### Sources actually used

| source | what it proves | timestamp |
|---|---|---|
| `maps.mail.ru/osm/tools/overpass/api/interpreter` — 12 responses, every query in this file | the whole dataset | `osm3s.timestamp_osm_base` = `2026-09-15T21:32:03Z` … `2026-09-15T21:44:04Z`, i.e. minutes before use |
| `api.openstreetmap.org/api/0.6` — `relation/<id>/full` for all 6 relations, plus `way/419747831/history` | independent second source; per-element version, user and edit date | live at 2026-09-15T21:26Z; newest element edit inside the pull `2026-07-29T10:54:56Z` |

### Mirrors rejected as stale

| mirror | `timestamp_osm_base` observed | verdict |
|---|---|---|
| `overpass.kumi.systems` | `2026-06-01T08:52:28Z`, `2026-07-28T02:16:18Z`, `2026-05-06T03:25:00Z` | **stale — rejected, 200 OK with no warning** |
| `overpass.private.coffee` | `2026-06-01T08:52:28Z`, `2026-07-15T15:22:01Z`, `2026-05-06T03:25:00Z` | **stale — rejected** |
| `overpass-api.de` | served `2026-09-15T20:53:51Z` in the first pass, then went unreachable (connection refused) | fresh while reachable, unusable now |
| `lz4.overpass-api.de`, `z.overpass-api.de` | — | unreachable |
| `overpass.openstreetmap.ru` | — | connection timed out |
| `overpass.osm.ch` | — | TLS certificate expired; `/api/status` returned HTTP 400 |
| `overpass.osm.jp` | — | unreachable |
| `overpass.osm.rambler.ru`, `overpass.osm.xyz` | — | DNS does not resolve |

### Which of the first-pass files were stale

| first-pass file | `timestamp_osm_base` | status |
|---|---|---|
| `q1-relations.json` | 2026-09-15T20:53:51Z | was fresh |
| `q2-member-ways.json` | 2026-09-15T20:53:51Z | was fresh |
| **`q3-member-ways-nodes.json`** | **2026-07-28T02:16:18Z** | **stale — this is the file behind every length, topology, surface and access figure in this document** |
| `q4-rel9605518-meta.json` | 2026-06-01T08:52:28Z | stale |
| `q5-all-sumnerlink-named.json` | 2026-07-28T02:16:18Z | stale |
| `q6-near-accessno-way.json` | 2026-09-15T20:56:53Z | was fresh |
| `q7-levee-dike-names.json` | 2026-06-01T08:52:28Z | stale |
| `q8-puyallup-river-highways.json` | 2026-05-06T03:25:00Z | stale |
| `q9-gap-edgewood_milton.json` | 2026-09-15T20:59:53Z | was fresh |
| `q9-gap-sumnerlink_whiteriver.json` | 2026-09-15T21:01:52Z | was fresh |
| `q9-gap-tapps_whiteriver.json` | 2026-05-06T03:25:00Z | stale |
| `q10-roads-*.json` (3 files, deleted before the audit) | not recorded | unknown — re-fetched fresh |
| `q11-interurban-named-gap.json` | 2026-09-15T21:07:54Z | was fresh |
| `q12-white-river-and-paths.json` | 2026-09-15T21:07:54Z | was fresh |

Every stale file has been deleted and replaced by the fresh pulls (`docs/data/raw/fresh/f*.json` and
`docs/data/raw/fresh/osmapi-*.xml`).

### Re-verification result: what actually changed

**Nothing that this dataset reports changed.** Every length, member-way count, bike-legal length, surface,
endpoint, distance-from-home and connectivity result is **identical**. The two independent fresh sources agree
with each other on all 72 member ways, node for node and coordinate for coordinate, so the numbers below are now
confirmed on current data rather than inherited from a 2026-07-28 snapshot.

Two real OSM edits happened between the stale snapshot and now. Both are on the **Fryar Avenue** on-road
connector inside the Sumner Link relation, neither on the trail line itself:

| OSM way | edit | effect |
|---|---|---|
| `691257983` | v7, 2026-07-29T10:54:56Z, user `rsitoni_Lyft` — one node (`14058460612`) inserted | 603.065 m → 601.638 m (**−1.427 m**) |
| `691257982` | endpoint node `5242303703` moved about 1.5 m north-west | 31.875 m → 33.316 m (**+1.441 m**) |

Net effect on the Sumner Link relation: **+0.014 m**, far below the 1 m rounding of `length_km`. Three of the
15 trail-to-trail gap distances move by **2.0 mm, 2.7 mm and 3.3 mm** — invisible at the reported 0.1 m. The
simplified geometry, `endpoints`, `surface`, `paved`, `loop`, `rideable_length_km` and every other shipped field
are byte-identical.

The one substantive gain from the re-check is not a change but a **history fact** about the closed Sumner Link
segment: `way/419747831/history` shows it was a designated cycleway for ten years and was closed on
2026-06-01. See [section 5.3](#53-237-km-of-it-is-closed--and-the-closure-is-recent).

---

## 1. Headline answers

1. **The 18.8 km figure for Sumner Link Trail is wrong.** It is a measurement artifact. The relation's real
   mapped length is **9.62 km**, and the continuous ridable line is **8.46 km**, of which only **6.09 km is
   legal for bicycles**. Full evidence in [section 5](#5-critical-verification--what-the-sumner-link-trail-relation-actually-is).
   **Re-verified against fresh OpenStreetMap data on 2026-09-15 — every number in this file is unchanged**
   (see [section 0](#0-data-freshness-and-re-verification)).
2. **Three trails form ONE connected corridor with no road link at all:** Sumner Link Trail + Riverwalk Trail +
   Lake Tapps Parkway Trail. They do not merely come close — they **share OSM nodes**, so the gap is exactly
   **0 m**. Combined one-way length **18.240 km**. (Correction to D9, which claimed 36.2 km.)
3. **Two trails need a road link:** Interurban Trail Edgewood + Interurban Trail Milton, minimum gap
   **626.5 m**. No route across this gap satisfies decision D10.
4. **White River Trail is effectively detached:** minimum gap **1402.4 m** to the corridor. A D10-compliant
   road link of **1433 m** exists, but it is 720 m of a 35 mph road with no bicycle facility.
5. **No trail in the set is a closed loop.** Under decision D3 every trail portion is therefore
   out-and-back. One exception worth knowing: Sumner Link Trail contains two small mapped cycles (1629 m and
   106 m) — see section 4.
6. **Every trail is paved-or-better on OSM tags.** No dirt, gravel, ground, or rough surface appears anywhere
   in the set, so all six pass D11.

## 2. Trail table

Distances from home are straight-line. `len` is the OSM trail length from full-resolution node geometry;
`ride` excludes segments tagged `bicycle=no` / `access=no`.

| # | Trail | OSM relation | len km | ride km | paved | loop | near home km | far home km | member ways | verified |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **Lake Tapps Parkway Trail** | [9605519](https://www.openstreetmap.org/relation/9605519) | 3.016 | 3.016 | yes | no | 1.88 | 4.01 | 3 | yes |
| 2 | **Sumner Link Trail** | [9605518](https://www.openstreetmap.org/relation/9605518) | 8.460 | 6.090 | yes | no | 1.88 | 4.92 | 42 | **no** |
| 3 | **Interurban Trail Edgewood** | [8826064](https://www.openstreetmap.org/relation/8826064) | 1.330 | 1.330 | yes | no | 2.09 | 3.10 | 3 | yes |
| 4 | **White River Trail** | [3508527](https://www.openstreetmap.org/relation/3508527) | 3.800 | 3.800 | yes | no | 3.65 | 7.05 | 5 | yes |
| 5 | **Interurban Trail Milton** | [6554034](https://www.openstreetmap.org/relation/6554034) | 4.639 | 4.639 | yes | no | 3.72 | 5.62 | 9 | **no** |
| 6 | **Riverwalk Trail** | [9605517](https://www.openstreetmap.org/relation/9605517) | 6.764 | 6.764 | yes | no | 4.56 | 5.90 | 10 | yes |

* `verified = true` means: I re-derived the geometry, length and surface from current OSM tags, every member
  way really belongs to the trail, the shipped polyline has no bridged break of 100 m or more, and there is no
  `bicycle=no` / `access=no` segment. See the per-trail notes in `trails.json`.
* Surfaces seen (length of the shipped line per value):

| # | Trail | surface (from OSM `surface=` tags) |
|---|---|---|
| 1 | Lake Tapps Parkway Trail | `asphalt` 2.00 km, `concrete` 1.00 km, `(untagged)` 0.02 km |
| 2 | Sumner Link Trail | `asphalt` 5.52 km, `(untagged)` 2.58 km, `concrete` 0.17 km, `paved` 0.14 km, `metal_grid` 0.05 km |
| 3 | Interurban Trail Edgewood | `asphalt` 1.33 km |
| 4 | White River Trail | `asphalt` 3.80 km |
| 5 | Interurban Trail Milton | `asphalt` 4.32 km, `(gap: no mapped way)` 0.32 km |
| 6 | Riverwalk Trail | `asphalt` 5.01 km, `paved` 1.59 km, `(gap: no mapped way)` 0.09 km, `concrete:plates` 0.04 km, `concrete` 0.03 km |

* `paved: true` means **no OSM surface tag on the trail says otherwise** (i.e. nothing is tagged gravel, dirt,
  ground, grass, sand or similar). It does **not** mean every metre is tagged. Where a trail has untagged
  metres, the row above shows them. Sumner Link Trail is the weak case: 2.58 km of the shipped line carries
  no `surface` tag, so `paved: true` there is an inference, not an observation.

## 3. Pair-by-pair minimum gap (all 15 pairs)

Measured between the **mapped trail geometry** (every vertex of every member way), not relation centres.
A distance of `0.0` means the closest point of one trail lies on the other trail's polyline.

| Trail A ↔ Trail B | min gap m | closest point on A | closest point on B |
|---|---|---|---|
| Sumner Link Trail ↔ Lake Tapps Parkway Trail | 0.0 | 47.24998,-122.23750 | 47.24998,-122.23750 |
| Sumner Link Trail ↔ Riverwalk Trail | 0.0 | 47.19649,-122.25107 | 47.19649,-122.25107 |
| Interurban Trail Edgewood ↔ Interurban Trail Milton | 626.5 | 47.25835,-122.29349 | 47.26106,-122.30077 |
| Lake Tapps Parkway Trail ↔ White River Trail | 1402.4 | 47.25206,-122.22883 | 47.26475,-122.22921 |
| Sumner Link Trail ↔ White River Trail | 1733.4 | 47.25010,-122.23750 | 47.26476,-122.22937 |
| Interurban Trail Edgewood ↔ Lake Tapps Parkway Trail | 2783.9 | 47.25607,-122.27733 | 47.24999,-122.24158 |
| Sumner Link Trail ↔ Interurban Trail Edgewood | 3082.4 | 47.25010,-122.23750 | 47.25607,-122.27733 |
| Interurban Trail Milton ↔ Riverwalk Trail | 3655.6 | 47.23920,-122.33472 | 47.20710,-122.32305 |
| Interurban Trail Edgewood ↔ White River Trail | 3742.9 | 47.25607,-122.27733 | 47.26487,-122.22948 |
| Lake Tapps Parkway Trail ↔ Interurban Trail Milton | 4637.3 | 47.24999,-122.24158 | 47.26106,-122.30077 |
| Sumner Link Trail ↔ Interurban Trail Milton | 4933.8 | 47.25010,-122.23750 | 47.26106,-122.30077 |
| Interurban Trail Milton ↔ White River Trail | 5400.9 | 47.26106,-122.30077 | 47.26492,-122.22950 |
| Lake Tapps Parkway Trail ↔ Riverwalk Trail | 5767.6 | 47.24999,-122.24158 | 47.19914,-122.25866 |
| Interurban Trail Edgewood ↔ Riverwalk Trail | 5935.7 | 47.25583,-122.28695 | 47.20256,-122.29687 |
| Riverwalk Trail ↔ White River Trail | 7582.9 | 47.19905,-122.25819 | 47.26476,-122.22937 |

Notes:
* The relation centre of Sumner Link Trail is 2.39 km from home; the trail itself comes within **1.88 km**.
  Measuring from centres would have been wrong for every trail in the set.
* Every other pair is more than 2.7 km apart, so only the three gaps marked in section 4 need a link.

## 4. Corridor structure and the links that are needed

### Corridor A — continuous, no road link (0 m)

| Link | how it joins | coordinates |
|---|---|---|
| Sumner Link Trail ↔ Riverwalk Trail | **shared OSM node `6486202530`** | `47.1964871, -122.2510719` |
| Sumner Link Trail ↔ Lake Tapps Parkway Trail | **shared OSM node `2670828792`** (Sumner Link way `261467897` / Lake Tapps way `261467941`) | `47.2499820, -122.2374970` |

* Members: Sumner Link Trail 8.460 km + Riverwalk Trail 6.764 km + Lake Tapps Parkway Trail 3.016 km.
* **One-way total 18.240 km.** Out-and-back the whole corridor is 36.5 km; out-and-back one trail at a time is
  the shorter option the app will usually pick.
* Rideable one-way total is **15.870 km** (Sumner Link loses 2.370 km to the closed levee segment).
* **Is the corridor a loop?** No. No trail in the set is a loop, and the corridor's outer ends do not meet:
  Sumner Link Trail north end `47.2500991,-122.2375027`; Lake Tapps Parkway Trail east end
  `47.245316,-122.209513`; Lake Tapps Parkway Trail west end `47.2499905,-122.2415757`; Riverwalk Trail west
  end `47.207103,-122.3230523` (plus the two facing ends of Riverwalk's own 93 m internal break). The two
  furthest apart, Lake Tapps east and Riverwalk west, are 5.77 km apart.
* **However, the corridor does contain two small cycles.** A bridge analysis of the corridor's node graph finds
  a **1629 m loop** inside Sumner Link Trail (`lat 47.2120-47.2170, lon -122.2429 … -122.2375`; ways
  `261467070`, `691257984`, `1433885402`, `691257986-88`, `1433885378-79`, `419747830`, `1433885326` and the
  short connectors between them) and a **106 m loop** at `47.20348-47.20390, -122.24430 … -122.24403`. Both are
  formed by two mapped alignments running 10-70 m apart that rejoin at both ends.
  **UNCERTAIN:** these are most likely the two sides of one path rather than a designed loop. Everything else
  in the corridor is a bridge edge, i.e. strictly out-and-back under D3.

### Corridor B — two trails, one road link

| Pair | min gap | best road route | D10 compliant? |
|---|---|---|---|
| Interurban Trail Edgewood ↔ Interurban Trail Milton | 626.5 m | 914 m | **NO** |

Closest points: Edgewood south end `47.258350, -122.293487` → Milton north-west end `47.261063, -122.300768`.

The 914 m road route, in order, with the OSM tags that decide its safety grade:

| metres | OSM way | `highway` | `name` | `maxspeed` | bike facility | `surface` |
|---|---|---|---|---|---|---|
| 32 + 19 + 67 | 50697841, 702655126, 1183624634 | `secondary` | Military Road East | **45 mph** | none tagged | asphalt |
| 14 + 12 | 1223074956, 1223074957 | `footway` | *(unnamed)* | — | none tagged | untagged |
| 470 | 1183620085 | **`primary`** | Enchanted Parkway South (`ref=SR 161`) | **45 mph** | `cycleway=lane`, `bicycle=designated` | asphalt |
| 37 | 1183620084 | `primary_link` | *(unnamed)* | — | none tagged | asphalt |
| 48 | 1183620086 | `tertiary` | 28th Avenue South | 35 mph | none tagged | untagged |
| 215 | 6420938 | `residential` | South 380th Street | 25 mph | none tagged | asphalt |

**Verdict: no D10-compliant route exists for this gap.** Military Road East is 45 mph with no bike facility
(mathematically grade E under D10) and Enchanted Parkway South is `highway=primary` (D10 "never E" list).
I re-ran the road search with primary/trunk/motorway, all roads >= 40 mph without a facility, all footways and
all `access=private` ways removed: **no path between the two trail ends exists.** Consequence for the app: a
ride that chains Edgewood with Milton must either accept a D10 violation (and say so to the rider) or not be
offered. The only clean fix is a new safe crossing that is not in OSM today.

I also checked that no trail is simply missing from OSM here: searching ways named `~"Interurban"` in
`(47.25,-122.31,47.27,-122.285)` returns exactly two ways — `259300195` "Interurban Trail" and `315246010`
"Milton Interurban Trail" — i.e. the two ends of the gap and nothing between them.

### Corridor A ↔ White River Trail — one road link

| Pair | min gap | best road route | D10 compliant? |
|---|---|---|---|
| Lake Tapps Parkway Trail ↔ White River Trail | 1402.4 m | 1433 m | yes (with one UNCERTAIN road) |
| Sumner Link Trail ↔ White River Trail | 1733.4 m | 2192 m | yes, but it is the same link plus 760 m |

Closest points: Lake Tapps north-east end `47.252063, -122.228831` → White River Trail south-west end
`47.264747, -122.229213`.

| metres | OSM way | `highway` | `name` | `maxspeed` | bike facility | notes |
|---|---|---|---|---|---|---|
| 161 + 457 | 1326182522, 1326182521 | `secondary` | East Valley Highway East (`alt` name Sumner Dieringer Road) | **not tagged** | none tagged | `sidewalk=left`/`no`; `maxheight=below_default` |
| 419 + 60 + 45 + 10 + 59 + 88 + 39 | 687694010, 985052702 | `secondary` | A Street Southeast | 35 mph | none tagged | `lanes=2`, no `surface` tag |
| 93 | 674202154 | `service` | *(unnamed)* | — | `motor_vehicle=destination` | last 93 m to the trail end |

* **UNCERTAIN:** East Valley Highway East carries **no `maxspeed` tag** and no bike facility. Its OSM name is
  also "Sumner Dieringer Road". From general knowledge this type of Pierce County rural highway runs at
  40-45 mph, which would make 618 m of the link grade D/E under D10 — but I could not verify the speed from
  OSM. **Verify before the app routes anyone along it.**
* A slightly shorter variant (1431 m) runs the last 240 m on `highway=footway` ways `1452260029`
  (`footway=sidewalk`), `947658197` and `1452260030-33` instead of A Street Southeast. Those ways carry **no
  `bicycle` tag**. Riding a sidewalk is legal in Washington unless a local rule says otherwise, but the ways
  are not tagged as cycling infrastructure, so this is **UNCERTAIN**.
* There is also a mapped riverside walk/cycle network just north of the gap (ways `845567706`, `947658850`,
  `845567707`, `759075979-759075985`), but it is split between unnamed `footway` and unnamed `cycleway` and is
  not part of any relation. It does not shorten the link.

### Summary of connectivity

| Group | Trails | joined by | one-way km |
|---|---|---|---|
| Corridor A | Sumner Link Trail, Riverwalk Trail, Lake Tapps Parkway Trail | shared OSM nodes (0 m) | 18.240 |
| Corridor B | Interurban Trail Edgewood, Interurban Trail Milton | 626.5 m gap — road link needed, **no D10-compliant route** | 5.969 |
| Detached | White River Trail | 1402.4 m gap — 1433 m road link via East Valley Hwy E / A St SE | 3.800 |

Whole set: **28.009 km** of one-way trail. Corridors A and B are 2.78 km apart at their nearest points
(Edgewood ↔ Lake Tapps Parkway), which is over the "about 2 km" threshold, so they are treated as separate.

### Breaks *inside* a single trail (not trail-to-trail gaps)

| Trail | break | coordinates |
|---|---|---|
| Interurban Trail Milton | 323.5 m — the relation is two unconnected pieces | `47.243333,-122.334533` ↔ `47.240732,-122.336437` |
| Riverwalk Trail | 93.4 m — no way mapped across a road crossing | `47.197639,-122.287803` |

These are recorded in `trails.json` as `geometry_gaps`. The shipped polyline bridges each of them with one
straight line. For Milton the bridged piece is 323 m and is therefore tagged `verified: false`.

## 5. CRITICAL VERIFICATION — what the "Sumner Link Trail" relation actually is

**Object:** OSM relation **9605518**, tags `name=Sumner Link Trail`, `type=route`, `route=bicycle`,
`network=lcn`. Version **15**, last edited **2025-09-29T06:51:12Z** by user **goldfndr** (changeset 172596005).
42 member ways, every one with an empty role, bounding box `47.1964871 … 47.2500991` N,
`-122.2562088 … -122.2292430` E.

### 5.1 The 18.8 km figure is an artifact — reproduced and disproved

| measurement | result |
|---|---|
| Sum of the 42 member ways' own lengths (full-resolution OSM nodes, no double counting) | **9.619 km** |
| Length on the shipped continuous line (`trails.json`) | **8.460 km** |
| Length obtained by concatenating the 42 member geometries **in relation-member order** and summing consecutive point distances | **18.78 km** |

That last number reproduces the **18.8 km** in D4/D9 to 0.02 km. It is wrong because the 42 ways are **not
stored in walking order**: concatenating them inserts straight-line "jumps" between the end of one way and the
start of a non-adjacent way. Those jumps total about 9.2 km of pure fiction. The earlier session's map input
(`docs/research/raw/edgewood-near-trails-geom.json`, key `"Sumner Link Trail"`, 437 points) has exactly this
shape and sums to 18.78 km — so 18.8 km came from that bug, not from the trail.

**Correct lengths: 9.62 km of mapped route; 8.46 km as one continuous ridable line; 6.09 km of that is legal
for bicycles.**

### 5.2 It is one connected route, not several detached trails

A union-find over shared OSM node ids across all 42 ways yields **one connected component**. So the relation is
a single continuous chain, not a bundle of unrelated paths. Its member ways do, however, carry three different
names:

| what | ways | length | tags |
|---|---|---|---|
| the trail proper | 35 ways named `Sumner Link Trail` | **8.710 km** | mostly `highway=cycleway` |
| a road it borrows | 5 ways named `Fryar Avenue` | **0.886 km** | `highway=tertiary`, `maxspeed=25 mph`, `lanes=3`, `surface=asphalt`, `cycleway=lane`, `bicycle=designated` |
| unnamed connectors | 2 ways | **0.024 km** | one `highway=cycleway` `bicycle=designated` (14.1 m), one `highway=service` stub (9.7 m) |

Breakdown by `highway` (all 42 ways):

| `highway` | ways | length |
|---|---|---|
| `cycleway` | 29 | 6.286 km |
| **`unclassified`** | **1** | **2.370 km** |
| `tertiary` (Fryar Avenue) | 5 | 0.886 km |
| `footway` | 6 | 0.068 km |
| `service` | 1 | 0.010 km |

Breakdown by `surface`, again over all 42 member ways: `asphalt` 6.342 km (21 ways),
**`(untagged)` 2.855 km (13 ways)**, `concrete` 0.238 km (5 ways), `paved` 0.136 km (2 ways),
`metal_grid` 0.049 km (1 way). (Section 2 lists surfaces along the shipped 8.46 km line, which is why the
figures differ.)

### 5.3 2.37 km of it is closed — and the closure is recent

OSM way **`419747831`** is the single longest member. Fresh pull, 2026-09-15:

```
{"access": "no", "bicycle": "no", "foot": "no", "highway": "unclassified",
 "name": "Sumner Link Trail", "segregated": "no"}
```

Version 7, last edited **2026-06-01T17:33:17Z** by `fencebuilder2000`. 2,369.7 m, 67 nodes, from
`47.2184449, -122.2311095` to `47.2355283, -122.2349371` — a 2.37 km run through the northern-middle of the
route (the route spans lat 47.1965-47.2501), tagged **no access for anyone**, including bicycles.

`GET https://api.openstreetmap.org/api/0.6/way/419747831/history` shows exactly what happened, and it changes
the interpretation:

| version | date | user | tags |
|---|---|---|---|
| v1 | 2016-05-20T18:09:02Z | paulmach | `highway=cycleway`, `bicycle=designated`, `foot=designated` |
| v2–v5 | 2016-11-03 … 2024-07-01 | CloCkWeRX, jamesholio, Takatsume, giantcicada | same |
| v6 | 2025-09-28T20:48:27Z | goldfndr | same (67 nodes) |
| **v7** | **2026-06-01T17:33:17Z** | **fencebuilder2000** | **`highway=unclassified`, `access=no`, `bicycle=no`, `foot=no`** |

Only the tags changed in v7; the 67 nodes are identical to v6. So this is **not** a road that was never
rideable, and **not** a long-standing mis-tag. It was a **designated cycleway for ten years** and was
**deliberately closed on 2026-06-01** by a mapper. The likely reading is a physical closure (levee work, a
washout, or a construction project) recorded in OSM. A temporary closure is also possible.
The relation itself was last edited **2025-09-29**, i.e. *before* the closure, so the route relation still sends
riders over a way that is now closed — an inconsistency inside OSM, not an artefact of this dataset.

A fresh query within 80 m of its midpoint `47.2270, -122.2321` returns `way 25802448 name="White River"` and
`relation 19238395 name="White River" type=waterway`. So this segment runs along the **White River**, most
likely on the levee / top of bank.

**This is the single most important fact for the app: the Sumner Link Trail relation cannot be ridden
end-to-end today.** Every route that uses it must either stop before `47.2185` or leave the trail. Because the
closure has a date and a deliberate edit behind it, the app should **re-check this way on a schedule** rather
than treat 2.37 km as permanently lost.

### 5.4 It is NOT the Puyallup River levee path

* The closed segment follows the **White** River, not the Puyallup.
* A search for any object whose name contains `levee` or `dike` in `(47.16,-122.30,47.30,-122.18)` returned only
  `North Levee Road` (`highway=tertiary`, `surface=asphalt`) and the `Lake Tapps Dike Number 1/2A/2B` dams.
  **There is no "Puyallup River levee path" in OSM.**
* A search for `highway`-tagged objects named `~"Puyallup River"` in the same area returned **nothing**.
* The Puyallup River path is a *different* relation: **9605517 Riverwalk Trail** (10 ways, 6.670 km, all named
  `Riverwalk Trail`), which follows the river from `47.2071,-122.3231` to `47.1965,-122.2511`.

### 5.5 The relation is also incomplete

Three more ways named `Sumner Link Trail` exist in OSM but are **not members of the relation**:
`759079451` (`47.2065,-122.2433`, `footway`), `759079455` (`47.2101,-122.2423`, `footway`, `surface=concrete`),
`1433970031` (`47.2096,-122.2423`, `footway`). They sit on the Fryar Avenue corridor between the relation's
chain ends, i.e. exactly where the relation is patched with road. So the relation both **over-includes** (a
25 mph road, a closed levee) and **under-includes** (three footway pieces of the trail itself).

### 5.6 Proposed split

Suggested replacement for D4's single "Sumner Link (18.8 km)" entry:

Lengths in this table are measured along the single continuous line (8.46 km), so they exclude the roughly
1.16 km of parallel side alignments that the relation also carries; that is why they sum to 7.71 km and not to
the 8.71 km of trail-named ways.

| proposed object | ways | length | rideable | what it is |
|---|---|---|---|---|
| `Sumner Link Trail (south)` | the 35 `Sumner Link Trail` ways below lat 47.2035 | ≈ 1.72 km | yes | cycleway/footway, Puyallup–Sumner, joins Riverwalk Trail at the shared node |
| `Sumner Link Trail (mid)` | the `Sumner Link Trail` ways 47.2105–47.2185 | ≈ 1.38 km | yes | cycleway along the Fryar Avenue corridor |
| `Sumner Link Trail (north)` | way `261467897`, `98695977`, `98696487`, `261467895` etc. above lat 47.2355 | ≈ 2.24 km | yes | cycleway, joins Lake Tapps Parkway Trail at the shared node |
| `Sumner Link Trail (White River levee)` | way `419747831` | 2.370 km | **NO** | `access=no`, `bicycle=no` — closed |
| *not a trail:* Fryar Avenue connector | the 5 `Fryar Avenue` ways | 0.886 km | yes | 25 mph tertiary road with a bike lane; a road link, not a trail |

If the split is adopted, the numbers to use are: **8.46 km of one continuous line** (or 8.71 km for the
trail-named ways only), **6.09 km rideable end-to-end**, **2.37 km closed**, **0.89 km on-road**.
I have not applied the split in `trails.json` — the file ships the relation as one object with
`verified: false`, `rideable_length_km: 6.090` and a machine-readable `bike_access_conflicts` entry, so the
split can be decided deliberately.

## 6. Surface honesty (decision D11)

* **No unpaved surface appears anywhere in the set.** Searching the member ways of all six relations for
  `surface` values other than asphalt / concrete / concrete:plates / paved / metal_grid returned nothing.
  D11's reject list (dirt, ground, grass, sand, rough) does not occur, so all six trails pass D11.
* Surface tags do **not** cover everything:
  * Sumner Link Trail — **2.855 km of 9.619 km untagged** (30%). The untagged part is dominated by the closed
    levee way. `paved` there is an inference from the way types, not from `surface` tags.
  * Lake Tapps Parkway Trail — 23.6 m untagged (way `759075987`).
  * Interurban Trail Milton and Riverwalk Trail — the only "untagged" metres on the shipped line are the
    straight bridges across their own internal gaps, which are not real trail.
* Two special surfaces worth knowing about:
  * `metal_grid` — 49 m on Sumner Link Trail (way `261467895`, lat 47.2445–47.2447). A metal-grid bridge deck.
    Rideable, but slippery when wet; a hybrid-bike rider should walk it.
  * `concrete:plates` — 43.3 m on Riverwalk Trail (way `1274743331`). A good surface, no concern.
* **UNCERTAIN:** all surfaces are OSM tags. I did not verify any of them on the ground. OSM surface tagging in
  this area is patchy, so treat `paved: true` as "nothing suggests otherwise", not as a survey.

## 7. `trails.json` field reference

The file is a JSON array of six objects, ordered by distance from home.

| field | meaning |
|---|---|
| `name` | relation name, as used in D4 |
| `osm_relation_id` | OSM relation id |
| `osm_url` | `https://www.openstreetmap.org/relation/<id>` |
| `length_km` | full-resolution OSM length of the shipped line, from node geometry |
| `rideable_length_km` | `length_km` minus segments tagged `bicycle=no` or `access=no` |
| `surface` | every OSM `surface` value seen on the shipped line, plus `"(untagged)"` and `"(gap: no mapped way)"` |
| `surface_m` | metres of the shipped line per surface value (makes `surface` checkable, not decorative) |
| `paved` | `true` when no unpaved surface value occurs (see section 6) |
| `loop` | `true` only when the mapped trail is a closed loop. **All six are `false`.** |
| `geometry` | simplified polyline, array of `[lat, lon]` pairs, `[lat, lon]` order, 5 decimals |
| `endpoints` | two extreme `[lat, lon]` pairs, in line order |
| `nearest_distance_from_home_km` | great-circle, home to the nearest point of the line |
| `farthest_distance_from_home_km` | great-circle, home to the farthest point of the line |
| `member_way_count` | distinct member ways used |
| `geometry_gaps` | unmapped breaks inside the trail that the polyline bridges, with gap metres |
| `bike_access_conflicts` | member ways tagged `access=no` / `bicycle=no` / `bicycle=dismount` |
| `verified` | see the rule under section 2 |
| `notes` | per-trail caveats |

### Geometry simplification

Ramer–Douglas–Peucker, tolerance **10 m**, computed in local metres, coordinates rounded to 5 decimals
(~1.1 m). Result: **174 points** for all six trails, **4.2 KB** of coordinate data — small enough to bundle in
an APK with room to spare. The simplification shortens each line by about 1% (e.g. Sumner Link Trail
8.460 km → 8.377 km), which is the expected chord-vs-arc loss at this tolerance. `length_km` reports the
unsimplified value; the map and the app should use the simplified polyline.

## 8. Everything I could not verify (UNCERTAIN)

1. `maxspeed` on **East Valley Highway East** — not tagged. The 618 m of the Corridor-A→White-River link on
   that road may be grade D/E under D10. **The biggest open risk in this file.**
2. Legal rideability of the `highway=footway` ways used by the shorter White River link variant
   (`1452260029-33`, `947658197`) — no `bicycle` tag either way.
3. Whether the 2026-06-01 closure of way `419747831` is permanent or temporary. What is now *known*: it is
   deliberate (a tag-only edit by `fencebuilder2000`, geometry untouched), it ended ten years of
   `highway=cycleway` + `bicycle=designated`, and the relation was never updated to match. Re-check
   periodically.
4. On-the-ground surface for every trail. All `surface` values are OSM tags only.
5. Whether D3's "trail is a loop" test should treat **Corridor A as a whole** as a loop. It is not a loop as
   mapped, but the app could lap the corridor. That is a product decision, not a data one.
6. The three orphan `Sumner Link Trail` footways (section 5.5) — whether they are open, and whether they belong
   in the relation.

## Appendix A — Queries run

**Pass 1 (2026-09-15, 20:53-21:09Z)** — the pass whose staleness was later found. 17 Overpass queries, three
mirrors rotating on failure, descriptive `User-Agent`
`bike-trail-planner/0.1 (personal bike route dataset; contact: local user; polite low-rate)`, 4 attempts per
query with exponential back-off. 504s were common and were retried rather than abandoned; 6 attempts failed
with HTTP 504. **Stamps were not checked in this pass — that was the mistake.** Five of the files it produced
turned out to be stale and have since been deleted; [section 0](#0-data-freshness-and-re-verification) lists
each file's stamp.

| # | timestamp (UTC) | mirror | bytes | purpose | stamp was |
|---|---|---|---|---|---|
| 1 | 2026-09-15T20:55:04Z | `overpass.kumi.systems` | 570 | smoke-rel-9605518 | **STALE** |
| 2 | 2026-09-15T20:55:16Z | `overpass-api.de` | 7,192 | target relations, out body | fresh |
| 3 | 2026-09-15T20:55:19Z | `overpass-api.de` | 81,304 | member ways of target relations, out tags geom | fresh |
| 4 | 2026-09-15T20:55:44Z | `overpass.kumi.systems` | 100,376 | member ways with node ids + geometry (out body geom) | **STALE** |
| 5 | 2026-09-15T20:56:09Z | `overpass.kumi.systems` | 3,742 | relation 9605518 metadata (version/user/timestamp) | **STALE** |
| 6 | 2026-09-15T20:57:37Z | `overpass.kumi.systems` | 10,772 | all OSM objects named ~"Sumner Link" in bbox | **STALE** |
| 7 | 2026-09-15T20:57:48Z | `overpass-api.de` | 1,491 | features within 80 m of midpoint of way 419747831 | fresh |
| 8 | 2026-09-15T20:57:59Z | `overpass.private.coffee` | 2,965 | objects with "levee|dike" in name, bbox Puyallup/Sumner | **STALE** |
| 9 | 2026-09-15T20:58:46Z | `overpass.kumi.systems` | 287 | highways named "Puyallup River..." bbox | **STALE** |
| 10 | 2026-09-15T21:00:53Z | `overpass-api.de` | 16,430 | highways within 30 m of 9 sample points across gap edgewood_milton | fresh |
| 11 | 2026-09-15T21:03:19Z | `overpass.private.coffee` | 19,806 | highways within 30 m of 9 sample points across gap tapps_whiteriver | **STALE** |
| 12 | 2026-09-15T21:04:15Z | `overpass-api.de` | 22,209 | highways within 30 m of 9 sample points across gap sumnerlink_whiteriver | fresh |
| 13 | 2026-09-15T21:04:25Z | `overpass-api.de` | 292,371 | all highway ways in gap bbox edgewood_milton | fresh |
| 14 | 2026-09-15T21:04:26Z | `overpass-api.de` | 264,970 | all highway ways in gap bbox tapps_whiteriver | fresh |
| 15 | 2026-09-15T21:08:23Z | `overpass-api.de` | 385,451 | all highway ways in gap bbox sumnerlink_whiteriver | fresh |
| 16 | 2026-09-15T21:08:54Z | `overpass-api.de` | 930 | ways named ~Interurban in Edgewood-Milton bbox | fresh |
| 17 | 2026-09-15T21:09:25Z | `overpass-api.de` | 10,725 | White River named highways + cycleway/path/footway in the Tapps-WhiteRiver gap bbox | fresh |

**Pass 2 (2026-09-15, 21:32-21:44Z)** — the re-verification, and the data behind this document. Every response
was required to carry `osm3s.timestamp_osm_base` within 3 days of `2026-09-15T21:24Z`. Stale responses were
rejected and the query retried against another mirror; 3 stale responses were thrown away (kumi at
`2026-06-01T08:52:28Z`, private.coffee at `2026-07-15T15:22:01Z` twice). All 12 accepted responses came from
`maps.mail.ru`. These files are kept in `docs/data/raw/fresh/`.

| raw file | what it fetched | `timestamp_osm_base` | bytes | query |
|---|---|---|---|---|
| `f1-relations.json` | rel out body (6 relations) | `2026-09-15T21:32:03Z` | 7,191 | `[out:json][timeout:120];\nrel(id:9605518,8826064,9605519,6554034,9605517,3508527);\nout body;` |
| `f2-member-ways-nodes.json` | member ways, node ids + geometry | `2026-09-15T21:36:05Z` | 100,440 | `[out:json][timeout:180];\nrel(id:9605518,8826064,9605519,6554034,9605517,3508527);\nway(r);\nout body geom;` |
| `f3-rel9605518-meta.json` | relation 9605518 metadata | `2026-09-15T21:37:03Z` | 3,741 | `[out:json][timeout:60];\nrel(9605518);\nout meta;` |
| `f4-all-sumnerlink-named.json` | ways/relations named ~"Sumner Link" (bbox 47.19,-122.26,47.255,-122.225) | `2026-09-15T21:40:05Z` | 10,771 | `way["name"~"Sumner Link",i](47.19,-122.26,47.255,-122.225);  rel[ same ]();  out tags center;` |
| `f5-near-accessno-way.json` | features within 80 m of way 419747831 midpoint | `2026-09-15T21:42:05Z` | 1,490 | `way(around:80,47.2270,-122.2321)["name"];  way(around:80,...)["highway"];  rel(around:80,...)["name"];  out tags center;` |
| `f6-levee-dike-names.json` | objects named ~"levee|dike" (bbox 47.16,-122.30,47.30,-122.18) | `2026-09-15T21:43:05Z` | 2,964 | `nwr["name"~"levee|Levee|dike|Dike",i](47.16,-122.30,47.30,-122.18);\nout tags center;` |
| `f7-puyallup-river-highways.json` | highways named ~"Puyallup River" | `2026-09-15T21:43:05Z` | 286 | `nwr["name"~"Puyallup River",i]["highway"](47.16,-122.32,47.30,-122.18);\nout tags center;` |
| `f8-interurban-named-gap.json` | ways named ~"Interurban" in the Edgewood-Milton bbox | `2026-09-15T21:43:05Z` | 929 | `way["name"~"Interurban",i](47.2500,-122.3100,47.2700,-122.2850);\nout tags center;` |
| `f9-white-river-and-paths.json` | White River highways + cycleway/path/footway near the gap | `2026-09-15T21:44:04Z` | 10,724 | `way["name"~"White River",i]["highway"](47.23,-122.26,47.30,-122.18);  way["highway"~"cycleway|path|footway"](47.2500,-122.2350,47.2700,-122.2230);  out tags center;` |
| `f10-roads-edgewood_milton.json` | all highways in the Edgewood-Milton gap bbox | `2026-09-15T21:43:05Z` | 292,370 | `way["highway"](47.25383,-122.30739,47.26558,-122.28687);\nout body geom;` |
| `f10-roads-tapps_whiteriver.json` | all highways in the Tapps-WhiteRiver gap bbox | `2026-09-15T21:43:05Z` | 264,969 | `way["highway"](47.24754,-122.23583,47.26927,-122.22221);\nout body geom;` |
| `f10-roads-sumnerlink_whiteriver.json` | all highways in the SumnerLink-WhiteRiver gap bbox | `2026-09-15T21:44:04Z` | 385,450 | `way["highway"](47.24558,-122.24412,47.26928,-122.22275);\nout body geom;` |

**Pass 2, second independent source — live OSM API, no snapshot involved.** Kept in `docs/data/raw/fresh/`.

| raw file | what it fetched | when | request |
|---|---|---|---|
| `fresh/osmapi-relation-<id>-full.xml` | relation + member ways + nodes, per-element version/user/edit-date (6 files) | live at 2026-09-15T21:26Z | `GET https://api.openstreetmap.org/api/0.6/relation/<id>/full` |
| `fresh/osmapi-way-419747831-history.xml` | full edit history of the closed Sumner Link way | live at 2026-09-15T21:52Z | `GET https://api.openstreetmap.org/api/0.6/way/419747831/history` |

### Query text per first-pass raw file

| raw file | what it fetched | query |
|---|---|---|
| `—` | (smoke test, not saved) | `[out:json][timeout:30];rel(9605518);out tags bb;` |
| `q1-relations.json` | target relations, out body | `[out:json][timeout:120];\nrel(id:9605518,8826064,9605519,6554034,9605517,3508527);\nout body;` |
| `q2-member-ways.json` | member ways, out tags geom | `[out:json][timeout:180];\nrel(id:9605518,8826064,9605519,6554034,9605517,3508527);\nway(r);\nout tags geom;` |
| `q3-member-ways-nodes.json` | member ways with node ids, out body geom | `[out:json][timeout:180];\nrel(id:9605518,8826064,9605519,6554034,9605517,3508527);\nway(r);\nout body geom;` |
| `q4-rel9605518-meta.json` | relation 9605518 metadata | `[out:json][timeout:60];\nrel(9605518);\nout meta;` |
| `q5-all-sumnerlink-named.json` | all objects named ~"Sumner Link" | `[out:json][timeout:120];\n(\n  way["name"~"Sumner Link",i](47.15,-122.31,47.31,-122.17);\n  rel["name"~"Sumner Link",i](47.15,-122.31,47.31,-122.17);\n);\nout tags center;` |
| `q6-near-accessno-way.json` | features within 80 m of way 419747831 midpoint | `[out:json][timeout:120];\n(\n  way(around:80,47.2270,-122.2321)["name"];\n  way(around:80,47.2270,-122.2321)["highway"~"^(primary|secondary|tertiary|trunk|motorway|unclassified|residential|track|path|cycleway|footway)$"];\n  rel(around:80,47.2270,-122.2321)["name"];\n);\nout tags center;` |
| `q7-levee-dike-names.json` | objects named ~"levee|dike" in Puyallup/Sumner bbox | `[out:json][timeout:120];\nnwr["name"~"levee|Levee|dike|Dike",i](47.16,-122.30,47.30,-122.18);\nout tags center;` |
| `q8-puyallup-river-highways.json` | highways named ~"Puyallup River" | `[out:json][timeout:120];\nnwr["name"~"Puyallup River",i]["highway"](47.16,-122.32,47.30,-122.18);\nout tags center;` |
| `q9-gap-*.json` | highways within 30 m of 9 sample points across each gap (3 files) | `way(around:30,<lat>,<lon>)["highway"];  x9, out tags geom;` |
| ~~`q10-roads-*.json`~~ | all highway ways in each gap bbox (3 files, **deleted after distillation**) | `way["highway"](<south>,<west>,<north>,<east>); out body geom;` |
| `q11-interurban-named-gap.json` | ways named ~"Interurban" in the Edgewood-Milton bbox | `[out:json][timeout:120];\nway["name"~"Interurban",i](47.2500,-122.3100,47.2700,-122.2850);\nout tags center;` |
| `q12-white-river-and-paths.json` | White River named highways + cycleway/path/footway in the Tapps-WhiteRiver bbox | `[out:json][timeout:120];\n( way["name"~"White River",i]["highway"](47.23,-122.26,47.30,-122.18);\n  way["highway"~"cycleway|path|footway"](47.2500,-122.2350,47.2700,-122.2230); );\nout tags center;` |

Full timings and mirror choices are in the run log; the table above lists the mirror that finally answered.

## Appendix B — Files and sizes

| file | size | contents |
|---|---|---|
| `docs/data/trails.json` | ~13 KB | deliverable 1 |
| `docs/data/trail-corridor.md` | this file | deliverable 2 |
| `docs/data/trail-network.svg` | ~11 KB | deliverable 3 |
| `docs/data/gap-link-routes.json` | ~28 KB | distilled road routes across the three gaps, with per-way tags, the D10 verdict and the freshness block |
| `docs/data/raw/fresh/f1…f9*.json` | ~84 KiB | **fresh** Overpass responses (pass 2) — kept |
| `docs/data/raw/fresh/osmapi-*.xml` | ~261 KiB | **fresh** live-OSM-API pulls (7 files) — kept, and the per-element edit-date evidence |
| `docs/data/raw/q1…q12*.json` | **deleted** | pass-1 responses; five were stale and the rest were superseded by pass 2 |

`docs/data/` as a whole is well under 1 MiB; no file in it exceeds 2 MB.

### Deletions

* The three large `f10-roads-*.json`/`q10-roads-*.json` road-network dumps (~950 KB each time) were **deleted
  after the routing results were distilled into `gap-link-routes.json`**, because the drive must stay small and
  the exact query is recorded in Appendix A. One query per gap rebuilds them.
* **All pass-1 raw files were deleted.** Five of them (`q3` 2026-07-28, `q4` 2026-06-01, `q5` 2026-07-28,
  `q7` 2026-06-01, `q8` 2026-05-06) carried stale snapshots and keeping them next to fresh files would invite
  exactly the mistake this re-check was about. The rest were byte-equivalent to their pass-2 replacements.
* Nothing else in `docs/data/raw/` was touched. Files there that I did **not** create —
  `analyze_islands.py`, `fetch_islands.py`, `q11-manifest.json`, `q11-known-*.json`, `q11-trail-<relid>.json`,
  `q12-q13-manifest.json`, `q12-targets.json`, `q12-wrt-2hop.json`, `q13-path-spur.json` — were written by a
  different agent working in parallel and were **left alone**. Note that some of those carry their own stale
  stamps (`q11-trail-8826064` and `q11-trail-9605519` at `2026-06-01T08:52:28Z`, `q12-wrt-2hop` at the same,
  `q11-known-*` and `q13-path-spur` at `2026-07-28T02:16:18Z`), which is that agent's problem to resolve.

### Reproducing this dataset

1. Pull `rel(id:...)` and `way(r)` with `out body geom` from a mirror whose `osm3s.timestamp_osm_base` is
   fresh — verify the stamp, do not trust HTTP 200.
2. Cross-check against `https://api.openstreetmap.org/api/0.6/relation/<id>/full`.
3. Re-run the length, topology, surface, access, gap and routing steps described in sections 3, 4 and 5.
