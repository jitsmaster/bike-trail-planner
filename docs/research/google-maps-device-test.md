# Google Maps multi-stop BICYCLE handoff — Android device test

**Question.** On Android, when another app hands Google Maps a *multi-stop bicycle round trip*
(origin = destination, waypoints in between, `travelmode=bicycling`), does Google Maps actually start
**turn-by-turn navigation**, or does it only show a **preview** of the stops?

**Answer (one line).** Google Maps **does start real turn-by-turn navigation and keeps the waypoints as
navigation stops** — but *only when the route origin is (close to) the device's current location*. When the
origin is elsewhere, Maps shows a preview and waits for the user to press **Start**.
**Practical upshot:** omit `origin` from the handoff URL and one 155-character URL starts a multi-stop
bicycle round trip from the rider's live position with no further taps (§3.11).

**VERDICT: `NAVIGATES_MULTISTOP`** (with the location precondition stated in §6).

- Method: emulator device test, 2026-09-15. Raw evidence in `docs/research/raw/device-test/`
  (screenshots `.png`, UI text dumps `.xml`, logcat `.logcat`).
- Related prior work: `docs/research/google-maps-handoff.md` §8 listed this exact behaviour as
  **[NOT DOCUMENTED]** and "verify on a device before relying on it". This file is that verification.

---

## 1. Environment

| Item | Value |
|---|---|
| Host / tooling | macOS; `adb` = `/opt/homebrew/bin/adb`; emulator = `/opt/homebrew/share/android-commandlinetools/emulator/emulator` |
| AVD used | `pra_test` (`emulator -list-avds` also lists `tts_test`) |
| AVD system image | `system-images/android-34/google_apis/arm64-v8a`, `tag.id=google_apis`, `abi.type=arm64-v8a`, `PlayStore.enabled=no` in `config.ini` |
| Boot command | `emulator -avd pra_test -no-window -no-snapshot-save -no-audio -gpu swiftshader_indirect` |
| Device | `emulator-5554`, `ro.product.name` = `sdk_gphone64_arm64`, brand `google`, `ro.build.version.sdk` = 34, `ro.build.version.release` = 14 |
| Build | `ro.build.fingerprint` = `google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys` |
| Google Maps | **installed**: `com.google.android.apps.maps` versionName **11.71.0300** (versionCode 1066937505, lastUpdateTime 2024-07-11) |
| Play services | `com.google.android.gms` versionName **23.18.18** (190400-535401451); `com.google.android.gsf` present |
| Play Store | `com.android.vending` **present** (versionName 1.8) — surprising given `PlayStore.enabled=no`; this image ships Google apps even though it is not a `google_apis_playstore` image |
| Image type | **Google APIs image with bundled Google apps** (Maps, GMS, GSF, Play Store, Gmail, Photos, YouTube). Not AOSP. |
| Google account | **none signed in** — `adb shell dumpsys account` → `Accounts: 0`. Maps showed a "Make it your map / Sign in" sheet, which was dismissed with SKIP |
| Location | `settings get secure location_mode` = 3; Maps location permissions were `granted=false` and were granted by `adb shell pm grant`; position was set with `adb emu geo fix <lon> <lat>` |
| Maps UI language | en-US |

### How the screen was observed (important limitation)

**The agent running this test has no vision capability** (`attach_image` refused: *"deepseek-v4-flash does
not support vision"*). Nothing in this file is a guess about pixels:

1. **Primary evidence = machine-read UI text.** `adb shell uiautomator dump` + the raw XML, which exposes the
   real accessibility tree: button labels, `resource-id`s, `content-desc`, stop list rows, ETA text.
   Every quoted string in §3 comes from that XML (or from `dumpsys notification` / `dumpsys window`).
2. **Supporting evidence = `dumpsys notification` and `dumpsys window`**, which show that Maps' navigation
   notification and navigation service are (or are not) running, and which app has focus.
3. **Pixel checks with PIL** (colour histograms of the bottom action band), which need no vision — see §4.1.

**No visual (image-reading) review was obtained: nobody and nothing has looked at the pictures.** The PNGs are
saved as the raw record and §4.2 lists exactly what a human must check in them. Screenshot-derived claims are
marked `[not visually verified]`.

---

## 2. Commands used (verbatim)

```bash
# boot + readiness
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd pra_test -no-window -no-snapshot-save -no-audio -gpu swiftshader_indirect
adb devices -l
adb shell getprop sys.boot_completed          # -> 1

# environment
adb shell getprop ro.product.name ; adb shell getprop ro.build.version.sdk ; adb shell getprop ro.build.version.release
adb shell pm list packages | grep -E 'maps|vending|gms'
adb shell dumpsys package com.google.android.apps.maps | grep -E "versionName|versionCode"
adb shell dumpsys package com.google.android.gms   | grep -E "versionName"
adb shell dumpsys account | head -5                # -> "Accounts: 0"
adb shell settings get secure location_mode        # -> 3

# location + permission control
adb shell pm grant com.google.android.apps.maps android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.google.android.apps.maps android.permission.ACCESS_COARSE_LOCATION
adb emu geo fix -122.7588 45.4186                  # <lon> <lat>  (Portland, OR)
adb shell dumpsys location | grep -E "last location"

# fire a handoff (URL)  -- note: single-quote the URL so the phone shell does not eat the '&'
adb shell am start -a android.intent.action.VIEW -d '<URL>'

# fire the legacy intent
adb shell am start -a android.intent.action.NAVIGATION -d 'google.navigation:q=45.4765,-122.5450&mode=b'
adb shell am start -a android.intent.action.VIEW       -d 'google.navigation:q=45.4765,-122.5450&mode=b'
adb shell cmd package query-activities --brief -a android.intent.action.NAVIGATION -d 'google.navigation:q=45.4765,-122.5450&mode=b'

# observe
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
adb exec-out screencap -p > <name>.png
adb logcat -c ; ... ; adb logcat -d -v threadtime > <name>.logcat
adb shell dumpsys window | grep mCurrentFocus
adb shell dumpsys notification --noredact | grep -A25 'pkg=com.google.android.apps.maps'
```

Each test was isolated with `adb shell am force-stop com.google.android.apps.maps` before firing.

### URLs under test (Portland, OR — real coordinates)

```text
# A) round trip, 2 waypoints, WITH dir_action=navigate   (180 chars)
https://www.google.com/maps/dir/?api=1&origin=45.4186,-122.7588&destination=45.4186,-122.7588&waypoints=45.4612,-122.6602|45.4765,-122.5450&travelmode=bicycling&dir_action=navigate

# B) identical, WITHOUT dir_action                        (160 chars)
https://www.google.com/maps/dir/?api=1&origin=45.4186,-122.7588&destination=45.4186,-122.7588&waypoints=45.4612,-122.6602|45.4765,-122.5450&travelmode=bicycling
```

---

## 3. Test matrix and observed results

Nine fires, each with a screenshot + UI dump + logcat. All quoted strings below are verbatim from the
uiautomator XML or from `dumpsys`. "Button label" means the text/`content-desc` of the clickable
`resource-id=...start_button` (or the equivalent on-map control).

| # | What was fired | Device GPS at fire time | Navigation started? | Button label | Evidence file stem |
|---|---|---|---|---|---|
| t1 | URL A (2 waypoints, `dir_action=navigate`) | `37.4220,-122.0840` (Mountain View, ~1000 km away) | **no** — route sheet | **"Start navigation"** | `t1-url-nav-far` |
| t2 | URL B (same, no `dir_action`) | same | **no** — route sheet | **"Preview"** | `t2-url-no-dir-action` |
| t3 | URL A | `45.4186,-122.7588` = the route origin | **YES** — turn-by-turn | — | `t3-url-nav-locATorigin` |
| t4 | URL A, then fake GPS moved to waypoint B | at waypoint B | YES, advanced to leg 2 | prompt "Continue" | `t4-nav-after-move-to-B` |
| t5 | then fake GPS moved to waypoint C | at waypoint C | YES, advanced to leg 3 (back to origin) | prompt "Continue" | `t5-nav-after-move-to-C` |
| t6 | URL A | `45.4400,-122.8000` (≈4 km from origin) | **no** — dedicated "Route preview" screen | none found | `t6-url-nav-offorigin-preview` |
| t7 | 9-waypoint URL, no `dir_action` | ≈4 km from origin | no — route sheet, "**9 stops**" | "Preview" | `t7-url-9waypoints-offorigin` |
| t8 | 10-waypoint URL, no `dir_action` | same | no — route sheet, still "**9 stops**" | "Preview" | `t8-url-10waypoints-offorigin` |
| t9a | `am start -a android.intent.action.NAVIGATION -d google.navigation:…&mode=b` | ≈4 km from origin | **cannot resolve** | — | `t9a-navigation-action` |
| t9b | `am start -a android.intent.action.VIEW -d google.navigation:…&mode=b` | ≈4 km from origin | **YES** — immediate turn-by-turn | — | `t9b-view-google-navigation-uri` |
| t10 | same as t9b but `mode=w` (walking, A/B control) | same | YES | — | `t10-view-gnav-mode-w` |
| t11 | 9-waypoint URL **with** `dir_action=navigate` | = the route origin | **YES** — turn-by-turn, stop queued | — | `t11-url-9wp-nav-at-origin` |

### t1 — URL A, GPS far from origin (the documented "preview" case)

```text
adb shell am start -a android.intent.action.VIEW -d '<URL A with dir_action=navigate>'
```
Fired; Maps opened its directions sheet (focus `com.google.android.apps.maps/com.google.android.maps.MapsActivity`).
Verbatim from the UI dump:

```text
resource-id=...start_button  clickable=true   content-desc="Start navigation"   text="Start"
[251,178]  text="7925 SW Fanno Creek Dr, Portland, OR 97224"     <- origin row
[251,262]  text="2 stops"                                        <- the waypoints survived
[251,346]  text="7925 SW Fanno Creek Dr, Portland, OR 97224"     <- destination row (= origin)
[0,0]      content-desc="bicycle directions"                     <- map container says bicycle
content-desc="bicycling. 1 hour 1 minute. via SW Multnomah Blvd. 11 mi. Moderate hills, Total ascent of 1332 ft,
              Total descent of 1332 ft.. This route has restricted usage or private roads.. Activate to open step list"
content-desc="Bicycling mode: 3 hr 21"   (mode chips: Driving 1 hr 15 / Transit — / Walking 12 hr / Bicycling 3 hr 21)
```
Tapping the summary expanded the stop list (image `t1b-expand-stops.png`):

```text
7925 SW Fanno Creek Dr, Portland, OR 97224                                  (origin)
Waverly Landing Condominium, 525 SE Marion St, Portland, OR 97202           (waypoint 1 = 45.4612,-122.6602)
11600 Block SE Foster, Portland, OR 97266                                   (waypoint 2 = 45.4765,-122.5450)
7925 SW Fanno Creek Dr, Portland, OR 97224                                  (destination = origin)
"Add stop"
"Total trip: 3 hr 21 min"  "(35 mi)"
```
**Navigation was not running**: the only foreground service was
`…gmm.util.systemhealth.impl.SystemHealthService`, there was no navigation notification, and the UI was a
route sheet with a **Start navigation** button. This matches the documented rule ("origin … not close to the
user's current location … the map launches a route preview").

### t2 — identical URL without `dir_action=navigate`

Everything on screen was the same **except the button**:

```text
resource-id=...start_button  content-desc="Preview"   text="Preview"
```
So on this build `dir_action=navigate` changes exactly one thing at handoff time: it turns the
**Preview** button into a **Start navigation** button. It does **not** by itself start navigating while the
user is away from the route origin.

### t3 — same URL, GPS now AT the route origin → navigation starts

`adb emu geo fix -122.7588 45.4186`, then URL A again. Maps responded with the first-run navigation consent
screens (`navigation_welcome_title_text` = *"Welcome to Google Maps Navigation"*, then
*"How navigation data makes Maps better"*), i.e. **it had entered navigation mode**. After dismissing both,
the live guidance UI was present:

```text
step_instruction_container       content-desc="Head toward Southwest Fanno Creek Drive"
top_cue_text                     text="toward  SW Fanno Creek Dr"
next_step_instruction_container  content-desc="Then Turn right onto Southwest Fanno Creek Drive"
navigation_time_remaining_label  text="1 hr 1 min"   content-desc="Time until arrival is 1 hr 1 min"
                                 text="11 mi  •  1:47 PM"
speedometer                      content-desc="Current speed -- mile per hour"
                                 content-desc="Audio level selector: unmuted"
                                 content-desc="Close navigation" / content-desc="Route preview"
```
The navigation foreground notification was present at the same moment:

```text
NotificationRecord(pkg=com.google.android.apps.maps id=1 channel=1_foreground_1 category=navigation
                   groupKey=navigation_status_notification_group color=0xff188038 actions=1)
  android.title   = "0 ft"
  android.text    = "toward SW Fanno Creek Dr"
  android.subText = "1 hr 1 min · 11 mi · 1:56 PM ETA"
  action          = "Exit navigation"
```
Opening the navigation overflow menu (tap the bottom bar) showed:

```text
"Search along route" / "Remove next stop" / "Directions" / "Show satellite map" / "Settings"
```
**"Remove next stop"** only exists when a further stop is queued → the waypoints are inside the active
navigation session. The 11 mi / 1 hr 1 min figure is the **first leg** (A→B); the whole trip is 35 mi / 3 hr 21 min.

### t4 / t5 — does it walk the whole round trip? (GPS teleported to each waypoint)

With guidance running, `adb emu geo fix` was moved to waypoint B (`-122.6602 45.4612`). After ~20 s:

```text
text="Arriving at"        text="Waverly Landing Condominium"
textbox text="Continue to 11600 Block SE Foster (37 min)"     buttons: "End trip"   "Continue"
```
Tapping **Continue** switched to leg 2 and real guidance continued:

```text
step_instruction_container       content-desc="Exit the parking lot toward Southeast Marion Street"
next_step_instruction_container  content-desc="Then Turn left onto Southeast Marion Street"
navigation_time_remaining_label  text="37 min"    text="6.4 mi  •  1:24 PM"
```
Moving the GPS to waypoint C (`-122.5450 45.4765`) produced:

```text
text="Arriving at"   text="11600 Block SE Foster"
textbox text="Continue to 7925 SW Fanno Creek Dr (1 hr 42 min)"   buttons: "End trip"  "Continue"
```
i.e. Maps offered to continue **back to the origin** — the round trip closes. 1 h 1 min + 37 min + 1 h 42 min
= 3 h 20 min ≈ the 3 h 21 min total the preview promised.

> Note: the per-leg times shown during navigation (1 h 1 min, 37 min) are **time to the next stop**, not to
> the final destination. Plan UI copy accordingly.

### t6 — GPS 4 km from the origin

URL A again. Maps opened a **"Route preview" screen** (`title` = *"Route preview"*, `nav_button` =
"Navigate up", step pager `content-desc` = "Show previous" / "Show next", step text
*"Head toward SW Fanno Creek Dr"*). The accessibility tree contained **no** start/navigate control
(`start_button` was absent). This is a preview, not guidance.

*Honest uncertainty:* in t1 (GPS ~1000 km away) Maps showed the **directions sheet with a "Start navigation"
button**, while in t6 (GPS ~4 km away, and immediately after a navigation session had been closed) it showed
this separate **Route preview** screen. Both are previews. Which of the two UIs appears, and why, was not
isolated — distance, or leftover navigation state, is the likely factor. The pixel histogram of t6b's action band (§4.1) shows a full-width pale-blue band and **no** filled
blue button, which supports the reading that t6b is not the same screen as the t1/t2 route sheet.

### t7 / t8 — how many waypoints does the Android app keep? (open question #2 of the handoff research)

9-waypoint URL (286 chars, `waypoints=45.4612,-122.6602|45.4765,-122.5450|45.5231,-122.6765|45.5626,-122.6500|45.5170,-122.7300|45.4800,-122.7100|45.4520,-122.7200|45.4350,-122.6900|45.4250,-122.6700`):

```text
[251,262] text="9 stops"
"Total trip: 6 hr 9 min"   "(60 mi)"
content-desc="Bicycling mode: 6 hr"
```
Expanding + scrolling the stop list showed individual rows (partially visible in the first dump:
*7925 SW Fanno Creek Dr* / *Waverly Landing Condominium* / *11600 Block SE Foster* / *Carl Giavanti Consulting*
/ *1478 NE Killingsworth St # B*; after a scroll: *5140 SW Barnes Rd* / *3150 SW Bertha Blvd* /
*4136 SW Huber St* / *1055 SW Englewood Dr, Lake Oswego* / *610 E Ave, Lake Oswego*).

A **10-waypoint** URL produced **the same** *"9 stops"* and **the same** *"Total trip: 6 hr 9 min (60 mi)"* →
the 10th waypoint was **silently dropped**. Conclusion: the **Android app honours at most 9 waypoints**
(the URL-doc limit), i.e. **not** the 3-waypoint "mobile browser" limit. Also note the route sheet's
"N stops" label counts **waypoints**, not total stops (2 waypoints → "2 stops").

*Honest uncertainty:* the individual waypoint rows carry letter badges (A, B, C, …) that are drawn offset from
their row in the dump, so counting rows by letter is unreliable; the *"9 stops"* label and the identical totals
for the 9- and 10-waypoint URLs are the load-bearing evidence.

### t9 — the legacy `google.navigation:` intent

```text
adb shell am start -a android.intent.action.NAVIGATION -d 'google.navigation:q=45.4765,-122.5450&mode=b'
  -> Starting: Intent { act=android.intent.action.NAVIGATION dat=google.navigation: }
  -> Error: Activity not started, unable to resolve Intent { act=android.intent.action.NAVIGATION ... }

adb shell cmd package query-activities --brief -a android.intent.action.NAVIGATION -d 'google.navigation:q=45.4765,-122.5450&mode=b'
  -> No activities found
adb shell cmd package query-activities --brief -a android.intent.action.VIEW -d 'google.navigation:q=45.4765,-122.5450&mode=b'
  -> 1 activities found: com.google.android.apps.maps/com.google.android.maps.MapsActivity
```
**`android.intent.action.NAVIGATION` is not handled by Maps 11.71.0300 at all** — only `ACTION_VIEW` with the
`google.navigation:` URI works. Via `ACTION_VIEW` navigation started **immediately** (no preview, no Start
button):

```text
step_instruction_container       content-desc="Head toward Southwest 121st Avenue"
navigation_time_remaining_label  text="1 hr 35 min"    text="17 mi  •  2:27 PM"
speedometer                      content-desc="Current speed 0 miles per hour"
```
Repeating with `mode=w` (walking) gave **5 hr 35 min / 15 mi** (2.7 mph) versus **1 hr 35 min / 17 mi**
(10.7 mph) for `mode=b`. So `mode=b` is honoured as a bicycle route, and this intent always routes **from the
current location** with **no waypoints** — exactly as the prior doc research said.

### 3.10 Post-consent control matrix (repeat runs) — the trigger rule, and two honest surprises

t1/t2 were run **before Maps had ever entered navigation** (the navigation consent dialogs first appeared in
t3). To remove that state dependency the four combinations were re-run afterwards, with the location fix
settled for ~6 s before firing and Maps force-stopped between runs:

| Run | URL | GPS vs route origin | Observed result |
|---|---|---|---|
| **X1a** | A (`dir_action=navigate`) | **= origin** | **turn-by-turn starts immediately** (banner "Head toward Southwest Fanno Creek Drive", "1 hr 1 min", ETA), nav notification `1 hr 1 min · 11 mi · … ETA` |
| **R1, R1b, X1b** | A (`dir_action=navigate`) | far (Mountain View / 4 km) | **"Route preview" screen** (`title` = "Route preview", step pager "Show previous"/"Show next"). Stable for 45 s — it does **not** drift into navigation. **No start control** was found: no `start_button` in the accessibility tree, and no saturated blue button in the footer pixels |
| **R3, R3b** | B (no `dir_action`) | **= origin** | directions sheet: **"2 stops"**, bicycling card, mode chip "Bicycling mode: 3 hr 21", button **`start_button` = "Start navigation"** |
| **R2, R2b** | B (no `dir_action`) | far | same directions sheet, same "2 stops", button **`start_button` = "Preview"` |
| **R3c, R4** | B, then **tapped** "Start navigation" | = origin | **turn-by-turn starts**: banner + "1 hr 1 min" + "11 mi · ETA" — identical first leg to the `dir_action=navigate` handoff, and the sheet it came from said "2 stops" |

### 3.11 The shape the app will actually use — `origin` omitted (155 chars)

```text
https://www.google.com/maps/dir/?api=1&destination=45.4186,-122.7588&waypoints=45.4612,-122.6602|45.4765,-122.5450&travelmode=bicycling&dir_action=navigate
```
Fired with the device at the start point. Result (run `N1`, `N2`):

```text
step_instruction_container      content-desc="Head toward Southwest Fanno Creek Drive"
next_step_instruction_container content-desc="Then Turn right onto Southwest Fanno Creek Drive"
navigation_time_remaining_label text="1 hr 1 min"   text="11 mi  •  2:06 PM"
nav notification                android.subText = "1 hr 1 min · 11 mi · 2:06 PM ETA"
```
→ **immediate turn-by-turn, no tap required**, from the user's live position. Then the GPS was moved to the
first waypoint:

```text
text="Arriving at"   text="Waverly Landing Condominium"
textbox text="Continue to 11600 Block SE Foster (37 min)"    buttons: "End trip"  "Continue"
```
→ the waypoints are **in the live navigation session**, so the whole multi-stop round trip is navigated
from one URL. This is the recommended handoff shape: omit `origin`, set `destination` to the loop's start
point, put the loop's shape points in `waypoints` (≤ 9), and add `travelmode=bicycling&dir_action=navigate`.

So the trigger rule on this build is:

- **URL origin ≈ current location** → Maps is willing to navigate; `dir_action=navigate` starts guidance with
  no further interaction, without it the user presses once ("Start navigation").
- **URL origin known to be far from the current location** → **preview only**. With `dir_action=navigate` you
  get the "Route preview" screen; without it you get the route sheet labelled "Preview".

**Surprise 1 — the preview UI is state dependent.** In the pre-consent state, the *same* far-origin
`dir_action=navigate` URL produced the **directions sheet labelled "Start navigation"** (t1) instead of the
"Route preview" screen (R1/R1b/X1b). Both are previews, but they are different screens, so the exact preview
UI depends on Maps' own state, not only on the URL.

**Surprise 2 — one unexplained observation.** In one run (t12) the far-origin `dir_action=navigate` URL
started **live navigation of a 789 mi / 2 d 21 h bicycle route** from Mountain View to the Portland loop
(banner "Head west", "2 d 21 hr", "789 mi", matching navigation notification). This could **not** be
reproduced in three deliberate repeats (R1, R1b, X1b all gave the "Route preview" screen). The one
difference was that Maps had been actively navigating a moment earlier in that run; the hypothesis that a
recently-killed navigation session makes the next handoff auto-navigate is **untested**. Reported here rather
than hidden: the far-origin case is *usually* a preview, but it is not 100 % deterministic.

---

## 4. What the screenshots show — and what was NOT visually verified

**Honest statement.** The agent that ran this test **cannot see images**: `attach_image` refused with
*"deepseek-v4-flash does not support vision"*. A visual review by a vision-capable helper was attempted and
**withdrawn**, so **no human- or model-read description of the screenshots exists**. Every observation in this
file therefore rests on three **machine-read** channels, which are objective and reproducible:

1. `adb shell uiautomator dump` — the app's real accessibility tree: button labels, `resource-id`s,
   `content-desc`, stop rows, ETA text (all quoted strings in §3).
2. `adb shell dumpsys notification` — presence and content of Maps' navigation notification.
3. `adb shell dumpsys window` / `dumpsys activity` — which app has focus, which services run.

The screenshots are saved so that **you** can look at them; §4.2 lists what to check. Claims in this file
that would need eyes are explicitly marked `[not visually verified]`.

### 4.1 Objective pixel checks the agent could make (no vision required)

Colour histogram of the bottom band of the screen (`y = 2114…2337`, i.e. where the primary action button or
the navigation ETA bar sits), computed with PIL:

| Screenshot | Google-blue pixels | pale blue (232,240,254) | white | what the UI dump reports for the same screen |
|---|---|---|---|---|
| `t1-url-nav-far.png` | **8.8 %** | 0.1 % | 88 % | route sheet, button `start_button` = **"Start navigation"** |
| `t2-url-no-dir-action.png` | **10.9 %** | 0.1 % | 86 % | route sheet, button = **"Preview"** |
| `t6b-route-preview-settled.png` | 0 % | **39.9 %** | 50 % | "Route preview" screen, **no** `start_button` in the tree |
| `t3c-after-dismiss-dialog.png` | 0 % | 0 % | 88 % | live guidance: white ETA bar, no action button |
| `t9b-view-google-navigation-uri.png` | 0 % | 0 % | 87 % | live guidance: white ETA bar |
| `t3-url-nav-locATorigin.png` | 0 % | 0 % | 0 % | dimmed background behind the navigation welcome dialog |
| `t4-nav-after-move-to-B.png`, `t5-nav-after-move-to-C.png` | 0 % | 0 % | ~87 % | "Arriving at …" + "Continue to …" / "End trip" |

Read across: the two **preview** screens carry a filled blue button in that band; the **navigation** screens
carry a white ETA bar and no blue button; the "Route preview" screen is a third, different layout. This is a
pixel-level corroboration of the accessibility text and it needed no vision.

### 4.2 `[not visually verified]` — what you must confirm with your own eyes

The screenshots are the raw record; the following were **not** visually confirmed and should be checked by
whoever runs §7. Filenames below are in `docs/research/raw/device-test/`:

| Screenshot | What to look for |
|---|---|
| `t1-url-nav-far.png`, `t2-url-no-dir-action.png` | Is a route line drawn over the map, are stop pins/letters visible, and does the bottom-left button read "Start navigation" (t1) vs "Preview" (t2)? Is the bicycle chip the selected one? |
| `t1b-expand-stops.png` | The expanded A–D stop list and "Total trip: 3 hr 21 min (35 mi)" |
| `t3-url-nav-locATorigin.png` | The "Welcome to Google Maps Navigation" dialog |
| `t3c-after-dismiss-dialog.png`, `t3d-…`, `t3e-…`, `t3f-…` | The live turn-by-turn banner: turn arrow, "Head toward SW Fanno Creek Dr", 1 hr 1 min / 11 mi, speedometer, "Close navigation" |
| `t4-nav-after-move-to-B.png` | "Arriving at Waverly Landing Condominium" + the "Continue to 11600 Block SE Foster (37 min)" panel with "End trip"/"Continue" |
| `t5-nav-after-move-to-C.png` | "Arriving at 11600 Block SE Foster" + "Continue to 7925 SW Fanno Creek Dr (1 hr 42 min)" — the round trip closing |
| `t6b-route-preview-settled.png` | The "Route preview" screen, and **whether it has any start control at all** (see §8) |
| `t7b-expanded-9waypoints.png`, `t8b-expanded-10waypoints.png` | The 9-row stop list and the identical "Total trip: 6 hr 9 min (60 mi)" |
| `t9b-view-google-navigation-uri.png` | Immediate guidance from the legacy `google.navigation:` URI |
| `t11b-nav-menu-9wp.png` | The navigation overflow menu containing "Remove next stop" |
| `N2-no-origin-arrive-at-B.png` | The no-`origin` URL arriving at the first waypoint with the continue prompt |

---

## 5. What this establishes (findings)

1. **Multi-stop bicycle navigation via a Maps URL works.** With the device at the route origin, URL A
   (`waypoints` + `travelmode=bicycling` + `dir_action=navigate`, origin = destination) put Google Maps into
   **live turn-by-turn guidance** whose session contained the waypoints as stops ("Remove next stop" in the
   navigation menu; "Continue to <next stop>" at each stop; the last leg returns to the origin).
2. **`dir_action=navigate` is honoured only as far as the documented rule allows.** It flips the route-sheet
   button from **Preview** to **Start navigation**, and it starts guidance *immediately* only when the route
   origin is the user's current location. Otherwise the user gets a preview (route sheet, or a dedicated
   "Route preview" screen) and must tap once.
3. **The waypoints are not dropped** when combined with `dir_action=navigate` — the previously undocumented
   combination behaved correctly on this build, in both the preview and the live navigation session.
4. **`travelmode=bicycling` is honoured.** The route sheet labels the route *bicycling* with bicycle
   ascent/descent detail and a bicycle mode chip; the same origin→waypoint leg that Maps called "bicycling …
   1 hour 1 minute … 11 mi" was the leg it then navigated; speeds implied by the ETAs are bicycle speeds
   (10.7 mph), and the `mode=b` / `mode=w` A/B on the legacy intent gave 1 h 35 m vs 5 h 35 m for the same
   destination.
5. **Waypoint ceiling on the Android app: 9.** A 10-waypoint URL was silently reduced to 9 (identical
   "9 stops" and identical "Total trip"). The mobile-browser limit of 3 does **not** apply.
6. **No Google account was needed.** The emulator had zero accounts (`dumpsys account` → `Accounts: 0`) and
   navigation still started; only the first-run navigation consent dialogs were shown.
7. **ETA semantics during navigation are per stop.** The bottom bar showed 1 h 1 min / 11 mi for leg 1 and
   37 min / 6.4 mi for leg 2 — time to the **next stop**, not the whole 35 mi round trip.
8. **`android.intent.action.NAVIGATION` is dead on this build** (unresolvable, "No activities found").
   The legacy URI only works through `ACTION_VIEW`, and then it always routes from the current location to a
   single destination with no waypoints.
9. The UI dump plus the navigation notification identify the state of every run unambiguously: a route sheet
   with a `start_button` (labelled "Start navigation" or "Preview"), a "Route preview" screen, or a live
   guidance banner with `navigation_time_remaining_label` + speedometer + a `category=navigation`
   notification. The screenshots record the same states but were **not** read by anything (§4).

10. **The recommended handoff shape works end to end.** Omitting `origin` and passing `destination` = the loop
    start + `waypoints` + `travelmode=bicycling&dir_action=navigate` (155 chars) started turn-by-turn
    immediately from the user's live position, and at the first waypoint Maps offered
    *"Continue to 11600 Block SE Foster (37 min)"* — the whole multi-stop round trip runs as **one navigation
    session started from one URL with no taps** (§3.11).

## 6. Verdict and fallback implications

**VERDICT: `NAVIGATES_MULTISTOP`** — Google Maps (11.71.0300, Android 14, Google APIs image) starts real
turn-by-turn bicycle navigation for a multi-stop round trip handed off as a Maps URL, and keeps the stops.
Precondition: **the route origin must be at (or close to) the device's current location**; when the origin is
known to be far away Maps shows a preview instead (§3.10). The legacy `google.navigation:` intent can never
be multi-stop.

Trigger rule, as measured (not as documented):

| URL | GPS vs `origin` | What happens |
|---|---|---|
| `...&dir_action=navigate` | at origin | immediate turn-by-turn, waypoints queued as stops |
| `...&dir_action=navigate` | far (known) | "Route preview" screen — preview only |
| without `dir_action` | at origin | directions sheet, button **Start navigation** → one tap → turn-by-turn with stops |
| without `dir_action` | far (known) | directions sheet, button **Preview** |

So **omit `origin` (or set it to the user's current position) if you want guidance to start by itself**;
specify a distant `origin` only when you deliberately want a confirmation step.

Consequences for the app (D1 in `docs/DECISIONS.md`):

- **One-shot handoff is viable.** Build the URL with `origin` = the user's current position (or omit
  `origin`), `destination` = the same start point to close the loop, and the trail's shape points in
  `waypoints` (≤ 9). Expect Maps to open straight into turn-by-turn with the stops queued.
- **If you set `origin` to somewhere the user is not standing, expect a preview**, not navigation. That is a
  feature if you want the user to confirm first — but then the copy must tell them to press something, and on
  this build the far-origin + `dir_action=navigate` combination lands on a "Route preview" screen whose start
  affordance could not even be found in the accessibility tree (§8).
- **`dir_action=navigate` is worth keeping, for the at-origin case:** at the origin it removes the extra tap
  (guidance starts by itself), whereas without it the user always has to press "Start navigation". It does
  **not** override the far-origin rule.
- **The staged `google.navigation:` fallback shrank in value.** Use `ACTION_VIEW` (not
  `ACTION_NAVIGATION`, which does not resolve), accept that it is one destination at a time, always from the
  current location, and that it starts guidance instantly with no chance to preview.
- **Geometry is still Google's, not yours.** Nothing here changes the earlier conclusion that Maps
  re-computes the route through the waypoints; your bike-stress geometry is advisory shaping only, and the
  "Total trip" numbers Maps shows are its own.
- **Do not show Maps' per-leg time as the trip time.** If the app promises "35 mi / 3 h 21 m", that number
  must come from your own engine; Maps will display 1 h 1 min for the first leg.
- **Budget ≤ 9 waypoints.** Our two-waypoint URL is 180 chars and the nine-waypoint URL 305 chars — the
  2 048-char ceiling is not a constraint.
- **No API key, no account, no billing** for this path.

## 7. Confirm on your own phone (recommended — do this before shipping)

The emulator's Maps is from July 2024 (11.71.0300). Current phone builds may differ, and this is a 10-minute
check. Plug the phone in with USB debugging on and paste these commands.

```bash
# 0) phone reachable, and which Maps version you have
adb devices
adb shell dumpsys package com.google.android.apps.maps | grep versionName

# 1) your live position -> use it as the round-trip origin
adb shell dumpsys location | grep -m3 'last location'

# 2a) THE MAIN TEST -- the shape the app will use: no origin (Maps uses your live position),
#     destination = loop start, two trail waypoints, bicycling, dir_action=navigate.
#     Expect: Maps opens straight into turn-by-turn with the stops queued.
adb shell am start -a android.intent.action.VIEW -d 'https://www.google.com/maps/dir/?api=1&destination=START&waypoints=WP1|WP2&travelmode=bicycling&dir_action=navigate'
#     real example used in this test (155 chars):
#     adb shell am start -a android.intent.action.VIEW -d 'https://www.google.com/maps/dir/?api=1&destination=45.4186,-122.7588&waypoints=45.4612,-122.6602|45.4765,-122.5450&travelmode=bicycling&dir_action=navigate'

# 2b) same round trip WITH an explicit origin equal to START (expect the same: turn-by-turn)
adb shell am start -a android.intent.action.VIEW -d 'https://www.google.com/maps/dir/?api=1&origin=START&destination=START&waypoints=WP1|WP2&travelmode=bicycling&dir_action=navigate'

# 2c) the preview case: origin set to a point a few km away from you (expect a preview, not guidance)
adb shell am start -a android.intent.action.VIEW -d 'https://www.google.com/maps/dir/?api=1&origin=FARPOINT&destination=START&waypoints=WP1|WP2&travelmode=bicycling&dir_action=navigate'

# 3) what did Maps do?  Two checks:
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '>' '>\n' | grep -E 'start_button|Preview|Start|navigation_time_remaining|step_instruction'
adb exec-out screencap -p > maps-handoff.png          # look at this file

# 4) proof that real guidance is running (not a preview): a navigation notification with an ETA
adb shell dumpsys notification --noredact | grep -A20 'pkg=com.google.android.apps.maps' | grep -E 'android.title|android.text|android.subText|category='

# 5) proof that the waypoints became navigation stops: tap the bottom bar in Maps, then
#    dump again and look for the menu item "Remove next stop"
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | grep -o 'Remove next stop'

# 6) the "user is not at the trailhead" case: same URL but with origin set to a point a few km away.
#    Expect a preview with a "Start navigation" button instead of instant guidance.

# 7) the legacy intent, for comparison (note: ACTION_NAVIGATION does NOT resolve -- use ACTION_VIEW)
adb shell am start -a android.intent.action.NAVIGATION -d 'google.navigation:q=45.4765,-122.5450&mode=b'   # expect failure
adb shell am start -a android.intent.action.VIEW       -d 'google.navigation:q=45.4765,-122.5450&mode=b'   # starts instantly
adb shell dumpsys package com.google.android.apps.maps >/dev/null   # no-op; ignore
```

What to look for, in one line each: does a turn arrow + instruction + ETA appear (guidance), or a
route sheet with a Start/Preview button (preview)? Does the overflow menu offer "Remove next stop"
(stops queued)? Does any sheet say *bicycling* / show a bicycle chip? **Keep the URL in single quotes** —
without them the phone's shell eats the `&` and Maps only sees the first parameter.

## 8. Uncertainty and limits of this test (be honest about these)

- **Old Maps build.** 11.71.0300 (2024-07-11) came with the emulator image; a 2026 phone build may behave
  differently, especially around the "Route preview" screen. Re-run §7 on the real phone.
- **No Google account** was signed in. That did not block navigation here, but signed-in phones may show
  different onboarding (e.g. no "Welcome to Google Maps Navigation" dialog).
- **Faked GPS.** Position was injected with `adb emu geo fix` (GPS + fused providers reported it), not a real
  fix, and it was teleported rather than moved continuously. Arrival detection ("Arriving at …", "Continue
  to …") therefore triggered through a teleport; a real ride would reach the same state more slowly.
- **One device, one image, one Maps version.** No second configuration was tested.
- **The t1 vs t6 difference in the preview UI is not explained** (route sheet with "Start navigation" versus a
  dedicated "Route preview" screen with no start control). Both are previews; only the "does it navigate"
  question is settled.
- **Stop-counting subtlety.** The waypoint rows carry letter badges that the accessibility dump places offset
  from their row; the "9 stops" label and the total-trip figures are the reliable evidence for the 9-waypoint
  ceiling, not a manual count of letters.
- **Nothing has visually inspected the screenshots.** The agent's model has no vision (`attach_image`
  refused) and an attempt to delegate a visual review was withdrawn. The load-bearing evidence is entirely
  machine-read (`uiautomator` tree, `dumpsys notification`, PIL colour histograms); §4.2 lists the visual
  points still open — notably whether the "Route preview" screen shows any Start control, and the on-screen
  bicycle-mode indication during guidance.
- **The trigger rule is state dependent, and not 100 % reproducible.** The same far-origin
  `dir_action=navigate` URL gave the directions sheet in the pre-consent state (t1) and the "Route preview"
  screen afterwards (R1/R1b/X1b); and one run (t12) started navigation for a far origin and could not be
  reproduced (§3.10). Real phones carry even more state (sign-in, location history, recents).
- **"Close to the current location" was never quantified.** Distances tested were ≈ 0 m, ≈ 4 km and ≈ 1 270 km
  (789 mi). Where the boundary sits, and whether it is absolute or relative, is untested.
- **The "Route preview" screen's start affordance is unresolved** on this build: no `start_button` in the
  accessibility tree and no saturated blue button in the footer pixels. It may be a custom-drawn control or
  the screen may require another gesture. If the app's flow lands users on that screen (far origin +
  `dir_action=navigate`), test what the user must press.
- **Google applies no bicycle distance cap here**: it happily started navigating a 789 mi / 2 d 21 h bicycle
  route. Do not rely on Maps to refuse an absurd route.
- **Not tested:** `waypoint_place_ids`, `avoid`, transit/walking multi-stop handoff, Very-long bicycle routes
  (> 60 mi was fine), behavior with an active navigation already running, and whether a *signed-in* user gets
  a different auto-start rule.

## 9. Evidence index

All under `docs/research/raw/device-test/`:

| File | What it is |
|---|---|
| `01-maps-first-launch.png`, `02-after-skip-tap.png` | Maps first run (sign-in sheet) and the main screen after SKIP |
| `t1-url-nav-far.{png,xml,logcat}`, `t1b-expand-stops.png` | 2-waypoint URL with `dir_action=navigate`, GPS 1000 km away: route sheet, "Start navigation" button, expanded stop list A–D, "Total trip: 3 hr 21 min (35 mi)" |
| `t2-url-no-dir-action.{png,xml,logcat}` | Same URL without `dir_action`: same sheet, button says "Preview" |
| `t3-url-nav-locATorigin.{png,xml,logcat}`, `t3b-…`, `t3c-after-dismiss-dialog.png`, `t3d-…`, `t3e-…`, `t3f-…` | GPS moved to the origin: navigation consent dialogs, then live guidance; overflow menu with "Remove next stop" |
| `t4-nav-after-move-to-B.{png,xml}`, `t4b-after-continue.png` | "Arriving at Waverly Landing Condominium" + "Continue to 11600 Block SE Foster (37 min)"; leg 2 guidance |
| `t5-nav-after-move-to-C.{png,xml}` | "Arriving at 11600 Block SE Foster" + "Continue to 7925 SW Fanno Creek Dr (1 hr 42 min)" — the round trip closes |
| `t6-url-nav-offorigin-preview.{png,xml,logcat}`, `t6b-route-preview-settled.{png,xml}` | GPS 4 km from origin: "Route preview" screen |
| `t7-url-9waypoints-offorigin.*`, `t7b-expanded-9waypoints.png` | 9-waypoint URL: "9 stops", "Total trip: 6 hr 9 min (60 mi)" |
| `t8-url-10waypoints-offorigin.*`, `t8b-expanded-10waypoints.png` | 10-waypoint URL: identical "9 stops" and totals → 10th dropped |
| `t9-legacy-navigation-intent.*`, `t9a-navigation-action.*`, `t9b-view-google-navigation-uri.*` | `ACTION_NAVIGATION` fails to resolve; `ACTION_VIEW` + `google.navigation:` starts guidance instantly |
| `t10-view-gnav-mode-w.*` | Same legacy intent with `mode=w`: 5 h 35 m vs 1 h 35 m → `mode=b` really is bicycling |
| `t11-url-9wp-nav-at-origin.*`, `t11b-nav-menu-9wp.png` | 9-waypoint URL **with** `dir_action=navigate` while at the origin: live navigation, stop queued |
| `t12-preview-state-recheck.{png,xml}` | The single non-reproducible observation: far origin, `dir_action=navigate`, navigation started anyway (789 mi) |
| `N1-no-origin-nav.*`, `N1b-nav-menu-no-origin.*`, `N2-no-origin-arrive-at-B.{png,xml}` | The recommended URL shape (`origin` omitted, 155 chars): immediate navigation, then "Continue to 11600 Block SE Foster (37 min)" at the first waypoint |
| `R1-far-URLnav*`, `R1b-*`, `R2-far-URLnoNav*`, `R2b-*`, `R3-origin-URLnoNav*`, `R3b-*`, `R3c-tap-start-button.*`, `R4-nav-menu-after-tapped-start.xml`, `X1a-*`, `X1b-*` | The post-consent control matrix of §3.10: far/at-origin × with/without `dir_action`, plus the "tap Start" path |
| *(no visual-review file exists — the screenshots have not been described by anyone; see §4)* | |
