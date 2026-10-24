# Regional trails: what the bundled data is, and is NOT

Written 2026-09-16, revised the same day after two rounds of review, for the regional
bundle (`docs/DECISIONS.md` D23, final answer (a)).
Producer: `tools/build_regional_trails.py` -> `docs/data/trails_regional.json` ->
`app/src/main/assets/trails_regional.json`. Measured numbers and the full drop tables:
`docs/data/trails_regional.md` (generated; do not edit that one by hand).

## What it is

The OpenStreetMap **route relations** tagged `route=bicycle` or `route=mtb` for:

1. **Washington state** -- read from the OSM extract already on this disk,
   `valhalla/custom_files/washington-latest.osm.pbf` (md5 verified against the `.md5`
   beside it; the same extract the self-hosted Valhalla server was built from).
2. **The Portland metro and the northern Willamette Valley** -- an Overpass bbox query,
   `45.0..46.0 N, -123.7..-121.8 W`. The first box tried, `45.2..45.8 / -123.2..-122.2`,
   cut real trails in half (Henry Hagg Lake sits at lon -123.25, so 21.1 km of a 21.5 km
   loop was clipped away), so it was widened after measuring.

Every geometry is **clipped** to that region and **reduced to its off-road parts**; the
shipped length is measured on that clipped, simplified, off-road-only shape. Per trail the
file keeps: the OSM relation id, the name, the route type, the surface when tagged,
`offroad_share`, `offroad_km`, `onroad_km_removed`, the simplified geometry, the computed
length, and whether the shape closes on itself.

**Current totals (2026-09-16, final build):** 191 trails (158 Washington, 33 Portland),
2,446.6 km of clipped simplified geometry, 14,550 geometry points, **0.39 MB** on disk
(392,575 bytes), Douglas-Peucker at 10 m.

## The one invariant that matters: every shipped metre is off-road

`SafetyGrader.gradeTrailLeg`
(`app/src/main/java/com/biketrailplanner/domain/SafetyGrader.kt:236`) hard-codes
`grade = Grade.A` with `onTrailGeometry = true` and the reason "trail geometry: <name> (OSM
route relation, paved cycleway/path)". It applies no traffic volume and no speed. That
assumption is true for the six near-home trails and **false for OSM in general**: a
`route=bicycle` relation can be a signed route along roads (measured example from an
earlier build: "Washington State Route 27 Bike Route", 2,629 m of road route per geometry
point). Loading one as a Trail would count road miles as separated trail and inflate the
safely-graded mileage -- what D10 and D14 exist to prevent, and dangerous on a highway.

Two build rules keep that from happening:

1. **Gate:** a relation is bundled only when at least **90%** of its clipped member-way
   length is off-road (off-road = `highway` in cycleway / path / footway / bridleway /
   track / pedestrian / steps; a way with no `highway` tag is unknown, and unknown is never
   off-road). This removed **349** relations (228 Washington, 121 Portland).
2. **Off-road geometry only:** inside a bundled relation, the on-road and unknown member
   pieces are **removed from `segments`** at build time. Total removed: **38.35 km**
   (32.12 Washington, 6.23 Portland) spread over 65 relations, maximum 11.8 km (Palouse to
   Cascades). Pieces are never joined across a removed road piece, so a relation can ship as
   several rideable spans -- which the app's `Trail` already supports (`rideableSpans`, the
   Sumner Link closure case). A relation is kept only if at least **1.0 km** of off-road
   geometry remains; **24** relations (21 Washington, 3 Portland) fell below that and were
   dropped, each with 0.26-0.95 km of off-road geometry left.

Result: `segments` contains no road geometry at all, so the app cannot grade a road metre
as trail, whatever it does with the rest of the route.

### The 0.80-0.90 band is excluded on purpose

The share distribution is dominated by two clusters (a real path network at 0.9-1.0 and a
route along streets at 0.0-0.1) with a real middle band. Washington has 185 of 413
relations at 0.90-1.00, 129 under 0.10, and 59 at 0.50-0.90; Portland has 41 / 88 / 20.
The 33 relations in the 0.80-0.90 band stay out. That is a **deliberate choice, not an
oversight**: with an all-or-nothing grader, admitting that band would grade up to 20% of
those routes as if they were separated trail. Coverage is not the constraint -- 191 trails
and 2,446.6 km is plenty for Washington plus Portland -- honesty of the grade is.

Worked examples of what the gate costs, so the tradeoff is visible rather than silent:

| relation | name | off-road share | what would have been mis-graded |
|---|---|---|---|
| 2338379 | Hagg Lake Trail (Portland) | 0.877 | 2.63 km of the park loop road |
| 1896877 | Olympic Discovery Trail | 0.51 | about half the route |
| 16837633 | Washington Timber Trail | 0.31 | about two thirds of the route |
| 3319525 | 10 (Washington) | 0.01 | the whole route |

## What it is NOT -- read this before trusting a number

**Every trail in this file is `verified = false`.** The app must set that flag on load.
None of the checks the six near-home trails went through have been done for a regional
trail:

1. **No closure check.** D15's closure knowledge is about the Sumner Link Trail only.
   Nothing here knows that any regional trail is closed, washed out or built over. A member
   way tagged `bicycle=no` is not re-checked at build time either.
2. **No surface audit.** `surface` is copied from the relation tag or the most common
   member way tag, and is `null` for many relations. It has not been verified on the ground.
   "Off-road" here means the OSM `highway` class only: `track` and `path` count as off-road
   and can be mud, and `footway` can be a sidewalk.
3. **No measured traffic.** `assets/traffic.json` and `assets/limits.json` cover Pierce
   County and the WSDOT state highways only. Away from home they match almost nothing, so
   **most roads grade UNGRADED**, and the UI must say "no local traffic data for this area"
   rather than implying the ride is fine.
4. **No access-point probe.** A regional trail has no `measured_route_km_from_home` table,
   so an approach to it is a guess until the app routes it live.
5. **No elevation, difficulty or MTB grading.** A `route=mtb` relation can be a green
   beginner loop or a double-black descent.
6. **Coverage is incomplete.** Only what OSM maps as a route *relation* is here. Most
   mountain-bike networks are mapped as individual ways with `mtb:scale` and are not
   relations, so `mtb` is thin (64 of 191 entries). Long-distance routes that cross the
   state line arrive in more than one geometry piece, because members outside the extract
   are absent.

## Filters, drop counts and what they cost (final build)

| filter | dropped | note |
|---|---|---|
| no geometry in the extract, or nothing left inside the region after clipping | 9 | all Washington |
| **road route (off-road share < 0.90)** | **349** | 228 Washington, 121 Portland |
| under 1.0 km of off-road geometry left after removing road pieces | 24 | 21 Washington, 3 Portland |
| over the 800 km length cap | **0** | see below |
| source geometry sparser than 500 m/point | 0 | |
| shipped geometry degenerate (< 3 points) | 1 | Centennial Trail r6554049, 1.4 km |
| shipped geometry sparser than 1,500 m/point | 0 | |
| duplicate relation id across the two sources | 5 | the Washington copy is kept |
| duplicate points under a different relation id | 1 | Yelm-Tenino Trail r1749027 duplicates r6047 |
| unnamed relations | 0 dropped, 5 relabelled | `Unnamed bike route (r<id>)` |

### The length cap is 800 km and removes nothing

Raised from 200 km on review. The cap is only insurance against a membership artifact
pretending to be a ride, and the 1,127 km artifacts are handled by the clipping plus the
density guard. 200 km was removing genuinely long off-road rail-trails; at 800 km the cap
removes **0** relations. A long trail is not a problem for this app: it plans a 5-50 mile
round trip and rides the part nearest the rider. Result of the change: **Palouse to
Cascades State Park Trail** is now bundled at 388.4 km of off-road geometry (share 0.971,
11.8 km of road removed from it) and is the longest entry in the file.

### Point-density thresholds (two of them, both measured)

- **500 m per point on the clipped source geometry.** This catches the membership
  artifacts: the "87/95/97 (Alaska)" relations measured 1,127 km over 243 points, about
  4.6 km per point. They are also road routes (0% off-road) and the gate removes them.
- **1,500 m per point on the shipped simplified geometry**, because Douglas-Peucker removes
  collinear points and a genuine straight rail-trail keeps few: measured on the shipped set,
  Cascade Trail 793 m/point, Tolt Pipeline Trail 636, Yelm-Rainier-Tenino Trail 571. The two
  entries cited in review (Zero Avenue 2,681 m/point, Washington State Route 27 Bike Route
  2,629 m/point) are gone -- both were road routes. 1,500 m/point sits at about twice the
  sparsest real trail and about 1.75x under the smallest artifact.
- **Fewer than 3 shipped points is dropped** as degenerate: a 2-point straight line the
  trail walk cannot interpolate along.

### Clipping, before -> after (the first review's defect)

The Washington extract keeps whole relations, so a relation's members can lie far outside
the state. Before clipping the geometry spanned **lat 42.307..55.354, lon -131.714..-116.834**
-- Alaska and British Columbia -- and the three "87/95/97 (Alaska)" relations each claimed
1,127.0 km with 243 points.

| | before clipping | shipped |
|---|---|---|
| relations | 516 | 191 |
| sum of lengths | 15,516.3 km | 2,446.6 km |
| min / median / max length | 1.013 / 6.802 / **1,127.0** km | 0.993 / 5.31 / **388.4** km |
| coordinate range | lat 42.307..55.354, lon -131.714..-116.834 | lat 45.000..48.991, lon -124.071..-117.025 |
| file size | 1.23 MB (unclipped, before the safety gate) | **0.39 MB** |

4,531.0 km of the unclipped total sat outside the region and was clipped away, and
`length_km_source_unclipped` is provenance only -- never a rideable length.

### Unnamed relations (fixed)

An empty `name` reached the rider's screen and the rotation signature. The 5 shipped
unnamed relations are now labelled `Unnamed bike route (r<relation id>)`. The two largest
unnamed entries in the pre-review build (about 194 km from 1,109 points, and about 170 km
from 856 points, both Washington) are gone entirely: they were road routes (17% and 12%
off-road) and the gate removes them.

## Licence and provenance

OpenStreetMap data, **ODbL 1.0**. `"(c) OpenStreetMap contributors"` must be displayed
wherever this data is shown, and this file is a Derivative Database that stays under the
ODbL. See `docs/DATA-LICENSES.md`.

The raw Overpass response behind the Portland half is kept for reproducibility at
`docs/discovery-raw/regional/portland-overpass-2026-09-16T185016Z.json.gz` (2.1 MB gzipped,
from 18.5 MB raw) with its `.meta.json` (mirror, byte count, `timestamp_osm_base`) and
`portland-query.overpassql` readable beside it. `docs/discovery-raw/` is gitignored.

Build-time tools only: **pyosmium** (`uv pip install osmium`) and numpy. The Android app
gains **no** new runtime dependency: it only reads the JSON asset.
