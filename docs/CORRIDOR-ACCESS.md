# Corridor access — Sumner Link / Riverwalk / Lake Tapps (analysis of the 103-point access sweep)

Status: analysis of `docs/corridor-raw/ap-sweep.json` + `ap-sweep.log` (complete, 103/103), plus new
Valhalla and Overpass evidence captured on 2026-09-16 into `docs/corridor-raw/`.
Every number below comes from a file in this repo or from a raw request/response saved in
`docs/corridor-raw/requests/` and `docs/corridor-raw/responses/`. Nothing here is estimated.

## 1. Summary

1. **41 of 103 corridor access points pass the app's D10 rule** (Lake Tapps Parkway 0/16,
   Sumner Link 31/59, Riverwalk 10/28). All 41 pass with only **158-182 m of grade D**, versus the
   805 m / 10 % cap.
2. **The three Lake Tapps Parkway Trail access points and all other failures are road-access
   failures, not trail failures.** 26 of the 103 swept routes touch **Jovita Boulevard East**; **every
   one of those 26 fails**. No passing route touches Jovita.
3. **The best passing route genuinely avoids Jovita Boulevard East - it does not merely stay under the
   cap.** Its nearest approach to the Jovita centreline is **1137 m**, and its only grade-D road is
   **158 m of Bridge Street in Sumner**. Roads used: 122nd Avenue East, Edgewood Drive East,
   48th Street East, 18th Street East, Sumner Heights Drive East, Valley Avenue East, Bridge Street,
   plus 22 m of West Valley Highway East and 60 m of an unnamed service road (both UNGRADED).
4. **The sweep's grading rule is a faithful mirror of the app's real rule** (`docs/SPEC.md` section 6/6b
   and `SafetyGrader.kt`) - three routes were re-run and reproduced the sweep's numbers exactly - with
   two rule-level differences that do **not** change any of the 103 verdicts (see section 4).
5. **Adding `exclude_polygons` around the Jovita corridor does not rescue the failing access points.**
   Valhalla then rides 142nd Avenue East / Puyallup Street / Tacoma Avenue instead: 10.191 km with
   **3254 m of grade D** (32.2 %, 2.02 mi) - a worse failure than before. `exclude_closures` is a
   no-op in this Valhalla build (3.8.3): it returns 200 and leaves the geometry unchanged.
6. **There is a real, measured under-200 m hole in the OSM network** on the direct line from home to the
   Sumner Link north stub: **128 m of 16th Street East is missing across SR-167** (ways 12189691 and
   682030385 share a name, a surface and a speed limit, and are 128 m apart), and the Edgewood side is
   cut off from West Valley Highway East by a **225 m** break in the service-way network, with a third
   break of **54 m** in 126th Avenue Court East. Because of those breaks, a point **373 m from home**
   costs a **5.540 km** ride. Closing them is **not** sufficient on its own: the link road is West
   Valley Highway East, which the app grades **E** (40 mph) - and D10 allows no E.
7. **What the app must act on:** plan the corridor from the Sumner Link **Bridge Street** access cluster
   (node ids in section 8), never from the north-stub / Lake Tapps / Puyallup clusters, and make the
   trail-radius rule (SPEC section 7.1) refuse the north stub at home rather than silently routing
   7.6 km of Jovita Boulevard East.

## 2. Provenance

| File | What it holds |
|---|---|
| `docs/corridor-raw/ap-sweep.log` | 103 lines: trail, OSM node, route km, D m, E m, pass/fail |
| `docs/corridor-raw/ap-sweep.json` | per-AP record: route km, grade breakdown, D10 verdict, first 12 named stretches, encoded shape |
| `scripts/corridor_sweep.py` | the sweep driver (routes home to every corridor access point, grades each) |
| `scripts/corridor_probe.py` | the Python mirror of the app's grader (`Grader`, `breakdown`, `d10`, `route_app`) |
| `docs/corridor-raw/requests/`, `docs/corridor-raw/responses/` | 268 raw request/response files each: the sweep's 216 pairs, plus the 52 pairs added by this analysis (listed below) |
| `recheck_sumner6486202557@route.json`, `...@trace.json` | re-run of the best passing route (section 5) |
| `recheck_sumner2670824385@...`, `recheck_riverwalk6277555604@...` | re-runs of the two failing clusters (section 7) |
| `roundtrip_sumner6486202557_leg0/1_trace.json` | the same route as a real round trip (home -> AP -> home) |
| `exclude_jovita_polygons_route_2670824385.json`, `..._trace_...json` | `exclude_polygons` experiment (section 6) |
| `exclude_closures_jovita_route_2670824385.json`, `exclude_closures_control_122nd_avenue.json` | `exclude_closures` experiment (section 6) |
| `gap_probe_*.json`, `gap_hop_*.json` | connectivity probes that measure the missing links (section 7) |
| `overpass_jovita.json` | Jovita Boulevard East geometry (Overpass, `timestamp_osm_base` 2026-09-16T16:26:16Z) |
| `overpass_west_edgewood_strip_nodes.json`, `overpass_puyallup_milwaukee_box_nodes.json`, `overpass_highways_edgewood_sumner_box.json`, `overpass_white_river_sr167.json` | OSM graph extracts used for the component/gap analysis |

## 3. Pass table

### 3a. Per trail

| Trail | swept access points | passing | best (lowest D, then shortest) | best D m | best route km |
|---|---|---|---|---|---|
| Lake Tapps Parkway Trail | 16 | **0** | - | - | - |
| Sumner Link Trail | 59 | **31** | node 110713098 | 158 | 5.867 |
| Riverwalk Trail | 28 | **10** | node 6486202530 | 175 | 6.880 |
| **total** | **103** | **41** | | | |

`assets/access_points.json` holds 143 access points across 6 trails; 103 of them belong to the three
corridor trails and were swept. The other 40 (Interurban Milton 22, White River 14, Interurban
Edgewood 4) are not part of this sweep.

### 3b. Every passing Sumner Link Trail access point

| OSM node | lat, lon | straight m | route km | D m | D % of graded | D mi | E m | ungraded m | highway |
|---|---|---|---|---|---|---|---|---|---|
| 110713098 | 47.203400, -122.244529 | 4318 | 5.867 | 158 | 2.7% | 0.10 | 0 | 82 | secondary |
| 7090006592 | 47.203328, -122.244549 | 4325 | 5.876 | 158 | 2.7% | 0.10 | 0 | 82 | — |
| 6486202558 | 47.203498, -122.244511 | 4308 | 5.878 | 158 | 2.7% | 0.10 | 0 | 82 | — |
| 6486202557 | 47.203535, -122.244370 | 4307 | 5.890 | 158 | 2.7% | 0.10 | 0 | 82 | — |
| 13179055162 | 47.202800, -122.245915 | 4351 | 6.008 | 158 | 2.7% | 0.10 | 0 | 82 | — |
| 6486202551 | 47.202286, -122.247806 | 4369 | 6.162 | 158 | 2.6% | 0.10 | 0 | 82 | residential |
| 3730012154 | 47.202292, -122.249517 | 4338 | 6.292 | 158 | 2.5% | 0.10 | 0 | 82 | residential |
| 2670806304 | 47.200739, -122.253084 | 4458 | 6.618 | 158 | 2.4% | 0.10 | 0 | 82 | — |
| 2670806307 | 47.200769, -122.252956 | 4456 | 6.629 | 158 | 2.4% | 0.10 | 0 | 82 | motorway |
| 110790321 | 47.200730, -122.252799 | 4462 | 6.641 | 158 | 2.4% | 0.10 | 0 | 82 | residential |
| 2670806286 | 47.200143, -122.254492 | 4509 | 6.744 | 158 | 2.4% | 0.10 | 0 | 82 | residential |
| 8251417567 | 47.203325, -122.244336 | 4330 | 5.884 | 174 | 3.0% | 0.11 | 0 | 82 | residential |
| 2670806303 | 47.197493, -122.250016 | 4853 | 6.743 | 175 | 2.9% | 0.11 | 0 | 683 | secondary |
| 6486202534 | 47.197258, -122.250242 | 4876 | 6.774 | 175 | 2.9% | 0.11 | 0 | 683 | — |
| 2670806296 | 47.197436, -122.251058 | 4845 | 6.822 | 175 | 2.9% | 0.11 | 0 | 683 | — |
| 6486202530 | 47.196487, -122.251072 | 4949 | 6.880 | 175 | 2.8% | 0.11 | 0 | 683 | cycleway |
| 110422923 | 47.203308, -122.244243 | 4334 | 5.891 | 182 | 3.1% | 0.11 | 0 | 82 | — |
| 7090006597 | 47.203452, -122.244133 | 4322 | 5.908 | 182 | 3.1% | 0.11 | 0 | 82 | — |
| 7089994023 | 47.203456, -122.244115 | 4322 | 5.909 | 182 | 3.1% | 0.11 | 0 | 82 | — |
| 7098613132 | 47.203904, -122.244034 | 4276 | 5.960 | 182 | 3.1% | 0.11 | 0 | 82 | — |
| 5242303776 | 47.204849, -122.243515 | 4190 | 6.073 | 182 | 3.0% | 0.11 | 0 | 82 | — |
| 5242303703 | 47.205129, -122.243357 | 4164 | 6.106 | 182 | 3.0% | 0.11 | 0 | 82 | — |
| 110422926 | 47.206017, -122.242880 | 4084 | 6.212 | 182 | 3.0% | 0.11 | 0 | 82 | residential |
| 110422931 | 47.207430, -122.242147 | 3958 | 6.379 | 182 | 2.9% | 0.11 | 0 | 82 | tertiary |
| 5479081486 | 47.210397, -122.242134 | 3656 | 6.709 | 182 | 2.7% | 0.11 | 0 | 82 | tertiary |
| 6486202563 | 47.210393, -122.242263 | 3652 | 6.719 | 182 | 2.7% | 0.11 | 0 | 82 | footway |
| 13179759696 | 47.210450, -122.242263 | 3646 | 6.725 | 182 | 2.7% | 0.11 | 0 | 82 | — |
| 13179759695 | 47.210894, -122.242264 | 3601 | 6.774 | 182 | 2.7% | 0.11 | 0 | 82 | — |
| 13179759683 | 47.211096, -122.242263 | 3581 | 6.797 | 182 | 2.7% | 0.11 | 0 | 82 | — |
| 6486202556 | 47.211569, -122.242256 | 3533 | 6.850 | 182 | 2.7% | 0.11 | 0 | 82 | — |
| 6486202553 | 47.211902, -122.242217 | 3501 | 6.887 | 182 | 2.7% | 0.11 | 0 | 82 | — |

### 3c. Every passing Riverwalk Trail access point

| OSM node | lat, lon | straight m | route km | D m | D % of graded | D mi | E m | ungraded m | highway |
|---|---|---|---|---|---|---|---|---|---|
| 6486202530 | 47.196487, -122.251072 | 4949 | 6.880 | 175 | 2.8% | 0.11 | 0 | 683 | cycleway |
| 6486202533 | 47.196279, -122.251297 | 4969 | 6.909 | 175 | 2.8% | 0.11 | 0 | 683 | — |
| 2101094348 | 47.196466, -122.251283 | 4949 | 7.000 | 175 | 2.8% | 0.11 | 0 | 683 | — |
| 3621050859 | 47.196515, -122.251303 | 4943 | 7.006 | 175 | 2.8% | 0.11 | 0 | 683 | secondary |
| 2101094355 | 47.198210, -122.255749 | 4712 | 7.392 | 175 | 2.6% | 0.11 | 0 | 683 | path |
| 12953091081 | 47.194163, -122.276433 | 5252 | 9.097 | 175 | 2.1% | 0.11 | 0 | 683 | — |
| 12953104608 | 47.193919, -122.276551 | 5281 | 9.126 | 175 | 2.1% | 0.11 | 0 | 683 | — |
| 2865478604 | 47.192952, -122.282044 | 5485 | 9.576 | 175 | 2.0% | 0.11 | 0 | 683 | motorway |
| 2101094324 | 47.192859, -122.282918 | 5514 | 9.651 | 175 | 2.0% | 0.11 | 0 | 683 | residential |
| 6273297985 | 47.192712, -122.282911 | 5529 | 9.667 | 175 | 1.9% | 0.11 | 0 | 683 | — |

Grading levels of the passing set: Sumner Link passes at D = 158 m (11 APs), 174 m (1), 175 m (4),
182 m (15); Riverwalk passes at D = 175 m (10). No passing route has any grade E and none has a
rejected surface.

**Two more data-hygiene notes.** (a) Two access points carry an implausible `highway` value in
`assets/access_points.json` - nodes 2670806307 and 2865478604 are tagged `motorway` with `on_road:
null`. The field is metadata only (every grade in this sweep comes from Valhalla `trace_attributes`
edges, not from this field), so no verdict depends on it, but it is worth a data check.
(b) Node **6486202530** is listed twice in
`assets/access_points.json` - once as Riverwalk Trail (`on_road: Sumner Link Trail`) and once as
Sumner Link Trail (`on_road: Riverwalk Trail`), with identical coordinates
(47.196487, -122.251072). Node **2670828792** is likewise listed under both Sumner Link Trail and Lake
Tapps Parkway Trail. The sweep therefore graded those two nodes twice.

## 4. How the sweep graded, and whether that matches the app

* `scripts/corridor_sweep.py` routes home to the access point with `costing=bicycle` and
  `costing_options` = `bicycle_type: hybrid`, `use_roads: 0.1`, `avoid_bad_surfaces: 0.3`,
  `use_hills: 0.25` (`corridor_probe.APP_COSTING`) - **exactly** the body `docs/SPEC.md` section 7
  step 3 specifies.
* It then calls `trace_attributes` on the route shape and grades every edge with
  `corridor_probe.Grader.grade`, which is a line-by-line mirror of `SafetyGrader.gradeEdge` +
  `applyMeasuredVolume` + `TrafficTable.lookup` + `LimitsTable.override`: D11 surface reject, then
  E (never-classes, or >= 40 mph), then A (trail use / separated lane / bicycle_network), B (dedicated
  at <= 25 mph), C (residential / service / living_street at <= 25 mph), D (tertiary or secondary at
  30-35 mph), UNGRADED (no speed anywhere), and finally the ladder-hole D. Measured volume then moves
  the grade (low under 1500 cannot be D; medium 1500-7000 promotes only a ladder-hole D at <= 25 mph;
  high over 7000 becomes D), and the D14 caps pull A/B down to C for a stale count or LTS 4.
* `corridor_probe.d10` mirrors `SafetyGrader.evaluateD10(breakdown, 0.10, 0.5)`.
* **Verification.** Three routes were re-run from scratch on 2026-09-16 and reproduced the sweep
  exactly: node 6486202557 -> 5.890 km, D = 158 m; node 2670824385 -> 7.646 km, D = 2113 m;
  node 6277555604 -> 7.604 km, D = 1297 m. Same named stretches, same metres.

### 4a. Two rule-level differences (neither changes a verdict in this sweep)

1. **D11 / REJECTED is not mirrored.** `corridor_probe.breakdown` folds `REJECTED` into the graded
   metre count and `d10` never raises the `rejected surface (D11)` violation that
   `SafetyGrader.evaluateD10` raises (`if (breakdown.hasRejected) violations.add(...)`). The sweep
   could therefore have passed a route with a dirt / rough / impassable edge. **Measured: it does not
   matter here - across all 103 records the only grades present are A (82 records), C (103) and D
   (103); there is no REJECTED edge in any swept route.**
2. **One-way grading vs the app's round trip.** The sweep grades the *one-way* home-to-access-point
   shape once (`breakdown(segs)` with `repeat=1`). The app plans a round trip and multiplies the
   commute by `repeat=2` (`SafetyGrader.breakdown(segments, repeat)`). The **share** cap (10 %) is
   repeat-invariant, but the **absolute** cap (0.5 mi of D) is not: between 805 m and 1610 m of
   one-way D, the sweep can pass where the app's round trip would fail. **Measured: no AP in this
   sweep sits in that window** - the failing cluster nearest the boundary has one-way D = 994 m
   (0.62 mi) and fails the one-way test too.
   The best passing AP was re-checked as a real round trip (`roundtrip_sumner6486202557_leg0/leg1`):
   **12.181 km total, D = 316 m (0.196 mi), 3.10 % of graded, E = 0 -> still passes**, with a 16.4 %
   ungraded share (below the 50 % "NOT VERIFIED" threshold of section 6b).

## 5. The best passing route: what it actually rides

Route home to node 6486202557, re-run as `recheck_sumner6486202557` (5.890 km, 18.3 min),
traced and graded edge by edge:

| metres | grade | road | Valhalla road_class | speed | volume used |
|---|---|---|---|---|---|
| 3038 | C | 122nd Avenue East | tertiary | 25 mph (override) | 2626 vpd (1996) STALE |
| 1371 | C | Edgewood Drive East | tertiary | 25 mph (override) | 2875 vpd (1994) STALE |
| 501 | C | 48th Street East | tertiary | 25 mph (override) | 2469 vpd (1996) STALE |
| 280 | C | 18th Street East | residential | 25 mph (override) | 381 vpd (1996) STALE |
| 231 | C | Sumner Heights Drive East | tertiary | no speed limit in OSM | 721 vpd (1978) STALE |
| 206 | C | Valley Avenue East | secondary | 25 mph (OSM) | 5088 vpd (1992) STALE |
| **158** | **D** | **Bridge Street** | secondary | 25 mph (OSM) | no count -> *ladder hole*, not volume |
| 60 | UNGRADED | (unnamed service road) | service_other | no speed limit | - |
| 22 | UNGRADED | West Valley Highway East | tertiary | no speed limit | 1877 vpd (1996) STALE |
| 22 | A | Sumner Link Trail | - | - | - |

**Answer to the open question: yes, the passing route really avoids Jovita Boulevard East.**
* No edge of the route is named Jovita Boulevard East (trace above).
* Minimum distance from any of the 203 shape points to the Jovita Boulevard East centreline
  (`overpass_jovita.json`, all 12 Jovita ways) is **1136.5 m**.
* The 158 m of grade D is **Bridge Street**, and it is D only because the SPEC section 6 ladder has a
  hole (secondary at 25 mph matches neither C nor D) - Bridge Street has no traffic count in
  `assets/traffic.json` or `assets/limits.json`, so volume did not condemn it, the hole did.
* For contrast, the failing node 2670824385 rides **Jovita Boulevard East 1703 m (D)** and
  **Stewart Road Southwest 410 m (D)**, with the route's minimum distance to Jovita = **0 m**.
  Jovita's counts: way 12200224 = **10358 vpd (1996, stale)**, the other 11 ways = 8708 vpd
  (1996, stale), all `lts: 4` (`assets/traffic.json`). Stewart Road Southwest = 14669 vpd (1995).

## 6. What `exclude_polygons` does to the Jovita corridor

Test: `exclude_polygons` around the corridor **as actually ridden** on the failing route
(11 Jovita edges, way ids 12200224 + 1455331513, offset plus/minus 60 m, ring decimated to 87 vertices).

| run | file | route km | D m | D share | D mi | E m | verdict |
|---|---|---|---|---|---|---|---|
| baseline (sweep) | `ap_Sumner_2670824385.json` | 7.646 | 2113 | 35.1 % | 1.31 | 0 | FAIL |
| with Jovita excluded | `exclude_jovita_polygons_route_2670824385.json` | **10.191** | **3254** | **32.2 %** | **2.02** | 0 | **FAIL (worse)** |
| passing AP, Jovita excluded (control) | `exclude_jovita_polygons_route_6486202557.json` | 5.890 | 158 | 2.7 % | 0.10 | 0 | PASS (unchanged) |

The excluded route no longer touches Jovita; it rides **142nd Avenue East 2032 m (D)**,
**Tacoma Avenue 572 m (D)**, **Puyallup Street 468 m (D)** and Bridge Street 182 m (D) instead.
So excluding Jovita swaps one 1703 m arterial for three arterials and costs 2.5 km more distance.
**Exclusion is a strict downgrade, and it still fails D10.**

API notes (all captured in the `exclude_test_*` files):
* `exclude_polygons` expects a list of polygons where each polygon is a **flat list of `[lon, lat]`
  pairs**. A list of `{"lat":..., "lon":...}` objects fails with `Failed to parse polygon: IsArray()`
  (error 137); a list of `[lat, lon]` pairs fails with
  `Latitude must be in the range [-90, 90] degrees`; a doubly nested ring fails with
  `Failed to parse polygon: IsNumber()`.
* There is a hard cap of **100 vertices per polygon**: error 176,
  `Exceeded maximum number of vertices for exclude_polygons: 100`.
* `exclude_closures` (GeoJSON, `coordinates` in `[lon, lat]`) returns HTTP 200 but had **no effect**:
  the Jovita-corridor run came back with identical geometry (7.646 km, Jovita 1703 m D), and a
  **control** rectangle laid over 122nd Avenue East - a road the route must use - also left the route
  at 5.890 km. The same rectangle passed as `exclude_polygons` produced
  `No path could be found for input` (error 442), which proves the control polygon was real and that
  only `exclude_polygons` is enforced in this build.

## 7. The missing OSM link, and the common failure road

### 7a. The failing clusters, by their dominant D road

| cluster | APs | trail | straight-line from home | route km | D m | dominant D road(s) | that road's count |
|---|---|---|---|---|---|---|---|
| D = 5198 | 1 | Sumner Link | 2190 | 9.308 | 5198 | East Valley Highway East 1899 m + Jovita Blvd E 1703 m | 10358 vpd (1996) on Jovita |
| D = 3288 / 3299 | 20 | Lake Tapps + Sumner Link | 1878-2137 | 5.99-8.69 | 3288-3299 | Jovita Blvd E + Stewart Road SW | 10358 / 14669 vpd |
| D = 2113 | 3 | Sumner Link (north stub) | 1951-2114 | 7.646-7.815 | 2113 | **Jovita Blvd E 1703 m + Stewart Road SW 410 m** | 10358 / 14669 vpd |
| D = 1297 | 18 | Riverwalk (Puyallup side) | 4865-5000 | 7.60-10.0 | 1297 | **Milwaukee Avenue East 1246 m** (+ 5th St NE 51 m) | 8025 vpd (2024) |
| D = 994 | 18 | Sumner Link (mid stub) | 3089-3200 | 7.79-8.61 | 994 | Puyallup Street 468 m + Tacoma Avenue 344 m + Bridge Street 182 m | no counts for Puyallup St / Tacoma Ave |

**Answer to "the common failure road": the two clusters do not share a road.** The D = 2113 cluster
fails on **Jovita Boulevard East** (plus Stewart Road Southwest); the D = 1297 cluster fails on
**Milwaukee Avenue East**. Their only shared roads are the harmless approach streets
(18th Street East, 114th Avenue East, and 60 m of unnamed service way - way id 736260642 - leaving
home). What they *do* share is the mechanism: **both are crossings of a river on the only
bicycle-legal road, with no quieter crossing nearby.** Jovita Boulevard East (OSM way 12200224)
crosses the **White River**; Milwaukee Avenue East crosses the **Puyallup River** on a bridge
(OSM way 12243373, `bridge=yes`).

### 7b. Is there a missing link under ~200 m? Yes - three of them, and they are measured

The OSM graph in the strip between home and the Sumner Link north stub
(`overpass_west_edgewood_strip_nodes.json`, 116 ways, box 47.238-47.247 x -122.268--122.252) splits
into **10 components**. Node-to-node gaps between the component that contains home and the components
that contain the roads to the trail:

| gap | between | metres (node to node) | Valhalla cost of that gap |
|---|---|---|---|
| **128 m** | **16th Street East** west stub (way 12189691) - **16th Street East** east stub (way 682030385) | 128 (47.24290,-122.25576 - 47.24286,-122.25406) | home to east stub 5.692 km; **west stub to east stub 2.530 km** (`gap_hop_gap3w_to_gap3e.json`) |
| **225 m** | unnamed service ways north ends (874996133 / 1078400228 / 918269523 / 702701767, lon about -122.2609) - unnamed service way 894827442 (47.24230,-122.25797) | 225 | home to 894827442 = **5.540 km** (373 m straight line) |
| **54 m** | **126th Avenue Court East** (way 12167558) - **126th Avenue Court East** (way 12167549): same street name, two ways | 54 (47.24158,-122.26143 - 47.24121,-122.26096) | home to north node 1.724 km (123 m straight line); node to node 2.057 km |

The 128 m gap sits exactly on the **SR-167 Valley Freeway**: at lat 47.2429 the freeway centreline is
at lon -122.25545 to -122.25483 (ways 970883806, 1051003241), that is, inside the 128 m gap, and
**no bridge way connects the two halves of 16th Street East**. Both halves are `highway=residential`,
`maxspeed=25 mph`, `surface=asphalt`, and both are named 16th Street East (the west half is also
called Nyberg Road Southeast).

What closing the gaps would be worth, measured with Valhalla:

| hop | straight | by bike today |
|---|---|---|
| home to 874996133 node (228 m away) | 228 m | **0.993 km** |
| home to 894827442 node (373 m away) | 373 m | **5.540 km** |
| 894827442 to 16th St E west stub | 180 m | **0.348 km** (same component) |
| 16th St E east stub to Sumner Link stub junction | 934 m | **0.937 km** (same component: 16th St E itself) |
| home to Sumner Link north stub junction | 1559 m | **6.483 km** |

So the chain home -> (225 m gap) -> West Valley Highway East / 17th Street Court East -> 16th Street
East west stub -> (128 m gap over SR-167) -> 16th Street East east stub -> Thornton Avenue SW -> the
trail stub exists **except for those two short pieces**. If both were bridged, the north stub would be
about 0.99 + 0.23 + 0.35 + 0.13 + 0.94 = **2.6 km** from home instead of 6.48 km, and Jovita
Boulevard East would not be used at all.

**But closing the gaps is not sufficient to make the route safe.** Any such link must use
**West Valley Highway East** (OSM way 627325431, `highway=tertiary`, `maxspeed=40 mph`), and the app's
ladder grades a 40 mph road with no dedicated lane as **E - and D10 allows no E**. Measured: the probe
route home to the 16th St E west stub (5.338 km) already carries **808 m of grade E**, all of it on
West Valley Highway East. Pedestrian costing does not help either:
`connect_pedestrian_home_to_sumner2670824385` returns **7.792 km**, that is, the same detour, so there
is no foot-only shortcut in OSM either.

For the **Riverwalk / D = 1297 cluster there is no missing link at all**: in
`overpass_puyallup_milwaukee_box_nodes.json` (1131 ways, box 47.193-47.214 x -122.300--122.274) the
Riverwalk Trail cycleways and Milwaukee Avenue East are in the **same connected component** - the trail
is reachable from the Puyallup street grid, it just has to be reached over a busy river bridge.

## 8. What the app must change

Concrete access-point node ids the planner should consider (all measured passing, from sections 3b/3c):

1. **Primary - Sumner Link, the Bridge Street cluster (D = 158 m, 5.87 km):**
   `110713098`, `6486202558`, `7090006592`, `6486202557`, `13179055162`, `6486202551`, `3730012154`,
   `2670806304`, `2670806307`, `110790321`, `2670806286`.
   Best single id: **110713098** (5.867 km, D = 158 m, E = 0, 82 m ungraded). Verified as a round trip:
   12.181 km, D = 316 m, 3.10 % - passes.
2. **Secondary - Sumner Link, the rest of the east / Bridge Street row (D = 174-182 m):**
   `6486202553`, `6486202556`, `13179759683`, `13179759695`, `6486202563`, `13179759696`, `5479081486`,
   `5242303703`, `5242303776`, `7098613132`, `7089994023`, `7090006597`, `110422923`, `110422926`,
   `110422931`, `8251417567`, `2670806303`, `6486202534`, `2670806296`, `6486202530`.
3. **Riverwalk Trail (D = 175 m):** `6486202530`, `6486202533`, `2101094348`, `3621050859`,
   `2101094355`. Five more (`12953091081`, `12953104608`, `2865478604`, `2101094324`, `6273297985`,
   9.1-9.67 km) also pass but should rank last.
4. **Never offer:** every access point whose route rides Jovita Boulevard East or Milwaukee Avenue East -
   the 16 Lake Tapps Parkway access points, the 28 failing Sumner Link nodes (the D = 994 / 2113 / 3288
   / 3299 / 5198 clusters), and the 18 failing Riverwalk nodes. Excluding Jovita with `exclude_polygons`
   does not help (10.191 km, D = 3254 m), so do not implement exclusion as a fix.
5. **Deduplicate access points.** Node 6486202530 is stored twice (Riverwalk Trail *and* Sumner Link
   Trail), node 2670828792 likewise (Sumner Link *and* Lake Tapps Parkway Trail). Key the table by
   `osm_node_id` (or by coordinates) so one physical access point is graded once.
6. **Tell the truth about the north stub.** SPEC section 7.1 measures the trail radius from the live
   position. From home the Sumner Link north stub (nodes 2670824385, 1141829782, 1141829849) is
   1.95-2.11 km away in a straight line but 7.65-7.82 km by bike, and every route to it fails D10 on
   Jovita Boulevard East. It must never be presented as an option at home; the app should say the trail
   is not reachable safely from here and offer discovery, not a 7.6 km caution route.
7. **Do not expect a costing or exclusion knob to fix access.** Confirmed here twice: the default app
   costing produced the sweep's geometry, and `exclude_polygons` made the failing route *worse*.
   Safety comes from access-point choice, exactly as `docs/SPEC.md` section 6b states.
