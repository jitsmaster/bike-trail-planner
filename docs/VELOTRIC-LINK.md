# Velotric app link — can bike-trail-planner start/stop a Velotric ride?

Status: **answered and verified on the device** · 2026-09-16 · device Samsung SM-S918U (1440x3088), Android,
Velotric app `com.mddoscar.velotricbike` **4.5.5** (versionCode 217, targetSdk 35, installed 2026-08-12).

Deep background research (identity, policy, fallbacks, 34 source URLs) lives in
`docs/research/velotric-app-control.md`. This file records what was **measured on the phone**.

---

## 1. Short answer

| Ask | Answer | Mechanism |
|---|---|---|
| Start a Velotric ride when our "Start navigation" is pressed | **Yes, but only by UI automation.** No API exists. | Accessibility/`input` gesture on the node `content-desc="START RIDING"` |
| Stop the Velotric ride when the destination is reached | **Yes, same way — a 2-step gesture.** No API exists. | Long-press (~3 s) `content-desc` ride HUD, then tap `Confirm` |
| Any deep link, intent, SDK, broadcast, notification, or cloud API | **No.** Verified negative. | see §2 |

Consequence: this is **automation of another app's screen**, not integration. It needs an
`AccessibilityService` (with the Play "Permissions Declaration Form" + a prominent in-app disclosure,
because automation is explicitly not an accessibility tool) or Shizuku/ADB (dev-only, not shippable).

## 2. The five closed doors (measured, not assumed)

1. **Deep links / App Links** — the only custom scheme in the manifest is `hwpps://com.mddoscar.velotricbike`,
   which belongs to a Huawei **ads** SDK. The vendor publishes an empty `assetlinks.json` (`[]`).
   `aapt2 dump xmltree --file AndroidManifest.xml` shows no ride/record intent filter.
2. **Exported components** — 9 exported components, none for rides. The recording service
   `com.pravera.flutter_foreground_task.service.ForegroundService` is `android:exported="false"`,
   so no other app can start it.
3. **Notification hook** — **there is no notification at all while a ride records.** Measured during a live
   ride: `dumpsys notification --noredact` has zero `NotificationRecord` for the package, and
   `dumpsys activity services` shows only `com.mapbox.common.LifecycleService`,
   `com.baseflow.geolocator.GeolocatorLocationService` and `com.lyokone.location.FlutterLocationService`.
   No foreground service runs, even after `KEYCODE_HOME`. So a `NotificationListenerService` cannot see,
   let alone stop, a ride.
4. **Cloud API** — the APK embeds `http://app.velotricbike.com:7002`. In 4.5.5 the only ride routes are
   `/api/rides` and `/api/rides/ridingEvent` (both credential-gated, undocumented, and they *upload* a ride
   the phone already recorded — they do not start one). The 2022 build had `/api/base/rides/go` and
   `/api/base/rides/{}/end`, but that host (`rdprod.velotric.net:7072`) is NXDOMAIN today.
5. **Bike-side recording** — none. During my test the bike read **"Disconnected"** and the ride still
   recorded, so ride recording is entirely in the phone app. The bike display keeps only a trip counter and
   odometer. (The independent BLE protocol `seaty6/velotric-pwa` drives the bike but has no record opcode.)

## 3. What actually works (end-to-end test, same session)

Flutter exposes semantics to UiAutomator, so the buttons are addressable by name — no pixel guessing needed
for discovery:

```
adb shell uiautomator dump   # home screen, before
  'START RIDING'        bounds=(61,2117)-(1379,2361) clickable=true
  'BIKE SETTINGS'       bounds=(61,2452)-(1379,2696)
```

1. **START** — `adb shell input tap 720 2239` (centre of `START RIDING`).
   Result after 7 s: the ride HUD appeared, one merged accessibility node
   `content-desc="Disconnected\n63%\n0\n.\nMPH\n0\nECO\nHold to Stop\nResume"`,
   bounds `(0,1492)-(1440,3032)`, `long-clickable=true`. **A ride started while the bike was disconnected.**
2. **STOP** — the HUD's `Hold to Stop` is a **long-press**, not a tap.
   `adb shell input swipe 381 2825 381 2825 3000` (3 s long-press, centre read by OCR of the screenshot)
   produced a confirmation dialog, read back from the semantics dump:
   `WARNING` / `The trip distance is less than 100 meters, so this ride won't be recorded.` /
   `Confirm` `(216,1719)-(1224,1899)` / `Dismiss`.
3. **CONFIRM** — `adb shell input tap 720 1809` → app returned to the home screen (`START RIDING` visible),
   no service left running.

Side finding: **a ride shorter than 100 m is never recorded**, so a start/stop probe leaves no junk ride
in the Velotric account.

Evidence files (raw, in git-ignored `docs/phone-evidence/velotric/`):
`vel_01_home.xml` (home semantics), `vel_03_ride.png` + `/tmp/v6.xml` (ride HUD),
`vel_04_after_stop.png` (warning dialog), `vel_05_stopped.png` (back home).

Note on tooling: this model has no image input, so screenshots were read with macOS **Vision OCR** through
`pyobjc` (`Vision.VNRecognizeTextRequest`), which returns text plus pixel boxes. That is what located
`Hold to Stop` at `(143,2784) 476x82`.

## 4. Options, ranked

| # | Option | Ship-able | Notes |
|---|---|---|---|
| A | **Assist, don't automate**: our "Start navigation" opens the Velotric app (or shows a one-tap "Start Velotric ride" button); at arrival show a "Stop your ride" reminder | **Yes, clean** | Zero policy risk, zero fragility. Costs the user 2 taps. |
| B | **AccessibilityService automation**: launch Velotric, `dispatchGesture` tap `START RIDING`; at arrival long-press + tap `Confirm`, then return | Yes, with declaration + disclosure | Needs screen on and the Velotric app in front; both steps are name-addressable, but the warning dialog and layout can change with app updates. |
| C | **Shizuku / `adb input`** | No | Wireless debugging, re-auth each reboot, not a Play product. Fine as a personal dev rig. |
| D | **Read-only complement — Health Connect**: the Velotric app holds `health.WRITE_DISTANCE` + `WRITE_ACTIVE_CALORIES_BURNED` and writes finished rides there; our app can *read* them to attach real ride distance/calories to our own trip record | Yes, clean | Cannot start/stop anything, but gets the Velotric numbers without any automation. Needs one confirmatory test that the app really writes per-ride records. |
| E | Ignore Velotric; track the ride in bike-trail-planner | Yes | Loses Velotric-account stats sync. |

## 5. What was decided

**The user chose option A — assist, not automation (2026-09-16), and it is built (D35).** The app
opens the Velotric app and never presses one of its buttons, so there is no `AccessibilityService`,
no Play permissions declaration and no fragility to a Velotric UI change. The three shipped pieces:

1. `nav/VelotricLauncher.kt` — pinned to `com.mddoscar.velotricbike`, `available()` so the UI can hide
the button when the app is absent.
2. A **"Start Velotric ride"** button beside "Confirm on map" / "Start navigating" on the result
   screen; it only opens the Velotric app so the rider taps START RIDING themselves.
3. An **on-return arrival prompt** (option **3a**: no background location, no background service). A
good fix back within ~250 m of the start point, at least ~10 minutes after the hand-off, shows a bar
that opens the Velotric app so the rider can stop the ride. The decision is the pure function
`domain/RideNudge.kt`, unit-tested by `RideNudgeTest`.

**The cost is still 2 taps per ride.** Option A removes the *memory* problem, not the taps; the UI
copy must keep saying so. Not built, by choice: option B (accessibility automation), option C
(Shizuku/ADB), option D (Health Connect read-only), option E (track the ride in this app).

Still open, deliberately: should a nudge fire when the rider stops still >250 m from the start?

---

## 6. D36 — end the ride when NAVIGATION stops (2026-09-16)

The rider asked to change the trigger: "the ride is ended when I stop navigation, not just return to
original location". Google Maps has no "navigation stopped" event, so this is built on its navigation
notification, which was measured on the phone and is unambiguous:

```
pkg=com.google.android.apps.maps  id=1 tag=null  channel=1_foreground_1  category=navigation
flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE|PROMOTED_ONGOING  groupKey=navigation_status_notification_group
```

`NO_CLEAR` is the important part: the rider cannot swipe it away, so its **removal means navigation
really ended**. It is removed when navigation stops, and there is then no live `NotificationRecord`
for Maps (verified).

What was built:

- `nav/RideWatchService.kt` — a `NotificationListenerService` that feeds the notification events to
  the pure state machine and persists the outcome. It also re-reads `getActiveNotifications()` on
  reconnect, so a navigation already running is still detected after our process is killed.
- `domain/RideWatch.kt` — the whole decision, unit-tested on the JVM: armed on either hand-off, fires
  only if a **real navigation was seen**, 6-hour window, disarmed on fire / dismiss / new plan.
- `data/RideWatchStore.kt` — the persisted watch, because Android kills the app during a 30-60 min
  ride.
- `nav/RideReminder.kt` — the one heads-up prompt: **"Ride finished?"** → tap opens Velotric. A tap is
  user-initiated, which is the only kind of launch Android allows from the background; option A is
  still intact, and the app never presses Velotric's buttons.
- **Safety net:** tapping **Start Velotric ride** records the ride, and if Google Maps navigation is
  never seen the rider is reminded **when the app is next opened**, so a started ride cannot be left
  running with no reminder at all.
- Settings gains an **"End of ride"** row showing the status and offering the grant.

Permissions and the cost: **Notification access** (a special grant that lets the app read *every*
notification on the phone — there is no narrower one) and, on API 33+, **POST_NOTIFICATIONS**. If
Notification access is not granted the app falls back to the D35 return-to-start nudge and says so.

This supersedes D35's trigger but keeps D35's nudge as the fallback. The taps themselves are
unchanged: option A removes the *memory* problem, never the two taps.
