# UI SPEC — Bike Trail Planner (Material 3, Google Maps look)

Decided 2026-09-15 (user: "tighten up the UI, it's ugly and full of debug info right now. Use material
theme and make it look like Google Map"). The app must read like Google Maps, not like a build log.

## 1. Language rules (the biggest problem today)

The current screens leak internal vocabulary. Fix it:

| Never show | Show instead |
|---|---|
| `Round-trip miles (D2)` | **How far do you want to ride?** |
| `graded 50% / ungraded 50%` | a **segmented bar** plus `50% measured` |
| `A 0.83 mi | B 0.00 mi | C 1.34 mi | D 0.00 mi | E 0.00 mi | ungraded 2.17 mi` | the same bar, with a `Details` expander listing each grade in words |
| `114th Avenue East: 2.09 mi (no speed limit, tertiary) traffic: 2337 vpd (1996) STALE` | **114th Avenue East** · 2.1 mi · *2,300 vehicles/day, 1996 data* |
| `Measured traffic volume (SPEC 6b)` | *(delete -- fold into the street row)* |
| `vpd`, `STALE`, `tertiary`, `service_other` | `vehicles/day`, `older data`, `country road`, `service road` |
| *"not verified"* used for anything but the RIDE's verdict | **one word, one meaning.** `not verified` = the D10 verdict on the ride (too much of it is unmeasured to vouch for). A live-discovered trail with no closure or surface check is *`unchecked`*, defined once under the trail list. A via-point chosen by road class rather than by a traffic count says why: *`18th St E (residential street, no posted limit)`* |
| `Capped (D9): the ride is 5.16, not the 5.50 mi target` | **Shorter than you asked:** 5.2 mi instead of 5.5 mi — the trail ends here |
| `corridor exhausted: ... no documented link continues the corridor` | *(delete; it is a reason for us, not a sentence for a rider)* |
| `home 47.240371,-122.262019` | *(delete)* |
| `Public router fallback (D12: automatic)` | *(delete from the main screen; see Settings)* |
| `Status: not checked yet` next to a raw URL | a small dot: grey = unknown, green = connected, amber = using the public router |
| `© OpenStreetMap contributors (ODbL) | traffic counts: WSDOT / Pierce County` | keep the text (it is legally required) but move it to an **About** row at the bottom of Settings, 12sp, secondary colour |

Never remove the legal attribution; relocate it.

## 2. Look and feel

**ALWAYS LIGHT (D19, 2026-09-15).** The user rides in daylight only and has asked for a light theme
permanently. Do NOT follow the system dark setting -- no `isSystemInDarkTheme()` branching. On the
user's own phone the device night mode is currently ON, so a build that follows the system renders
dark and is wrong. The dark tokens below are retained only for documentation; they must not be wired to
the runtime theme. Also set `android:forceDarkAllowed="false"` so the platform cannot auto-darken
views, and use a light splash/status-bar treatment so nothing flashes dark at launch.

Material 3, Google Maps palette, light only.

| Token | Light | Dark |
|---|---|---|
| primary | `#1A73E8` | `#8AB4F8` |
| surface | `#FFFFFF` | `#202124` |
| surfaceVariant (sheet, chips) | `#F1F3F4` | `#303134` |
| onSurface | `#202124` | `#E8EAED` |
| onSurfaceVariant | `#5F6368` | `#9AA0A6` |
| outline | `#DADCE0` | `#5F6368` |

Grade colours (same in both themes, used for the bar and the chips):
A trail `#188038` · B protected `#34A853` · C quiet road `#F9AB00` · D caution `#EA8600`
· E avoid `#D93025` · not measured `#9AA0A6`.

Typography: Roboto. Screen title `headlineSmall` (24sp). Big stat `displaySmall` (36sp, medium).
Body `bodyMedium` (14sp). Chips and captions `labelMedium` (12sp).

Shape: pills for chips (`CircleShape`), bottom sheet with `RoundedCornerShape(topStart=28.dp,
topEnd=28.dp)`, cards `RoundedCornerShape(16.dp)`. Elevation: sheet 3.dp, top pill 2.dp, FAB 6.dp.

Spacing: 16.dp horizontal page padding, 12.dp between blocks, 8.dp inside a chip. Never cram.

## 3. Screen 1 — Plan

Map-first, like Google Maps:

1. **Top pill**, 48.dp tall, full width minus 16.dp margins, `CircleShape`, white/surface, 2.dp
   elevation: leading menu icon, text *"Plan a ride from home"*, trailing a small avatar-less
   location icon. Not a text field -- tapping opens Settings.
2. **A real map fills the screen** behind everything (D21): MapLibre GL with the OpenFreeMap **positron**
   style -- light grey, the closest free equivalent to the Google Maps look, no API key. Before a plan
   exists, centre on home at about z13 with the home pin. After planning, draw the route in grade
   colours, add pins for home, the trail entry and the turnaround, and fit the camera to the route
   bounds with padding for the top pill and bottom sheet. Attribution ("© OpenStreetMap contributors",
   OpenFreeMap) stays in the map's corner control AND in Settings > About.
3. **Milestone chips row** (wrapping, not scrolling), **two presets: `10 mi` and `20 mi`** (D56, user
   2026-10-11: *"remove any button for round trip distance over 20 mi, or under 10 mi, keep custom"* -
   narrowing D20's `5` `10` `15` `20` `25` `30` `35` `40` `45` `50`). Pill-shaped, selected chip =
   primary container, unselected = surfaceVariant. A trailing `Custom` chip opens a small stepper for
   any value from **1 to 200 mi**, snapping to 1 mi; it reads `Custom` until a value outside the two
   presets is chosen, then names it (`Custom: 15 mi`), so a remembered 15 does not leave the row with
   nothing selected. Above the row, one small grey line: *"Round-trip distance"*. Remember the last
   value used and preselect it on the next launch (fall back to `10 mi` on first run).
   The chips WRAP rather than scroll: with twelve choices a single scrolling row hid eight of them on a
   phone (D20), and a rider could not see the option they wanted at all.
4. **Bottom sheet**, dragging handle (32x4.dp, outline colour, centred), containing:
   - Title: **Bike Trail Planner** (or the planned trail name once planned)
   - A row of two stat blocks: `Distance` / `Trail` with big numbers.
   - A full-width **FilledButton**: `Plan ride` (48.dp tall, `RoundedCornerShape(24.dp)`).
   - A `TextButton` row: `Use my location` · `Refresh trails`.
   - **The handle resizes the panel (D53).** The map is the point of the app, and the panel grows to
     nearly the whole frame once there is a plan, so the rider can push the panel down to look at the
     roads. Dragging the handle moves it continuously between **30%**, **62%** and **100%** of the
     space below the chrome; releasing snaps to the nearest of those three presets, and a single tap
     on the handle steps to the next one. The choice is remembered across launches
     (docs/DECISIONS.md D53). The panel never collapses below 30%: the handle and the title always
     stay on screen, so there is always a way back to the plan.
5. Loading state (D39): a linear progress bar directly under the sheet's handle, plus a real elapsed
   clock — *"Finding a safe way to the trail on Sumner Link… 29 s"* — and a **Stop** button that
   cancels the plan. Named trail = the rider's tap started this. The clock is not decoration: a plan is
   dozens of sequential routing calls (minutes on the fallback router), and an indeterminate spinner
   for minutes is indistinguishable from a hang. No raw counters, no request URLs.
6. **"Trails near you" (D38):** a card in the sheet listing every reachable trail, **closest first**,
   one row per trail: the name, *"3.2 mi of trail"*, and *"4.1 away"* pinned on the right so the
   distance is never the part that ellipsizes. Tapping a row plans on that trail. A row whose trail
   came from live discovery carries *"· unchecked"*, and the card says once what that means:
   *"An unchecked trail has had no closure or surface check yet."*

## 4. Screen 2 — Result (the confirmation)

Bottom sheet grows to ~60% height, panel-style:

1. **Header:** trail name in `titleLarge`, and under it one human sentence:
   *"5.2 mi round trip · 1.7 mi on trail"*.
2. **Segmented grade bar:** a single 8.dp-tall rounded row whose segments are proportional to
   distance and coloured by grade. Under it, one caption line:
   *"50% of this ride is measured — see what we know"*.
   **Above the bar, the "Choose your ride" chip row (D37)** appears when the plan found more than one
   ride for this distance: one chip per option, leading with *"1.2 mi round trip · 3.1 mi on trail"*,
   and — for a ride whose D10 verdict is UNVERIFIED (D41) — the words *"not verified"* go **first**,
   because a narrow chip ellipsizes from the end and the caveat must survive. An unselected
   UNVERIFIED chip is outlined as well as labelled. Tapping a chip re-draws the map, the bar, the
   details and both hand-off buttons from data the plan already has: no re-plan, no new call.
   **Way back (D31, D47):** one line says whether the rider comes back a different way:
   *"You come back a different way: 50% of the roads on the way back are new."* The share is
   measured from the OSM road ways both legs actually use, so it is a number, not a claim. When the
   search ran and found nothing, the line says so plainly instead of staying silent:
   *"No different safe way back was found, so this one comes back the same way."* The line appears
   only when the preference is on and the search ran; a plan made with `prefer_loop` off says
   nothing, because nothing was looked for.
   If the plan routed around a road it will not ride, a line names each via-point and why it was
   chosen (D13/D40): *"Way around: A St SE (1,200 vpd, 2019)"* for a measured count,
   *"Way around: 18th St E (residential street, no posted limit)"* for a street picked by road class.
3. **Only three things are pinned: the handle, the header (trail name + distance) and the hand-off
   buttons at the bottom. Everything else scrolls**, including the D27 AI notes. Measured 2026-09-26
   (D48): when the AI card sat ABOVE the scrolling part, its four rows grew the fixed rows past the
   sheet's height, the scrolling part got zero height, and the grade bar, the measured caption and
   the `Details` expander became unreachable with nothing to scroll. A row added to the fixed part
   can therefore silently remove the rider's safety summary, and one more line pushed
   `Start navigating` off the sheet. `tools/acceptance.py` requires `Details` and
   `Start navigating` on the result screen for exactly this reason.
4. **Details expander** (collapsed by default, `TextButton` labelled `Details`):
   - `Trail` A · 0.8 mi — *"separated path"*
   - `Quiet road` C · 1.3 mi
   - `Caution` D · 0.0 mi
   - `Not measured` · 2.2 mi, then each street as `114th Avenue East · 2.1 mi · 2,300 vehicles/day (1996)`
   - A single info line explaining that unmeasured means we could not verify it, not that it is safe.
5. **Primary action:** FilledButton `Confirm on map` (opens Google Maps with the stops and a Start
   button). **Secondary:** outlined button `Start navigating` (jumps straight into turn-by-turn).
   **Velotric assist (D35, option A):** when the Velotric app is installed, an outlined button
   `Start Velotric ride` opens the Velotric app and nothing else — the rider taps START RIDING there.
   It is hidden when the app is absent. Option A removes the memory problem, not the taps.
6. **On-return arrival bar (D35, piece 3a):** the fallback reminder, used only when the D36
   notification watcher is unavailable (Notification access not granted). When the app is brought back
   to the front and the last ride's good fix is back within ~250 m of where it started (at least
   ~10 min after the hand-off), a bar appears above every screen: *"You're back at your start."* with
   `Open Velotric` and `Dismiss`. It never stops a ride by itself. The **"End of ride"** Settings row
   (D36) shows which of the two is active.
7. **End-of-navigation prompt (D36):** when Google Maps navigation stops, a high-importance
   notification **"Ride finished?"** — *"Tap to open Velotric and stop your ride."* — with a
   `Dismiss` action. Tapping it opens the Velotric app. It also fires as a safety net, on opening the
   app, when a Velotric ride was started through the app but navigation was never seen.
7. If nothing compliant was found: a red-tinted card, **"Not safe as planned - your call"** (D42), with
   the one-line reason and the named streets, plus a `Try a safer detour` action. **The hand-off
   buttons below stay live**: the ride is drawn and can be sent to Google Maps. Advise, never block -
   the rider decides, and the words "No safe route today" are reserved for the case where no route
   could be computed at all.
   **`Try a safer detour` always answers (D43).** It re-plans once with a wider detour budget and then
   says which happened: *"We searched further out and found a safer way."* or *"We searched further out
   for a safer way and could not find one. Try a different trail, or ride this one and take care."*
   A tap that changes nothing on screen reads as a broken button, so silence is not an option.

## 5. Screen 3 — Settings (holds everything technical)

Move all of the current noise here, one row each, `ListItem` style: server address + connection dot,
public-router fallback toggle (default on per D12), home address, re-check trail data, About with the
attribution and the data sources (`OpenStreetMap (ODbL)`, `WSDOT`, `Pierce County`, `PSRC`).

**"End of ride" (D36):** one row that says whether Notification access is granted (with a button that
opens the system screen) and whether a ride is currently armed, so the rider can see why the
end-of-navigation reminder did or did not come.

## 6. Motion

Sheet transitions 200 ms ease-in-out. Grade bar segments animate their widths over 300 ms when a plan
arrives. Chips animate selection with a 100 ms scale to 0.96. Nothing bounces; Google is calm.

## 7. Verification without sight

Neither the agent nor the implementing model can see the screen. So verify structurally, then let the
user judge:

- Dump the UI tree (`adb shell uiautomator dump`) and assert **no banned strings** appear:
  `D2`, `D9`, `D12`, `SPEC`, `vpd`, `STALE`, `tertiary`, `service_other`, `corridor exhausted`,
  `graded `, `ungraded `, and no raw latitude/longitude on the main screens.
- Assert the required strings DO appear: the trail name, a human distance sentence, `Confirm on map`,
  `Details`, and the attribution inside Settings.
- Take a screenshot and check the dominant colours programmatically (white/`#F1F3F4` surfaces,
  `#1A73E8` primary, the six grade colours present in the bar only when non-zero).
- Then ask the user to look at the phone and report what to change. That is the only real aesthetic
  gate, and it is theirs, not ours.

## 3.6 Screen — Ride (the rider's own recorded rides)

D59/D60. Its own screen, reached from a **`Ride`** button in the plan screen's top pill, next to
`Settings` (the pill itself still opens Settings). Built like the plan screen - a pill, a **resizable
sheet** (D53) and the map in the middle - so the map is never covered:

1. **Pill:** *"Your rides"* with `Back`.
2. **The map is visible on entry**, whichever height the plan panel was left at: the sheet is clamped to
   half height until the rider adjusts it here. The camera fits a shape into the part of the map the
   sheet leaves uncovered (D53/D64) - with a fixed margin the 19.5 km ride fitted into a space that was
   mostly behind the sheet, and the rider could see a strip of it. A ride selected in the list is drawn
   on it as **one line**, start and end marked - *one* colour, not grade colours, because grades are stored per traced
   street and not per GPS point, and colouring metres that were never assessed would be a claim the data
   cannot support.
3. **Zoom:** `+` / `-` buttons on the visible map area (top-end - the sheet covers the middle). The map
   also pinches; the buttons are for a bar mount or an emulator.
4. **Sheet, pinned under the handle:** the answer to the last tap.
   - A **point**: `Point 82 · 0.3 mi in`, the street it sits on if the trace matched one, and its postal
     address.
   - A **street**: the name, the grade, the length and the posted speed.
   - Plus *"About N m from your tap"*, because the answer is approximate and says so.
5. **While recording, the map draws the ride as it happens** (D65): the accepted fixes are drawn as
   one growing line and the camera moves only when the newest fix leaves the visible area - never a
   re-fit per fix, which would jerk the map once a second. The line keeps the ride colour, not grade
   colours, for the reason in 2 above.
6. **Sheet, scrolling:** `Record a ride` (and `Simulate` in debug builds), then the list. Tapping a card
   selects it - the map draws it and the tap becomes live. Each card carries the date, the segmented
   grade bar, distance, time, GPS gaps, and the honest *"N% of this ride had no speed information"* line.
   `Delete` removes the ride **and its rotation-history row** (D58).

**Where the Google Maps stops come from (D64).** Not an even stride through the GPS points: the ride
records the **roads** it was on, and the stops are its **key turns** - the junctions where the road
changes, scored by how far the recorded geometry actually turns there, whether the street name changes,
whether the ride enters or leaves a separated path, and (on a ride that comes back) the far point.
Anything within 120 m of a better corner is dropped, so a corner ridden twice is one stop. The ride
screen says which turns were named and how many were left over: *"names its turns: 18th Street East →
114th Avenue East, … ; and 4 more"*, and on a loop it says Google chooses the way back.

**The address is third-party.** Valhalla is not a geocoder, so a postal address comes from
OpenStreetMap's Nominatim, gated behind the rider's existing `allowPublicFallback` switch: whoever has
not agreed to send their coordinates to a public router is not silently sending them to a public
geocoder either. With it off, the screen says so and everything else - the point, the distance into the
ride, the street name - still works, from the ride's own data and with no network at all.
