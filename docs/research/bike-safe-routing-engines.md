# Bike-safe routing engines — primary-source research

**Goal.** Which routing engines can compute a *low-traffic / bike-lane-preferring* bicycle route, and
how can an Android app call them — including whether the engine returns per-segment attributes
(road class, surface, bike lane, speed limit, traffic) that we can turn into a safety score.

**Method / evidence rules.** Every claim below comes from a primary source (official docs, official
OpenAPI/spec files, official source code) and carries the exact URL plus a one-line verbatim quote.
Live HTTP probes were run against public endpoints on **2026-09-15**; raw captures are saved in
`docs/research/raw/`. Anything not verifiable from a primary source is marked
**UNCERTAIN** and is *not* guessed. "Not documented" is reported as a finding, not as a "no".

**Scope covered:** Valhalla, BRouter, OpenRouteService, GraphHopper, RideWithGPS, CycleStreets, plus
how to obtain trail data (OSM `route=bicycle` / `route=mtb` relations, `highway=track` + `tracktype`,
Trailforks, MTB Project). **Correction to the original brief:** surface grades `grade1`–`grade5` belong to
`highway=track` + `tracktype`, **not** to `leisure=track` (which is a running/racing track) — see the
OpenStreetMap section.

---

### Contents

- [0. Executive summary](#0-executive-summary)
- [Valhalla](#valhalla) · [GraphHopper](#graphhopper) · [OpenRouteService (ORS)](#openrouteservice-ors) · [CycleStreets](#cyclestreets) · [BRouter](#brouter) · [RideWithGPS](#ridewithgps)
- BRouter on Android (offline / AIDL) — [section](#brouter-android-app--offline-operation-requested-in-scope-kept-separate-from-the-scoring-matrix-above)
- Trail data: [OpenStreetMap + Overpass](#openstreetmap-trail--bicycle-tagging--overpass-api) · [Trailforks](#trailforks) · [MTB Project](#mtb-project-adventure-projects--onx) · [Waymarked Trails](#waymarked-trails-osm-based-queryable) · [OpenMTBMap](#openmtbmap--velomap-garmin-maps-from-osm) · [Available vs gated](#availability-summary-what-is-available-vs-gated)
- [Cross-engine synthesis](#cross-engine-synthesis)
- [Appendix — source URL index](#appendix--source-url-index-and-how-to-verify)

---

## 0. Executive summary

| Engine | Bike traffic-stress model? | Per-segment attributes in response? | Hosted API | Key needed? | Self-host | Safety score output? |
|---|---|---|---|---|---|---|
| **Valhalla** | Yes — `use_roads`, `avoid_bad_surfaces`, `bicycle_type`; cost fn computes "roadway stress" | Not in `/route`; **yes** via `trace_attributes` (`cycle_lane`, `road_class`, `speed_limit`, `surface`, `bicycle_network`, `shoulder`, `sac_scale`) | `https://valhalla1.openstreetmap.de` (FOSSGIS, free) + Stadia Maps (paid) | **No** on FOSSGIS | Docker, ~175 MB compressed, needs OSM PBF | No |
| **BRouter** | Yes — profile scripts (`.brf`), incl. a dedicated low-traffic profile | Yes — GeoJSON `messages` per segment + GPX extensions | `brouter.de` | No | Java server or `segments4` tiles | No (profile-only) |
| **OpenRouteService** | Yes — `avoid_features` + `profile_params.weightings` | Yes — `extra_info` (`waytype`, `surface`, `suitability`, `green`, `noise`, `traildifficulty`, …) | `https://api.openrouteservice.org` | **Yes** | Docker + graph build | No official score; `suitability` is the closest |
| **GraphHopper** | Yes — custom models over `road_class`, `road_environment`, `surface`, `bike_network`, … | Yes — `details=` per-segment, and GPX `<extensions>` | `https://graphhopper.com/api/1` | **Yes** | Open-source core + Docker | No |
| **RideWithGPS** | Planner UI only — "prioritize based on safety for cycling"; the **public API is CRUD-only and has no routing endpoint** | No routing response; route `track_points` carry undocumented integer `S` (surface) / `R` (highway) codes | `https://ridewithgps.com/api/v1` | **Yes** (key + OAuth) | **No** (closed SaaS) | No |
| **CycleStreets** | Yes — `plan=quietest`; "an estimate of how busy a section is for cycling" | Partly — `segments[].provisionName` + `busynance` are in the sample output but not in the documented segment field list | `https://api.cyclestreets.net` / `cyclestreets.net/api` | **Yes** | **No** — engine not open source, setup repo says the needed repo "is not yet publicly available" | Closest to a score: route `quietness` 0–100 + `busynance` (m) |

**Trail data (see the "Trail data" section):** OSM `route=bicycle` / `route=mtb` relations,
`mtb:scale`, `sac_scale`, `tracktype`, `surface`, `smoothness` are **open (ODbL)** and queryable via the
**Overpass API**; **Trailforks is partner-gated** (`app_id`+`app_secret`, approval required);
**MTB Project's Data API is deprecated and closed to new keys**; **Waymarked Trails** has an undocumented but
live free JSON API.

---

## Valhalla

**Verdict:** strongest fit. It (a) has a bicycle costing model whose cost function literally
computes "roadway stress", (b) can return per-edge bike-infrastructure attributes
(`cycle_lane`, `bicycle_network`, `speed_limit`, `surface`, `road_class`, `sac_scale`) so we can
score and explain safety, and (c) is free to call with **no API key** on the FOSSGIS public
instance, which exposes `trace_attributes`. Verified live (see "Verified workflow" below).

### Bicycle profile & safety/traffic-stress modelling

Source: <https://valhalla.github.io/valhalla/api/route/api-reference/> (section "Bicycle costing options")

> "The default bicycle costing is tuned toward road bicycles with a slight preference for using cycleways or roads with bicycle lanes."

Documented bicycle costing options:

| Option | Range / values | Default | Doc quote |
|---|---|---|---|
| `bicycle_type` | `road`, `cross`, `hybrid`, `city`, `mountain` | `hybrid` | "The type of bicycle. The default type is hybrid." |
| `cycling_speed` | kph; road 25, cross 20, hybrid/city 18, mountain 16 | per type | "Cycling speed is the average travel speed along smooth, flat roads." |
| `use_roads` | 0–1 | **0.25** | "A cyclist's propensity to use roads alongside other vehicles. This is a range of values from 0 to 1, where 0 attempts to avoid roads and stay on cycleways and paths, and 1 indicates the rider is more comfortable riding on roads. Based on the use_roads factor, roads with certain classifications and higher speeds are penalized in an attempt to avoid them when finding the best path." |
| `use_hills` | 0–1 | 0.25 | "A cyclist's desire to tackle hills in their routes." |
| `avoid_bad_surfaces` | 0–1 (1 = disallow) | 0.25 | "When the value is equal to 1, all bad surfaces are completely disallowed from routing, including start and end points." |
| `use_living_streets` | 0–1 | 0.5 | "Values near 0 attempt to avoid living streets…" |
| `use_ferry` | 0–1 | 0.5 | — |
| `shortest` | bool | false | "Changes the metric to quasi-shortest, i.e. purely distance-based costing." |

Also accepted for bicycle: `maneuver_penalty`, `gate_cost`, `gate_penalty`, `destination_only_penalty`,
`country_crossing_cost`, `country_costing_penalty`, `service_penalty`.

**How low-traffic routing is actually produced** — the cost function computes a *stress* term.
Source: <https://github.com/valhalla/valhalla/blob/master/src/sif/bicyclecost.cc>

> "// Add in penalization for road classification (higher class roads are more stress)"

> "// Create an edge factor based on total stress (sum of accommodation factor and roadway stress) and the weighted grade penalty for the edge."

> "// How much to favor bicycle networks."
> "constexpr float kBicycleNetworkFactor = 0.95f;"

> "// Edge speed above which extra penalties apply (to avoid roads with higher speed traffic). This threshold is adjusted upwards with higher useroads factors."
> "constexpr uint32_t kSpeedPenaltyThreshold = 40; // 40 KPH ~ 25 MPH"

> "We want to try and avoid roads that specify to use a cycling path to the side" → `if (edge->use_sidepath()) { accommodation_factor += sidepath_factor_; }`

The same file holds `kRoadClassFactor[]` (Motorway 1.0 → Trunk 0.4 → Primary 0.2 → Secondary 0.1 →
Tertiary 0.05 → Unclassified 0.05 → Residential 0.0 → Service 0.5) and
`kCycleLaneTransitionFactor[]` keyed by shoulder/cycle-lane combination
("No shoulder or cycle lane" 1.0 → "No shoulder, separated cycle lane" 0.1).
Defaults in the same file: `kDefaultUseRoad`, `kDefaultUseHills = 0.25f`,
`kDefaultAvoidBadSurfaces = 0.25f`, `kDefaultUseLivingStreets = 0.5f`, `kDefaultBicycleType = "hybrid"`.

### Per-segment response attributes (can we score/explain safety?)

**The `/route` response does NOT contain per-segment attribution.** Verified live: response has only
`trip` → `legs` → `maneuvers` / `summary` / `shape`; each maneuver carries `instruction`,
`street_names`, `length`, `time`, `cost`, `begin_shape_index`, `end_shape_index`, `travel_mode`,
`travel_type`. There is no surface / lane / speed-limit / traffic field.

Per-edge attribution comes from the **map-matching `trace_attributes` action**:
<https://valhalla.github.io/valhalla/api/map-matching/>

Documented edge filter keys relevant to bike safety (verbatim from the doc's "Edge filter Keys"):

> `edge.road_class` · `edge.use` · `edge.surface` · `edge.unpaved` · `edge.cycle_lane` ·
> `edge.bicycle_network` · `edge.sac_scale` · `edge.shoulder` · `edge.sidewalk` · `edge.speed_limit` ·
> `edge.traversability` · `edge.weighted_grade` · `edge.max_upward_grade` · `edge.max_downward_grade` ·
> `edge.lane_count` · `edge.density` · `edge.names` · `edge.length` · `edge.way_id` ·
> `edge.begin_osm_node_id` · `edge.end_osm_node_id` · `edge.traffic_signal`

Node filters (useful for junction stress): `node.intersecting_edge.cyclability`,
`node.intersecting_edge.road_class`, `node.intersecting_edge.use`,
`node.intersecting_edge.lane_count`, `node.intersecting_edge.driveability`.

Field value enumerations (authoritative, from
<https://github.com/valhalla/valhalla/blob/master/valhalla/baldr/graphconstants.h>):

```
enum class CycleLane{ kNone=0, kShared=1, kDedicated=2, kSeparated=3 }
// bicycle_network is a BITMASK:
constexpr uint8_t kNcn = 1; // national   kRcn = 2; // regional
constexpr uint8_t kLcn = 4; // local      kMcn = 8; // mountain bike network
enum class Surface{ kPavedSmooth=0, kPaved=1, kPavedRough=2, kCompacted=3, kDirt=4, kGravel=5, kPath=6, kImpassable=7 }
enum class RoadClass{ kMotorway=0,kTrunk=1,kPrimary=2,kSecondary=3,kTertiary=4,kUnclassified=5,kResidential=6,kServiceOther=7 }
enum class Use{ kRoad=0,kTrack=3,kLivingStreet=10,kCycleway=20,kMountainBike=21,kSidewalk=24,kFootway=25,kSteps=26,kPath=27,kPedestrian=28,kBridleway=29,... }
enum class Traversability{ kNone=0,kForward=1,kBackward=2,kBoth=3 }
enum class SacScale{ kNone=0,kHiking=1,kMountainHiking=2,kDemandingMountainHiking=3,kAlpineHiking=4,kDemandingAlpineHiking=5,kDifficultAlpineHiking=6 }
```

`edge.density` semantics (0–15) are **not** documented in the API reference; source only says
`constexpr uint32_t kMaxDensity = 15; // Maximum relative density at a node or within a tile`.
Observed `density: 15` on a dense city-centre edge (Amsterdam). **UNCERTAIN:** the exact scale direction.

### Verified workflow (live, no API key, free public server)

1. `POST https://valhalla1.openstreetmap.de/route` with
   `{"costing":"bicycle","costing_options":{"bicycle":{"use_roads":0.05,"avoid_bad_surfaces":0.9}}}`
   → `200`, returns `trip.legs[0].shape` (encoded polyline) + maneuvers. **No per-edge attributes.**
2. `POST https://valhalla1.openstreetmap.de/trace_attributes` with
   `{"encoded_polyline":"<shape from step 1>","costing":"bicycle","shape_match":"edge_walk",`
   `"filters":{"attributes":["edge.road_class","edge.cycle_lane","edge.speed_limit","edge.surface","edge.bicycle_network","edge.use"],"action":"include"}}`
   → `200`, 94 edges returned, e.g.
   `{"speed_limit":30,"bicycle_network":0,"cycle_lane":"none","surface":"paved","use":"road","road_class":"residential"}`

`edge_walk` is the correct mode for this: the doc says
> "edge_walk … This algorithm requires nearly exact shape matching, so it should only be used when the shape is from a prior Valhalla route."

So the app can compute a low-stress route **and** retrieve the infrastructure attributes of every
edge of that exact route, then turn them into its own safety score. Raw samples saved:
`docs/research/raw/valhalla_live_route_sample.json`,
`docs/research/raw/valhalla_live_trace_attributes_edge_walk.json`.

### Hosted API: endpoint, auth, pricing, limits

**Free, no API key — FOSSGIS:** `https://valhalla1.openstreetmap.de/`
Live `/status` (`2026-09-15`, version 3.8.3):

> `{"version":"3.8.3-cc015b6","tileset_last_modified":1789364340,"available_actions":["tile","status","centroid","expansion","transit_available","trace_attributes","trace_route","isochrone","optimized_route","sources_to_targets","height","route","locate"]}`

Demo UI: <https://valhalla.openstreetmap.de/>. Rate limits: **not documented** on any reachable page
(the FOSSGIS wiki page <https://www.fossgis.de/wiki/Valhalla> is behind an Anubis bot-challenge).
**UNCERTAIN.** Fair-use assumed; do not treat as a production SLA.

**Hosted-commercial (Stadia Maps)** — same Valhalla API shape:
endpoint `https://api.stadiamaps.com/route/v1?api_key=YOUR-API-KEY`
(<https://docs.stadiamaps.com/routing/standard-routing/>). Pricing
(<https://stadiamaps.com/pricing/>): Free `$0 /month`, "200,000 credits/month", "Basic APIs only",
"Commercial use not allowed"; `Starter $20 /month`, 1,000,000 credits/month. Credit schedule:
"Standard Routing 20/req", "Trace Attributes 20/req". The standard-routing page states
"Traffic-influenced routing requires a Standard plan or higher."
**UNCERTAIN:** whether "Standard Routing" is a "Basic API" allowed on the Free plan.

### Limits: max waypoints, max route distance

Documented *defaults* (authoritative config source
<https://github.com/valhalla/valhalla/blob/master/scripts/valhalla_build_config>):

```
"bicycle": { "max_distance": 500000.0, "max_locations": 50,
             "max_matrix_distance": 200000.0, "max_matrix_location_pairs": 2500 },
"trace":   { "max_distance": 200000.0, "max_shape": 16000, "max_gps_accuracy": 100.0 }
```

The public FOSSGIS instance is configured **tighter** — measured live:

* 11 locations → `400 {"error_code":150,"error":"Exceeded max locations: 10"}`
  → **max 10 waypoints** on that server.
* Amsterdam → Barcelona by bicycle → `400 {"error_code":154,"error":"Path distance exceeds the max distance limit: 150000 meters"}`
  → **max 150 km per bicycle route** on that server. The same request with `"costing":"auto"`
  returned `200`, so this is a bicycle profile limit, not a server-wide distance limit.

### Self-hosting

* Images: `ghcr.io/valhalla/valhalla` (base) and `ghcr.io/valhalla/valhalla-scripted`
  (scripted, recommended). Docs: <https://valhalla.github.io/valhalla/start/installation/>
  ("> To run Valhalla locally or your own server, we recommend using one of our Docker images").
* **Measured compressed image sizes** (OCI manifest, linux/amd64, tag `latest`, as of this research):
  `valhalla-scripted` ≈ **175 MB**, base `valhalla` ≈ **249 MB**. (Uncompressed on-disk is larger.)
* Requires an OSM extract. From <https://github.com/valhalla/valhalla/blob/master/docker/README.md>:
  > `wget -O custom_files/andorra-latest.osm.pbf https://download.geofabrik.de/europe/andorra-latest.osm.pbf`
  > `docker run -dt --name valhalla -p 8002:8002 -v $PWD/custom_files:/custom_files ghcr.io/valhalla/valhalla-scripted:latest`
* Convenience env vars: `tile_urls`, `force_rebuild`, `build_elevation` (HGT/SRTM "Skadi" tiles,
  needed for elevation), `build_admins` (default `True`), `build_time_zones` (default `True`),
  `build_tar`, `serve_tiles`, `server_threads`, `tileset_name`, `use_tiles_ignore_pbf`.
* Config note (relevant to us): "If you need to customize Valhalla's configuration to e.g. increase
  the allowed maximum distance for the `/route` service, just edit `custom_files/valhalla.json` and
  restart the container." — so the 150 km public cap is a config value we could raise ourselves.
* Bindings published: Python (`pyvalhalla` on PyPI) and Node.js. **No official Android SDK/AAR is
  listed in the Valhalla docs.** An Android app would either call a self-hosted HTTP server or embed
  the C++ library via JNI. **UNCERTAIN:** community Android JNI wrappers exist but none are official.

### Safety / traffic-stress score output?

**No.** Valhalla computes "roadway stress" internally for cost, but neither the `/route` response nor
`trace_attributes` returns a safety, stress, or traffic score. The app must build its own score from
`cycle_lane` + `road_class` + `speed_limit` + `shoulder` + `bicycle_network` + `surface`.

---
## GraphHopper

### Bicycle profile & safety/traffic-stress modelling

**Built-in bicycle profiles (open source engine).** The custom-model files shipped in the repo are listed at
<https://api.github.com/repos/graphhopper/graphhopper/contents/core/src/main/resources/com/graphhopper/custom_models>
(exact directory: `core/src/main/resources/com/graphhopper/custom_models`, **not** `.../routing/util/...`).
Bicycle-relevant entries are exactly: `bike.json`, `bike_avoid_private.json`, `bike_avoid_private_node.json`,
`bike_avoid_turns.json`, `bike_elevation.json`, `bike_tc.json`, `cargo_bike.json`, `mtb.json`, `racingbike.json`.

**There is no `ebike` profile.** UNCERTAIN: no `ebike` profile exists — no file named `ebike*.json` exists in the
`custom_models` directory listing, there are 0 occurrences of the string `ebike` in
<https://docs.graphhopper.com/_bundle/openapi.yaml>, and the hosted profile table lists `ecargobike` instead.
Closest documented relatives:
> "ecargobike | Similar to racingbike but a bit faster and with width constraints. | bike access but without steps and footways"
(`graphhopper-openapi-bundle.yaml`, tag "Map Data and Routing Profiles")
`cargo_bike.json` header comment:
> "We assume e-bikes and lower the speed to prefer shorter and not faster routes automatically"

`config-example.yml` ships only this bicycle profile enabled and two commented out:
> "   - name: bike
>      custom_model_files: [bike.json, bike_elevation.json]"
> "#    - name: racingbike
> #      custom_model_files: [racingbike.json, bike_elevation.json]
> #
> #    - name: mtb
> #      custom_model_files: [mtb.json, bike_elevation.json]"

Active bicycle profile names in the hosted API (`Map Data and Routing Profiles` table): `bike` ("Trekking bike
avoiding hills"), `mtb` ("Mountainbike"), `racingbike` ("Bike preferring roads"), `ecargobike`. There is no
`ebike` row.

**Traffic-stress-like modelling: yes, but not called "safety"/"stress".** GraphHopper models it as a per-edge
`priority` factor. `docs/core/custom-models.md`:
> "edge_weight = edge_distance / (speed * priority) + edge_distance * distance_influence + turn_penalty"
> "The `priority` factor ... changing the priority only changes the edge weight, and not the travelling time. By default, `priority` is always `1`"

`bike_priority` is listed as an encoded value and documented as numeric 0..1 (`docs/core/custom-models.md`):
> "with postfix `_priority` contains the road preference without changing the speed for a specific vehicle (0..1)"
Implementation detail (contradicts "0..1" at the top end):
`DefaultImportRegistry.java` → `VehiclePriority.create("bike", 4, PriorityCode.getFactor(1), false)`; with
`PriorityCode.getFactor(v) = v/10.0` and `BEST(15)` the representable maximum is 1.5. This discrepancy is recorded,
not resolved.

The actual derivation lives in
`core/src/main/java/com/graphhopper/routing/util/parsers/BikeCommonPriorityParser.java`
(verbatim constants and thresholds):
> `protected final Set<String> goodSurface = Set.of("paved", "asphalt", "concrete");`
> "addPushingSection("footway"); addPushingSection("pedestrian"); addPushingSection("steps"); addPushingSection("platform");"
> "avoidHighwayTags.put("secondary", AVOID); avoidHighwayTags.put("secondary_link", AVOID); avoidHighwayTags.put("bridleway", AVOID);"
> "boolean highSpeed = maxSpeed != MaxSpeed.MAXSPEED_MISSING && maxSpeed >= 71;"
> "} else if (preferHighwayTags.contains(highway) || maxSpeed <= 30) {"
> "if (way.hasTag("bicycle", "use_sidepath")) { weightToPrioMap.put(100d, REACH_DESTINATION); }"
> "} else if (cyclewayValues.contains("track")) { weightToPrioMap.put(100d, VERY_NICE); }"
> "if (way.hasTag("railway", "tram")) weightToPrioMap.put(50d, AVOID_MORE);"
`BikePriorityParser.java` adds:
> "avoidHighwayTags.put("primary", BAD); avoidHighwayTags.put("primary_link", BAD);"
> "preferHighwayTags.add("service"); preferHighwayTags.add("residential"); preferHighwayTags.add("unclassified"); preferHighwayTags.add("cycleway");"
> "setSpecificClassBicycle("touring");"

Priority scale, exact (`core/src/main/java/com/graphhopper/routing/util/PriorityCode.java`):
> "EXCLUDE(0), REACH_DESTINATION(1), VERY_BAD(3), BAD(5), AVOID_MORE(6), AVOID(8), SLIGHT_AVOID(9), UNCHANGED(10), SLIGHT_PREFER(11), PREFER(12), VERY_NICE(13), BEST(15);"
> "public static double getFactor(int value) { return (double) value / 10.0; }"

**Encoded values usable in bike custom models** (`docs/core/custom-models.md`, "Road attributes"):
> "road_class: (OTHER, MOTORWAY, TRUNK, PRIMARY, SECONDARY, TRACK, STEPS, CYCLEWAY, FOOTWAY, ...)"
> "road_environment: (ROAD, FERRY, BRIDGE, TUNNEL, ...)"
> "surface: (PAVED, DIRT, SAND, GRAVEL, ...)"
> "smoothness: (EXCELLENT, GOOD, INTERMEDIATE, ...)"
> "bike_network, foot_network: (MISSING, INTERNATIONAL, NATIONAL, REGIONAL, LOCAL, OTHER)"
> "track_type: (MISSING, GRADE1, GRADE2, ..., GRADE5)"
> "urban_density: (RURAL, RESIDENTIAL, CITY)"
> "hike_rating: a number from 0 to 6 for the `sac_scale` in OSM, e.g. 0 means "missing", 1 means "hiking", 2 means "mountain_hiking", 3 means demanding_mountain_hiking, 4 means alpine_hiking, 5 means demanding_alpine_hiking, and 6 means difficult_alpine_hiking"
> "mtb_rating: a number from 0 to 7 for the `mtb:scale` in OSM, e.g. 0 means "missing", 1 means `mtb:scale=0`, 2 means `mtb:scale=1` and so on. A leading "+" or "-" character is ignored."
> "- get_off_bike" (boolean group)
> "- average_slope: a signed decimal for 100 * "elevation change" / edge_distance for a road segment; it changes the sign in reverse direction; see max_slope"
> "- max_slope: a signed decimal for the maximum slope ... It changes the sign in reverse direction."
> "- max_speed: the speed limit from a sign or from local default speed limits (km/h)"
Full enumerations from source:
- `RoadClass.java`: `OTHER, MOTORWAY, TRUNK, PRIMARY, SECONDARY, TERTIARY, RESIDENTIAL, UNCLASSIFIED, SERVICE, ROAD, TRACK, BRIDLEWAY, STEPS, CYCLEWAY, PATH, LIVING_STREET, FOOTWAY, PEDESTRIAN, PLATFORM, CORRIDOR, CONSTRUCTION, BUSWAY`
- `Surface.java`: `MISSING, PAVED, ASPHALT, CONCRETE, PAVING_STONES, COBBLESTONE, UNPAVED, COMPACTED, FINE_GRAVEL, GRAVEL, GROUND, DIRT, GRASS, SAND, WOOD, OTHER`
- `Smoothness.java`: `MISSING, EXCELLENT, GOOD, INTERMEDIATE, BAD, VERY_BAD, HORRIBLE, VERY_HORRIBLE, IMPASSABLE, OTHER`
- `TrackType.java`: `MISSING, GRADE1, GRADE2, GRADE3, GRADE4, GRADE5`
- `RoadEnvironment.java`: `OTHER, ROAD, FERRY, TUNNEL, BRIDGE, FORD`
- `RouteNetwork.java` (used for `bike_network`): `MISSING, INTERNATIONAL, NATIONAL, REGIONAL, LOCAL, OTHER`
- `BikeRoadAccess.java`: `MISSING, YES, DESIGNATED, DISMOUNT, DESTINATION, PRIVATE, MILITARY, USE_SIDEPATH, NO` (key `bike_road_access`)
- `GetOffBike.java`: `public static final String KEY = "get_off_bike";`
- `BikeNetwork.java`: `public static final String KEY = RouteNetwork.key("bike");` (i.e. `bike_network`)
`bike_network` is derived from OSM route relations — `OSMBikeNetworkTagParser.java`:
> "with lcn=yes is mapped in OSM way consider this as route=bicycle" (comment)

**`bike.json` priority rules, verbatim (all values are the shipping defaults):**
```json
"priority": [
  { "if": "true",  "multiply_by": "bike_priority" },
  { "if": "bike_network == INTERNATIONAL || bike_network == NATIONAL",  "multiply_by": "1.4" },
  { "else_if": "bike_network == REGIONAL || bike_network == LOCAL",  "multiply_by": "1.2" },
  { "if": "road_environment == FERRY", "multiply_by": "0.5" },
  { "if": "mtb_rating > 2",  "multiply_by": "0" },
  { "if": "hike_rating > 1",  "multiply_by": "0" },
  { "if": "bike_road_access == NO", "multiply_by": "0" },
  { "if": "!bike_access && (!backward_bike_access || roundabout)",  "multiply_by": "0" },
  { "else_if": "!bike_access && backward_bike_access",  "multiply_by": "0.2" }
],
"speed": [
  { "if": "road_environment == FERRY", "limit_to": "ferry_speed" },
  { "else": "", "limit_to": "bike_average_speed" },
  { "if": "!bike_access && backward_bike_access", "limit_to": "6" }
]
```
Required config for `bike.json`, verbatim header comment:
> "// graph.encoded_values: bike_priority, bike_access, bike_network, roundabout, bike_average_speed, bike_road_access, foot_road_access, average_slope, mtb_rating, hike_rating, country, road_class, ferry_speed"

**`mtb.json`** thresholds: `{ "if": "mtb_rating > 6", "multiply_by": "0" }`, `{ "else_if": "mtb_rating > 3",  "multiply_by": "0.5" }`,
`{ "if": "hike_rating > 4",  "multiply_by": "0" }`, `{ "if": "mtb_rating > 3",  "limit_to": "4" }`;
`mtb_network` boost: `multiply_by "1.5"` for INTERNATIONAL/NATIONAL, `"1.3"` for REGIONAL/LOCAL.

**`racingbike.json`** thresholds: `{ "if": "mtb_rating > 2",  "multiply_by": "0" }`, `{ "else_if": "mtb_rating == 2",  "multiply_by": "0.5" }`,
`{ "if": "hike_rating > 1",  "multiply_by": "0" }`.

**Elevation / slope model — `bike_elevation.json` (complete file body):**
```json
"speed": [
    { "if":      "average_slope >= 15", "limit_to": "3"},
    { "else_if": "average_slope >= 12", "limit_to": "6"},
    { "else_if": "average_slope >=  8", "multiply_by": "0.60"},
    { "else_if": "average_slope >=  4", "multiply_by": "0.90"},
    { "else_if": "average_slope <= -4", "multiply_by": "1.10"}
]
```
`docs/core/elevation.md`:
> "Per default elevation is `srtm` and if you remove the config line you automatically disable it and reduce storange and RAM usage a bit."
> "## Custom Models
>
> The `average_slope` and `max_slope` attributes of a road segment can be used to make your routing
> elevation-aware, i.e. to prefer or avoid, to speed up or slow down your vehicle based on the elevation
> change."

**Turn-cost variants** (`bike_tc.json` header, verbatim):
> "// Configures bike that avoids zig-zag routes and uses the turn_costs based approach to avoid private-only and destination-only roads."
> "// Note, it is not recommended to increase these costs heavily as otherwise larger, bike-unfriendly roads will be preferred as they often require less turns."
> "//        vehicle_types: [bicycle]
> //        u_turn_costs: 20"
`bike_avoid_turns.json` (turn penalties, verbatim): straight `add "0"`, right (`25..80`) `add "3"`, sharp right (`80..180`) `add "4"`, left `add "3"`, sharp left `add "4"`.

**"Bike safety" / "avoid" request parameters: NOT DOCUMENTED.**
- `avoid` does **not** exist as a request parameter in the current hosted spec. Enumerated GET-parameter names of
  `/route` in <https://docs.graphhopper.com/_bundle/openapi.yaml> are exactly: `profile, point, point_hint,
  snap_prevention, curbside, locale, elevation, details, optimize, instructions, calc_points, debug,
  points_encoded, ch.disable, heading, heading_penalty, pass_through, algorithm, round_trip.distance,
  round_trip.seed, alternative_route.max_paths, alternative_route.max_weight_factor,
  alternative_route.max_share_factor`. No `avoid*` parameter.
- The documented replacement is the custom model. `postroute.md`, `snap_preventions`:
  > "Note that once snapped the routing algorithm can still route over bridges (or the other values). To avoid this you need to use the custom_model."
- There is no request parameter or documented profile named `bike_safety`, `quiet`, or similar.

### Per-segment response attributes (can we score/explain safety?)

**Hosted Routing API `details` parameter — full documented list**, verbatim (`postroute.md` and `getroute.md`):
> "The following path details are available: street_name, street_ref, street_destination, leg_time, leg_distance, roundabout, country, time, distance, max_speed,
> max_weight, max_width, toll, road_class, road_class_link, road_access, road_environment, hazmat, hazmat_tunnel, hazmat_water,
> lanes, surface, smoothness, hike_rating, mtb_rating, foot_network, bike_network."
So for bicycle scoring the hosted API exposes exactly: `road_class`, `road_class_link`, `road_environment`,
`road_access`, `surface`, `smoothness`, `hike_rating`, `mtb_rating`, `bike_network`, `max_speed`, `toll`, `lanes`,
`max_weight`, `max_width`, `country`, `roundabout`, plus the meta details `street_name`, `street_ref`,
`street_destination`, `leg_time`, `leg_distance`, `time`, `distance`.

**NOT DOCUMENTED in the hosted `details` list** (i.e. do not assume they can be requested on the hosted API):
`bike_priority`, `get_off_bike`, `bike_road_access`, `track_type`, `curbside`, `edge_id`, `average_speed`,
`average_slope`, `max_slope`, `urban_density`, `state`.
The OpenAPI `details` parameter has no enum (`items: {type: string}`), so the list above is descriptive, not machine-enforced.

**Response shape**, verbatim (`postroute.md`):
> "`paths.details` (object) — Details, as requested with the details parameter. Consider the value {"street_name": [[0,2,"Frankfurter Straße"],[2,6,"Zollweg"]]}. In this example, the route uses two streets: The first, Frankfurter Straße, is used between points[0] and points[2], and the second, Zollweg, between points[2] and points[6]."
> "`paths.points_encoded` (boolean) — Whether the points and snapped_waypoints fields are polyline-encoded strings rather than JSON arrays of coordinates."
> "`points_encoded` (boolean) — Allows changing the encoding of location data in the response. The default is polyline encoding ... Set this parameter to false to switch the encoding to simple coordinate pairs like [lon,lat], or [lon,lat,elevation]."

**`curbsides` are motor-vehicle-only in the hosted API**, verbatim (`postroute.md`):
> "`curbsides` (array) — Optional parameter. It specifies on which side a point should be relative to the driver when she leaves/arrives at a start/target/via point. You need to specify this parameter for either none or all points. Only supported for motor vehicle profiles and OpenStreetMap."
So for `profile=bike` on the hosted API, curbsides are explicitly not supported. Self-hosted `docs/web/api-doc.md`:
> "curbside | any | Optional parameter applicable to edge-based routing only. It specifies on which side a query point should be relative to the driver when she leaves/arrives at a start/target/via point. Possible values: right, left, any. Specify for every point parameter. See similar heading parameter."
> "curbside_strictness| strict | Optional parameter. If it is set to "strict" there will be an exception in case the curbside parameters cannot be fulfilled (e.g. specifying the wrong side for one-ways). If you don't want this use "soft"."

**Self-hosted `details` list** (`docs/web/api-doc.md`) is different and explicitly open-ended:
> "details | - | Optional parameter. You can request additional details for the route: `average_speed`, `street_name`, `edge_id`, `road_class`, `road_environment`, `max_speed` and `time` (and see which other values are configured in `graph.encoded_values`). Multiple values are specified like `details=average_speed&details=time`. The returned format for one detail segment is `[fromRef, toRef, value]`. The `ref` references the points of the response. Value can also be `null` if the property does not exist for one detail segment."
Self-hosted default `graph.encoded_values` in `config-example.yml`:
> "    car_access, car_average_speed, country, road_class, roundabout, max_speed, road_environment,
>     foot_access, foot_average_speed, foot_priority, foot_road_access, hike_rating, average_slope,
>     bike_access, bike_average_speed, bike_priority, bike_road_access, bike_network, mtb_rating, ferry_speed"
`/info` exposes what is actually available (`docs/web/api-doc.md`):
> "encoded_values | The encoded values that can be used as path details or in a custom_model"

**GPX output.** Self-hosted only. `RouteResource.java` GET `/route` accepts `type=gpx` plus `gpx.route`,
`gpx.track`, `gpx.waypoints`, `gpx.trackname`, `gpx.millis` (defaults quoted verbatim):
> "@QueryParam("gpx.route") @DefaultValue("true") boolean withRoute /* default to false for the route part in next API version, see #437 */,
>             @QueryParam("gpx.track") @DefaultValue("true") boolean withTrack,
>             @QueryParam("gpx.waypoints") @DefaultValue("false") boolean withWayPoints,
>             @QueryParam("gpx.trackname") @DefaultValue("GraphHopper Track") String trackName,"
`TypeGPXFilter.java` translates the query parameter into an Accept header:
> "if (maybeType != null && maybeType.equals("gpx")) { rc.getHeaders().putSingle(HttpHeaders.ACCEPT, "application/gpx+xml"); }"
GPX **does** carry `gh:*` extensions, but only on `<rtept>` and only instruction-level data — the requested
`details=` path details are **not** in the GPX. `GpxConversions.createGPX(InstructionList instructions, String
trackName, long startTimeMillis, boolean includeElevation, boolean withRoute, boolean withTrack, boolean
withWayPoints, String version, Translation tr)` takes no details argument, and `createRteptBlock` emits exactly:
> "output.append("<gh:distance>")... output.append("<gh:time>")... output.append("<gh:direction>")... output.append("<gh:azimuth>")... output.append("<gh:exit_number>")... output.append("<gh:sign>")"
Namespace declared in the GPX header:
> "+ " xmlns:gh=\"https://graphhopper.com/public/schema/gpx/1.1\">"
> "// This xmlns:gh acts only as ID, no valid URL necessary.
> // Use a separate namespace for custom extensions to make basecamp happy."
`<trkpt>` carries only `<ele>` and `<time>`:
> "if (includeElevation) gpxOutput.append("<ele>")... if (entry.getTime() != null) gpxOutput.append("<time>")"
Also: `gpx-schema.xsd` in the test resources contains **0** occurrences of the string `gh:`.
The hosted Directions API Routing API does **not** document any GPX output: the GET `/route` parameter list has no
`type` parameter, and the only GPX mentions in the OpenAPI bundle concern the Map Matching `/match` **request** body.
**NOT DOCUMENTED in https://docs.graphhopper.com/_bundle/openapi.yaml**: GPX response for `/route`.

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- Base URL, verbatim from the OpenAPI `servers` block: `https://graphhopper.com/api/1`
- Endpoint: `GET /route` and `POST /route` → `https://graphhopper.com/api/1/route`
- Auth: API key as a **query parameter** named `key`. Verbatim:
  > "Authenticate to the API by passing your key as a query parameter in every request."
  > "1. [Sign up for GraphHopper](https://graphhopper.com/dashboard/signup)"
  Verified live: `GET https://graphhopper.com/api/1/route?point=52.5,13.4&point=52.51,13.42&profile=bike&key=` → HTTP 401
  `{"message":"No API key specified. Please register and see documentation: https://www.graphhopper.com/developers/"}`
  (saved as `graphhopper-live-probe-no-key.json`).
- Free tier (graphhopper.com/pricing/, per plan column "Free"):
  > "Credits per day 500 per day"
  > "Max. locations per request 5 locations"
  > "Max. vehicles per request 1 vehicles"
  > "The Free Plan is for non-commercial use only. It has a limited credit count per minute, a limited set of vehicle profiles and cannot use the flexible mode (ch.disable=true)."
  Consequence: `ch.disable=true` is required for `custom_model` (`postroute.md`: "Use true to enable the flexible mode to use the custom_model"), so on the Free plan the bike custom-model / traffic-stress knobs are not available by contract.
  Free profile set (`graphhopper-openapi-bundle.yaml`):
  > "the free package supports only the routing profiles `car`, `bike` or `foot`"
  So on Free: `bike` yes, `mtb` / `racingbike` / `ecargobike` no.
- Paid plan prices (EUR, per month, from https://www.graphhopper.com/pricing/):
  - Basic `69€`, `5,000 per day`, `30 locations`, `2 vehicles`
  - Standard `199€`, `15,000 per day`, `80 locations`, `10 vehicles`
  - Premium `479€`, `50,000 per day`, `200 locations`, `20 vehicles`
  - Custom: `Bulk discount`, `Custom` credits/day, `up to 10K` locations, `up to 200` vehicles
  - Annual billing: "Select annual payment for annual billing; otherwise payment is monthly. Prices do not include VAT." and "We offer a 20% attribution discount."
- Rate limits (footnote on the pricing page, verbatim):
  > "Limits: a maximum of 10 requests per second with up to 1000 credits per minute (c/min) are available for the Premium Plan. For the Standard Plan, 2 requests per second with up to 400 c/min are available. For the Basic Plan, the maximum is 1 request per second with up to 100 c/min. Requests that violate the limits might be blocked to provide a stable service for all customers, see here for more details. The Free Plan does not include any guarantees.."
  Header-based accounting, verbatim from the API description:
  > "`X-RateLimit-Limit` | Your total daily credit limit"
  > "`X-RateLimit-Remaining` | Credits left until reset — measured **before** the current request's cost is deducted"
  > "`X-RateLimit-Reset` | Seconds until the daily credit counter resets"
  > "`X-RateLimit-Credits` | Credits consumed by the current request"
  > "A `429` response means the daily or per-minute limit is exhausted."
  OpenAPI `429` response: "API limit reached."
- Credit costs for Routing (https://www.graphhopper.com/faq/what-is-one-credit/, verbatim):
  > "A standard request with 2 to 10 locations costs 1 credit ... Above 10 locations, the cost is #locations / 10 e.g. a route with 25 locations costs 2.5 credits."
  > "A request that demands alternative routes (algorithm=alternative_route) or round trips (algorithm=round_trip) costs 2 credits."
  > "Requests with optimize=true (solves a Traveling Salesman problem) cost 10× the normal cost. E.g. optimizing 12 locations costs 12 credits instead of 1.2."

### Limits: max waypoints, max distance, other caps

Hosted API — max locations per request is a plan limit, not a documented constant. `getroute.md`, `point`:
> "The maximum number depends on your plan."
Values by plan (pricing page, "Max. locations per request"): Free `5 locations`, Basic `30 locations`, Standard
`80 locations`, Premium `200 locations`, Custom `up to 10K`.
Also (`postroute.md`, `points`):
> "The query points used to calculate the route, requiring at least a start and a destination point, with optional via-points in between."
400 response: "Your request is not valid. For example, you specified too few or too many points."
Is not documented as a numeric constant anywhere in the spec: "NOT DOCUMENTED in https://docs.graphhopper.com/_bundle/openapi.yaml" — a fixed max waypoint number.
`optimize` cross-reference (`getroute.md`): "Keep in mind that the limits on the number of locations of the Route Optimization API applies, and the request costs more credits."
`optimize` + custom model caveat: "If you set it to "true" then it cannot be used with ch.disable: true."

Self-hosted — no max waypoint count is documented; there are distance/time caps per request:
`config-example.yml` (verbatim):
> "# You can limit the max distance between two consecutive waypoints of flexible routing requests to be less or equal
>   # the given distance in meter. Default is set to 1000km.
>   routing.non_ch.max_waypoint_distance: 1000000"
`docs/core/deploy.md` (verbatim):
> "If you want to support none-CH requests you should consider enabling landmarks or limit requests to a
> certain distance via `routing.non_ch.max_waypoint_distance` (in meter, default is 1) or
> to a node count via `routing.max_visited_nodes`."
(the "(in meter, default is 1)" text in deploy.md conflicts with config-example.yml `1000000` and with the
"Default is set to 1000km" comment in config-example.yml — recorded, not resolved)
`config-example.yml`:
> "# The maximum time in milliseconds after which a routing request will be aborted. ... The default is Long.MAX_VALUE
>   # routing.timeout_ms: 300000"
Self-hosted GET `/route` also has `timeout_ms` per request (`api-doc.md`):
> "timeout_ms | infinity | Optional parameter. Limits the request runtime to the minimum between the given value in milli-seconds and the server-side timeout configuration"
Other caps: URL length only for GET (`api-doc.md`): "The GET request has an URL length limitation, so it won't work for many locations per request. In those cases use a HTTP POST request with JSON data as input."
Hosted POST body size: "This will also avoid the HTTP 413 error "Request Entity Too Large"." (gzip POST recommended).

### Self-hosting: Docker image (size if stated), OSM extract needed, effort

- Licence: Apache License 2.0. `LICENSE.txt` begins "Apache License / Version 2.0, January 2004". README:
  > "GraphHopper is a fast and memory-efficient routing engine released under Apache License 2.0."
  `NOTICE.md` at repo root states `## Licenses`: "GraphHopper licensed under the Apache license, Version 2.0 / Copyright 2012 - 2026 GraphHopper GmbH", and lists bundled third-party licenses (MIT, BSD-3-Clause, EDL, LGPL, public domain). Its data table:
  > "|OpenStreetMap data for the road network | [ODBL](https://www.openstreetmap.org/copyright) | yes | yes"
  (i.e. OSM data is the default and is included; there is no `licenses/` directory in the master tree).
- **No official Docker image.** README, verbatim:
  > "The Docker images created by the community from the `master` branch can be found [here](https://hub.docker.com/r/israelhikingmap/graphhopper) (currently daily). See the [Dockerfile](https://github.com/IsraelHikingMap/graphhopper-docker-image-push) for more details."
  Evidence it is not vendor-published:
  - `https://api.github.com/repos/graphhopper/graphhopper/git/trees/master?recursive=1` → 1626 entries, `"truncated": false`, and **no** `Dockerfile` path.
  - `GET https://hub.docker.com/v2/repositories/graphhopper/graphhopper/` → HTTP 404 `{"message":"object not found","errinfo":{}}`.
  - `GET https://ghcr.io/v2/graphhopper/graphhopper/tags/list` (with anonymous pull token) → HTTP 404 `{"errors":[{"code":"NAME_UNKNOWN","message":"repository name not known to registry"}]}`.
  Community image sizes observed via `https://hub.docker.com/v2/repositories/israelhikingmap/graphhopper/tags?page_size=5`:
  - `latest` (2026-06-18): amd64 `151347918` bytes (~144 MiB compressed), arm64 `149872343` bytes; `full_size` reported as `151347918`.
  - `11.0`: amd64 `144813965` bytes. `10.2`: `143832307`. `10.0`: `143831415`. `9.1`: `143304912`.
  These are Docker Hub's own reported sizes; no size is "stated" in GraphHopper's docs.
- OSM extract: yes, an `.osm.pbf` is the input. README installation, verbatim:
  > "wget https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.0/graphhopper-web-11.0.jar \
  >   https://raw.githubusercontent.com/graphhopper/graphhopper/11.x/config-example.yml \
  >   http://download.geofabrik.de/europe/germany/berlin-latest.osm.pbf
  > java -D"dw.graphhopper.datareader.file=berlin-latest.osm.pbf" -jar graphhopper*.jar server config-example.yml"
  Requires a JVM: "you [need a JVM](https://adoptium.net) (>= Java 25)". `pom.xml` sets `<maven.compiler.target>25</maven.compiler.target>`.
- Effort / memory guidance, verbatim (`docs/core/deploy.md`):
  > "A good estimation for the `-Xmx` value in the server command is "size of graph-cache minus the geometry file"."
  > "Parsing this planet file and creating the GraphHopper base graph requires ~60GB RAM and takes ~3h for the import. If you can accept much slower import times (3 days!) this can be reduced to 31GB RAM when you set `graph.dataaccess.default_type: MMAP` in the config file. As of May 2022 the graph has around 415M edges (150M for Europe, 86M for North America)."
  > "Running the CH preparation, required for best response times, needs ~120GB RAM and the additional CH preparation takes ~25 hours (for the car profile with turn cost) ... Without turn cost support, e.g. sufficient for bike, it takes much less (~5 hours)."
  > "It is also possible to add CH/LM preparations for existing profiles after the initial import. Adding or modifying profiles is not possible and you need to run a new import instead."
  `docs/core/technical.md`: "The import process is fast e.g. complete Germany takes roughly 10 minutes."
  `config-example.yml` ships bike **without** CH by default:
  > "     # By default foot and bike are without CH preparation as this requires less resources. Add them to "profiles_ch" if you need it."
  > "  profiles_ch:
  >     - profile: car"
  Elevation adds file-handle tuning requirements (`docs/core/deploy.md`: "sudo vi /etc/security/limits.conf" / "add: `* - nofile 100000`").
- Self-hosted endpoints: `/route` (GET+POST), `/isochrone`, `/spt`, `/info`, `/match`, `/nearest`, `/mvt`. Web UI bundled ("GraphHopper Maps"). Maps UI geocoding uses the hosted Directions API and needs a token (`deploy.md`: "To be able to use the autocomplete feature of the point inputs you get your API Token at graphhopper.com").

### Safety / traffic-stress score output? (yes/no + evidence)

**No.** There is no field, encoded value, path detail, or request/response parameter named `safety`, `traffic
stress`, `stress`, `quiet`, or similar, and no numeric safety score is returned.
Evidence (all zero-match greps, case-insensitive, over the saved primary sources):
- `graphhopper-openapi-bundle.yaml` — 0 hits for `safety`, `traffic stress`, `stress`, `quiet`.
- `graphhopper-openapi.md`, `graphhopper-custom-models.md`, `graphhopper-profiles.md`, `graphhopper-postroute.md`,
  `graphhopper-getroute.md`, `graphhopper-api-doc.md`, `graphhopper-deploy.md`, `graphhopper-elevation.md`,
  `graphhopper-README.md` — 0 hits for each of those terms.
- `graphhopper-pricing.html`, `graphhopper-config-example.yml` — 0 hits.
What GraphHopper *does* return that is safety-adjacent: (a) the `priority` factor used internally during routing,
(b) the individual encoded values listed above, from which a caller can build its own score, and (c) the profile's
route choice itself. Only (b) is directly observable, and only if the encoded value is enabled and exposed as a
path detail on that server. `bike_priority` specifically is documented as a custom-model/encoded value in the open
source engine, but is **not** in the hosted API's documented `details` list.
**NOT DOCUMENTED in https://docs.graphhopper.com/openapi.md**: any safety or traffic-stress score field.

### Sources

- https://www.graphhopper.com/pricing/
- https://www.graphhopper.com/faq/what-is-one-credit/
- https://docs.graphhopper.com/
- https://docs.graphhopper.com/llms.txt
- https://docs.graphhopper.com/openapi.md
- https://docs.graphhopper.com/openapi/routing/postroute.md
- https://docs.graphhopper.com/openapi/routing/getroute.md
- https://docs.graphhopper.com/_bundle/openapi.yaml
- https://graphhopper.com/api/1/route (HTTP 401 without `key`; probe saved)
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/README.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/LICENSE.txt
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/NOTICE.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/pom.xml
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/config-example.yml
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/profiles.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/custom-models.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/elevation.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/deploy.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/technical.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/web/api-doc.md
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike.json
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike_elevation.json
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike_tc.json
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike_avoid_turns.json
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/mtb.json
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/racingbike.json
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/cargo_bike.json
- https://api.github.com/repos/graphhopper/graphhopper/contents/core/src/main/resources/com/graphhopper/custom_models
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/parsers/BikeCommonPriorityParser.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/parsers/BikePriorityParser.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/PriorityCode.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/ev/DefaultImportRegistry.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/ev/BikeRoadAccess.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/ev/GetOffBike.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/parsers/OSMBikeNetworkTagParser.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/web-bundle/src/main/java/com/graphhopper/resources/RouteResource.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/web-bundle/src/main/java/com/graphhopper/gpx/GpxConversions.java
- https://raw.githubusercontent.com/graphhopper/graphhopper/master/web-bundle/src/main/java/com/graphhopper/http/TypeGPXFilter.java
- https://api.github.com/repos/graphhopper/graphhopper/git/trees/master?recursive=1
- https://hub.docker.com/v2/repositories/graphhopper/graphhopper/ (404)
- https://hub.docker.com/v2/repositories/israelhikingmap/graphhopper/tags?page_size=5
- https://hub.docker.com/r/israelhikingmap/graphhopper
- https://github.com/IsraelHikingMap/graphhopper-docker-image-push

---
## OpenRouteService (ORS)

### Bicycle profile & safety/traffic-stress modelling

The four cycling profiles are `cycling-regular`, `cycling-road`, `cycling-mountain`, `cycling-electric`.
Exact enum strings from the official API playground OpenAPI spec (`profile` path parameter), plus the rest of the
profile list for context:

```
"cycling-regular", "cycling-road", "cycling-mountain", "cycling-electric",
"foot-walking", "foot-hiking", "wheelchair", "public-transport"
```
(url: https://docs.openrouteservice.org/all/docs, path `/openrouteservice/v2/directions/{profile}/geojson`,
parameter `profile`; and url: https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/)

> Verbatim (self-host config page, possible values for `encoder_name`): "driving-car / driving-hgv / cycling-regular / cycling-mountain / cycling-road / cycling-electric / foot-walking / foot-hiking / wheelchair / public-transport (experimental)"

Are all four cycling profiles on the hosted API? **Yes per the official spec** — the spec that the public API
playground renders enumerates all four `cycling-*` values in the `{profile}` path parameter (enum above), and it is
served from the vendor's own docs host. I could not functionally confirm them with a live keyed request, because the
live API rejects unauthenticated requests before profile validation:

> Verbatim (live call, 2026-09-15): `GET https://api.openrouteservice.org/v2/directions/cycling-road/geojson` -> HTTP 401 `{     "error": "Authorization field missing" }`
> Verbatim (same for a deliberately invalid profile): `GET .../v2/directions/cycling-bogus/geojson` -> HTTP 401 `{     "error": "Authorization field missing" }`

Because auth is checked first, the live API cannot be used to enumerate valid profiles without a key.
The official spec's own server block lists `https://api.heigit.org` (plus `http://localhost:{port}{basePath}`);
`GET https://api.heigit.org/openrouteservice/v2/directions/cycling-road/geojson` returns the same 401
("Authorization field missing"), i.e. the new host is live behind the same auth.

Safety-relevant request parameters (exact names, allowed values, defaults):

`options.avoid_features` — string array; per-profile applicability is documented explicitly:

| Feature | Available for |
|---|---|
| `highways` | driving-* |
| `tollways` | driving-* |
| `ferries` | driving-*, cycling-*, foot-*, wheelchair |
| `fords` | driving-*, cycling-*, foot-* |
| `steps` | cycling-*, foot-*, wheelchair |

> Verbatim: "A string array of features to avoid. The available features are :" (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/routing-options)

The spec adds machine-readable item restrictions, e.g. `"cycling-*": ["ferries","steps","fords"]`
(url: https://docs.openrouteservice.org/all/docs). There is **no** "busy road", "traffic", "safety" or
"stress" value in `avoid_features`.

`preference` — enum `fastest`, `shortest`, `recommended`, `custom`; `default: "recommended"`.
> Verbatim: `"description": "Specifies the route preference", "enum": ["fastest","shortest","recommended","custom"], "default": "recommended"` (url: https://docs.openrouteservice.org/all/docs)

`options.profile_params.weightings` — the complete set in current docs/spec is `steepness_difficulty`, `green`,
`quiet`, `shadow`:
- `steepness_difficulty`: cycling-* only. Values 0=Novice, 1=Moderate, 2=Amateur, 3=Pro.
  > Verbatim: "Integer specifying the fitness level for **`cycling-*`** profiles. The preferred gradient increases with the value." (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/routing-options)
  > Verbatim (spec): "level: 0 = Novice, 1 = Moderate, 2 = Amateur, 3 = Pro. The prefered gradient increases with level." with `"x-validWhen": {"ref": "profile", "value": "cycling-*"}` (url: https://docs.openrouteservice.org/all/docs)
- `green`: **foot-\* only** in the current docs (cycling is *not* listed).
  > Verbatim: "`green`: Integer value specifying the Green factor for **`foot-*`** profiles." (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/routing-options)
- `quiet`: **foot-\* only**.
  > Verbatim: "`quiet`: Integer value specifying the Quiet factor for **`foot-*`** profiles." (same URL)
- `shadow`: foot-* only. Values "Multiplication factor range from 0 to 1" per the spec (url: https://docs.openrouteservice.org/all/docs).

  Docs inconsistency to be aware of: the docs page describes `green`/`quiet` as "Integer value" with values 0/1,
  the OpenAPI spec describes the same parameters as a number with "Multiplication factor range from 0 to 1"
  (example 0.4 / 0.8). Both are current official sources; they disagree.
- `allow_unsuitable`: **NOT DOCUMENTED** in the ORS docs or in the current OpenAPI spec. It appears in none of
  `routing-options` (9.7.1 and v8.2.0), `routing-options` (v7.1.1), `run-instance/configuration/engine/profiles/build`,
  or the default `ors-api/src/main/resources/application.yml` (checked for the four cycling profiles).
  No primary source found for it; the parameter I found in the weightings class is
  `PARAM_STEEPNESS_DIFFICULTY`, `PARAM_GREEN_INDEX`, `PARAM_QUIETNESS`, `PARAM_SHADOW_INDEX` only
  (url: https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/requests/routing/RequestProfileParamsWeightings.java).
- `trail_difficulty` as a *request parameter*: **NOT DOCUMENTED** as a weighting. It exists only as a
  response extra (`extra_info=traildifficulty`) — see below.

Custom models (`custom_model` + `preference=custom`) are the only documented way to hand-tune weights, and they are
**not available on the hosted API**:

> Verbatim: "This feature is still in experimental state and is currently not available on our public API for any profile" (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/custom-models)

> Verbatim: "The priority weightings of openrouteservice for foot and bike profiles (that represent preference of designated footpaths or designated bicycle paths over car roads and so on) can be combined "on top" of the custom model weighting." (same URL)

How the cycling profile judges a way (graph-build logic, self-host only): OSM tag acceptance/rejection tables for
`cycling-regular [cycling-electric, cycling-road & cycling-mountain]`, e.g. reject `highway = [motorway, motorway_link, bridleway, construction]` or `motorroad = yes`, conditional (`ford`) for `highway = ford OR ford = *`,
and per-profile `sac_scale` rules (url: https://giscience.github.io/openrouteservice/technical-details/tag-filtering).
Cycling travel speeds are tabulated per OSM `highway` value for `cycling-regular` (e.g. `cycleway` 18 km/h,
`residential` 18 km/h, `steps` 2 km/h, `path` 10 km/h) (url: https://giscience.github.io/openrouteservice/technical-details/travel-speeds/waytype-speeds/).

Bike-relevant `encoder_options` (self-host, defaults from `ors-api/src/main/resources/application.yml`, all four cycling profiles):
```
cycling-regular / cycling-mountain / cycling-road / cycling-electric:
  encoder_options: {consider_elevation: true, turn_costs: true, block_fords: false, enable_custom_models: false}
  ext_storages: {WayCategory:, WaySurfaceType:, HillIndex:, TrailDifficulty:}
```
(url: https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/resources/application.yml)
Documented meanings: `block_fords` "Do not route through fords" (applies to `*`), `consider_elevation` for `bike-*`
"The maximum possible speed is the bike-type specific maximum downhill speed..." (url: https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/build).

**No traffic-stress model is documented anywhere for the bike profiles.** See "Safety / traffic-stress score output?" below.

### Per-segment response attributes (can we score/explain safety?)

`extra_info` request array. Exact allowed values (OpenAPI spec enum, identical to the docs table):
```
["steepness","suitability","surface","waycategory","waytype","tollways","traildifficulty",
 "osmid","roadaccessrestrictions","countryinfo","green","noise","csv","shadow"]
```
Response key differs for one item: request `osmid` -> response `osmId`.
(url: https://docs.openrouteservice.org/all/docs and https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info)

Per-profile availability (official table, verbatim rows): cycling-regular / cycling-mountain / cycling-road each get
`steepness, suitability, surface, waycategory, waytype, traildifficulty` only. `tollways`, `osmid`,
`roadaccessrestrictions`, `countryinfo` are blank for cycling. `green`, `noise`, `shadow` are marked `x` **only for
foot-walking and foot-hiking**.
> Verbatim: "| cycling-regular  |     x     |      x      |    x    |      x      |    x    |          |        x        |       |                        |             |       |       |        |" (columns: steepness suitability surface waycategory waytype tollways traildifficulty osmid roadaccessrestrictions countryinfo green noise shadow) (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info)

`csv` and `shadow` are annotated: "No data available in the public openrouteservice hosted by HeiGIT" (same URL).

Response shape: `$.routes[*].extras` with per-item `{"values": [[fromWayPointIndex, toWayPointIndex, value], ...], "summary": [{"value":n,"distance":m,"amount":pct}]}`.
> Verbatim: "A list of integer arrays with length 3 representing a contiguous section of the calculated route with the same value of the requested information type." (same URL)

Value encodings (all official):
- `suitability`: 1–10, "1 (unsuitable) - 10 (very suitable)" (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info)
- `waytype`: OSM `highway` value IDs (url: .../extra-info/waytype)
- `surface`: surface IDs (url: .../extra-info/surface)
- `waycategory` bit field: 1 Highway, 2 Tollways, 4 Steps, 8 Ferry, 16 Ford (url: .../extra-info/waycategory)
- `steepness`: -5..5 categories, -5 = ">=16% decline", 5 = ">=16% incline" (url: .../extra-info/steepness)
- `traildifficulty`: cycling-* uses `mtb:scale` 0..6, foot-* uses `sac_scale` (url: .../extra-info/trail-difficulty)
- `roadaccessrestrictions` bit field: 1 No, 2 Customers, 4 Destination, 8 Delivery, 16 Private, 32 Permissive, 64 Permit (url: .../extra-info/road-access-restrictions)
- `green` / `noise` / `shadow`: 0–10 scales, foot-* only (url: .../extra-info)

Other request params that control segments/attributes:
- `attributes` (route-level, per segment in response): enum `avgspeed`, `detourfactor`, `percentage`; docs: "This value is in _km/h_ and equals the average speed for this way segment after grading and applying factors." (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/route-attributes)
- `geometry_simplify` (default `false`): "Simplify geometry cannot be applied to routes with more than **one segment** and when `extra_info` is required."
- `elevation` (boolean; default false), `instructions` (default true), `units` (`m`|`km`|`mi`, default `m`), `radiuses`, `bearings` (cycling-* only per spec `x-validWhen`), `continue_straight`, `alternative_routes`, `suppress_warnings`, `skip_segments`, `options.round_trip`.
  (urls: https://docs.openrouteservice.org/all/docs ; https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/requests-and-return-types)
- Response `format`: `json` | `geojson` | `gpx` (enum `RouteResponseType` GPX/JSON/GEOJSON, `ors-api/.../APIEnums.java`).
- `extra_info` items `csv`/`shadow` are the only "CSV" concept I found; there is no `details`/`extras`-style request
  parameter beyond `extra_info` + `attributes`. (Search of the merged spec + docs.)

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- Directions endpoint (documented): `https://api.openrouteservice.org/v2/directions/{profile}/geojson` (also `/json`,
  `/gpx`, and the bare `{profile}` path). Official spec server block: `https://api.heigit.org` (paths prefixed
  `/openrouteservice/...`) and `http://localhost:{port}{basePath}` (default port 8082, basePath `/ors`).
  (url: https://docs.openrouteservice.org/all/docs)
- Auth: API key required for every call.
  > Verbatim (live call 2026-09-15, no key): HTTP 401 `{     "error": "Authorization field missing" }`
  > Verbatim: "Since we migrated our user management to [account.heigit.org](https://account.heigit.org) in the beginning of 2025, newly issued keys look like JSON Web Tokens (JWT)." (url: https://giscience.github.io/openrouteservice/frequently-asked-questions)
  > Verbatim: "every HeiGIT API key belongs to one person. Thus, an API key must not be used client-side in an application" (same URL)
- Free tier / pricing: the marketing page states a free allowance; the plans page itself (`https://openrouteservice.org/plans/`)
  is an Angular app whose content is rendered from the HeiGIT account app bundle. Plan data in that bundle:
  - `DirectionsForPublic_v2 #smart_mobility #public #production` -> `limit:{rate:40, per:60, quota_max:2e3, quota_renewal_rate:86400}`
  - `DirectionsForPublic_v2 #smart_mobility #public #production` (Collaborative plan) -> `rate:40,per:60,quota_max:2e3` (public key tier); non-public `ORS Directions` on the Collaborative plan -> `rate:60,per:60,quota_max:1e4,quota_renewal_rate:86400`
  - plan names/descriptions in the same bundle: `["Standard", ..., "Our standard plan.\nFree to use for everyone"]` and `["Collaborative", ..., "If your application is in a humanitarian, academic, governmental, or not-for-profit organisation, you may be eligible for the ..."]`
  (url: https://account.heigit.org/chunk-3FIDLIS3.js — vendor's own plans UI bundle)
  - Docs (authoritative, human-readable): "the `directions` endpoint and its default limit of 2000 requests per day";
    "The minutely limit is enforced as a sliding window, meaning that any consecutive period of 60 seconds may only contain 40 directions requests."; daily overrun -> "403 - Forbidden", minutely overrun -> "429 - Too many requests"; remaining quota via `x-ratelimit-remaining` / `x-ratelimit-reset`; daily quota resets 24h from the first request, not at midnight.
    (url: https://giscience.github.io/openrouteservice/frequently-asked-questions)
  - Marketing claim (do not confuse with the directions quota): "Start firing your requests over 7000 times a day. For free." (url: https://openrouteservice.org/services/)
- Paid options: `https://openrouteservice.org/plans/` shows "Collaborative" (eligibility-gated) and "On-Premise";
  No public price list was retrievable from a primary source. The plans page is client-rendered and no pricing figures
  are published in the API/account endpoints I could read.
  UNCERTAIN: any EUR/USD price for ORS hosted plans.

### Limits (hosted API)

Source: https://openrouteservice.org/restrictions/ ("API Restrictions").
- Directions: maximum distance 6.000 km for foot / cycling / driving profiles; 300 km for wheelchair.
  Table header "Maximum distance with profile restrictions" repeats 6,000 km for cycling.
- Directions options: distance with avoid areas 150 km; distance (alternative & round trip) 100 km; alternative route
  count 3; avoid polygon area 200 km²; avoid polygon extent (height or width) 20 km; **route waypoints 50**.
- Isochrones: locations 5; intervals 10; range distance 120 km; range time cycling 5 h.
- Matrix: 3.500 locations (e.g. 50 x 50) per request; 25 with dynamic arguments.
- Snap: 5.000 locations per request. Export: bbox 10 km² (Standard) / 50 km² (Collaborative). Elevation: 2000 vertices.
- Self-host defaults are different (and smaller) than the hosted caps: `maximum_distance 100000`,
  `maximum_way_points 50`, `maximum_snapping_radius 400`
  (url: https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/service).
- The Export endpoint is not in the live API: "Export endpoint _(not available in our live API)_" (url: https://giscience.github.io/openrouteservice/getting-started).

### Self-hosting (Docker, OSM extract, effort)

- Image: `openrouteservice/openrouteservice` — the official compose file pinned `image: openrouteservice/openrouteservice:v9.10.0`
  (url: https://github.com/GIScience/openrouteservice/releases/latest/download/docker-compose.yml).
- Size: **NOT DOCUMENTED** in the ORS docs (the docs never state an image size). Vendor registry figures
  (Docker Hub API, `full_size` = compressed size): `latest` / `v9.10.0` = 259,753,923 bytes (~248 MiB),
  `latest-slim` / `v9.10.0-slim` = 244,437,744 bytes (~233 MiB) (url: https://hub.docker.com/v2/repositories/openrouteservice/openrouteservice/tags/).
- OSM extract: required, user-supplied: "An OSM extract, e.g. from [Geofabrik](http://download.geofabrik.de) or for small areas directly export from [osm.org]";
  set via `ors.engine.profile_default.build.source_file`; default sample is a Heidelberg test PBF.
  Graphs are built inside the container (`REBUILD_GRAPHS: True` / `BUILD_GRAPHS=True`)
  (urls: https://giscience.github.io/openrouteservice/run-instance/running-with-docker, https://giscience.github.io/openrouteservice/run-instance/data).
- RAM: "The planet file is around 50GB and for bike and pedestrian profiles, this needs around 100-105GB RAM. Germany is around 3GB and can normally be built on a reasonable system with around 8GB RAM, and Baden-Württemberg is 450MB which can be built on a machine with around 2-4GB RAM." (url: https://giscience.github.io/openrouteservice/run-instance/system-requirements)
- Rule of thumb from the official compose file: "Rule of Thumb: <PBF-size> * <profiles> * 2" (defaults `XMS: 1g`, `XMX: 2g`).
- Build time: "The build process in itself is [rather resource-intensive]. It takes roughly two days for any one of the nine profiles." (url: https://giscience.github.io/openrouteservice/frequently-asked-questions)
- Pre-built graphs: a client exists but is not generally available: "Since v9 openrouteservice includes a client for graph repositories. This makes it possible to download and use graphs built elsewhere instead of building them locally." and "The graph management functionality is in beta testing and not yet available for the broader public." (url: https://giscience.github.io/openrouteservice/technical-details/graph-repo-client)
- Effort summary (evidence-based): run `docker compose up -d` for the bundled Heidelberg sample; for a real region you supply an
  OSM `.pbf`, size the JVM per the PBF-size rule or the RAM table, and wait for a one-off graph build (hours to days at country/planet scale).

### Safety / traffic-stress score output? 

**No.** 
- Evidence of what exists instead: the only per-segment "how good is this way" number for cycling is `suitability`,
  "1 (unsuitable) - 10 (very suitable)", which the docs define as:
  > Verbatim: "How suitable the way is based on characteristics of the route and the profile" (url: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info)
  Source-code evidence of what this number is: it is derived from the bike/other encoder's *priority* value
  (`FlagEncoderKeys.PRIORITY_KEY`), normalised to 3..10, i.e. a routing *preference* (designated cycleway vs car road),
  not a measured traffic exposure or collision risk:
  > Verbatim: `priorityIndex = (int) Math.round(3 + priority * PriorityCode.BEST.getValue()); // normalize values between 3 and 10` (url: https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/pathprocessors/ExtraInfoProcessor.java)
  and the bike encoder sets that priority from OSM `class:bicycle` and highway class
  (`convertClassValueToPriority`, `handlePriority`; url: https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/graphhopper/extensions/flagencoders/bike/CommonBikeFlagEncoder.java).
- NOT DOCUMENTED anywhere in the ORS docs: the words "safety", "traffic stress" or "stress score" as a scoring concept.
  `noise` (0 quiet – 10 noisy, "influenced by things like proximity to highways") is the closest traffic-exposure proxy
  in ORS, and it is **foot-\* only**, with no data on the public API instance for the related `csv`/`shadow` extras.
  The docs only link a research blog post about "reducing stress by avoiding noise with quiet routing"
  (url: https://giscience.github.io/openrouteservice/run-instance/data), and quiet routing is `foot-*`-only in the API.
- Practical consequence for a bike planner: on the hosted API you can *avoid* tag classes (`ferries`, `steps`, `fords`),
  pick `preference`, request `suitability`/`surface`/`waycategory`/`traildifficulty`/`steepness`, and post-process.
  There is no documented traffic-stress input or output. Building your own stress model on `extra_info` output is
  possible; getting ORS to *route* on your own stress weights requires `custom_model`, which is self-host-only.

### Sources (ORS)

- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/routing-options
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/waycategory
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/steepness
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/trail-difficulty
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/road-access-restrictions
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/route-attributes
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/requests-and-return-types
- https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/custom-models
- https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/
- https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/build
- https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/service
- https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/repo
- https://giscience.github.io/openrouteservice/run-instance/running-with-docker
- https://giscience.github.io/openrouteservice/run-instance/system-requirements
- https://giscience.github.io/openrouteservice/run-instance/data
- https://giscience.github.io/openrouteservice/technical-details/graph-repo-client/
- https://giscience.github.io/openrouteservice/technical-details/tag-filtering
- https://giscience.github.io/openrouteservice/technical-details/travel-speeds/waytype-speeds/
- https://giscience.github.io/openrouteservice/frequently-asked-questions
- https://giscience.github.io/openrouteservice/getting-started
- https://docs.openrouteservice.org/all/docs  (official merged OpenAPI, "ORS Core-Version 9.10.0")
- https://openrouteservice.org/restrictions/
- https://openrouteservice.org/services/
- https://openrouteservice.org/plans/  (client-rendered; content source: https://account.heigit.org/chunk-3FIDLIS3.js)
- https://github.com/GIScience/openrouteservice/releases/latest/download/docker-compose.yml
- https://hub.docker.com/v2/repositories/openrouteservice/openrouteservice/tags/
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/resources/application.yml
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/APIEnums.java
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/requests/routing/RouteRequest.java
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/requests/routing/RequestProfileParamsWeightings.java
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/RouteExtraInfoFlag.java
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/pathprocessors/ExtraInfoProcessor.java
- https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/graphhopper/extensions/flagencoders/bike/CommonBikeFlagEncoder.java

---

## CycleStreets

### Bicycle profile & safety/traffic-stress modelling

Route types (`plan` parameter) and their documented definitions:
> Verbatim: "`balanced`: We recommend this to be the default route type in your interface - it aims to give practical routes that balance speed and pleasantness, suitable for most riders." / "`fastest`: This route type will tend to favour busier roads that suit more confident riders." / "`quietest`: The route type will produce more pleasant, but often less direct, routes." / "`shortest`: In general we do not recommend including this in your interface unless you have a need for it..." (url: https://www.cyclestreets.net/api/v1/journey/)

The V1 docs also give the internal objective for "quietest" explicitly:
> Verbatim: "The scoring factor for Quietest routes is an estimate of how busy a section is for cycling. The quietest routes are broadly inversely proportional to the road classification. Trunk roads and primary roads are considered very hostile, whereas smaller roads and dedicated cycleways are quiet. But other factors are included, such as the maximum speed of other traffic, the smoothness of the riding surface and hills." (url: https://www.cyclestreets.net/help/journey/routing/)

This is the closest thing to a traffic-safety/comfort model in either engine: a *subjective* per-section quietness
percentage, converted to a "busyness" cost:
> Verbatim: "Quietness is rated as a percentage score. The quietest routes score 100%. Examples of these are cycle tracks and park paths, these are off-road routes and for cycling it doesn't get any quieter than that. Slightly less quiet are "Quiet Streets", at 75%, and shared-use facilities at 80%. (Shared use are often too narrow, and there are pedestrians and other cyclists to avoid.) Busy roads score 50% or less."
> Verbatim: "busyness = length / quietness"
> Verbatim: "overall quietness =  total length / total busyness"
> Verbatim (caveat): "These figures are subjective and have been changing in recent months (June 2009) as we receive feedback from users. They also depend on time of day, which the current version of Journey Planner does not (yet) take into account."
(url: https://www.cyclestreets.net/help/journey/howitworks/)

Documented inputs to the routing score (no explicit traffic-stress term used):
> Verbatim: "Road/street type / Lengths of streets/paths / Hills and inclines / Cycle infrastructure / Path widths/quality / Barriers, obstructions / Land ownership / Surface type and quality / Route legibility / Delays caused by traffic signals / Crossings and junctions / Barriers / Kerbs / National signed routes / Local Authority signage / Lighting (partial support) / Turn delays (initial implementation)" (url: https://www.cyclestreets.net/help/journey/routing/ ; identical list on https://www.cyclestreets.net/api/v1/journey/)

Tunable parameters on the routing call (V1 `plan` endpoint):
- `plan`: `balanced` | `fastest` | `quietest` | `shortest` (required).
- `speed`: `16|20|24` km/h, "The maximum speed at which you will ride, in km/h, defaults to 20." (required-adjacent optional).
- `segments`: `0|1`, default 1 — controls detailed segment output and full archival of the route.
- `reporterrors`: `0|1`, default 0 — "Strongly recommend to be enabled."
- `redirect`, `useDom` (legacy XML/GML), `itinerary` (re-plan an archived journey in another plan type).
- E-bike is documented only as a routing *type* on the help pages ("E-bike ... similar to fastest routes, but assumes a higher default cycling speed. Hills are not included in the scoring."), not as an API parameter value.
- There is **no** documented "avoid busy roads", "traffic level", "safety weight" or hill/steepness parameter in the V1
  journey API: **NOT DOCUMENTED** in https://www.cyclestreets.net/api/v1/journey/.

Coverage: UK + Ireland built daily, other countries "on a less frequent basis, roughly every 2-3 weeks"
(url: https://www.cyclestreets.net/help/journey/osmconversion/). Elevation from SRTM (~90 m), "a cost of six seconds per
metre climbed, but a saving of 1.8 seconds per metre descended" (cycling Naismith) (url: https://www.cyclestreets.net/help/journey/routing/).

### Per-segment response attributes (can we score/explain safety?)

V1 call: `https://www.cyclestreets.net/api/journey.json?...&segments=1` (JSON or XML; JSON recommended).
Route (`marker` level) fields documented: `start`, `finish`, `start_longitude/latitude`, `finish_longitude/latitude`,
`crow_fly_distance`, `event`, `whence`, `speed`, `itinerary`, `plan`, `note`, `length`, `time`, `busynance`,
`quietness`, `walk`, `west/south/east/north`, `signalledJunctions`, `signalledCrossings`, `coordinates`, `elevations`,
`distances`, `grammesCO2saved`, `calories`.
> Verbatim: "`busynance` | int | A measure of the cost of busyness of the route, in metres."
> Verbatim: "`quietness` | int | A measure of the cost of quietness of the route, a percentage value from 0 to 100."
(url: https://www.cyclestreets.net/api/v1/journey/)

Per-segment fields documented in "Return values, in detail" -> "Segment": `turn`, `turnAngle (proposed feature)`,
`startBearing (currently included but may change name)`, `distance`, `time`, `signalledJunctions`,
`signalledCrossings`, `points`, `distances`, `elevations`, plus the documented `turn` value list
(`straight on`, `bear right`, `turn right`, `sharp right`, `double-back`, `sharp left`, `turn left`, `bear left`,
`unknown`, `join roundabout`, `first exit` ... `seventh or more exit`). (same URL)

Segments in the official sample JSON also carry (present in the sample, **not** in the "in detail" field list):
`name`, `legNumber`, `busynance`, `walk`, `color`, `provisionName`.
Example rows from the official sample output (verbatim attribute set):
```
{"name":"Senate House Hill, NCN 11","legNumber":"1","distance":"23","time":"11","busynance":"57","walk":"0","signalledJunctions":"0","signalledCrossings":"0","turn":"","startBearing":"176","color":"#7777cc","provisionName":"Service road","type":"segment"}
```
`provisionName` values that appear in the official sample output of that page:
`Cycle path`, `Service road`, `Minor road`, `Footpath`, `Major road access`, `Major road`, `Pedestrianized area`,
`Residential street`, `Unclassified road`.

- One official page that would plausibly hold that mapping, `/views/osmHighwayTranslation/` ("Basic interpretation of streets from the OSM highway tag", linked from https://www.cyclestreets.net/help/journey/osmconversion/), returned an empty body / connection reset when fetched directly — treat as not publicly readable.
**The full enumerated provision-code list is NOT DOCUMENTED** on the pages I checked. Tested and 404:
`https://www.cyclestreets.net/api/v1/journeyadvanced/`, `/api/v2/journeyadvanced/`, `/api/journeyadvanced.json`,
`/api/v1/journeyadvanced.json`, `/journey/waytypes/`, `/help/journey/waytypes/`, `/journey/provisions/`.
There is no `journeyadvanced` endpoint documented anywhere in the current API index (https://www.cyclestreets.net/api/).
Provision type names do appear in prose on other official pages, e.g.:
> Verbatim: "The busy paths are shown using a blue line, and they correspond to the provision type "Busy Road". The red line is the provision type "Cycle Lane", and the black is "Quiet Street"." (url: https://www.cyclestreets.net/help/journey/junctions/)
UNCERTAIN: the complete official list of `provisionName` codes and their numeric/colour mapping — only the sample values above are quotable; no published enumeration was found.

Also relevant for explaining a route: separate CycleStreets APIs expose collisions (`/api/v2/collisions.locations/`,
"locations and details of every reported road traffic collision in the UK from 2005") and traffic counts
(`/api/v2/trafficcounts.locations/`), but these are *not* joined into the journey response — they are separate datasets.
(url: https://www.cyclestreets.net/api/)

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- Host: "All calls are made to `https://api.cyclestreets.net`." (url: https://www.cyclestreets.net/api/v2/overview/)
- Key required, three ways to pass it: GET `?key=`, HTTP basic username, or header `X-API-KEY`.
  > Verbatim: "As a HTTP header: `X-API-KEY:` registeredapikey along with the call itself" (same URL)
- Verified live (no key, 2026-09-15):
  > `GET https://api.cyclestreets.net/v2/journey.plan?...` -> HTTP 401 `{"error": "No valid API key was supplied. Please go to https://www.cyclestreets.net/api/apply/ to apply for an API key."}`
  > `GET https://www.cyclestreets.net/api/journey.json?...` (V1) -> HTTP 401 `{"error":"No valid API key was supplied. Please go to https:\/\/www.cyclestreets.net\/api\/apply\/ to apply for an API key."}`
  (Note: an invalid v2 path also returns 401, i.e. auth is checked before routing.)
- V1 vs V2 for journey planning:
  > Verbatim: "Information on the new V2 API is only available to Administrators at present. The current V1 equivalent API call is available and remains fully-supported. This remains easy to use, with JSON output." (url: https://www.cyclestreets.net/api/v2/journey.plan/ — identical text on .../journey.retrieve/)
  The API index sidebar lists the V1 docs entry as "API V1 documentation (deprecated)" pointing at
  `https://www.cyclestreets.net/api/v1/`, and that URL currently serves the **v2** index content
  ("The CycleStreets API (v2) is a modern JSON interface...") while `https://www.cyclestreets.net/api/v1/journey/`
  still serves the V1 journey documentation (urls: https://www.cyclestreets.net/api/ , https://www.cyclestreets.net/api/v1/).
  Observed inconsistency, reported as-is: the V1 navigation entry is labelled deprecated while the V1 journey call is
  described as "fully-supported".
- Deprecation promise:
  > Verbatim: "It is our intention to continue to support API parameters and output formats from the time they are published onwards. If, in exceptional cases, we need to withdraw particular aspects of the published API that we know are in use, we will give developers a minimum of 3 months' notice." (url: https://www.cyclestreets.net/api/policy/)
- Free tier — measured in **distance routed, not request count**:
  > Verbatim: "We offer the option to pay for an assured quality level beyond a certain amount of route-planning. At present this threshold is 10,000km of route planning per day for free apps, and 5,000km per day for paid apps."
  > Verbatim: "These figures are measured as as-the-crow-flies distance route planning per day, calculated on a monthly basis."
  > Verbatim: "Large organisations, including consultancy companies, funded research organisations, local authorities, and governmental bodies, are expected to obtain a paid SLA key, following any initial evaluation."
  (url: https://www.cyclestreets.net/api/policy/)
  Appendix examples (same page): 10,000 km/day ≈ 1,667 cross-city routes (6 km) or 200 long-distance routes (50 km).
- Requests-per-minute/day limit: **NOT DOCUMENTED** on https://www.cyclestreets.net/api/policy/ (no req/min or
  req/day figure is stated; the quota is the km/day distance budget above). Usage is monitored in a per-key control
  panel ("API users may log in at any time to their control panel where their usage metrics are displayed.").
- Pricing for SLA: "SLA license keys require payment, based on either a rate per 100,000 routes or a fixed amount to be
  agreed by negotiation dependent on expected usage." — no public price list.
  Not published at https://www.cyclestreets.net/api/policy/.
  UNCERTAIN: any published GBP/EUR price for a CycleStreets SLA key.
- Key application requires a CycleStreets account (https://www.cyclestreets.net/api/apply/ redirects to sign-in).
- Attribution obligations: "© OpenStreetMap contributors" plus "Your application must mention in the credits (or
  elsewhere in the body of the application) that routing is provided by CycleStreets and include a link to
  www.cyclestreets.net." Also: "Use of our APIs, or crawling of our website in any form, for AI training purposes is
  expressly forbidden without permission, and requires an SLA license key." (url: https://www.cyclestreets.net/api/policy/)
- Satellite-navigation style apps with automatic re-planning "normally require use of an SLA license key" (same URL).

### Limits

- Maximum journey length: "Journey lengths must be at least 4 metres, and not more than 300 km." (url: https://www.cyclestreets.net/api/v1/journey/)
  The help FAQ repeats it: "The current limit on journey length is 186 miles, 300km." (url: https://www.cyclestreets.net/help/journey/faq/).
  The Status API documents the same cap as data: `"maxjourneylength": {"distance": 300, "units": "km"}` (url: https://www.cyclestreets.net/api/v2/status/).
- Maximum waypoints: "There can be at most 30 waypoints." (url: https://www.cyclestreets.net/api/v1/journey/)
- Other caps: "Note that there is an overall limit of around 2000 characters for a URL."; waypoints closer than 4 m
  "are treated as duplicates and ignored." (same URL)
- Live status endpoint exists (`https://api.cyclestreets.net/v2/status`, key required) and returns
  `{"sitestatus":{"journeyplanner":"live","photomap":"live"},"maxjourneylength":{"distance":300,"units":"km"}}`
  (documented sample, url: https://www.cyclestreets.net/api/v2/status/).
- Uptime target: "We aim to meet at least 99.9% uptime." (url: https://www.cyclestreets.net/api/policy/)

### Self-hosting (Docker image, OSM extract, effort)

**Not possible for third parties from public sources.**
- The engine is not open source. Official statement:
  > Verbatim: "We fully intend to open source CycleStreets in due course so that people in other countries might be able to set up their own versions, and so that the complex routing engine can be improved by a community of programmers. We plan to do this once key security audits have taken place of the code and once the engine has matured and stabilised (the engine code and the database schema are subject to ongoing revision at present)." (url: https://www.cyclestreets.net/help/overview/)
  > Verbatim: "This system is built on top of a huge pile of mostly open source software, and it is our intention to open source following a detailed code review." (url: https://www.cyclestreets.net/help/history/)
- The official deployment repo says the needed code is not published:
  > Verbatim: "**Note this is work-in-progress and the CycleStreets repo which is needed is not yet publicly available.**" (url: https://raw.githubusercontent.com/cyclestreets/cyclestreets-setup/master/README.md)
  (repo: https://github.com/cyclestreets/cyclestreets-setup — "Deployment scripts for installing CycleStreets website and related components", Ubuntu Server 22.04 LTS)
- No Docker image is published by CycleStreets. **NOT DOCUMENTED**: any CycleStreets routing-engine Docker image or
  image size at https://www.cyclestreets.net/api/ or https://github.com/cyclestreets.
- The public GitHub org (35 repos, checked 2026-09-15) contains clients and data tools only — `android`, `ios`,
  `mobileweb2022`, `routing-ui`, `cyclestreets-setup`, `cyclestreets-r`, `bikedata`, `cyclescape`, etc. No routing
  engine repository. (url: https://api.github.com/orgs/cyclestreets/repos)
- Server-side stack, for reference: "developed on both MS Windows and GNU/Linux platforms (Debian, Ubuntu) using GNU
  Emacs, and a GLAMP setup - Apache, MySQL and PHP." (url: https://www.cyclestreets.net/help/history/)
- OSM extract required in any self-host scenario: the engine is built from OSM ("The routing data for CycleStreets is
  based on the maps provided by the OpenStreetMap (OSM) project"; UK/Ireland rebuilt daily). No supported,
  self-service build path is documented.
- Practical conclusion for a planner project: CycleStreets must be consumed as a hosted API; self-hosting is not
  documented as available.

### Safety / traffic-stress score output? 

**Partially: a comfort/quietness score, not a safety or traffic-stress score.**
- Yes, there is a per-route score: route-level `quietness` (0–100) and `busynance` (metres), both documented.
  > Verbatim: "`quietness` | int | A measure of the cost of quietness of the route, a percentage value from 0 to 100." (url: https://www.cyclestreets.net/api/v1/journey/)
- The scoring basis is documented as busyness of traffic on a section: "The scoring factor for Quietest routes is an
  estimate of how busy a section is for cycling... Trunk roads and primary roads are considered very hostile, whereas
  smaller roads and dedicated cycleways are quiet. But other factors are included, such as the maximum speed of other
  traffic..." (url: https://www.cyclestreets.net/help/journey/routing/)
- Per-segment: `busynance` and `provisionName` are present in segments in the official sample, but the V1 "Return values,
  in detail -> Segment" field list does not document them (documentation gap). Earlier "journeyadvanced" endpoints that
  are cited for per-segment provision data are **NOT DOCUMENTED** / 404 (see above).
- The word "safety" appears in the official docs only as a caveat, not as a score:
  > Verbatim: "(Yet another dimension is the issue of personal safety and some people will not want to be routed in some directions at certain times of day or night.)" (url: https://www.cyclestreets.net/help/journey/howitworks/)
- Therefore: **No explicit safety or Level-of-Traffic-Stress score is output.** The quietness/busynance model is a
  subjective traffic-comfort weighting used for the "quietest" route type, and it is the most transferable idea
  (length/quietness cost) for a bike planner.

### Sources (CycleStreets)

- https://www.cyclestreets.net/api/
- https://www.cyclestreets.net/api/v1/  (serves the v2 index; nav label "API V1 documentation (deprecated)")
- https://www.cyclestreets.net/api/v1/journey/
- https://www.cyclestreets.net/api/v2/overview/
- https://www.cyclestreets.net/api/v2/journey.plan/  (admin-only notice)
- https://www.cyclestreets.net/api/v2/journey.retrieve/  (admin-only notice)
- https://www.cyclestreets.net/api/v2/status/
- https://www.cyclestreets.net/api/policy/
- https://www.cyclestreets.net/api/apply/  (requires sign-in)
- https://www.cyclestreets.net/help/journey/routing/
- https://www.cyclestreets.net/help/journey/howitworks/
- https://www.cyclestreets.net/help/journey/osmconversion/
- https://www.cyclestreets.net/help/journey/junctions/
- https://www.cyclestreets.net/help/journey/faq/
- https://www.cyclestreets.net/help/overview/
- https://www.cyclestreets.net/help/history/
- https://raw.githubusercontent.com/cyclestreets/cyclestreets-setup/master/README.md
- https://api.github.com/orgs/cyclestreets/repos
- https://api.cyclestreets.net/v2/status  (live 401 without key)

---

### Cross-engine summary — ORS vs CycleStreets (facts only)

| Question | ORS | CycleStreets |
|---|---|---|
| Traffic-stress / safety score output | No. `suitability` 1–10 is documented as "How suitable the way is based on characteristics of the route and the profile" and is computed from encoder priority | No. Route `quietness` 0–100 + `busynance` (m) exist; documented as an estimate of how busy a section is, "subjective" |
| Cycling traffic-avoidance control on hosted API | `avoid_features` (cycling: `ferries`, `steps`, `fords`), `preference`, `steepness_difficulty`. Green/quiet weightings are foot-*-only; `custom_model` unavailable on the public API | `plan=quietest` / `balanced` only; no numeric traffic parameter |
| Per-segment explainability | Yes, rich: `extra_info` (cycling gets steepness, suitability, surface, waycategory, waytype, traildifficulty) + `attributes` | Yes but thinner: segments with `provisionName`, `busynance`, `walk`, `signalledJunctions/Crossings`, elevations; full provision list not published |
| Hosted auth | API key (JWT-style since 2025), must be server-side | API key via `key=`, basic user, or `X-API-KEY` |
| Free tier | 2,000 directions/day, 40/minute (Standard plan) | 10,000 km routed/day (free apps), 5,000 km/day (paid apps) |
| Max waypoints | 50 | 30 |
| Max distance | 6,000 km (cycling, hosted cap) | 300 km |
| Self-hosting | Yes: `openrouteservice/openrouteservice` Docker image, own OSM PBF, graph build (~2 days/profile for planet; planet bike ~100–105 GB RAM) | No: engine not open source, "the CycleStreets repo which is needed is not yet publicly available" |

---
## BRouter

### Bicycle profile & safety/traffic-stress modelling   (exact parameter names + defaults + ranges)

#### Shipped profile list (authoritative: the live server directory listing + repo tree)

`https://brouter.de/brouter/profiles2/` (directory listing, fetched 2026-09-15) contains 42 `.brf` files.
Verbatim listing lines (name + size) for the profiles in scope:

```
<a href="trekking.brf">trekking.brf</a>                                       22-Apr-2026 10:04               17852
<a href="trekking-ignore-cr.brf">trekking-ignore-cr.brf</a>                             22-Apr-2026 10:04               17842
<a href="trekking-noferries.brf">trekking-noferries.brf</a>                             22-Apr-2026 10:04               17838
<a href="trekking-nosteps.brf">trekking-nosteps.brf</a>                               22-Apr-2026 10:04               17836
<a href="trekking-steep.brf">trekking-steep.brf</a>                                 22-Apr-2026 10:04               17843
<a href="fastbike.brf">fastbike.brf</a>                                       22-Apr-2026 10:04               15984
<a href="fastbike-lowtraffic.brf">fastbike-lowtraffic.brf</a>                            22-Apr-2026 10:04               15975
<a href="fastbike-asia-pacific.brf">fastbike-asia-pacific.brf</a>                          22-Apr-2026 10:04               15976
<a href="fastbike-verylowtraffic.brf">fastbike-verylowtraffic.brf</a>                        30-Jul-2026 09:57               27190
<a href="racebike-verylowtraffic.brf">racebike-verylowtraffic.brf</a>                        05-Sep-2026 11:40               27484
<a href="safety.brf">safety.brf</a>                                         22-Apr-2026 10:04               17836
<a href="shortest.brf">shortest.brf</a>                                       22-Apr-2026 10:04                5528
<a href="softaccess.brf">softaccess.brf</a>                                     22-Apr-2026 10:04                 654
<a href="hiking-beta.brf">hiking-beta.brf</a>                                    24-Dec-2021 12:45               14280
<a href="car-eco.brf">car-eco.brf</a>                                        22-Apr-2026 10:04               11875
<a href="moped.brf">moped.brf</a>                                          22-Apr-2026 10:04                7015
<a href="vm-forum-liegerad-schnell.brf">vm-forum-liegerad-schnell.brf</a>                      22-Apr-2026 10:04               20649
<a href="vm-forum-velomobil-schnell.brf">vm-forum-velomobil-schnell.brf</a>                      22-Apr-2026 10:04               20549
```

Status of every file named in the task:

| file | exists? | where verified |
|---|---|---|
| `trekking.brf` | YES | live listing, HTTP 200 |
| `fastbike.brf` | YES | live listing, HTTP 200 |
| `fastbike-lowtraffic.brf` | YES | live listing, HTTP 200 |
| `fastbike-asia-pacific.brf` | YES | live listing, HTTP 200 |
| `car-eco.brf` | YES | live listing, HTTP 200 |
| `moped.brf` | YES | live listing, HTTP 200 |
| `safety.brf` | YES | live listing, HTTP 200 |
| `shortest.brf` | YES | live listing, HTTP 200 |
| `trekking-ignore-cr.brf` | YES | live listing, HTTP 200 |
| `hiking-beta.brf` | YES | live listing, HTTP 200 (file dated 24-Dec-2021) |
| `softaccess.brf` | YES | live listing, HTTP 200 |
| **`vmforge.brf`** | **NO** | `https://brouter.de/brouter/profiles2/vmforge.brf` → **404**; also absent from the repo tree listing of `master` |
| **`routino.brf`** | **NO** | `https://brouter.de/brouter/profiles2/routino.brf` → **404**; also absent from the repo tree listing of `master` |

`UNCERTAIN: whether vmforge.brf / routino.brf ever existed under these exact names in an older BRouter release — no primary source for that could be retrieved.`
Related-but-different files that DO exist: `vm-forum-liegerad-schnell.brf`, `vm-forum-velomobil-schnell.brf`
(the old `online.html` profile `<select>` on the vendor site is stale — it lists `car-test`, `trekking-steep`,
`trekking-noferries`, `trekking-nosteps` and omits everything added after ~2014).

The repo also carries a second, *non-server* profile copy at `misc/profiles2/` used for the Docker image and
for map creation. Its `readme.txt` (https://brouter.de/brouter/profiles2/readme.txt) says verbatim:

```
NOTE: 

Please copy a profile before you edit it. 
The delivered profiles will be overwritten when the lookups.dat changes.
```

#### `safety.brf` is literally `trekking.brf` with one flag flipped

Byte-level diff of `https://brouter.de/brouter/profiles2/trekking.brf` vs
`https://brouter.de/brouter/profiles2/safety.brf` (both fetched 2026-09-15, 17852 vs 17836 bytes):
the **only** functional difference is one line in the `---context:global` block:

```
-assign   avoid_unsafe             = false  # %avoid_unsafe% | Set true to avoid standard highways | boolean
+assign avoid_unsafe = true # %avoid_unsafe% | Set true to avoid standard highways | boolean
```

The comment header is **not** changed — `safety.brf` still opens with the trekking text:
```
# *** The trekking profile is for slow travel
# *** and avoiding car traffic, but still with
# *** a focus on approaching your destination
# *** efficiently.
```
So the header comment of `safety.brf` is misleading; the profile is "trekking + avoid unsafe highways".

#### What each requested profile actually optimises (verbatim comment headers)

`trekking.brf` (https://brouter.de/brouter/profiles2/trekking.brf), lines 1-4:
```
# *** The trekking profile is for slow travel
# *** and avoiding car traffic, but still with
# *** a focus on approaching your destination
# *** efficiently.
```

`fastbike.brf` (https://brouter.de/brouter/profiles2/fastbike.brf), lines 1-15:
```
#
# A fastbike could be a racing bike or a speed pedelec.
# But also at night or in rainy weather you might want
# to fallback to this one.
#
# Structure is similar to trekking.brf, see this for documentation.
#
# With default configuration (tested for Europe), trunks are
# strongly avoided even if there's no bike restriction
# (assuming implicit bicycle=no), and motorways are not allowed
# even with bicycle=yes). This is not the expected behavior for some countries
# such as Japan or Australia.
# Then, you should switch allow_motorways variable to true for these regions.
#
```

`fastbike-lowtraffic.brf` and `fastbike-asia-pacific.brf` carry the **same** header block as `fastbike.brf`
(byte-identical header; verified by diff). They differ only in two `---context:global` values:

```
fastbike.brf:               assign consider_traffic      = 0.1      # %consider_traffic% | ... [1=very important, 0.5=pretty important, 0.3=important, 0.1=slightly preferred, 0.0=do not consider traffic]
fastbike-lowtraffic.brf:    assign consider_traffic = true # %consider_traffic% | ... [1=very important, ...]
fastbike-asia-pacific.brf:  assign allow_motorways = true # %allow_motorways% | Set to true to allow motorways (useful in Asia / Oceania for example) | boolean
```
i.e. **`fastbike-lowtraffic.brf` is produced purely by setting `consider_traffic` from `0.1` to `true` (=1)
in `fastbike.brf`.** No other line differs.

`fastbike-verylowtraffic.brf` / `racebike-verylowtraffic.brf` (https://brouter.de/brouter/profiles2/fastbike-verylowtraffic.brf),
lines 1-11, verbatim (note the internal filename mismatch — the first line says "racebike" in *both* files):
```
#  "racebike-verylowtraffic.brf" -- Version 30.07.2026    -- (Support the new tag "shoulder" & "estimated_crossing_class")
#
#  The basic version of this profile for "racebike" is "very low traffic",  intended for cyclists who ride alone in the middle of the week (where high car / truck traffic is expected)
#   -but, cyclists biking in a group or at the weekend can change the routing by using the option "consider_traffic" as follow:
#
#
#  setting of the option "consider_traffic"     | route is calculated with preference...
# --------------------------------------------------------------------------------------------------------------
#  as cyclist alone in the week (default)       | very low traffic (using all highway-types allowed for bikers)
#  as cyclist alone at the weekend              | low traffic      (more tertiary /secondary / primary as above)
#  with a group of cyclist                      | medium traffic  (more tertiary /secondary / primary as above, less residential, footway etc..)
#  with a group of cyclist at the weekend       | high traffic is accepted (more tertiary /secondary / primary as above, less residential, footway etc..)
#  do not consider traffic                      | also very high traffic is accepted (more tertiary /secondary / primary as above, less residential, footway etc..)
```

`moped.brf` (https://brouter.de/brouter/profiles2/moped.brf), lines 1-7:
```
#
# Moped-Routing is experimental !!!
#
# DO NOT USE FOR ACTUAL NAVIGATION
#
# Turn restrictions are missing, leading to wrong routes
#
```

`car-eco.brf` (https://brouter.de/brouter/profiles2/car-eco.brf), lines 1-9 — note it is **car**, not bike, and
it declares a kinematic model:
```
#
# Car-Routing based on a kinematic model
#
# Depending on the vmax-parameter (target-speed)
# and can handle the costfactor
# this can be anything from least-time routing to eco-routing
#
#
---model:btools.router.KinematicModel
```

`shortest.brf` (https://brouter.de/brouter/profiles2/shortest.brf) has **no comment header at all** — the file
starts directly with `---context:global   # following code refers to global config`. It is the pure
distance profile:
```
assign downhillcost 0
assign downhillcutoff 1.5
assign uphillcost 0
assign uphillcutoff 1.5
```

`softaccess.brf` (https://brouter.de/brouter/profiles2/softaccess.brf) — the whole file is 631 bytes:
```
# *** soft-access check used to prevent setting the cyclewaybit on motorroads
...
assign costfactor

  #
  # exclude motorways, proposed roads and motorroads
  #
  switch    bicycle=yes                               1
  switch or highway=motorway highway=motorway_link    100000
  switch or highway=proposed highway=abandoned        100000
  switch    motorroad=yes                             100000

  1
```
It is **not a routing profile**; it is an auxiliary profile passed to the map creator
(`process_pbf_planet.sh` passes `../trekking.brf ../softaccess.brf` as arguments to `OsmFastCutter`).

`hiking-beta.brf` (https://brouter.de/brouter/profiles2/hiking-beta.brf), lines 1-8:
```
# Profile for medium-level hiking
#
# See https://github.com/poutnikl/Brouter-profiles for
# more variants and poutnik's profile collection

# *** Based on Trekking profile, added flag driven customizations
# 10/6/2015 v1.7.2 BETA - Fixed general way access penalties
# See bottom for Changelog and verbose comments.
```
`hiking-beta.brf` is **legacy / superseded**: brouter-web's own config renames it (see
https://brouter.de/brouter-web/config.js):
```
    BR.conf.profilesRename = {
        'hiking-beta': 'hiking-mountain',
    };
```
It is still served on brouter.de (HTTP 200) but no longer offered in the web UI, and the Android app's
download list (`brouter-routing-app/src/main/assets/serverconfig.txt`) ships `hiking-mountain.brf`, not
`hiking-beta.brf`.

`trekking-ignore-cr.brf` — same header as `trekking.brf`; single functional diff:
```
-assign   ignore_cycleroutes       = false  # %ignore_cycleroutes% | Set true for better elevation results | boolean
+assign ignore_cycleroutes = true # %ignore_cycleroutes% | Set true for better elevation results | boolean
```

#### The `.brf` profile scripting language

Technical reference (primary): https://github.com/abrensch/brouter/blob/master/docs/developers/profile_developers_guide.md
(also served as plain text at https://brouter.de/brouter/profile_developers_guide.txt).
Its own opening line: `Profile developers guide` / `Technical reference for BRouter profile scripts`.

**Three contexts** (verbatim):
```
The special tags: `---context:way` and `---context:node` mark the beginning of
each section.
...
In the profile scripts there is a third context `global` which contains global
configuration which is shared for all contexts and is accessible by the routing
engine.

The variables from the `global` section in the profile scripts are read-only
visible in the `way` and `node` sections of the scripts.
```
Node context can read way variables through the `way:` prefix — verbatim:
```
An exception from context separation is the node-context, where variables from
the way-context of the originating way can be accessed using the `way:` prefix.
```

**Polish notation + the complete operator set.** Verbatim from the Profile Developers Guide and confirmed
in code at `brouter-expressions/src/main/java/btools/expressions/BExpression.java` (lines 106-200, which
switch on exactly these tokens):

```
- 1 Operand operators are:

  - `not <boolean expression>`

- 2 Operand operators are:

  - `or       <boolean expression 1> <boolean expression 2>`
  - `and      <boolean expression 1> <boolean expression 2>`
  - `xor      <boolean expression 1> <boolean expression 2>`
  - `multiply <numeric expression 1> <numeric expression 2>`
  - `divide   <numeric expression 1> <numeric expression 2>`
  - `add      <numeric expression 1> <numeric expression 2>`
  - `sub      <numeric expression 1> <numeric expression 2>`
  - `max      <numeric expression 1> <numeric expression 2>`
  - `min      <numeric expression 1> <numeric expression 2>`
  - `equal    <numeric expression 1> <numeric expression 2>`
  - `greater  <numeric expression 1> <numeric expression 2>`
  - `lesser   <numeric expression 1> <numeric expression 2>`

- 3 Operand operators are:

  - `switch <boolean-expression> <true-expression> <false-expression>`
```
Plus `if ... then ... else ...` sugar, `assign <variable-name> = <expression>`, parentheses,
`true`/`false`, and the pipe shorthand `highway=primary|secondary|tertiary`. **`assign` is only legal at
top level** — `BExpression.java:124-126`:
```java
      if (!"assign".equals(operator)) {
        throw new IllegalArgumentException("operator " + operator + " is invalid on toplevel (only 'assign' allowed)");
      }
```

**`consider` / `ignore` mechanism — a correction.** There is **no `consider` or `ignore` operator/directive
in the BRouter profile language.** NOT DOCUMENTED in
https://github.com/abrensch/brouter/blob/master/docs/developers/profile_developers_guide.md — and the parser
(`BExpression.java`) accepts only the operator tokens listed above. The nearest real mechanisms are:

1. Ordinary `assign <flag> = ...` variables whose names *start with* `consider_` — purely a naming
   convention (e.g. `consider_traffic`, `consider_noise`, `consider_crossing`, `consider_river`,
   `consider_forest`, `consider_town`, `consider_elevation`, `considerTurnRestrictions`).
2. `processUnusedTags` — the mechanism that controls whether *unused* OSM tags survive into the response;
   guide verbatim:
   ```
   - `processUnusedTags` default=false

      If an OSM tag is unused within the profile, BRouter totally ignores the
      tag existence. Skipping unused tags improves BRouter speed. As a side
      effect, the tag is not even listed in the route segment table nor the
      table exported as CSV. Setting it to true/1, Brouter-web Data page will
      list all tags present in the RD5 file.
   ```
3. Hard exclusion by cost: "Forbidden ways or nodes must be treated as very high cost, because there is no
   *forbidden* value." (see Limits below).

**Cost model.** Verbatim, guide:
```
#
# calculate the cost-factor, which is the factor
# by which the distance of a way-segment is multiplied
# to calculate the cost of that segment. The costfactor
# must be >=1 and it's supposed to be close to 1 for the
# type of way the routing profile is searching for
#
```
(from `trekking.brf`). Way cost = `costfactor` × length, plus `turncost`, plus `initialcost` on
`initialclassifier` change, plus elevation cost.

**Predefined / engine-read variables** (guide, verbatim), global section:
```
    - `downhillcost`
    - `downhillcutoff`
    - `downhillmaxslope`
    - `downhillmaxslopecost`
    - `uphillcost`
    - `uphillcutoff`
    - `uphillmaxslope`
    - `uphillmaxslopecost`
    - `elevationpenaltybuffer`
    - `elevationmaxbuffer`
    - `elevationbufferreduce`
...
    - `validForBikes`
    - `validForFoot`
    - `validForCars`
...
    - `pass1coefficient`
    - `pass2coefficient`
...
    - `turnInstructionMode`          0=none, 1=auto-choose, 2=locus-style,
      3=osmand-style, 4=comment-style, 5=gpsies-style, 6=orux-style, 7=locus-old-style
    - `turnInstructionCatchingRange` default=40m
    - `turnInstructionRoundabouts`   default=true generate explicit roundabout
      hints
```
plus behaviour switches `processUnusedTags` (default false), `use_dynamic_range` (default true),
`add_beeline` (default false), `check_start_way` (default false), `correctMisplacedViaPoints` (default false),
`correctMisplacedViaPointsDistance` (default 400), `continueStraight` (default false).

Way-section predefined variables:
```
  - `turncost`
  - `initialcost`
  - `costfactor`
  - `uphillcostfactor`
  - `downhillcostfactor`
  - `nodeaccessgranted`
  - `initialclassifier`
  - `priorityclassifier`
```
Node section: only `initialcost`.

Engine defaults for some of these, read in `brouter-core/src/main/java/btools/router/RoutingContext.java`:
```java
    waypointCatchingRange = expctxGlobal.getVariableValue("waypointCatchingRange", 250.f);
...
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 45.f) / 3.6;
...
    defaultC_r = expctxGlobal.getVariableValue("C_r", 0.01f);
...
    buildBeelineOnRange = expctxGlobal.getVariableValue("add_beeline", 0f) == 1f;
```

**`initialclassifier`.** Guide, verbatim:
```
To trigger the addition of the `initialcost`, another variable is used:
`initialclassifier` - any change in the value of that variable leads to adding
the value of `initialcost`.
```

**`priorityclassifier`.** Guide, verbatim:
```
`priorityclassifier` is a BRouter numerical parameter calculated for ways and
used for generation of pictogram/voice navigation instructions.

Higher values means the more significant (noticeable) way, as far as it can be
predicted from OSM data.
```
`trekking.brf` maps it to highway classes, e.g.:
```
assign priorityclassifier =

  if      ( highway=motorway                          ) then  30
  else if ( highway=motorway_link                     ) then  29
...
  else if ( isresidentialorliving                     ) then  6
  else if ( highway=service                           ) then  6
  else if ( highway=cycleway                          ) then  6
  else if ( or bicycle=designated bicycle_road=yes    ) then  6
  else if ( highway=track                             ) then if tracktype=grade1 then 6 else 4
```
and encodes further bits into `classifiermask` (used for voice hints):
```
assign classifiermask add          isbadoneway
                      add multiply isgoodoneway   2
                      add multiply isroundabout   4
                      add multiply islinktype     8
                          multiply isgoodforcars 16
```

**`turncost`.** Guide: "this is the cost (in Meter) for a 90-degree turn / The actual cost is calculated as
turncost*cos(angle)". `trekking.brf`:
```
assign turncost = if is_ldcr then 0
                  else if junction=roundabout then 0
                  else 90
```
`moped.brf`: `assign turncost = if junction=roundabout then 0 else 90`.

**`nodeaccess` / `nodeaccessgranted`.** The variable actually named in the language is
**`nodeaccessgranted`** (way context) — there is **no** variable called `nodeaccess`.
NOT DOCUMENTED in the guide beyond its name in the way-section list. Guide:
```
For the variable nodeaccessgranted there's an additional legacy-hack to access
it as a lookup value without prefix:

`if nodeaccessgranted=yes then ...`
```
`trekking.brf` produces it from cycle-route membership:
```
assign nodeaccessgranted =
     if any_cycleroute then true
     else lcn=yes
```
and the node context consumes it:
```
assign bikeaccess =
       if nodeaccessgranted=yes then true
```
The actual node penalty is the node-context `initialcost` (`trekking.brf`, node section, verbatim):
```
assign initialcost =
       if or highway=traffic_signals and highway=crossing crossing=traffic_signals then 20
       else
       if bikeaccess then 0
       else ( if footaccess then 100 else 1000000 )
```

#### How a LOW-TRAFFIC route is actually produced

Three separate mechanisms exist; only the first two are "traffic" in the real sense.

**(a) `estimated_traffic_class` pseudo-tag.** Documented in
https://github.com/abrensch/brouter/blob/master/docs/developers/environmental_considerations_and_pseudo_tags.md:
```
Environmental considerations (penalties for traffic, noise, town, no river, no forest) are possible due to the creation of pseudo tags during processing OSM data by spatial SQL queries in [brouter.sql](...)
```
and the derivation:
```
### traffic_class

(modified copy from the sql file).
OSM data used to estimate the traffic:

- population of towns (+ distance from position to the towns)
- size of industrial areas (landuse=industrial) and distance to them. Not considered: solar & wind farms.
- airports international
- motorway and trunk road density - traffic on motorways decreases traffic on primary/secondary/tertiary. Calculated on a grid (100 km^2)
- density of highways (tertiary and higher) calculated on a grid (100 km^2). Traffic decreases when more such roads are available. Exceptions: near junctions between motorways and other roads the traffic increases on these roads.
- mountain-ranges calculated as density of peaks > 400 m traffic is generally on highways in such regions higher as only generated by the local population or industrial areas
- calculate traffic from the population (for each segment of type primary secondary tertiary)
- SUM of (population of each town < 100 km) / ( town-radius + 2500 + dist(segment-position to the town) ** 2 )
- town-radius is calculated as sqrt(population)
```
`estimated_traffic_class` has values 1..7 in `lookups.dat` (way context):
```
estimated_traffic_class;0000000001 1
estimated_traffic_class;0000000001 2
...
estimated_traffic_class;0000000001 7
```
`trekking.brf` (verbatim):
```
# add estimate tags
assign traffic_penalty
   switch consider_traffic
      switch estimated_traffic_class=       0
      switch estimated_traffic_class=1|2    0.2
      switch estimated_traffic_class=3      0.4
      switch estimated_traffic_class=4      0.6
      switch estimated_traffic_class=5      0.8
      switch estimated_traffic_class=6|7    1 99 0
```
Note `consider_traffic` **defaults to `false` in trekking.brf** (`assign consider_traffic = false`), so with
stock settings `trekking.brf` applies **no** traffic penalty. `fastbike.brf` (verbatim):
```
# Eventually compute traffic penalty
assign trafficpenalty =
      if not consider_traffic then 0

      min switch hascycleway 0.3 100   # trafficpenalty not above 0.3 if a cycleway is present
      if   estimated_traffic_class=|1|2  then 0
      else if estimated_traffic_class=3 then multiply   0.3   consider_traffic
      else if estimated_traffic_class=4 then multiply   0.6   consider_traffic
      else if estimated_traffic_class=5 then multiply   0.9   consider_traffic
      else if estimated_traffic_class=6|7 then multiply 1.5   consider_traffic
```
**So: the low-traffic variants are exactly the profiles that raise `consider_traffic` above its default.**
`fastbike.brf` = 0.1 (slight), `fastbike-lowtraffic.brf` = `true` (=1, "very important"),
`fastbike-verylowtraffic.brf` / `racebike-verylowtraffic.brf` = 1 plus `consider_crossing = true`
(verbatim from `fastbike-verylowtraffic.brf`):
```
assign   consider_traffic     =  1        #  %consider_traffic% | how do you plan to drive the tour? |  [1=as cyclist alone in the week, 0.5=as cyclist alone at the weekend, ...
assign   consider_crossing     =  true        # %consider_crossing% | set to true to add penalty by unprotected intersections | boolean
```
`gravel.brf` uses its own flag name: `assign consider_traffic_estimate false # %consider_traffic_estimate% | Enable to consider traffic estimates | boolean`.

**(b) `estimated_crossing_class` pseudo-tag (unprotected crossings/left turns).** Documented in
https://github.com/abrensch/brouter/blob/master/docs/developers/pseudo_nodes.md, verbatim:
```
Case 1: "unsecured crossing"
A biker is riding on a highway such as track, path, residential… and have to cross a primary or secondary highway with (very) high car-traffic and WITHOUT any securing equipment (such as traffic-signal, zebra...).
The risk and wait time of course depends on the traffic on the Primary / secondary.
An option to avoid (whenever possible) such nodes should exist!

Case 2: "unsecured left-turn"
```
```
The junctions that are secured by traffic-signal, zebra, roundabout ... get the pseudo-tag "estimated_crossing_class=0".
The remaining junctions  get the pseudo-tag "estimated_crossing_class" with a value between 1 (low risk) to 6 (high risk).
```
It is a **node** tag (lookups.dat `---context:node` → `estimated_crossing_class;0000000001 0` … `6`).
`fastbike-verylowtraffic.brf` implements it (verbatim excerpt):
```
# additional turn costs when "estimated_crossing_class" exists on the node at the junction between the old and the new HW
# and "consider_crossing" is true!
...
switch estimated_crossing_class=   0
switch estimated_crossing_class=1  111
switch estimated_crossing_class=2  211
switch estimated_crossing_class=3  311
switch estimated_crossing_class=4  511
```
`trekking.brf` / `safety.brf` / `fastbike*.brf` (except the verylowtraffic pair) do **not** use
`estimated_crossing_class`.

**(c) Long-distance cycle-route stickiness** — the mechanism that makes `trekking` "avoid car traffic".
Verbatim in `trekking.brf`:
```
#
# handle long-distance cycle-routes.
#
else if ( is_ldcr ) then 1                   # always treated as perfect (=1)
else
add ( if stick_to_cycleroutes then 0.5 else 0.05 )  # everything else somewhat up
```
and https://brouter.de/brouter/cycleroutes.html, verbatim:
```
BRouter can do that, and the "trekking" profile makes use of it.
It does that implicitly by just making them cheap in the cost-function,
so that the routing sometimes "snaps in" to long distance cycle routes.
```
`trekking-ignore-cr.brf` is the documented opt-out: *"(use the "trekking-ignore-cr" profile for that purpose)"*.

#### OSM tag keys referenced by the shipped profiles (which of the task's list are actually used)

Checked with an exact `key=` regex over every `.brf` on https://brouter.de/brouter/profiles2/:

| tag key | used by |
|---|---|
| `highway=` | all profiles except `rail.brf`, `river.brf`, `dummy.brf`, `all.brf` (which use it too) |
| `maxspeed=` | `mtb`, `moped`, `gravel`, `fastbike-verylowtraffic`, `racebike-verylowtraffic`, `trekking_SB`, `MTB_SB_light`, car profiles |
| `surface=` | `trekking*`, `safety`, `fastbike*`, `gravel`, `moped`, `mtb`, `skating`, `vm-forum-velomobil`, car profiles |
| `tracktype=` | `trekking*`, `safety`, `fastbike*`, `gravel`, `moped`, `mtb`, `shortest`, `skating`, car profiles |
| `bicycle=` | `trekking*`, `safety`, `fastbike*`, `gravel`, `mtb`, `shortest`, `skating`, `softaccess`, `hiking*`, `trekking_SB`, `MTB_SB_light` |
| `cycleway=` / `cycleway:left=` / `cycleway:right=` / `cycleway:both=` | `trekking*`, `safety`, `fastbike*`, `gravel`, `mtb`, `skating`, `vm-forum-velomobil`, `trekking_SB`, `MTB_SB_light` |
| `sidewalk=` | **only** `hiking-beta.brf` and `hiking-mountain.brf` |
| `lit=` | **no shipped profile uses `lit=`** (the key exists in `lookups.dat`, but no `.brf` matches it) |
| `mtb:scale=` | `mtb`, `gravel`, `quaelnix-gravel`, `hiking-beta`, `hiking-mountain` |
| `sac_scale=` | `gravel`, `quaelnix-gravel`, `hiking-beta`, `hiking-mountain` |
| `smoothness=` | nearly all bike profiles |
| `oneway=` / `oneway:bicycle=` | `trekking*`, `safety`, `fastbike*`, `mtb`, `gravel`, `skating`, `vm-forum`, `trekking_SB`, `MTB_SB_light` |
| `access=` / `vehicle=` / `foot=` | `trekking*`, `safety`, `fastbike*`, `mtb`, `shortest`, `skating`, `moped`, car profiles |
| `shoulder=` | `fastbike-verylowtraffic`, `racebike-verylowtraffic`, `trekking_SB`, `MTB_SB_light` |
| `estimated_traffic_class=` | `trekking*`, `safety`, `fastbike*`, `gravel`, `quaelnix-gravel`, `mtb`, `skating`, `trekking_SB`, `MTB_SB_light` |
| `estimated_crossing_class=` | `fastbike-verylowtraffic`, `racebike-verylowtraffic`, `trekking_SB`, `MTB_SB_light` |
| `estimated_noise_class=`, `estimated_river_class=`, `estimated_forest_class=`, `estimated_town_class=` | `trekking*`, `safety`, `fastbike*`, `gravel`, `quaelnix-gravel`, `skating`, `trekking_SB`, `MTB_SB_light`, car profiles |

Example of the `probable-good-surface` idiom that the docs advertise (verbatim `trekking.brf`):
```
assign ispaved = or surface=paved|asphalt|concrete|paving_stones|sett smoothness=excellent|good
assign isunpaved = not or ispaved or ( and surface= smoothness= ) or surface=fine_gravel|cobblestone smoothness=intermediate|bad
assign probablyGood = or ispaved and ( or isbike highway=footway ) not isunpaved
```

**Parameter annotation convention** (this is how a profile declares tunable defaults, descriptions, types and
ranges, and it is what brouter-web / the Android app parse). Verbatim from
https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md:
```
assign avoid_path            = false  # %avoid_path% | Set to true to avoid paths | boolean
```
Parser implementation (verbatim, `RoutingParameterDialog.getParamsFromProfile`) splits on `#` then on `|`
into name / description / type. Observed type strings in shipped profiles: `boolean`, `number`, and
enumerations written as a bracketed list, e.g.
`[1=very important, 0.5=pretty important, 0.3=important, 0.1=slightly preferred, 0.0=do not consider traffic]`.

### Per-segment response attributes (can we score/explain safety?)  (exact field names, incl. any "attributes"/"details"/"extras" request params)

**Yes — but the returned per-segment values are cost/length/time components plus the raw OSM tags used by the
profile. There is no safety score.** The exact per-segment column list is a compile-time constant in
`brouter-core/src/main/java/btools/router/Formatter.java:32`:

```java
  static final String MESSAGES_HEADER = "Longitude\tLatitude\tElevation\tDistance\tCostPerKm\tElevCost\tTurnCost\tNodeCost\tInitialCost\tWayTags\tNodeTags\tTime\tEnergy";
```

Verified live (2026-09-15) from
`https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&format=geojson`
(response saved as `brouter-live-geojson-trekking.json`):
```json
        "messages": [
          ["Longitude", "Latitude", "Elevation", "Distance", "CostPerKm", "ElevCost", "TurnCost", "NodeCost", "InitialCost", "WayTags", "NodeTags", "Time", "Energy"],
          ["8799329", "49565935", "307", "6", "1350", "0", "0", "0", "0", "highway=service", "", "1", "107"],
          ["8799411", "49566035", "307", "13", "1350", "0", "0", "0", "0", "reversedirection=yes highway=service", "estimated_crossing_class=2", "3", "355"],
          ["8799433", "49566030", "307", "2", "1650", "0", "76", "0", "0", "highway=secondary surface=asphalt smoothness=good cycleway:both=no", "estimated_crossing_class=2", "3", "393"],
```
**13 columns, exactly.** Round-tripped against the emitting code (`MessageData.toMessage()`, verbatim):
```java
    int iCost = (int) (costfactor * 1000 + 0.5f);
    return (lon - 180000000) + "\t"
      + (lat - 90000000) + "\t"
      + ele / 4 + "\t"
      + linkdist + "\t"
      + iCost + "\t"
      + linkelevationcost
      + "\t" + linkturncost
      + "\t" + linknodecost
      + "\t" + linkinitcost
      + "\t" + wayKeyValues
      + "\t" + (nodeKeyValues == null ? "" : nodeKeyValues)
      + "\t" + ((int) time)
      + "\t" + ((int) energy);
```
- `CostPerKm` = `costfactor * 1000` (integer). It is **not** a per-km monetary or safety cost; it is 1000× the
  profile's dimensionless cost multiplier. This is the single most useful field for re-scoring: you get the
  profile's own per-segment cost multiplier.
- `ElevCost`, `TurnCost`, `NodeCost`, `InitialCost` are the *cost components* in the profile's internal unit
  (metres of "equivalent length"). `TurnCost`/`NodeCost` are exactly where traffic-stress-ish penalties land.
- `WayTags` / `NodeTags` are the OSM tags **the profile actually reads** (plus any forced in, see below),
  space-separated `key=value`. The live sample shows the node pseudo-tag `estimated_crossing_class=2` reaching
  the response.

**"StreetName" and "Beeline" are NOT per-segment columns.** `grep -r "StreetName"` over the whole `master`
tarball returns **zero** hits. NOT DOCUMENTED in
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/Formatter.java and
absent from the current source. `beeline` appears only as a *waypoint feature type* in GeoJSON/GPX when a
direct (`d`) waypoint is requested — verified live:
`https://brouter.de/brouter?lonlats=8.799297,49.565883|8.90,49.60,d|8.811764,49.563606&profile=trekking&format=geojson&exportWaypoints=1`
returns `{"name": "via1", "type": "beeline"}` (source: `FormatJson.java`: `case MatchedWaypoint.WAYPOINT_TYPE_DIRECT: type = "beeline"; break;`).

**GeoJSON `properties` fields** (verbatim from `FormatJson.java`, plus one live sample):
```
"creator": "BRouter-1.7.10"   "name": "brouter_trekking_0"   "track-length": "1706"
"filtered ascend": "118"      "plain-ascend": "119"          "total-time": "1156"
"total-energy": "115631"      "cost": "3124"
```
plus optional `voicehints`, optional `speedprofile` (profile must set `showSpeedProfile`), `messages`, `times`
(per-track-point seconds, present only when `getTotalSeconds() > 0`), optional `iternity` (search statistics),
and the `geometry` LineString whose 3rd coordinate is elevation (or speed when the profile sets `showspeed`).

**How to force extra tags into the response.** Two mechanisms:
1. `assign processUnusedTags = true` (documented above) — dumps *all* tags present in the rd5 file.
2. The **undocumented `dummyUsage` trick** used by shipped profiles. Verbatim `trekking.brf` line 410-411:
```
# include `smoothness=` tags in the response's WayTags for track analysis
assign dummyUsage = smoothness=
```
Also in `misc/profiles2/shortest.brf:124`, `misc/profiles2/moped.brf:150`, `misc/profiles2/fastbike.brf:317`,
`misc/profiles2/fastbike-verylowtraffic.brf:337`, `misc/profiles2/skating.brf:440`
(`assign dummyUsage = or smoothness= surface=`).
NOT DOCUMENTED in the Profile Developers Guide — `dummyUsage` appears only inside `.brf` files, with the
inline comment above as its only explanation. This means a custom profile can add arbitrary extra tags to
`WayTags` for downstream scoring.

**GPX per-track-point `<extensions>`.** Verified live on
`https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&format=gpx&timode=9`:
```xml
 xmlns:brouter="Not yet documented" 
...
   <trkpt lon="8.799411" lat="49.566035"><ele>307.75</ele><desc>right</desc><sym>TR</sym><extensions><brouter:voicehint>TR;259, 6(81)24 (-92)24</brouter:voicehint><brouter:way>reversedirection=yes highway=service</brouter:way><brouter:node>estimated_crossing_class=2</brouter:node></extensions></trkpt>
```
The literal XML namespace declaration `xmlns:brouter="Not yet documented"` is emitted by the server
(`FormatGpx.java`), i.e. the vendor states in-band that this extension namespace is undocumented.
Emitting code (`FormatGpx.java`, `turnInstructionMode == 9` branch):
```java
          sele += "<brouter:voicehint>" + getCommandString(...) + ";" + (int) (hint.distanceToNext) + "," + hint.formatGeometry() + "</brouter:voicehint>";
          if (n.message != null && n.message.wayKeyValues != null && !n.message.wayKeyValues.equals(lastway)) {
            sele += "<brouter:way>" + n.message.wayKeyValues + "</brouter:way>";
...
          if (n.message != null && n.message.nodeKeyValues != null) {
            sele += "<brouter:node>" + n.message.nodeKeyValues + "</brouter:node>";
```
`brouter:speed` is added when the profile sets `showspeed`.

**`timode` values 8 and 9 exist in code but are NOT DOCUMENTED.** Every doc site
(`ServerHandler.java` Javadoc, `IBRouterService.aidl`, `docs/developers/profile_developers_guide.md`) lists
only `0..7`. NOT DOCUMENTED in
https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/request/ServerHandler.java
— yet `FormatGpx.java` branches on `turnInstructionMode == 8` ("osmand style, cruiser") and `== 9`
("trkpt/sym style", "BRouter style"), and mode 9 is the one that carries the `<brouter:*>` extensions.

**CSV export** exists and uses the identical 13-column header. Verified live:
`https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&format=csv` →
```
Longitude	Latitude	Elevation	Distance	CostPerKm	ElevCost	TurnCost	NodeCost	InitialCost	WayTags	NodeTags	Time	Energy
8799329	49565935	307	6	1350	0	0	0	0	highway=service		1	107
```
(Note: `docs/developers/http_server.md` / `ServerHandler` Javadoc say `format = [kml|gpx|geojson]`; `csv` is
implemented but missing from that doc comment — NOT DOCUMENTED in the ServerHandler Javadoc, only in code.)

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

**Endpoint.** `https://brouter.de/brouter` — GET, query parameters only.
Confirmed live: `https://brouter.de/brouter?lonlats=...&profile=trekking&format=geojson` → `200 OK`.
Confirmed as the official public instance by the web client config
(https://brouter.de/brouter-web/config.js):
```js
    if (hostname.endsWith('brouter.de')) {
        // online service (brouter.de) configuration

        BR.conf.host = origin;
        BR.conf.profilesUrl = origin + '/brouter/profiles2/';
```
(another public instance of the *frontend* is listed in https://github.com/nrenner/brouter-web `README.md`:
`- [brouter.damsy.net](https://brouter.damsy.net) _(provided by [@bagage](https://github.com/bagage))_`).
The vendor site index https://brouter.de/ also links a third-party instance `https://brouter.m11n.de`. This
research did **not** exercise the mirrors — `UNCERTAIN: whether the mirrors run the same BRouter version and
enforce the same watchdog/timeout limits as brouter.de.`

**Auth / API key.** **None.** No key, token, or account is required. Verified: every request above succeeded
with only a `User-Agent` (and `Accept-Encoding: identity`) header; `ServerHandler.readRoutingContext()` reads
only request parameters and contains no credential handling.

**Free tier & pricing.** There is **no pricing page and no paid tier** for BRouter. `NOT DOCUMENTED in
https://brouter.de/ and https://brouter.de/brouter/` — the complete link set of the vendor site index
https://brouter.de/ is: `/brouter-web`, `https://brouter.m11n.de`, the Google Play listing, `/brouter`,
openstreetmap.org, the FOSSGIS donation page, srtm.csi.cgiar.org, sonny.4lima.de, the Google group,
three YouTube videos, three third-party review articles and `privacypolicy.html` — there is no "pricing",
"plans", "quota" or "commercial use" page or link. BRouter itself is MIT-licensed (`LICENSE`, and
https://github.com/abrensch/brouter `README.md` says `BRouter is released under an [MIT License](LICENSE).`).
The only vendor statement about the public service is the privacy policy
(https://brouter.de/privacypolicy.html), verbatim:
```
Visits to this site are recorded in standard HTTP logfiles. This includes
among others, IP adresses, time of access, the requested resources, name and version
of the user agent, the geo-coordinates of the requested routings and the content of
uploaded custom routing profiles. This information is needed to maintain the
quality of service. It is stored for 2 weeks and then deleted.
```

**Rate limits.** `NOT DOCUMENTED in https://brouter.de/brouter/` (no page or heading mentions rate limits,
quotas, or per-IP throttling). Two concrete pieces of evidence:
- **Measured, 2026-09-15:** 15 sequential requests to
  `https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&format=geojson`
  in 11.2 s total → `200 0.73`, `200 0.75`, `200 0.71`, `200 0.71`, `200 0.74`, `200 0.72`, `200 0.71`,
  `200 0.71`, `200 0.72`, `200 0.72`, `200 0.73`, `200 0.75`, `200 0.71`, `200 0.72`, `200 0.72`.
  No 429, no throttling observed.
- **Server-side policy is a thread-limit, not a rate limit.** `brouter-server/src/main/java/btools/server/RouteServer.java`
  (verbatim):
  ```java
      if (threadQueue.size() >= maxthreads) {
        ...
          // no way... stop the oldest thread
          RouteServer oldest = threadQueue.poll();
          oldest.stopRouter();
          System.out.println(formattedTimeStamp(t) + " contention! ms killed " + (t - oldest.starttime));
  ```
  and `misc/scripts/standalone/server.sh`: `MAXTHREADS=${MAXTHREADS:-1}`.
  The value used by the deployed brouter.de instance is **not published**.
  `UNCERTAIN: the exact MAXTHREADS and maxRunningTime of the deployed brouter.de instance — not observable from outside.`

**What *is* enforced on the public instance (measured).** A long request is actively killed:
`https://brouter.de/brouter?lonlats=8.799297,49.565883|13.377485,52.516247&profile=trekking&format=geojson`
(Heidelberg → Berlin) returned **HTTP 400** with body (verbatim):
```
operation killed by thread-priority-watchdog after 9 seconds
```
That string is produced by `RoutingEngine.java:2022`:
```java
        throw new IllegalArgumentException("operation killed by thread-priority-watchdog after " + (System.currentTimeMillis() - startTime) / 1000 + " seconds");
```
So the effective public-instance budget is roughly **9 s of CPU**, enforced by the server's priority watchdog.

**POST/PUT bodies.** Disabled by default and **observed disabled on brouter.de**:
`POST https://brouter.de/brouter` with an `application/x-www-form-urlencoded` body returned
`302 Moved Temporarily` → `Location: https://brouter.de/brouter/index.html`.
Docs, verbatim (https://github.com/abrensch/brouter/blob/master/docs/developers/http_server.md):
```
Request bodies are disabled by default. To enable them, set the system
property `usePOSTRequests` to `true` in the start script
```
and the accepted body size:
```
The accepted body size is capped by the `maxRequestLength` system property
in bytes (default 1000000)
```

**Other endpoints on the same server** (`RouteServer.java` constants, verbatim):
```java
  public static final String PROFILE_UPLOAD_URL = "/brouter/profile";
  public static final String ROUTING_URL = "/brouter";
```
plus `/brouter/suspects` (`else if (url.startsWith("/brouter/suspects"))`). `OPTIONS /brouter/profile` on the
public instance returns `200 OK` with `Access-Control-Allow-Methods: GET, POST` — so the public server does
expose the custom-profile upload endpoint. Custom profiles are addressed as `profile=custom_<id>`
(`ProfileUploadHandler.CUSTOM_PREFIX = "custom_"`); the upload size cap is
`private static final int MAX_LENGTH = 100000;`.

**Request parameters (authoritative list).** Verbatim Javadoc of
`brouter-server/src/main/java/btools/server/request/ServerHandler.java`:
```
 * lonlats = lon,lat|... (unlimited list of lon,lat waypoints separated by |)
 * nogos = lon,lat,radius,weight|... (optional, list of lon, lat, radius in meters, weight (optional) separated by |)
 * polylines = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
 * polygons        = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
 * profile = profile file name without .brf
 * alternativeidx = [0|1|2|3] (optional, default 0)
 * format = [kml|gpx|geojson] (optional, default gpx)
 * trackname = name used for filename and format specific trackname (optional, default brouter)
 * exportWaypoints = 1 to export them (optional, default is no export)
 * pois = lon,lat,name|... (optional)
 * timode = turnInstructionMode [0=none, 1=auto-choose, 2=locus-style, 3=osmand-style, 4=comment-style, 5=gpsies-style, 6=orux-style, 7=locus-old-style] default 0
 * heading = angle (optional to give a route a start direction)
 * profile:xxx = parameter in profile (optional)
 * straight = idx1,idx2,.. (optional, minimum one value, index of a direct routing point in the waypoint list)
```
Additional parameters implemented in `RoutingParamCollector.setParams` but **NOT DOCUMENTED** in that Javadoc:
`engineMode`, `direction`, `roundTripStartDirection`, `roundTripDistance`, `roundTripDirectionAdd`,
`roundTripPoints`, `allowSamewayback`, `turnInstructionMode`, `turnInstructionFormat`, `exportCorrectedWaypoints`,
`trackFormat`, `nogoLons`/`nogoLats`/`nogoRadi`.
`format=gpx+...` does **not** exist: NOT DOCUMENTED in `ServerHandler.java` and not implemented — the only
accepted values are `gpx`, `kml`, `geojson`, `csv` (`ServerHandler.formatTrack`). An unknown value silently
falls back to GPX (`System.out.println("unknown track format '" + format + "', using default");`), verified
live: `&format=xml` → `200` + GPX.
`profile:xxx=value` works — verified live:
`...&profile=trekking&profile:consider_traffic=1&format=geojson` returned `"track-length": "1485"` versus
`1706` without the override, i.e. the override changed the route.

### Limits: max waypoints, max distance, other caps

| cap | value | evidence |
|---|---|---|
| **max waypoints** | **no cap documented and none found in code.** Docs say *"unlimited list of lon,lat waypoints separated by \|"* (`ServerHandler.java` Javadoc; identical wording in `IBRouterService.aidl`). `RoutingParamCollector.getWayPointList()` contains no count check. **Verified live:** 60 waypoints → 200 (54,299 B); **200 waypoints → 200** (58,538 B). `NOT DOCUMENTED in https://brouter.de/brouter/` for any numeric per-request waypoint limit. | `brouter-live-60waypoints.json`, `brouter-live-200waypoints.txt` |
| **max nogos / polylines / polygons** | *"unlimited"* per the same Javadoc; no count check in `RoutingParamCollector`. `NOT DOCUMENTED` beyond "unlimited". Default nogo radius 20 m — `RoutingContext.prepareNogoPoints`: `int ir = 20; // default radius`, and https://brouter.de/brouter/vianogo.html: *"a default radius of 20m around the given point"*. | |
| **max distance** | There is **no route-length cap**. The only identically-named parameter is the *waypoint snapping radius*, not a route limit — `brouter-mapaccess/src/main/java/btools/mapaccess/WaypointMatcherImpl.java`: `private double maxDistance;` / `public WaypointMatcherImpl(List<MatchedWaypoint> waypoints, double maxDistance, OsmNodePairSet islandPairs)` (called from `NodesCache.matchWaypointsToNodes(..., double maxDistance, ...)`). Related: `RoutingEngine.java`: `private int MAX_DYNAMIC_RANGE = 60000;` feeds the "dynamic range" waypoint search (`use_dynamic_range`, default true; Profile Developers Guide: *"With this new variable, it goes deeper and could reach a radius of about 50 km."*). BUT the *algorithm* and the *public server* both bound route length: `docs/features/offline.md` (and the site's `offline.html`), verbatim: *"BRouter always does it's best on the quality, but has a processing time that scales quadratic with distance, leading to a limit at about 150km in air-distance, which is enough for a bikers daytrip."* Measured: Heidelberg→Berlin (~550 km) → HTTP 400 `operation killed by thread-priority-watchdog after 9 seconds`. | |
| **`alternativeidx`** | clamped to 0..3 — `RoutingEngine.java:326`: `if (i == routingContext.getAlternativeIdx(0, 3)) {`; docs say `[0\|1\|2\|3]` | |
| **`roundTripPoints`** | clamped: `if (rctx.roundTripPoints == null \|\| rctx.roundTripPoints < 3 \|\| rctx.roundTripPoints > 20) { rctx.roundTripPoints = 5; }` | `RoutingParamCollector.java` |
| **`roundTripDistance`** | default 1500 m — `IBRouterService.aidl`: `//  "roundTripDistance" = distance to the round trip points (default 1500)` | |
| **request timeout** | `RouteServer.getMaxRunningTime()`: `long maxRunningTime = 60000;` (60 s), overridable by `-DmaxRunningTime=<seconds>`; Docker/`server.sh` default `-DmaxRunningTime=300`. 0 disables it (`server.sh`: *"maxRunningTime is the request timeout in seconds, set to 0 to disable timeout"*). Android service timeout is 60 s (`readme.txt`: *"if called via the service-interface, BRouter uses a timeout of 60 seconds"*). | |
| **POST/PUT body size** | 1,000,000 bytes by default — `getMaxRequestLength()`: `int maxRequestLength = 1000000;`, override `-DmaxRequestLength=` | `RouteServer.java` |
| **custom profile upload** | 100,000 characters — `ProfileUploadHandler`: `private static final int MAX_LENGTH = 100000;` | |
| **waypoint catching radius** | 250 m default — `RoutingContext.java:143`: `waypointCatchingRange = expctxGlobal.getVariableValue("waypointCatchingRange", 250.f);` | |
| **forbidden values** | `Ways with costfactor >= 10000 are considered as if they did not exist at all.` / `Ways with costfactor = 9999 are considered as if they did not exist during route calculation, but the navigation hint generator takes them into account.` / `Technically, values >= 10000 for a (way-)costfactor, and >= 1000000 for a nodes initalcost are treated as infinity` | Profile Developers Guide |
| **concurrency** | `MAXTHREADS` default 1; the oldest running request is killed when the limit is reached (`RouteServer.java`, quoted above). Per-IP "session count" is a log field only — `IpAccessMonitor`: `private static long MAX_IDLE = 900000; // 15 minutes` | |
| **error strings** | `"we need two lat/lon points at least!"`, `"not a valid engine mode"`, `"no track found"`, `"-position not mapped in existing datafile"`, `"memory limit reached"`, `"operation killed by thread-priority-watchdog after N seconds"`, `"<operation> timeout after N seconds"` | `RoutingEngine.java` |

### Self-hosting: Docker image (size if stated), OSM extract needed, effort

**Published image:** `ghcr.io/abrensch/brouter`. `README.md`, verbatim:
```
A ready image for amd64 and arm64 is published at `ghcr.io/abrensch/brouter`. The tag `latest`
points to the newest release, a tag such as `v1.7.10` to that release, `nightly` to the daily
build of master. The container runs as user 1000 and starts `server.sh`, which reads these
variables:
```
Environment variables table (verbatim from `README.md`):

| Variable | Default | Meaning |
|---|---|---|
| `JAVA_OPTS` | `-Xmx128M -Xms128M -Xmn8M -DmaxRunningTime=300 -DuseRFCMimeType=false` | JVM options and the request timeout |
| `MAXTHREADS` | `1` | Requests the server handles at once |
| `PORT` | `17777` | Listen port |
| `BINDADDRESS` | all interfaces | Listen address |

**Tag list actually published** (registry `https://ghcr.io/v2/abrensch/brouter/tags/list`, 2026-09-15,
verbatim):
```json
{"name":"abrensch/brouter","tags":["master","nightly","v1.7.5","latest","v1.7.6","v1.7.7","v1.7.8"]}
```
(`v1.7.10` is **not** yet published as a registry tag, although `README.md` uses it as the example.)

**Image size.**
- *Stated in docs* (`docs/developers/docker_guide.md`, verbatim): a locally built 1.7.2 image is `410MB`:
  ```
  REPOSITORY                     TAG                                        IMAGE ID       CREATED             SIZE
  brouter-1.7.2                  latest                                     e39703dec2fa   2 hours ago         410MB
  ```
- *Measured by me* from the published OCI manifest
  (`https://ghcr.io/v2/abrensch/brouter/manifests/latest`, amd64 manifest
  `sha256:f2a108ff82df238ced5b1c86e56c352623d8144603e0e1e7768ba853ba8e8e33`): **6 layers, 222,756,296 bytes
  total compressed** (≈ 222.8 MB). Largest single layer 187,548,781 bytes. Uncompressed size was not measured.
  `NOT DOCUMENTED` — the README does not state a published-image size.

**What the image contains** (`Dockerfile`, verbatim excerpts):
```dockerfile
FROM eclipse-temurin:17-jre-jammy
...
COPY --from=build /brouter/brouter-server/build/libs/brouter-*-all.jar /app/brouter.jar
COPY --from=build --chmod=755 /brouter/misc/scripts/standalone/server.sh /app/server.sh
COPY --from=build /brouter/misc/profiles2/ /profiles2/
...
EXPOSE 17777
```
→ Java 17 JRE base, **profiles are baked in from the repo's `misc/profiles2/` sample set**, and `/segments4`
is an empty mount point (`mkdir -p /app /segments4 /customprofiles`). **No segment data is shipped.**

**OSM extract needed.** Yes. `README.md`, verbatim:
```
Routing data files are organised as 5*5 degree files,
with the filename containing the south-west corner
of the square, which means:

- You want to route near West48/North37 -> you need `W50_N35.rd5`
- You want to route near East7/North47 -> you need `E5_N45.rd5`
```
Two options:
1. Download prebuilt `.rd5` tiles from https://brouter.de/brouter/segments4/ (*"Segments files from the whole
   planet are generated weekly"* — `docs/users/download_segments.md`).
2. Build from an OSM dump. `docs/developers/build_segments.md`, verbatim:
   ```
   You can now run the `misc/scripts/mapcreation/process_pbf_planet.sh` script to
   build the segments. ... By default, the script
   will download the latest full planet dump from
   [planet.osm.org](https://planet.osm.org/). You can also download a
   geographical extract provided by [Geofabrik](https://download.geofabrik.de/)
   and set the `PLANET_FILE` variable to point to it.
   ```
   Elevation is optional but requires SRTM: *"If you don't have the SRTM data available, the segments files will
   still be generated without any issue (but they will miss the elevation data)."*

**Effort (facts, not estimates).**
- Build: `./gradlew clean build` (or `-x :brouter-routing-app:build` to skip Android; the Android SDK path must
  be in `local.properties` for the Android module). Fat JAR: `./gradlew clean build fatJar # places JAR file in
  brouter-server/build/libs/`. Requires a JDK (Dockerfile uses Temurin **17**).
- Run standalone: `./misc/scripts/standalone/server.sh`, or
  `java -cp brouter.jar btools.server.RouteServer <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads> [bindaddress]`
  (`RouteServer.main` usage string, verbatim).
- Data volume: **10.0 GB compressed across 1,142 `.rd5` tiles** (measured from the live listing
  https://brouter.de/brouter/segments4/ on 2026-09-15; per-tile min 4,433 B, max 252,218,498 B (`E5_N45.rd5`),
  median ≈ 1.23 MB). A regional subset is far smaller — the 2021-era hardcoded per-tile table in the Android
  app (`BInstallerSizes.rd5_sizes`, comment `// Generated using ReadSizes.java`) is a useful order-of-magnitude
  reference but is stale (e.g. tile index 1983 = `E5_N45` = 94,226,174 B there vs 252,218,498 B now).
- Memory: `-Xmx128M` default in `server.sh`; docs example for heavier use `-e JAVA_OPTS="-Xmx512M -DmaxRunningTime=300"`.

### Safety / traffic-stress score output?  (yes/no + evidence)

**NO — BRouter does not output a safety or traffic-stress score.**

Evidence:
1. **No score field exists in any output format.** The complete GeoJSON `properties` set is
   `creator, name, track-length, filtered ascend, plain-ascend, total-time, total-energy, cost`
   (+ optional `voicehints`, `speedprofile`, `messages`, `times`, `iternity`) — `FormatJson.java`, quoted in
   full above. The only per-segment numbers are the 13 `messages` columns, and `CostPerKm` is explicitly
   `costfactor * 1000` (`MessageData.toMessage()`), i.e. the profile's own cost multiplier, not a stress index.
   Verified live on https://brouter.de/brouter (response saved as `brouter-live-geojson-trekking.json`).

2. **`safety.brf` optimises; it does not report.** As shown above, `safety.brf` is byte-identical to
   `trekking.brf` except `assign avoid_unsafe = true`. The flag's only use in the profile is inside the
   `costfactor` expression (verbatim):
   ```
   #
   # When avoiding unsafe ways, avoid highways without a bike hint
   #
   else add ( if ( and avoid_unsafe not isbike ) then 2 else 0 )
   ```
   — a cost multiplier of `+2` for ways that lack a bike hint. It never writes a score. `avoid_unsafe` is not
   mentioned in any doc page (`grep` over the whole repo: only `trekking*.brf`, `safety.brf`).

3. **Stress inputs are per-way pseudo-tags that BRouter itself derives at tile-build time**, and it returns
   them verbatim if the profile references them — which is the closest thing to an explainable stress signal:
   `estimated_traffic_class` (1–7), `estimated_noise_class` (1–6), `estimated_river_class` (1–6),
   `estimated_forest_class` (1–6), `estimated_town_class` (1–6) as **way** tags, and `estimated_crossing_class`
   (0–6) as a **node** tag. All are documented in
   https://github.com/abrensch/brouter/blob/master/docs/developers/environmental_considerations_and_pseudo_tags.md
   and https://github.com/abrensch/brouter/blob/master/docs/developers/pseudo_nodes.md, and all are listed in
   `lookups.dat` under `---context:way` / `---context:node`. Live proof they reach the client:
   `"NodeTags": "estimated_crossing_class=2"` in `brouter-live-geojson-trekking.json`.
   `NOT DOCUMENTED` — no vendor page states that these classes are a "safety score"; they are documented only
   as routing-cost input classes with the class tables reproduced above.

4. `traffic_class` input factors are documented (population, industrial areas, airports, motorway/trunk
   density on a 100 km² grid, highway density, mountain ranges) — see the verbatim block above. The
   classification thresholds themselves for `traffic_class` are **NOT DOCUMENTED** in
   https://github.com/abrensch/brouter/blob/master/docs/developers/environmental_considerations_and_pseudo_tags.md
   (the page gives thresholds for noise/river/forest/town only, and says of traffic only
   `(modified copy from the sql file)`).

### Sources  (bulleted exact URLs)

Vendor documentation site (brouter.de):
- https://brouter.de/brouter/  (index)
- https://brouter.de/brouter/costfunctions.html
- https://brouter.de/brouter/offline.html
- https://brouter.de/brouter/online.html  (stale, ~2014 content)
- https://brouter.de/brouter/vianogo.html
- https://brouter.de/brouter/cycleroutes.html
- https://brouter.de/brouter/alternatives.html
- https://brouter.de/brouter/elevation.html
- https://brouter.de/brouter/algorithm.html
- https://brouter.de/brouter/profile_developers_guide.txt
- https://brouter.de/brouter/readme.txt
- https://brouter.de/brouter/revisions.html
- https://brouter.de/brouter/profiles2/  (live profile directory listing)
- https://brouter.de/brouter/segments4/  (live segment tiles; + `diff/`)
- https://brouter.de/brouter/segments4/diff/
- https://brouter.de/privacypolicy.html
- https://brouter.de/brouter-api.html  → **404 Not Found** (no successor page)
- https://brouter.m11n.de  (third-party instance linked from the vendor site index; not exercised)

Live API probes (2026-09-15), saved under `docs/research/raw/`:
- https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&alternativeidx=0&format=geojson
- https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&format=csv
- https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&format=gpx&timode=9
- https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&profile:consider_traffic=1&format=geojson
- https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&engineMode=3&waypointCatchingRange=500
- https://brouter.de/brouter?lonlats=8.799297,49.565883&profile=dummy&engineMode=2&waypointCatchingRange=500
- https://brouter.de/brouter?lonlats=8.799297,49.565883|13.377485,52.516247&profile=trekking&alternativeidx=0&format=geojson  (→ 400 watchdog)
- https://brouter.de/brouter?lonlats=<60 waypoints>…&profile=trekking&format=geojson
- https://brouter.de/brouter?lonlats=<200 waypoints>…&profile=trekking&format=geojson
- https://brouter.de/brouter?lonlats=8.799297,49.565883|8.90,49.60,d|8.811764,49.563606&profile=trekking&format=geojson&exportWaypoints=1
- https://brouter.de/brouter/profile  (OPTIONS → 200 + CORS)

Official source (github.com/abrensch/brouter, branch `master`, tarball fetched 2026-09-15):
- https://github.com/abrensch/brouter
- https://github.com/abrensch/brouter/blob/master/README.md
- https://github.com/abrensch/brouter/blob/master/Dockerfile
- https://github.com/abrensch/brouter/blob/master/misc/scripts/standalone/server.sh
- https://github.com/abrensch/brouter/blob/master/gradle.properties
- https://github.com/abrensch/brouter/blob/master/docs/revisions.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/profile_developers_guide.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/http_server.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/docker_guide.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/build_segments.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/pseudo_nodes.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/environmental_considerations_and_pseudo_tags.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/algorithm.md
- https://github.com/abrensch/brouter/blob/master/docs/developers/developer_guide.md
- https://github.com/abrensch/brouter/blob/master/docs/users/download_segments.md
- https://github.com/abrensch/brouter/blob/master/docs/users/android_advanced.md
- https://github.com/abrensch/brouter/blob/master/docs/users/android_quickstart.md
- https://github.com/abrensch/brouter/blob/master/docs/users/osmand.md
- https://github.com/abrensch/brouter/blob/master/docs/features/costfunctions.md
- https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/request/ServerHandler.java
- https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/RouteServer.java
- https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/request/ProfileUploadHandler.java
- https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/IpAccessMonitor.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/Formatter.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/FormatJson.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/FormatGpx.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/MessageData.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/OsmTrack.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/RoutingEngine.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/RoutingContext.java
- https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/RoutingParamCollector.java
- https://github.com/abrensch/brouter/blob/master/brouter-expressions/src/main/java/btools/expressions/BExpression.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/AndroidManifest.xml
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BRouterService.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BRouterWorker.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BRouterActivity.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/RoutingParameterDialog.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/ServerConfig.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/DownloadWorker.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BInstallerActivity.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BInstallerSizes.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BInstallerView.java
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/assets/serverconfig.txt
- https://github.com/abrensch/brouter/blob/master/brouter-routing-app/build.gradle
- https://github.com/abrensch/brouter/blob/master/misc/profiles2/lookups.dat
- https://github.com/abrensch/brouter/blob/master/misc/profiles2/readme.txt
- https://raw.githubusercontent.com/abrensch/brouter/master/  (per-file raw access used for all of the above)
- https://api.github.com/repos/abrensch/brouter  (`"default_branch": "master"`)
- https://api.github.com/repos/abrensch/brouter/git/trees/master?recursive=1
- https://api.github.com/repos/abrensch/brouter/tags?per_page=100
- https://github.com/abrensch/brouter/blob/v1.6.3/brouter-routing-app/src/main/AndroidManifest.xml  (older release, checked for the legacy intent API)

Published profiles used for verbatim header quotes (all fetched 2026-09-15, HTTP 200):
`https://brouter.de/brouter/profiles2/{trekking,trekking-ignore-cr,trekking-steep,trekking-noferries,trekking-nosteps,fastbike,fastbike-lowtraffic,fastbike-asia-pacific,fastbike-verylowtraffic,racebike-verylowtraffic,safety,shortest,softaccess,hiking-beta,hiking-mountain,car-eco,moped,mtb,gravel,skating,vm-forum-liegerad-schnell,vm-forum-velomobil-schnell,dummy,all,river,rail,car-vario}.brf`
and `https://brouter.de/brouter/profiles2/{routino,vmforge}.brf` → **404**.

Registry / container:
- https://ghcr.io/v2/abrensch/brouter/tags/list
- https://ghcr.io/v2/abrensch/brouter/manifests/latest

Related project used only to confirm the official endpoints (not as a source for BRouter behaviour):
- https://brouter.de/brouter-web/config.js
- https://github.com/nrenner/brouter-web  (`README.md`)

Raw artifacts saved for re-verification: `docs/research/raw/brouter-*` and
`docs/research/raw/brouter-profiles/*.brf`, plus an URL index in
`docs/research/raw/brouter-SOURCES.txt`.

---

## BRouter Android app / offline operation (requested in scope; kept separate from the scoring matrix above)

### Bicycle profiles shipped to the app
`brouter-routing-app/src/main/assets/serverconfig.txt` (verbatim):
```
segment_url=https://brouter.de/brouter/segments4/
lookup_url=https://brouter.de/brouter/profiles2/
profiles_url=https://brouter.de/brouter/profiles2/


# these are comma separated arrays

check_lookup=lookups.dat
check_profiles=car-eco.brf,car-eco-de.brf,car-fast.brf,car-eco-nocost.brf,car-fast-nocost.brf,fastbike.brf,fastbike-asia-pacific.brf,fastbike-lowtraffic.brf,fastbike-verylowtraffic.brf,hiking-mountain.brf,moped.brf,mtb.brf,rail.brf,river.brf,safety.brf,shortest.brf,trekking.brf,trekking-ignore-cr.brf,trekking-noferries.brf,trekking-nosteps.brf,trekking-steep.brf,vm-forum-liegerad-schnell.brf,vm-forum-velomobil-schnell.brf,skating.brf
```
Note: `hiking-beta.brf` is **not** in the app's download list; `hiking-mountain.brf` is.

### The intent-based API — `btools.routingapp.action.PLAN` does NOT exist in current BRouter

**Finding:** the action strings `btools.routingapp.action.PLAN` and `btools.routingapp.action.PLAN_RESULT`, and
the extras you listed (`lonlats`, `profile`, `v`, `format`, `extra`, `trackname`, `export` as *Intent* extras,
plus the broadcast/callback pair), are **not present anywhere in the current BRouter source**:

`grep -rn "btools\.routingapp\.action|PLAN_RESULT|SendIntent|IRoutingService"` over the full `master`
tarball (all `.java`, `.xml`, `.aidl`, `.md`, `.txt`) returns **only**:
```
./docs/developers/android_service.md:9:used by other applications to calculate routes. See `IBRouterService.aidl` for
./docs/developers/http_server.md:54:Please see also [IBRouterService.aidl](./android_service.md) for calling parameter.
./brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl:4:interface IBRouterService {
./brouter-routing-app/src/main/java/btools/routingapp/BRouterService.java:36:  private IBRouterService.Stub myBRouterServiceStub = new IBRouterService.Stub() {
```
Also checked the previous major release tag `v1.6.3` (`AndroidManifest.xml` +
`BRouterActivity.java`): **no `PLAN` action, no `SendIntent`** — the only actions declared are
`android.intent.action.MAIN` and `android.intent.action.VIEW`.

`UNCERTAIN: whether an Intent API with action "btools.routingapp.action.PLAN" existed in a pre-GitHub BRouter 1.4.x/1.5.x APK. The former documentation page https://brouter.de/brouter-api.html is 404 and the Internet Archive (CDX API) returned "Internet Archive: Temporarily Offline" (HTTP 503) on 2026-09-15, so no archived copy could be retrieved. The shipped BRouter 1.4 readme (https://brouter.de/brouter/readme.txt) documents only the "service interface" and the "file interface", and names no Intent action.`

**So: `NOT DOCUMENTED in https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md`
and NOT PRESENT in the current source.** The current inter-app API is (1) a bound AIDL service and
(2) explicit-Intent launches into two activities.

### What actually exists today (exact strings, from source)

**(1) Bound Service + AIDL** — `brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl`
(interface name is **`IBRouterService`, not `IRoutingService`**), verbatim:
```java
package btools.routingapp;


interface IBRouterService {


    //param params--> Map of params:
    //  "pathToFileResult"-->String with the path to where the result must be saved, including file name and extension
    //                    -->if null, the track is passed via the return argument, this should be default when Android Q or later
    //  "maxRunningTime"-->String with a number of seconds for the routing timeout, default = 60
    //  "turnInstructionFormat"-->String selecting the format for turn-instructions values: osmand, locus
    //  "trackFormat"-->[kml|gpx|json] default = gpx
    //  "acceptCompressedResult"-->[true] sends a compressed result when output format is gpx
    //  "lats"-->double[] array of latitudes; 2 values at least.
    //  "lons"-->double[] array of longitudes; 2 values at least.
    //  "nogoLats"-->double[] array of nogo latitudes; may be null.
    //  "nogoLons"-->double[] array of nogo longitudes; may be null.
    //  "nogoRadi"-->double[] array of nogo radius in meters; may be null.
    //  "fast"-->[0|1]
    //  "v"-->[motorcar|bicycle|foot]
    //  "remoteProfile"--> (String), net-content of a profile. If remoteProfile != null, v+fast are ignored
    //
    //  "lonlats"         = lon,lat|... (unlimited list of lon,lat waypoints separated by |)
    //                      variantes: lon,lat,d|... (from this point to the next  do a direct line)
    //                                 lon,lat,m|... (route point has no name and works as a meeting point)
    //                                 lon,lat,name|... (route point has a name and should not be ignored)
    //                                 lon,lat,r|... (from this point start a round trip)
    //  "straight"        = idx1,idx2,.. (optional, minimum one value, index of a direct routing point in the waypoint list)
    //  "nogos"           = lon,lat,radius,weight|... (optional, list of lon, lat, radius in meters, weight (optional))
    //  "polylines"       = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
    //  "polygons"        = lon,lat,lon,lat,...,weight|... (unlimited list of lon,lat and weight (optional), lists separated by |)
    //  "profile"         = profile file name without .brf
    //  "alternativeidx"  = [0|1|2|3] (optional, default 0)
    //  "exportWaypoints" = 1 to export them (optional, default is no export)
    //  "exportCorrectedWaypoints" = 1 to export them (optional, default is no export)
    //  "pois"            = lon,lat,name|... (optional)
    //  "extraParams"     = Bundle key=value list for a profile setup (like "profile:")
    //  "timode"          = turnInstructionMode [0=none, 1=auto-choose, 2=locus-style, 3=osmand-style, 4=comment-style, 5=gpsies-style, 6=orux-style, 7=locus-old-style] default 0
    //  "heading"         = angle (optional to give a route a start direction)
    //  "direction"       = angle (optional, used like "heading" on a recalculation request by Locus as start direction, is also used for round trip as start direction - on value = -1, BRouter uses a random direction)
    //  "engineMode"      = 0 (optional, default 0, 2 = get elevation, 3 = get segment info, 4 = round trip)
    //
    //  extra round trip parameter
    //  "roundTripDistance" = distance to the round trip points (default 1500)
    //  "roundTripPoints"   = number of used points (default 5)
    //
    // return null if all ok and no path given, the track if ok and path given, an error message if it was wrong
    //        the resultas string when 'pathToFileResult' is null, this should be default when Android Q or later
    // call in a background thread, heavy task!

    String getTrackFromParams(in Bundle params);
}
```
The `Bundle` is consumed by `BRouterService.getTrackFromParams(Bundle)`; `BRouterService` is declared in the
manifest as `android:exported="true"` and in a separate process:
```xml
        <service
            android:name=".BRouterService"
            android:enabled="true"
            android:exported="true"
            android:process=":brouter_service" />
```

**(2) Explicit-Intent launch into `BRouterActivity` — the only documented Intent API.** Verbatim from
https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md:
```
Intent intent = new Intent();
intent.setClassName("btools.routingapp", "btools.routingapp.BRouterActivity");
intent.putExtra("runsilent", true);
startActivity(intent);
```
Source confirms exactly one extra is read from the launch Intent (`BRouterActivity.java:126-127`):
```java
    Intent i = getIntent();
    if (i.hasExtra("runsilent")) startSilent = true;
```
`runsilent` is the **only** extra `BRouterActivity` reads from its own launch Intent. There is no
`PLAN`/`PLAN_RESULT` action and no broadcast result.

**(3) Profile-parameter dialog Intent (documented in a source comment).** `RoutingParameterDialog.java`
class Javadoc, verbatim:
```
 * Call the Parameter Setting Dialog from your app like this:
 * <p>
 * // read the variable parameters into array
 * List<RoutingParam> listParams = new ArrayList<>();
 * String file = "xyz.brf";
 * InputStream fis = new FileInputStream(file);  // could be also a stream from DocumentFile
 * listParams = getParamsFromProfile(fis);
 * fis.close();
 * <p>
 * // call the BRouter param dialog
 * ComponentName cn = new ComponentName("btools.routingapp", "btools.routingapp.RoutingParameterDialog");
 * Intent i = new Intent();
 * i.setComponent(cn);
 * <p>
 * // fill some parameter for the dialog
 * String profile_hash = String.format("X%X", file.hashCode()); // some identify code
 * i.putExtra("PROFILE_HASH", profile_hash);
 * i.putExtra("PROFILE", profile_name);   // the profile name, only used for display
 * i.putExtra("PARAMS", listParams);      // the settings list
 * i.putExtra("PARAMS_VALUES", saved_params);  // your stored profile parameter or nothing
 * <p>
 * startActivityForResult(i, ROUTE_SETTING_REQUEST);
```
Result extras (same Javadoc): `PARAMS_VALUES` (String), `PROFILE` (String), `PROFILE_HASH` (String).
The activity is manifest-declared `android:exported="true"` with `android:launchMode="singleTask"`.

**Is the intent API documented anywhere or only in source?** Both, in different places:
- Explicit-Intent (`runsilent`) → documented in `docs/developers/android_service.md`.
- AIDL `getTrackFromParams(Bundle)` → documented in the `.aidl` file itself and referenced by
  `docs/developers/android_service.md` ("See `IBRouterService.aidl` for the interface definition.") and
  `docs/developers/http_server.md`.
- `RoutingParameterDialog` Intent → **source comment only**; `docs/developers/android_service.md` merely links
  to the file ("You find the call of BRouter app in comment at RoutingParameterDialog").
- `btools.routingapp.action.PLAN` / `PLAN_RESULT` → **not documented anywhere and not in the source** (see
  above).

**App metadata (from `brouter-routing-app/build.gradle`):** `applicationId "btools.routingapp"`,
`versionCode 57`, `minSdkVersion 23`, `targetSdkVersion 36`. Distribution:
F-Droid `https://f-droid.org/packages/btools.routingapp`, Google Play
`https://play.google.com/store/apps/details?id=btools.routingapp` (`README.md`).
Manifest permissions: `WRITE_EXTERNAL_STORAGE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. Manifest **does not** declare the legacy
`btools.routingapp.DownloadService` that v1.6.3 had.

**App-side defaults / modes (docs/developers/android_service.md, verbatim):**
```
   "v"-->[motorcar|bicycle|foot]
   "fast"-->[0|1]
   This enables BRouter to look into the file serviceconfig.dat.
   In there BRouter find the profile associated for e.g bicyle_fast trekking
```
```
"engineMode=2" allows a client to request only an elevation for a point. This can be restricted with "waypointCatchingRange".
"engineMode=3" allows a client to request the description tags for  a segment. This can be restricted with "waypointCatchingRange".
"engineMode=4" generate routes returning to the start point.
This needs some parameter:
- roundTripDistance - radius to the round trip points in meters (default 1500)
- direction - initial round-trip bearing; use a fixed angle for reproducible loops, -1/random otherwise
```
(engineMode 3 verified on the HTTP server too — see below.)

Old `serviceconfig.dat` mapping (from the shipped 1.4 readme, https://brouter.de/brouter/readme.txt, verbatim):
```
  motorcar_fast  -> car-test
  motorcar_short -> moped
  bicycle_fast   -> fastbike
  bicycle_short  -> trekking
  foot_fast      -> shortest
  foot_short     -> shortest
```
That file is stale; current mode mapping is user-editable in the app ("Server Mode" button).

**File interface:** `docs/users/android_advanced.md`, verbatim:
```
To do this, start the BRouter app, select two or more waypoints and then start the route calculation. BRouter reads waypoints from the `import` folder
`favourites.gpx` file.
```
and nogo naming convention (verbatim): `nogo[radius] [name]` … *"radius (in Meter) is optional and defaults to 20m"*.

### Offline routing data: `.rd5` tiles, download server, tile naming, sizes

**Tile format & naming.** `docs/users/download_segments.md`, verbatim:
```
Routing data files are organised as 5\*5 degree files, with the filename
containing the south-west corner of the square, which means:

- You want to route near West48/North37 -> you need `W50_N35.rd5`
- You want to route near East7/North47 -> you need `E5_N45.rd5`

These data files, called _segments_ across BRouter, are generated from
[OpenStreetMap](https://www.openstreetmap.org/) data and stored in a custom
binary format (rd5) for improved efficiency of BRouter routing.
```
Tiles are built by `btools.mapcreator.WayLinker` (`misc/scripts/mapcreation/process_pbf_planet.sh`:
`... WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat ../lookups.dat ../all.brf segments rd5`).

**Download server.** `https://brouter.de/brouter/segments4/` — *"Segments files from the whole planet are
generated weekly at [https://brouter.de/brouter/segments4/](http://brouter.de/brouter/segments4/)."*
The directory listing is plain HTML (nginx autoindex) and contains:
```
<a href="../">../</a>
<a href="diff/">diff/</a>
<a href="0_timestamps_are_map_snapshot_time_in_CET_timezone">0_timestamps_are_map_snapshot_time_in_CET_timezone</a>
<a href="storageconfig.txt">storageconfig.txt</a>
```
plus 1,142 `<NAME>.rd5` entries. There is **no JSON index**: `https://brouter.de/brouter/segments4/segments.json`
→ **404 Not Found** (verified). The app therefore parses the HTML listing; `misc/utils/ReadSizes.java`
documents the format it expects (verbatim): `// Example (stripped multiple spaces): "<a href="E0_N10.rd5">E0_N10.rd5</a> 17-Oct-2021 01:03 9648604"`.

**Measured tile sizes (2026-09-15, from the live listing; full table saved as `brouter-segments4-sizes.csv`):**
- 1,142 tiles; total 9,990,493,712 bytes ≈ **10.0 GB**
- min 4,433 B, median 1,226,546 B (≈ 1.2 MB), mean 8,748,243 B
- max **252,218,498 B** (`E5_N45.rd5`)
- Examples: `E0_N50.rd5` 79,383,519 B; `E10_N45.rd5` 198,545,742 B; `W10_N50.rd5` 43,002,630 B

The app ships a hardcoded per-tile size table for its download UI
(`brouter-routing-app/src/main/java/btools/routingapp/BInstallerSizes.java`, comment
`// Generated using ReadSizes.java`), indexed by `BInstallerView.gridPos2Tileindex(ix, iy)`:
```java
  private int gridPos2Tileindex(int ix, int iy) {
    return (35 - iy) * 72 + (ix >= 70 ? ix - 70 : ix + 2);
  }
```
**That table is a stale snapshot** (e.g. index 1983 = `E5_N45` stores 94,226,174 B vs 252,218,498 B now).

**How the app downloads them.** `DownloadWorker.java`, verbatim key constants and URL construction:
```java
  private static final String SEGMENT_DIFF_SUFFIX = ".df5";
  private static final String SEGMENT_SUFFIX = ".rd5";
```
```java
      String md5 = Rd5DiffManager.getMD5(segmentFile);
      String segmentDeltaLocation = segmentBaseUrl + "diff/" + segmentName.replace(SEGMENT_SUFFIX, "/" + md5 + SEGMENT_DIFF_SUFFIX);
      ...
      URL segmentDeltaUrl = new URL(segmentDeltaLocation);
      if (httpFileExists(segmentDeltaUrl)) {
        ...
        downloadFile(segmentDeltaUrl, segmentDeltaFile, 0, true, DownloadType.SEGMENT);
      }
      ...
      URL segmentUrl = new URL(segmentBaseUrl + segmentName);
```
i.e. base URL `<segment_url>` + `<TILE>.rd5`, with an md5-keyed delta update at
`<segment_url>diff/<TILE>/<md5>.df5`. Default `segment_url` is
`https://brouter.de/brouter/segments4/` (`ServerConfig.java`: `private String mSegmentUrl = "https://brouter.de/brouter/segments4/";`),
overridable via `<basedir>/brouter/segments4/serverconfig.txt`. `diff/` exists and contains one subdirectory
per tile (verified: `https://brouter.de/brouter/segments4/diff/` → `<a href="E0_N10/">E0_N10/</a>` etc.).
Profiles and `lookups.dat` come from `<profiles_url>` = `https://brouter.de/brouter/profiles2/`;
the download list is the `check_profiles=` / `check_lookup=` lines quoted above.

**App directory layout** (`docs/users/android_advanced.md`, verbatim):
```
| `<basedir>/brouter`               |                                              |
| `<basedir>/brouter/modes`         | routing-mode/profile mapping and route cache |
| `<basedir>/brouter/profiles2`     | lookup-table and routing-profiles (\*.brf)   |
| `<basedir>/brouter/segments4`     | **routing data files (\*.rd5)**              |
| `<basedir>/brouter/import`        | allow a small file exchange with other apps  |
| `<basedir>/brouter/import/tracks` | place the `nogo*.gpx` files here             |
```
Minimum viable install (verbatim, https://brouter.de/brouter/readme.txt):
```
/mnt/sdcard/brouter/segments4/E5_N45.rd5
/mnt/sdcard/brouter/profiles2/lookups.dat
/mnt/sdcard/brouter/profiles2/trekking.brf
```
**Elevation is inside the `.rd5` files** for the app: the download list contains only `.rd5` + `lookups.dat` +
`*.brf`, the segments4 listing contains no `.bef`/SRTM tiles, and `.bef` files appear only in the
map-creation toolchain (`ElevationRasterTileConverter`, `PosUnifier`). NOT DOCUMENTED as an explicit
statement in https://github.com/abrensch/brouter/blob/master/docs/users/download_segments.md; the evidence is
the absence of any elevation download URL in `serverconfig.txt` plus the presence of elevation in the
response (`"Elevation"` column, `"filtered ascend"`, `.bef` only referenced by `brouter-map-creator`).

`storageconfig.txt` is needed in `segments4/` (`secondary_segment_dir`, `additional_maptool_dir`) — the server
copy at `https://brouter.de/brouter/segments4/storageconfig.txt` is verbatim:
```
#
# secondary segemnts dir initially points to the old data-directory ( segments2 )
# ...
secondary_segment_dir=/var/www/html/brouter/segments4_lastrun
```
app note (`docs/users/android_advanced.md`, verbatim): *"On the other hand, the download manager always writes
new datafiles to the primary directory, so the secondary directory is read-only."*

### `engineMode` on the HTTP server (verified live)

`https://brouter.de/brouter?lonlats=8.799297,49.565883&profile=dummy&engineMode=2&waypointCatchingRange=500`
→ `200`, GPX containing only a waypoint:
```xml
 <wpt lon="8.799287" lat="49.565885"><ele>307.25</ele><name>wpt_info</name></wpt>
```
`https://brouter.de/brouter?lonlats=8.799297,49.565883|8.811764,49.563606&profile=trekking&engineMode=3&waypointCatchingRange=500`
→ `200`, GPX whose waypoint carries the matched way's description tags:
```xml
 <wpt lon="8.799287" lat="49.565886"><ele>307.25</ele><name>wpt_info</name><desc>highway=service</desc><type>poi</type></wpt>
```
**`engineMode` is NOT DOCUMENTED in the `ServerHandler.java` Javadoc** (the HTTP parameter list quoted above
omits it). It is documented only in `IBRouterService.aidl` and implemented in
`RouteServer.java` (`if (params.containsKey("engineMode")) { engineMode = Integer.parseInt(params.get("engineMode")); }`).
Mode constants (`RoutingEngine.java`, verbatim):
```java
  public final static int BROUTER_ENGINEMODE_ROUTING = 0;
  public final static int BROUTER_ENGINEMODE_SEED = 1;
  public final static int BROUTER_ENGINEMODE_GETELEV = 2;
  public final static int BROUTER_ENGINEMODE_GETINFO = 3;
  public final static int BROUTER_ENGINEMODE_ROUNDTRIP = 4;
```
Caveat measured: for `engineMode=3` the public server advertises `Content-Encoding: gzip` while sending plain
text, which breaks strict HTTP clients (observed as `httpx.DecodingError / zlib.error: incorrect header check`);
curl with `Accept-Encoding: identity` works.

---
## RideWithGPS

Canonical doc host: `https://ridewithgps.com/api` **redirects** to `https://ridewithgps.com/api/v1/doc`.
The machine-readable contract is `https://ridewithgps.com/api/v1/openapi.yaml` (OpenAPI 3.0.1,
`title: Ride with GPS API v1`).

### Bicycle profile & safety/traffic-stress modelling

The **public API has no bicycle routing profile at all** — there is no routing/planning surface
(see next sections). The bicycle "profile" exists only in the **web/app Route Planner**, documented
in the help centre, not exposed as API parameters.

- Routing modes are a UI concept. Verbatim (https://support.ridewithgps.com/hc/en-us/articles/4415462488475-Route-Planning-101):
  - `Cycling - prioritize based on safety for cycling. Routes will use multi-use and bike paths, but bike infrastructure may not be available in all areas.`
  - `Walking - prioritize based on safety for walking. This routing will not be based on traffic laws such as one-way streets and will use multi-use paths.`
  - `Driving - prioritize based on the shortest distance using roads.`
  - `Draw Lines - allows you to draw straight lines from point to point anywhere on the map, with no regard to existing roads or trails.`
- Surface preference is a **3-value enum**, not a numeric stress score. Verbatim (same article):
  - `Paved - stick to paved surfaces. This option will prioritize paved surfaces.`
  - `Unpaved - stick to unpaved surfaces. This option will always prioritize unpaved surfaces.`
  - `Any Surface - the Route Planner will have no preference between paved or unpaved surfaces, optimizing for a cycling route first and foremost.`
- Surface classes are Paved / Unpaved / Unknown only. Verbatim
  (https://support.ridewithgps.com/hc/en-us/articles/4419010273179-Surface-Types):
  - `Paved - Paved surfaces include asphalt, concrete, tarmac, and chip seal. These are indicated with a solid line.`
  - `Unpaved - Unpaved surfaces include gravel, dirt, and natural/unimproved trails. These are indicated with a dashed line.`
  - `Unknown - Paths are indicated as "unknown" if surface type data in the area is insufficient to determine surface type. Unknown surface types are indicated with an outlined white line.`
- **The underlying surface data is OpenStreetMap.** Verbatim (same article):
  `We rely on data from OpenStreetMap (OSM), an open-source mapping service of the entire world, for surfaces and can only update our surface type information when they do so.`
- A manual per-route surface edit exists but is **not** a reusable profile change. Verbatim
  (https://support.ridewithgps.com/hc/en-us/articles/4415470200859-Advanced-Route-Planning):
  `Manually adjust the surface type that appears on your route. Note that this will only affect this route, and will not update underlying surface type data.`
- "Cycling friendly" is a map-layer legend class, defined by traffic qualitatively. Verbatim
  (https://support.ridewithgps.com/hc/en-us/articles/4419013709851-Map-Types):
  `Cycling Friendly Roads: Paved and unpaved roads designated for lower traffic. Sometimes marked as "greenways" or as a local "bicycle network," featuring shared-lane markings, commonly known as "sharrows."`
- MTB difficulty is a *map layer*, borrowed from IMBA/STS, not a routing weight. Verbatim
  (Route Planning 101): `MTB Trail Ratings can indicate mountain bike trails as well as an IMBA or STS trail ratings to gauge mountain biking difficulty.`
- No parameter names, no defaults, and no numeric ranges are published for the cycling profile.
  **NOT DOCUMENTED** in any of:
  `https://support.ridewithgps.com/hc/en-us/articles/4415462488475-Route-Planning-101`,
  `https://support.ridewithgps.com/hc/en-us/articles/4415470200859-Advanced-Route-Planning`,
  `https://ridewithgps.com/api/v1/openapi.yaml`.
- `traffic stress`, `low-traffic`, `stress` (as a score): **NOT DOCUMENTED** anywhere in the fetched
  RideWithGPS doc/help set (grep over all saved `rwgps*` text = 0 hits).

### Per-segment response attributes (can we score/explain safety?)

Yes for **stored assets**, no for **planning**. The route "show" response carries per-track-point
integers plus per-route summary fields.

Route-level attributes, verbatim from `https://ridewithgps.com/api/v1/doc/reference/routes_and_trips`:

| Attribute | Description | Type | Unit |
|---|---|---|---|
| `track_type` | Type of track | String | n/a |
| `terrain` | Type of terrain | String | n/a |
| `difficulty` | Overall difficult | String | n/a |
| `unpaved_pct` | Percentage of unpaved points | Integer | n/a |
| `surface` | Surface type | String | n/a |

Enumerated values (footnotes on the same page, verbatim):
- `track_type` is one of `loop, out_and_back, point_to_point, unknown`
- `terrain` is one of `flat, rolling, climbing`
- `difficulty` is one of `casual, easy, moderate, hard, multi_day`
- `surface` is one of `paved, mostly_paved, mixed_surfaces, mostly_unpaved, unknown`
- `unpaved_pct` is documented on the **Route only** column (not on Trip).

Per-track-point attributes, verbatim from `https://ridewithgps.com/api/v1/doc/reference/track_points`:

| Attribute | Description | Type | Unit | Route track points | Route course points | Trip track points |
|---|---|---|---|---|---|---|
| `x` | Longitude | Float | degrees | Y | Y | Y |
| `y` | Latitude | Float | degrees | Y | Y | Y |
| `d` | Distance from start | Integer | meters | Y | Y | Y |
| `e` | Elevation | Integer | meters | Y | Y | Y |
| `S` | Surface type | Int | n/a | Y | | |
| `R` | Highway tag | Int | n/a | Y | | |
| `i` | Cue track index | String | n/a | | Y | |
| `t` | Type of cue | String | n/a | | Y | |
| `n` | Cue text | String | n/a | | Y | |
| `_e` | User edited cue | Boolean | n/a | | Y | |

`S` (surface) and `R` (highway tag) are the closest thing to a per-segment safety/surface signal.
**The integer code tables for `S` and `R` are NOT DOCUMENTED** — the `track_points` reference page
gives only `"Surface type"` / `"Highway tag"` and the unit column says `n/a`. The same is true in
`openapi.yaml` (`S: {type: integer, description: Surface type}`, `R: {type: integer, description: Highway tag}`).

Request params that control detail level:
- **No** `attributes` / `details` / `extras` parameter exists. Detail is implicit: `openapi.yaml` and
  the reference page state verbatim
  `activity_types, track_points, course_points and points_of_interest are only given when requesting the record details. They are not included in the responses of requests for collection of records.`
- The only "get more" knob is `privacy_code` (`description: Privacy code for the non public asset`,
  `in: query`, `required: false`), which unlocks a non-public asset you were given the code for.
- Full-resolution geometry also available as `GET /api/v1/routes/{id}/polyline.json` and
  `GET /api/v1/routes/{id}.{format}` (GPX/TCX/KML/FIT download).
- Course points (`course_points`) give turn cues: `t` = type of cue, `n` = cue text. Useful for
  explanation, not for traffic stress.
- Route activity types (`activity_types`, array of up to 3) are enumerated
  (https://ridewithgps.com/api/v1/doc/reference/activity_types):
  `cycling:generic`, `cycling:road`, `cycling:gravel`, `cycling:mountain`, `cycling:commute`,
  `walking:generic`, `walking:hiking`, `running:generic`, `driving:generic`, `motorcycling:generic`,
  `swimming:generic`, `snow:generic`.

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- Base: `https://ridewithgps.com`, namespace `/api/v1`. Verbatim
  (https://ridewithgps.com/api/v1/doc):
  `Our current (and only) API version is v1 and is available at the http://ridewithgps.com/api/v1 namespace.`
- Key required. Verbatim: `An API client is required for all API requests. Once the API client is created, an API key (api_key) will be assigned to it.`
- Two auth schemes. Verbatim: `The preferred method to authenticate is with OAuth` ... `The API also supports Basic authentication, using your api_key and an authentication token. Authentication tokens can be created on the API clients management page.`
  - OAuth: authorize at `https://ridewithgps.com/oauth/authorize?client_id=..&redirect_uri=..&response_type=code`;
    exchange at `POST https://ridewithgps.com/oauth/token.json`; revoke at `POST https://ridewithgps.com/oauth/revoke.json`.
    Example token response scope is `"scope": "user"` — **no other scope values are documented**.
  - Basic: `POST https://ridewithgps.com/api/v1/auth_tokens.json` with headers `x-rwgps-api-key`
    and body `{user:{email,password}}`; then headers `x-rwgps-api-key` + `x-rwgps-auth-token`, or
    `Authorization: Basic base64(api_key:auth_token)`.
  - Live probe: `GET https://ridewithgps.com/api/v1/routes.json` without credentials returns
    HTTP **401** (body saved: `rwgps-routes-noauth.txt`). So there is **no anonymous/public read**.
- API ToS (https://ridewithgps.com/api/v1/doc/terms, "Effective September 11, 2026") — the
  important verbatim lines:
  - `Anyone may use the API. You do not need a paid Ride with GPS plan, a commercial agreement, or prior approval.`
  - `There is no limit on the number of users who may connect to your API client.`
  - `You may use the API in free or paid products and services, including products that support your business or your customers.`
  - `No attribution to Ride with GPS is required.`
  - `We do not currently impose fixed request rate limits.`
  - `We may also introduce rate limits or other access controls in the future.`
  - `Collect, store, use, share, and delete API data only as authorized by the user and permitted by law.`
- **Redistribution / caching of route data: NOT DOCUMENTED.** The API Terms grant commercial use
  and require no attribution, but there is no explicit clause granting or forbidding redistribution
  of bulk route data, and no explicit caching-duration rule beyond
  `cache data when appropriate, use webhooks instead of frequent polling`.
  The consumer-service `https://ridewithgps.com/terms` was not parsed for a data-redistribution
  clause — flag as unverified.
- Free tier & pricing: the account tiers are **Starter (free)**, **Basic**, **Premium**
  (https://ridewithgps.com/pricing). Verbatim from that page: `Starter` / `Free` / `Sign Up Now`.
  Numeric prices are **not** present in the served HTML (JS-injected) — **UNCERTAIN: exact monthly/annual
  prices for Basic and Premium**. The API itself needs no paid plan (see ToS quote above); the
  *planner* features that matter for safety (heatmaps in the planner, Explore Nearby Routes,
  Advanced Map Layers, POI) are Premium-gated.
- Rate limits: none published; only the ToS soft guidance above. A `429`/`504` error code is
  **NOT DOCUMENTED** for this API. Documented error codes are
  `404, 400, 401, 403, 422, 500`.

### Limits: max waypoints, max distance, other caps

- **No public routing endpoint**, so there is no API waypoint/distance cap to document.
  Exact path list from `openapi.yaml` (22 paths) contains **no** `plan`, `route`, `directions`,
  `journey` or `matrix` endpoint:
  `/api/v1/auth_tokens`, `/api/v1/collections.json`, `/api/v1/collections/pinned.json`,
  `/api/v1/collections/{id}.json`, `/api/v1/events.json`, `/api/v1/events/{id}.json`,
  `/api/v1/members.json`, `/api/v1/members/{id}.json`, `/api/v1/points_of_interest.json`,
  `/api/v1/points_of_interest/{id}.json`, `/api/v1/points_of_interest/{id}/routes/{route_id}.json`,
  `/api/v1/routes.json`, `/api/v1/routes/{id}.json`, `/api/v1/routes/{id}.{format}`,
  `/api/v1/routes/{id}/polyline.json`, `/api/v1/sync.json`, `/api/v1/tasks/{id}.json`,
  `/api/v1/trips.json`, `/api/v1/trips/{id}.json`, `/api/v1/trips/{id}.{format}`,
  `/api/v1/trips/{id}/polyline.json`, `/api/v1/users/current`.
  Grep of `openapi.yaml` for `plan`, `planner`, `bbox`, `waypoint` (as a request param), `traffic`,
  `safety`, `popularity`, `heatmap`: **0 hits** for each (except `waypoint` inside the GPX download
  description: `GPX 1.1 track at full resolution, without waypoints`).
- `GET /api/v1/routes.json` verbatim description: `Returns a paginated list of routes for the authenticated user.`
  Params: `page` (min 1, default 1), `page_size` (min 20, max 200, default 20), `name` (partial match),
  `visibility` (`public` | `friends_only` | `private`), `distance_min`, `distance_max`,
  `elevation_gain_min`, `elevation_gain_max` (metres), `archived` (`true`|`false`).
  **There is no `bbox`, `lat`/`lng`, `near`, or user filter.** Pagination meta:
  `record_count`, `page_count`, `page_size`, `next_page_url`.
- **Reading other users' public routes via the API: not possible by search.** The index endpoint is
  scoped to `the authenticated user`. A single route can be fetched by id at
  `GET /api/v1/routes/{id}.json`, which accepts `privacy_code` and can return
  `403 - Forbidden - Route not visible to user`. Visibility semantics
  (https://ridewithgps.com/api/v1/doc/reference/visibilities): `public - accessible to everyone`,
  `friends_only - accessible only to the owner's friends`, `private - accessible only to the owner`.
  So: id-known + public ⇒ readable; **area/public search ⇒ NOT DOCUMENTED** in the API.
  The UI *does* have a public search: verbatim (Advanced Route Planning)
  `Search our public activities and routes database, along with your personal items to quickly add a route or activity to the route planner.`
  and Explore Nearby Routes is a right-click tool (Premium only). That search is **not exposed by the public API**.
- `GET /api/v1/sync.json` is an incremental library sync for the authenticated user
  (`Returns a list of items (routes and/or trips) that the user has interacted with since the given datetime.`),
  actions `created|updated|deleted|added|removed`, default flush via
  `GET https://ridewithgps.com/api/v1/sync.json?since=1970-01-01`.
- Upload cap: `POST /api/v1/routes.json` and `POST /api/v1/trips.json` accept
  `GPX, TCX, KML and FIT` as `multipart/form-data` and return a `202` task to poll
  (`status: "pending"` → `completed`). **No maximum file size or point count is documented.**
- **NOT DOCUMENTED:** maximum number of waypoints, control points, POIs, route length, or track
  points, in `openapi.yaml`, in the API doc pages, or in the help-centre search results for
  "route length limit" / "maximum waypoints" / "number of waypoints"
  (https://support.ridewithgps.com/hc/en-us/articles/36795897776411-Waypoints documents waypoints
  qualitatively only).
- Pagination cap `page_size` max **200** is the only hard documented numeric cap in the API.

### Self-hosting: Docker image (size if stated), OSM extract needed, effort

**Not offered.** RideWithGPS is closed-source SaaS. No Docker image, no self-host, no
on-prem option is documented at `https://ridewithgps.com/api`, `https://ridewithgps.com/api/v1/doc`,
or `https://ridewithgps.com/pricing`. **NOT DOCUMENTED.**

Related but distinct: RideWithGPS consumes OSM for its basemap and surfaces
(`Ride with GPS uses map data from OpenStreetMap (OSM) to power the RWGPS, OSM, OSM Cycle, and ESRI map types`,
https://support.ridewithgps.com/hc/en-us/articles/4419010818203-OSM-Map-Data) and lets users jump out to
`www.openstreetmap.org` to fix the data. OSM edits take time to appear:
`edits may take up to a few weeks to appear on Ride with GPS`.

### Safety / traffic-stress score output?

**No numeric score. Partial qualitative signals only.**

- No field named `safety`, `stress`, `lts`, `traffic`, or `risk` exists in `openapi.yaml` (0 hits).
- What does exist and is usable for scoring/explaining:
  1. `unpaved_pct` (Integer) — "% of unpaved points" (Route only).
  2. `surface` 5-value enum (`paved` … `mostly_unpaved`, `unknown`).
  3. Per-track-point `S` (surface int) and `R` (highway int) — **codes undocumented**.
  4. `difficulty` 5-value enum (`casual` … `multi_day`) — trip-character, not traffic stress.
  5. `terrain` (`flat|rolling|climbing`), `track_type` (`loop|out_and_back|point_to_point|unknown`).
- The UI claims safety-aware *cycling* routing but publishes no score:
  `Cycling - prioritize based on safety for cycling.`
- Heatmap / popularity is **proprietary and absent from the public API**. Verbatim
  (https://support.ridewithgps.com/hc/en-us/articles/4419004026395-Global-Heatmaps):
  - `Highlighting aggregated ride data, our heatmap reveals the most popular paths. The darker the line, the more activity it has.`
  - `The public Ride with GPS heatmap can be viewed at ridewithgps.com/heatmap. Premium members can access the Global Heatmap in the route planner, personal activities and route libraries, and Explore.`
  - `We used the most recent two years of publicly recorded and uploaded trips from public Ride with GPS users.`
  - Heapmap variants: `Cycling`, `Mountain Biking`, `Foot`; timeframes `All Time`, `Last 30 Days`, `Last 7 Days`.
  - There is **no** heatmap/popularity endpoint or field in `openapi.yaml` (grep `popularity`, `heatmap` = 0 hits).
- "Route Insights" is a website-only popularity/usage panel, also not in the API. Verbatim
  (https://support.ridewithgps.com/hc/en-us/articles/53501218461467-Route-Insights):
  `Insights are based on aggregated activity patterns from the Ride with GPS community.`
  and `Currently, Route Insights are only available on route pages on the Ride with GPS website.`

### Sources

- https://ridewithgps.com/api  (redirects to /api/v1/doc)
- https://ridewithgps.com/api/v1/doc
- https://ridewithgps.com/api/v1/openapi.yaml
- https://ridewithgps.com/api/v1/doc/authentication
- https://ridewithgps.com/api/v1/doc/terms
- https://ridewithgps.com/api/v1/doc/change_log
- https://ridewithgps.com/api/v1/doc/reference/routes_and_trips
- https://ridewithgps.com/api/v1/doc/reference/track_points
- https://ridewithgps.com/api/v1/doc/reference/visibilities
- https://ridewithgps.com/api/v1/doc/reference/activity_types
- https://ridewithgps.com/api/v1/doc/reference/points_of_interest
- https://ridewithgps.com/api/v1/doc/webhooks
- https://support.ridewithgps.com/hc/en-us/articles/4415462488475-Route-Planning-101
- https://support.ridewithgps.com/hc/en-us/articles/4415470200859-Advanced-Route-Planning
- https://support.ridewithgps.com/hc/en-us/articles/4419010273179-Surface-Types
- https://support.ridewithgps.com/hc/en-us/articles/4419013709851-Map-Types
- https://support.ridewithgps.com/hc/en-us/articles/4419010818203-OSM-Map-Data
- https://support.ridewithgps.com/hc/en-us/articles/4419004026395-Global-Heatmaps
- https://support.ridewithgps.com/hc/en-us/articles/53501218461467-Route-Insights
- https://support.ridewithgps.com/hc/en-us/articles/15171358082075-Explore-Nearby-Routes
- https://ridewithgps.com/pricing
- Live probes: `GET https://ridewithgps.com/api/v1/routes.json` -> 401 (no auth)

---

## OpenStreetMap (trail & bicycle tagging) + Overpass API

### Bicycle profile & safety/traffic-stress modelling

OSM has **no** built-in bicycle profile and no traffic-stress key. It provides the *raw inputs* a
router turns into a profile: access tags, highway class, surface, smoothness, track grade, and
MTB/hiking difficulty scales. Worth naming explicitly because most bike routers (BRouter,
GraphHopper, OSRM, ORS, Valhalla) read exactly these.

- Bicycle access (https://wiki.openstreetmap.org/wiki/Key:bicycle). Verbatim:
  `bicycle specifies legal restriction for cyclists when used on roads and paths.`
  Common values (verbatim list): `bicycle=yes`, `bicycle=no`, `bicycle=designated`,
  `bicycle=use_sidepath`, `bicycle=discouraged`, `bicycle=optional_sidepath`, `bicycle=dismount`,
  `bicycle=permissive`, `bicycle=private`, `bicycle=customers`, `bicycle=destination`.
  The page is in `Category:Routing Tags`.
- Highway classes that matter for trails (https://wiki.openstreetmap.org/wiki/Key:highway), verbatim:
  - `highway=path`: `A non-specific path.`
  - `highway=track`: `Roads for mostly agricultural or forestry uses. To describe the quality of a track, see tracktype=*.`
  - `highway=cycleway`: `For designated cycleways.`
  - `highway=bridleway`: `For horse riders. Pedestrians are usually also permitted, cyclists may be permitted depending on local rules/laws.`
  - `highway=footway`: `For designated footpaths; i.e., mainly/exclusively for pedestrians.`
- Track quality is `tracktype`, **not** `leisure=track`.
  ⚠️ Correction to a common assumption: `https://wiki.openstreetmap.org/wiki/Tag:leisure%3Dtrack`
  is a *different* feature. Verbatim:
  `A dedicated track for running, cycling and other non-motorised racing such as horses, greyhounds, typically not a part of the normal network of ways and paths.`
  Grade1..grade5 belong to `highway=track` + `tracktype` (https://wiki.openstreetmap.org/wiki/Key:tracktype).
  Verbatim values (`Template:Map Features:tracktype`):
  - `grade1` — `Solid. Usually a paved surface (called also Sealed road), often also tagged with surface=asphalt/concrete/chipseal/paved etc.`
  - `grade2` — `Mostly solid. Usually an unpaved track with surface of gravel mixed with a varying amount of sand, silt, and clay.`
  - `grade3` — `Even mixture of hard and soft materials. An unpaved track.`
  - `grade4` — `Mostly soft. An unpaved track prominently with soil/sand/grass, but with some hard or compacted materials mixed in.`
  - `grade5` — `Soft. An unimproved track lacking hard materials, uncompacted, with surface of soil/sand/grass.`
  Note the wiki's own caveat: `The tracktype key provides a simple classification of tracks and other unpaved ways.` and
  `tracktype involves a degree of subjectivity`.
- `surface` (https://wiki.openstreetmap.org/wiki/Key:surface) groups values under **Paved** and **Unpaved**.
  Paved group: `paved, asphalt, chipseal, concrete, concrete:lanes, concrete:plates, paving_stones,
  paving_stones:lanes, grass_paver, sett, unhewn_cobblestone, cobblestone, cobblestone:flattened,
  bricks, metal, metal_grid, wood, stepping_stones, tiles, resin_bound, user_defined`.
  Unpaved group: `unpaved, compacted, fine_gravel, gravel, shells, rock, pebblestone, ground, dirt,
  earth, mud, laterite, grass, sand, woodchips, snow, ice, salt, user_defined`.
  Plus a `Special (sports, etc.)` group (`clay, tartan, artificial_turf, acrylic, carpet, plastic, rubber`).
- `smoothness` (https://wiki.openstreetmap.org/wiki/Key:smoothness) — the closest thing to a
  vehicle-class usability scale. Verbatim purpose:
  `smoothness provides a classification scheme regarding the physical usability of a way for wheeled vehicles, particularly regarding surface regularity/flatness.`
  Documented values and "Usable by" classes (from the rendered page table):
  `excellent` — "As-new asphalt or concrete…"; `good` — `(thin_wheels) racing bike, sport motorcycles and all below`;
  `intermediate` — `(wheels) city bike, sport cars, wheel chair, cruiser/touring motorcycle`;
  `bad` — `(robust_wheels) trekking bike, normal cars…`; `very_bad` — `(high_clearance) Car with high clearance…`;
  `horrible` — `(off_road_wheels) heavy-duty off road vehicles…`; `very_horrible` —
  `(specialized_off_road_wheels) tractor, ATV, tanks, Trial/Enduro moto…`; `impassable` — `No wheeled vehicle`.
  Also verbatim: `Certain navigation applications use smoothness information to propose an optimal route depending on the vehicle the user is using (e.g., road bike / city bike / mountain bike).`
- MTB difficulty: `mtb:scale` (https://wiki.openstreetmap.org/wiki/Key:mtb:scale), **0–6**.
  Verbatim: `mtb:scale=* provides a classification scheme for difficulty of trails suitable for mountain-biking.`
  `mtb:scale applies to downhill and flat trails. Use mtb:scale:uphill for uphill.`
  Sub-values `0-` / `0+` / `1+` are allowed: `If a finer separation is needed than provided by the 7 classes, "+" or "-" can be added to the value.`
  Per-grade maxima: 1 `<40% (22°)`, 2 `<70% (35°)`, 3 `>70% (35°)`, 4 `>70%`, 5 `>70%`, 6 `>100% (45°)`.
  `mtb:scale:uphill` is **0–5**, average/max incline and obstacle column, e.g.
  `0` = `<10%` avg / `<15%` max / `None`; `5` = `Way is too steep and/or difficult to ride uphill.`
  `mtb:scale:imba` is **0–4** (`0` Easiest/white circle → `4` Extremely difficult/double black diamond),
  for purpose-built trails: `The Trail Difficulty Rating System shall be used for mountain bike trails which are specifically designed and designated for mountain biking… For "natural" trails it is advised to use mtb:scale and mtb:scale:uphill instead.`
- Hiking difficulty: `sac_scale` (https://wiki.openstreetmap.org/wiki/Key:sac_scale), **7 grades**.
  Verbatim: `The scale discerns seven grades in ascending level: From strolling up to difficult_alpine_hiking.`
  Values ↔ SAC: `strolling` (none), `hiking` (T1), `mountain_hiking` (T2),
  `demanding_mountain_hiking` (T3), `alpine_hiking` (T4), `demanding_alpine_hiking` (T5),
  `difficult_alpine_hiking` (T6).
- Trail visibility: `trail_visibility` (https://wiki.openstreetmap.org/wiki/Key:trail_visibility).
  Verbatim: `This key describes attributes regarding orientation skills required to follow a path (not the route).`
  Values: `excellent, good, intermediate, bad, horrible, no`.
- Bicycle infrastructure: `cycleway` (https://wiki.openstreetmap.org/wiki/Key:cycleway). Verbatim:
  `The cycleway tagging is used on linear features tagged with highway to map cycling infrastructure that is an inherent part of the road.`
  It `implies bicycle=designated`. Side attributes `cycleway:left` / `cycleway:right` / `cycleway:both`
  and `cycleway:lanes` / `bicycle:lanes` are documented.
- Route networks: `network` (https://wiki.openstreetmap.org/wiki/Key:network). Verbatim:
  `For example, a national cycling network is network=ncn while a national hiking trail network is network=nwn.`
  Cycle values `icn/ncn/rcn/lcn`, walking values `nwn/rwn/lwn`; `cycle_network` gives finer granularity,
  `network:type=node_network` marks junction/node networks.
- **Route relations.** `route=bicycle` and `route=mtb` are the two bicycle route values.
  `Key:route` (https://wiki.openstreetmap.org/wiki/Key:route) lists them under
  `Non-motorized vehicles`: `route=bicycle,mtb,wheelchair`, and warns
  `For instance route=foot; route=bicycle or route=horse may be used on relations (for signposted routes), but should not be used on ways.`
  `Relation:route` requires `type=route` + `route=<value>`; useful members keys include
  `name`, `ref`, `network`, `operator`, `state` (`proposed|alternate|temporary|connection`),
  `symbol`, `description`, `distance` (default unit km), `ascent`, `descent`
  (`The ascent covered by a route (default units are metres…)`), `roundtrip=yes/no`.
  Way roles: empty (main), `forward`/`backward`, and approved functional roles
  `main, alternative, approach, excursion, connection`.
  `Tag:route=mtb` (https://wiki.openstreetmap.org/wiki/Tag:route%3Dmtb) verbatim:
  `MTB (Mountain bike) routes are named or numbered or otherwise signed routes, designated for mountain biking. They may go along roads, trails or dedicated cycle paths, or a combination of these.`
  and the relation should carry `type=route` (mandatory), `route=mtb` (mandatory), `network`, `ref`,
  `name`, `operator`, optionally `mtb:scale`, `mtb:scale:uphill`, `mtb:scale:imba`, `distance`,
  `ascent`, `descent`, `roundtrip`, `signed_direction`, `network:type=node_network`.
  `Tag:route=mtb` status is `de facto` (not approved); `Key:mtb:scale` is `approved`.
- `ascent` (https://wiki.openstreetmap.org/wiki/Key:ascent): verbatim
  `ascent=<ascent in m> is used to indicate the total accumulated ascent in meters of a route relation.` and
  `Ascent should not be used on single ways, because it will cause confusion if the way is split up into several parts.`
- `Key:difficulty` **does not exist**: `https://wiki.openstreetmap.org/wiki/Key:difficulty` returns **404**.
  Difficulty is expressed per-domain via `mtb:scale`, `sac_scale`, `tracktype`, `smoothness`
  (and `piste:difficulty` for ski pistes).
- `mtb:difficulty` (values seen in live data: `advanced`, `intermediate`): **NOT DOCUMENTED** on the
  OSM wiki — `https://wiki.openstreetmap.org/wiki/Key:mtb:difficulty` returns **404** and a wiki
  search for `mtb:difficulty` finds no key page (saved: `osm-search-mtbdifficulty.json`).
  It is however present in the live database: a live Overpass query returned e.g.
  `"mtb:difficulty": "advanced"` and `"mtb:difficulty": "intermediate"` on `route=mtb` relations
  (`overpass-live-test-relation-route-mtb.json`).

### Per-segment response attributes (can we score/explain safety?)

OSM has no response format of its own; the query response is OSM elements with tags. The usable
per-way scoring inputs are exactly the tags above, all on the **way** (not the route relation):
`highway`, `bicycle`, `cycleway*`, `surface`, `smoothness`, `tracktype`, `mtb:scale`,
`mtb:scale:uphill`, `mtb:scale:imba`, `sac_scale`, `trail_visibility`, `incline`, `width`,
`access`, `oneway`, `maxspeed`. Overpass returns them verbatim under `tags`.

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- **Two different services, do not conflate them.**
  1. **Main OSM API** (`https://api.openstreetmap.org/api/0.6/`) — the *editing* API. Policy:
     `https://operations.osmfoundation.org/policies/api/`. Verbatim key limits:
     `Valid User-Agent identifying application and version. Faking another app's User-Agent WILL get you blocked.`
     `Maximum of 2 download threads.`
     It is explicitly **not** for bulk data: `We request that you set up your own data server, usually with planet.osm; or use another provider.`
     Verbatim on Overpass: `Overpass API - This is an API provided for read only purposes. It is part of the OpenStreetMap ecosystem, but not as "core" as the main editing API covered by this policy. They are however subject to limitations and policies of their own.`
     Also: `OpenStreetMap is open data… we cannot provide a free-of-charge map API or map tiles for third-parties.`
  2. **Overpass API** — read-only bulk query. Endpoint `https://overpass-api.de/api/interpreter`
     (operated by FOSSGIS). No key, no account, no pricing tier for the public instance.
- Overpass usage policy — there is **no** policy page at
  `https://operations.osmfoundation.org/policies/overpass/` (**404**; the OWG policy index lists only
  tiles, vector, nominatim, api, security, banner, new-tile-layers). The policy lives with the project:
  - https://dev.overpass-api.de/overpass-doc/en/preface/commons.html — verbatim:
    - `As a broad guideline to stay within safety margins, users are expected to send a maximum of about 10000 requests per day and keep their download volume below about 1 GB per day.`
    - `Each of the Overpass API servers can fulfill about 1 million requests per day, and two servers listen on the address overpass-api.de.`
    - Slots: `Every execution of a request occupies one of the slots available to the user, in particular for the full actual execution time plus a cool down time.`
      and `The number of available slots is written in line 3 after Rate limit:`
    - Enqueue window: `Requests stay enqueued up to 15 seconds on the server if not yet a slot is available to them.`
    - Denied for rate limit ⇒ `HTTP status code 429`.
    - Default max runtime / memory: `If no maximum run time is declared then a default limit of 180 seconds applies. For the maximum memory usage, the default value is 512 MiB.`
      Overrides: `[timeout:...]` and `[maxsize:...]`.
    - Resource mismatch ⇒ `HTTP status code 504`.
    - Anti-scraping guidance: `Stiching bounding boxes to scrape the full data of the complete world.` is listed as problematic behaviour; `Setting up an app for more than just OSM mappers and relying on the public instances as backend.` likewise.
  - https://wiki.openstreetmap.org/wiki/Overpass_API (public-instance table) — verbatim for
    `https://overpass-api.de/api/interpreter` (2 servers, each 16 physical cores, 128 GB RAM, NVME-SSD):
    - `You can assume that you don't disturb other users when you do less than 10,000 queries per day and download less than 1 GB data per day`.
    - `If you set something up that uses the Overpass API regularly, then divide those numbers by 100 (making less than 100 queries fetching less 10 MB of data per day fine).`
    - `If you have an app or website, then the usage counts towards the sum of requests made by all your users.`
    - `Commercial use should use self-hosted or paid Overpass servers`.
    - `No parallel running of multiple scripts`.
    - `If you receive an HTTP error code such as 429 or 406, pause for 30 seconds before making a new request`.
    - `Nowadays this server is overloaded - be mindful of that, do not overconsume resources and do not expect high reliability.`
- Live verification of behaviour (this research, from one host, `Accept: */*`):
  - `POST` body form-encoded with a generic UA ⇒ **406 Not Acceptable**.
  - `GET .../api/interpreter?data=...` with a descriptive UA ⇒ first attempt **504**
    `Error: runtime error: open64: 0 Success /osm3s_osm_base Dispatcher_Client::request_read_and_idx::timeout. The server is probably too busy to handle your request.`
  - Immediate retry ⇒ **200** with results. (Matches the documented load-shedding behaviour.)
  - Every Overpass response body carries: `The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.`
- Alternative public instances documented on the same wiki page (useful for a bike planner):
  `https://maps.mail.ru/osm/tools/overpass/api/interpreter` (VK Maps, `There are currently no requests limitations`),
  `https://overpass.private.coffee/api/interpreter` (ex Kumi, `there is no rate limit in place. Please notify us in advance if you intend to use our service in a large scale project.`),
  plus **paid** ones: Geofabrik `https://overpass.geofabrik.de/YOUR_API_KEY/api/interpreter` (`Payment required`),
  FairwayMapper, Tracestrack, Overspan (`Payment required`), NextGIS (free API key, no limits stated).
  Regional-only instances exist (Switzerland, Britain/Ireland, Virginia, Ethiopia).

### How to query the relations programmatically

- Overpass QL filter syntax is documented at https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL.
  The **exact** `relation["route"="bicycle"]` example is **NOT DOCUMENTED** on that page; the closest
  documented analogue is Example 9: `way.b["route"="hiking"] -> .hiking_routes;`
  (and Example 6 uses `rel(area.a)[route=bus];`).
- Verified working live (saved as `overpass-live-test-relation-route-bicycle.json`), request:
  `GET https://overpass-api.de/api/interpreter?data=[out:json][timeout:25];relation["route"="bicycle"](40.70,-74.02,40.80,-73.95);out tags 3;`
  returned e.g. relation 372471 `{"cycle_network":"US:NY","name":"NY Bike Route 9","network":"rcn","operator":"NYSDOT","ref":"9","route":"bicycle","type":"route"}`.
- Same for MTB (`overpass-live-test-relation-route-mtb.json`), request
  `relation["route"="mtb"](47.3,11.3,47.45,11.5);out tags 3;` returned e.g. relation 935991 with
  `mtb:difficulty=intermediate`, `ascent=490`, `distance=4.07`, `network=rcn`.
- Practical pattern: query the route relations, then expand to member ways and read the per-way tags
  (`surface`, `smoothness`, `tracktype`, `mtb:scale`, …) to build a per-segment score.

### Limits: max waypoints, max distance, other caps

- Overpass: no waypoint concept. Caps are `[timeout:]` (default **180 s**), `[maxsize:]`
  (default **512 MiB**), per-user slots, ~**10 000 req/day** and **<1 GB/day** for one-off use,
  **/100** of that for regular/production use, and 15 s enqueue. HTTP **429** (rate limit) and
  **504** (resource mismatch).
- Main OSM API: **2 download threads** max, valid User-Agent required, editing-oriented.
- Overpass has no documented hard distance cap; the practical cap is runtime/memory plus the
  guidance to use `planet.osm` / regional extracts (e.g. Geofabrik) instead of stitching bboxes.

### Self-hosting: Docker image (size if stated), OSM extract needed, effort

- Overpass API is open source: `license = GNU AGPL v3`, repo `https://github.com/drolbr/Overpass-API`
  (https://wiki.openstreetmap.org/wiki/Overpass_API, `version = 0.7.62.4`, `platform = Web;Linux;Docker`).
  A Docker image is published (`platform` lists Docker); **image size is NOT DOCUMENTED** on that page.
- Self-hosting is the project's stated remedy for heavy use:
  `Amongst the expressed goals of the Overpass API project is to make running your own instance really simple.`
  and `Heavy users easily can set up their own instance.`
- Needed data: an OSM extract/planet. Documented guidance: `set up your own data server, usually with planet.osm`
  (OWG API policy) and `Or download the regional dumps https://download.geofabrik.de and filter it using osmium`
  (Overpass wiki warning box). Installation instructions:
  `https://dev.overpass-api.de/overpass-doc/en/more_info/setup.html`.
  **Effort/time and disk requirement are NOT DOCUMENTED** on the pages fetched.

### Safety / traffic-stress score output?

**No.** OSM has no traffic-stress key and no LTS/BNA classification. What exists is
per-way physical/infrastructure/legal tagging (list above). Traffic-stress scoring is a *derived*
model each router implements (e.g. from `highway`, lanes, `maxspeed`, `cycleway*`), not an OSM field.
**NOT DOCUMENTED** in `https://wiki.openstreetmap.org/wiki/Key:highway`,
`https://wiki.openstreetmap.org/wiki/Key:bicycle`, `https://wiki.openstreetmap.org/wiki/Key:surface`,
or `https://wiki.openstreetmap.org/wiki/Key:smoothness`.

### Licence (ODbL) — exact obligations

https://www.openstreetmap.org/copyright, verbatim:

- `OpenStreetMap is open data, licensed under the Open Data Commons Open Database License (ODbL) by the OpenStreetMap Foundation (OSMF).`
- Attribution + share-alike: `You are free to copy, distribute, transmit and adapt our data, as long as you credit OpenStreetMap and its contributors. If you alter or build upon our data, you may distribute the result only under the same license.`
- The two required acts: `You are required to do the following two things: Provide credit to OpenStreetMap by displaying our attribution notice. Make clear that the data is available under the Open Database License.`
- `If you are distributing OSM in data form, please name and link directly to the license(s).`
- Wiki/documentation text is separate: `Our documentation is licensed under the Creative Commons Attribution-ShareAlike 2.0 license (CC BY-SA 2.0).`
- Overpass also stamps it in the response: `The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.`

### Sources

- https://wiki.openstreetmap.org/wiki/Relation:route
- https://wiki.openstreetmap.org/wiki/Key:route
- https://wiki.openstreetmap.org/wiki/Tag:route%3Dmtb
- https://wiki.openstreetmap.org/wiki/Cycle_routes
- https://wiki.openstreetmap.org/wiki/Key:mtb:scale
- https://wiki.openstreetmap.org/wiki/Key:mtb:scale:imba
- https://wiki.openstreetmap.org/wiki/Key:sac_scale
- https://wiki.openstreetmap.org/wiki/Key:trail_visibility
- https://wiki.openstreetmap.org/wiki/Key:tracktype
- https://wiki.openstreetmap.org/wiki/Tag:leisure%3Dtrack
- https://wiki.openstreetmap.org/wiki/Key:highway
- https://wiki.openstreetmap.org/wiki/Key:surface
- https://wiki.openstreetmap.org/wiki/Key:smoothness
- https://wiki.openstreetmap.org/wiki/Key:bicycle
- https://wiki.openstreetmap.org/wiki/Key:cycleway
- https://wiki.openstreetmap.org/wiki/Key:network
- https://wiki.openstreetmap.org/wiki/Key:ascent
- https://wiki.openstreetmap.org/wiki/Key:difficulty  (404 — page does not exist)
- https://wiki.openstreetmap.org/wiki/Key:mtb:difficulty  (404 — page does not exist)
- https://wiki.openstreetmap.org/wiki/Overpass_API
- https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL
- https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- https://operations.osmfoundation.org/policies/
- https://operations.osmfoundation.org/policies/api/
- https://operations.osmfoundation.org/policies/overpass/  (404 — no such policy)
- https://www.openstreetmap.org/copyright
- Live probes: `GET https://overpass-api.de/api/interpreter?data=...` (406 / 504 / 200 observed)

---

## Trailforks

Host: `https://www.trailforks.com`. Owner: **Outside** (Outside Interactive Inc.).
Direct fetches of most `trailforks.com` pages from this environment return **HTTP 403** with a
Cloudflare interstitial ("Sorry, you have been blocked"), so several official pages below were read
from Wayback Machine captures of the official URLs. Where that happened the URL is given as
`web.archive.org/web/<ts>/<official-url>` and this is flagged as a cached copy, not a live fetch.

### Bicycle profile & safety/traffic-stress modelling

- Trailforks publishes a **difficulty rating per trail** and a **popularity heatmap**, both as
  product features, not as documented API fields.
  Verbatim from Outside's own brand description
  (https://www.outsideinc.com/brands/ — live fetch):
  `Get the dirt with Trailforks, the go-to website and navigation app for mountain biking and all types of trail activities. Find new trails with the popularity heatmap, check the latest trail conditions, ride confidently…`
- The Trailforks site menu (from the official API page capture) lists
  `Route Planner`, `Route Finder`, `Route by Trails Finder`, `Heatmap`, `Reports`, `Ride Log`,
  `Ride Logs`, `Road Trips` — i.e. an interactive planner and a heatmap exist in the product.
  Verbatim from https://www.trailforks.com/about/api/ (capture 2024-11-12):
  `A JSON API is available upon approval to integrate Trailforks data into your application or website.`
- **No difficulty scale, no traffic-stress formula, no parameter names/defaults/ranges are
  documented publicly.** Difficulty scales are **NOT DOCUMENTED** at
  `https://www.trailforks.com/about/api/` or `https://www.trailforks.com/about/data/` (captures).
  Trailforks' own difficulty vocabulary (`green/blue/black`, `Easy/Intermediate/Difficult/…`) is
  **NOT DOCUMENTED** in any page fetched.

### Per-segment response attributes (can we score/explain safety?)

**UNCERTAIN: cannot verify.** The documented API documentation itself is behind a login:
verbatim (https://www.trailforks.com/about/api/, capture 2024-11-12):
`API Documentation: You must login to view the API documentation. All API requests are logged.`
and `For details on what various metadata ids mean reference this page.` (link target not verifiable —
`https://docs.trailforks.com/` is behind a Cloudflare JS challenge and returns
`Just a moment... Enable JavaScript and cookies to continue.`).
There is **no public field list** for trails, difficulty, or surface. UNCERTAIN.

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- Gating, verbatim from https://www.trailforks.com/about/api/ (capture 2024-11-12):
  - `A JSON API is available upon approval to integrate Trailforks data into your application or website. This access is available upon request but not guaranteed. Please fully explain the benefits your project will bring to Trailforks and its community in your request.`
  - `A valid "app_id" & "app_secret" is required to access the API. All requests to the API are logged.`
  - `Access is not guaranteed or usually granted to students and individuals for personal projects and websites.`
  - `We can also provide RSS feeds for most content updates and we provide a number of embeddable widgets.`
  - `API is under development, more methods to come and additional functions for POST & PUT.`
  - `We have OAuth2 setup allowing 3rd party app users to login to their Trailforks account and authorize the app to access their Trailforks data.`
  - POST auth: `timestamp = a unix timestamp`, `hash = SHA1(timestamp+token_private)`, plus `token_public`.
- Live probe of the API surface (this research, current):
  - `GET https://www.trailforks.com/api/0/regions/` ⇒ **400** `{"error":1,"message":"Required parameter of 'app_id' is missing or empty","data":null}`
  - `GET https://www.trailforks.com/api/0/regions/?app_id=test&key=test` ⇒ **400** `{"error":1,"message":"Required parameter of 'app_secret' is missing or empty","data":null}`
  - `GET https://www.trailforks.com/api/0/regions/?app_id=x&app_secret=y` ⇒ **401** `{"error":1,"message":"Invalid app_id","data":null}`
  - `GET https://www.trailforks.com/api/0/trails/?app_id=x&app_secret=y` ⇒ **401** `{"error":1,"message":"Invalid app_id","data":null}`
  - `GET https://www.trailforks.com/api/0/difficulties/?app_id=x&app_secret=y` ⇒ **405** `{"error":1,"message":"Invalid method","data":null}`
  - ⇒ Endpoint shape is `https://www.trailforks.com/api/0/<resource>/`, credential-gated by
    `app_id` + `app_secret`. Saved verbatim in `trailforks-api-probe.txt`.
- Free tier & pricing: **UNCERTAIN.** No API pricing page is reachable. Trailforks sells a consumer
  `Trailforks Pro` subscription (site nav: `Get Trailforks Pro`) and Outside sells `Powered by Outside+`
  (site header, from the captures). **No documented API price, quota, or free tier.**
- Rate limits: `All requests to the API are logged.` is the only documented control.
  **NOT DOCUMENTED:** numeric rate limits or quotas.
- Bulk export: **NOT DOCUMENTED.** No bulk-export endpoint or dataset is documented on
  `https://www.trailforks.com/about/api/` or `https://www.trailforks.com/about/data/`.

### Is scraping allowed by the ToS?

**No — explicitly prohibited.** Outside Terms of Use, as served at
https://www.trailforks.com/about/tou/ (capture 2025-09-11; page says `OUTSIDE® TERMS OF USE / Last Updated: March 4, 2025`),
verbatim prohibitions:

- `Use robots, spiders, scripts, service, software or any manual or automatic device, tool, or process designed to data mine or scrape Content available on the Services, data or information from the Services, or otherwise use, access, or collect content, data or information from the Services using automated means;`
- `Use automated means to submit content to the Services; or`
- `Use the Content for the development of any software program, including, but not limited to, training a machine learning or artificial intelligence (AI) system.`
- License scope: `we grant you a limited, revocable, personal, non-transferable and non-exclusive right and license to access and use the Services and the Outside Content for your own personal, noncommercial purposes, provided that you do not (and do not allow any third party to) copy, modify, creative derivative works, reverse engineer, sell, assign, sublease, transfer or otherwise commercially exploit any right in the Outside Services or Content.`

Supporting evidence — the official Data Use Policy
(https://www.trailforks.com/about/data/ , capture 2025-04-20), verbatim:

- `Use of Trailforks data is only allowed via the Trailforks API. You must be granted an access key to the API.`
- `Content that is restricted on the Trailforks website such as hidden trail gps tracks, cannot be made available.`
- `You can only use the Trailforks data via the Trailforks API.`
- `You can not copy Trailforks data for non personal use.`
- `You must attribute any use of the data to Trailforks, or works produced with the data.`
- `The following data policy does not apply to the Trailforks embeddable widgets or RSS feeds, they can be placed on webpages with advertising.`
- `This data policy is subject to change.`

`robots.txt` (live fetch, https://www.trailforks.com/robots.txt) is consistent and notably
disallows raw-track downloads:
`Disallow: /*/kml`, `Disallow: /*/gpx`, `Disallow: /*/rms`, `Disallow: /routeplan/*?`,
`Disallow: /ridelog/planner/*?`, `Disallow: /x_*`, with `Crawl-delay: 1` for `User-agent: *`,
and named crawl delays for `GPTBot` (1), `ClaudeBot` (1), `CCBot` (1), `PerplexityBot` (1), etc.

### Ownership

- Verbatim from https://www.trailforks.com/about/legal/ (capture 2026-01-31):
  `Trailforks and Pinkbike are now part of Outside! As of December 3, 2021, please refer to the Outside Terms of Use and Privacy Policy which govern your use of the Trailforks and Pinkbike websites, applications and services.`
  Footer on the same page: `Copyright © 2026. Outside Interactive Inc. All rights reserved.`
  (The 2024 API-page capture still said `Copyright © 2024. Trailforks Mapping Inc. All rights reserved.`)
- Live cross-check: https://www.outsideinc.com/brands/ lists Trailforks among its brands.

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits — summary

Gated/partner-only: `app_id` + `app_secret`, approval required, OAuth2 for user-level access,
docs behind login, no published price, no published quota.

### Limits: max waypoints, max distance, other caps

**NOT DOCUMENTED.** Nothing about waypoint counts, route length caps, or request caps appears on
`https://www.trailforks.com/about/api/` or `https://www.trailforks.com/about/data/` (captures), and
the API documentation is login-gated.

### Self-hosting: Docker image (size if stated), OSM extract needed, effort

**Not offered.** Trailforks is a closed SaaS product. No Docker image, no self-host option, and no
data-dump option is documented; the Data Use Policy in fact restricts data to the API only
(`You can only use the Trailforks data via the Trailforks API.`). **NOT DOCUMENTED.**

### Safety / traffic-stress score output?

**UNCERTAIN.** The product visibly has a `popularity heatmap` (quoted above from
https://www.outsideinc.com/brands/) and per-trail difficulty, but no field names, no score
definition, and no formula are publicly documented. No primary source found that defines a
Trailforks traffic-stress or safety score.

### Sources

- https://www.trailforks.com/api/  (403 Cloudflare from this environment)
- https://www.trailforks.com/about/api/  (403 live; capture: https://web.archive.org/web/20241112190443/https://www.trailforks.com/about/api/)
- https://www.trailforks.com/about/data/  (403 live; capture: https://web.archive.org/web/20250420220620/https://www.trailforks.com/about/data/)
- https://www.trailforks.com/about/tou/  (403 live; capture: https://web.archive.org/web/20250911205156/https://www.trailforks.com/about/tou/)
- https://www.trailforks.com/about/legal/  (403 live; capture: https://web.archive.org/web/20260131211947/https://www.trailforks.com/about/legal/)
- https://www.trailforks.com/robots.txt  (live 200)
- https://docs.trailforks.com/  (Cloudflare JS challenge — content not verifiable)
- https://www.outsideinc.com/brands/
- Live probes: `GET https://www.trailforks.com/api/0/regions/`, `.../api/0/trails/`, `.../api/0/difficulties/`

---

## MTB Project (Adventure Projects / onX)

### API status — the API was deprecated, and no new keys are issued

Verbatim from the official API page, live fetch of **https://www.mtbproject.com/data**:

```
MTB Project Data API

Thanks for your interest in the Adventure Projects API. In late 2020, Adventure Projects
deprecated public access and use of the Data API across all of the projects. In December 2020,
Adventure Projects was acquired by onX. onX will continue Adventure Projects' policy, and for
the foreseeable future will continue to decline new requests to the Data API.
```

Footer on the same page: `© 2026 onX Maps, Inc.`

- The **endpoint is still live and still enforces a key**. Live probe:
  `GET https://www.mtbproject.com/data/get-trails?lat=40.0274&lon=-105.2519&maxDistance=10&key=test`
  ⇒ **403** `{"success": 0, "message": "Invalid Key"}`. Saved as
  `mtbp-www-mtbproject-com-data-get-trails-lat-40-0274-lon-105-2519-maxDistanc.txt`.
  So the classic `get-trails` route still exists and rejects invalid keys; valid keys are no longer
  issued per the statement above.
- `https://www.mtbproject.com/api` (no sub-path) returns `{"error":1,"message":"Action not understood: ","user_message":""}`.
  `https://www.mtbproject.com/api/v1/get-trails` returns **404**. `https://www.mtbproject.com/tos`
  and `https://www.mtbproject.com/legal` return **404**.
- **"Replaced by AllTrails": UNCERTAIN — no primary source found.** The documented replacement of the
  *company* is onX Maps, Inc. (quoted above). No AllTrails page or statement about MTB Project was
  found to verify the AllTrails claim.
- Same statement is served on sibling projects: `https://www.hikingproject.com/data` returns the
  same `Data API` shell (saved `mtbp-www-hikingproject-com-data.txt`).

### Bicycle profile & safety/traffic-stress modelling

**UNCERTAIN / NOT DOCUMENTED.** No public API, hence no documented profile. The consumer product
shows difficulty labels — the home page lists routes tagged `Easy/Intermediate`, `Intermediate/Difficult`,
`Difficult`, `Very Difficult` (live fetch of https://www.mtbproject.com/) — but no scale definition,
parameter names, defaults or ranges are documented on any page reachable without an account.

### Per-segment response attributes (can we score/explain safety?)

**UNCERTAIN.** No accessible field list. The deprecated `get-trails` response schema is no longer
documented at `https://www.mtbproject.com/data` (that page now only carries the deprecation notice).

### Hosted API: endpoint, auth/key, free tier & pricing, rate limits

- Legacy endpoint family: `https://www.mtbproject.com/data/get-trails` with a `key` query param.
- **No keys issued** (`onX will continue Adventure Projects' policy, and for the foreseeable future
  will continue to decline new requests to the Data API.`).
- Free tier / pricing / rate limits: **NOT DOCUMENTED** — n/a because access is closed.

### Limits: max waypoints, max distance, other caps

**NOT DOCUMENTED.** The legacy `get-trails` params (`lat`, `lon`, `maxDistance`, `key`) survive in
the URL that still answers, but no schema or cap page exists at the reachable URLs.

### Self-hosting / Safety-score / Licence

- Self-hosting: **not offered** (closed SaaS, no repo, no image documented).
- Safety or traffic-stress score: **UNCERTAIN** — no primary source.
- Licence / redistribution: **NOT DOCUMENTED** at reachable URLs (`/tos` and `/legal` both 404);
  the deprecation notice plus the ToU click-through is the only statement found.
  Note the community-content warning banner rendered site-wide:
  `Taking other people's content (text, photos, etc) without permission is a copyright violation and NOT OKAY!`

### Sources

- https://www.mtbproject.com/data
- https://www.mtbproject.com/
- https://www.mtbproject.com/about
- https://www.hikingproject.com/data
- Live probes: `GET https://www.mtbproject.com/data/get-trails?...&key=test` (403 Invalid Key);
  `GET https://www.mtbproject.com/api`; `GET https://www.mtbproject.com/api/v1/get-trails` (404);
  `GET https://www.mtbproject.com/tos` (404); `GET https://www.mtbproject.com/legal` (404)

---

## Waymarked Trails (OSM-based, queryable)

Free OSM-based route viewer for cycling / MTB / hiking / riding / skating / slopes.
Root: https://waymarkedtrails.org/ (live 200), themes at
`https://cycling.waymarkedtrails.org`, `https://mtb.waymarkedtrails.org`, etc.

### Is there an API?

**Yes, a JSON API exists and works, but it is NOT DOCUMENTED at a public docs URL.**

- The API base is visible in the site's own client bundle
  (`https://cycling.waymarkedtrails.org/build/help-CXrFrkaH.js`), verbatim:
  `Lo="https://{theme}.waymarkedtrails.org/api/v1".replace("{theme}",To)`
  ⇒ base for cycling is `https://cycling.waymarkedtrails.org/api/v1`.
- Documented-URL check: `https://waymarkedtrails.org/help/` ⇒ **404**,
  `https://waymarkedtrails.org/api/` ⇒ **404**, `https://cycling.waymarkedtrails.org/help/en` ⇒ **404**,
  `https://cycling.waymarkedtrails.org/api/v1/` ⇒ **404** `{"title": "404 Not Found"}`.
  ⇒ **NOT DOCUMENTED** at those URLs; the endpoint names below were recovered from the client JS and
  confirmed by live probes.
- Live-probed endpoints (all **200**, `application/json`):
  - `GET https://cycling.waymarkedtrails.org/api/v1/list/search?query=EuroVelo`
    ⇒ `{"query":"EuroVelo","page":1,"results":[{"type":"relation","id":6641108,"ref":"EV1","name":"EuroVelo 1","group":"INT","linear":"yes","itinerary":["Waterford","Dungarvan"],"symbol_id":"ctb_INT_afbe24_004500560031"},…]}`
  - `GET https://cycling.waymarkedtrails.org/api/v1/list/by_ids?relations=2820053`
    ⇒ `{"relations":[2820053],"results":[{"type":"relation","id":2820053,"ref":"AV","name":"Avenue Verte London ↔ Paris","group":"INT","linear":"no","symbol_id":"ctb_INT_green_00410056"}]}`
  - `GET https://cycling.waymarkedtrails.org/api/v1/list/by_area?bbox=8.5,47.3,8.6,47.4&limit=2`
    ⇒ `{"bbox":[8.50000000,47.30000000,8.60000000,47.40000000],"results":[]}`
  - Same base exists for MTB: `https://mtb.waymarkedtrails.org/api/v1/list/by_area?bbox=…`
  - `GET https://cycling.waymarkedtrails.org/api/v1/route/2820053` ⇒ **404** (no such route path).
- Returned per-route fields seen live: `type`, `id`, `ref`, `name`, `group` (`INT`/`NAT`/`REG`/`""`/`NDS`),
  `linear` (`yes`/`no`), `itinerary` (array of place names), `symbol_id`.
  `ROUTE_GROUPS` in the page config maps `INT`→`network.continental`, `NAT`→`network.national`,
  `REG`→`network.regional`, `""`→`network.other`, `NDS`→`network.node`.
- Auth/key/rate limits/free tier: **none observed** — no key was sent and no `429` occurred.
  **NOT DOCUMENTED** anywhere (no policy page found).
- Exports: the client builds GPX/KML/GeoJSON downloads in-browser from OSM data and stamps them, verbatim:
  `n.setAttribute("creator","waymarkedtrails.org")` …
  `m(r,"copyright","license","https://www.openstreetmap.org/copyright").setAttribute("author","OpenStreetMap and Contributors")`
  and for GeoJSON `license:"https://www.openstreetmap.org/copyright"`.
  ⇒ **data is ODbL / OSM**, i.e. reusable under ODbL attribution + share-alike.
- Tiles: `https://tile.waymarkedtrails.org/{theme}/{z}/{x}/{y}.png`; hillshade
  `https://hillshading.waymarkedtrails.org/srtm/{z}/{x}/{-y}.png`.
- Self-hosting: the front end is open source, but **NOT VERIFIED** in this pass (no repo URL fetched).
  Mark self-hosting/effort **UNCERTAIN**.
- Safety / traffic-stress score: **No.** Only route grouping (`INT/NAT/REG/other/node`) and geometry.
  **NOT DOCUMENTED** as a score.

### Sources

- https://waymarkedtrails.org/
- https://cycling.waymarkedtrails.org/
- https://mtb.waymarkedtrails.org/
- https://cycling.waymarkedtrails.org/build/main.js
- https://cycling.waymarkedtrails.org/build/help-CXrFrkaH.js
- Live probes: `…/api/v1/list/search`, `…/api/v1/list/by_ids`, `…/api/v1/list/by_area`
- 404 checks: https://waymarkedtrails.org/help/, https://waymarkedtrails.org/api/,
  https://cycling.waymarkedtrails.org/api/v1/

---

## OpenMTBMap / VeloMap (Garmin maps from OSM)

### What it is, and licence

- https://openmtbmap.org/ — `Routable Outdoor Maps for Garmin GPS Devices`, built from OSM.
  Free (ODbL) downloads at https://openmtbmap.org/download/odbl/ plus paid/donation-gated
  "Premium" downloads (`Become a Premium Member`, `Europe Map Premium Download`,
  `Please support Openmtbmap by donating a few bucks`).
- Licence, verbatim footer
  (https://openmtbmap.org/about-2/, live fetch):
  `Copyright © 2026 Openmtbmap.org - Some Rights Reserved - All map screenhots/pictures shown on this page are, if not noted otherwise, published under CC-BY-SA-2.0 by OpenMTBMap Using Data from Openstreeetmap & Contributors published under ODBL.`
- Update cadence and OSM dependence, verbatim (https://openmtbmap.org/):
  `I update the maps weekly, so every week the maps become better and more complete!`
  and `Contourlines integrated nearly worldwide based on the excellent viewfinderpanoramas.org data.`

### Bicycle profile & safety/traffic-stress modelling

- The maps carry MTB/hiking/cycle-relevant OSM attributes and are auto-routable, but there is **no
  published profile definition** (no weight table, no parameter names/defaults). Verbatim
  (https://openmtbmap.org/about-2/, live fetch):
  `Autorouting needs attributes like tracktype, bicycle routes, mtb:scale, sac_scale, mtb routes and others to work well. So keep care to enter those details into OSM.`
  and `Clear differentiation between all street types. The maps display info out of the eyes of a biker / hiker. E.g. downhill arrows for steep singletracks`.
- Safety/traffic-stress score: **No.** **NOT DOCUMENTED** on https://openmtbmap.org/ or its
  download/about pages.

### Queryable API?

**No API.** It is a downloadable map product (Windows/Linux installers, `gmapsupp.img` for Garmin,
macOS premium), not a service. **NOT DOCUMENTED** as having an API.

### Sources

- https://openmtbmap.org/
- https://openmtbmap.org/download/odbl/
- https://openmtbmap.org/about-2/

---

## Availability summary: what is AVAILABLE vs GATED

| Source | Data | Access | Licence / reuse | Query API | Verdict |
|---|---|---|---|---|---|
| **RideWithGPS** | User routes/trips, per-point surface+highway ints, surface/unpaved_pct/terrain/difficulty | API key + user OAuth or Basic token; **no paid plan needed** | API ToS: commercial use allowed, `No attribution to Ride with GPS is required.`; **redistribution NOT DOCUMENTED** | REST JSON, key required; no routing/plan endpoint; index = own routes only | **AVAILABLE but no planner & no public-route search** (401 unauth) |
| **RideWithGPS planner** | safety-aware cycling routing, Paved/Unpaved/Any, Global Heatmap, Explore Nearby Routes, Route Insights | Premium subscription + web UI only | proprietary | none | **GATED (UI only)** |
| **OSM tagging** | bicycle/mtb route relations, `mtb:scale`, `sac_scale`, `tracktype`, `surface`, `smoothness`, `trail_visibility`, `cycleway*`, `network` | open | **ODbL** (attribution + share-alike) | Overpass QL | **AVAILABLE** |
| **Overpass (public)** | read-only OSM query | no key | ODbL; policy: ~10k req/day, <1 GB/day, /100 for regular use | `…/api/interpreter` | **AVAILABLE but rate-limited**; commercial use → self-host or paid |
| **Overpass (paid/self-host)** | same, higher quota | key or own server | ODbL; AGPLv3 software | same | **AVAILABLE (paid/self-host)** |
| **Trailforks** | trails, difficulty, reports, popularity heatmap | `app_id`+`app_secret`, approval required, OAuth2; docs behind login | Data Use Policy: API only, attribution required, `You can not copy Trailforks data for non personal use.`; ToU forbids scraping | `/api/0/<resource>/`, credential-gated | **GATED (partner-only)** |
| **MTB Project** | trails/routes | legacy `key` param, **no new keys issued** | deprecation notice; ToS page 404 | `/data/get-trails` still answers, rejects bad keys | **GATED / effectively CLOSED** |
| **Waymarked Trails** | OSM route relations, per-theme | **no key**, free | ODbL (explicit in exported files) | `/api/v1/list/search`, `/list/by_ids`, `/list/by_area` — undocumented but live | **AVAILABLE (undocumented API)** |
| **OpenMTBMap / VeloMap** | Garmin map downloads from OSM | free ODbL download; premium paid | ODbL + CC-BY-SA-2.0 for imagery | none | **AVAILABLE as downloads; no API** |

---

## Cross-engine synthesis

### Which engines actually model low-traffic / bike-lane preference?

All five open engines do, in different mechanisms:

| Engine | Mechanism | Exact names |
|---|---|---|
| Valhalla | Numeric costing knobs + internal "roadway stress" cost | `use_roads`, `avoid_bad_surfaces`, `use_hills`, `use_living_streets`, `bicycle_type`, `cycling_speed`, `use_ferry` |
| BRouter | Profile scripts; a whole family of low-traffic profiles | `fastbike-lowtraffic.brf`, `fastbike-verylowtraffic.brf`, `racebike-verylowtraffic.brf`, `safety.brf`, `trekking.brf`; tunable `consider_traffic`, `avoid_unsafe` |
| OpenRouteService | Avoid-list + profile weightings | `options.avoid_features` (`ferries`, `steps`, `fords` for cycling), `options.profile_params.weightings.steepness_difficulty` |
| GraphHopper | Custom models over OSM-derived encoded values | `bike.json` custom model with `bike_priority`, `bike_network`, `mtb_rating`, `hike_rating`, `road_class` |
| CycleStreets | Four route types; "quietest" is traffic-driven | `plan=balanced\|fastest\|quietest\|shortest` |

RideWithGPS: see §RideWithGPS.

### Which engines return per-segment attributes usable for our own safety score?

| Engine | Request parameter | Fields we can score on |
|---|---|---|
| **Valhalla** | `/trace_attributes` + `filters.attributes` | `edge.cycle_lane`, `edge.road_class`, `edge.speed_limit`, `edge.surface`, `edge.shoulder`, `edge.sidewalk`, `edge.bicycle_network`, `edge.sac_scale`, `edge.use`, `edge.traversability`, `edge.weighted_grade` |
| **BRouter** | `format=geojson` (or `csv`) → `messages` | 13 columns ending in `WayTags` / `NodeTags` — the raw OSM tags, incl. `estimated_traffic_class` (1–7) and `estimated_crossing_class` (0–6) |
| **OpenRouteService** | `extra_info=[...]` | `steepness`, `suitability` (1–10), `surface`, `waycategory`, `waytype`, `traildifficulty` |
| **GraphHopper** | `details=...` | `road_class`, `road_environment`, `road_access`, `surface`, `smoothness`, `hike_rating`, `mtb_rating`, `bike_network`, `max_speed` |
| **CycleStreets** | `segments=1` | `provisionName`, `busynance`, `walk`, `signalledJunctions`, `signalledCrossings`, `elevations`; route-level `quietness` (0–100) |

### Does any engine output an explicit "safety" or "traffic stress" score?

**No engine outputs a safety or traffic-stress score.** Verified per engine in each section above.
The closest things that exist:

* **CycleStreets** — route-level `quietness` (0–100) and `busynance` (metres). The routing doc defines it as
  "an estimate of how busy a section is for cycling… Trunk roads and primary roads are considered very hostile",
  with the explicit caveat "These figures are subjective".
* **OpenRouteService** — `suitability` 1–10, but the docs define it as "How suitable the way is based on
  characteristics of the route and the profile", and the source shows it is the routing *priority* normalised to
  3..10 — a preference, not exposure.
* **BRouter** — `CostPerKm` per segment is `costfactor * 1000`, the profile's own cost multiplier, not a stress index.
* **GraphHopper** — a per-edge `priority` factor used in `edge_weight`; not returned by the hosted API.
* **Valhalla** — computes "roadway stress" internally but exposes no numeric stress field.

**Consequence for our app:** we must compute the safety score ourselves. The inputs exist
(§1.2). This also matches the standard research model: **Bicycle Level of Traffic Stress (LTS)**.
The canonical primary source is the Mineta Transportation Institute report *Low-Stress Bicycling and Network
Connectivity* (Mekuria, Furth, Nixon, 2012), PDF at
<https://transweb.sjsu.edu/sites/default/files/1005-low-stress-bicycling-network-connectivity.pdf> (HTTP 200;
`%PDF-1.6`, 2.5 MB). **UNCERTAIN:** no verbatim quote — the PDF text could not be extracted with the tools
available in this environment, so the LTS 1–4 criteria are *not* verified here and must be read from the PDF
before being implemented.

### How can an Android app call each engine?

| Engine | Android integration path | Cost / key |
|---|---|---|
| **BRouter** | **On-device, offline, no network.** Two ways: (a) inter-app AIDL bound service `IBRouterService.getTrackFromParams(Bundle)` on `btools.routingapp` (`android:exported="true"`, separate process `:brouter_service`); (b) our own HTTP call to any BRouter server. Or embed the Java engine directly (it is MIT-licensed, Java 17). | Free, MIT |
| **Valhalla** | HTTPS to `https://valhalla1.openstreetmap.de` (free, no key, 10 waypoints / 150 km) or a self-hosted instance. No official Android SDK — Python and Node.js bindings only. | Free / self-host |
| **OpenRouteService** | HTTPS to `https://api.openrouteservice.org/v2/directions/{profile}/geojson`. Note the vendor's own rule: "every HeiGIT API key belongs to one person. Thus, an API key must not be used client-side in an application" → proxy through our backend. | Key required |
| **GraphHopper** | HTTPS to `https://graphhopper.com/api/1/route?key=...`. Safe on-device only if the key is restricted; free plan forbids the custom-model mode. | Key required |
| **CycleStreets** | HTTPS to `https://api.cyclestreets.net`. Key required; SLA expectations for sat-nav style apps. | Key required |
| **RideWithGPS** | HTTPS to `https://ridewithgps.com/api/v1`. Key/OAuth required. | Key required |

**Practical ranking for an offline-first Android bike planner.** BRouter is the only engine in this set that
runs fully on the device today: MIT licence, Java, and downloadable `.rd5` segment tiles. Valhalla can be
self-hosted next to the app but needs a server. ORS, GraphHopper and CycleStreets are hosted-API-only for
practical purposes (and ORS/GraphHopper forbid or discourage client-side keys).

---

---

## Appendix — source URL index and how to verify

This document cites **379 unique primary-source URLs**. Each engine section also ends with its own
`### Sources` list. Raw captures of every page used (HTML, JSON, YAML, `.brf`, `.json` samples, live
probe responses) are stored under `docs/research/raw/`, with per-topic manifests at
`docs/research/raw/ors-cyclestreets-sources.txt`, `docs/research/raw/graphhopper-sources.txt`,
`docs/research/raw/brouter-SOURCES.txt` and `docs/research/raw/rwgps-trails-sources.txt`.

Live probes were made on 2026-09-15 against Valhalla, GraphHopper, ORS, CycleStreets, BRouter and Waymarked Trails.

```
http://brouter.de/brouter/segments4/
http://download.geofabrik.de
http://download.geofabrik.de/europe/germany/berlin-latest.osm.pbf
http://localhost:{port}{basePath}
http://ridewithgps.com/api/v1
https://account.heigit.org
https://account.heigit.org/chunk-3FIDLIS3.js
https://adoptium.net
https://api.cyclestreets.net
https://api.cyclestreets.net/v2/journey.plan?
https://api.cyclestreets.net/v2/status
https://api.github.com/orgs/cyclestreets/repos
https://api.github.com/repos/abrensch/brouter
https://api.github.com/repos/abrensch/brouter/git/trees/master?recursive=1
https://api.github.com/repos/abrensch/brouter/tags?per_page=100
https://api.github.com/repos/graphhopper/graphhopper/contents/core/src/main/resources/com/graphhopper/custom_models
https://api.github.com/repos/graphhopper/graphhopper/git/trees/master?recursive=1
https://api.heigit.org
https://api.heigit.org/openrouteservice/v2/directions/cycling-road/geojson
https://api.openrouteservice.org
https://api.openrouteservice.org/v2/directions/cycling-road/geojson
https://api.openrouteservice.org/v2/directions/{profile}/geojson
https://api.openstreetmap.org/api/0.6/
https://api.stadiamaps.com/route/v1?api_key=YOUR-API-KEY
https://brouter.damsy.net
https://brouter.de/
https://brouter.de/brouter
https://brouter.de/brouter-api.html
https://brouter.de/brouter-web/config.js
https://brouter.de/brouter/
https://brouter.de/brouter/algorithm.html
https://brouter.de/brouter/alternatives.html
https://brouter.de/brouter/costfunctions.html
https://brouter.de/brouter/cycleroutes.html
https://brouter.de/brouter/elevation.html
https://brouter.de/brouter/index.html
https://brouter.de/brouter/offline.html
https://brouter.de/brouter/online.html
https://brouter.de/brouter/profile
https://brouter.de/brouter/profile_developers_guide.txt
https://brouter.de/brouter/profiles2/
https://brouter.de/brouter/profiles2/:
https://brouter.de/brouter/profiles2/car-eco.brf
https://brouter.de/brouter/profiles2/fastbike-verylowtraffic.brf
https://brouter.de/brouter/profiles2/fastbike.brf
https://brouter.de/brouter/profiles2/hiking-beta.brf
https://brouter.de/brouter/profiles2/moped.brf
https://brouter.de/brouter/profiles2/readme.txt
https://brouter.de/brouter/profiles2/routino.brf
https://brouter.de/brouter/profiles2/safety.brf
https://brouter.de/brouter/profiles2/shortest.brf
https://brouter.de/brouter/profiles2/softaccess.brf
https://brouter.de/brouter/profiles2/trekking.brf
https://brouter.de/brouter/profiles2/vmforge.brf
https://brouter.de/brouter/profiles2/{routino
https://brouter.de/brouter/profiles2/{trekking
https://brouter.de/brouter/readme.txt
https://brouter.de/brouter/revisions.html
https://brouter.de/brouter/segments4/
https://brouter.de/brouter/segments4/diff/
https://brouter.de/brouter/segments4/segments.json
https://brouter.de/brouter/segments4/storageconfig.txt
https://brouter.de/brouter/vianogo.html
https://brouter.de/brouter/vianogo.html:
https://brouter.de/brouter?lonlats=...&profile=trekking&format=geojson
https://brouter.de/brouter?lonlats=8.799297
https://brouter.de/brouter?lonlats=<200
https://brouter.de/brouter?lonlats=<60
https://brouter.de/privacypolicy.html
https://brouter.m11n.de
https://cycling.waymarkedtrails.org
https://cycling.waymarkedtrails.org/
https://cycling.waymarkedtrails.org/api/v1
https://cycling.waymarkedtrails.org/api/v1/
https://cycling.waymarkedtrails.org/api/v1/list/by_area?bbox=8.5
https://cycling.waymarkedtrails.org/api/v1/list/by_ids?relations=2820053
https://cycling.waymarkedtrails.org/api/v1/list/search?query=EuroVelo
https://cycling.waymarkedtrails.org/api/v1/route/2820053
https://cycling.waymarkedtrails.org/build/help-CXrFrkaH.js
https://cycling.waymarkedtrails.org/build/main.js
https://cycling.waymarkedtrails.org/help/en
https://dev.overpass-api.de/overpass-doc/en/more_info/setup.html
https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
https://docs.graphhopper.com/
https://docs.graphhopper.com/_bundle/openapi.yaml
https://docs.graphhopper.com/_bundle/openapi.yaml**:
https://docs.graphhopper.com/llms.txt
https://docs.graphhopper.com/openapi.md
https://docs.graphhopper.com/openapi.md**:
https://docs.graphhopper.com/openapi/routing/getroute.md
https://docs.graphhopper.com/openapi/routing/postroute.md
https://docs.openrouteservice.org/all/docs
https://docs.stadiamaps.com/routing/standard-routing/
https://docs.trailforks.com/
https://download.geofabrik.de
https://download.geofabrik.de/
https://download.geofabrik.de/europe/andorra-latest.osm.pbf
https://f-droid.org/packages/btools.routingapp
https://ghcr.io/v2/abrensch/brouter/manifests/latest
https://ghcr.io/v2/abrensch/brouter/tags/list
https://ghcr.io/v2/graphhopper/graphhopper/tags/list
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/custom-models
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/road-access-restrictions
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/steepness
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/trail-difficulty
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/extra-info/waycategory
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/requests-and-return-types
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/route-attributes
https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/routing-options
https://giscience.github.io/openrouteservice/frequently-asked-questions
https://giscience.github.io/openrouteservice/getting-started
https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/
https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/build
https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/repo
https://giscience.github.io/openrouteservice/run-instance/configuration/engine/profiles/service
https://giscience.github.io/openrouteservice/run-instance/data
https://giscience.github.io/openrouteservice/run-instance/running-with-docker
https://giscience.github.io/openrouteservice/run-instance/system-requirements
https://giscience.github.io/openrouteservice/technical-details/graph-repo-client
https://giscience.github.io/openrouteservice/technical-details/graph-repo-client/
https://giscience.github.io/openrouteservice/technical-details/tag-filtering
https://giscience.github.io/openrouteservice/technical-details/travel-speeds/waytype-speeds/
https://github.com/GIScience/openrouteservice/releases/latest/download/docker-compose.yml
https://github.com/IsraelHikingMap/graphhopper-docker-image-push
https://github.com/abrensch/brouter
https://github.com/abrensch/brouter/blob/master/Dockerfile
https://github.com/abrensch/brouter/blob/master/README.md
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/FormatGpx.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/FormatJson.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/Formatter.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/MessageData.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/OsmTrack.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/RoutingContext.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/RoutingEngine.java
https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/RoutingParamCollector.java
https://github.com/abrensch/brouter/blob/master/brouter-expressions/src/main/java/btools/expressions/BExpression.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/build.gradle
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/AndroidManifest.xml
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/assets/serverconfig.txt
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BInstallerActivity.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BInstallerSizes.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BInstallerView.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BRouterActivity.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BRouterService.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/BRouterWorker.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/DownloadWorker.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/RoutingParameterDialog.java
https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/java/btools/routingapp/ServerConfig.java
https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/IpAccessMonitor.java
https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/RouteServer.java
https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/request/ProfileUploadHandler.java
https://github.com/abrensch/brouter/blob/master/brouter-server/src/main/java/btools/server/request/ServerHandler.java
https://github.com/abrensch/brouter/blob/master/docs/developers/algorithm.md
https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md
https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md:
https://github.com/abrensch/brouter/blob/master/docs/developers/build_segments.md
https://github.com/abrensch/brouter/blob/master/docs/developers/developer_guide.md
https://github.com/abrensch/brouter/blob/master/docs/developers/docker_guide.md
https://github.com/abrensch/brouter/blob/master/docs/developers/environmental_considerations_and_pseudo_tags.md
https://github.com/abrensch/brouter/blob/master/docs/developers/environmental_considerations_and_pseudo_tags.md:
https://github.com/abrensch/brouter/blob/master/docs/developers/http_server.md
https://github.com/abrensch/brouter/blob/master/docs/developers/profile_developers_guide.md
https://github.com/abrensch/brouter/blob/master/docs/developers/pseudo_nodes.md
https://github.com/abrensch/brouter/blob/master/docs/features/costfunctions.md
https://github.com/abrensch/brouter/blob/master/docs/revisions.md
https://github.com/abrensch/brouter/blob/master/docs/users/android_advanced.md
https://github.com/abrensch/brouter/blob/master/docs/users/android_quickstart.md
https://github.com/abrensch/brouter/blob/master/docs/users/download_segments.md
https://github.com/abrensch/brouter/blob/master/docs/users/osmand.md
https://github.com/abrensch/brouter/blob/master/gradle.properties
https://github.com/abrensch/brouter/blob/master/misc/profiles2/lookups.dat
https://github.com/abrensch/brouter/blob/master/misc/profiles2/readme.txt
https://github.com/abrensch/brouter/blob/master/misc/scripts/standalone/server.sh
https://github.com/abrensch/brouter/blob/v1.6.3/brouter-routing-app/src/main/AndroidManifest.xml
https://github.com/bagage
https://github.com/cyclestreets
https://github.com/cyclestreets/cyclestreets-setup
https://github.com/drolbr/Overpass-API
https://github.com/nrenner/brouter-web
https://github.com/poutnikl/Brouter-profiles
https://github.com/valhalla/valhalla/blob/master/docker/README.md
https://github.com/valhalla/valhalla/blob/master/scripts/valhalla_build_config
https://github.com/valhalla/valhalla/blob/master/src/sif/bicyclecost.cc
https://github.com/valhalla/valhalla/blob/master/valhalla/baldr/graphconstants.h
https://graphhopper.com/api/1
https://graphhopper.com/api/1/route
https://graphhopper.com/api/1/route?key=
https://graphhopper.com/api/1/route?point=52.5
https://graphhopper.com/dashboard/signup
https://graphhopper.com/public/schema/gpx/1.1\
https://hillshading.waymarkedtrails.org/srtm/{z}/{x}/{-y}.png
https://hub.docker.com/r/israelhikingmap/graphhopper
https://hub.docker.com/v2/repositories/graphhopper/graphhopper/
https://hub.docker.com/v2/repositories/israelhikingmap/graphhopper/tags?page_size=5
https://hub.docker.com/v2/repositories/openrouteservice/openrouteservice/tags/
https://maps.mail.ru/osm/tools/overpass/api/interpreter
https://mtb.waymarkedtrails.org
https://mtb.waymarkedtrails.org/
https://mtb.waymarkedtrails.org/api/v1/list/by_area?bbox=…
https://openmtbmap.org/
https://openmtbmap.org/about-2/
https://openmtbmap.org/download/odbl/
https://openrouteservice.org/plans/
https://openrouteservice.org/restrictions/
https://openrouteservice.org/services/
https://operations.osmfoundation.org/policies/
https://operations.osmfoundation.org/policies/api/
https://operations.osmfoundation.org/policies/overpass/
https://overpass-api.de/api/interpreter
https://overpass-api.de/api/interpreter?data=
https://overpass-api.de/api/interpreter?data=[out:json
https://overpass.geofabrik.de/YOUR_API_KEY/api/interpreter
https://overpass.private.coffee/api/interpreter
https://planet.osm.org/
https://play.google.com/store/apps/details?id=btools.routingapp
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/APIEnums.java
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/requests/routing/RequestProfileParamsWeightings.java
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/java/org/heigit/ors/api/requests/routing/RouteRequest.java
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-api/src/main/resources/application.yml
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/RouteExtraInfoFlag.java
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/graphhopper/extensions/flagencoders/bike/CommonBikeFlagEncoder.java
https://raw.githubusercontent.com/GIScience/openrouteservice/main/ors-engine/src/main/java/org/heigit/ors/routing/pathprocessors/ExtraInfoProcessor.java
https://raw.githubusercontent.com/abrensch/brouter/master/
https://raw.githubusercontent.com/cyclestreets/cyclestreets-setup/master/README.md
https://raw.githubusercontent.com/graphhopper/graphhopper/11.x/config-example.yml
https://raw.githubusercontent.com/graphhopper/graphhopper/master/LICENSE.txt
https://raw.githubusercontent.com/graphhopper/graphhopper/master/NOTICE.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/README.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/config-example.yml
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/ev/BikeRoadAccess.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/ev/DefaultImportRegistry.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/ev/GetOffBike.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/PriorityCode.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/parsers/BikeCommonPriorityParser.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/parsers/BikePriorityParser.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/java/com/graphhopper/routing/util/parsers/OSMBikeNetworkTagParser.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike_avoid_turns.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike_elevation.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/bike_tc.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/cargo_bike.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/mtb.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/core/src/main/resources/com/graphhopper/custom_models/racingbike.json
https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/custom-models.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/deploy.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/elevation.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/profiles.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/core/technical.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/docs/web/api-doc.md
https://raw.githubusercontent.com/graphhopper/graphhopper/master/pom.xml
https://raw.githubusercontent.com/graphhopper/graphhopper/master/web-bundle/src/main/java/com/graphhopper/gpx/GpxConversions.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/web-bundle/src/main/java/com/graphhopper/http/TypeGPXFilter.java
https://raw.githubusercontent.com/graphhopper/graphhopper/master/web-bundle/src/main/java/com/graphhopper/resources/RouteResource.java
https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.0/graphhopper-web-11.0.jar
https://ridewithgps.com
https://ridewithgps.com/api
https://ridewithgps.com/api/v1
https://ridewithgps.com/api/v1/auth_tokens.json
https://ridewithgps.com/api/v1/doc
https://ridewithgps.com/api/v1/doc/authentication
https://ridewithgps.com/api/v1/doc/change_log
https://ridewithgps.com/api/v1/doc/reference/activity_types
https://ridewithgps.com/api/v1/doc/reference/points_of_interest
https://ridewithgps.com/api/v1/doc/reference/routes_and_trips
https://ridewithgps.com/api/v1/doc/reference/track_points
https://ridewithgps.com/api/v1/doc/reference/visibilities
https://ridewithgps.com/api/v1/doc/terms
https://ridewithgps.com/api/v1/doc/webhooks
https://ridewithgps.com/api/v1/openapi.yaml
https://ridewithgps.com/api/v1/routes.json
https://ridewithgps.com/api/v1/sync.json?since=1970-01-01
https://ridewithgps.com/oauth/authorize?client_id=..&redirect_uri=..&response_type=code
https://ridewithgps.com/oauth/revoke.json
https://ridewithgps.com/oauth/token.json
https://ridewithgps.com/pricing
https://ridewithgps.com/terms
https://stadiamaps.com/pricing/
https://support.ridewithgps.com/hc/en-us/articles/15171358082075-Explore-Nearby-Routes
https://support.ridewithgps.com/hc/en-us/articles/36795897776411-Waypoints
https://support.ridewithgps.com/hc/en-us/articles/4415462488475-Route-Planning-101
https://support.ridewithgps.com/hc/en-us/articles/4415470200859-Advanced-Route-Planning
https://support.ridewithgps.com/hc/en-us/articles/4419004026395-Global-Heatmaps
https://support.ridewithgps.com/hc/en-us/articles/4419010273179-Surface-Types
https://support.ridewithgps.com/hc/en-us/articles/4419010818203-OSM-Map-Data
https://support.ridewithgps.com/hc/en-us/articles/4419013709851-Map-Types
https://support.ridewithgps.com/hc/en-us/articles/53501218461467-Route-Insights
https://tile.waymarkedtrails.org/{theme}/{z}/{x}/{y}.png
https://transweb.sjsu.edu/sites/default/files/1005-low-stress-bicycling-network-connectivity.pdf
https://valhalla.github.io/valhalla/api/map-matching/
https://valhalla.github.io/valhalla/api/route/api-reference/
https://valhalla.github.io/valhalla/start/installation/
https://valhalla.openstreetmap.de/
https://valhalla1.openstreetmap.de
https://valhalla1.openstreetmap.de/
https://valhalla1.openstreetmap.de/route
https://valhalla1.openstreetmap.de/trace_attributes
https://waymarkedtrails.org/
https://waymarkedtrails.org/api/
https://waymarkedtrails.org/help/
https://web.archive.org/web/20241112190443/https://www.trailforks.com/about/api/
https://web.archive.org/web/20250420220620/https://www.trailforks.com/about/data/
https://web.archive.org/web/20250911205156/https://www.trailforks.com/about/tou/
https://web.archive.org/web/20260131211947/https://www.trailforks.com/about/legal/
https://wiki.openstreetmap.org/wiki/Cycle_routes
https://wiki.openstreetmap.org/wiki/Key:ascent
https://wiki.openstreetmap.org/wiki/Key:bicycle
https://wiki.openstreetmap.org/wiki/Key:cycleway
https://wiki.openstreetmap.org/wiki/Key:difficulty
https://wiki.openstreetmap.org/wiki/Key:highway
https://wiki.openstreetmap.org/wiki/Key:mtb:difficulty
https://wiki.openstreetmap.org/wiki/Key:mtb:scale
https://wiki.openstreetmap.org/wiki/Key:mtb:scale:imba
https://wiki.openstreetmap.org/wiki/Key:network
https://wiki.openstreetmap.org/wiki/Key:route
https://wiki.openstreetmap.org/wiki/Key:sac_scale
https://wiki.openstreetmap.org/wiki/Key:smoothness
https://wiki.openstreetmap.org/wiki/Key:surface
https://wiki.openstreetmap.org/wiki/Key:tracktype
https://wiki.openstreetmap.org/wiki/Key:trail_visibility
https://wiki.openstreetmap.org/wiki/Overpass_API
https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL
https://wiki.openstreetmap.org/wiki/Relation:route
https://wiki.openstreetmap.org/wiki/Tag:leisure%3Dtrack
https://wiki.openstreetmap.org/wiki/Tag:route%3Dmtb
https://www.cyclestreets.net/api/
https://www.cyclestreets.net/api/apply/
https://www.cyclestreets.net/api/journey.json?
https://www.cyclestreets.net/api/journey.json?...&segments=1
https://www.cyclestreets.net/api/policy/
https://www.cyclestreets.net/api/v1/
https://www.cyclestreets.net/api/v1/journey/
https://www.cyclestreets.net/api/v1/journeyadvanced/
https://www.cyclestreets.net/api/v2/journey.plan/
https://www.cyclestreets.net/api/v2/journey.retrieve/
https://www.cyclestreets.net/api/v2/overview/
https://www.cyclestreets.net/api/v2/status/
https://www.cyclestreets.net/help/history/
https://www.cyclestreets.net/help/journey/faq/
https://www.cyclestreets.net/help/journey/howitworks/
https://www.cyclestreets.net/help/journey/junctions/
https://www.cyclestreets.net/help/journey/osmconversion/
https://www.cyclestreets.net/help/journey/routing/
https://www.cyclestreets.net/help/overview/
https://www.fossgis.de/wiki/Valhalla
https://www.graphhopper.com/developers/
https://www.graphhopper.com/faq/what-is-one-credit/
https://www.graphhopper.com/pricing/
https://www.hikingproject.com/data
https://www.mtbproject.com/
https://www.mtbproject.com/about
https://www.mtbproject.com/api
https://www.mtbproject.com/api/v1/get-trails
https://www.mtbproject.com/data
https://www.mtbproject.com/data**:
https://www.mtbproject.com/data/get-trails
https://www.mtbproject.com/data/get-trails?...&key=test
https://www.mtbproject.com/data/get-trails?lat=40.0274&lon=-105.2519&maxDistance=10&key=test
https://www.mtbproject.com/legal
https://www.mtbproject.com/tos
https://www.openstreetmap.org/
https://www.openstreetmap.org/copyright
https://www.outsideinc.com/brands/
https://www.trailforks.com
https://www.trailforks.com/about/api/
https://www.trailforks.com/about/data/
https://www.trailforks.com/about/legal/
https://www.trailforks.com/about/tou/
https://www.trailforks.com/api/
https://www.trailforks.com/api/0/<resource
https://www.trailforks.com/api/0/difficulties/?app_id=x&app_secret=y
https://www.trailforks.com/api/0/regions/
https://www.trailforks.com/api/0/regions/?app_id=test&key=test
https://www.trailforks.com/api/0/regions/?app_id=x&app_secret=y
https://www.trailforks.com/api/0/trails/?app_id=x&app_secret=y
https://www.trailforks.com/robots.txt
https://{theme}.waymarkedtrails.org/api/v1
```
