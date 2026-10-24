# The "Use my location" control — diagnosis and proposal

Status: diagnosis only, 2026-09-16. **No app source was changed for this document.**
Authoritative reading first: `docs/SPEC.md` section 7 (step 1, D22) and 7c, `docs/DECISIONS.md`
D22 (trip origin), D24 (access-point ranking), D25 (detour bands).

Device: Samsung SM-S918U, serial `R5CW30PD58H`, Android 16, 1440x3088.
Installed build: `com.biketrailplanner` versionName 0.1.0, `lastUpdateTime=2026-09-16 09:37:16`,
built from the sources as of 09:02 that day (`PlanScreen.kt`, `PlanViewModel.kt`).
Note: `RouteStore.kt`, `SavedRouteCodec.kt` and `Room.kt` changed at 09:44, i.e. after that build.
They are a parallel route-store change; they do not touch the location control.

## 1. The user's complaint, split in two

> "Plus, make use current location working. When clicked, current location will be the home to plan
> trips around"

Two separate questions:

* **(a)** Is the control broken?
* **(b)** Does the app treat the CURRENT LOCATION as the place trips are planned around?

They have different answers, and that split is the whole finding: **(a) no, it works but says nothing;
(b) mostly yes already, except in the places that decide which trail access point is tried.**

## 2. Every location-related control in the UI

`grep -rn "Use my location\|refreshLocation\|onRefreshLocation\|location" app/src/main/java/com/biketrailplanner/ui/`

| Control | Where | Callback it fires | What the callback does |
|---|---|---|---|
| **"Use my location"** (TextButton) | `ui/PlanScreen.kt:186` (wired from `MainActivity.kt:112` `onRefreshLocation = viewModel::refreshLocation`) | `PlanViewModel.refreshLocation()` | `PlanViewModel.kt:118-120` → `refreshFix()` (`PlanViewModel.kt:173-181`): paints `lastFix()` immediately, then awaits `LocationProvider.freshFix()` (12 s timeout) and paints that. It only updates origin/accuracy/age/fixNote in `UiState`. **It does not re-plan, does not move the map, does not touch the trail radius, and shows no spinner.** |
| (same button, second entry point) permission result | `MainActivity.kt:65-67` | `viewModel.refreshLocation()` | same as above |
| (same button, automatic entry point) app start | `MainActivity.kt:83-89` | `permissionLauncher.launch(...)` then `viewModel.refreshLocation()` | asks for FINE/COARSE location, then the same refresh |
| **"Refresh trails"** (TextButton) | `ui/PlanScreen.kt:187-189` | `refreshTrailData()` | re-reads OSM tags of closed ways (`PlanViewModel.kt:337`). Not a location control. |
| **"Re-check trail data"** (TextButton) | `ui/SettingsScreen.kt:81` | `refreshTrailData()` | same |
| **"Home" row** (static text) | `ui/SettingsScreen.kt:80` | none | prints `state.homeLabel` from `assets/config.json`. Not a control: there is **no way in the app to set or change the origin**. |
| Fix note line (not a control) | `ui/PlanScreen.kt:144-150` | none | the only visible effect of the refresh: `state.fixNote` |

The feedback channel is therefore exactly one line of text, built by
`PlanViewModel.fixNoteFor()` (`PlanViewModel.kt:151-155`): "Starting from your location (±N m)." /
"Waiting for a good GPS fix: ...". Everything else the button changes is invisible.

## 3. Phone evidence

Screenshots and UI trees are in `docs/phone-evidence/`.

### Experiment A — tap with an already-good fix (the normal case)

1. Launch: `adb shell monkey -p com.biketrailplanner -c android.intent.category.LAUNCHER 1`, then
   `adb shell input keyevent KEYCODE_BACK` to return from the result screen to the plan screen.
2. `uiautomator dump` + `screencap` → `use-my-location-before-2026-09-16.{xml,png}`
3. `adb shell input tap 315 2867` (centre of the button).
4. `uiautomator dump` + `screencap` → `use-my-location-after-2026-09-16.{xml,png}`

Result:

* **The UI tree is byte-identical.** sha256 `9a5c01e8...a82884` for both
  `use-my-location-before-2026-09-16.xml` and `use-my-location-after-2026-09-16.xml`.
  No text changed, no node appeared, no spinner, no progress bar, no button state change.
* Pixel diff of the two screenshots: **252 changed pixels, all inside y 54-91 / x 1263-1269** —
  the status-bar clock. The app area is unchanged.
* The note stayed "Starting from your location (±5 m)."; the route ("Interurban Trail Milton",
  15.0 mi / 3.8 mi) stayed identical.
* `adb logcat -d -t 400` (saved as `use-my-location-logcat-2026-09-16.txt`): **no exception, no ANR,
  no app crash.** The only two lines containing "Exception" are from pid 32603, the `uiautomator`
  tool itself, not the app.

### Experiment B — tap with a fix that is not already good

To show whether the button does anything at all, the fix had to be different from the one already on
screen. Device location was switched off for ~75 s (Android caches the last fix), the button was
tapped, and the setting was restored immediately afterwards (`location_mode` 3 → 0 → 3, verified 3).

* Before tap: "Starting from your location (±5 m)."
* After tap: **"Starting from your location (±41 m)."**
* Pixel diff: **2239 changed pixels, all inside y 2270-2321 / x 749-894** — exactly the fix-note line
  (`bounds [60,2260][869,2327]`). Nothing else on the screen changed.
* Files: `use-my-location-location-off-before-tap-2026-09-16.png`,
  `use-my-location-location-off-after-tap-2026-09-16.png`.

### Reachability

The button is on screen without scrolling: bounds `[105,2825][526,2909]` on a 1440x3088 display, and
the plan `Column` (`PlanScreen.kt:64`) has no `verticalScroll`. The control is reachable; that is not
the problem.

## 4. Verdict

**Verdict: (i) — the control works, but its effect is invisible in the only case the rider will ever
see it.** It is *not* a no-op and *not* a crash: Experiment B proves the callback runs, reads a fresh
fix, and repaints the state. Experiment A proves that when the fix is already fresh and good, nothing
at all changes on screen — byte-identical UI tree, no spinner, no re-plan, no text change — so the tap
looks broken to the rider.

Element of **(iii) is also true for what the rider means by the words**: the button only refreshes
the fix. It does not make the current location "the home to plan trips around" in any sense the rider
can see, and it does not re-plan. Combined with section 5, the rider's sentence is a request for a
behaviour that partly exists (the plan already starts from the live fix) and partly does not (the
saved home still decides which access points get tried, and still owns every "home" label).

## 5. Where the SAVED home still decides behaviour

`grep -rn "config.home\|home =" app/src/main/java/com/biketrailplanner/`, plus the data and UI paths.

### Blocks "current location as the planning centre"

| # | What | File:line | Why it blocks |
|---|---|---|---|
| B1 | **Access-point approach distance.** `approachM()` returns `ap.probeRouteKmFromHome * 1000` while the rider is within `PROBE_ORIGIN_TOLERANCE_M` of the saved home, else falls back to crow-flies from the live origin. | `domain/Planner.kt:407-411`; tolerance `Planner.kt:757` (`2_000.0`); asset field `data/Dtos.kt:93`, `domain/Models.kt:125`, `data/TrailRepository.kt:140`; data `app/src/main/assets/access_points.json` (143 records, all `measured_route_km_from_home`) | This is the ranking key that D24 proved must be a **measured road distance**. Away from home it silently degrades to the crow-flies proxy that D24 measured as useless (28 nearest access points on Sumner Link all fail; the 6 that pass are the top 6 by measured distance). So exactly when the rider starts somewhere else, the planner loses the fix that makes it work. |
| B2 | **No runtime notion of "the origin I am planning from".** `UiState.home`/`homeLabel` are loaded from the bundled asset; nothing else can be set by the rider. | `ui/PlanViewModel.kt:22-23, 63-64` ← `AppContainer.kt:86` ← `data/TrailRepository.kt:170-171` ← `app/src/main/assets/config.json` | The app can only *name* one place, and it is the saved home. There is no state to hold "planning from here". |
| B3 | **Every "home" label the rider reads.** Settings row; the no-trails sentence ("the trails in this app are the ones mapped near Home — 12424 18th St E..."); the map's fallback pin/camera when no fix exists; the map marker itself. | `ui/SettingsScreen.kt:80`; `ui/RiderText.kt:59-62` (used at `ui/ResultScreen.kt:158, 336`); `MainActivity.kt:99` (`home = state.origin ?: state.home`); `ui/RouteMap.kt:152` (`HomeMarker`) | These are what the rider sees as "the app thinks home is where I plan". They are static asset text. |
| B4 | **Rotation memory and saved-route reuse are keyed on the origin**, so an origin change silently invalidates both. | `domain/Rotator.kt:9-33` (`Signature.of`, origin rounded to 3 dp ≈ 110 m); new `domain/RouteStore.kt:93-98, 203-215` (bucket `%.2f,%.2f` ≈ 1 km, `ORIGIN_MATCH_M`) | Already live-origin, not saved-home — but it means "current location is home" changes what counts as "the same ride twice" (D6). Needs an explicit decision, not a silent side effect. |
| B5 | **The fix note is only on the plan screen.** The result screen shows the accuracy but never where the trip is planned from. | `ui/PlanScreen.kt:144-150`; `ui/ResultScreen.kt:99` (`originSentence(state.originAccuracyM)`) | The rider cannot verify the adopted origin on the screen where the route is confirmed. |
| B6 | **Stale-planning inconsistency.** Tapping "Use my location" republishes `state.origin`, which is the map pin/camera source (`MainActivity.kt:99`), while the drawn route came from the previous origin. | `MainActivity.kt:99` vs `ui/PlanViewModel.kt:173-181` (no `plan()` call) | The map may move while the route does not, with nothing on screen saying the route is from the old position. |

### Already correct (D22 is implemented for these — do not "fix" them)

| What | File:line | Note |
|---|---|---|
| Trail search radius from the live origin | `domain/Planner.kt:86-87`, `:441` | uses `request.origin` |
| Commute routed from the live origin | `domain/Planner.kt:554` (`client.route(request.origin, spec.ap.point, ...)`) | |
| Round trip returns to the live origin | `domain/Planner.kt` `buildRoundTrip()` | |
| Hand-off destination = live origin, `origin` omitted | `ui/PlanViewModel.kt:273, 283` (`home = loc`), `domain/MapsUrlBuilder.kt:164-165` | D18 shape, already validated on device |
| Traffic-coverage warning measured from the live origin **to** the data centre (home) | `domain/Planner.kt:85` (`trafficCoverageKm` = 15.0 km in `config.json`) | honest as written: the counts really are centred on home |
| Trail data fields measured from home | `domain/Models.kt:124, 180-181`, `data/TrailRepository.kt:112-113, 139` | loaded but unused by the planner path (only the access-point probe is used) |

## 6. Proposal — what "the current location becomes home" has to mean

Three layers, in this order. Layer 1 already exists; layers 2 and 3 are the work.

### Layer 1 — origin of the trip (keep as is, D22)

The live fix starts and finishes the round trip, with the freshness gate (age <= 60 s, accuracy
<= 50 m) and `origin` omitted from the Maps URL. No change.

### Layer 2 — the planning centre must become the current location, including the measured data

1. **Re-key the access-point approach on the live origin (fixes B1).** Where the origin is not the
   saved home, do not fall back to crow-flies. Instead measure it: rank candidates by crow-flies,
   take the top ~12, spend one `POST /route` per candidate from the live origin, and rank by that
   measured distance. Cache the result in the existing Room route cache keyed by
   `(origin rounded ~1 km, access-point node id)`, so a second plan from the same spot is free.
   Keep the asset's `measured_route_km_from_home` as a warm start **only** while the origin is within
   the 2 km tolerance — that is what it honestly is.
2. **Name the origin instead of the home in the UI (fixes B2, B3, B5).** Add
   `UiState.planningOrigin: LatLon?` + `planningOriginLabel: String?` (e.g. "your current location
   (±4 m)" or "Home — 12424 18th St E, Edgewood, WA 98372" when the rider really is there). Show it
   on the plan sheet next to the existing fix note **and** on the result screen. Rename the Settings
   row from "Home" to "Saved home", and say plainly that plans start from the current location.
3. **Keep the traffic-coverage wording, but stop calling the centre "home"** in rider text
   (`ui/RiderText.kt:59-62`): "the trails in this app are the ones mapped near where you planned from"
   is untrue when the rider is 12 miles away; better: "this app only knows trails and traffic counts
   near 12424 18th St E; you are 19 km from there." That is the honest version of the same fact, and it
   points at the open D23 (trail discovery away from home).

### Layer 3 — make the control do what the rider thinks it does

1. **"Use my location" adopts the fix as the planning origin for this session**, and says so:
   the sheet shows "Planning from your location (±4 m) · 0.2 mi from your saved home", with a
   timestamp.
2. **If a plan is on screen, do not leave it silently stale (fixes B6).** Either re-plan
   automatically when the adopted origin moved more than ~100 m or the plan is older than ~2 minutes,
   or show an explicit amber banner "This route was planned from your previous position — plan again".
   Re-planning is the better default: it is one tap for the rider, and it is what the tap means.
3. **Persist what should persist.** Do **not** write `assets/config.json`: bundled assets are
   read-only at runtime (the app cannot rewrite its own APK), so "the current location replaces the
   saved home in config.json" is not implementable and would also destroy the only record of where the
   bundled traffic/trail data is valid. Instead:
   * **In-memory/session origin** = the live fix, refreshed on every plan. Persist the last origin in
     `SharedPreferences` only as a *fallback for display* when no fresh fix exists, always with its age
     shown ("the last good fix, 3 min old"), never used silently (D22 rule 1).
   * **"Save this spot as home"** as a separate, explicit action that writes a user home
     (lat, lon, label) to `SharedPreferences` (`bike-trail-planner` prefs, next to `allow_public_fallback`
     and `last_target_miles`). That is the only durable "home" the app should own, and it is the user's
     choice, not a side effect of tapping a refresh button.
4. **Decide rotation under a moving origin (B4) before it bites.** Either
   (a) key the rotation on the trail/access-point part of the signature only, so the same ride from a
   slightly different start still counts as "ridden recently", or
   (b) keep the origin in the signature and accept that riding from anywhere else resets the memory.
   Recommendation: (a) for D6 rotation, and keep the origin bucket for *route reuse* (the new
   `RouteStore`) where geometry really must match.

### Consequences to accept out loud

* Away from home the bundled six trails still do not cover the area (`Planner.kt:95-96` already warns;
  D23 is still open), and the traffic counts do not either (`Planner.kt:85-92` already warns). Making
  the current location the planning centre makes those warnings *more* frequent, not less.
* Re-measuring the access-point approach uses one `POST /route` per candidate. That is the cost D24
  already paid at build time, now paid at plan time; the route cache is what keeps it cheap.

## 7. Evidence files

| File | What it shows |
|---|---|
| `docs/phone-evidence/use-my-location-before-2026-09-16.{png,xml}` | plan screen, good fix, before the tap |
| `docs/phone-evidence/use-my-location-after-2026-09-16.{png,xml}` | after the tap — tree byte-identical (sha256 `9a5c01e8...a82884`), screenshot differs only in the clock |
| `docs/phone-evidence/use-my-location-location-off-before-tap-2026-09-16.png` | fix deliberately stale (location off 75 s): note reads "±5 m" |
| `docs/phone-evidence/use-my-location-location-off-after-tap-2026-09-16.png` | after the tap: note reads "±41 m" — changed pixels only inside the note's bounds |
| `docs/phone-evidence/use-my-location-logcat-2026-09-16.txt` | logcat around the tap: no app exception, no ANR |

## 8. Implemented - 2026-09-16

Everything in section 6 layers 2 and 3 is now in the app, verified on `R5CW30PD58H` (SM-S918U,
Android 16). The change is state and wording plus one new preferences store: **no route, grade or
policy code was touched**, and nothing writes into `assets/`.

### 8.1 A real notion of the planning origin (B2, B3, B5)

* `UiState.planningOrigin: LatLon?` is the live fix **whenever it is good** (age <= 60 s, accuracy
  <= 50 m) and null otherwise, with `planningOriginLabel`, `planningOriginAtSavedHome` and
  `planningOriginFromSavedHomeM` beside it (`ui/PlanViewModel.kt:40-46`), set in one place:
  `publishFix()` (`ui/PlanViewModel.kt:211-233`). `home`/`homeLabel` stay the SAVED home
  (`ui/PlanViewModel.kt:28-34`, `:88-101`).
* Every "where this ride starts" sentence uses the planning origin: the plan sheet's `positionNote`
  (`ui/PlanScreen.kt:154-163`) and the result screen sentence, which replaced the accuracy-only line
  (`ui/ResultScreen.kt:98-105`, `ui/RiderText.kt:93-99`). At the saved home it names the address,
  away from it "your current position (+/-N m)".
* Settings: **Home -> "Saved home"**, with one line saying a ride starts where you are
  (`ui/SettingsScreen.kt:83-88`). The bundled label "Home - 12424 18th St E..." is normalised to
  "12424 18th St E, Edgewood, WA 98372" so a row called "Saved home" does not repeat the retired word
  (`ui/RiderText.kt:298-304`).
* The map pin marks the PLANNING origin, falling back to the saved home only when there is no fix
  (`MainActivity.kt:106-109`). The map's `home` parameter was renamed `origin`
  (`ui/RouteMap.kt:41-48`, `ui/BaseMap.kt:54-60`) so the old idea cannot creep back in.
* The no-trails card no longer calls the saved home "where you are": it names the area the bundled
  data covers, then the planning origin and the distance between them (`ui/RiderText.kt:60-78`,
  `ui/ResultScreen.kt:160-165`, `:328-360`).

### 8.2 The control does something visible (B6, layer 3.1 and 3.2)

* `refreshLocation()` (`ui/PlanViewModel.kt:159-166`) answers the tap **at once** with "Checking your
  position..." and then, when the fix arrives (`onLocationAdopted`, `ui/PlanViewModel.kt:259-278`):
  * good fix, no plan on screen -> "Updated your position. Planning from <label>. Tap Plan ride to go.";
  * good fix, a plan on screen -> "Updated your position. Planning again from <label>." **and a
    re-plan from that same fix**, so the GPS is not read twice and the announcement and the plan
    cannot disagree;
  * fix not usable -> "Your position is not usable yet: <reason>. The ride still starts from where it
    did, and nothing was planned again." - and **no** re-plan.
* The decision is one pure function, `RiderText.PlanningOrigin.tapAction()` (`ui/RiderText.kt:243-250`),
  so "never two plans at once" is a tested rule instead of a hope; `plan()` also refuses to start
  while `planning` is true (`ui/PlanViewModel.kt:331-340`).
* App start and the permission result use `refreshFixQuietly()` (`ui/PlanViewModel.kt:171-174`,
  `MainActivity.kt:74`, `:96`): they adopt the fix but announce nothing, because there was no tap.

### 8.3 Making the current location the saved home, without writing assets (layer 3.3)

* **"Save this spot as home"** and **"Forget this saved home"** in Settings
  (`ui/SettingsScreen.kt:89-97`, `ui/PlanViewModel.kt:283-323`) store the point and a label as strings
  in the existing `bike-trail-planner` SharedPreferences (`user_home_lat`, `user_home_lon`,
  `user_home_label`), next to `allow_public_fallback` and `last_target_miles`. New file:
  `data/UserHomeStore.kt` (55 lines). Lat/lon are strings, not floats, so saving does not move the
  point by half a metre, and nothing is logged (SEC-10).
* `assets/config.json` is **never** written. The saved home is used in exactly **two** places, both
  honestly about the bundled data, and nowhere else:
  1. the Settings "Saved home" row (`ui/SettingsScreen.kt:83`), and
  2. the no-trails card, which says the bundle covers the area around the saved home
     (`ui/RiderText.kt:64-78`).
* Saving takes effect at once: the state is republished with the new home, and the existing
  `container.reload()` runs off the main thread (`ui/PlanViewModel.kt:298-306`, `:325-329`).
  `reload()` itself re-reads the bundled assets and cannot see preferences; the immediate effect
  comes from the state refresh.

### 8.4 Deliberately not done here (needs the files another worker owns)

* **B1** (access-point ranking away from home) lives in `domain/Planner.kt` and is covered by
  `PlannerOriginRankingTest`; it is not part of this change.
* **The saved home does not reach the planner.** `AppContainer.planner()` builds
  `Planner(home = loaded.config.home)`, so a rider-saved home still does not move the 2 km probe
  tolerance (`Planner.kt:757`) or the traffic-coverage origin (`Planner.kt:85`). That is a one-line
  change in `AppContainer.kt`, which this change is not allowed to touch. Reported, not done.
* **Rotation memory (B4)** is untouched: `domain/Rotator.kt` still keys the signature on the origin,
  so riding from somewhere else still resets D6.

### 8.5 Tests

`PlanningOriginTest` (22 tests) covers the label, the at-home tolerance, all four tap actions
(including the "never a second plan" guard), the three confirmations, the stale / vague / no-fix
wording, the saved-home label and its normalisation, the no-trails sentence, and a guard that none of
it leaks internal vocabulary. `:app:testDebugUnitTest` is green: **169 tests, 0 failures**, of which
22 are the new ones.

### 8.6 Phone evidence

`tools/location_evidence.py R5CW30PD58H` drives the phone and writes `docs/phone-evidence/`. It
clears the app first, because a saved trip can otherwise answer a re-plan in under a second and hide
the work.

| Files | What it shows |
|---|---|
| `location-tap-no-plan-2026-09-16-before.{png,xml}` / `-after.{png,xml}` | **the missing visible effect.** No plan on screen: before the tap the tree has no position sentence, after it has `Updated your position. Planning from 12424 18th St E, Edgewood, WA 98372. Tap Plan ride to go.` The tree sha256 differs; in section 3 the same pair was byte-identical. |
| `location-tap-with-plan-2026-09-16-{planned,before,s0,s1,replanned}.{png,xml}` | a plan on screen: the tap is answered at once (`Checking your position...` in `s0`, +2.4 s), and the app then plans the ride again and opens the **new** result by itself (`s1`/`replanned`, +11.3 s). Before the tap this screen was the plan screen with the old ride on it. |
| `location-tap-no-position-2026-09-16-{before,after,15s-later}.{png,xml}` | no usable position: the tap explains itself in rider language and **does not plan**; 15 s later the plan screen is still the plan screen. |
| `save-home-2026-09-16-{before,after,forgotten}.{png,xml}` | "Save this spot as home": the saved label appears with the honest note that the bundled data has not moved, and "Forget this saved home" undoes it. |
| `use-my-location-*-2026-09-16.*` | the section 3 evidence from before the change, kept for comparison. |

The line in between ("Updated your position. Planning again from ...") is on screen only while the
plan runs, and a saved trip (docs/ROUTE-STORE.md) can finish a re-plan in under three seconds, so a
tree dump does not always catch it; the effect that is always captured is the instant answer plus the
new result. `location-tap-with-plan-2026-09-16-replanned.xml` is byte-identical to `-planned.xml` when
the re-plan produces the same ride, which is the point: the tap re-ran the plan rather than doing
nothing.

`python3 tools/acceptance.py R5CW30PD58H` still **PASSES**: install, launch, light theme while the
device is in night mode, a real plan against the live Valhalla server, no leaked vocabulary, and the
hand-off reaching Google Maps.
