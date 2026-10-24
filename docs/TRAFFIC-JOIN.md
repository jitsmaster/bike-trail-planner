# Traffic join: how `assets/traffic.json` was built

Research/build date: **2026-09-15**. Home: `47.240371,-122.262019` (12424 18th Street East, Edgewood, Pierce County WA).
Source report: `docs/research/traffic-data-wa.md` (verified). Spec that consumes this data: `docs/SPEC.md` section 6b.
Home area for this asset: a **12 km square** around home, WGS84 `-122.421,47.1324` to `-122.103,47.349`.

## 1. Output files

| File | Size | Records | Purpose |
|---|---|---|---|
| `assets/traffic.json` | 1.29 MB | 4,365 | Evidence table keyed by **OSM way id**, for grading every route edge |
| `assets/limits.json` | 4.8 KB | 13 roads | Small hand-checked override table + the empty user-filled `posted_speed_mph` fields |

Raw downloads were kept under `docs/traffic-raw/` while building and **deleted afterwards**. Nothing over 2 MB was
left behind. The join script itself was **not** kept, because the task restricted me to creating `assets/` and this
file; the method is specified in section 4 and section 11 in full.

## 2. The match key: **OSM way id** (chosen approach)

I chose a **stable match key, not a polyline**. Each record carries `osm_way_id`.

Why this works for the app: the local Valhalla build is **3.8.3-9eecaca4e** (`http://127.0.0.1:8002/status`), and its
`trace_attributes` response returns a `way_id` on **every** edge. Verified on the repo's own captured response
`valhalla/responses/trace_attributes_edgewalk_r1.json`: all 178 edges carry `way_id` (alongside `names`,
`road_class`, `speed_limit`, `cycle_lane`, `surface`, `use`, `bicycle_network`).

Lookup rule for the app:

```text
for each edge in trace_attributes.edges:
    record = traffic_by_way_id.get(edge.way_id)
    if record is None                     -> UNGRADED / unknown  (never "low traffic")
    elif record.volume_band == "unknown"  -> unknown             (never "low traffic")
    else                                  -> use record.aadt + record.aadt_confidence (+ record.aadt_year for display)
```

The file is an **evidence table, not a road census**: a way id that is absent means "no usable count found", which
must be treated exactly like `volume_band: "unknown"`. See section 9 for why the file cannot hold every way.

## 3. Datasets, exact request URLs, and the record counts actually received

All requests used the header
`User-Agent: bike-trail-planner-traffic-asset/1.0 (personal bike routing app; contact: local user)`.

### 3.1 WSDOT "Traffic Sections (AADT) Current" (state highways only)

| What | URL | Received |
|---|---|---|
| One-shot CSV (row count + attributes) | `https://gisdata-wsdot.opendata.arcgis.com/api/download/v1/items/c05b181c4f314cdc93ef44bae460af54/csv?layers=1` | HTTP 200, 820,993 bytes, **4,815 data rows** |
| Geometry, paginated query (used for the join) | `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/TrafficData/FeatureServer/1/query` with `where=1=1`, `geometry=-122.421,47.1324,-122.103,47.349`, `geometryType=esriGeometryEnvelope`, `inSR=4326`, `outSR=4326`, `spatialRel=esriSpatialRelIntersects`, `outFields=OBJECTID,RouteIdentifier,StateRouteNumber,RelatedRouteType,RelatedRouteQualifier,Location,AADT,BeginAccumulatedRouteMile,EndAccumulatedRouteMile,ReportingYear`, `returnGeometry=true`, `f=geojson` | 1 page, **157 features** inside the box |

`ReportingYear` is 2025 on every row used. Routes present in the box: 167 (29), 161 (21), 509 (20), 99 (16),
410 (16), 18 (13), 512 (12), 5 (11), 164 (10), 162 (9). Licence: `"For Planning use only. ..."` (WSDOT).

### 3.2 Pierce County "Traffic Volume" (local roads)

| What | URL | Received |
|---|---|---|
| One-shot CSV (row count + attributes) | `https://gisdata-piercecowa.opendata.arcgis.com/api/download/v1/items/8145f84ad0e94b558a381ec36864c4ff/csv?layers=0` | HTTP 200, 1,246,468 bytes, **8,575 data rows** |
| One-shot GeoJSON | same item, `/geojson?layers=0` | HTTP 200, 9,281,383 bytes, 8,575 features, **`crs: EPSG:2927` (WA State Plane South), so its coordinates are not lat/lon.** Not usable for the join as downloaded |
| Geometry in WGS84, paginated query (used) | `https://services2.arcgis.com/1UvBaQ5y1ubjUPmd/arcgis/rest/services/Traffic_Volume/FeatureServer/0/query` with `outSR=4326`, same envelope, `outFields=OBJECTID,RoadNumber,BMP,EMP,ADTSource,ADTVolume,ADTYear,StateLRS,LocError`, `returnGeometry=true`, `f=geojson`, `resultRecordCount=2000`, `resultOffset` paging | 2 pages (2,000 + 397), **2,397 rows inside the box** |
| Street names (`RoadNumber`/RLOG to `FULLNAME`) | `https://services2.arcgis.com/1UvBaQ5y1ubjUPmd/arcgis/rest/services/Roads/FeatureServer/0/query` with `where=RLOG IN (...)` in 60-value chunks, `outFields=RLOG,FULLNAME,NAME,LCITY,RCITY`, `returnGeometry=false`, `f=json` | 27 requests; **1,588 of the 1,607 RLOGs** in the box resolved to at least one `FULLNAME` |

The Pierce ADT layer has **no street-name field**; the name comes only from the `Roads` layer, which is why that
lookup is part of the join rather than an afterthought.

### 3.3 Level of Traffic Stress (optional advisory field - included, with caveats)

| What | URL | Received |
|---|---|---|
| WSDOT Active Transportation LTS | `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/ActiveTransportationData/MapServer/2/query`, same envelope, `outFields=OBJECTID,RouteIdentifier,AADT,SpeedLimit,LaneCount,BikeFacilityType,LTS_Bicycle,LTS_General,ShoulderWidth,BeginARM,EndARM` | 3 pages (1,000 + 1,000 + 190), **2,190 rows**; **1,366** are mainline (`RouteIdentifier` = digits + `i`/`d`) with `LTS_Bicycle != 999`. Licence `"For Planning use only. ..."` |
| PSRC LTS | `https://services6.arcgis.com/GWxg6t7KXELn1thE/arcgis/rest/services/level_of_traffic_stress_gdb/FeatureServer/0/query` | 2 pages (2,000 + 1,149), **3,149 rows** in the box |

### 3.4 OpenStreetMap (the join's other half)

| What | Request | Received |
|---|---|---|
| Way count in the box | `https://overpass.private.coffee/api/interpreter`, `way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|..._link)$"](box); out count;` | **16,431 ways** |
| Ways + tags + geometry | same endpoint, the box split into a **5x5 grid of 4.8 km cells**, `out tags geom;` per cell | **16,443 ways** with geometry (union of the cell results, de-duplicated by way id) |

`overpass-api.de` is unreachable from this host (`curl: (7)`); `overpass.private.coffee` and
`overpass.kumi.systems` both answered, with occasional HTTP 504 on large queries - hence the grid.

## 4. Join rules

There is **no shared ID** between OSM and either count dataset, so every match needs **route identity AND geometry**.
The identity gate is the real protection; geometry only confirms it.

**Identity gate**

* WSDOT rows: the OSM way must carry `ref` containing the state route number (e.g. `WA 161`, `WA 161;WA 512`),
  or, as a fallback, its `name` must be one of the names observed on `ref`-carrying ways of that same route
  (this fallback added 14 ways, e.g. `Bay Street` and `River Road East` on SR-167).
  Rows whose `RouteIdentifier` has a related-route suffix (`167HD01440`) are dropped as ramps/laterals.
* Pierce rows: the way's normalised name must be in the normalised name set of that row's `RoadNumber` (RLOG),
  read from the `Roads` layer. `exact` = names equal after suffix expansion (`122nd Avenue East` = `122ND AVE E`);
  `root` = equal after also dropping a trailing direction (`Stewart Road Southwest` vs `STEWART RD SW` style variants).
  RLOG alone is never the key - RLOG `98060` is `122ND AVE E` + `36TH ST E` + `48TH ST E` + `EDGEWOOD DR E` +
  `SUMNER HEIGHTS DR E`, so a name that is not in *that* list can never borrow its counts.

**Geometry gate** (both polylines projected to metres about home, `x = (lon-lon0)*111320*cos(lat0)`)

* a vertex is "near" when it is within **50 m** (the top of the 30-50 m envelope the source report specifies);
* accept if either the way lies on the evidence (`>=70%` of the way's vertices near it, median distance `<=50 m`)
  or the evidence lies on the way (`>=70%` of the evidence's vertices near it, median `<=50 m`, and `>=50 m` of it near);
* in the second case the two lines must be roughly parallel (angle `<45 deg`), which is what stops a short
  crossing row from being accepted at a junction.

**Choosing one count per way** (highest first): confidence rank (`measured` > `stale` > `estimate` > `unknown`),
then name specificity (an RLOG whose *only* name is the way's name beats a multi-name RLOG; a `ref` match beats a
name fallback), then geometry (median distance), then the share of vertices near, then the year, then the length of
way covered. Result: **3,730 ways with a count row**, plus **635 ways that have no count but do have an LTS value**
(emitted with `source: "none"`, `aadt: null`, and the LTS advisory attached) - **4,365 records** in total.

## 5. Confidence and band semantics (the decisions that matter)

| `aadt_confidence` | Rule used here |
|---|---|
| `measured` | a count source (`County Count`, `State/City Count`, `Consultant Count`) with `ADTYear >= 2010`, or WSDOT sections with `ReportingYear >= 2010` |
| `stale` | a real count older than 2010 (this is why 1994-1996 Pierce rows are kept, but labelled) |
| `estimate` | a row whose source label says `Estimate` (`County Estimate` etc.) - **recorded as an estimate, never as a measurement** |
| `unknown` | no value at all: `ADTVolume = 0` ("Not Known"), null, or no match |

`volume_band` (`low` <1500, `medium` 1500-7000, `high` >7000 - Puyallup 2044 Comprehensive Plan bands) is written
**only when `aadt_confidence` is `measured` or `stale`**. For `estimate` and `unknown` it is `"unknown"`.
Consequences, all deliberate:

* `ADTVolume = 0` means "Not Known" and becomes `aadt: null`, `volume_band: "unknown"` - never `low`. Checked: **0 records** have band `low` with anything other than a real count, and **0 records** have a band with `aadt: null`.
* Military Road E's 1978 estimate (1,600) and Fryar Avenue's 1983 estimate (846) therefore carry `volume_band: "unknown"`. An old estimate is not a usable count.
* Pierce rows whose source label is `Not Known` **but which do carry a value from 2010 or later** (East Valley Hwy E 11,050 @2010; Stewart Rd SW 13,000 @2011) are treated as usable, and keep the raw label in `aadt_source_label`. This is deliberate: the *number* is recent and published, only its provenance label is blank, and hiding it would push a real 11,050 vpd arterial into UNGRADED. The raw label travels with the record so nothing is concealed.
* WSDOT section `AADT` is a published current-year figure that WSDOT itself derives with interpolation between count stations (one value covers up to 2.8 km on SR-161). It is labelled `measured` because it is the current published value; treat it as a smoothed segment figure, not a station reading.

## 6. MUST-REPORT roads (13), matched record or "no usable count"

Radius: 6 km (the radius the source report used for these roads). "nearest" is the distance from home to the nearest
OSM way carrying that name. "Other rows seen" lists every distinct count the join attached to that road's ways.

| Road | nearest | Matched record | Year | confidence | band | LTS | Other rows seen |
|---|---|---|---|---|---|---|---|
| **18th Street East** | 310 m | **381** (`pierce/RLOG-84800`) | 1996 | stale | low | - | - (a 2nd way at 6.1 km has no row) |
| **122nd Avenue East** | 308 m | **2,626** (`pierce/RLOG-98210`) | 1996 | stale | medium | 4 | - (a 2nd way at 3.5 km has no row) |
| **Edgewood Drive East** | 2,953 m | **2,875** (`pierce/RLOG-83850`) | 1994 | stale | medium | 4 | - |
| **Meridian Ave E / SR-161** | 2,407 m | **20,000** (`wsdot/SR-161/31.57-33.31`) | 2025 | measured | high | 4 | 15,000 / 16,000 / 25,000 / 29,000 (2025) on the neighbouring SR-161 sections |
| **Military Road E** | 3,099 m | **no usable count** (`pierce/RLOG-98250`, 1,600 vpd) | 1978 | estimate | **unknown** | 4 | 21,150 / 21,200 (2025) and 6,200 / 11,725 / 13,525 (2024) belong to RLOG 97425, a *different* Military Rd E stretch **10.1-11.9 km** south of home; 500 (1993) is RLOG 77020 |
| **Tacoma Avenue** | 3,073 m | **no usable count** (`ADTVolume` 0, "Not Known") | 2008 | unknown | **unknown** | - | - |
| **48th Street East** | 2,949 m | **2,469** (`pierce/RLOG-83900`) | 1996 | stale | medium | 4 | 1,079 (1996, same RLOG, a 164 m stretch of the same way), 200 (1978 estimate) |
| **Stewart Road SW** | 1,168 m | **14,669** (`pierce/RLOG-98600`) | 1995 | stale | high | 4 | 11,100 (2002) and 13,000 (2011, "Not Known") on the next stretches east. **Not one of the six requested anchors**; reported because the join found it |
| **A Street SE** | 3,131 m | **no usable count** | - | unknown | **unknown** | - | absent from the Pierce `Roads` layer entirely: it is in Auburn, **King County**, so no dataset here covers it |
| **East Valley Hwy E** | 2,660 m | **11,050** (`pierce/RLOG-97910`) | 2010 | measured | high | 4 | 9,950 (2002), 10,060 (2005), 12,500 (2002) |
| **Fryar Avenue** | 3,509 m | **no usable count** (`ADTVolume` 0, "Not Known") | 1983 | unknown | **unknown** | - | the 846 vpd 1983 County Estimate sits on RLOG 98430 and its geometry did not pass the gate for the Fryar Avenue ways |
| **142nd Avenue East** | 1,737 m | **no usable count** | - | unknown | **unknown** | - | only 1983-era County Estimates (846) and `ADTVolume` 0 rows; the 846 row reaches only 64% of its vertices into the near-home way, below the 70% gate, so it was refused |
| **West Valley Hwy E** | 472 m | **5,750** (`pierce/RLOG-98350`) | 2025 | measured | medium | 4 | 1,877 (1996) on the adjacent stretches |

Every match is **UNCERTAIN** to the degree that a same-named road elsewhere could exist; the name gate plus the
50 m geometry gate is what makes each of these defensible. Where a match could not be defended, the answer recorded
is "no usable count", not a number.

## 7. Sanity anchors: 6 of 6 reproduced

| Anchor from the source report | Reproduced | Where |
|---|---|---|
| 18th St E 381 (1996) | **yes**, 381 / 1996 / stale / low | `pierce/RLOG-84800` |
| 122nd Ave E 2,626 (1996) | **yes**, 2,626 / 1996 / stale / medium | `pierce/RLOG-98210` |
| Edgewood Dr E 2,875 (1994) | **yes**, 2,875 / 1994 / stale / medium | `pierce/RLOG-83850` |
| SR-161 20,000 (2025) | **yes**, 20,000 / 2025 / measured / high | `wsdot/SR-161/31.57-33.31`, the section covering home's latitude |
| East Valley Hwy E 11,050 (2010) | **yes**, 11,050 / 2010 / measured / high | `pierce/RLOG-97910` |
| West Valley Hwy E 5,750 (2025) | **yes**, 5,750 / 2025 / measured / medium | `pierce/RLOG-98350` |

Two anchors were wrong in the first builds and were fixed by changing the ranking, not by hand-editing values:

1. **Edgewood Drive E.** The first build gave 1,987 (1996) because RLOG `98060` (a five-name route) also lists
   `EDGEWOOD DR E`. RLOG `83850`'s *only* name is `EDGEWOOD DR E`, so the ranking now prefers the single-name RLOG.
2. **122nd Avenue East.** The first build gave 4,826 (1996), the neighbouring segment of the same RLOG. Both rows are
   `1996`/`County Count`, so geometry now breaks the tie (median 352 m vs 1,657 m from the 4 km way) and 2,626 wins.
   Its OSM way is one 4 km polyline, so the record carries the row that covers most of it - see the limitations below.

Trap checks that came out clean:

* **Military Road E near home never receives SR-161's or the southern 21,200 vpd value.** Those ways have no `ref`, and
  `MILITARY ROAD EAST` is not one of the names seen on SR-161 ways; the RLOG 97425 rows sit 10.1-11.9 km away.
* **48th St E never receives the 2024 rows** (4,025-8,000 vpd): those belong to RLOG `86570`, whose names are
  `166TH AVE E` / `SOUTH TAPPS DR E`, so the gate refuses them.
* **48th St E's near-home way is not given the nearby West Valley Hwy E row** even though that row passes 27 m from it:
  the name gate rejects a different street's RLOG.
* **No WSDOT `Location` string was used anywhere.** Route identity comes from `RouteIdentifier`/`StateRouteNumber` plus
  the OSM `ref`; mileposts (`BeginAccumulatedRouteMile`) appear only as evidence metadata.

## 8. Level of Traffic Stress (advisory, optional)

Included, because it was cheap and it is already measured for the home arterials. **2,124 records carry an `lts`
value**: 796 from WSDOT (`lts_source: "wsdot"`, state highways, `LTS_Bicycle`, matched only within the same
route) and 1,328 from PSRC (`lts_source: "psrc"`, `max(LTS_IJ, LTS_JI)`).

PSRC matching rules: the row's `Fullname` tokens must be a subset of the way's name tokens **and** the first token
must agree (`122ND` for `122nd Avenue East`; `MERIDIAN`, `EDGEWOOD`, `STEWART`, ...), the geometry must pass the same
50 m / 70% gate, and `FacilType = "Shared Use Path"` rows are **never** attached to a road way (that is the trap that
made a Fryar Avenue sample return `Sumner Link Trail`).

Anchor LTS values reproduced: 122nd Ave E **4**, Edgewood Dr E **4**, SR-161 **4**, Military Rd E **4**,
East Valley Hwy E **4**, West Valley Hwy E **4**, Stewart Rd SW **4**, 48th St E **4**. 18th St E, Tacoma Ave,
Fryar Ave and 142nd Ave E have **no** PSRC row (their nearest same-shape rows carry other names, and PSRC does not
contain residential streets at all).

Two caveats that must be shown honestly with any LTS badge:

1. **PSRC declares no licence** (`licenseInfo: None`, `copyrightText` empty, `accessInformation: None`). Bundling it
   redistributes data whose terms are unknown. If that is unacceptable, drop every record with
   `lts_source: "psrc"`; nothing else depends on it.
2. **It conflicts with the counts by design**: 122nd Ave E and Edgewood Dr E are published as LTS 4 (high stress)
   while carrying only ~2,600-2,900 vpd from 1994-1996, and PSRC has no collectors or residential streets, so it
   cannot grade Edgewood's local streets (including the home street).

## 9. Real coverage - the honest numbers

Measured on the real 9.19 km home-to-trail route already captured in
`valhalla/responses/trace_attributes_edgewalk_r1.json` (178 edges, 9,186 m), looked up edge by edge on `way_id`:

| Share of the route | What it has |
|---|---|
| **58.4%** (5,369 m) | **a usable count** (band low/medium/high; every one of these is `stale` 1991-2002 data) |
| 26.9% (2,467 m) | a county row that exists but yields **no usable count** (`ADTVolume` 0, or only an estimate) |
| 14.7% (1,350 m) | **no row at all** (mostly unnamed service roads and driveways, plus stretches the county never inventoried) |
| 85.3% (7,836 m) | at least *some* evidence record, usable or not |

Road level: the route uses **12 named roads** (122nd Avenue East 3,038 m, Edgewood Drive East 1,371 m, Fryar Avenue
981 m, Tacoma Avenue 572 m, 48th Street East 501 m, Puyallup Street 468 m, 142nd Avenue East 446 m,
18th Street East 280 m, Sumner Heights Drive East 231 m, Valley Avenue East 206 m, Bridge Street 182 m,
West Valley Highway East 22 m, plus 888 m of unnamed service road). **6 of the 12** have a usable count
(122nd Ave E, Edgewood Dr E, 48th St E, Sumner Heights Dr E, Valley Ave E, West Valley Hwy E).

**Plainly: on a typical route from this house, about three fifths of the metres get a real published number and about
two fifths do not.** Every number that does exist for the local Edgewood streets is 1994-1996 vintage, so the app must
show the year. The `tertiary` problem of the source report is only partly fixed: SR-161 is now correctly high, but
Fryar Avenue, Tacoma Avenue, 142nd Avenue East, Military Road E and A St SE still have nothing usable - and those are
exactly the connector streets a router likes to pick.

## 10. Limitations and UNCERTAIN items

* **One value per OSM way.** A 4 km way such as `122nd Avenue East` gets one record, while the county publishes
  several rows along it (2,626 / 4,826 / 2,168 / 2,234 ...). The record carries the row that best covers that way, so a
  long way is smeared to a single number. Splitting ways per count row is the obvious next improvement.
* **County geometry is offset from OSM.** Median offsets for accepted matches are 1 m (p50) and 20 m (p90), and 269
  records sit between 30 m and 50 m. That is why the gate is 50 m rather than 30 m - the 30-50 m envelope the source
  report specifies.
* **1.3% of matched ways (49 of 3,730) have a materially closer row with a different road identity** (median >10 m
  closer). Most are the same street under two identity systems (e.g. WSDOT route `512` vs Pierce RLOG `99512`). The
  name gate decided those, deliberately.
* **Pierce ADT direction is UNCERTAIN.** The layer does not say whether `ADTVolume` is one-way or both-way, while
  WSDOT sections explicitly say `"all directions of travel"`. **Do not blend the two sources as if they were the same
  unit.**
* **WSDOT section values are interpolated.** One value per section, up to 2.8 km on SR-161.
* **The file cannot hold every road.** 14,985 named ways and 1,458 unnamed ones fall inside the 12 km box, and a
  complete table would exceed the ~2 MB budget (3.47 MB with named-only coverage). Records exist only where
  there is evidence; **absence means unknown**. If the app must distinguish "known road, no data" for a specific
  street, `assets/limits.json` already lists the 13 roads that matter, with explicit nulls.
* **`posted_speed_mph` is deliberately empty** for all 13 roads in `assets/limits.json`. Speed limits are the user's
  local knowledge; nothing here guesses them.
* **UNCERTAIN:** whether Pierce publishes a fresher local count under another feed; whether King County (for
  A Street SE) publishes anything usable; whether the WSDOT LTS `AADT` field is one-directional (the source report's
  factor-of-two observation is an inference, not a quoted statement). None of these were resolved here.

## 11. Reproducing this

The build was: (1) fetch the count, geometry and name sources exactly as listed in section 3; (2) fetch OSM ways for
the same box from Overpass; (3) apply the value semantics of section 5; (4) apply the identity gate and then the
geometry gate of section 4; (5) apply the ranking of section 4 to pick one row per way; (6) attach the advisory LTS
of section 8; (7) write `assets/traffic.json` and `assets/limits.json`. Every URL is in section 3, every threshold in
section 4, and every classification rule in section 5, so the pipeline can be re-implemented without guessing.
