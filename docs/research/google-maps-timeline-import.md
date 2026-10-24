# Google Maps / Velotric finished-trip import — is there an API, and what is actually possible?

**Question.** Can an app list a rider's recent trips from Google Maps (or Velotric) and fetch their
data, so a finished ride can be imported as a saved trip? Researched 2026-09-26 against primary
sources (Google Maps Platform docs, Google's own blog/support, Android developer docs). Secondary
sources are marked as such. Local Velotric measurements are in `docs/VELOTRIC-LINK.md`.

**Bottom line. No API exists for either app, and for Google Timeline it cannot exist any more.** A
user's Timeline is stored **on the phone only, end-to-end encrypted**; Google's servers hold no
readable copy, so no server API could return it even if one were offered. The only supported way to
get the data out is a **manual file export from the phone's own settings**. Google Fit (a historical
"read my activities" door) was shut down 2025-06-30 in favour of Health Connect, which can read
exercise routes but only from apps that *wrote* them there — Google Maps does not, and Velotric
exposes no export at all.

---

## 1. Google Maps Platform has no trips / Timeline / Location History API

The official catalogue of every Google Maps Platform API —
<https://developers.google.com/maps/apis-by-platform> — lists, under **Web Service APIs**:

Address Validation, Aerial View, Air Quality, Elevation, Geocoding, Geolocation, Maps Datasets,
Map Tiles, Places, Places Aggregate, Pollen, Roads, Routes, Route Optimization, Solar, Time Zone,
Weather.

There is **no Timeline, Location History, or user-trips product**. The two near misses are:

- **Roads API** — "Snap-to-road functionality to accurately trace GPS breadcrumbs." It takes
  breadcrumbs **you already hold**; it does not return a user's history. Same source page.
- **Routes API** — "The next generation … version of the Directions API and Distance Matrix API."
  It computes a **new** route; it does not read past trips. Same source page.

The Maps documentation index (<https://developers.google.com/maps/documentation>) confirms the same
product set; nothing there reads a user's location history.

Google's support forum says it directly:

> "Unfortunately, Google does not offer a public Google Maps Timeline API."
> — <https://support.google.com/maps/thread/340941030>

> "No, there is no API. A user can download their own data, either a single day at a time via the
> online Timeline website."
> — <https://support.google.com/maps/thread/40110147>

## 2. Timeline is on-device and end-to-end encrypted, so no server API is even possible

Google's own announcement, "Updates to Location History and new controls coming soon to Maps":

> "**Coming soon: Your Timeline saved on your device** … soon your Timeline will be saved right on
> your device — giving you even more control over your data."
> — <https://blog.google/products-and-platforms/products/maps/updates-to-location-history-and-new-controls-coming-soon-to-maps>

> "If you're getting a new phone … you can always choose to back up your data to the cloud …
> We'll automatically encrypt your backed-up data so no one can read it, **including Google**."
> — same page

The support doc states the consequence for the web/PC:

> "Since the data shown on your Google Maps Timeline comes directly from your device, Timeline is not
> available for Maps on your computer."
> — <https://support.google.com/maps/answer/6258979>

The rollout completed through 2024-12 (the December 2024 cut-over is widely reported; e.g.
<https://thehackernews.com/2024/06/google-maps-timeline-data-to-be-stored.html> — secondary).
The practical reading: **the data physically is not on Google's servers in readable form**, so a
Maps Platform endpoint could not serve it.

## 3. The only supported extraction is a manual per-device export

Android (Google's own support answer, and multiple corroborating reports):

> "Your phone can export a JSON file with the Timeline data it has… Android: Go to device Settings >
> Location > Location Services > Timeline > 'Export Timeline data' button."
> — <https://support.google.com/maps/thread/264641290>

iOS equivalent: Google Maps → account icon → **Settings → Personal Content → Export Timeline data**.

**Format.** The phone export is a JSON with three sections: `semanticSegments`, `rawSignals`,
`userLocationProfile`. `semanticSegments` carry activity start/end coordinates and a path (the
phone-takeout format), and `rawSignals` carry raw position readings. Field-level detail is from a
secondary write-up, <https://dawarich.app/blog/whats-inside-your-google-timeline-export>
(**secondary — verify against a real export before writing a parser**).

**Google Takeout is no longer a dependable path.** Google's support threads show users now receiving
only `Settings.json` / encrypted-backup stubs from Takeout under "Location History (Timeline)", with
the rest greyed out:
<https://support.google.com/maps/thread/324612962>,
<https://support.google.com/maps/thread/347468981>. The per-device export above is the one Google's
own experts point users to.

## 4. Google Fit is gone; Health Connect is the replacement, and it does not cover Maps

Google's developer blog:

> "Google Fit APIs, including the REST API, will remain available until **June 30, 2025** … new
> sign-ups for the Google Fit APIs will no longer be accepted."
> — <https://android-developers.googleblog.com/2024/05/evolving-health-on-android-migrating-from-google-fit-apis-to-android-health.html>

The replacement, **Health Connect**, does expose recorded exercise routes:

- `android.permission.health.READ_EXERCISE_ROUTES` (plus an exercise permission; each route is tied
  to an `ExerciseSession`).
- Route reads from another app's data may return `ExerciseRouteResult.ConsentRequired`, and the docs
  recommend reading on deliberate user interaction.
- Source: <https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes>

**But**: Health Connect only holds sessions that some app **wrote** to it. **Google Maps does not
write Timeline activities to Health Connect.** So Health Connect is a useful door for Fitbit/Samsung
Health/Wear-OS-recorded rides, and not for Google Maps.

## 5. Velotric exposes nothing (measured locally)

`docs/VELOTRIC-LINK.md` §2 records five doors measured shut on the device: no ride deep link, no
exported ride component, no notification while recording, no usable cloud ride API, no bike-side
recording. The vendor manual's only "share" of a finished ride is a **picture** (route image + stats):
<https://velotric.zendesk.com/hc/en-us/articles/23522731151003-Velotric-App-Manual> (secondary, but
consistent with the on-device measurement). There is **no GPX, no file export, and no API**.

## 6. What this leaves

| Path | Google Maps | Velotric | Automatic? |
|---|---|---|---|
| Maps Platform API to list trips | ✗ does not exist | ✗ does not exist | — |
| Phone Timeline JSON export + import | ✓ manual export | ✗ | ✗ (manual) |
| Share a Maps directions link | ✓ one route | ✗ | ✗ |
| Health Connect `ExerciseRoute` | ✗ Maps writes nothing | ✗ (unmeasured; no evidence it writes) | ✓ if data present |
| GPX/KML file import | ✗ Maps won't produce one | ✗ | ✗ |
| **App records the ride itself** | n/a — independent | n/a | ✓ (foreground service) |

**Project decision (2026-09-26).** Because no external source can be queried, the app will **record
the ride itself** with a foreground location service rather than import after the fact: start at the
Google Maps / Velotric hand-off **or** from a manual "Record a ride" action; stop when Google Maps
navigation ends (the signal `nav/RideWatchService` already detects) or on an explicit Stop; then
convert the track to a graded saved trip via Valhalla `trace_attributes`. Google Maps Timeline import
is **out of scope**. See the session decisions in the feature design.
