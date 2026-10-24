# RIDE-RECORDER — recording the ride the rider actually rode

Status: **implemented 2026-09-26** (D51 core + D52 Android wiring; only the items in section 6
remain). Research that forced this shape:
`docs/research/google-maps-timeline-import.md`.

## 1. Why not import

The rider asked to "save/import the finished trips from google map or velotric map as saved trips".
Research killed it: Google Maps Platform has **no** Timeline / Location History / trips API; Timeline
is stored on the phone, end-to-end encrypted, so no server API is even possible; Velotric exposes no
export or API; Google Fit was shut down 2025-06-30. The rider then chose to have the **app record the
ride itself**, because *"I always explore smaller streets, so the actual trip is never the planned
trip."*

## 2. Decisions (rider, 2026-09-26)

1. **Both triggers** — start recording automatically at a hand-off to Google Maps / Velotric, **and**
   a manual "Record a ride" for a ride with no plan.
2. **Both outcomes** — a finished recording becomes a **graded saved trip** *and* a history/rotation
   entry.
3. **No Google Maps import.** The recorder replaces it.

## 3. Shape

```
hand-off or "Record a ride"
        │
        ▼
RideRecordingService  (foreground service, foregroundServiceType="location",
        │              visible notification, Stop action)
        │  collects fixes
        ▼
LocationSource ──────────────► RideRecorder (pure: filter, dedupe, distance, gaps, pause)
        │                                   │
        │ on stop                            ▼
        │                            RecordedRide (points, distance, duration, gaps)
        │                                   │
        │                            Valhalla trace_attributes (same call the planner makes)
        ▼                                   ▼
stop triggers:                    graded saved trip (SavedRouteRecord, "as ridden")
  - Maps navigation end (RideWatchService signal)
  - explicit Stop on the notification
  - return-to-start
  - a hard time cap
```

A **foreground** service keeps running while the rider is in Google Maps or Velotric, so the recorder
needs **no `ACCESS_BACKGROUND_LOCATION`** — this preserves the D35/D36 "assist, never background
surveillance" stance. The recording notification is the visible signal and the Stop control.

### Honest limits (must be surfaced, never hidden)
- The app must be running; if Android kills it, the track has a **gap**, and the saved trip says so.
- GPS accuracy varies; fixes worse than `maxAccuracyM` are dropped and counted.
- A recorded track is graded UNGRADED wherever the roads still are — recording does not make a road
  safe, and an "as ridden" trip can come back NOT VERIFIED.
- Location stays on the device; the recorder writes only to the app's own Room tables.

## 4. Testability without riding (rider's explicit requirement)

The recorder consumes a pure `LocationSource` seam, so the same recorder code is driven four ways:

| Level | Drives | Where |
|---|---|---|
| 1. Scripted track | `RideRecorder` logic + conversion | JVM unit test (`RideRecorderTest`), CI, no device |
| 2. Debug in-app simulator | the real foreground service + DB + UI | debug builds, phone or emulator |
| 3. Emulator NMEA playback | the **real** `AndroidLocationSource` | `tools/simulate_ride.py` over `adb emu geo nmea` |
| 4. Mock location | the real platform path on hardware | Developer Options → Select mock location app |

`domain/TrackSimulator.kt` generates a plausible track (speed, sampling interval, GPS jitter,
accuracy, dropouts) from any `LatLon` shape, so one generator serves the JVM tests and the debug
simulator, and `tools/simulate_ride.py` generates the same shape for the emulator's own provider.

## 5. Files

```
app/src/main/java/com/biketrailplanner/
  domain/RideRecorder.kt       pure accumulator: fixes -> RecordedRide (D51)
  domain/TrackSimulator.kt     synthetic tracks for tests and the debug simulator
  domain/RideTrack.kt          graded ride + RideTrackStore + RideTrackSignature
  domain/RideTrackCodec.kt     encoded polyline + named segments + grades + way ids (payload)
  domain/RideTrackGrader.kt    recorded track -> graded RideTrack (same SafetyGrader as a plan)
  data/Room.kt                 ride_track entity/DAO, AppDatabase v4, RoomRideTrackStore
  data/LocationSource.kt       the seam: AndroidLocationSource + ScriptedLocationSource
  data/RideRecordingStore.kt   persisted "a ride is being recorded" flag
  nav/RideRecordingService.kt  foreground location service + notification + Stop + auto-stop
  domain/RideAutoStop.kt       the two automatic stop rules (back at start, six-hour cap)
  AppContainer.kt              start/stop + saveRecordedRide (trace -> grade -> store -> history)
  ui/PlanViewModel.kt          auto-start at hand-off, recordingActive state, toggle
  ui/Components.kt             `RideRecordingBanner`: the always-visible "recording" strip
  ui/SettingsScreen.kt         "Record this ride" row + "Past rides" entry
  ui/RidesScreen.kt            the list of rides the rider actually rode, graded
  MainActivity.kt              draws the banner, routes to the rides list
app/src/test/java/com/biketrailplanner/
  RideRecorderTest.kt          9 tests: distance, jitter, accuracy, gaps, pause, simulator
  RideTrackTest.kt             6 tests: grading, UNGRADED, codec, signature, store order
  RideAutoStopTest.kt          5 tests: the two automatic stop rules
tools/simulate_ride.py         NMEA playback against the emulator's real location provider
```

## 6. Open items

Done: storage (`ride_track`), grading/conversion, the foreground service + notification + Stop, the
auto start at hand-off, the auto stop on the `RideWatchService` navigation-end signal, the two
automatic stops (`RideAutoStop`: back-at-the-start and a six-hour cap), the manual Settings toggle, the
"Past rides" list with per-ride grading and delete, the always-visible recording banner
(`ui/Components.kt` `RideRecordingBanner`, drawn on every screen from `MainActivity`), the manifest
permissions/service type, the NMEA simulator tool, the debug in-app simulator
(`data/RideSimulation.kt` + Settings → Debug tools), and D55: **a finished recording always reaches
Past rides, whatever its length** - `hasFixes` (one usable fix) is the gate on keeping a ride, and
`hasTrack` (two points) only decides whether there is a line to draw and trace. A recording that caught
no usable fix at all is still not stored, and says so. D57 makes the app tell the truth about whether a
recording is running at all: the persisted flag is only believed alongside a live service, and the
banner appears on start and clears on stop (both were broken by the async service lifecycle).

**Verified on the phone (2026-10-11).** A *stationary* phone - the case that used to be thrown away -
recorded three rides of **0.0 m, 0.0 m and 3.1 m**, and all three are in Past rides; the 3.1 m one was
even traced and matched to one OSM way. The notification for a trackless one reads *"Ride saved · Saved
to Past rides · too short to map"*, and its card carries *"Too short to map: the recording caught a
single GPS fix, so there is no route to draw."*

Still open:
- **Nothing blocking.** The graded path was verified on an emulator on 2026-10-10 (section 7); a run on
  the rider's own phone away from the tailnet would be the last nice-to-have.

## 7. Device test result (2026-10-10, Galaxy S23 Ultra, Android 16)

Driven entirely over `adb` with the debug in-app simulator (the rider cannot ride). `arm64-v8a` debug
APK, `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` / `POST_NOTIFICATIONS` granted.

| Step | Result |
|---|---|
| Settings → Debug tools → *Record a simulated ride* | started |
| `RideRecording` log | `SIMULATED recording from LatLon(47.24030035, -122.26187909)` (a real fix) |
| Foreground notification 4712 | `Recording your ride`, channel `ride_recording`, 1 action, `ONGOING_EVENT` |
| Auto-stop | `recording stopped on its own: RETURNED_TO_START` after ~49 s of replay |
| Result notification 4713 | `Ride saved` · `1.0 mi recorded · 100% not measured · You were back where you started, so the recording stopped.` |
| `ride_track` row | 1664.8 m, 299 s moving, max 5.0 m/s, 0 gaps, 0 rejected fixes, 0 graded / 1664.8 ungraded, 0 ways |
| `ride_history` row | registered for the D6 rotation |
| Past rides (after a force-stop + relaunch) | card shows `Recorded ride`, the date, `1.0 mi`, and the honest *“100% of this ride had no speed information, so it could not be measured.”* |

**Findings.**

1. **The whole recorder works on-device**, and the saved ride survives an app restart. Storage,
   service, notification, auto-stop, history and UI are all confirmed.
2. **The trace failed and the ride was still kept, exactly as designed.** The local Valhalla was down:
   `http://127.0.0.1:8002/trace_attributes` refused the connection and the tailnet URL
   `http://admins-mac-mini.taile1279d.ts.net` returned **502**. `saveRecordedRide` stored the ride
   UNGRADED rather than dropping it, with no way to claim it was safe. This is the intended behaviour,
   but it means the *graded* path was not exercised on-device.
3. **Known simulator artifact:** the replay is faster than real time, so `movingMs` (299 s, simulated)
   exceeds the wall-clock duration used for `durationMs` (~49 s) and the card reads `Time 0 min`. On a
   real ride the two agree; the card is right for real rides.

### 7.1 Emulator re-run, and the two bugs it found (2026-10-10)

The same test on an Android 16 arm64 emulator (no real fix, so the simulator fell back to
`RideSimulation.DEFAULT_ORIGIN`). The primary endpoint is the tailnet Valhalla, which an emulator cannot
reach - which turned the run into a test of the D12 public fallback.

| Run | Result |
|---|---|
| First (before the fixes) | `Ride saved · 1.0 mi recorded · 27% not measured` - the ride graded, but see bug 2 |
| After the fixes | `Ride saved · 1.0 mi recorded · 63% not measured`; row: ridden 1665 m, breakdown 1665 m, graded 613 m, ungraded 1052 m, 9 ways, `measured share 36.8%` |

**Bug 1 - a recorded ride could never use the public fallback.** `ValhallaClient.route` has honoured
D12 (`allowFallback` -> `publicBase`) since it was written, but `trace(shape, baseUrl)` took a single
endpoint, and `AppContainer.saveRecordedRide` passed only `BuildConfig.VALHALLA_BASE_URL`. So on any
phone that was not on the tailnet - i.e. anywhere away from home - **every** recorded ride was stored
100% ungraded while planning on the very same server worked fine. Fixed: `RouteService.trace` gained
`allowFallback` (default false, so every existing caller is unchanged and a planned route still traces
against the graph it came from), and the recorder now passes the rider's own fallback choice. The
emulator run proves it: primary unreachable -> public server -> graded, with real street names.

**Bug 2 - the measured share was computed against the wrong denominator.** A recorded trace only partly
matches the road network. On the first emulator run the rider rode **1665 m**, the trace matched
**843 m**, and the breakdown totalled 843 m - so the app reported **72.7% measured** when only 36.8% of
the distance actually ridden had been looked at. The unmatched 822 m was silently dropped. That is the
opposite of what this feature is for. Fixed: `RideTrackGrader` now counts the unmatched remainder as an
explicit UNGRADED segment (`"(not matched to a road)"`), so the breakdown always adds up to the
distance the rider rode and the share is the share of the RIDE that was assessed. On a real ride this
can only ever make the app less confident, never more - which is the right direction.
