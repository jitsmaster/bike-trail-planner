# Bike Trail Planner

A personal Android app that plans a **safe bicycle round trip from home**: home -> the nearest
access point of a nearby bike trail -> ride the trail for the mileage that is left -> home. It
grades every road segment, shows the route on a map for confirmation, and hands the trip to
Google Maps for live bicycle turn-by-turn navigation.

The spec is `docs/SPEC.md`; the locked decisions are `docs/DECISIONS.md`; the routing server is
documented in `docs/SERVER.md`; the measured safety reality is `docs/SAFETY-PROBE.md`. Those files
are authoritative and are not modified by the app.

**What the app will not do:** it never calls an ungraded segment safe. 82-100% of every route from
this home runs on roads with no `maxspeed` in OpenStreetMap, so most of the ride is reported as
**UNGRADED** and named street by street, with a **NOT VERIFIED** banner.

## 1. Requirements

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

## 2. Build, install, test

```console
$ export JAVA_HOME=/opt/homebrew/opt/openjdk@17
$ ./gradlew :app:assembleDebug
$ ./gradlew :app:testDebugUnitTest        # 442 JVM unit tests, all passing
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

## 3. Start the routing server (on the Mac)

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

## 4. Emulator demo (this Mac)

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

## 5. Google Maps: no key, no billing (D17/D18)

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

## 6. Phone test

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

## 6b. Look and feel (docs/UI.md, M12)

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

Simulating a ride without riding (D51/D64 - the map draws it live while it happens):

```console
$ python3 tools/ride_route.py                    # home -> Milton trail -> home, ~1 h, + RAW ROAD LIST
$ python3 tools/simulate_ride.py --serial emulator-5554 \
      --shape-file build/sim/ride-shape.json       # replays it into the emulator's GPS, 1 fix/s
```

`simulate_ride.py` uses `adb emu geo fix` by default because `geo nmea` is accepted but ignored on
the android-34 `google_apis` image (measured 2026-10-25: the GPS provider stayed on the last `geo fix`
with `satellites=0` through 60 sentences).

`tools/acceptance.py` is the independent gate (install, launch, plan, banned vocabulary, required
wording, the light-theme check, and the Google Maps hand-off). On the `pra_test` emulator its
`monkey` launch does not work (monkey cannot start any app there - measured), so run it against the
phone, or start the app once with `adb shell am start -n com.biketrailplanner/.MainActivity` first.

## 7. Closed trail segments (SPEC section 7b)

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

## 8. How it fits together

```
app/src/main/
  assets/trails.json          6 trails, geometry + connectivity (from docs/data/)
  assets/access_points.json   143 measured access points (from the safety probe)
  assets/trail_links.json     corridor joins and the road links, with their D10 verdict
  assets/trail_closures.json  the Sumner Link segment that is bicycle=no in OSM
  assets/limits.json          the local speed override table (ships EMPTY on purpose)
  assets/traffic.json         measured traffic volumes, with their count year
  assets/config.json          home origin and every planner threshold
  java/.../data/              TrailRepository, ValhallaClient, RouteCache + RideHistory (Room)
  java/.../domain/            Planner, SafetyGrader, TrailWalker, MapsUrlBuilder, Rotator, Geo
  java/.../ui/                PlanScreen, ConfirmMapScreen, summary card
  java/.../nav/               GoogleMapsLauncher (ACTION_VIEW, SPEC section 7 step 8)
```

Regenerate the assets from `docs/` with `python3 tools/build_assets.py`. If the traffic-data
producer writes `docs/traffic.json` or `docs/limits.json`, that script copies it in and the app
picks it up with no code change.

## 9. Honest limits

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
* **The Sumner Link Trail is severed** (see section 7). Plans either detour around it on roads, which
  pushes grade-D exposure well over the D10 cap and is reported as **NO SAFE ROUTE TODAY**, or they
  cap the ride at the stub. Both outcomes are stated, never hidden.
* **The corridor walk can dead-end** at the far end of a trail when no documented join continues it;
  the app then caps the ride and says which trail ran out.
* **Google re-routes through the waypoints.** The app packs at most 9 waypoints at the places that
  matter and lists them in ride order, but Google still chooses its own path between them.
* **`trace_attributes` may fail**: if neither `edge_walk` nor `map_snap` matches the shape, the road
  legs are shown as ungraded and the route is marked not verified, never safe.
