# SECURITY REVIEW — Bike Trail Planner

Scope: the whole system as designed — the Android app, the self-hosted Valhalla server on this Mac,
the Tailscale path between them, the Google Maps key, the repo, and the delegated agent workflow.
Method: live checks on this machine plus a read of the design (`docs/SPEC.md`, `docs/DECISIONS.md`).
Date: 2026-09-15.

## 1. Assets worth protecting

| Asset | Why it matters |
|---|---|
| Home location | The app's whole input. Address + coordinates appear in this repo. |
| The Mac as a server | It runs an unauthenticated routing API reachable over the tailnet. |
| Google Maps API key and its billing account | Extractable from an APK; misuse costs money. (Not used — D17.) |
| DeepSeek API key (added 2026-09-16, D27) | The rider's own bearer credential, typed into Settings. It is stored only in Android-Keystore-encrypted preferences, and if it leaks it bills the rider's account and can be used by anyone who has it. |
| Ride history on the phone | Where you ride, when, and from where. |
| The repo itself | Contains the home address and screenshots. |

## 2. Trust boundaries

```
  PHONE (trusted)                     Mac + Valhalla (your hardware)
      |  Tailscale / WireGuard              ^
      |  encrypted, tailnet-only  ----------+
      |
  LAN 192.168.x.x (UNTRUSTED) -- must never reach :8002
  Public internet (UNTRUSTED) -- must never reach :8002
  Google (trusted with waypoint coordinates only)
  Public Valhalla server (third party; only used for the D12 fallback)
  DeepSeek api.deepseek.com (third party, D27; receives trail and street NAMES and the target
      mileage, NEVER coordinates; one request per NEW plan; reached over TLS)
```

## 3. Findings

| ID | Severity | Status | Finding |
|---|---|---|---|
| SEC-1 | **High** | **Fixed** (instructed) | Valhalla has **no authentication**. Publishing it as `-p 8002:8002` binds `0.0.0.0`, so anything on the home LAN, guest Wi-Fi, or the tailnet could route through it or exhaust the Mac's CPU. Fix: bind **loopback only** (`-p 127.0.0.1:8002:8002`) and publish to the phone through Tailscale Serve. |
| SEC-2 | **High** | **Verified clean** | No public exposure today: `tailscale serve status` and `tailscale funnel status` both report *"No serve config"*. Funnel must stay off forever — it would publish the router to the internet. Recorded as an explicit rule for the server worker. |
| SEC-3 | Medium | **Fixed** | Android blocks cleartext HTTP by default, and the lazy fix (`usesCleartextTraffic="true"`) weakens the entire app. **OUTCOME (2026-09-15): HTTPS was NOT available** — the tailnet has HTTPS certificates disabled, so `tailscale serve --https=443` failed with *"Serve is not enabled on your tailnet"*. The server worker took the safe path: loopback bind plus **Tailscale Serve on HTTP:80, tailnet-only**. Consequence: the Android app must carry a `network_security_config` cleartext exception **scoped to `admins-mac-mini.taile1279d.ts.net` only** — never `usesCleartextTraffic="true"` globally. The hop is still encrypted by WireGuard, so this is acceptable; enabling HTTPS certificates in the Tailscale admin console removes the need for the exception entirely and is recommended. |
| SEC-4 | Medium | **MOOT (D17)** — the Google Maps SDK is not used, so there is no key to restrict. Kept on record in case the decision is reversed. | Google Maps SDK key. Required: restrict it to the app's **package name + debug and release SHA-1**; enable **only** Maps SDK for Android (not Directions or Routes); keep it in `local.properties` (now gitignored); never paste it into source, docs, or a screenshot. Add a **quota and budget alert** on the Cloud project. An unrestricted key is readable by anyone who unpacks the APK. |
| SEC-5 | Medium | **Fixed** | The home **street address** had leaked into the generated map (`edgewood-trail-network.svg` title), `docs/SPEC.md`, and the raw OSM dumps. Street address scrubbed from the SVG; `.gitignore` now excludes `docs/research/raw/` and `docs/data/raw/`. `SPEC.md` still carries the address and coordinates — acceptable while this stays a local repo, but it must not be published. |
| SEC-6 | Medium | **Open — user action** | Tailnet ACL. Restrict inbound access to the Mac's port 443 (and 8002) to **your phone's node only**. Do not share this Mac's node into anyone else's tailnet: every shared node is a route to your router. |
| SEC-7 | Medium | **Design decision needed** | The D12 fallback sends your **home coordinates and route request to a third-party volunteer server** (`valhalla1.openstreetmap.de`) whenever the Mac is unreachable. That is a real privacy leak, not a theoretical one. Recommendation: make the fallback **opt-in per ride** with a visible note, or drop D12 and fail loudly instead. |
| SEC-8 | Low | **Fixed** (instructed) | Container hygiene: no `--privileged`, `--network host`, or `--pid host`; mount only `valhalla/custom_files`, never a home directory; `--memory 6g --cpus 4`; keep request caps modest (300 km, 25 waypoints) so one unauthenticated request cannot pin the Mac. |
| SEC-9 | Low | **Fixed** (instructed) | Supply chain: verify the Washington extract against Geofabrik's published `.md5` before building tiles, and pull the image **only** from the official `ghcr.io/valhalla/...` repository — pinned by tag or digest, not floating `latest`. |
| SEC-10 | Low | **Open — app work** | On-device data. Room will store ride history and the home location. Set `android:allowBackup="false"` (or a backup-rules file excluding the database) so ride history is not uploaded to Google's cloud backup, and never log coordinates in release builds. |
| SEC-11 | Low | **Open — app work** | Intent and URL hygiene. The app builds a Maps URL from OpenStreetMap-derived strings. Use **only numeric coordinates** in `waypoints`, URL-encode everything, enforce the 2048-character ceiling, and validate before firing the Intent, so a trail name containing `&`, `%`, or spaces cannot corrupt the hand-off. |
| SEC-15 | Medium | **Fixed** | Server hardening verified live after bring-up: `-p 127.0.0.1:8002:8002` only (`lsof` shows just `127.0.0.1:8002` LISTEN), `--memory 6g --cpus 4`, a single `custom_files` mount, no `--privileged`/host networking, request caps lowered to 300 km / 25 locations and proven to reject larger requests, Washington extract MD5 verified against Geofabrik, and Funnel confirmed absent. |
| SEC-16 | Medium | **MOOT (D17)** — no Maps key means no key-related cleartext concern. The Valhalla cleartext exception (SEC-3) still applies. | Android cleartext exception, scoped to the single ts.net host (see SEC-3). Must be written as a `network_security_config.xml` with a domain entry, and the config must be applied only to that host. |
| SEC-12 | Low | **Verified clean** | Secret scan of the whole project (binaries excluded) for `AIza…` API keys, bearer tokens, `tskey-` auth keys, passwords, `secret`, and private keys: **zero hits**. No credentials in the tree. |
| SEC-13 | Info | **Open — housekeeping** | `docs/research/raw/` is 55 MB and contains large third-party JS bundles, a PDF, and **79 emulator screenshots that may show personal notifications or account details on screen**. Delete before sharing anything. Now gitignored. |
| SEC-17 | **Medium** | **Open — app work (found in field testing 2026-09-15)** | **Intent interception on the Maps hand-off.** Firing the route as a generic `ACTION_VIEW` web intent delivered it to whatever app was top-most (observed first reaching Tailscale, then the user's Novel TTS app -- not Google Maps). Any installed app that registers for `google.com/maps` URLs could receive the user's home coordinates and route. Fix: build the hand-off intent with `setPackage("com.google.android.apps.maps")` (and keep the https URL, so the user still sees the address bar target). Also verify with `dumpsys window | grep mCurrentFocus` rather than trusting a UI dump. |
| SEC-18 | **Medium** | **Fixed in code; device verification pending (2026-09-16, D27)** | **The app now holds a bearer credential the rider typed.** Until 2026-09-16 the app deliberately held no keys (D17); D27 added a user-supplied DeepSeek key, so two risks exist: the key leaking off the device, and local trail/street names going to a third party. **Mitigations implemented:** the key lives ONLY in `EncryptedSharedPreferences` (file `secure_settings`, `MasterKey` `AES256_GCM`, `AES256_SIV` names / `AES256_GCM` values) with **no plaintext fallback** (if the keystore cannot be opened the AI section says so and stores nothing); it is never logged, never printed, never put in `BuildConfig` and never committed; the UI reads it back masked (last 4 characters); `android:allowBackup="false"` already keeps it out of cloud backup; the request contains trail and street **names** and the target mileage but **no coordinates** (`docs/AI-KEY.md` section 3); one request per new plan, 20 s timeout, 700 output-token cap, one retry, and a one-tap **Clear**. **Residual risk:** DeepSeek's own retention policy governs what is sent, and the key is only as safe as the phone's lockscreen. Not yet verified on the device (the acceptance gate installs the build and reads the masked key back). || SEC-18 | **Medium** | **Fixed in code; device verification pending (2026-09-16, D27)** | **The app now holds a bearer credential the rider typed.** Until 2026-09-16 the app deliberately held no keys (D17); D27 added a user-supplied DeepSeek key, so two risks exist: the key leaking off the device, and local trail/street names going to a third party. **Mitigations implemented:** the key lives ONLY in `EncryptedSharedPreferences` (file `secure_settings`, `MasterKey` `AES256_GCM`, `AES256_SIV` names / `AES256_GCM` values) with **no plaintext fallback** (if the keystore cannot be opened the AI section says so and stores nothing); it is never logged, never printed, never put in `BuildConfig` and never committed; the UI reads it back masked (last 4 characters); `android:allowBackup="false"` already keeps it out of cloud backup; the request contains trail and street **names** and the target mileage but **no coordinates** (`docs/AI-KEY.md` section 3); one request per new plan, 20 s timeout, 700 output-token cap, one retry, and a one-tap **Clear**. **Residual risk:** DeepSeek's own retention policy governs what is sent, and the key is only as safe as the phone's lockscreen. Not yet verified on the device (the acceptance gate installs the build and reads the masked key back). |
| SEC-19 | **Medium** | **Open — needs device verification (2026-09-16, D23a)** | **Overpass is a new third-party dependency that receives the rider's position (D23 option (a)).** The "find trails near here" button sends a query containing the live coordinates (as an `around:` radius) to a volunteer-run Overpass instance, so a third party learns roughly where the rider is, and the app's discovered-trail geometry is an ODbL derivative of their data. This is the same class of leak as SEC-7 (public Valhalla fallback) and D21 (third-party map tiles); it is accepted for this personal app, and it is **only** used when the rider presses the button or the plan needs trails the bundle does not cover — the bundled regional set (D23a) needs no network at all. **Mitigations implemented:** an explicit `User-Agent` is sent (overpass-api.de answers 406 without one); every response's `osm3s.timestamp_osm_base` is checked, and an answer older than 48 h, older than the newest stamp any mirror already served, missing, or unparseable is REFUSED and the next mirror is tried; a mirror answering HTTP 200 with an empty relation list is treated as second-class rather than as "no trails here"; the raw answer is cached locally by ~1 km bucket with a 30-day TTL (so a repeat plan asks nothing); no coordinates are logged, and the cache stores the server's answer, not the rider's request. **Residual risk:** the mirrors see the request IP and the query coordinates, and their availability and data freshness are outside the rider's control. |
| SEC-20 | **Medium** | **Accepted + implemented (2026-09-16, D36); verified on the phone** | **Notification access is a broad grant.** To tell when Google Maps navigation stops, the app's `NotificationListenerService` must hold `BIND_NOTIFICATION_LISTENER_SERVICE`, which lets it read **every notification on the phone** — Android has no narrower grant. The app reads only `com.google.android.apps.maps` notifications with `category=navigation` and never logs or stores their content, but the *capability* is broad. **Mitigations:** the grant is optional and hand-granted; without it the app falls back to the D35 return-to-start nudge and says so (Settings → "End of ride"); the service matches one package and one category; nothing is persisted except the armed/seen booleans and timestamps (`bike-trail-planner-ride-watch`, no coordinates, no notification content); `android:allowBackup="false"` keeps it out of cloud backup; and the feature is documented so the rider can revoke it at any time. The other new permission, **POST_NOTIFICATIONS**, is an ordinary runtime prompt. |
| SEC-14 | Low | **Open — app work** | Do not trust the server blindly. The app should sanity-check returned geometry (distance and shape plausible for the request) before showing it as safe — a corrupted or hostile response should degrade to "unverified", never to "safe". |

## 4. Verified clean

- No credentials, API keys, tokens, or private keys anywhere in the project.
- No Tailscale Serve or Funnel configuration: the Mac exposes nothing publicly today.
- Valhalla is not yet running, so there is at the moment **no listening socket** to attack.
- `adb`/emulator testing stayed on the local machine.

## 5. Process finding

A delegated research child spawned its own sub-agents on models the user forbids (claude-haiku and
claude-opus) without being told not to. Fixed with a global rule and a prompt note, and the offending
child was deleted. The general lesson: when delegating, state the model constraint **and** verify each
child's `rlm-subagent.json` before reporting work as complete.

## 6. Residual risk after the fixes

The design is sound for a personal tool: the router is reachable only over the tailnet, over TLS, from
loopback, with resource caps. What remains is mostly the user's own to close:

1. Create the Google key **with** package + SHA-1 restrictions and a budget alert (SEC-4).
2. Add the tailnet ACL locking the Mac's port to your phone (SEC-6).
3. Decide whether the public-router fallback is worth leaking your home coordinates (SEC-7).
4. Keep the repo local; never push it with the address and screenshots inside (SEC-5, SEC-13).
5. Consider full-disk encryption for the external volume if it is not already encrypted — the repo
   and the OSM extract live there.
