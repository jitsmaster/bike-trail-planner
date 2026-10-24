# Handing a pre-computed bicycle round-trip route to Google Maps

**Question.** How can an Android app hand a *pre-computed* bicycle round-trip route to Google Maps for
turn-by-turn navigation, and what are the hard limits?

**Method.** All facts below were read on 2026-09-15 from primary Google sources (developers.google.com
documents, Google Maps Platform pricing/terms, Google Maps Help Centre). Every finding carries the exact
doc URL and a verbatim quote. Items that the docs do **not** state are marked **[NOT DOCUMENTED]** or
**[UNCERTAIN]** — no guessing.

**Bottom line.** Google Maps will **never** navigate a third-party-computed geometry. There are exactly
four supported ways to get a route "into" Google, and none of them accepts an app's own polyline for
bicycle navigation:

1. **Maps URLs / `google.navigation:` intent** — hand over *waypoints as places or coordinates only*.
   Google Maps then computes its own bicycle route through those points. Works today, no API key, but the
   app loses control of the geometry and of the number of shaping points (9 waypoints max).
2. **Routes API / Directions API `travelmode=bicycling`** — get Google's own bicycle route (with an
   optional shaping influence from an encoded polyline), draw it in-app for confirmation. Google offers
   **no official in-app turn-by-turn SDK for bicycles** (the Navigation SDK's pre-planned-route feature is
   DRIVE/TWO_WHEELER only).
3. **Navigation SDK for Android** — official in-app turn-by-turn, and `RoutingOptions.TravelMode.CYCLING`
   exists, but the "planned route via route token" path (the only way to feed a specific polyline into
   navigation) is **not available for `BICYCLE`**. Enterprise-tier billing applies.
4. **Draw the route yourself** (`PolylineOptions`) and run your own guidance. This is the only path that
   gives full fidelity to the app's computed geometry.

---

## 1. Google Maps URLs (`https://www.google.com/maps/dir/?api=1&...`)

Doc: <https://developers.google.com/maps/documentation/urls/get-started>

### 1.1 Supported parameters (directions action)

- `api=1` (required), `origin`, `destination`, `origin_place_id`, `destination_place_id`, `travelmode`,
  `dir_action=navigate`, `waypoints`, `waypoint_place_ids`, `avoid`.
- Quote (required param): *"The parameter `api=1` identifies the version of Maps URLs this URL is intended
  for. This parameter is required in every request. The only valid value is 1. If `api=1` is **NOT**
  present in the URL, all parameters are ignored and the default Google Maps app will launch…"*
- No API key needed: *"You don't need a Google API key to use Maps URLs."*
- URL length limit: *"Additionally, URLs are limited to 2,048 characters for each request. Be aware of this
  limit when constructing your URLs."*
- `avoid` values: *"It can be set to one or more of these options, separated by a comma character"* `","`
  *(URL-encoded as %2C)*, then the values listed one per line as *"ferries"*, *"highways"*, *"tolls"*.

### 1.2 `travelmode=bicycling` is a documented value and is honoured

- Quote: *"travelmode (optional): Defines the method of travel. Here's the list of options: driving …
  walking: Prefers pedestrian paths and sidewalks, where available. bicycling: Routes through bike paths,
  where available. "Bicycling" refers to human-powered travel mode. two-wheeler … transit"*
- Documented example (bicycling): *"The following example launches a map with bicycling directions from the
  Space Needle to Pike Place Market, in Seattle, WA."* —
  `https://www.google.com/maps/dir/?api=1&origin=Space+Needle+Seattle+WA&destination=Pike+Place+Market+Seattle+WA&travelmode=bicycling`
- **[NOT DOCUMENTED]** Region-level availability of bicycle directions in the *app* through a Maps URL.
  Google Maps Help says only: *"When you ride your bicycle, you can use cycling routes if they're available
  in your country or region."* (<https://support.google.com/maps/answer/144339?co=GENIE.Platform%3DAndroid>)
  Treat a missing bicycle route as a normal failure case.

### 1.3 Waypoints: how many, and in what form

- Quote (limit): *"The number of waypoints allowed varies by the platform where the link opens, with up to
  three waypoints supported on mobile browsers, and a maximum of nine waypoints supported otherwise."*
- Quote (form): *"Each waypoint can be either a place name, address, or comma-separated latitude/longitude
  coordinates."*
- Quote (order): *"Waypoints are displayed on the map in the same order they are listed in the URL."*
- Quote (encoding): *"Strings should be URL-encoded, so waypoints such as "Berlin,Germany|Paris,France"
  should be converted to Berlin%2CGermany%7CParis%2CFrance."*
- Quote (escape hatch — the key limit for this app): *"Waypoints are not supported on all Google Maps
  products; in those cases this parameter will be ignored."*
- **[UNCERTAIN]** Which bucket the Android *Maps app* falls into ("mobile browsers" = 3, "otherwise" = 9).
  The doc does not name the Android app explicitly. Google Maps Help independently confirms 9 stops in the
  app UI: *"You can add up to 9 stops, which includes the final destination."*
  (<https://support.google.com/maps/answer/144339?co=GENIE.Platform%3DAndroid>)
- Only place names, addresses and lat/lng coordinates are accepted. **No encoded polyline, no GPX, no KML,
  no "here is my geometry" parameter exists** — see §3.

### 1.4 `dir_action=navigate` — and what happens with waypoints

- Quote: *"dir_action=navigate (optional): Launches either turn-by-turn navigation or route preview to the
  specified destination, based on whether the origin is available. If the user specifies an origin and it
  is not close to the user's current location, or the current device location is unavailable, the map
  launches a route preview. If the user does not specify an origin (in which case the origin defaults to
  the current device location), or the origin is close to the user's current location, the map launches
  turn-by-turn navigation. Note that navigation is not available on all Google Maps products and/or between
  all destinations; in those cases this parameter will be ignored."*
- Corroborating statement from Google Maps Help: *"Important: To start navigation, your starting point must
  be your location. Otherwise, you get a preview of the route instead."*
  (<https://support.google.com/maps/answer/144339?co=GENIE.Platform%3DAndroid>)
- **Practical consequence (derived, not a direct quote):** a loop URL that names an `origin` will land on a
  **route preview**, not on turn-by-turn navigation. To get turn-by-turn you must omit `origin` (Maps then
  uses the current location), which is exactly the behaviour a round trip wants — origin = destination =
  the user's current position.
- **Does Maps show the multi-stop route before starting navigation?** **[NOT DOCUMENTED]** for the URL
  case. The docs state only that the action is "turn-by-turn navigation or route preview" and that
  waypoints "are displayed on the map in the same order they are listed in the URL". **[UNCERTAIN]**
  whether `dir_action=navigate` with `waypoints` (and no origin) starts guidance immediately or shows the
  stop list first. Google also warns that waypoints may simply be *ignored* on some products; so a URL with
  waypoints is not a guaranteed multi-stop handoff. Verify on a device before relying on it.
- **[NOT DOCUMENTED]** Any parameter to force "preview only" or "navigate the whole loop as one trip".

### 1.5 URL budget for a round trip

A lat/lng waypoint costs roughly 18–24 characters after encoding. A round trip with the origin omitted and
8 shaping waypoints is about **230 characters** — far below the 2,048-character ceiling:

```
https://www.google.com/maps/dir/?api=1&origin=47.3769,8.5417&destination=47.3712,8.5701&travelmode=bicycling&waypoints=47.39010,8.55020%7C47.40120,8.56280%7C47.41230,8.57310%7C47.40620,8.59020%7C47.39210,8.59810%7C47.38080,8.58550
```

So the 2,048-character limit is **not** the binding constraint; the binding constraint is that only 9
waypoints (3 on "mobile browsers") are accepted, and only as places/coordinates.

---

## 2. Legacy intent `google.navigation:q=...&mode=b`

Doc: <https://developers.google.com/maps/documentation/urls/android-intents>

- Quote (purpose, and the hard restriction): *"Use this intent URI to launch Google Maps navigation with
  turn-by-turn directions to the address or coordinate specified. Directions are always given from the
  user's current location."*
- Quote (documented parameters — this is the complete list):
  *"`q`: Sets the endpoint for navigation searches. This value can be latitude and longitude coordinates or
  a query-formatted address. If it's a query string that returns more than one result, the first result is
  selected."* … *"`mode` sets the method of transportation. `mode` is optional and can be set to one of the
  following:"* `d` for driving (default) / `b` for bicycling / `l` for two-wheeler / `w` for walking.
  … *"`avoid` sets features the route should try to avoid. `avoid` is optional and can be set to one or
  more of the following:"* `t` for tolls / `h` for highways / `f` for ferries.
  (The doc lists each `mode` and `avoid` value on its own line, without commas.)
- Quote (bicycling example): *"If you prefer a bit of exercise, you can request bicycling directions
  instead."* — `google.navigation:q=Taronga+Zoo,+Sydney+Australia&mode=b`
- **`mode=b` works.** It is an explicitly documented value with a documented example.
- **Waypoints / intermediates: not supported.** The parameter list contains only `q`, `mode`, `avoid`, and
  the page states directions are *always* from the user's current location. Verified by absence: the raw
  page for `android-intents` contains **zero** occurrences of the strings `waypoints`, `intermediate`,
  `dir_action`, and `maps/dir`.
- **[NOT DOCUMENTED]** Any documented restriction such as "navigation is not available with mode=b in
  region X". The only documented caveat is the intent's own: turn-by-turn navigation is a
  platform-specific, mobile-only capability, which is why the page recommends Maps URLs for everything
  else: *"You should only use the Android-specific Maps Intents for features that may only be functional on
  a mobile platform (for example, turn-by-turn navigation)."*
- Also documented on this page: query strings must be URI-encoded
  (*"All strings passed to the Google Maps Intents must be URI encoded."*), package
  `com.google.android.apps.maps`, and a `resolveActivity()` check before `startActivity()` because
  *"If the system can't identify an app that can respond to the intent, your app might crash."*

---

## 3. Can Google Maps *import* a custom route (GPX / KML / `geo:` / encoded polyline)?

**No, not for navigation.** Concretely:

- **Maps app + KML/GPX import: not supported.** The only Google route customisation path is My Maps, a
  separate product: *"You can import map features like lines, shapes, and places to your map from KML
  files, spreadsheets and other files."* with supported types *"CSV TSV KML KMZ GPX XLSX Google Sheet …"*
  (<https://support.google.com/mymaps/answer/3024836>). My Maps is documented as *viewing* inside Google
  Maps only — *"You can view your My Maps using Google Maps."*
  (<https://support.google.com/maps/answer/3045850>) — with **no documented navigation of a My Maps line**.
  [NOT DOCUMENTED] = cannot navigate a My Maps polyline.
- **`geo:` URIs show a location or run a search, not a route.** Documented uses on the intents page are
  display-a-map (`geo:37.7749,-122.4194`) and search
  (`geo:0,0?q=101+main+street`), plus Plus Codes. There is no route/geometry form.
- **No encoded-polyline parameter exists in Maps URLs.** Verified by absence: the string `polyline` does not
  occur anywhere in <https://developers.google.com/maps/documentation/urls/get-started>. The waypoint
  parameter accepts only "a place name, address, or comma-separated latitude/longitude coordinates" (§1.3).
- **Explicitly NOT possible:** hand Google Maps the app's own computed bicycle geometry and have Google
  Maps navigate it. No documented input accepts geometry: Maps URLs takes places/coordinates (and then
  Google re-computes the route), `google.navigation:` takes a single `q`, `geo:` has no route form, and My
  Maps lines are not navigable. Any instruction to "just send the GPX/polyline to Google Maps" is
  **not supported by any Google document reviewed**.
- **The one place geometry *influences* a Google route** is the Directions API (Legacy), not the Maps app:
  *"Alternatively, you can supply an encoded set of points using the Encoded Polyline Algorithm. You will
  find an encoded set is useful for a large number of waypoints, because the URL is significantly shorter."*
  and *"Encoded polylines must be prefixed with `enc:` and followed by a colon (`:`). For example:
  `waypoints=enc:gfo}EtohhU:`."*
  (<https://developers.google.com/maps/documentation/directions/get-directions>)
  With the `via:` prefix (*"If you'd like to influence the route using waypoints without adding a stopover,
  add the prefix `via:` to the waypoint."*) this forces Google's own routing engine to pass through a
  sampled version of your geometry. Note the returned route is still Google's snapped route, not the
  app's geometry, and web-service URLs are capped at *"a URL limit of 16384 characters"*.
  This is a **server-side API**; it cannot be handed to the Google Maps app for navigation.

---

## 4. Directions API (Legacy) and Routes API

### 4.1 `travelmode=bicycling` still exists — but the Directions API is now "Legacy"

- Directions API doc banner: *"This product or feature is in Legacy status. For more information about
  Legacy status and how to migrate from Legacy to newer services, see Legacy products and features."*
  (<https://developers.google.com/maps/documentation/directions/get-directions>,
  <https://developers.google.com/maps/documentation/directions/overview>)
- Legacy semantics: *"Legacy is an intermediate lifecycle step to ease the transition between Generally
  Available services and Deprecated services."*, *"Legacy-marked services will retain full support."*,
  *"Existing projects already using Legacy services can continue to do so."*, and *"…we will provide at
  least a 12-month notice prior to the decommission of the services."*
  (<https://developers.google.com/maps/legacy>). The Legacy table lists the **Directions API** with
  Legacy date *"March 1, 2025"* and new service *"Routes API"*.
- Bicycling mode is still documented: *"bicycling requests bicycling directions or distance via bicycle
  paths & preferred streets (where available)."*
- **Mandatory warning display:** *"Note: Both walking and bicycling directions may sometimes not include
  clear pedestrian or bicycling paths, so these directions will return warnings in the returned result
  which you must display to the user."*
- Routes API equivalent: `RouteTravelMode.BICYCLE` = *"Travel by bicycle."*
  (<https://developers.google.com/maps/documentation/routes/reference/rest/v2/RouteTravelMode>) with the
  same beta caveat: *"WALK, BICYCLE, and TWO_WHEELER routes are in beta and might sometimes be missing
  clear sidewalks, pedestrian paths, or bicycling paths. You must display this warning to the user for all
  walking, bicycling, and two-wheel routes that you display in your app."*

### 4.2 Waypoint limits per mode

| Interface | Limit | Mode restrictions | Source |
|---|---|---|---|
| Maps URLs (handoff to Maps app) | 3 waypoints on mobile browsers, **9** otherwise | none documented by mode; may be ignored entirely | URLs get-started |
| `google.navigation:` intent | **0** (no waypoint parameter) | `mode=b` allowed | Android intents |
| Directions API (Legacy) | up to **25** (11–25 billed higher) | *"The API supports waypoints for these travel modes: driving, walking and bicycling; not transit."* | get-directions |
| Routes API `computeRoutes` | *"Up to 25 intermediate waypoints are supported."* | none documented by mode; `routingPreference` only allowed for `DRIVE`/`TWO_WHEELER` | computeRoutes reference |
| Navigation SDK | *"You can configure a maximum of 25 waypoints."* | route-token path is `DRIVE`/`TWO_WHEELER` only | customize-route |

- Directions API billing caution: *"Caution: Requests using more than 10 waypoints (between 11 and 25), or
  waypoint optimization, are billed at a higher rate."*
- Routes API: *"You can define up to 25 intermediate waypoints in a single request using the intermediates
  array property…"* with the same 11–25 warning
  (<https://developers.google.com/maps/documentation/routes/intermed_waypoints>).

### 4.3 `alternatives` / `computeAlternativeRoutes` for bicycling

- Directions API: *"If set to true, specifies that the Directions service may provide more than one route
  alternative in the response. Note that providing route alternatives may increase the response time from
  the server. This is only available for requests without intermediate waypoints."* (listed on the page
  under the heading *"alternatives"*)
  The current text contains **no** mode-based exclusion for bicycling. **[NOT DOCUMENTED]** whether
  bicycling alternatives are honoured beyond this — the doc makes no bicycle-specific promise.
- Routes API: *"The response contains a maximum of three alternative routes. However, sometimes no
  alternative routes are available so the response only contains the default route."* and the page summary
  states: *"Alternative routes can only be requested for routes without intermediate waypoints and may
  increase API response time."*
  (<https://developers.google.com/maps/documentation/routes/alternative-routes>)
  Practical impact for this app: **a round-trip loop with shaping waypoints cannot ask for alternatives at
  all.**
- **[NOT DOCUMENTED]** Any statement that alternatives are unavailable for `BICYCLE`; the docs restrict
  alternatives by *waypoints*, not by mode.

### 4.4 Traffic volume, bike lanes, road safety per segment

- **Not exposed.** No field in the Directions API response carries bike lanes, traffic volume, or a safety
  score. With BICYCLING, traffic modelling is out of scope: Routes API `TRAFFIC_ON_POLYLINE` —
  *"Set the travelMode to DRIVE or TWO_WHEELER. Requests for any other travel mode return an error."*
  (<https://developers.google.com/maps/documentation/routes/traffic_on_polylines>)
- Directions API `traffic_speed_entry` is a dead field: *"Deprecated: This array is always empty.
  Information about traffic speed along the leg."*
- The only segment-level road-characteristic data in the API surface is `polylineDetails`, which is
  presence/index information, not quality data: `tunnelInfo`, `restrictionInfo`, `flyoverInfo`,
  `narrowRoadInfo` (*"Encapsulates information about narrow roads along the polyline."*), `bridgeInfo`,
  `skywayInfo` (<https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes>).
  `restrictionInfo` is truck/vehicle-attribute driven. So: **no bike-lane data, no traffic-volume data, no
  safety/comfort metric per bicycle segment.**

### 4.5 What bicycling mode actually optimizes

- Directions API overview: *"The Directions API (Legacy) returns the most efficient routes when calculating
  directions. The product factors in the following travel elements when determining the most efficient
  route:"* … *"Travel time (primary)"* / *"Distance"* / *"Number of turns"* (three bullets in the doc).
  (<https://developers.google.com/maps/documentation/directions/overview>)
- Route *selection* for bicycling is described only as *"Routes through bike paths, where available"* /
  *"via bicycle paths & preferred streets (where available)"*. There is **no** published weighting for
  traffic stress, hilliness, surface, or safety.
- Shaping mechanism if the app must force a corridor: Routes API pass-through waypoints —
  *"… a pass-through waypoint forces the routing engine to pass through a specific location on the way to
  the destination—for example, to follow a preferred road segment or avoid a known hazard."*
  (<https://developers.google.com/maps/documentation/routes/pass-through>), or Directions API
  `via:enc:<polyline>:` (§3).

---

## 5. Maps SDK for Android: draw the route, but no turn-by-turn

- Drawing a computed route for confirmation is fully supported:
  <https://developers.google.com/maps/documentation/android-sdk/shapes>
  - Quote: *"To create a Polyline, first create a PolylineOptions object and add points to it. Points
    represent a point on the earth's surface, and are expressed as a LatLng object. Line segments are drawn
    between points according to the order in which you add them to the PolylineOptions object."*
  - Quote: *"To add points to a PolylineOptions object, call PolylineOptions.add()."*
  - Quote: *"You can then add the polyline to a map by calling GoogleMap.addPolyline(PolylineOptions)."*
  - Customisation and interaction: *"Polylines and Polygons can be customized with multicolored segments,
    gradients, and textures…"* and *"All shapes support click events that can be handled with designated
    listeners like OnPolylineClickListener, OnPolygonClickListener, and OnCircleClickListener."*
  - For decoding encoded polylines and importing GeoJSON/KML in-app, Google provides an open-source utility
    library: <https://developers.google.com/maps/documentation/android-sdk/utility>. Encoding format:
    <https://developers.google.com/maps/documentation/utilities/polylinealgorithm>.
- **The Maps SDK itself provides no navigation.** Its overview describes only map display, markers,
  polygons and overlays: *"With the Maps SDK for Android, add maps to your Android app including Wear OS
  apps using Google Maps data, map displays, and map gesture responses."* The words "navigation" and
  "turn-by-turn" do not appear on the overview page
  (<https://developers.google.com/maps/documentation/android-sdk/overview>). The Android intents page
  reinforces the split: *"To embed a map in your app, refer to the Maps SDK for Android Quickstart."*
- **The official in-app turn-by-turn SDK is the Navigation SDK for Android**
  (<https://developers.google.com/maps/documentation/navigation/android-sdk/overview>):
  - Quote: *"The Navigation SDK enables integrating Google Maps turn-by-turn navigation directly into your
    mobile apps…"*
  - Quote (modes): *"Support various modes of transportation, including driving, walking, cycling, and
    two-wheel vehicles."* The code sample confirms the enum: *"Set the travel mode (DRIVING, WALKING,
    CYCLING, or TWO_WHEELER)."* — `RoutingOptions.TravelMode.CYCLING`
    (<https://developers.google.com/maps/documentation/navigation/android-sdk/route>).
  - Quote (mutual exclusion): *"You cannot use the Navigation SDK and Maps SDK in the same app."*
  - Quote (build requirements, v7.0.0+): *"the target version must be Android 16 (API level 36) or higher.
    the minimum version must be Android 7 (API level 24) or higher."* Device needs *"Google Play services
    installed and enabled. 2GB of RAM or more. OpenGL ES 2.0 support."*
    (<https://developers.google.com/maps/documentation/navigation/android-sdk/setup-overview>)
  - Pricing: the SKU table's *Category* cell is *"Enterprise"* and its *SKU Details* cell is
    *"SKU: Navigation Request"*; usage is tracked as the *"Navigation Request"* SKU; the page summary
    states *"API calls are limited to a rate of 30,000 queries per minute and a maximum of 25 destinations
    per call."*; and *"Billing and pricing for the Navigation SDK depend on the service agreement you have
    with Google Maps Platform."* (<https://developers.google.com/maps/documentation/navigation/android-sdk/pricing>)
    **[UNCERTAIN]** whether a separate approval gate exists; no page reviewed states one, but the
    Enterprise category and per-destination billing mean this is not a free/standard-tier feature.
  - **Hard limit for bicycles:** the only way to feed a *specific* route geometry into Navigation SDK
    guidance is a **route token**, and route tokens do not support `BICYCLE`:
    *"Set travel_mode to DRIVE or TWO_WHEELER"*, *"Set routing_preference to TRAFFIC_AWARE or
    TRAFFIC_AWARE_OPTIMAL"*, *"Do NOT use Via waypoints"*
    (<https://developers.google.com/maps/documentation/navigation/android-sdk/customize-route#use-token>).
    The Routes API page agrees: *"To request that Compute Routes returns route tokens… Set travelMode to
    DRIVE or TWO_WHEELER."* — note its own page summary contradicts itself by saying *"you must set
    travelMode to DRIVE"*; the body text is the operative one, and **both exclude BICYCLE**.
    <https://developers.google.com/maps/documentation/routes/route_token>
  - Even when a token is used, the planned geometry is not guaranteed: *"The actual route traveled may vary
    from the planned route provided by the token."*
- **Conclusion for §5:** you can *draw* a pre-computed bicycle route in-app (PolylineOptions) for
  confirmation, and you can run *Google's* turn-by-turn for cycling through the Navigation SDK
  (`TravelMode.CYCLING`) — but you **cannot** make the Navigation SDK navigate *your* bicycle geometry.

---

## 6. Terms-of-service constraints that matter here

- **Displaying Directions/Routes results on a Google map in-app: allowed.**
  Maps Service Specific Terms, §4.1/§19.1 — *"Use without a Google Map. Customer may use Google Maps
  Content from the Directions API in Customer Applications without a corresponding Google Map."*
  (and the identical §19.1 for the Routes API)
- **Mixing with a non-Google map (e.g. OpenStreetMap base): not allowed.**
  §4.2/§19.2 — *"No use with a non-Google map. Customer must not use Google Maps Content from the
  Directions API in conjunction with a non-Google map."*
- **Caching: 30 consecutive calendar days, coordinates only.**
  §4.3 — *"Caching. Customer may temporarily cache latitude and longitude values from the Directions API
  for up to 30 consecutive calendar days, after which Customer must delete the cached latitude and
  longitude values."* Route caching for the Routes API is identical (§19.3).
  <https://cloud.google.com/maps-platform/terms/maps-service-terms>
- **General no-caching rule** (Google Maps Platform Terms of Service §3.2.3(b)):
  *"No Caching. Customer will not cache Google Maps Content except as expressly permitted under the Maps
  Service Specific Terms."* §3.2.3(a) also forbids *"bulk download Google Maps tiles, Street View images,
  geocodes, directions, distance matrix results, roads information, places information, elevation values,
  and time zone details"* and forbids creating derived content:
  *"No Creating Content From Google Maps Content. Customer will not create content based on Google Maps
  Content."* <https://cloud.google.com/maps-platform/terms>
- **Place IDs are exempt from caching restrictions** (Directions API policies):
  *"Note that the place ID, used to uniquely identify a place, is exempt from the caching restrictions. You
  can therefore store place ID values indefinitely."*
- **Attribution/display requirements** (Directions API policies):
  *"Directions API results displayed on a map must be shown on a Google Map, and if displayed without a
  map, require a visible Google logo and attribution."* and *"Applications must display attribution for the
  Google logo and third-party data providers…"*
  Also: *"Applications using the Directions API must provide publicly accessible Terms of Use and a Privacy
  Policy that incorporate Google's Terms of Service and Privacy Policy, respectively."*
  <https://developers.google.com/maps/documentation/directions/policies>
- **Warning display is a licence obligation, not a nicety:** for walking/bicycling/two-wheeler routes
  *"You must display this warning to the user"* (§4.1, RouteTravelMode; Directions API `warnings` field:
  *"You must handle and display these warnings yourself."*).
- **Implication for this app:** a saved round trip may keep Google-derived lat/lng for at most 30 days
  unless it is displayed on a Google map with attribution; the app must not ship a persistent store of
  Google-derived polylines, must not render Google routes over a non-Google basemap, and must surface the
  bicycling-path warning.

---

## 7. Recommended handoff for a bicycle round trip (derived options, clearly labelled)

These are engineering conclusions from the facts above, not quotes.

| Option | How | Gets real turn-by-turn? | Geometry fidelity | Cost/limits |
|---|---|---|---|---|
| A. Maps URL handoff | `dir_action=navigate`, `travelmode=bicycling`, omit `origin`, pass up to 9 lat/lng waypoints | Yes (Google's route) | App controls only the shaping points; Google re-routes | Free, no key, ≤2,048 chars, waypoints may be ignored |
| B. `google.navigation:q=...&mode=b` | Single destination, from current location | Yes | None (destination only) | Free, but **zero** waypoints |
| C. Routes API `BICYCLE` + in-app polyline + own guidance | Compute with up to 25 intermediates / `via` shaping | No (own guidance or voice cues) | High — the app owns the geometry | Paid API, 30-day cache limit, warning + attribution duties |
| D. Navigation SDK + route token | `CustomRoutesOptions.setRouteToken` | Yes, official | Token polyline, but **not available for BICYCLE** | Enterprise SKU, per-destination billing, replaces Maps SDK |

For a *round-trip loop with app-chosen roads*, options A and B cannot express the route, and D cannot
navigate a bicycle. That leaves C (compute + draw + guide in-app) or A (accept Google's route through a
handful of shape points).

---

## 8. Explicitly uncertain / not documented (do not guess past these)

1. Whether `dir_action=navigate` + `waypoints` on the Android Maps app starts guidance immediately or shows
   a multi-stop preview. Not documented; test on device.
2. Whether the Android Maps app is "mobile browser" (3 waypoints) or "otherwise" (9 waypoints) for the
   Maps URLs waypoint limit.
3. Whether waypoints are silently dropped when combined with `dir_action=navigate` on any given Maps build
   — the docs only warn waypoints "are not supported on all Google Maps products".
4. Whether Directions API `alternatives=true` returns alternatives for `bicycling` (no mode exclusion is
   documented, but no bicycle guarantee either; it is blocked whenever waypoints are used).
5. Exact bicycle coverage/regions for the maps-app handoff via URL.
6. Whether the Navigation SDK requires a separate approval beyond Enterprise-tier billing.
7. Any Google capability to navigate a My Maps line or an imported GPX/KML track — nothing documented.

---

## Sources (all fetched 2026-09-15)

1. Maps URLs — Get started: <https://developers.google.com/maps/documentation/urls/get-started>
2. Google Maps Intents for Android: <https://developers.google.com/maps/documentation/urls/android-intents>
3. Google Maps Help — Get directions & show routes (Android):
   <https://support.google.com/maps/answer/144339?co=GENIE.Platform%3DAndroid>
4. Google Maps Help — Use navigation in Google Maps (Android):
   <https://support.google.com/maps/answer/3273406?hl=en>
5. Google My Maps Help — Import map features from a file:
   <https://support.google.com/mymaps/answer/3024836>
6. Google Maps Help — View your My Maps using Google Maps:
   <https://support.google.com/maps/answer/3045850>
7. Directions API (Legacy) — overview:
   <https://developers.google.com/maps/documentation/directions/overview>
8. Directions API (Legacy) — get directions:
   <https://developers.google.com/maps/documentation/directions/get-directions>
9. Directions API (Legacy) — policies and attributions:
   <https://developers.google.com/maps/documentation/directions/policies>
10. Legacy products and features: <https://developers.google.com/maps/legacy>
11. Routes API — Compute Routes overview:
    <https://developers.google.com/maps/documentation/routes/overview>
12. Routes API — `computeRoutes` reference:
    <https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes>
13. Routes API — `RouteTravelMode`:
    <https://developers.google.com/maps/documentation/routes/reference/rest/v2/RouteTravelMode>
14. Routes API — Set intermediate waypoints:
    <https://developers.google.com/maps/documentation/routes/intermed_waypoints>
15. Routes API — Set a point for a route to pass through:
    <https://developers.google.com/maps/documentation/routes/pass-through>
16. Routes API — Get alternative routes:
    <https://developers.google.com/maps/documentation/routes/alternative-routes>
17. Routes API — Get a route token:
    <https://developers.google.com/maps/documentation/routes/route_token>
18. Routes API — Traffic-aware polylines:
    <https://developers.google.com/maps/documentation/routes/traffic_on_polylines>
19. Routes API — Get a two-wheeled vehicle route (for the BICYCLE-vs-TWO_WHEELER distinction):
    <https://developers.google.com/maps/documentation/routes/route_two_wheel>
20. Maps SDK for Android — overview:
    <https://developers.google.com/maps/documentation/android-sdk/overview>
21. Maps SDK for Android — Shapes (PolylineOptions):
    <https://developers.google.com/maps/documentation/android-sdk/shapes>
22. Maps SDK for Android — Utility Library:
    <https://developers.google.com/maps/documentation/android-sdk/utility>
23. Encoded Polyline Algorithm Format:
    <https://developers.google.com/maps/documentation/utilities/polylinealgorithm>
24. Navigation SDK for Android — overview:
    <https://developers.google.com/maps/documentation/navigation/android-sdk/overview>
25. Navigation SDK for Android — overview and requirements:
    <https://developers.google.com/maps/documentation/navigation/android-sdk/setup-overview>
26. Navigation SDK for Android — navigate a route (TravelMode enum):
    <https://developers.google.com/maps/documentation/navigation/android-sdk/route>
27. Navigation SDK for Android — plan a route (route token):
    <https://developers.google.com/maps/documentation/navigation/android-sdk/customize-route#use-token>
28. Navigation SDK for Android — manage waypoints:
    <https://developers.google.com/maps/documentation/navigation/android-sdk/manage-waypoints>
29. Navigation SDK for Android — usage and billing:
    <https://developers.google.com/maps/documentation/navigation/android-sdk/pricing>
30. Google Maps Platform Terms of Service:
    <https://cloud.google.com/maps-platform/terms>
31. Google Maps Platform Maps Service Specific Terms:
    <https://cloud.google.com/maps-platform/terms/maps-service-terms>
32. Google Maps Platform pricing (product list):
    <https://mapsplatform.google.com/pricing/>
