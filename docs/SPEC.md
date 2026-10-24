# SPEC — Bike Trail Planner (personal Android app)

Status: v1 brief, agreed with the user on 2026-09-15. Decision history: `docs/DECISIONS.md`.

## 1. What it does

Plans a **safe bicycle round trip from home**: current location -> a bike trail near home -> ride the
trail for the mileage that is left -> back home. It rotates between routes so the same ride does not
repeat. It shows the planned route on a Google map for confirmation, then hands the trip to Google
Maps and starts bicycle navigation.

## 2. Who uses it

One person: the user (`jitsmaster`), on their own Android phone, riding from
**12424 18th St E, Edgewood, WA 98372** (`47.240371, -122.262019`). Every ride starts and ends at
home. There are no other users, no accounts, and no sharing.

## 3. Why

The user wants to ride bike trails, not fight traffic to reach them. The route to the trail is the
risky part of the trip and the part that is tedious to plan by hand: it must prefer separated paths
and low-traffic streets, and it must avoid being sent down a 40 mph arterial with no bike lane.
Google Maps will not optimise for that, so this app computes the route itself and uses Google only
to display and to navigate.

## 4. Grounding

Everything below is verified in this repo, not assumed:

| Source | What it establishes |
|---|---|
| `docs/research/google-maps-handoff.md` | Google exposes no bike-lane, traffic, or safety data. Google Maps will never navigate a third-party route geometry. Maps URL waypoint ceiling is 9 (3 on mobile browsers). |
| `docs/research/google-maps-device-test.md` | **On a real Android device**: a Maps URL with `origin` omitted, `destination` = loop start, `waypoints`, `travelmode=bicycling`, `dir_action=navigate` opens **straight into multi-stop turn-by-turn** with the stops queued and zero taps. A known-far `origin` produces a **preview only**. |
| `docs/research/bike-safe-routing-engines.md` | Valhalla has the only free bike-traffic-stress model, and returns per-edge `cycle_lane`, `road_class`, `speed_limit`, `shoulder`, `bicycle_network`, `surface` via `/route` -> `trace_attributes`. No engine returns a safety score; we compute it. |
| `docs/SERVER.md` | The self-hosted Valhalla server on this Mac (Tailscale-reachable), its exact config, and a verified route from home. |
| `docs/data/trails.json` | Trail geometry, lengths, surfaces, and the connectivity graph for the near-home trail set. |

## 5. Locked decisions

| # | Decision |
|---|---|
| D1 | **Shape B.** We compute the safe route; Google Maps navigates it. Confirmation happens in our app. |
| D2 | The mileage the user types is **total round-trip miles**. Trail portion = target minus the round-trip commute. |
| D3 | Trail portion shape is **automatic**: a loop when the trail loops, otherwise out-and-back (A->B->A). |
| D4 | Trail set is **near-only, ~8 km** from home: Sumner Link, Riverwalk, Interurban (Edgewood + Milton), White River, Lake Tapps Parkway. |
| D5 | **Self-hosted Valhalla** in Docker on this Mac (Washington extract), reached by the phone over **Tailscale**. |
| D6 | "Alternate" = **rotation with memory**: propose the viable route ridden longest ago; never the same signature twice in a row; show up to **five** options (**user, 2026-09-16**: "increase the recent trips on UI to 5", then "both" - so both the saved-trip suggestions and the plan's alternative options are capped at 5, not 3). |
| D9 | When a trail is too short for the trail mileage: **chain trails**; **cap** the trip when nothing fits; **never auto-lap**. |
| D10 | Safety is **capped caution**: only grades A/B/C, plus at most **10% and 0.5 mi of grade D**, and **never grade E**. Caution segments are always named. |
| D11 | Surfaces: **paved, compacted, gravel allowed**; dirt, rough, impassable rejected. |
| D12 | If the Mac is unreachable, **fall back to the public Valhalla server** (`valhalla1.openstreetmap.de`, no key) with a visible badge. |

## 6. Safety grading

Per road segment, from Valhalla `trace_attributes` edges:

| Grade | Rule |
|---|---|
| **A — trail** | `edge.use` is `cycleway`, `path`, or `mountain_bike`; or `cycle_lane` is `separated`; or `bicycle_network` is set. |
| **B — protected road** | `cycle_lane` is `dedicated`, and `speed_limit` <= 25 mph. |
| **C — shared quiet road** | `road_class` is `residential` or `service`, or `use` is `living_street`, with `speed_limit` <= 25 mph. |
| **D — caution** | `road_class` is `tertiary` or `secondary`, at 30-35 mph, with no dedicated lane. |
| **E — reject** | `road_class` is `primary`, `trunk`, or `motorway`; or `speed_limit` >= 40 mph with no dedicated lane or separated path. |

`surface` of `dirt`, `rough`, or `impassable` rejects the edge (D11). The route keeps a per-grade
metre count; the UI shows the breakdown and names every D segment.

### 6b. Measured traffic volume beats road class (added 2026-09-15, after `docs/research/traffic-data-wa.md`)

Road class was condemning roads that are actually quiet. Measured volumes at home:

| Road | AADT | Year | Verdict |
|---|---|---|---|
| 18th St E (leaves home) | **381** | 1996 | genuinely quiet |
| 122nd Ave E | 2,626 | 1996 | low-medium, but **stale** |
| Edgewood Dr E | 2,875 | 1994 | low-medium, but **stale** |
| West Valley Hwy E | 5,750 | 2025 | medium |
| East Valley Hwy E | 11,050 | 2010 | high |
| SR-161 / Meridian Ave E | **20,000** | 2025 | correctly high |

So the grade ladder is fed in this priority order:

1. **Measured volume**, when a count exists and is fresh: bands <1500 / 1500-7000 / >7000 AADT
   (Puyallup 2044 Comprehensive Plan bands). Low volume cannot be grade D.
2. **Published Level of Traffic Stress**, where available (WSDOT `ActiveTransportationData/MapServer/2`,
   PSRC `level_of_traffic_stress_gdb`), shown as an advisory badge. Note the conflict we must display
   honestly: 122nd Ave E and Edgewood Dr E are published as **LTS 4 (high stress)** while carrying only
   ~2,600-2,900 vehicles/day from **1994-1996** data.
3. **Speed limit / road class from OSM**, when neither of the above exists.
4. **UNGRADED** when nothing applies.

Two hard rules: **a count of 0 means "Not Known", never zero traffic** (Pierce County uses 0 that way),
and **unknown must never become "low traffic"**. Counts older than ~10 years are marked **STALE** and
displayed with their year, so the rider sees that 1996 data is 1996 data.

Join rules for the datasets (they share no ID with OSM): join by **route identity plus geometry
(30-50 m)**, never by the `Location` string -- 61.6% of WSDOT sections have Location mileposts that
disagree with AccumulatedRouteMile -- and never by Pierce RLOG alone, because one RLOG is a route, not a
street (RLOG 98060 = 122nd Ave E + 36th St E + 48th St E + Edgewood Dr E).

**UNGRADED is a first-class state (added 2026-09-15, after `docs/SAFETY-PROBE.md`).** Measured fact:
82-100% of every route from home runs on roads with **no `maxspeed` in OpenStreetMap** -- `18th Street
East` and `122nd Avenue East`, the two roads leaving the house, carry `{"highway":"tertiary"}` and
nothing else. `edge.lanes` is never returned by this Valhalla build. So speed limits cannot be graded
from OSM alone.

Grades are therefore computed from **two inputs, merged in this order**:

1. A bundled **local override table** (`assets/limits.json`): posted speed limits and, where we can get
   them, real traffic counts (AADT) for the roads that matter near home. Derived from the user's own
   knowledge of their roads, and from WSDOT / Pierce County open data where it exists.
2. OpenStreetMap `maxspeed` and road class, for anything not in the table.

A segment with neither a speed limit nor an override is **UNGRADED**. Rules for UNGRADED segments:

- They are **never** reported as safe, and they never count towards the A/B/C mileage.
- They are named in the UI, exactly like D segments.
- They count against a separate budget: the app shows "graded 18% / ungraded 82%", and the user decides.
- `D10`'s cap is evaluated over **graded** segments only, and the ungraded share is always displayed
  next to it. A route whose ungraded share is above a threshold is marked **NOT VERIFIED** in the
  confirmation screen. The threshold is the `unverified_ungraded_share` knob in `assets/config.json`
  (0.5, i.e. mark the ride when most of it was never measured).
- **D10 has exactly three outcomes** (`domain/Models.kt`, `SafetyOutcome`), and they are not two:
  **PASS** (no known violation *and* enough of the ride was graded to stand behind that),
  **UNVERIFIED** (no known violation, but too much of it is ungraded to vouch for), and **FAIL**
  (a known violation: grade E, a rejected surface, or grade D past the cap).
  **UNVERIFIED is not a refusal.** Measured over 203 traces / 1,351.5 km of the local network, 48.53%
  of it is ungraded, so treating "not assessed" as "failed" would refuse nearly every ride from home.
  It is offered, with the state shown on the ride's chip and in the sheet. Only a known violation
  refuses. (Implemented 2026-09-21 — see D41: this section described the threshold from 2026-09-15
  but the code ignored the knob and could report a pass on a barely-graded route.)

**The app cannot tune its way to safety.** Measured: `use_roads` 0.0/0.05/0.1 x `use_living_streets`
0.25/0.5 x `bicycle_type` hybrid/city/road all returned **byte-identical geometry**. The options change
`cost` and `time`, never the path. Only `shortest:true` shifts the path (by 0.2-2%) and it never removes
a D segment. Safety therefore comes from **data quality and access-point choice**, not from Valhalla's
cost function. Do not build features that assume a cost knob will fix a bad route.

## 7. How a plan is computed

1. **Origin = the live GPS position, always (D22, 2026-09-15).** Read the device position fresh for
   every plan and use it as the start AND the finish of the round trip. Never substitute home, and never
   reuse a cached fix: if the fix is older than a minute or its accuracy is worse than about 50 m, say
   so and wait for a better one rather than planning from a stale point. Home is no longer special as an
   origin -- it is a saved place where the bundled data is richest.
   Consequences that must be handled explicitly:
   - The **trail search radius is measured from the current position**, not from home. The bundled six
     trails only apply near home; elsewhere the app must say it has no trails for this area and offer
     discovery (see D23) instead of planning a ride to a trail 30 miles away.
   - The **round trip returns to the same live origin**, not to home.
   - The **hand-off URL still omits `origin`** so Google navigates from the live position, and its
     `destination` is the live origin (the point that closes the loop). This is the exact shape the
     device test validated.
   - The **traffic override table and AADT assets are area-limited** (Pierce County + WSDOT state
     highways). Away from home they match nothing, so most roads become UNGRADED. The UI must say
     "no local traffic data for this area" rather than implying the route is fine.
2. **Candidates.** Build candidate itineraries from the near trail set:
   - *Single trail:* safe commute to the nearest access point, then out-and-back along the trail for
     the remaining mileage, or the loop when the trail loops.
   - *Chained corridor:* enter the corridor at its nearest access point, ride trail segments in
     sequence, turn around where the trail mileage is used up, return along the corridor.
   - The commute is measured by Valhalla, not by straight-line distance, so candidates are ranked
     after routing, not before.
   - **Which access points are tried, and in what order (D24, fixed 2026-09-16):** by the
     **measured approach distance** in `assets/access_points.json` (`measured_route_km_from_home`,
     one `POST /route` per candidate at build time), with crow-flies distance as the tie-break and
     the OSM node id after that so the order is deterministic. Crow-flies alone is not a usable
     proxy: on the Sumner Link Trail the 28 nearest access points by crow-flies ALL fail, because
     their approach is walled off behind Jovita Blvd E and 142nd Ave E, while the 6 nearest by
     measured road distance all pass.
   - **Away from the saved home the approach is MEASURED, not guessed (D24/D22).** The bundled
     `measured_route_km_from_home` is measured from the saved home, so it only describes the rider's
     approach while they are within `PROBE_ORIGIN_TOLERANCE_M` (2 km) of it. Farther away than that,
     the planner takes the 8 entrances per trail that are closest by crow-flies and calls
     `POST /route` from the live origin for each one (`Planner.liveApproachM`), then ranks by those
     measured lengths. The calls go through the same cached `RouteService` as everything else, so a
     repeat plan from the same place is free. Measured: a plan from 3.7 km away takes 545 ms.
   - **Alternate access points must be untried ones (D24).** The second pass over access points
     takes the next candidates down the same ranked list (the already-tried set is passed in and
     skipped). Re-selecting the nearest N a second time adds nothing: `detour_access_points_per_trail`
     (4) is a subset of `access_points_per_trail` (6).
3. **Route.** Call Valhalla `/route` with `costing=bicycle`, `bicycle_type=hybrid`, `use_roads=0.1`,
   `avoid_bad_surfaces=0.3`, `use_hills=0.25` for the road legs (home -> access point, and any
   connector link).
   **The return leg is routed, not mirrored (D31, `docs/LOOPS.md`).** The rider asked not to ride the
   same way back, so the return is a separate `/route` call, optionally through a via point placed off
   the outbound corridor, and the ride is scored by how much of the outbound road set the return
   re-uses (measured with the way ids `trace_attributes` already returns). A loop is preferred up to
   **5 km** of extra distance; if no compliant loop exists, the return is mirrored and the app says so.
4. **Grade.** Call `trace_attributes` on the route shape and grade every edge (section 6).
5. **Enforce D10.** Discard any candidate that uses grade E, or more than 10% / 0.5 mi of grade D.
   The D13 detour bands are measured against **the longer of the shortest candidate and the mileage
   the rider asked for** (D25, fixed 2026-09-16): a compliant route within the requested mileage is
   what the rider asked for, not a detour, and must not be charged as one.
   If nothing survives, do NOT expect a costing tweak to help -- the
   sweep proved the geometry is identical across costings. Instead choose a different **access point**
   (the probe found 143 routable access points across the 6 trails) and recompute.
   **If still nothing survives, ADVISE - do not block (D42, rider 2026-09-21).** The best attempt
   becomes the ride the app offers: it is drawn, listed and handed to Google Maps like any other, and
   the screen states the outcome as **UNSAFE** with exactly which segments disqualified it, street by
   street and count by count. The rule that survives unchanged is *never silently ship an unsafe
   route* - it is shipped **loudly**, never called safe, never described as compliant. The app's job is
   to tell the rider the truth about a road, not to take the decision away from them. `REFUSED` now
   means only that no route could be computed at all, and is the only state with nothing to hand over.
5b. **Look for a quieter way (D44, added 2026-09-21).** A compliant ride can still be a ride down the
   arterials, and steps 1-5 cannot see that: step 1 asks Valhalla for the shortest way and step 5b only
   runs when nothing is compliant. So, when the best compliant ride is mostly bigger roads - under
   `quiet_share_target` (0.35) of its distance on streets that suit a bike, or carrying
   `quiet_busy_floor_m` (300 m) of grade D or known >25 mph - the planner searches a **bounded** set of
   small-street via-points (`max_quiet_evals` 6 proposals, `quiet_max_vias` 2 streets each) and keeps a
   quieter ride if one exists. Streets come from the D40 pool; a ride through them grades as it grades
   (usually UNVERIFIED) and is never called safe. Distance is the last tiebreak, never the first.
6. **Choose (D6).** Compute a route signature (ordered trail segment ids + access and turnaround
   points, rounded). Prefer the surviving signature ridden longest ago. Never repeat the last one.
7. **Show.** Draw the route on a Google map in-app, coloured by grade, with the summary: trail miles,
   commute miles, caution mileage, and the named caution streets.
8. **Hand off.** On confirmation, build the Maps URL and start navigation:
   `https://www.google.com/maps/dir/?api=1&destination=<home>&waypoints=<...>&travelmode=bicycling&dir_action=navigate`
   with **no `origin`** so Google starts guidance immediately from the live position.

### 7b. Closed trail segments (added 2026-09-15, with provenance)

The middle **2.37 km of the Sumner Link Trail is officially closed**. Evidence: OSM way 419747831 was
`highway=cycleway` with `bicycle=designated` from 2016-05-20 to 2025-09-28 (six versions), then on
**2026-06-01** changeset 183511084 by user `fencebuilder2000` changed only the tags, comment
*"Updating to reflect closure"*, with **`source=https://sumnerwa.gov/wp-content/uploads/2025/05/TrailClosed-2048x1225.png`**
-- a City of Sumner "Trail Closed" notice. So the closure is authoritative, not a mapping error, and the
notice itself dates from May 2025.

Consequences the code must honour:

1. **Trail mileage is measured on the routable trail, not the nominal relation length.** Valhalla's graph
   already drops `bicycle=no` ways, so a planner that measures "how much trail is left" from the
   nominal geometry will over-promise. Compute the rideable trail length from the graph, along the route
   actually being planned.
2. **Do not assume a trail is continuous.** A closed middle splits a trail into stubs, which also splits
   any corridor it participates in (Sumner Link is one of the three trails forming the 18.24 km
   corridor). When a closure breaks a corridor, the planner routes around it on roads and the grader
   then scores those roads -- which is correct behaviour, and must be visible in the summary.
3. **Surface the provenance, not just the fact.** The UI states what is closed, since when, and on whose
   authority, so the rider can judge whether it is temporary.
4. **This specific closure is permanent (D15, user-confirmed):** the middle 2.37 km of Sumner Link is "cut off by private property now". Do not imply or promise reopening, and never count that segment as rideable trail mileage. Keep the cheap re-check anyway (it costs one HTTP call), but as a data-hygiene habit, not as an expectation. Historically: closures are usually temporary, so the app should still be able to re-read the tags for the ways in `assets/trails.json` (a cheap OSM API call: `way/<id>.json`, and `way/<id>/history.json` to see whether it reopened) and unlock a segment automatically if the `bicycle=no` tag disappears. Never write a closed segment off permanently in the code.** The app should be able to re-read the tags
   for the ways in `assets/trails.json` (a cheap OSM API call: `way/<id>.json`, and
   `way/<id>/history.json` to see whether it reopened) and unlock a segment automatically if the
   `bicycle=no` tag disappears. Never write a closed segment off permanently.

### 7c. Detour policy (D13, decided 2026-09-15)

When the direct route violates D10, the planner must **look for a compliant alternative before
refusing**. Measured context: the 12-mile plan from home produced trail 2.45 mi / commute 9.55 mi with
**2.63 mi of grade D on Jovita Blvd E (10,358 vpd, 1996, LTS 4)**, which breaks the 0.5 mi cap. The
direct route is unsafe; a longer one may not be.

Rules:

1. **Via-points are the only lever.** The costing sweep proved that `use_roads`, `use_living_streets`
   and `bicycle_type` all return byte-identical geometry, so diversity must come from forcing the route
   through quiet intermediate points. Generate candidates from two sources (D40, added 2026-09-21):
   - **measured** — low-volume links from `assets/traffic.json` (band `low`, or `medium` with a real
     count). Tried first: a counted quiet road is the best evidence the app has.
   - **class** — the small streets themselves: `residential`, `service`, `service_other`,
     `living_street`, whose own default speed is under 25 mph (Valhalla's Washington table puts
     residential at 18). Tried next. Measurements still win: a small street with a count of its own is
     judged by the count, and a small street signed above 25 mph is refused. Tertiary (default 39 mph)
     and anything larger is never admitted by class.
   A via-point selected by class does **not** claim a speed for the street, and the route through it is
   graded by the same ladder as any other, so an unposted uncounted street still grades UNGRADED and
   the ride can still come back UNVERIFIED. Every via-point is named on the result screen with the
   reason it was chosen (`domain/QuietLinks.kt`).
   Alternate access points along the corridor remain the third source.
2. **Acceptance bands.** A compliant candidate is acceptable when its total distance is at most
   **1.40x** the shortest route. Search in bands: +0%, +25%, +40%. If nothing compliant exists, try up to
   **+80%**, and if one is found, present it as an explicit detour with the extra distance and extra
   time shown. Never take a detour silently.
3. **The mileage target still wins (interaction with D2).** The target is total round-trip miles, so a
   longer commute eats the trail portion. Keep the total at the target by shortening the trail ride.
   If the compliant detour pushes the trail portion below the **2.0 mi floor**, do not silently deliver
   a token trail ride: report the tradeoff and offer (i) accept a longer total, (ii) plan a different
   trail, or (iii) see why the direct route was refused.
4. **Never exceed the requested mileage without saying so.** If the only compliant route is longer than
   the target, state that before confirmation.
5. **Search harder when asked (D43).** "Try a safer detour" is a real action, never a no-op: with a
   trail-floor offer it plans the longer total of rule 3; otherwise it re-plans **once** with a wider
   band (`SEARCH_HARDER_FACTOR` 2.50x instead of the shipped 1.80x, and twice the via-point pool) and
   reports the answer either way. The wider budget is one-shot: the next plan is the shipped search.
6. **Advise last, and explain (D42).** If no compliant candidate exists in any band, offer the best
   attempt and name every disqualifying segment with its traffic count and year -- as the app already
   does.

## 8. Waypoint packing (the fidelity limit)

Google re-computes the route through the waypoints, so the waypoints must pin the parts that matter.
We have at most **9**. Pack in priority order, and stop at 9:

1. The corridor access point (where the ride leaves the road network for the trail).
2. The turnaround point (where the rider turns back).
3. Any corner where our safe route differs from the obvious direct route.
4. Points where our route uses a specific separated path or crosses a hostile road.

Keep the URL under 2048 characters. If a safety-critical point does not fit, the UI must say so
rather than pretend the hand-off is exact.

## 9. Architecture

```
app/src/main/
  assets/trails.json           trail geometry + connectivity (from docs/data/)
  assets/traffic.json          AADT + LTS per road, with year and confidence (section 6b)
  java/.../data/               TrailRepository, ValhallaClient, RouteCache (Room), RideHistory (Room)
  java/.../domain/             Planner (mileage math, chaining, D2/D3/D9)
                               SafetyGrader (section 6)
                               MapsUrlBuilder (section 8)
                               Rotator (D6)
  java/.../ui/                 PlanScreen, ConfirmMapScreen (Maps SDK), summary card
  java/.../nav/                GoogleMapsLauncher (ACTION_VIEW, section 7 step 8)
                               VelotricLauncher (D35 assist: opens the Velotric app, never presses it)
                               RideWatchService + RideReminder (D36: watch the Maps navigation
                               notification and prompt when it disappears)
```

- **Kotlin 2.0.21, Jetpack Compose (Material 3), Room, Navigation Compose, OkHttp,
  kotlinx.serialization** — the same stack as the user's `store-gps` project.
- **Gradle 8.13 / AGP 8.7.3, minSdk 26, targetSdk 34, compileSdk 35.**
- **Maps SDK for Android** (`play-services-maps`) to draw the confirmed route. Needs one API key
  restricted to the app's package name and SHA-1.
- **Attribution (ODbL, mandatory):** "© OpenStreetMap contributors" must appear anywhere route or
  trail data is shown. See `docs/DATA-LICENSES.md`.
- **Valhalla endpoint** is read from `BuildConfig` so the Tailscale host is a one-line change:
  `http://admins-mac-mini:8002` (Tailscale `100.85.43.11`), with the public server as D12 fallback.

## 10. Deliverables

1. `docs/SPEC.md` (this file), `docs/DECISIONS.md`, `docs/SERVER.md`, `docs/data/`.
2. The Android app, buildable with `./gradlew :app:assembleDebug`.
3. JVM unit tests for: mileage math, corridor chaining, safety grading, waypoint packing, rotation.
4. An emulator demo on the `pra_test` AVD with GPS teleported to `47.240371,-122.262019`.
5. `README.md` with the exact run steps, the server start command, and the phone test command.
6. The exact Maps URL from a real plan, so the user can paste it into a terminal and check
   navigation on their own phone.

## 11. Out of scope for v1

Accounts and cloud sync; offline maps; in-app turn-by-turn navigation; weather; mountain-bike
specific routing; driving to distant trailheads; multi-user support.

## 12. Known risks

| Risk | Mitigation |
|---|---|
| Google re-routes through our waypoints and drifts onto a busier road. | Waypoints at safety-critical points (section 8); name the caution segments in the UI; the user confirms before navigation. |
| The Mac is asleep or Docker is down, so Valhalla is unreachable. | D12 fallback to the public Valhalla server, with a visible badge. |
| `trace_attributes` fails to match the route shape. | Try `edge_walk`, then `map_snap`; if both fail, fall back to Valhalla's own `use_roads` preference and mark the route's safety as **unverified** in the UI — never as safe. |
| Trail geometry in OSM is wrong or mis-tagged. | `docs/data/trail-corridor.md` verifies each relation; the Sumner Link relation is under verification before it is trusted. |
| The commute cannot be made safe at all for a given trail. | Offer the best attempt with outcome **UNSAFE** and the reason, named street by street (D42). The rider can still navigate it. |
| Most of the route is UNGRADED because OSM has no speed limits (measured: 82-100%). | The local override table, the always-visible ungraded share, and the NOT VERIFIED banner. The table is seeded from the user's own knowledge and from WSDOT/Pierce County open data. |
| **A trail segment is officially closed mid-route (measured: Sumner Link's middle 2.37 km, City of Sumner notice).** | Section 7b: measure rideable trail miles from the graph, never assume continuity, show provenance, and re-check the tags periodically instead of writing the segment off. |
| A trail relation member is tagged `bicycle=no` and disappears from the routing graph. | Measured: Sumner Link member way 419747831 (2370 m, the MIDDLE of the trail) is `access=no bicycle=no foot=no`, so a bike trip between its ends costs 3.383 km with 2491 m off-trail. The app must detect `access`/`bicycle` exclusions in trail members and tell the rider the trail cannot be ridden through, instead of pretending the geometry is continuous. |
