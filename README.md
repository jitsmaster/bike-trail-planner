# Bike Trail Planner

A personal Android app with two halves, both ending in the same place - **Google Maps bicycle
navigation**:

1. **Plan a safe bicycle round trip from home**: home -> the nearest access point of a nearby bike
   trail -> ride the trail for the mileage that is left -> home. It grades every road segment, shows
   the route on a map for confirmation, and hands the trip to Google Maps.
2. **Record a ride you did not plan**, then turn that ride into a Google Maps route (section 1).
   This is the half that has been ridden for real: it draws the ride live while it happens, writes
   down **every road you rode and every raw GPS fix**, and afterwards gives back a Maps route through
   the ride's key turns plus a home-screen icon that opens it again.

The spec is `docs/SPEC.md`; the locked decisions are `docs/DECISIONS.md`; the routing server is
documented in `docs/SERVER.md`; the measured safety reality is `docs/SAFETY-PROBE.md`. Those files
are authoritative and are not modified by the app.

**What the app will not do:** it never calls an ungraded segment safe. 82-100% of every route from
this home runs on roads with no `maxspeed` in OpenStreetMap, so most of the ride is reported as
**UNGRADED** and named street by street, with a **NOT VERIFIED** banner.

## 1. Record a ride, then ride it in Google Maps

Record the ride on the phone, get it back as a **Google Maps bicycle route** - and keep a permanent,
named icon for it on the home screen. No Google API key, no upload, and **no network at all while
riding**.

```
ride (unplanned)  ->  the line drawn live on the map  ->  the saved ride, as a list of roads
                                                       |-> Navigate this ride in Google Maps (D64)
                                                       |-> Save this ride as a home screen icon (D68)
                                                       `-> the raw GPS fixes, kept on disk (D66)
```

### What the rider does

| Step | What happens |
|---|---|
| **Record a ride** | A foreground service starts; the line is drawn as you move, one fix at a time (D65) |
| *(riding)* | The camera moves only when you would otherwise leave the visible part of the map - never a re-fit on every fix |
| **Stop** | The ride is in *Your rides* **within a second**, ungraded, then streets and grades fill in (D67) |
| Tap the ride -> **Navigate this ride in Google Maps** | Google Maps opens a **bicycling** route through the ride's key turns, in the order you rode them |
| **Save this ride as a home screen icon** | Asks for a name first, then pins an icon that opens that same route (D68) |
| **Delete** | Asks **twice**, because a recorded ride cannot be re-created from anything (D69) |
| *(later)* | Every fix is still on the phone in `files/ride-raw/`, whatever happened above (D66) |

### How a recorded ride becomes a Google Maps route (D64)

Google Maps' URL API takes **stops**, not a line: no GPX, no polyline, and no way to make it ride the
exact streets in the recording. So the app does the only honest thing available.

1. **The ride becomes a list of roads.** The recorded line is traced against OpenStreetMap
   (`trace_attributes`, matched with `map_snap` - `edge_walk` returns HTTP 400 for a hand-assembled
   shape) and collapses into roads: `way_id`, name, road class, where each one starts, how long it is.
   That list is the raw material, and it is stored in the ride itself.
2. **The stops are the key turns, not an even stride.** `StopPicker` scores junctions by what would
   change Google's choice: a **measured bearing change** (a +/-12 m probe, so a seam between two
   halves of one trail is never mistaken for a corner), a **name change**, a **separated path**, and
   the **far point** of the ride. Candidates are deduped geometrically (120 m) and capped at **9** -
   Google Maps' waypoint limit.
3. **Maps fills in the rest.** Those stops go into
   `https://www.google.com/maps/dir/?api=1&...&travelmode=bicycling` in riding order; the small
   natural corners between them are left to Google. Naming only the turns that matter is the whole
   design: hand Google every vertex and it re-routes worse, hand it none and it ignores the trail.
4. **A loop is a special case.** Google Maps rejects a route whose origin equals its destination, so a
   ride that returns to where it started **omits `origin`** and starts from the phone's location
   (the same rule as the planner's `MapsUrlBuilder`, D18).

### Measured on the phone, on the rider's own ride (S23 Ultra, 2026-10-26)

12.0 mi, 1 h 7 min, 8% of it with no speed information, recorded on Edgewood's streets and the
Riverwalk Trail - the first real ride this recorder ever did.

| What was tapped | What happened |
|---|---|
| *Stop* | the ride appeared at the top of *Your rides* in about a second, then graded |
| *Navigate this ride in Google Maps* | `Your location` -> **9 stops** -> `12424 18th St E, Edgewood`, **1 hr 10 min (12 mi)** by bicycle, `53% bike lanes` |
| *Save this ride as a home screen icon*, named *"Riverwalk loop"* | the launcher asked to add it, the icon appeared with that label, and tapping it opened the same route in Google Maps |

A recorded out-and-back was also checked: **no `origin`, 8 waypoints**, `Your location` -> 8 stops ->
`12424 18th St E, Edgewood`, `1 hr 18 min (12 mi)`.

### The recording never depends on the network

Away from home the Valhalla server does not exist (`127.0.0.1:8002` is only reachable while tethered),
so the trace fails - and that is the **normal** case on a real ride. The ride is still stored, still
listed, still navigable from its stops, just ungraded. The ungraded path is the one that had to be
safest, so it is the first write, not the fallback.

### The raw fixes are written down first (D66)

Before the save is even attempted, the unfiltered GPS journal is copied to
`files/ride-raw/YYYY-MM-DD-HHmmss-ride.txt`: a header naming the ride and the column order, then one
fix per line - `lat,lon,epochMs,accuracyM,speedMps`. It holds **every** fix the phone reported,
including the ones the recorder rejected, so where you went cannot be lost to a trace failure, a
database failure or a crash. Fifty files are kept, oldest pruned; two rides started in the same
minute no longer overwrite each other.

```console
$ adb pull /data/data/com.biketrailplanner/files/ride-raw/      # every fix of every ride
```

The phone also reports location from several providers (GPS plus fused/network) because a silent GPS
provider logs nothing at all - measured indoors on the S23 Ultra: GPS alone, **zero fixes in four
minutes**.

### Judging the algorithm from a stored ride

The stops that were chosen - metres, degrees, reason - and the exact Maps URL are logged at DEBUG, so
the algorithm can be re-judged from a ride that is already on disk:

```console
$ adb logcat -d -s RideRoads | grep -E "saved stops|maps url"
$ adb logcat -d -s RideLive                       # publish 1, 2, 100, 200 ... the live line
```

Design notes, the stop-picking rules and the device results: **`docs/RIDE-RECORDER.md`**. The locked
decisions are **D64-D69** in `docs/DECISIONS.md`.

## 2. Requirements

| Thing | Value used here |
|---|---|
| JDK | 17 — `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` (there is no `java` on `PATH`) |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` (platforms 34 + 35, build-tools 34.0.0) |
| Gradle | the wrapper in this repo, 8.13 (AGP 8.7.3) |
| Valhalla | self-hosted on this Mac, `http://127.0.0.1:8002`, reached by the phone over Tailscale |

`local.properties` (gitignored) must contain:

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
# optional overrides; the defaults are the tailnet host and the public fallback
valhalla.baseUrl=http://127.0.0.1:8002
```

With no `valhalla.baseUrl`, the app uses `http://admins-mac-mini.taile1279d.ts.net` (D5) and can
fall back to `https://valhalla1.openstreetmap.de` only when the rider turns the D12 switch on.

## 3. Build, install, test

```console
$ export JAVA_HOME=/opt/homebrew/opt/openjdk@17
$ ./gradlew :app:assembleDebug
$ ./gradlew :app:testDebugUnitTest        # 455 JVM unit tests, all passing
```

APK (ABI splits, so each device only downloads its own native libraries):

| Device | Path | Size |
|---|---|---|
| Phone (Galaxy S23 Ultra, arm64-v8a) | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | ~25 MB |
| Emulator `pra_test` (arm64-v8a) | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | ~28 MB |

The emulator is an **arm64-v8a** AVD on this Apple-silicon Mac: installing the x86_64 split fails
with `INSTALL_FAILED_NO_MATCHING_ABIS` (measured 2026-09-26). Check the ABI of whatever is attached
(`adb shell getprop ro.product.cpu.abi`) before picking an APK; `tools/acceptance.py` does this for
you, and the `app-x86_64-debug.apk` split stays in the build for an x86_64 host.

`tools/acceptance.py` picks the right one for the attached device automatically.

Unit tests cover the mileage math (D2), corridor chaining, the severed-corridor case and mid-line
corridor joins (D3/D9), the safety grader including UNGRADED, the missing-speed-limit case and the
measured-volume rules (SPEC 6/6b), the closure detour and its provenance (SPEC 7b), the OSM re-check,
waypoint packing (SPEC 8), the rotation (D6) and number formatting.

The recorder is covered too: the fix journal and its round trip, the recorder's filters (accuracy,
speed, dwell at a stop), road collapsing from a trace, the **stop picker** itself (junctions as
candidates, the +/-12 m bearing probe, name-change and far-point bonuses, the 120 m dedupe, the
9-stop cap and riding order), the Maps URL shapes (loop vs point-to-point, `SAME_STOP_M`), the raw
archive (pruning, filename collision, a header that is not a fix), the shortcut name and its stable
per-ride id, and the simulator loop itself (it must close on itself and have real corners - a
straight line would never exercise the stop picker).

## 4. Start the routing server (on the Mac)

```console
$ open -a Docker                                  # Docker Desktop must be running
$ /usr/local/bin/docker start valhalla            # ~10 s, no tile rebuild
$ curl -sf http://127.0.0.1:8002/status | head -c 60 && echo " OK"
$ tailscale serve status                          # should still show the port-80 proxy
```

To re-create the container from scratch (taken from `docs/SERVER.md` section 10):

```console
$ /usr/local/bin/docker run -dt --name valhalla --memory 6g --cpus 4 -p 127.0.0.1:8002:8002 \
    -v "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files:/custom_files" \
    -e build_elevation=True -e server_threads=4 ghcr.io/valhalla/valhalla-scripted:latest
```

## 5. Emulator demo (this Mac)

```console
$ export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ $ANDROID_HOME/emulator/emulator -avd pra_test -no-window -no-snapshot-save -no-audio -gpu swiftshader_indirect &
$ /opt/homebrew/bin/adb wait-for-device
$ /opt/homebrew/bin/adb reverse tcp:8002 tcp:8002                 # emulator 127.0.0.1:8002 -> Mac
$ /opt/homebrew/bin/adb emu geo fix -122.262019 47.240371          # NOTE: lon first, then lat
$ /opt/homebrew/bin/adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
$ /opt/homebrew/bin/adb shell am start -n com.biketrailplanner/.MainActivity
```

Then grant the location permission, tap **Read location**, type the round-trip miles and tap
**Plan ride**. `adb reverse` is only needed for the emulator; the phone uses the tailnet URL.

## 6. Google Maps: no key, no billing (D17/D18)

This app uses **no Google Maps Platform service at all**. There is no API key, no SHA-1
restriction, no billing account, and the `play-services-maps` dependency has been removed. The
in-app view is the app's own drawing of the graded route on a canvas (D17).

Handing the trip to Google Maps uses a plain `https://www.google.com/maps/dir/...` URL through an
`ACTION_VIEW` intent, which needs nothing but the Google Maps app:

* **Confirm on map** - the URL **without** `dir_action=navigate`, so Google Maps shows the route
  with its stops and a Start button. This is the confirmation step (D18).
* **Start navigating** - the same URL **with** `dir_action=navigate`, for an immediate start.

The intent is pinned to the Google Maps package (`intent.setPackage("com.google.android.apps.maps")`,
SEC-17). Without the pin, any app registered for `google.com/maps` URLs could receive the home
coordinates and the route; if Google Maps is not installed the app says so instead of falling back
to an unpinned intent.

## 7. Phone test

```console
$ adb -d install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
$ adb -d shell am start -n com.biketrailplanner/.MainActivity
```

The phone must be on the tailnet with the Mac awake. The default base URL is
`http://admins-mac-mini.taile1279d.ts.net`, and cleartext HTTP is allowed for that one host only
(`app/src/main/res/xml/network_security_config.xml`, SEC-3/SEC-16).

To check the hand-off shape by hand, paste this real URL from the emulator demo into a phone or a
terminal (it is the exact URL the app produced for a 12 mile plan):

```
https://www.google.com/maps/dir/?api=1&destination=47.240371,-122.262019&waypoints=47.254999,-122.277206|47.250571,-122.258297|47.242829,-122.241692|47.235562,-122.236612|47.249711,-122.237501&travelmode=bicycling
```

`Confirm on map` uses that URL (no `dir_action`, so Google shows the stops and a Start button);
`Start navigating` appends `&dir_action=navigate` to start straight away. When testing such a URL
in a device shell, encode the separators as `%7C` - an unquoted `|` is a shell pipe.

(That 12 mile plan is capped at the north stub. A 20 mile plan chains Lake Tapps -> Sumner Link ->
Riverwalk and detours around the closure; the app reports it as **NO SAFE ROUTE TODAY** because the
detour roads put grade D at 39.9% of the graded route, against a 10% cap.)

## 7b. Look and feel (docs/UI.md, M12)

Material 3 with the Google Maps palette: primary `#1A73E8`, surface `#FFFFFF`, sheet/chips
`#F1F3F4`, onSurface `#202124`, onSurfaceVariant `#5F6368`, outline `#DADCE0`, plus the six grade
colours. Screens are map-first: a top pill, distance chips, a rounded bottom sheet with a drag
handle, a segmented grade bar with a **Details** expander, and two actions that stay pinned.

The distance picker (D20) offers 5, 10, ..., 50 mi as pill chips plus a **Custom** stepper for any
value from 1 to 200 mi. The last value used is remembered (10 mi on first run). When a target is
larger than the trail network can supply, the sheet says so: *"The trails near home add up to about
16 mi, so most of this ride will be on roads."* With D9 the app never laps a trail - it chains or
caps - and the result screen says which.

**The app is always light (D19).** It is ridden in daylight, so there is no `isSystemInDarkTheme()`
anywhere; the light scheme is passed to `MaterialTheme` unconditionally and
`android:forceDarkAllowed="false"` stops the platform auto-darkening anything. The dark token set
stays documented in `docs/UI.md` only.

Rider language is mandatory: no decision numbers, no road classes, no "vpd", no raw coordinates on
a main screen. Everything technical (server address, connection dot, public-router switch, home
address, trail-data re-check, attribution) lives in **Settings**.

Check the wording structurally, without a screen:

```console
$ tools/verify_ui.sh emulator-5554 plan       # dumped UI tree + screenshot + colour report
$ tools/verify_ui.sh emulator-5554 result
$ tools/verify_ui.sh emulator-5554 settings
$ python3 tools/acceptance.py R5CW30PD58H     # end-to-end gate on a real device
```

Simulating a ride without riding (D51/D64 - the map draws it live while it happens). Two ways:

* **In the app.** *Simulate* rides a real **2.6 km OSM loop with corners** - 18th St E -> 122nd Ave E
  -> 24th St E -> 125th Ave Ct E -> home - shifted to the phone when the phone is away from home. A
  straight line would produce one stop and prove nothing, so the fixture *has* to turn (the test
  asserts at least six corners, four of them sharper than 80 degrees).
* **From the Mac, as a GPS feed.** `tools/simulate_ride.py` sends `adb emu geo fix` at 1 fix/s, either
  around a rectangle or along a real routed shape:

```console
$ python3 tools/ride_route.py                    # home -> Milton trail -> home, ~1 h, + RAW ROAD LIST
$ python3 tools/simulate_ride.py --serial emulator-5554 \
      --shape-file build/sim/ride-shape.json       # replays that 20 km shape, 1 fix/s
$ python3 tools/simulate_ride.py --serial emulator-5554 --lat 47.2404 --lon -122.2619 --distance-m 1500
```

`simulate_ride.py` uses `adb emu geo fix` by default because `geo nmea` is accepted but ignored on
the android-34 `google_apis` image (measured 2026-10-25: the GPS provider stayed on the last `geo fix`
with `satellites=0` through 60 sentences).

`tools/acceptance.py` is the independent gate (install, launch, plan, banned vocabulary, required
wording, the light-theme check, and the Google Maps hand-off). On the `pra_test` emulator its
`monkey` launch does not work (monkey cannot start any app there - measured), so run it against the
phone, or start the app once with `adb shell am start -n com.biketrailplanner/.MainActivity` first.

## 8. Closed trail segments (SPEC section 7b)

The middle 2.37 km of the Sumner Link Trail is **officially closed**: OSM way `419747831`, closed on
**2026-06-01** (changeset `183511084`, user `fencebuilder2000`) on the authority of a **City of Sumner
trail-closure notice**, having been `highway=cycleway, bicycle=designated` for six versions before that.
Valhalla drops the way from its graph, so the trail is effectively two stubs. The app:

* measures trail mileage on the **rideable** trail along the route actually planned, never on the
  nominal relation length;
* **routes around** the closed metres on roads, grades those roads with the same ladder, names them,
  and shows the detour in the summary ("2.02 mi each way via 142nd Avenue East, Tacoma Avenue, 24th
  Street East, ..." in the verified 20 mile run);
* states what is closed, **since when, and on whose authority**, on the plan and confirmation screens;
* offers **Refresh trail data**, which re-reads `way/<id>.json` and `way/<id>/history.json` from the OSM
  API and unlocks the segment as soon as `bicycle=no` disappears. Verified on the emulator:
  "way 419747831 is still closed in OSM (version 7, 2026-06-01T17:33:17Z)".

## 9. How it fits together

```
app/src/main/
  assets/trails.json          6 trails, geometry + connectivity (from docs/data/)
  assets/access_points.json   143 measured access points (from the safety probe)
  assets/trail_links.json     corridor joins and the road links, with their D10 verdict
  assets/trail_closures.json  the Sumner Link segment that is bicycle=no in OSM
  assets/limits.json          the local speed override table (ships EMPTY on purpose)
  assets/traffic.json         measured traffic volumes, with their count year
  assets/config.json          home origin and every planner threshold
  java/.../data/              TrailRepository, ValhallaClient, RouteCache + RideHistory (Room),
                              RideRecordingStore/RideFixJournal (what was recorded),
                              RideRawArchive (the fixes on disk), LiveRideTrack (the live line),
                              RideShortcut (the home-screen icon), RideSimulation, RideDebugLog
  java/.../domain/            Planner, SafetyGrader, TrailWalker, MapsUrlBuilder, Rotator, Geo,
                              RideRecorder (the filters), RideTrackGrader (line -> roads),
                              RideRoad + StopPicker (roads -> the 9 stops), GoogleMapsLink (D64),
                              MapShape (live vs recorded vs planned)
  java/.../ui/                PlanScreen, ResultScreen, RideScreen, SettingsScreen, BaseMap/RouteMap
  java/.../nav/               GoogleMapsLauncher, RideRecordingService (foreground GPS recorder)
```

Regenerate the assets from `docs/` with `python3 tools/build_assets.py`. If the traffic-data
producer writes `docs/traffic.json` or `docs/limits.json`, that script copies it in and the app
picks it up with no code change.

## 10. Honest limits

* **UNGRADED**: most of every route has no `maxspeed` in OSM. Those segments are never safe, always
  named, and shown against a separate budget ("graded 62% / ungraded 38%" in the verified demo run).
* **`assets/limits.json` ships with null speeds.** It names the 13 roads near home and carries no
  posted limit, because nobody has surveyed them and inventing one would turn an assumption into a
  false safety claim. Fill in `posted_speed_mph` and those segments get graded.
* **Traffic counts are old.** 18th St E is 381 vpd but from **1996**. Those rows are labelled STALE
  and always shown with their year. A published `AADT 0` means "Not Known" and is loaded as unknown.
* **What the measured volume changed (verified on the emulator, same 12 mile plan):** grading went
  from 62% graded / 38% ungraded to **81% graded / 19% ungraded**, and grade D rose from 0.51 mi to
  2.63 mi. Jovita Boulevard East now grades **D** on a measured **10,358 vpd (1996, STALE)** count
  with a published LTS 4 advisory; 114th Avenue East carries 2,337 vpd (medium) and therefore stays
  UNGRADED. The route still fails D10 and the app still says **NO SAFE ROUTE TODAY**.
* **The Sumner Link Trail is severed** (see section 8). Plans either detour around it on roads, which
  pushes grade-D exposure well over the D10 cap and is reported as **NO SAFE ROUTE TODAY**, or they
  cap the ride at the stub. Both outcomes are stated, never hidden.
* **The corridor walk can dead-end** at the far end of a trail when no documented join continues it;
  the app then caps the ride and says which trail ran out.
* **Google re-routes through the waypoints.** The app packs at most 9 waypoints at the places that
  matter and lists them in ride order, but Google still chooses its own path between them. This is a
  real difference, not a technicality: a recorded ride that followed the **Milton Interurban Trail**
  came back from Google Maps along **Edgewood's streets** instead, because Maps has no way to be told
  the trail is rideable. Naming the corners is the trade-off - the alternative is a route Maps would
  ignore.
* **A recorded ride cannot be replayed exactly.** Maps' URL API takes stops, not a line. The app
  gives it the corners that would change the route; the metres between them are Google's choice.
* **The recording is only as good as the GPS.** 8% of the measured ride had no speed information at
  all. Those metres are stored and navigable, but they cannot be graded - and the app says so instead
  of guessing. The raw archive keeps even the fixes the recorder rejected, so a bad stretch can be
  re-examined later rather than argued about.
* **`trace_attributes` may fail**: if neither `edge_walk` nor `map_snap` matches the shape, the road
  legs are shown as ungraded and the route is marked not verified, never safe.
