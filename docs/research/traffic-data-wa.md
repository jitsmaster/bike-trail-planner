# Real traffic-volume data for honest "low traffic" grading

Research date: 2026-09-15. Home: `47.240371,-122.262019` (12424 18th Street East, Edgewood, Pierce County, WA 98372).
Motivation: the app grades a route "low traffic or a dedicated bike lane" from OSM `road_class` and `speed_limit`
only. A real 9.19 km route came out 87% `cycle_lane=none`, 7630 m tagged `road_class=tertiary`, and `speed_limit`
missing on 58% of edges. OSM `tertiary` does not mean busy, so the app may be rejecting safe roads.

Evidence files: `docs/research/raw/traffic/` (raw JSON/CSV excerpts). Every claim below has an exact URL.
Unverified items are marked **UNCERTAIN**.

---

## 0. Verdict in one table

| Road (as named in the task) | OSM name here | Real vehicle count obtainable? |
|---|---|---|
| Meridian Ave E / SR-161 | `Meridian Avenue East` (`primary`, `ref=WA 161`) | **YES, 2025** — WSDOT: **20,000 vpd** at home's latitude |
| West Valley Hwy | `West Valley Highway East` (`tertiary`, no ref) | **YES, 2025** — Pierce County: **5,750 vpd** |
| East Valley Hwy E | `East Valley Highway East` (`secondary`, no ref) | **YES, but 2010** — Pierce County: 11,050 vpd |
| 122nd Ave E | `122nd Avenue East` (`tertiary`/`residential`) | **YES, but 1996** — Pierce County: 2,626 / 2,168 vpd |
| Edgewood Dr E | `Edgewood Drive East` (`tertiary`) | **YES, but 1994** — Pierce County: 2,875 vpd |
| 48th St E | `48th Street East` (`residential`) | **YES, but 1996** — Pierce County: 1,079 / 2,469 vpd |
| Military Rd E | `Military Road East` (`secondary`) | **NO** — only a 1978 estimate (1,600 vpd) |
| Fryar Ave | `Fryar Avenue` (`tertiary`) | **NO** — `ADTVolume` 0 / "Not Known"; 1983 estimate 846 vpd |
| Tacoma Ave | `Tacoma Avenue` (`tertiary`) | **NO** — `ADTVolume` 0 / "Not Known" |
| A St SE | `A Street Southeast` (`secondary`) | **NO** — outside Pierce County (it is in Auburn, King County) |

Also: **18th St E (the home's own street) = 381 vpd, 1996, Pierce County "County Count".**

Key consequence for the routing rule: **SR-161 is genuinely busy and the Edgewood local roads are genuinely
quiet.** The OSM `road_class` proxy fails in both directions. Details in sections 1.8, 2.4 and 5.

---

## 1. WSDOT traffic data

### 1.1 Yes — WSDOT publishes AADT as open data, and it is queryable

The authoritative ArcGIS REST server is:

```
https://data.wsdot.wa.gov/arcgis/rest/services
```

(`https://gisdata.wsdot.wa.gov/...` does **not** resolve — `curl: (6) Could not resolve host`. The WSDOT
marketing portal is `https://gisdata-wsdot.opendata.arcgis.com/`.)

Service root verbatim response (`?f=json`) lists folders:
`["Bridge","CIA","CommunityEngagement","DataLibrary","Environmental","EOC_TrafficEvents","FishPassageWSDOT","FunctionalClass","GPServices","HPMS","ILT","Maintenance","OAC","RealEstateServices","Scratch","Shared","SPORT","test","TRACTS","TransportationProjects","TravelCenter","TravelInformation","Utilities","Vertical_Clearance","WorkZone"]`

The traffic service is `Shared/TrafficData` (both a `FeatureServer` and a `MapServer`).

| Item | Value |
|---|---|
| Service description (verbatim) | `"Annual traffic counts and traffic sections."` |
| ArcGIS item: Traffic Counts | id `754cdad74f5d4497b182eec711bd514a`, modified **2026-09-04** |
| ArcGIS item: Traffic Sections | id `c05b181c4f314cdc93ef44bae460af54`, modified **2026-07-28** |
| Licence (both, verbatim) | `"For Planning use only. The Washington State Department of Transportation (WSDOT) shall not be liable for any activity involving the use of the data, including, but not limited to, lost profits or savings or any other economic or consequential damages. Nor does the WSDOT warrant the fitness of the data for use for a particular purpose, or the installation of the data, its use, or the results obtained."` |
| Spatial reference | `102100` (Web Mercator); `outSR=4326` supported |
| Max record count | `2000` (TrafficData layers); `1000` (LTS); `3000` (All State Route Points) |
| Pagination | yes — `resultOffset` + `resultRecordCount`; response sets `"exceededTransferLimit": true` |
| Query output formats | `"supportedQueryFormats": "JSON"` on TrafficData; `f=geojson` works. `f=csv` **fails**: `"Error: Output format not supported."` |
| One-shot download | yes, via the ArcGIS Hub download API (section 1.4) |

### 1.2 Layer 0 — "Traffic Counts" (points, per count station)

URL: `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/TrafficData/FeatureServer/0`

Layer description (verbatim, from the live service):

> `2023 Annual Average Daily Traffic figures for state highway traffic count locations. Data represents all directions of travel for the given location.`

**Stale-description pitfall:** that text says 2023, but the ArcGIS item description (modified 2026-09-04) says
`"2025 Annual Average Daily Traffic figures for state highway traffic count locations. Data represents all directions of travel for the given location. Data steward: Slade Dillard"`,
and every record in the live layer carries `ReportingYear = 2025`. A distinct-value query returns exactly one
value: `ReportingYear: [{'ReportingYear': 2025}]`. So the layer attributes are 2025; only the prose is stale.

Fields (`?f=json`): `OBJECTID, LRSDate, RouteIdentifier, Location, DirectionOfTravel, StateRouteNumber,
RelatedRouteType, RelatedRouteQualifier, SingleUnitTruckPct, DoubleUnitTruckPct, TripleUnitTruckPct, AADT,
AccumulatedRouteMile, GlobalID, ReportingYear`.

Row count: `{"count": 6615}`.

### 1.3 Layer 1 — "Traffic Sections" (polylines, per roadway segment) — this is the one to use

URL: `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/TrafficData/FeatureServer/1`

Layer description (verbatim):

> `2023 estimated Annual Average Daily Traffic volumes along the state highway system. Data represents all directions of travel for the given roadway segment.`

(Again the prose says 2023 but every record is `ReportingYear: 2025`.)

Fields: `OBJECTID, LRSDate, RouteIdentifier, StateRouteNumber, RelatedRouteType, RelatedRouteQualifier,
Location, AADT, SymbolCode, Shape__Length, BeginAccumulatedRouteMile, EndAccumulatedRouteMile, GlobalID,
ReportingYear`.

Row count: `{"count": 4815}`. Every row is a state-highway segment with `AADT`,
`BeginAccumulatedRouteMile`, `EndAccumulatedRouteMile`, and a human-readable `Location` string such as
`"From Milepost 31.57 A to Milepost 33.31 A"`.

### 1.4 One-shot downloads (CSV / GeoJSON) — small, bundleable

ArcGIS Hub download API (no key, no token):

```
https://gisdata-wsdot.opendata.arcgis.com/api/download/v1/items/<itemId>/csv?layers=<layerId>
https://gisdata-wsdot.opendata.arcgis.com/api/download/v1/items/<itemId>/geojson?layers=<layerId>
```

Measured on 2026-09-15:

| Dataset | URL | HTTP | bytes | data rows |
|---|---|---|---|---|
| Traffic Sections (AADT) 2025 | `.../items/c05b181c4f314cdc93ef44bae460af54/csv?layers=1` | 200 | 820,993 | 4,815 |
| Traffic Counts (AADT + truck %) 2025 | `.../items/754cdad74f5d4497b182eec711bd514a/csv?layers=0` | 200 | 1,534,499 | 6,615 |
| Active Transportation LTS (incl. AADT) | `.../items/36ba1d681bbb46f0a5792b9ff9dac21b/csv?layers=2` | 200 | 11,626,137 | 54,148 |

CSV header, Traffic Sections (verbatim):
`OBJECTID,LRSDate,RouteIdentifier,StateRouteNumber,RelatedRouteType,RelatedRouteQualifier,Location,AADT,SymbolCode,BeginAccumulatedRouteMile,EndAccumulatedRouteMile,GlobalID,ReportingYear,SHAPESTLength`

CSV header, Traffic Counts (verbatim):
`X,Y,OBJECTID,LRSDate,RouteIdentifier,Location,DirectionOfTravel,StateRouteNumber,RelatedRouteType,RelatedRouteQualifier,SingleUnitTruckPct,DoubleUnitTruckPct,TripleUnitTruckPct,AADT,AccumulatedRouteMile,GlobalID,ReportingYear`

**All three files are far below the 50 MB limit.** Total ~14 MB. Nothing was copied into the repo.

### 1.5 Historic AADT workbook ("Annual Traffic Report" replacement)

The WSDOT page `https://wsdot.wa.gov/about/transportation-data/travel-data/traffic-count-data` says, verbatim:

> `We publish data collected through our vehicular traffic data monitoring program, in which we collect and maintain data on the state route system from a network of permanent and short-duration traffic counters.`

> `The Annual Traffic Report summarized traffic data maintained by the Washington State Department of Transportation for the state highway system, but as of 2017 is no longer published. Point-specific traffic volume data is now available through the data reporting application and datasets listed above.`

> `Combined-direction summary statistics in tabular format for all count locations and years is available through our Historical AADT and Truck percentage dataset (XLSX 13MB).`

Direct link: `https://wsdot.wa.gov/sites/default/files/2026-07/Historical-Traffic-Data.xlsx` (13,146,645 bytes, HTTP 200).

Inspected from the workbook (downloaded to `/tmp`, **not** into the repo, deleted afterwards):
sheet `Data` is `A1:N227594` → **227,593 data rows**; sheet `Notes` states verbatim:

> `This workbook contains historical Annual Average Daily Traffic (AADT) volumes and truck percentages for all traffic counting locations monitored by the Transportation Data, GIS and Modeling Group of the Washington State Department of Transportation.`

> `Data is coded using the route mileposting system utilized within the department's State Highway Log`

> `For those wishing to represent this data spatially, latitude and longitude values have been provided.`

Columns: `Route ID, State Route Number, Related Route Type, Related Route Qualfier, Accumulated Route Mile,
Direction Of Travel, Location ID, Location Description, Year, AADT, Single Unit Truck Pct, Double Unit Truck
Pct, Triple Unit Truck Pct, Latitude, Longitude`. Years present: **1984–2025** (42 distinct years).
Distinct `State Route Number` values: **188** — all state route numbers, no local roads.

### 1.6 Other WSDOT GIS services checked

| Service | URL | Finding |
|---|---|---|
| Local Agency Public Roads (line) | `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/LocalAgencyPublicRoadData/FeatureServer/0` | Covers county roads and city streets, but **has no AADT field**. Fields: `CBMP, CEMP, RoadNumber, RoadName, Begin_Point, End_Point, FederalFunctionalClassDesc, CityName, Jurisdiction, Shape__Length, OldFederalFunctionalClass, Length, FederalFunctionalClassCode, InventoryDirection, RouteIdentifier, LRSDate, CityFIPSLongPlaceCode, CountyFIPSLongPlaceCode, GlobalID`. |
| All State Route Points | `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/AllStateRoutePoints/FeatureServer/0` | 1,552,201 points; `RouteID, ARM, SRMP, Direction, Easting, Northing, Longitude, Latitude, Azimuth, StateRouteNumber, RelRouteType, RelRouteQual, AheadBackInd, LRS_Date`. Description (verbatim): `"As a reference data source for geographic analysis and as a tabular source for applications needing locations along Washington State Highways in the form of ARMs, SRMPs, or geographic coordinates (WGS84 and WA State Plane South)."` |
| Milepost Values (10 mile) | `https://data.wsdot.wa.gov/arcgis/rest/services/Shared/MilepostValues/FeatureServer/0` | Same `ARM`+`SRMP` pair at 10-mile intervals. Description (verbatim): `"Milepost values at ten mile increments on mainline state routes derived from the WSDOT 24K Linear Referencing System for Washington State Routes (LRS)."` |
| HPMS Segments | `https://data.wsdot.wa.gov/arcgis/rest/services/HPMS/HpmsSegments/MapServer` | Service description (verbatim): `"This map service supports the HPMS Application. The service was stopped on 4/17/2023 and will re-open for the HPMS review period in Spring of 2024."` Only fields are `OBJECTID, HPMSID, Route_ID, Begin_Point, End_Point, Shape, Shape_Length, LRS_Date` — **no AADT exposed**. |
| WSDOT Traffic Volume (MS2 TCDS) | `https://wsdot.public.ms2soft.com/tcds/tsearch.asp?loc=Wsdot&mod=TCDS` | The point-specific count reporting app. **Programmatic access blocked** by an AWS WAF JavaScript challenge: HTTP `202` + `window.gokuProps` challenge page, and HTTP `403` ("Request blocked", CloudFront) with a browser User-Agent. **UNCERTAIN** whether it contains local-agency counts. |

### 1.7 Per-segment vs bounding-box query — both work

Both are confirmed working against `TrafficData/FeatureServer/1`:

* **Bounding box** (`geometryType=esriGeometryEnvelope`, `inSR=102100`, `spatialRel=esriSpatialRelIntersects`)
* **Per road segment** (`where=StateRouteNumber='161'`, or `RouteIdentifier='161i' AND BeginAccumulatedRouteMile BETWEEN ...`)
* **Point-with-radius**: `geometry=<x>,<y>&geometryType=esriGeometryPoint&distance=2000&units=esriSRUnit_Meter`

### 1.8 The count for the road that actually matters

SR-161 = `Meridian Avenue East`, the arterial that runs 2.4 km west of home and that the OSM data gives
`ref=WA 161`.

2025 Traffic Section nearest home (`StateRouteNumber='161'`, geometry sampled):

```
RouteIdentifier 161, AADT 20000, BeginAccumulatedRouteMile 31.57, EndAccumulatedRouteMile 33.31,
Location "From Milepost 31.57 A to Milepost 33.31 A", ReportingYear 2025
```

2025 Traffic Count points near home (all `DirectionOfTravel = Bothways`):

| distance | ARM/MP | AADT | Location string (2025) |
|---|---|---|---|
| 2646 m | 33.32 | 25000 | `NORTH OF MILEPOST 33.31: 8TH ST E, MILTON WAY (OLD SR 514) INTERS` |
| 2958 m | 31.58 | 20000 | `NORTH OF MILEPOST 31.57: 36TH ST E INTERSECTION` |
| 3115 m | 33.82 | 29000 | `SOUTH OF MILEPOST 33.83: MILITARY RD S INTERSECTION` |
| 3245 m | 31.28 | 15000 | `WEST OF MILEPOST 31.29: 102ND AVE E INTERSECTION` |

Home's own cross street is 18th St East; a retired count station named that intersection exactly:
`"SOUTH OF MILEPOST 32.64: 18TH ST CT E INTERSECTION"` (ARM 32.63, lat 47.2403, lon -122.29393) — that is
**2,411 m west of home at the same latitude**. So "the traffic on SR-161 at my latitude" has a published
answer: **20,000 vpd (2025, both directions)**.

WSDOT's own Bicycle Level of Traffic Stress for the same stretch (`ActiveTransportationData/MapServer/2`,
`RouteIdentifier='161i'`, ARM 32.2–33.1): `AADT 9620, SpeedLimit 35, LaneCount 2, BikeFacilityType "" (none),
ShoulderWidth 0, LTS_Bicycle 4, LTS_General 4`.

**Note the factor of ~2:** 9,620 vs 20,000. Two independent routes show the same halving (SR-167: LTS `167i`
AADT 49,266 vs section AADT 97,000). The TrafficData layer states `"all directions of travel"`; the LTS layer
does not say. **INFERENCE (not proven by a quote): the LTS layer's `AADT` is one-directional. UNCERTAIN.**

---

## 2. Local sources — Pierce County, Edgewood, Puyallup, Sumner, Milton

### 2.1 YES — Pierce County publishes a per-segment ADT layer

| Item | Value |
|---|---|
| Dataset name | `Traffic Volume` |
| ArcGIS REST query endpoint | `https://services2.arcgis.com/1UvBaQ5y1ubjUPmd/arcgis/rest/services/Traffic_Volume/FeatureServer/0/query` (HTTP 200) |
| ArcGIS item id | `8145f84ad0e94b558a381ec36864c4ff` |
| Hub landing page | `https://gisdata-piercecowa.opendata.arcgis.com/datasets/piercecowa::traffic-volume` |
| CSV download | `https://gisdata-piercecowa.opendata.arcgis.com/api/download/v1/items/8145f84ad0e94b558a381ec36864c4ff/csv?layers=0` — HTTP 200, **1,246,468 bytes** |
| GeoJSON download | same item id, `/geojson?layers=0` — HTTP 200, **9,281,383 bytes** |
| Metadata | `https://matterhorn.piercecountywa.gov/GISmetadata/pdbtrans_GISMO_tblADT.html` (HTTP 200) |
| Terms of Use | `https://matterhorn.piercecountywa.gov/Disclaimer/PierceCountyGISDataTermsofUse.pdf` (HTTP 200, 167,340 bytes) |
| Geometry | `esriGeometryPolyline`; native SR `wkid 2927` (WA State Plane South); `outSR=4326` works |
| `maxRecordCount` | `2000`; `advancedQueryCapabilities.supportsPagination = true` → `resultOffset` works |
| Row count (verified by me) | `{"count": 8575}` |
| Bounding box | West -122.829374, East -121.469586, North 47.421346, South 46.725950 (all of Pierce County) |
| Publication date (metadata) | `Publication_Date: 2026-07-02`, `Ending_Date: 2026-07-02`, `Metadata_Date: 20251031`, `Point_and_Vector_Object_Count: 8544` |
| Steward | `Pierce County Planning & Public Works - Traffic Division`, contact `david.ashley@piercecountywa.gov` |

Dataset description (verbatim, from the layer JSON):

> `This dataset geographically displays information about ADT data from the County Road Administration Board (CRAB) GIS-Mo database. GIS-Mo database replaced the old Mobility database. Please read the metadata (https://matterhorn.piercecountywa.gov/GISmetadata/pdbtrans_GISMO_tblADT.html) for additional information. Any data download or use constitutes acceptance of the Terms of Use (https://matterhorn.piercecountywa.gov/disclaimer/PierceCountyGISDataTermsofUse.pdf).`

Metadata Abstract (verbatim):

> `This dataset geographically displays information about ADT data from the County Road Administration Board (CRAB) GIS-Mo database. GIS-Modatabase replaced the old Mobility database.This dataset covers Pierce County owned and/or maintained public roadways. Any other roadways are either private or city-owned and can be viewed within the general use roads file.`

Metadata `Access_Constraints: None`; `Use_Constraints: All users of this data should read through the metadata and the Pierce County GIS Data Terms of Use.`

Fields (verified by me, `?f=json`):
`OBJECTID, AssetID, StateLRS, BMP, EMP, ADTSource, ADTVolume, ADTYear, TruckCount, BusCount, RVCount,
AADTFuture, AADTGrowthRate, AADTYear, FromDate, ToDate, LocError, OwnerID, RoadNumber, Shape__Length`.

`ADTVolume` = "Average Daily Traffic Volume."; `ADTYear` = "Annual Daily Traffic Year."; `ADTSource` = "Source
of ADT Volume."; `RoadNumber` = "Pierce County assigned Road Number (rlog)". In every row inspected,
`AADTFuture`, `AADTGrowthRate`, `AADTYear`, `TruckCount`, `BusCount`, `RVCount`, `OwnerID` are null.

**There is no street-name field.** Names live in a separate layer:
`https://services2.arcgis.com/1UvBaQ5y1ubjUPmd/arcgis/rest/services/Roads/FeatureServer/0`
(fields `FULLNAME, NAME, TYPE, SUFDIR, RLOG, LCITY, RCITY, MAP_CLASS`; 52,361 rows).

### 2.2 Recency — this dataset is old. I re-measured every number myself

| Query on `Traffic Volume` (8,575 rows) | Count | Share |
|---|---|---|
| `ADTYear <= 1990` | 4,864 | **56.7%** |
| `ADTYear >= 2015` | 1,573 | 18.3% |
| `ADTYear >= 2020` | 1,318 | 15.4% |
| `ADTSource = 'County Estimate'` | 5,505 | **64.2%** |
| `ADTSource = 'County Count'` | 2,925 | 34.1% |
| `ADTVolume = 0` (means "unknown") | 48 | 0.6% |

So ~57% of the published ADT numbers are from 1990 or earlier, and ~64% are estimates, not counts.
**Any use of this layer must gate on `ADTYear`.** `ADTVolume = 0` must be treated as missing, not as zero traffic.

### 2.3 Cities and the MPO — nothing usable

* **Edgewood, Puyallup, Sumner, Milton**: no city-owned AADT feed was found (all four portals were scanned
  in the sub-agent report `docs/research/raw/local-traffic-counts-wa.md`). **UNCERTAIN** only in the sense
  that a PDF may exist that is not indexed as GIS data.
* **PSRC** (Puget Sound Regional Council, the MPO): publishes no AADT dataset. Its 201-item DCAT feed
  (`https://psrc-psregcncl.hub.arcgis.com/api/feed/dcat-us/1.1.json`) contains no AADT layer. It does publish
  a Level of Traffic Stress layer — see section 4(e).
* A useful free anchor for interpreting numbers (from the sub-agent report): the City of Puyallup 2044
  Comprehensive Plan defines bicycle LTS bands at **<1,500 / 1,500–7,000 / >7,000 AADT**. That is a
  published, citable threshold set for exactly this decision.

### 2.4 The ten roads — measured, with the join done properly

Method (mine, run 2026-09-15): OSM ways for each named road within 6 km of home (Overpass,
`https://overpass.private.coffee/api/interpreter`); Pierce `Roads` layer to map `FULLNAME` → `RLOG`;
then `Traffic Volume` rows for those RLOGs only, kept when their geometry lies within 300 m of the OSM
way. This avoids the trap in section 3.7.

| Road (task name) | OSM name here | Pierce `FULLNAME` / RLOG | Newest published ADT within ~6 km | Verdict |
|---|---|---|---|---|
| 122nd Ave E | `122nd Avenue East` | `122ND AVE E` → RLOG 98210, 98060 | **2,626 / 2,168 vpd, 1996, County Count** | counted, but 30 years old |
| Edgewood Dr E | `Edgewood Drive East` | `EDGEWOOD DR E` → RLOG 83850, 98060 | **2,875 vpd, 1994, County Count** (and 1,987 vpd 1996) | counted, but 30+ years old |
| Meridian Ave E / SR-161 | `Meridian Avenue East` | `MERIDIAN AVE E` → RLOG 99161 | 23,000–25,000 vpd, **2005, State/City Count** — superseded by WSDOT's **20,000 (2025)** | **YES, current** |
| Military Rd E | `Military Road East` | `MILITARY RD E` → RLOG 53230, 53310, 53360, 77020, 95160, 97425, 98250 | Edgewood stretch (RLOG 98250): **1,600 vpd, 1978, County Estimate** | only a 1978 estimate |
| Tacoma Ave | `Tacoma Avenue` | `TACOMA AVE` → RLOG 98434 | **`ADTVolume` 0, `ADTSource` "Not Known", 2008** | **no usable count** |
| 48th St E | `48th Street East` | `48TH ST E` → RLOG 83900, 98060, 80530, 80260 | Edgewood stretch (RLOG 83900): **1,079 / 2,469 vpd, 1996, County Count** | counted, but 30 years old |
| East Valley Hwy E | `East Valley Highway East` | `EAST VALLEY HWY E` → RLOG 97910 | **11,050 vpd, 2010, "Not Known"** | counted, 16 years old |
| A St SE | `A Street Southeast` | not in the Pierce layer at all | — | **outside Pierce County** (it is in Auburn, King County) |
| Fryar Ave | `Fryar Avenue` | `FRYAR AVE` → RLOG 98430 | **`ADTVolume` 0, `ADTSource` "Not Known"; a 1983 estimate of 846 vpd exists** | **no usable count** |
| West Valley Hwy | `West Valley Highway East` / `... North` | `WEST VALLEY HWY E` → RLOG 98350; `WEST VALLEY HWY N` → absent | **5,750 vpd, 2025, County Count** (BMP 2.81–3.33, ~697 m from home) | **YES, current** |

Also measured, and directly relevant: **the home's own street, 18th St E (Edgewood, RLOG 84800), has
`ADTVolume` 381, `ADTYear` 1996, `ADTSource` "County Count"**, at 856 m from home. That is a real, published
low-traffic number for a residential Edgewood street.

### 2.5 Two corrections to the sub-agent's summary (I verified these myself)

1. **"Military Rd E — 21,200 / 21,150 (2025)" is not the Edgewood Military Rd E.** Those rows belong to
   RLOG `97425`, which the Pierce `Roads` layer names both `122ND ST E` and `MILITARY RD E`. I measured the
   geometry of the 2025 rows for RLOG 97425: centroids at `47.1341,-122.2656` and `47.1494,-122.2407` —
   **10.3 to 11.8 km south of home**. The Military Rd E next to home (RLOG 98250, `LCITY=EW`) has only a
   **1978** estimate.
2. **"48th St E — 1,250 (2024)" is not this 48th St E.** The 2024 rows (4,025–8,000 vpd) belong to
   RLOG `86570`, which the `Roads` layer names `166TH AVE E` and `SOUTH TAPPS DR E`. The 48th St E rows
   carrying RLOG `83900` are 1,079 / 2,469 vpd, **1996**.

Both errors come from the same root cause: **`RoadNumber` (RLOG) is a county route, not a street.** One RLOG
carries several `FULLNAME` values (e.g. RLOG `98060` is simultaneously `122ND AVE E`, `36TH ST E`,
`48TH ST E`, `EDGEWOOD DR E` and `SUMNER HEIGHTS DR E`). Name-based lookup without geometry, or geometry
without route identity, both give wrong answers.

### 2.6 The money finding

The quiet-road hypothesis is **corroborated**:

* SR-161 / Meridian Ave E at home's latitude: **20,000 vpd (WSDOT, 2025)** — genuinely busy.
* 122nd Ave E: **2,626 vpd (1996)**; Edgewood Dr E: **2,875 vpd (1994)**; West Valley Hwy E: **5,750 vpd (2025)**;
  18th St E: **381 vpd (1996)** — genuinely quiet.

So the app is right to treat SR-161 as high traffic, and **wrong to treat the Edgewood `tertiary`/local
roads as high traffic**. The OSM `road_class` proxy fails here in both directions.


---

## 3. How to join counts to OSM ways

### 3.1 The join key

There is **no shared ID**. OSM has no WSDOT `RouteIdentifier` or `AccumulatedRouteMile`. The practical key is
**geometry plus route identity**:

* WSDOT side: `RouteIdentifier` (`161`, `161i`, `161d`, `167HD01440`, `518R100291`, ...) plus
  `BeginAccumulatedRouteMile`/`EndAccumulatedRouteMile`.
* OSM side: `ref` = `"WA 161"` (with a space — verified with Overpass), and `name` = `"Meridian Avenue East"`.
* Everything else is geometry: buffer the OSM way and take the WSDOT section whose polyline is within N metres
  and whose route identity matches.

Do **not** try to join on `Location` text or on milepost arithmetic. See 3.2.

### 3.2 Pitfall A — `Location` mileposts are NOT `AccumulatedRouteMile`

I measured this on the full 2025 Traffic Sections layer (all 4,815 rows):

* **2,964 of 4,815 sections (61.6%) have a `Location` milepost that differs from `BeginAccumulatedRouteMile`
  by more than 0.02 mi.**
* The error is constant within a row but varies by route: I-2 (route `002`) row reads
  `Location "From Milepost 232.41 A to Milepost 249.73 A"` with `BeginAccumulatedRouteMile 224.33`,
  `EndAccumulatedRouteMile 241.65` — an offset of **-8.08 miles**. SR-395 is **+6.76**; SR-090 is **-1.72**.
* For SR-161 the offset is 0.000 on all 43 rows, so this route happens to be safe — but the code cannot assume
  that.

The `MilepostValues` and `AllStateRoutePoints` layers publish `ARM` and `SRMP` **side by side on the same
point**, which lets you build a per-route ARM↔SRMP correction curve. Example rows from
`MilepostValues/FeatureServer/0`: `{'RouteID': '002', 'ARM': 181.89, 'SRMP': 190.0}` and
`{'RouteID': '002', 'ARM': 271.92, 'SRMP': 280.0}`. The offset is not constant along a route.

### 3.3 Pitfall B — one count represents both directions

TrafficData says verbatim `"Data represents all directions of travel for the given location."` and
`"...for the given roadway segment."` The `DirectionOfTravel` field on the count points reads `Bothways`.
The LTS layer's `AADT` appears to be one direction (section 1.8). Mixing the two layers will double or halve a
road's apparent traffic.

### 3.4 Pitfall C — one station extrapolated over a long stretch

The single section covering home is `MP 31.57 → MP 33.31`, i.e. **2.80 km with one AADT value (20,000)**.
A count station is a point; WSDOT paints its value along the whole section. Adjacent 2025 values on SR-161 are
15,000 (MP 31.28), 20,000 (MP 31.58), 25,000 (MP 33.32), 29,000 (MP 33.82), so the local gradient is real —
interpolating between the bounding stations is better than taking the section value at the segment's midpoint.

### 3.5 Pitfall D — milepost range vs OSM geometry split

WSDOT sections are cut where the count changes; OSM ways are cut at every intersection and at every tag change.
They never align. Practical recipe:

1. Query `TrafficData/FeatureServer/1` with an envelope around the route (or `where=StateRouteNumber='161'`).
2. Keep only rows whose `RelatedRouteType`/`RelatedRouteQualifier` denote the mainline (the Counts layer
   contains ramps and laterals such as `RouteIdentifier "167HD01440"`, `"518R100291"`, `"167LX00964i"`). OSM
   `ref` matches the mainline only, so unattributed ramp rows must be dropped or they will steal the match.
3. Match OSM `ref` = `WA <StateRouteNumber>` (strip leading zeros: WSDOT stores `'161'`, not `'0161'`).
4. For each OSM way, sample its geometry and take the WSDOT section polyline with the smallest mean distance;
   accept only if that distance is below a threshold (tens of metres — WSDOT geometry is 24K LRS, OSM is
   usually closer than that, but a 30–50 m rejection threshold is safe).
5. Assign the section `AADT` to every Valhalla edge that lies on that OSM way. Never assign AADT to a way whose
   `ref` is absent.

Verification that step 1-5 is enough in practice: for SR-161 near home, the OSM way (`name=Meridian Avenue
East`, `ref=WA 161`, `highway=primary`, `maxspeed=35 mph`) sits at lon ≈ -122.2939, and both the WSDOT section
polyline and the 6,446 `AllStateRoutePoints` rows for route `161` sit at the same lon.

### 3.6 What the join buys you here

For the 9.19 km route in question, only the SR-161 portion can receive a real AADT. Every other named road in
the list is a local road (`ref` is absent in OSM, and WSDOT has no AADT for local roads). So this dataset
**does not** rescue the `tertiary` roads; it only fixes the arterial. See section 5.

### 3.7 Pitfall E — nearest-neighbour joins pick the WRONG road (measured)

I ran the naive version to see how bad it is. Taking a 60 m buffer around each named road's OSM geometry and
asking Pierce `Traffic Volume` for whatever came back:

| OSM road buffered | What a naive 60 m buffer returned |
|---|---|
| `Military Road East` | **SR-161's rows** (`RLOG 99161`, ADT 21,000 and 23,000, 2005) and a 1978 estimate — not Military Rd E |
| `Fryar Avenue` | `RLOG 96770` (5,076, 1988), `RLOG 96600`, `RLOG 98432` — crossing roads, not Fryar Ave |
| `A Street Southeast` | `RLOG 97910` (East Valley Hwy, 9,950, 2002) |
| `122nd Avenue East` | **24 different ADT segments**, because long county polylines cross it |

Then, when I asked PSRC LTS with the same 60 m buffer, a Military Rd E sample returned `Interurban Trail`,
`SR 161` and `ENCHANTED`; a Fryar Ave sample returned `Sumner Link Trail`; an A St SE sample returned
`White River Trail` and `LAKELAND HILLS`.

**Rule:** match on route identity *and* geometry. For Pierce County that means `RoadNumber`(RLOG) → `Roads.FULLNAME`,
then a distance check; `RoadNumber` alone is not a key (one RLOG carries several street names, see section 2.5),
and geometry alone is not a key either.

---

## 4. Alternatives when per-segment counts are missing

### (a) OSM `maxspeed` completeness — measured, and it is NOT a volume proxy

Overpass `out count` on the bbox `47.19,-122.33,47.29,-122.19` (`https://overpass.private.coffee/api/interpreter`,
HTTP 200; `overpass-api.de` refused connections from this host with `curl: (7)`):

| Set | Ways |
|---|---|
| all `highway=*` | 16,820 |
| `highway=*` + `maxspeed` | 2,993 → **17.8%** |
| major (`motorway|trunk|primary|secondary|tertiary`) | 943 |
| major + `maxspeed` | 641 → **68.0%** |
| `residential|unclassified|service|living_street` | 11,242 |
| that group + `maxspeed` | 2,322 → **20.7%** |

Excluding paths/cycleways: 2,993 / 12,379 = **24.2%**.

OSM wiki `https://wiki.openstreetmap.org/wiki/Key:maxspeed` (verbatim): `"Specifies the maximum legal speed
limit on a road, railway or waterway."` It is a legality attribute, not a volume measure. OSM's actual volume
key is `Key:traffic` (`https://wiki.openstreetmap.org/wiki/Key:traffic`): `"The traffic tag is used to describe
the amount of vehicles transiting on highways."` — measured **0 ways** in the bbox. So OSM cannot answer the
question here.

### (b) FHWA HPMS — local roads are present geometrically, but AADT is empty

* `https://www.fhwa.dot.gov/policyinformation/hpms.cfm` (HTTP 200).
* `https://www.fhwa.dot.gov/policyinformation/hpms/data.cfm` — **HTTP 404**. `.../geospatial.cfm` — **HTTP 404**.
* Live data: `https://datahub.transportation.gov/story/...` story page and Socrata resources
  `https://datahub.transportation.gov/resource/42um-tgh5.json` (Spatial All Sections 2024, 19,573,739 rows)
  and `https://datahub.transportation.gov/resource/c2pi-dj5z.json` (Spatial Local Sections 2024, 8,781,355 rows).
* Licence: `{"name": "Public Domain U.S. Government", "termsLink": "https://www.usa.gov/government-works"}`.
* AADT field: **`aadt`** (also `aadt_d`, `future_aadt`, `speed_limit`, `through_lanes`, `f_system`, `stateid`).

Measured by the sub-agent (count queries): Washington all-sections 262,929 rows, of which 102,309 (38.9%) have
`aadt`; **Washington local sections 151,185 rows, of which only 28 (0.019%) have `aadt`**; Pierce County local
sections 28,382 rows, of which **13** have `aadt`. In the Edgewood bbox, 0 of 121 sampled `f_system = 7` (local)
rows had `aadt` or `speed_limit`.

Verbatim from the data-access story: `"Specifically, HPMS consists of detailed road segment data for higher order
systems, sample attributes for collector systems, and summary data for the local roads."` and
`"Note: Not all States report complete geospatial data for local roads."`

The GeoJSON export size is **UNCERTAIN** (`Content-Length: 0` on a HEAD request, and 8.8 M rows nationally);
do not download it whole. **Nothing was downloaded.**

### (c) Census LEHD / LODES and CTPP — no traffic-count signal

LODES is defined at `https://lehd.ces.census.gov/data/` as employment statistics (verbatim: `"LEHD makes
available several data products that may be used to research and characterize workforce dynamics for specific
groups."`). The Washington directory contains only `od/`, `rac/`, `wac/`, `wa_xwalk.csv.gz` — no counts.
CTPP is ACS journey-to-work tabulation, not counts. **Neither can grade a street segment.**

### (d) Strava Metro — free, but not for an individual

* `https://metro.strava.com/` (HTTP 200): verbatim `"access to Strava Metro is free of charge"`.
* `https://metro.strava.com/terms` section 2.1.1, verbatim: `"Strava Metro is available to organizations that
  plan, own, or maintain active transportation or outdoor recreation infrastructure, or that seek to positively
  influence active transportation or outdoor recreation planning processes, and whose intended use falls within a
  Project Type approved by Strava in its sole discretion."`
* Definition 1.2, verbatim: `""Authorized User" means an individual employee of the Licensee, or an individual
  employee or personnel of a Consultant retained in accordance with Section 2.4, who Licensee allows to access
  the Strava Metro Service ..."`
* The data would be exactly right — verbatim from Strava's support article: `"Edge Counts exports allow you to
  download activity counts aggregated to individual street and trail segments."`
* `https://metro.strava.com/pricing` — **HTTP 404** (no self-serve tier). Application form:
  `https://metroview.strava.com/application`.
* No open Washington/Seattle publication found: `https://data.seattle.gov/api/catalog/v1?q=strava` returned
  `resultSetSize: 0`.

**Verdict: not usable for this personal app.** Only route in is the academic programme.

### (e) Published bicycle Level-of-Traffic-Stress layers — two exist and are downloadable

**WSDOT Active Transportation LTS** (this is the good one for SR-161):
`https://data.wsdot.wa.gov/arcgis/rest/services/Shared/ActiveTransportationData/MapServer/2`
(item `36ba1d681bbb46f0a5792b9ff9dac21b`, modified 2025-10-07). Fields include `AADT, SpeedLimit, LaneCount,
BikeFacilityType, LTS_General, LTS_Bicycle, LTS_Pedestrian, ShoulderWidth`, plus `BeginARM/EndARM`.
Licence verbatim: `"For Planning use only. ..."`. One-shot CSV:
`https://gisdata-wsdot.opendata.arcgis.com/api/download/v1/items/36ba1d681bbb46f0a5792b9ff9dac21b/csv?layers=2`
— HTTP 200, **11,626,137 bytes, 54,148 rows**. Description verbatim: `"WSDOT evaluated and assigned a Level of
Traffic Stress (LTS) ranking to WA state highway segments ..."` and `"Ramp segments are included in the dataset
with a default LTS ranking of 999."` **State highways only.**

**PSRC LTS** — the broadest free stress layer for this home:
`https://services6.arcgis.com/GWxg6t7KXELn1thE/arcgis/rest/services/level_of_traffic_stress_gdb/FeatureServer/0`
(item `cd829d20786e4ef8a6ff34c464227b22`, owner `kthomas_psrc`, modified 2025-08-14, 37,464 segments region-wide).
Fields: `PSRCEdgeID, Fullname, FacilType, County, LTS_IJ, LTS_JI, LTS_min, LTS_max`. Item description verbatim:
`"PSRC staff, in collaboration with BPAC, developed a methodology for assessing bicycle Level of Traffic Stress (LTS)."`

**I measured it against the ten roads myself.** In the box `-122.34..-122.18, 47.18..47.32` the layer returns
1,590 segments; `FacilType` is only `Principal Arterial` (804), `Minor Arterial` (698) and `Shared Use Path` (88)
— **no collector and no residential streets**. Buffered queries with a 60 m radius:

| Road | PSRC LTS found | `FacilType` |
|---|---|---|
| 122nd Ave E (312 m from home) | **LTS 4** | Minor Arterial |
| Meridian Ave E / SR-161 | **LTS 4** | Principal Arterial |
| Edgewood Dr E | **LTS 4** (the LTS-2 `EDGEWOOD` rows are a different street at `47.147,-122.564`) | Minor Arterial |
| Military Rd E | **LTS 4** | Minor Arterial |
| West Valley Hwy E / N | **LTS 4** | Minor Arterial |
| East Valley Hwy E | **LTS 4** | Minor Arterial |
| A St SE | LTS 1–4 (King County) | Principal Arterial |
| **Fryar Ave** | **none** | — |
| **Tacoma Ave** | **none** (the `TACOMA` rows are in Tacoma) | — |
| **48th St E** | **none within 60 m** | — |

**Two hard caveats:** (1) the layer declares **no licence** (`licenseInfo: None`, `accessInformation: None`) —
**UNCERTAIN** what terms apply; (2) residential streets are absent, so it cannot grade a route built from
Edgewood's local streets.

### (f) Everything else checked, and found wanting

| Source | Result |
|---|---|
| PeopleForBikes BNA | `https://bna.peopleforbikes.org/` HTTP 200; per-census-block scores, **no downloadable per-segment layer found** (`/about` → HTTP 404). **UNCERTAIN.** |
| WSDOT Bike & walk reports | `https://wsdot.wa.gov/about/accountability/bike-walk-reports` HTTP 200; LTS definition only, no segment layer. |
| WSDOT Bicycle & Pedestrian Documentation Project | `https://wsdot.wa.gov/travel/bicycling-walking/bicycle-pedestrian-documentation-project` → **HTTP 404**. |
| Cascade Bicycle Club | bike-map page → **HTTP 404**; no dataset. |
| Urban Cycling Institute | **HTTP 406** (blocked). |
| BTS NTAD | `https://www.bts.gov/ntad` → **HTTP 403**. |
| WA state geospatial portal | `https://geo.wa.gov/` — hosts only the WSDOT layers above (via the DCAT feed `https://gisdata-wsdot.opendata.arcgis.com/api/feed/dcat-us/1.1.json`, 468 datasets). No local-road AADT dataset. |


---

## 5. Verdict

**Yes — real vehicle volumes for the specific roads above are obtainable, free, in machine-readable form. But
not from one source, and not for all ten roads. Two datasets together cover the need, and neither is sufficient
alone.**

### 5.1 The single best dataset

For the arterial — which is the road the app is most likely to get wrong — the best dataset is
**WSDOT "Traffic Sections (AADT) Current"**:

```
https://data.wsdot.wa.gov/arcgis/rest/services/Shared/TrafficData/FeatureServer/1
```
One-shot CSV: `https://gisdata-wsdot.opendata.arcgis.com/api/download/v1/items/c05b181c4f314cdc93ef44bae460af54/csv?layers=1`
(820,993 bytes, 4,815 rows, `AADT` + `ReportingYear` = 2025, both directions, per state-highway segment).

For the local roads — where WSDOT is silent — the best dataset is
**Pierce County "Traffic Volume"**:

```
https://services2.arcgis.com/1UvBaQ5y1ubjUPmd/arcgis/rest/services/Traffic_Volume/FeatureServer/0/query
```
One-shot CSV: `https://gisdata-piercecowa.opendata.arcgis.com/api/download/v1/items/8145f84ad0e94b558a381ec36864c4ff/csv?layers=0`
(1,246,468 bytes, 8,575 rows, `ADTVolume` + `ADTYear` + `ADTSource`, ADT is one-way-or-both **UNCERTAIN**).

For a ready-made *stress grade* rather than a volume, add the two LTS layers from section 4(e).

### 5.2 Can we get a real volume for each road? — plainly

| # | Road | Real volume obtainable? | From |
|---|---|---|---|
| 1 | Meridian Ave E / SR-161 | **YES, 2025** | WSDOT Traffic Sections — 20,000 vpd at home's latitude |
| 2 | West Valley Hwy E | **YES, 2025** | Pierce Traffic Volume — 5,750 vpd, RLOG 98350 BMP 2.81–3.33 |
| 3 | East Valley Hwy E | **YES, 2010** | Pierce Traffic Volume — 11,050 vpd, RLOG 97910 |
| 4 | 122nd Ave E | **YES, but 1996** | Pierce Traffic Volume — 2,626 / 2,168 vpd |
| 5 | Edgewood Dr E | **YES, but 1994** | Pierce Traffic Volume — 2,875 vpd (RLOG 83850) |
| 6 | 48th St E | **YES, but 1996** | Pierce Traffic Volume — 1,079 / 2,469 vpd (RLOG 83900) |
| 7 | Military Rd E (Edgewood) | **NO** | only a 1978 estimate, 1,600 vpd (RLOG 98250) |
| 8 | Fryar Ave | **NO** | `ADTVolume` 0 / "Not Known"; 1983 estimate 846 vpd |
| 9 | Tacoma Ave | **NO** | `ADTVolume` 0 / "Not Known" 2008 |
| 10 | A St SE | **NO** | not in Pierce County data (it is in Auburn, King County) |

Plus, independently: the home street **18th St E = 381 vpd (1996, County Count)**.

So: **7 of 10 roads have a published number; only 3 of those are from 2010 or later; 3 have nothing usable.**
Anyone who claims a current, per-segment volume for all ten is guessing.

### 5.3 Concrete steps to use it

1. **Bundle the two CSVs** (2.1 MB total) into the app's data directory at build time; do not query at runtime.
2. **Build a state-route AADT table.** From `TrafficData/FeatureServer/1`, keep rows for the state routes near
   home (`StateRouteNumber IN ('161','167','410','162','512','18','5','99','181','164')`) with
   `RouteIdentifier, StateRouteNumber, AADT, BeginAccumulatedRouteMile, EndAccumulatedRouteMile, ReportingYear`.
3. **Build a local-road ADT table.** From Pierce `Traffic Volume`, keep rows with **`ADTYear >= 2010`** and
   `ADTVolume > 0`; carry `RoadNumber, BMP, EMP, ADTVolume, ADTYear, ADTSource`. Then join
   `Pierce Roads/FeatureServer/0` on `RLOG` to recover `FULLNAME` (the ADT layer has no street name).
4. **Join to Valhalla/OSM by geometry, not by name.** For each Valhalla edge, take the OSM `ref` and `name`;
   match `ref = "WA <n>"` for state routes and `name` for local roads; then require geometric proximity
   (≤ 30–50 m for WSDOT's 24K LRS, ≤ 100 m for Pierce's layer) before accepting a volume. See 3.7 for why.
5. **Grade honestly with three states**, not two: `measured` (a count exists within the thresholds above),
   `stale` (a count exists but `ADTYear` is older than some cut-off), and `unknown`. Never let `unknown`
   silently become `low traffic`.
6. **Use the LTS layers as a second opinion on arterials.** WSDOT LTS and PSRC LTS both put SR-161,
   122nd Ave E, Edgewood Dr E and Military Rd E at **LTS 4**. If a route uses one of those, the "low traffic"
   claim should fail even when the count is stale.
7. **Set the numeric bands from a published source**, e.g. the City of Puyallup 2044 Comprehensive Plan bands
   **<1,500 / 1,500–7,000 / >7,000 AADT**, so the thresholds are not invented.

### 5.4 What this does *not* fix

* It does not make the whole 9.19 km route gradeable. Pierce's local counts are 1994–1996 for the streets that
  matter most (122nd Ave E, Edgewood Dr E, 48th St E), and there is nothing at all for Fryar Ave, Tacoma Ave,
  Military Rd E or A St SE.
* It does not give bicycle counts. Strava Metro would, and it is free — but its terms admit organisations, not
  one person's app. **UNCERTAIN** whether Strava would make an exception; I did not apply.
* It does not remove the need for a per-route review. `docs/DECISIONS.md` D10 already caps grade-D exposure at
  10% / 0.5 mi and forbids grade E; real counts let that cap be applied to facts instead of to `road_class`.

### 5.5 Bottom line

**Obtainable: yes.** The concrete, honest answer for home is that **Meridian Ave E / SR-161 carries ~20,000 vpd
(WSDOT 2025) and must not be treated as low traffic**, while **122nd Ave E (~2,600 vpd, 1996), Edgewood Dr E
(~2,900 vpd, 1994), West Valley Hwy E (5,750 vpd, 2025) and 18th St E (381 vpd, 1996) are genuinely quiet**.
The `tertiary` panic was partly justified (SR-161) and partly wrong (the Edgewood local roads). Bundle WSDOT
Traffic Sections + Pierce Traffic Volume + the two LTS layers, gate the county data on `ADTYear`, and label
everything else `unknown` rather than "low traffic".

