# DATA LICENCES AND ATTRIBUTION

A short, honest record of every external dataset this app consumes, what its licence permits, and what
the app must display. Written 2026-09-15. This is not legal advice; it is a do-not-forget list.

| Source | Used for | Licence / terms | Obligation |
|---|---|---|---|
| **OpenStreetMap** (via Overpass + the OSM API) | Trail geometry, road classes, cycleways, maxspeed | **ODbL 1.0** | **Attribution is mandatory.** The app must display "© OpenStreetMap contributors". Also relevant: the trail `bicycle=no` closure tags come from OSM, and our assets are a Derivative Database, so the ODbL applies to `assets/trails.json`. |
| **Valhalla** (routing engine) | /route + /trace_attributes | MIT (software); tiles built from OSM data | Attribute Valhalla in the README. Routing output is derived from OSM, so the ODbL notice covers it. |
| **WSDOT "Traffic Sections (AADT) Current"** | Vehicle counts on state highways | WSDOT open data, but the record states **"For Planning use only"** | Acceptable for this personal, unpublished app. **If this app is ever published or distributed, revisit this** -- bundling the values into an APK is redistribution. |
| **Pierce County "Traffic Volume"** | Vehicle counts on local roads | County open GIS layer, no explicit licence stated | Same caveat as WSDOT. Counts are old (mostly 1994-1996) and 64% are "County Estimate"; the app labels stale and estimate values as such. |
| **WSDOT ActiveTransportationData LTS** | Advisory level of traffic stress on state highways | WSDOT open data | Advisory display only; never used to silently change a grade. |
| **PSRC `level_of_traffic_stress_gdb`** | Advisory LTS on local arterials | **NO LICENCE DECLARED** -- this is the weakest link in the whole dataset | Kept because it is the only stress data covering the local roads, and this app is personal and unpublished. It is stored under `lts_source=psrc` so it can be stripped with one filter. **If the app is ever shared, drop it.** |
| **Google Maps SDK for Android** | Drawing the confirmed route in-app | Google Maps Platform ToS | Requires the API key to be restricted (package + SHA-1). Google's terms also restrict caching of Google content; we do not cache Google tiles or routes. |

| **DeepSeek API (api.deepseek.com)** (added 2026-09-16, D27) | Choosing which of the trails the app already found to try first | DeepSeek's own platform terms; the rider's own account and key | Nothing to display. The request carries trail and street **names** and a target mileage, **never coordinates**, so no OpenStreetMap-derived geometry is redistributed. The key is the rider's, typed into Settings and held in encrypted preferences (`docs/AI-KEY.md`). If this app is ever distributed, each rider uses their own key. |

## Overpass API (live trail discovery, added 2026-09-16 with D23a)

| Item | Value |
|---|---|
| What it is | A public read-only query API over OpenStreetMap, run by volunteers. The app uses it for the "find trails near here" button when the bundled trail sets do not cover where the rider is. |
| Mirrors used, in order | `overpass-api.de`, `overpass.kumi.systems`, `overpass.osm.ch` |
| Licence of the answers | **ODbL 1.0** — the same licence as the OpenStreetMap data it serves. A discovered trail is a Derivative Database and stays under the ODbL. |
| Attribution | "© OpenStreetMap contributors" — already displayed. No extra line is required for Overpass itself, but the freshness of the answer is shown (`osm3s.timestamp_osm_base`). |
| Terms to respect | Free, no key, but rate-limited and courtesy-based: one `User-Agent` identifying the app is mandatory (`overpass-api.de` answers 406 without one); queries are small and bounded (a 5 km radius, two route types); answers are cached for 30 days so a repeat plan asks nothing; a mirror answering 429 is treated as a limit, not as a bug. |
| Measured behaviour, 2026-09-16 | `overpass-api.de` answered 102,827 bytes with 7 route relations and a 2-minute-old stamp; `kumi.systems` timed out at 90 s and 150 s; `osm.ch` answered HTTP 200 with an EMPTY result and a non-instant stamp ("117059"), which the freshness check refuses. |
| Privacy | The query contains the rider's coordinates (a 5 km `around:` radius), so the mirror learns roughly where they are. Recorded as SEC-19 in `docs/SECURITY.md`. The bundled regional set (D23a) needs no network. |

## What the app must display

1. **"© OpenStreetMap contributors"** wherever route or trail data is shown. This is an ODbL
   requirement, not a courtesy.
2. **The data year beside any traffic figure** ("2,626 vpd, 1996") so a 30-year-old count never looks
   current.
3. **Ungraded and unknown shown as such** -- unknown is never rendered as low traffic.

## Redistribution rule

The repo must not be published with the address, the screenshots, or the WSDOT/Pierce/PSRC derived
values inside it without revisiting the entries marked above. `.gitignore` already excludes the raw
dumps.
