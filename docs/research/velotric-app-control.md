# Velotric e-bike app — can a third-party Android app start/stop a ride recording?

Research date: **2026-09-16**. Subject: the Velotric companion app for Velotric electric bikes
(Discover / Nomad / T1 / Summit / Fold / Breeze / Go / Triker families).
Question: **can another Android app programmatically START and STOP a ride recording inside it?**

Answer in one line: **No.** The Velotric app has no public control surface of any kind, and the ride recording
lives entirely inside its own foreground UI process (it is then uploaded to Velotric's own cloud under the
rider's account). The only programmatic route is UI automation of the Velotric app itself (accessibility /
Shizuku-ADB), which is fragile and carries an explicit Play-policy declaration burden.

Evidence classes used below, and how much each is worth:

| Class | Source | Strength |
|---|---|---|
| OFFICIAL VENDOR | Velotric Zendesk help centre, velotricbike.com, Google Play listing, Apple App Store listing | strongest for *intent* and for *what the product is meant to do* |
| MEASURED ARTIFACT | `AndroidManifest.xml` and `libapp.so` of the official app **4.5.5 (versionCode 217)** decoded with `androguard`; `adb shell dumpsys package` on a Samsung SM-S918U | strongest for *what the app can actually do* |
| PUBLIC BACKEND ARTIFACT | `https://app.velotricbike.com/api/v2/api-docs` (public Swagger 2.0 document, 196 paths) | strong for "server-side ride records exist", silent about consumer access |
| THIRD PARTY / OPEN SOURCE | GitHub projects | strong for the bike's BLE protocol, weak for vendor intent |
| USER CLAIM | Play Store / App Store reviews | weak; quoted only as such |

Every claim carries an exact URL or an exact reproducible command. Items that could not be verified are marked
`UNCERTAIN`; reasoned-but-unproven statements are marked `SPECULATION`.

**Method, and its limits.** Everything below comes from direct fetches of primary sources (Play/App Store
listings, the vendor's Zendesk API, the vendor's own web properties, the app's decoded manifest and Dart
snapshot, public registries, public policy documents) plus read-only HTTP probes of the vendor's own public
endpoints. **No credential was ever sent, guessed or replayed, and no login was attempted.**
General web search was largely unusable on 2026-09-16 from this host: DuckDuckGo served a CAPTCHA, Google
blocked, Brave returned 429, Mojeek returned nothing, and Bing degenerated into brand-marketing results, while
Reddit returned HTTP 403 to every attempt. **Community/forum evidence is therefore "inconclusive by tooling",
not evidence of absence** — for the community-integration questions (§2.5) I relied on the integration
platforms' own search endpoints instead, which are the authoritative directories anyway.

Raw evidence kept under `docs/research/raw/velotric/` (`parent-thread.md`, `apk-and-manifest.md`,
`cloud-api-developer.md`, `automation-integrations.md`, `recorder-location.md`, plus raw HTML/JSON captures).

---

## 0. Verdict in one table

| # | Question | Verdict | Key evidence |
|---|---|---|---|
| 1 | Official Android package id and publisher | `com.mddoscar.velotricbike`; publisher **Velopower, Inc.**; Play listing "Velotric" (grid subtitle "Velopower") | §1 |
| 2 | Documented/discoverable deep links, URL schemes, App Links | **NONE for the app.** Vendor domain publishes an *empty* `assetlinks.json` (`[]`) and an *empty* `apple-app-site-association`; the APK declares exactly one custom scheme, `hwpps://com.mddoscar.velotricbike`, which belongs to a Huawei **ads** SDK | §2.1 |
| 3 | Exported activities/services/receivers/providers usable for ride control | **NONE.** 9 exported components; no ride/record action anywhere; the recording foreground service is `exported="false"` | §2.2 |
| 4 | Public SDK, AAR, Maven artifact, developer docs | **NONE FOUND.** No Velotric artifact in Maven Central (`numFound: 0`) / npm (`total: 0`) / PyPI (404) / GitHub; no developer portal; `developer.`/`open.`/`api.`/`cloud.`/`iot.` subdomains are NXDOMAIN (§2.3). The **only** vendor-published programmatic surface is commerce-only (`/api/ucp/mcp`, 13 tools, zero bike/ride tools) | §2.3, §2.4.3 |
| 5 | REST / cloud API for ride start/stop | **NO public or documented API.** (a) A public Swagger document for the vendor's *internal operations backend* exists and does contain per-ride records (`rideTrace`, `rideStartTime`, `imei`), but every path is `/admin/…`, `/sys/…` or factory-sync, guarded by a staff-issued `Authorization` API key. (b) The app's own API (`http://app.velotricbike.com:7002/api/…`, incl. `/api/auth/login`) is embedded in the APK, undocumented and credential-gated — in 4.5.5 the only ride routes are `/api/rides` and `/api/rides/ridingEvent`. (c) The **2022** build did contain `/api/base/rides/go`, `/api/base/rides/{}/end`, `/api/base/rides/{}/suspend`, `/api/rides/record/start…`, but its host `http://rdprod.velotric.net:7072` is **NXDOMAIN today** | §2.4, §2.4.1 |
| 6 | Tasker / MacroDroid / IFTTT / Google Home / SmartThings / Alexa | **NONE.** Not in Tasker's plugin list, 0 hits in the MacroDroid wiki, `No results found` on IFTTT | §2.5 |
| 7 | Quick-settings tile / home-screen widget / app shortcuts / Wear OS | **NONE.** `TileService`, `AppWidget`, `shortcuts` all absent from manifest and from `libapp.so`; no watch split; Play listing has zero occurrences of `Wear OS` | §2.6 |
| 8 | Third-party BLE protocol for the bike | **YES for bike control, NO for ride recording.** An independent Web-Bluetooth PWA drives the bike (service `000018e6-…`, write `000039f5-…`, notify `000039f6-…`, name prefix `Vel R01`); those UUIDs also appear in the official app. Its opcode map has no record/trip opcode | §2.7 |
| 9 | Who records the ride — phone or bike? | **The PHONE app records the ride (GPS track, saved to the account).** The bike's display keeps only a running trip counter + odometer, cleared with `Clear Trip Data` | §3 |
| 10 | Can a ride be recorded if the app is never started? | **No recoverable ride record.** No official source describes a bike-side ride history, and the display counter has no start/end timestamps and no route | §3.4 |
| 11 | Realistic fallbacks | Ranked in §4. Nothing is ship-clean; the best engineering answer is **record the ride in our own app** and treat Velotric as out of scope | §4 |
| 12 | What is *not* possible | Accessibility auto-tap (possible but policy-declared), UiAutomator/adb (not shippable), MediaSession hijack (does not work), screen-on tapping, cloud API, Tasker | §5 |

---

## 1. App identity (question 1)

| Item | Value | Source |
|---|---|---|
| Package id | **`com.mddoscar.velotricbike`** | manifest decoded from the APK: `package='com.mddoscar.velotricbike'`; Play `application/ld+json` `"url":"https://play.google.com/store/apps/details/Velotric?id=com.mddoscar.velotricbike&hl=en_US"` |
| Play listing | `https://play.google.com/store/apps/details?id=com.mddoscar.velotricbike` | fetched 2026-09-16 |
| Name shown | listing title `Velotric`; grid subtitle `Velopower` | Play listing |
| Publisher | `Velopower, Inc.` — `software.rd@velotric.bike` — `171 Heller Pl Ste C7 Bellmawr, NJ 08031-2503 United States` — `+86 186 8201 8372` | Play listing "About the developer" |
| Developer website | `https://velotric.bike/` (302 → `https://www.velotricbike.com/`) | Play "App support" |
| Privacy policy | `https://velotric.bike/policies/privacy-policy` | Play "App support" |
| Support email | `software.rd@velotric.bike` | Play "App support" |
| Version / code | `android:versionName="4.5.5"`, `android:versionCode="217"`, `minSdkVersion=26`, `targetSdkVersion=35` | `androguard.core.apk.APK("/tmp/velotric_apk/base.apk")` |
| Play "Updated on" | `Jun 30, 2026` | Play listing |
| Installs / rating | `10K+ Downloads`, `2.7 star`, `88 reviews` | Play listing |
| Category | `Lifestyle` | Play listing |
| iOS twin | `https://apps.apple.com/us/app/velotric/id1626016434`, iTunes id `1626016434`, `"bundleId": "com.mddoscar.velotricbike"` (**same string as the Android package id**), `"sellerName": "Velopower Inc"`, `"version": "4.5.5"`, `"currentVersionReleaseDate": "2026-07-20T03:26:02Z"` | `https://itunes.apple.com/lookup?id=1626016434&country=us` |
| Other Velopower apps | developer page lists exactly one app | `https://play.google.com/store/apps/developer?id=Velopower` |
| App tech | Flutter (`libapp.so`), plugins `flutter_reactive_ble`, `flutter_foreground_task`, `geolocator`, `flutter_location`, `mapbox`, `firebase_messaging`, Health Connect SDK, Huawei ads/scan SDK | manifest + `strings libapp.so` |
| Permissions that matter here | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `BLUETOOTH_SCAN/CONNECT/ADVERTISE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECORD_AUDIO`, `CAMERA`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `android.permission.health.WRITE_DISTANCE`, `android.permission.health.WRITE_ACTIVE_CALORIES_BURNED` | `androguard` permission dump of the APK |
| Play Data safety (summary) | `This app may collect these data types` → `Location, Personal info, and Device or other IDs`; `Data is encrypted in transit`; `You can request that data to be deleted` | Play listing |
| Play Data safety (detail page) | `Location` → `Precise location · Optional · App functionality`; no background-location row appears anywhere on the page. The APK nevertheless declares `android.permission.ACCESS_BACKGROUND_LOCATION` | `https://play.google.com/store/apps/datasafety?id=com.mddoscar.velotricbike` |
| iOS location disclosure | `This app may use your location even when it isn't open, which can decrease device battery life.` | App Store listing |
| APK mirror metadata | APKCombo: `4.5.5`, vercode `(217)`, `Update Jun 29, 2026`, `77 MB`, `Android 8.0+`; 15 older versions listed. mi9.com: `Version 4.5.5`, `Last updated Jun 30, 2026`, version history incl. versionCodes `217/200/187/180/155/136/112/96/86/73/37/24/11/304` | `https://apkcombo.com/velotric/com.mddoscar.velotricbike/`, `https://mi9.com/package/com.mddoscar.velotricbike/` |
| Mirrors that do **not** carry it | APKMirror `No results`, Aptoide API `"not found"`, apksos 404, apkdone none; apkpure.com and apkfab.com return HTTP 403 to automated fetches (their CDN still serves the file) | see §7 |
| Cleartext traffic | `android:usesCleartextTraffic="true"`; the app's API base is `http://app.velotricbike.com:7002` (plain HTTP) | decoded manifest; `libapp.so` strings |
| Prior app generation | versionCode `304` / `2.8.3` (2022) was a **DCloud uni-app** hybrid (`io.dcloud.PandoraEntry`, app id `__UNI__A64E6DA`, production host `http://rdprod.velotric.net`) | decoded manifest of the 2.8.3 APK; bundled `manifest.json` |

The bundle id string `com.mddoscar.velotricbike` is identical on both stores, which is unusual but useful: it proves
the two apps are one product from one publisher, and it means any domain-level App Link configuration would have
to serve both platforms (it serves neither — §2.1).

**Bottom line for question 1:** package id **`com.mddoscar.velotricbike`**, published by **Velopower, Inc.**
(Velotric's operating company), listing at
`https://play.google.com/store/apps/details?id=com.mddoscar.velotricbike`.

---

## 2. Is there ANY programmatic control surface? (question 2)

### 2.1 Deep links, URL schemes, App Links — NEGATIVE

| Check | URL / artifact | Result (2026-09-16) |
|---|---|---|
| Android App Links on the vendor domain | `https://www.velotricbike.com/.well-known/assetlinks.json` | HTTP 200, `content-type: application/json`, body is exactly **`[]`** |
| same, apex host | `https://velotricbike.com/.well-known/assetlinks.json`, `https://velotric.bike/.well-known/assetlinks.json` | both **`[]`** |
| iOS Universal Links on the vendor domain | `https://www.velotricbike.com/.well-known/apple-app-site-association` | HTTP 200, body `{"applinks":{"apps":[],"details":[]},"webcredentials":{"apps":[]},"appclips":{"apps":[]}}` |
| Custom schemes in the APK | `androguard` manifest dump: `re.findall(r'android:scheme="([^"]+)"')` | exactly one scheme: **`hwpps`** |
| That scheme's owner | manifest: `com.huawei.openalliance.ad.activity.PPSLauncherActivity` with `android:scheme="hwpps"` / `android:host="com.mddoscar.velotricbike"`, filter `VIEW` + `BROWSABLE` | Huawei **advertising** SDK deep link, not a product link |
| `velotric://`, `velopower://`, `applinks:` in the app | `strings` scan of `libapp.so` + manifest | zero hits. Also zero hits for the usual deep-link plumbing `app_links`, `uni_links`, `firebase_dynamic_links`, and zero custom actions of the form `com.mddoscar.velotricbike.START_RIDE` |
| The launcher activity's intent filters | manifest: `com.example.velotric_app.MainActivity` (a `FlutterFragmentActivity`) | exactly one filter, `MAIN`+`LAUNCHER`, `exported="true"`, **no `<data>` element at all** — so it cannot be reached by a URI |
| The 2022 build (2.8.3 / versionCode 304) | decoded manifest + its 26 JS bundles | `io.dcloud.PandoraEntry` BROWSABLE filters exist but carry only SDK schemes (`unipush://io.dcloud.unipush`, from the Getui push SDK); `velotric://` appears nowhere; `uni-app://`/`uni://` come from the DCloud framework |
| App Links on other Velotric hosts | the store is Shopify; the app's own API host `app.velotricbike.com` serves the ops console (no `assetlinks.json` found for the app) | no App Link configuration exists anywhere in the vendor estate |

**Consequence:** there is no documented or discoverable deep link that opens the Velotric app *at the ride
screen*, and no App Link at all. A third-party app cannot even rely on a vendor-supported "open the app" URL.
(It can still launch the launcher activity by component name — §2.2.)

### 2.2 Exported activities / services / receivers / providers — NEGATIVE for ride control

Decoded with `androguard` from the official APK 4.5.5 (`AndroidManifest.xml` rebuilt to text, 23,993 chars).
**Complete list of components with `android:exported="true"` (9 of them — the literal string
`android:exported="true"` occurs exactly 9 times in the decoded manifest):**

| Component | Type | Why it is exported |
|---|---|---|
| `com.example.velotric_app.MainActivity` | activity | `android.intent.action.MAIN` + `android.intent.category.LAUNCHER` |
| `com.example.velotric_app.HealthConnectPolicyActivity` | activity | `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` |
| `com.example.velotric_app.ViewPermissionUsageActivity` | activity-alias | `android.intent.action.VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` |
| `io.flutter.plugins.firebase.messaging.FlutterFirebaseMessagingReceiver` | receiver | `com.google.android.c2dm.intent.RECEIVE` (FCM) |
| `com.google.firebase.iid.FirebaseInstanceIdReceiver` | receiver | `com.google.android.c2dm.intent.RECEIVE` (FCM) |
| `com.pravera.flutter_foreground_task.service.RebootReceiver` | receiver | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` |
| `androidx.health.platform.client.impl.sdkservice.HealthDataSdkService` | service | `androidx.health.platform.client.ACTION_BIND_SDK_SERVICE` (Health Connect) |
| `androidx.profileinstaller.ProfileInstallReceiver` | receiver | profile-installer actions |
| `com.huawei.openalliance.ad.activity.PPSLauncherActivity` | activity | `VIEW` + `BROWSABLE`, `hwpps://com.mddoscar.velotricbike` (ads) |

Non-exported components that matter:

| Component | Exported | Meaning |
|---|---|---|
| `com.pravera.flutter_foreground_task.service.ForegroundService` | `false` | **the recording foreground service cannot be started by another app** |
| `com.baseflow.geolocator.GeolocatorLocationService` | `false` | location service not reachable |
| `com.lyokone.location.FlutterLocationService` | `false` | location service not reachable |
| `dev.fluttercommunity.plus.share.ShareFileProvider` | `false` | the ride "Share" path is a `FileProvider`, deliberately private |
| (no `ContentProvider`, no AIDL interface for ride control) | — | there is no IPC contract at all |

The only `<queries>` entries are Health Connect and navigation/map apps:
`com.google.android.apps.healthdata`, `com.google.android.apps.maps`, `com.google.android.apps.mapslite`,
`com.autonavi.minimap`, `com.baidu.BaiduMap`, `com.waze`, `ru.yandex.yandexnavi`, `com.mapswithme.maps.pro`,
`net.osmand`, … Nothing queries a third-party bike app.

**What *is* possible:** `startActivity()` with the explicit component
`com.mddoscar.velotricbike/com.example.velotric_app.MainActivity`. That only opens the app's UI. Per the Android
docs, no `<queries>` entry is needed for that: `Because the startActivity() method doesn't require package
visibility to start another application's activity, you don't need to add a <queries> element to your app's
manifest…` (`https://developer.android.com/training/package-visibility/use-cases`, fetched 2026-09-16).

### 2.3 SDK / AAR / Maven / developer docs — NONE FOUND

| Check | URL (all 2026-09-16) | Result (verified first-hand) |
|---|---|---|
| Maven Central | `https://search.maven.org/solrsearch/select?q=velotric&rows=20&wt=json` | `"numFound": 0` — no Velotric artifact |
| npm | `https://registry.npmjs.org/-/v1/search?text=velotric` | `{"objects":[],"total":0,…}` |
| PyPI | `https://pypi.org/pypi/velotric/json` | HTTP 404 |
| GitHub repositories | `https://api.github.com/search/repositories?q=velotric` | `"total_count": 7`: `AndroidCoolMan/Study_git`, two discount-code repos, `helenayuan-rgb/velotric-Accessories`, `gabriellekoo/Velotricbike`, **`seaty6/velotric-pwa`** (the BLE project, §2.7), `velotricai/skills-install` (a vendor org repo, internal AI skills only), `Albert0211/velotric-social-monitor-agent`. None is an SDK |
| GitHub repositories (`velopower`) | `https://api.github.com/search/repositories?q=velopower` | `"total_count": 0` |
| GitHub code search | `https://api.github.com/search/code?q=velotricbike` | `401 Requires authentication` — not possible without a token, so absence there is `UNCERTAIN` |
| grep.app | `https://grep.app/api/search?q=velotricbike` | 0 hits |
| Developer portal / service hosts | `developer.velotricbike.com`, `developer.velotric.bike`, `developers.velotric.bike`, `open.velotricbike.com`, `openapi.velotricbike.com`, `api.velotricbike.com`, `api.velotric.bike`, `cloud.velotricbike.com`, `iot.velotricbike.com`, `iot.velotric.bike`, `my.velotricbike.com` | **all NXDOMAIN** (`dig +short` empty). A ~220-name subdomain sweep plus certificate-transparency enumeration (`https://crt.sh/?q=%25.velotricbike.com&output=json`) found only marketing/e-commerce SaaS names (`www`, `account`, `b2b`, `press`, `referral`, `showroom`, `sms`, `securemetrics`, …) — no API/dev host |
| Domain trap | `velopower.com` (the publisher's namesake domain) | it is a **parked domain** (`NS ns1.namefind.com`, `TXT "v=spf1 -all"`, body redirects to `/lander`), so `api.velopower.com` and friends resolve to a parking wildcard and prove nothing. The publisher's real web presence is `velotricbike.com` / `velotric.bike` |
| Play listing links | "App support" has only Website / Support email / Privacy Policy | no developer link |
| Official docs for third parties | Velotric Zendesk search for `api`, `sdk`, `developer` | no developer documentation |

### 2.4 REST / cloud API — a real backend exists, but there is no documented consumer API

A **publicly reachable Swagger 2.0 document** was found for Velotric's internal operations backend:

- `https://app.velotricbike.com/api/v2/api-docs` — 525,349 bytes, `"host": "app.velotricbike.com"`, `"basePath": "/"`, **196 paths**.
- Security: `"securityDefinitions": {"ApiKey": {"type": "apiKey", "name": "Authorization", "in": "header"}}`; every `/admin/…` operation carries `"security": [{"ApiKey": ["Full Access"]}]`.
- `https://app.velotricbike.com/` returns the admin console: page title `velotric运营后台管理系统`
  ("Velotric operations backend management system"), copyright `© 2020 cy.com`.

The backend **stores per-ride records**. Verbatim from the spec:

| Path | Summary (verbatim Chinese) |
|---|---|
| `GET /admin/vehicleridesrecord/page` | `分页查询车辆骑行记录` |
| `GET /admin/vehicleridesrecord/{id}` | `查询车辆骑行记录详情` |
| `GET /admin/vehicleridesrecord/get/all` | `查询车辆骑行总里程和总时间` |
| `GET /admin/vehiclebase/getRideEventLog` | `分页查询车辆骑行事件` |
| `GET /admin/vehiclebase/getRideSensorLog` | `查询车辆骑行数据统计` |
| `GET /admin/oms-stats-count/getRideMileageVehicles` | `查询骑行里程排名前 10 的车辆` |
| `GET /admin/mqtt-logs` | MQTT broker message logs (`MQTT 消息日志`) |

Schema `AdminVehicleRidesRecordInfoVo` fields and their verbatim descriptions:
`imei` = `imei号`; `sn` = `车架号`; `rideStartTime` = `骑行开始时间`; `rideEndTime` = `骑行结束时间`;
`rideTime` = `骑行总时长（秒）`; `mileage` = `骑行总里程`; `avgSpeed`; `maxSpeed`; `calorie`;
`carbonDioxide`; `rideTrace` = `GPS经纬度信息`; `travels` = `骑行轨迹列表` (`TravelVO` = `lat`/`lng`);
`createTime` = `上传时间`; `userAccount` = `用户标识`; `adminComFileInfosVo` = `轨迹缩略图`.

Other tags show the fleet/IoT side: `MQTT 消息日志`, `指令模板管理` (command templates, incl.
`POST /admin/command-templates/{id}/push`), `固件管理` (firmware), `产测数据同步` (factory sync:
`POST /v2/api/vehicle/batch-sync`), `车辆故障记录`, `APP 用户管理`, `APP 操作日志管理`,
`/admin/vehicle-esims*` (eSIM management). **Every path is `/admin/…`, `/sys/…`, `/files/…` or
`/v2/api/vehicle/…`.** There is no `/app/…`, no consumer login, no OAuth client, no ride-upload endpoint
documented for third parties.

The only auth-related operations are administrative (`/admin/demo-auth/page|refresh|renew|save|updateStatus`,
`/admin/system/sys-users/{userId}/api-keys`). `Authorization` is an API key with `Full Access` scope, i.e.
staff credentials. **No request was made with any credential, and none was guessed.**

#### 2.4.1 The phone app's OWN (undocumented) API — found, private, not usable

The Flutter build embeds its API base and paths verbatim. Extracted by me from `libapp.so` (4.5.5,
2026-09-16) with `strings -n 6 … | grep -oE '/api/[A-Za-z0-9/_{}.\-]{2,60}' | sort -u`:

- Bases: `http://app.velotricbike.com:7002`, `http://oms.preprod.velotric.net:7002`,
  `http://oms-test.velotric.net:7002` — **plain HTTP on port 7002**, consistent with
  `android:usesCleartextTraffic="true"` in the manifest. `app.velotricbike.com` resolves to `3.236.95.227`
  (AWS us-east-1) and is the same host that serves the staff console under a different port/path.
- **Ride routes in 4.5.5: exactly two — `/api/rides` and `/api/rides/ridingEvent`.** There is no
  `/rides/go`, `/rides/{id}/end` or `/rides/record/start…` in the current build.
- Other paths include `/api/auth/login`, `/api/auth/refresh`, `/api/self/mileage`, `/api/vehicles`,
  `/api/vehicles/binds`, `/api/vehicles/by-mac`, `/api/vehicles/by-sn`, `/api/vehicles/getStateByCoordinates`,
  `/api/fingerprints`, `/api/esims`, `/api/firmwares/latest`, `/api/firmwares/upgrades`, `/api/app-configs`,
  `/api/app-versions`, `/api/repairments`, `/api/self`, `/api/self/destroy`, `/api/self/account/reset`,
  `/api/user-profiles/detail`.
- Dart symbols that make the direction of travel unambiguous: `onClickStartRide`, `_startRideTime`,
  `uploadRideRecord`, `RideRecordRequestParamsModel`, `RideRecordCacheService`, `rideRecordDetail`,
  `CyclingRecordDetailPage`, `ridingSpeed`.

So the **phone app records the ride, caches it on the phone and uploads it** (`/api/rides`), which is exactly
consistent with the server-side `vehicleridesrecord` rows in §2.4.

**Live probe (read-only, no credentials, 2026-09-16):** `GET http://app.velotricbike.com:7002/api/rides` and
`GET http://app.velotricbike.com:7002/api/auth/login` both answer
`{"timestamp":"2026-09-17 05:59:47","status":404,"error":"Not Found","message":"","path":"/api/rides"}` — the
port serves a Spring Boot service, but the literal base+path as extracted does not resolve. The app very likely
appends a context path at runtime (`UNCERTAIN`). No credential was sent or guessed.

**The legacy build did have cloud ride start/stop.** In the 2022 uni-app build (2.8.3 / versionCode 304) the
bundled `app-service.js` contains, verbatim:
`/api/base/rides/go`, `/api/base/rides/{}/end`, `/api/base/rides/{}/suspend`,
`/api/rides/record/start/or/end/rides`, `/api/rides/record/suspend/rides`, `/api/rides/record/list`,
`/api/rides/record/page`, `/api/rides/record/get/rides/result`, `/api/rides/record/update/move`,
`/api/base/lock/open`, `/api/base/lock/openride`, `/api/base/lock/closeride`, `/api/base/get/gps/`, and the
production host **`http://rdprod.velotric.net:7072`**. I re-checked that host on 2026-09-16:
`dig +short rdprod.velotric.net` returns **nothing (NXDOMAIN)**. So the one build that demonstrably had a cloud
"start ride / end ride" call talks to a host that no longer exists, and the current build no longer contains
those routes.

Using this API from a third party is **not viable**:
1. It is undocumented and unversioned; nothing about it is a contract.
2. `/api/auth/login` + `/api/auth/refresh` means an account credential or token is mandatory.
3. In the current build there is **no start/stop route at all** — `/api/rides` is a ride *upload/read* route.
4. Calling it with the user's credentials would be unauthorized use of a private API and would hit Play's
   Device and Network Abuse clause (`apps that access or use a service or API in a manner that violates its
   terms of service`).

`UNCERTAIN`: the exact semantics and schemas of `/api/rides` — no authenticated call was made.

#### 2.4.2 A cloud → bike command channel exists — but only for Velotric staff

The same Swagger document exposes the vendor's remote-command machinery: tag `指令模板管理`
("Command Template Controller") with `POST /admin/command-templates/{id}/push` (push a command template to a
vehicle), `GET /admin/mqtt-logs` (`MQTT 消息日志`), and tag `开放接口授权管理` ("open-interface authorization")
with `POST /admin/system/sys-users/{userId}/api-keys`. In other words the vendor *can* push commands to a bike
over MQTT, but the API keys are **issued by hand inside the staff console**. There is no self-service and no
public issuance path, so this is not a third-party surface.

Also exposed on the same host (recorded for completeness, not used): port `9000` serves a MinIO console login
page. No login was attempted.

#### 2.4.3 The only officially published programmatic surface is commerce

`https://www.velotricbike.com/agents.md` (found via `https://www.velotricbike.com/sitemap_agentic_discovery.xml`)
tells agents to use the store's Universal Commerce Protocol endpoints: `GET /.well-known/ucp` and
`POST /api/ucp/mcp`. Its `tools/list` returns 13 tools (`create_checkout`, `search_catalog`, …). **Zero bike or
ride tools.** So Velotric does publish a developer-facing API — for *shopping*, not for the bike or the app.

### 2.5 Tasker / MacroDroid / IFTTT / SmartThings / Google Home / Alexa — NONE

| Channel | Check (2026-09-16) | Result |
|---|---|---|
| Tasker | `https://tasker.joaoapps.com/plugins.html` | no `Velotric` / `velopower` anywhere on Tasker's official plugin page |
| MacroDroid | `https://wiki.macrodroid.com/wiki/api.php?action=query&list=search&srsearch=velotric` | `totalhits: 0` (also 0 for `velopower`, `ebike`, `bike`) |
| IFTTT | `https://ifttt.com/search/query/velotric`, `…/query/velopower` | `No results found We couldn't find an Applet or service that matched your query.` |
| SmartThings | `https://partners.smartthings.com/works-with-smartthings` | no Velotric entry (`WEAK`: a partner directory, absence is weaker evidence) |
| Google Home / Alexa | no authoritative public directory; Amazon skill search inconclusive | `WEAK / INCONCLUSIVE` |
| Vendor's own docs | Zendesk search API `https://velotric.zendesk.com/api/v2/help_center/articles/search.json?query=…` | `Alexa` → 0, `tasker` → 0, `Strava` → 0, `GPX` → 0, `TFT` → 0 |
| Automate (LlamaLab) | `https://llamalab.com/automate/community/?q=velotric` | `No matching flows!` |

**Consequence:** there is no documented or community automation integration. MacroDroid *could* in principle
drive the UI through its `UI Interaction` action, which its own wiki describes as using the accessibility
service: `This action can be used to perform basic UI interactions with other apps, such as clicks and gestures
(Android 7+), using Android's accessibility service. Please be aware that any macros using this action may be
fragile and subject to breaking…` and `Note that this action cannot function when the screen is off.`
(`https://wiki.macrodroid.com/wiki/index.php?title=Action:_UI_Interaction`). That is the same technique as §5.

### 2.6 Widget, quick-settings tile, shortcuts, Wear OS — NONE

| Surface | Check | Result |
|---|---|---|
| Home-screen widget | `android:name="…AppWidget…"` in manifest → 0; `appwidget` in `libapp.so` → absent; `res/xml/` contains only `flutter_share_file_paths.xml`, `ga_ad_services_config.xml`, `image_share_filepaths.xml`, `splits0.xml` | no widget |
| Quick-settings tile | `TileService` → 0 in manifest, absent in `libapp.so` | no tile |
| App shortcuts | `shortcut` → 1 hit in manifest, and it is `com.htc.launcher.permission.UPDATE_SHORTCUT` (a launcher badge/legacy permission), no `shortcuts.xml`, no `<meta-data android:name="android.app.shortcuts">` | no app shortcuts |
| Wear OS app | Play listing HTML (1,113,610 bytes) contains **0** occurrences of `Wear OS` and 0 of `wear`; `adb shell dumpsys package` on SM-S918U reports `splits=[base, config.arm64_v8a, config.en, config.xxhdpi]` (no watch split); manifest has 0 `android.hardware.type` and 0 `wear` | no Wear OS companion |
| Apple Watch companion (iOS only) | App Store `application/ld+json` has `"availableOnDevice":"Apple Watch, iPhone, iPod"`, `"operatingSystem":"Requires iOS 12 and watchOS 9 or later…"`; app strings `The Velotric Watch app is not installed…`, `com.mddoscar.velotricbike/appleWatch/heartRate` | exists, but **heart-rate only** (`When the button changes to STOP, the connection is successful and your iPhone has started broadcasting your Apple Watch heart rate to the bike.`) |

`UNCERTAIN`: whether the watchOS app can start/stop a ride. It cannot be inspected from Android; every
watch-related string in the Android app concerns heart rate, and no ride/record string mentions the Watch.

### 2.7 Third-party BLE protocol — YES for bike control, NO for ride recording

`https://github.com/seaty6/velotric-pwa` (MIT, pushed `2026-07-16T23:40:14Z`) is an independent
re-implementation of the Velotric bike's Bluetooth controls as a Web-Bluetooth PWA. Verbatim from its README:

- `This project is a reimplementation of the bike controls as a Progressive Web App (PWA). It works in Chrome on desktop and Android, and on iOS with Bluefy.`
- `The app connects directly to the bike with Web Bluetooth. It does not require a Velotric account, does not store credentials, and does not send bike data to a server. The current protocol implementation was developed and tested against a Velotric Tempo advertising as `Vel R01`.`
- `Close the official Velotric app before connecting so it does not hold the bike's Bluetooth connection.`
- `protocol.js builds and decodes the confirmed `CA 85 ... 0C` packets.`
- `This is an independent open-source project and is not affiliated with or endorsed by Velotric.`

Identifiers (`app.js`): `SERVICE_UUID = "000018e6-0000-1000-8000-00805f9b34fb"`,
`WRITE_UUID = "000039f5-0000-1000-8000-00805f9b34fb"`,
`NOTIFY_UUID = "000039f6-0000-1000-8000-00805f9b34fb"`, device filter `filters: [{ namePrefix: "Vel R01" }]`.
**Cross-check:** those three UUID strings are also present in the official app's `libapp.so`
(`strings` dump, 2026-09-16), so the independent project is talking to the real Velotric GATT service.

Documented opcode map (`OPCODE_NAMES`): `0x10` auth request, `0x11` auth result, `0x50/0x51` read/software
versions, `0x5c/0x5d` read/hardware versions, `0x70/0x71` read/settings block, `0x72/0x73` read/state block,
`0x74/0x75/0x76` start telemetry / result / telemetry, `0x7c/0x7d` keepalive, `0x80/0x81` read/ride profiles,
`0xa0–0xa7` motor/display settings write+status, `0xac–0xae` ride-profile write.

- **No record/trip/start-stop opcode exists in that map.** `SPECULATION` (consistent with §3): the bike
  protocol carries telemetry and settings; ride recording is a phone-side concept, so a BLE command that makes
  the *app* start recording very likely does not exist.
- The bike is **not open-control**: the PWA author had to implement
  `Direct Bluetooth connection and local session authentication` (`buildAuthChallenge()` with lookup tables),
  i.e. there is a challenge/response gate.

`UNCERTAIN`: whether an undocumented opcode could command the *bike* to do something the app treats as a ride.
Even if it did, the vendor's own model (§3) puts the ride record in the phone app and the account, not the bike.

**Bottom line for question 2:** no deep link, no exported component, no SDK, no consumer API, no automation
integration, no widget/tile/Wear OS surface. The only reachable programmatic behaviour is (a) launching the
launcher activity and (b) driving the UI (accessibility / Shizuku-ADB) — plus a third-party BLE protocol that
controls the bike but has no notion of "ride recording".

---

## 3. Who records the ride: the phone app or the bike? (question 4)

### 3.1 The official App Manual: the PHONE app is the recorder

Best source: **Velotric App Manual**,
`https://velotric.zendesk.com/hc/en-us/articles/23522731151003-Velotric-App-Manual`
(article `updated_at` `2026-09-15T19:03:19Z`; fetched via
`https://velotric.zendesk.com/api/v2/help_center/articles/23522731151003.json`; the same content exists as a
PDF, `https://velotric.zendesk.com/hc/en-us/article_attachments/38969697122587`, footer `V1.1`).
Verbatim, in workflow order:

- `Before pairing your Ebike with the Velotric App, you need to create an account.`
- Pairing is started **on the bike**: `Turn on the display, and long press the "M" button for 2 seconds to enter MENU. Select "Connect to App" and single-press the "M" button. You will then see the QR code. Tap "Pair My Bike" on the Velotric App. … Scan the QR code on the display to pair your Ebike.`
- `Tap "Start Riding" to track your ride. Please make sure your Ebike is powered on and connected to your device. You will see your location on the map and dashboard. If you want to record your route, please authorize the location permission of your device.`
- `Tap "Pause" to pause the recording. Tap "Resume" to continue recording or long press "Hold to Stop" for 3 seconds to stop tracking. Your ride will be saved to your account.`
- `Tap "My Rides" to enter the "My Rides" page to check ride history and statistics. … In ride history, you can see every single ride record. Tap it to see the ride details, including your Ebike picture, route, starting time, trip distance, trip time, average speed, max speed, calories, and CO2 saved.`
- `Tap "Share" to share your ride with other third-party apps.` — both share types are **images**
  (`Share the route: the route, trip distance, CO2 saved, trip time and calories;` /
  `Share your picture: upload the picture from your phone gallery or take a photo as the background.`).
  There is no GPX/FIT export and no Strava integration (`Strava` → 0, `GPX` → 0 in the help-centre search).
- The PDF adds a future integration: `On the Data Synchronization page, you can choose the health platform you want to sync with. Currently, Velotric supports Apple Health®. In the future, it will also support Health Connect and Strava.`

The marketing page agrees that the app is the recorder — and overstates it:
`https://www.velotricbike.com/pages/velotric-app` says `The Velotric App automatically tracks your rides.` and
`The Velotric App records your mileage, trajectory, average speed, and time of each ride.` The word
"automatically" is contradicted by the manual's manual tap; treat the marketing sentence as stale.
`UNCERTAIN` which text is current.

The app's own strings describe a phone-side, stateful recorder:

- `To support the 'Cycling Record' feature, the Velotric App will collect location data, even when it is closed or not in use.`
- `The Velotric App need the Location permission. Without this permission, recording your cycling trajectory won't be possible. The recorded cycling data will be saved to your account. You can delete this data at any time.`
- `The trip distance is less than 100 meters, so this ride won't be recorded.`
- `Unbinding the bike will interrupt the current recording. Are you sure you want to proceed?` (equivalents for `Add a new bike`, `Switching to another bike`, `Log out`)
- Dart modules: `package:velotric_app/services/location_foreground_service.dart`,
  `services/ride_record_cache_service.dart`, `shared/d2/riding_controller.dart`,
  `widgets/animated/animated_riding_stop_button.dart`
- Dart symbols: `onClickStartRide`, `_startRideTime`, `uploadRideRecord`, `RideRecordRequestParamsModel`,
  `RideRecordCacheService`, `rideRecordDetail`, `CyclingRecordDetailPage`, `ridingSpeed` — the record is
  created, cached on the phone (`RideRecordCacheService`), and then uploaded (`uploadRideRecord` →
  `/api/rides`, §2.4.1). The HTTP verb is not verified — only the path string is.)

### 3.2 The bike/display keeps trip *totals* only — no ride history

Official article `https://velotric.zendesk.com/hc/en-us/articles/38515919869595-How-to-Reset-Trip-Mileage`
(updated `2025-07-03T09:36:18Z`):

- `Note: Resetting the trip range will also simultaneously clear the maximum speed, average speed, trip time, and total calories.`
- `Discover 2/Breeze 1/Nomad 2/Fold1 Plus/Nomad 2X/Triker When there is no speed in the power on state, long press the [M] button for 2 seconds to enter MENU. … Single press the [M] button to reset the trip distance, trip time, average speed, max speed, calories burned and CO2 saved.`
- Owner's manuals list the same values as display screens: `Riding Data … TRIP DIST. TRIP TIME EST. RANGE CO2 SAVED - MAX SPEED AVG. SPEED ODO. TOTAL TIME` (Summit 2 manual p.36; same list on Tempo p.34, T1 ST Plus p.15).

So the bike holds **one running trip counter plus a lifetime odometer**, resettable with physical buttons. It has
no list of past rides, no start/end timestamps and no GPS trace. The T1 and T1 ST have *no LCD at all* (0
occurrences of `TRIP DIST`, `ODO`, `Display`, `LCD` in those manuals), so on those bikes the app is the only
place a distance exists.

### 3.3 The bike's cellular/GPS hardware is for anti-theft and OTA, not ride recording

`https://velotric.zendesk.com/hc/en-us/articles/42608078864795-T1-eSIM-FAQ`:
`If the eSIM service is not renewed, your bike will still function normally for daily riding. However, connected
features that rely on cellular service will no longer be available. Unavailable without renewal: Location
Tracking Anti-theft Alerts OTA (Over-the-Air) Updates`. Newer models also ship `Apple Find My/Android Find Hub`
(theft recovery, relayed by other people's phones). Neither is documented as ride recording.

### 3.4 Can a ride be recorded without the app? — No

- The only documented recorder trigger is `Tap "Start Riding"`; the only documented stop is
  `long press "Hold to Stop" for 3 seconds`, after which `Your ride will be saved to your account.`
- With no phone, all that remains is the display's running trip counter (§3.2) — a counter, not a ride: it has
  no wall-clock start/end time and no route, so it cannot be turned into a ride record after the fact.
- The app's ride history is built from app-recorded rides. The only "sync" the App Manual documents is
  **outward** (`Data Synchronization … Apple Health`), not bike → app.
- Server side, the vendor's own operations database *does* hold per-vehicle ride rows keyed by `imei`/`sn`
  (§2.4), and the phone app contains `uploadRideRecord` plus the `/api/rides` path (§2.4.1). The simplest
  reading — the phone uploads the ride it recorded — is consistent with both, and there is no documented
  bike → cloud ride upload. `UNCERTAIN`: whether the rows are only ever written by the phone app, or whether
  the IoT module also contributes telemetry; the schema does not carry a source field.
- **Therefore:** if the phone app is not started, no ride exists that a third party can retrieve. A third-party
  app cannot "recover" a missed ride.

**Bottom line for question 4:** the **phone app is the recorder** (GPS track, `authorize the location permission
of your device`), rides are saved to the rider's Velotric account, and the bike/controller only maintains trip
totals and odometer values that the rider clears with physical buttons. There is no bike → app ride sync to piggyback on.

---

## 4. Realistic fallbacks, ranked by robustness (question 3)

Ranked from most robust to least. "Play risk" is the Google Play policy exposure for shipping it in a Play app.

### F1 — Record the ride in OUR app; treat Velotric as out of scope. **RECOMMENDED**
- What it is: our app does the recording (it already owns planning/GPS), and the Velotric app is simply not the
  recorder for those rides. The user keeps the bike's display trip data for free.
- Robustness: **high**. No dependency on a third-party UI, no policy exposure, no breakage when Velotric ships
  an update.
- Play risk: **none**.
- Cost: the ride is not in the Velotric account, so the Velotric app's "My Rides"/Apple Health data will not
  include it. That is a product trade-off, not a technical limitation.

### F2 — Open the Velotric app (explicit launcher intent), then let the user tap. **Cheap and clean**
- What it is: `startActivity()` the exported `com.example.velotric_app.MainActivity`, optionally with a
  pre-ride notification/reminder, and ask the user to tap `START RIDING` (their Flutter button, per the App
  Manual).
- Robustness: **high** (a launcher activity is the most stable contract Android offers).
- Play risk: **none**. Docs explicitly say no `<queries>` element is needed to start another app's activity
  (`https://developer.android.com/training/package-visibility/use-cases`).
- Limit: the user still performs the tap. This automates *navigation*, not *control*.

### F3 — Accessibility-service UI automation inside the Velotric app ("auto-tap"). **Only working automation; policy-declared**
- What it is: our app registers an `AccessibilityService`, finds the Velotric node labelled `START RIDING`
  (and `Resume`, and the `Hold to Stop` button) and clicks it. This is exactly what the public Appium project
  `swseven-hub/auto-veloapp` does:
  `start_riding_btn = (AppiumBy.XPATH, '//android.view.View[@content-desc="START RIDING"]')` and
  `resume_btn = (AppiumBy.XPATH, "//*[@content-desc[contains(., 'Resume')]]")`
  (`https://raw.githubusercontent.com/swseven-hub/auto-veloapp/main/locator/home_locator.py`, `…/riding_locator.py`).
  It proves the controls are reachable as accessibility nodes.
- Robustness: **low-medium**. Flutter renders the controls as `android.view.View` nodes; a rename, a re-layout,
  or an off-screen element breaks the automation. Our own device experience says off-fold nodes can be missing
  from a `uiautomator` dump, and dumps need an idle window.
- Play risk: **material, but not a blanket ban.** Play's AccessibilityService policy says verbatim:
  `Apps using the Accessibility API for automation purposes must ensure that all actions performed on a user's
  behalf are for a narrow and clearly understood purpose. Any use of the Accessibility API that enables an app
  to autonomously initiate, plan, and execute actions or decisions is strictly prohibited. This does not
  prohibit deterministic, rule-based automation, where behavior follows a static, human-defined script (for
  example, "If Trigger X occurs, perform Action Y").`
  (`https://support.google.com/googleplay/android-developer/answer/10964491`). A user-pressed "start my ride"
  that deterministically clicks one known button fits the *permitted* deterministic case — but the same page
  requires the app to complete the **Permissions Declaration Form** (`Starting November 3, 2021, apps that
  target API level 31 and include an AccessibilityService will need to complete a new policy declaration in
  Play Console.`) and to show a **prominent disclosure inside the app** that `Must require affirmative user
  action for consent (for example, tap to accept, or tick a check box); Cannot only be placed in a privacy
  policy or terms of service; and Cannot be included with other disclosures pertaining to personal or sensitive
  data collection.` The page also names `automation tools` as *not* accessibility tools, so this app cannot
  claim the tool exemption. Add the Device and Network Abuse clause
  (`We don't allow apps that interfere with, disrupt, damage, or access in an unauthorized manner the user's
  device, … including but not limited to other apps on the device…`) as a residual risk if the automation acts
  without the user's immediate intent.
- Verdict: shippable only with the declaration form, the in-app disclosure, and a user-visible trigger. It will
  never be as reliable as F1.

### F4 — Shizuku / ADB-shell input injection (`input tap` at the button's bounds)
- What it is: the same automation as F3 but executed with shell-level privileges obtained from the user via
  Shizuku (wireless debugging). See `docs/research/deepseek-android.md`-style use of `adb` for the developer
  workflow.
- Robustness: **low**. Requires Shizuku installed and re-authorized after every reboot; the user has to enable
  wireless debugging; coordinates still break with layout changes.
- Play risk: **medium**. No policy names Shizuku, but the app depends on a non-Play-installed helper and on ADB
  privileges, and it needs `QUERY_ALL_PACKAGES`-class visibility to find it. Justifying that in a Permissions
  Declaration Form is hard: `the use of the permission is only permitted when your app's core user-facing
  functionality or purpose requires broad visibility into installed apps`
  (`https://support.google.com/googleplay/android-developer/answer/10158779`).
- Verdict: fine for a developer/sideload tool, poor for a Play product.

### F5 — UiAutomator / `adb shell` test-harness automation
- What it is: `androidx.test.uiautomator` driven by an instrumentation runner from a host, or plain
  `adb shell input tap`.
- Robustness: **medium inside a test rig, zero in a product.** UiAutomator requires an `Instrumentation`
  instance that only the system (or a test runner) can provide, and it only runs while the device is connected
  to a host.
- Play risk: **N/A — not shippable.** Useful only as our own acceptance-test tool for documenting behaviour
  (the same role `swseven-hub/auto-veloapp` plays for someone else).

### F6 — Read-side integration instead of control: Health Connect (Android) / Apple Health (iOS)
- What it is: don't start/stop anything; read the ride the Velotric app wrote out. The Android app declares
  `android.permission.health.WRITE_DISTANCE` and `android.permission.health.WRITE_ACTIVE_CALORIES_BURNED` and
  binds `androidx.health.platform.client.impl.sdkservice.HealthDataSdkService`; its own string says
  `To sync your riding data with Health Connect, please download and install the Health Connect app from Google
  Play.` The App Manual PDF says Health Connect is a future platform alongside Apple Health.
- Robustness: **medium**. It gives distance/calories (and whatever else Velotric writes), **not** a start/stop
  control and probably not a full track.
- Play risk: **low** (Health Connect has its own declaration requirements).
- Status: `UNCERTAIN` whether the Android Health Connect sync is already live; the published Play listing only
  advertises `Track ride data and view your riding history`.

### F7 — Direct BLE control of the bike (not the app)
- What it is: connect to the bike with the reverse-engineered protocol (service `000018e6-…`, write
  `000039f5-…`, notify `000039f6-…`, name prefix `Vel R01`) and change settings/read telemetry ourselves.
- Robustness: **low** and legally grey. The bike authenticates (`buildAuthChallenge()`), the vendor does not
  document the protocol, and the official app must be closed to free the connection.
- Play risk: **medium-high** — this is exactly the "access … in an unauthorized manner … other devices"
  wording in Device and Network Abuse, and it would `access or use a service or API in a manner that violates
  its terms of service`.
- Also: it does **not** solve the stated goal, because the protocol has no ride-record concept (§2.7).

### F8 — Hijack a MediaSession / inject media buttons
- What it is: `AudioManager.dispatchMediaKeyEvent()` to prod the Velotric app.
- Why it fails technically: the API reference says verbatim `The key event will be sent to the current media key
  event consumer which registered with AudioManager.registerMediaButtonEventReceiver(PendingIntent).`
  (`https://developer.android.com/reference/android/media/AudioManager`), i.e. the event goes to whichever app
  currently owns media-button routing, not to an app of your choosing. A `MediaSession` only receives media
  buttons for itself (`A MediaSession should be created when an app wants to publish media playback information
  or handle media keys.`, `https://developer.android.com/reference/android/media/session/MediaSession`), and the
  Velotric app declares no `MediaSession`/media-button receiver for rides.
- Verdict: **not possible.** Do not build on it.

### F9 — Cloud-side automation
- What it is: call the Velotric backend to create/start a ride.
- What exists: (a) an internal ops backend documented by public Swagger, every path `/admin/…` guarded by a
  staff `Authorization` API key (§2.4); (b) the app-facing API the Flutter build talks to,
  `http://app.velotricbike.com:7002/api/…`, which includes `/api/auth/login`, `/api/rides` and
  `/api/rides/ridingEvent` but no documented start/stop route (§2.4.1).
- Why it fails: (a) is staff-only and (b) is undocumented, credential-gated and, in the current build,
  semantically a ride *upload/read* API — the `rides/go` / `rides/{}/end` shapes exist only in the 2022 build,
  whose host is gone. Using either would be unauthorized access and would violate the service's terms.
- Verdict: **not possible** (and not attempted).

---

## 5. What is explicitly NOT possible

1. **Starting or stopping a Velotric ride recording through any Android IPC contract.** There is no exported
   activity, service, receiver, provider, action, or deep link for it (§2.1, §2.2). The recording foreground
   service is `exported="false"`.
2. **Any vendor-supported deep link**, including "open the app at the ride screen": the vendor domain publishes
   empty `assetlinks.json` and empty `apple-app-site-association` (§2.1).
3. **A public Velotric SDK or REST API** for ride control. None found in Maven/npm/PyPI/GitHub; no developer
   portal; `api.`/`developer.` subdomains do not resolve; the one public API document is the internal ops
   backend; the app-facing API embedded in the APK is undocumented, credential-gated and has no start/stop
   route in the current build (the 2022 build did, but its host `rdprod.velotric.net` is gone) — and the only
   *officially published* API is commerce-only (§2.3, §2.4, §2.4.1, §2.4.3).
4. **Tasker / MacroDroid / IFTTT / Google Home / SmartThings / Alexa control.** Not present anywhere (§2.5).
5. **Quick-settings tile, widget, or app shortcut** in the Velotric app to trigger. Absent (§2.6).
6. **Wear OS**: no watch app, so no wrist-side start/stop (§2.6). The Apple Watch app exists but is a heart-rate
   companion and is iOS-only.
7. **MediaSession / media-button hijacking.** The media-button path routes to the current registered consumer,
   not to the Velotric app (§4 F8).
8. **A bike-side ride record that a third party can read.** The bike keeps trip totals only; there is no
   documented bike-side ride history and no consumer API that exposes the server-side ride rows (§3).
9. **"Auto-record" behaviour to piggyback on.** No `autoRecord`/`auto record` string exists in the app; the
   manual documents a manual tap only (§3.1).
10. **Screen-on tapping from a background app.** Background activity launches are restricted since Android 10
    (`Since Android 10 (API level 29), the platform has placed restrictions on when apps can start activities
    from the background.`, `https://developer.android.com/guide/components/activities/background-starts`), and
    MacroDroid's own documentation states its accessibility-based `UI Interaction` `cannot function when the
    screen is off`. A "start my ride automatically when I leave home" flow therefore has to bring the phone up
    and in front of the user.
11. **UiAutomator / `adb`-only automation as a shipped feature.** It needs a host connection or an
    instrumentation runner; it is a test tool, not a product (§4 F5).

---

## 6. Reproduction recipe

```bash
# 1. identity + listing
curl -sL "https://play.google.com/store/apps/details?id=com.mddoscar.velotricbike&hl=en_US&gl=US" -o play.html
grep -o '"@type":"SoftwareApplication".\{0,400\}' play.html
curl -s "https://itunes.apple.com/lookup?id=1626016434&country=us"      # iOS twin, same bundle id

# 2. vendor App Links are absent
curl -s https://www.velotricbike.com/.well-known/assetlinks.json                    # -> []
curl -s https://www.velotricbike.com/.well-known/apple-app-site-association          # -> empty applinks

# 3. official recorder description
curl -s "https://velotric.zendesk.com/api/v2/help_center/articles/23522731151003.json" | python -m json.tool | head -40

# 4. internal ops API document (read-only; no credentials used)
curl -s https://app.velotricbike.com/api/v2/api-docs -o api-docs.json
python - <<'PY'
import json; s=json.load(open("api-docs.json"))
print(len(s["paths"])); print([p for p in s["paths"] if "ride" in p.lower()])
PY

# 5. APK facts (manifest, exported components, schemes, absence checks)
#    APK 4.5.5 (217) obtained from a mirror / the installed app; decoded with androguard 4.1.4
python - <<'PY'
import logging; logging.disable(logging.CRITICAL)
from androguard.core.apk import APK
import re
a = APK("base.apk")
print(a.get_package(), a.get_androidversion_name(), a.get_androidversion_code())
xml = a.get_android_manifest_axml().get_xml().decode()
print(sorted(set(re.findall(r'android:scheme="([^"]+)"', xml))))     # ['hwpps']
print(len(re.findall(r'android:exported="true"', xml)))              # 9
for kw in ("TileService", "AppWidget", "wear"):
    print(kw, len(re.findall(kw, xml, re.I)))                        # 0 0 0
print(re.findall(r'usesCleartextTraffic="[^"]+"', xml))              # ['usesCleartextTraffic="true"']
PY

# 6. the app's own API surface lives in the Dart snapshot
unzip -o -q velotric_4.5.5.xapk 'config.arm64_v8a.apk' -d xapk
unzip -o -q xapk/config.arm64_v8a.apk lib/arm64-v8a/libapp.so -d xapk
strings -n 6 xapk/lib/arm64-v8a/libapp.so | grep -E 'velotricbike\.com:7002|oms\.(preprod|test)|/api/(rides|auth)' | sort -u
strings -n 6 xapk/lib/arm64-v8a/libapp.so | grep -E 'onClickStartRide|uploadRideRecord|appleWatch' | sort -u
```

Evidence files: `docs/research/raw/velotric/` — thread reports `parent-thread.md`, `apk-and-manifest.md`,
`cloud-api-developer.md`, `automation-integrations.md`, `recorder-location.md`; raw artifacts
`app.velotricbike.com_api_v2_api-docs.json`, `app.velotricbike.com_api_paths.txt`, `apk_manifest_summary.txt`,
`app_strings_selected.txt`, `zendesk-articles.json`, `velotric-app-manual.pdf`, `pdfs-app-manual.txt`,
`play-datasafety.txt`, `apple-velotric.txt`, plus captured HTML. Tooling for the APK work:
`uv pip install androguard` (4.1.4) in a scratch venv.

---

## 7. Evidence index (all fetched 2026-09-16 unless a page states otherwise)

| # | URL | Used for |
|---|---|---|
| 1 | `https://play.google.com/store/apps/details?id=com.mddoscar.velotricbike` | package id, publisher, version, data safety, reviews |
| 2 | `https://play.google.com/store/apps/datasafety?id=com.mddoscar.velotricbike` | data-safety detail: `Location` → `Precise location · Optional · App functionality` |
| 3 | `https://apps.apple.com/us/app/velotric/id1626016434` | iOS twin, Apple Watch app, background-location disclosure |
| 4 | `https://itunes.apple.com/lookup?id=1626016434&country=us` | bundle id, seller, version 4.5.5 |
| 5 | `https://www.velotricbike.com/pages/velotric-app` | vendor's own description of the app as the recorder |
| 6 | `https://velotric.zendesk.com/hc/en-us/articles/23522731151003-Velotric-App-Manual` | Start/Pause/Resume/Hold-to-Stop flow; rides saved to the account |
| 7 | `https://velotric.zendesk.com/hc/en-us/article_attachments/38969697122587` | App Manual PDF `V1.1` (Health Connect/Strava future statement) |
| 8 | `https://velotric.zendesk.com/hc/en-us/articles/38515919869595-How-to-Reset-Trip-Mileage` | bike-side trip counter and `Clear Trip Data` |
| 9 | `https://velotric.zendesk.com/hc/en-us/articles/42608078864795-T1-eSIM-FAQ` | bike cellular module = tracking/anti-theft/OTA |
| 10 | `https://www.velotricbike.com/.well-known/assetlinks.json` (and apex) | Android App Links absent (`[]`) |
| 11 | `https://www.velotricbike.com/.well-known/apple-app-site-association` | iOS Universal Links absent |
| 12 | `https://app.velotricbike.com/api/v2/api-docs` | internal ops API; ride-record schemas |
| 13 | `https://github.com/seaty6/velotric-pwa` (+ `README.md`, `app.js`, `protocol.js`) | third-party BLE protocol, UUIDs, opcode map |
| 14 | `https://github.com/swseven-hub/auto-veloapp` (+ locators, test cases) | proof the ride buttons are accessibility nodes |
| 15 | `https://support.google.com/googleplay/android-developer/answer/10964491` | AccessibilityService automation policy + declaration form |
| 16 | `https://support.google.com/googleplay/android-developer/answer/9888379` | Device and Network Abuse |
| 17 | `https://support.google.com/googleplay/android-developer/answer/10158779` | `QUERY_ALL_PACKAGES` restrictions |
| 18 | `https://developer.android.com/guide/components/activities/background-starts` | background activity launch restrictions |
| 19 | `https://developer.android.com/training/package-visibility/use-cases` | starting another app's activity needs no `<queries>` |
| 20 | `https://developer.android.com/reference/android/media/AudioManager` | `dispatchMediaKeyEvent` goes to the current media-button consumer |
| 21 | `https://developer.android.com/reference/android/media/session/MediaSession` | media sessions handle their own media buttons |
| 22 | `https://tasker.joaoapps.com/plugins.html` | no Velotric Tasker plugin |
| 23 | `https://wiki.macrodroid.com/wiki/api.php?action=query&list=search&srsearch=velotric` | `totalhits: 0` |
| 24 | `https://ifttt.com/search/query/velotric` | `No results found` |
| 25 | `https://llamalab.com/automate/community/?q=velotric` | `No matching flows!` |
| 26 | `https://apkcombo.com/velotric/com.mddoscar.velotricbike/` | mirror metadata: `4.5.5`, `(217)`, `Update Jun 29, 2026`, `77 MB`, `Android 8.0+` |
| 27 | `https://mi9.com/package/com.mddoscar.velotricbike/` | version history with versionCodes (`217/200/187/…/304`) |
| 28 | `https://d.apkpure.com/b/XAPK/com.mddoscar.velotricbike?versionCode=217` | the XAPK that was decoded (`Velotric_4.5.5_APKPure.xapk`, 60,686,435 B) |
| 29 | `https://velotric.zendesk.com/api/v2/help_center/articles/search.json?query=…` | absence scans (`Strava`, `GPX`, `Alexa`, `tasker` → 0) |
| 30 | `https://www.velotricbike.com/agents.md` | the vendor's agent instructions; only store/commerce endpoints |
| 31 | `https://www.velotricbike.com/.well-known/ucp` + `POST https://www.velotricbike.com/api/ucp/mcp` (`tools/list`) | the only vendor-published API: 13 commerce tools, `get_checkout` … `get_product`, zero bike/ride tools |
| 32 | `https://crt.sh/?q=%25.velotric.net` | the `velotric.net` infrastructure host set (`oms.prod/preprod`, `ai.`, `api-ai.`) |
| 33 | `https://search.maven.org/solrsearch/select?q=velotric&rows=20&wt=json`, `https://registry.npmjs.org/-/v1/search?text=velotric`, `https://pypi.org/pypi/velotric/json`, `https://api.github.com/search/repositories?q=velotric` | registry sweeps (§2.3) |
| 34 | `https://www.velotricbike.com/policies/privacy-policy`, `…/policies/terms-of-service` | privacy notice is website-scoped (`Last updated March 15, 2022`, zero mentions of the app); terms have no API clause |

---

## 8. Confidence and open questions

| Claim | Confidence | Basis |
|---|---|---|
| Package is `com.mddoscar.velotricbike`, publisher Velopower, Inc. | **CERTAIN** | Play listing + APK manifest + iOS bundle id |
| No exported IPC component starts/stops a ride | **CERTAIN** | full exported-component list from the APK 4.5.5 manifest |
| The recording foreground service is not exported | **CERTAIN** | manifest `android:exported="false"` |
| No Android App Links / iOS Universal Links | **CERTAIN** | empty `assetlinks.json` and `apple-app-site-association` |
| No widget / QS tile / shortcuts / Wear OS | **HIGH** | manifest + `libapp.so` + Play listing + splits check |
| The phone app is the ride recorder | **HIGH** | App Manual + app strings + permissions |
| The bike keeps only a running trip counter | **HIGH** | manuals + `How to Reset Trip Mileage` |
| No public developer API / SDK | **HIGH** | Maven/npm/PyPI/GitHub searches, DNS failures, Play listing links |
| The public ops API is not usable for ride control | **HIGH** | every path is `/admin`/`/sys`/factory-sync, `ApiKey: Full Access` |
| Third-party BLE protocol exists but has no record opcode | **MEDIUM-HIGH** | independent PWA + cross-confirmed UUIDs in the official app |
| Health Connect export is live on Android | **UNCERTAIN** | app declares write permissions and a Health Connect string; the vendor calls it a future feature in the App Manual PDF |
| Whether the watchOS app can start/stop a ride | **UNCERTAIN** | can only inspect the Android side; all watch strings are heart rate |
| The app has its own undocumented API (`http://app.velotricbike.com:7002/api/…`) | **CERTAIN that it exists** | verbatim host and path strings in `libapp.so` |
| That API can start/stop a ride *today* | **VERY LOW** | 4.5.5 contains only `/api/rides` and `/api/rides/ridingEvent`; the credential-gated `rides/go` / `rides/{}/end` routes exist only in the 2022 build, whose host no longer resolves |
| Velotric can push commands to a bike from the cloud | **CERTAIN (staff side)** | Swagger: `POST /admin/command-templates/{id}/push`, `GET /admin/mqtt-logs`, API keys issued in the console |
| Community/forum evidence for or against integrations | **UNCERTAIN (tooling)** | search engines and Reddit were blocked on 2026-09-16; integration platforms' own directories were used instead |
| The phone uploads its recorded ride to Velotric's cloud | **MEDIUM-HIGH** | `uploadRideRecord`, `RideRecordRequestParamsModel`, `/api/rides` in `libapp.so`; server rows with `rideTrace` + `createTime` (上传时间) in the ops schema |
| Play Data safety declares background location | **NO** (declares `Precise location` only, while the APK holds `ACCESS_BACKGROUND_LOCATION`) | data-safety page vs decoded manifest. The implication is `UNCERTAIN`; the two facts are simply stated |

Open questions worth a follow-up if this ever becomes a feature request:

1. Does the app's `developer_mode_page.dart` expose raw BLE command sending? It is in-app UI, so it does not
   change the verdict, but it would document the protocol further.
2. Which host serves the phone app's ride upload? Knowing it would settle §3.4's bike-vs-phone question for the
   server rows, but not add a usable control surface.
3. Is a ride ever editable/back-filled in the app? No evidence either way (no API or doc mentions it).

## 9. Bottom line

**Start-track: not feasible.** **Stop-track: not feasible.** The Velotric app exposes no programmatic control
surface at all — no deep link, no exported ride component, no SDK, no documented consumer API, no automation integration,
no widget/tile/Wear OS trigger — and its ride recording is a manual, in-app, account-bound action. The bike
records only a running trip counter, so there is no bike-side ride to sync or read either.

Note also *where the data goes*: the recorded ride is cached on the phone and then uploaded to Velotric's own
cloud (`RideRecordCacheService` → `uploadRideRecord` → `/api/rides`), under the rider's Velotric account.
There is no documented export (the in-app `Share` produces an **image**), and Health Connect / Strava support is
announced as a *future* feature. So even the read side gives us nothing today.

If our product needs a recorded ride, **record it ourselves (F1)**. If we only need to put the user in the right
place, **open the Velotric app with an explicit launcher intent (F2)**, which is policy-clean. Anything that
clicks inside the Velotric app (F3/F4) is fragile UI automation that requires a Play Permissions Declaration
Form and an in-app prominent disclosure, and it will break whenever Velotric changes its Flutter layout.
