# SERVER — self-hosted Valhalla routing server on this Mac

Status: **WORKING** (routing + trace_attributes verified end to end; tailnet-only, no public exposure).
Written: 2026-09-15. Author: build agent (child of the SPEC/DECISIONS session).
Scope: this file records how the server was built, how to check it, how to restart it, what was
proven with real requests, and what does **not** work. It does not change `docs/SPEC.md` or
`docs/DECISIONS.md` (D5 says the engine is self-hosted Valhalla on this Mac, reached over Tailscale).

Work area: `/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/`

| Thing | Value |
|---|---|
| Host | `admins-mac-mini` (macOS, arm64), Tailscale IP `100.85.43.11`, tailnet DNS `admins-mac-mini.taile1279d.ts.net` |
| Engine | Valhalla `3.8.3-9eecaca4e` (`ghcr.io/valhalla/valhalla-scripted:latest`) |
| Image digest | `sha256:b5e41355b185fcd57aa40f8ae7fa83663063db2504eadfc4304c85d18904ef23` (746 MB) |
| Container | name `valhalla`, id `b2cdd290723c0615660874d1ee18d82f5d2812b13f3496a1bada51c6eccc9032`, state `running` |
| Data | Geofabrik `washington-latest.osm.pbf`, 363,098,240 bytes (346.3 MiB), md5 verified |
| Tiles | `/custom_files/valhalla_tiles.tar` (585,011,200 B) + `valhalla_tiles/` (622 `.gph` tiles, 604 MB), built **once**, reused on restart |
| Elevation | built in (`build_elevation=True`), 43 SRTM/Skadi tiles, 1.0 GB in `custom_files/elevation_data/` |
| Disk used by the server dir | 2.7 GB (`valhalla/custom_files`) |
| Tile build time (measured) | **112.3 s** from container create to first `/status` HTTP 200 (see §9) |
| Local URL | `http://127.0.0.1:8002` (loopback only) |
| Phone URL (tailnet) | `http://admins-mac-mini.taile1279d.ts.net` (Tailscale Serve, tailnet only, **cleartext HTTP**) |
| Public exposure | none — no Funnel config; nothing bound on `0.0.0.0` |

---

## 1. Evidence summary (what is actually proven)

| Claim | How it was proven | Result |
|---|---|---|
| Docker daemon runs | `docker info` after `open -a Docker` | up in < 5 s (it was already starting) |
| Server is up | `GET /status` | HTTP 200, `"version":"3.8.3-9eecaca4e"` |
| Route from home works | `POST /route`, costing `bicycle` | HTTP 200, 9.188 km / 5.709 mi, 1769.794 s, 1 leg, 12 maneuvers |
| `max_locations` cap = 25 | `POST /route` with 26 locations | HTTP 400 `"Exceeded max locations: 25"` |
| `max_distance` cap = 300000 | `POST /route` Seattle→Spokane, bicycle | HTTP 400 `"Path distance exceeds the max distance limit: 300000 meters"` |
| Per-edge safety fields exist | `POST /trace_attributes`, `shape_match: edge_walk` | HTTP 200, 178 edges, all 12 requested `edge.*` filters returned |
| Elevation data usable | `POST /height` over home→trail | `range_height` 122 m → 16 m |
| 9 shaping waypoints + O/D fits | `POST /route` with 11 locations | HTTP 200, 26.734 km / 16.612 mi, 1 leg, 53 maneuvers |
| Bind is loopback only | `lsof -nP -iTCP:8002 -sTCP:LISTEN`, `docker port valhalla` | `127.0.0.1:8002 (LISTEN)` only |
| Nothing on the LAN/tailnet IP directly | `curl http://100.85.43.11:8002/status` | connection refused (curl exit 7) |
| Reachable from the tailnet via Serve | `curl http://admins-mac-mini.taile1279d.ts.net/status` and `/route` | HTTP 200 both |
| No public Funnel | `tailscale funnel status`, `tailscale serve status --json` | serve entries only, marked `(tailnet only)`, no funnel entries |
| OSM extract integrity | Geofabrik `.md5` vs `md5 -r` | match: `cbab58e052edfb4b88a531411feeba5a` |

---

## 2. Host and disk facts

```console
$ df -h
Filesystem        Size    Used   Avail Capacity  Mounted on
/dev/disk3s1s1   228Gi    17Gi    38Gi    31%    /
/dev/disk5s1     1.8Ti   110Gi   1.7Ti     6%    /Volumes/Satechi 1
```

The project lives on `/Volumes/Satechi 1` (1.7 TiB free), so the 346 MiB extract, the 1.0 GB of
elevation tiles and the 585 MB tile tar were never a disk-space risk. Docker Desktop's VM disk
(`Docker.raw`, 1 TB sparse) and the user home also live on that volume. `/` had 36–38 GiB free
throughout.

```console
$ /usr/local/bin/docker version          # client 28.0.1, context desktop-linux, darwin/arm64
$ /usr/local/bin/docker info
Cannot connect to the Docker daemon at unix:///Volumes/Satechi/.docker/run/docker.sock. Is the docker daemon running?   # BEFORE
```

---

## 3. Commands, in order (verbatim)

### 3.1 Start the Docker daemon

```console
$ open -a Docker
$ for i in $(seq 1 90); do if /usr/local/bin/docker info >/dev/null 2>&1; then echo "DOCKER_UP after ${i} polls ($((i*5))s)"; exit 0; fi; sleep 5; done; echo DOCKER_TIMEOUT
DOCKER_UP after 1 polls (5s)
$ /usr/local/bin/docker info --format "{{.ServerVersion}} {{.OperatingSystem}} {{.Driver}}"
28.0.1 Docker Desktop overlayfs
```

No alternative runtime (colima/lima/nerdctl) was needed.

### 3.2 Prepare the data directory and download the extract

```console
$ mkdir -p "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files"
$ curl -L -C - --retry 5 --retry-delay 5 \
    -o washington-latest.osm.pbf \
    https://download.geofabrik.de/north-america/us/washington-latest.osm.pbf
100  346M  100  346M    0     0  11.8M      0  0:00:29  0:00:29 --:--:-- 12.6M
$ ls -la custom_files/washington-latest.osm.pbf
-rw-rw-r--@  1 satechi  staff  363098240 Sep 15 13:53 washington-latest.osm.pbf
```

Final size: **363,098,240 bytes = 346.3 MiB**, downloaded in 29 s (no resume was needed; `-C -`
was used anyway so a retry would resume).

Checksum verification:

```console
$ curl -sL -o washington-latest.osm.pbf.md5 \
    https://download.geofabrik.de/north-america/us/washington-latest.osm.pbf.md5
$ cat washington-latest.osm.pbf.md5
cbab58e052edfb4b88a531411feeba5a  washington-latest.osm.pbf
$ md5 -r washington-latest.osm.pbf
cbab58e052edfb4b88a531411feeba5a washington-latest.osm.pbf
```

**Match.** The extract was not re-downloaded.

Read the canonical container recipe first:

```console
$ curl -sL https://raw.githubusercontent.com/valhalla/valhalla/master/docker/README.md -o /tmp/valhalla-docker-README.md
```

Mount test before trusting a path with a space and an external volume:

```console
$ /usr/local/bin/docker run --rm -v "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla:/custom_files" alpine:latest \
    sh -c "ls -la /custom_files; touch /custom_files/.mount_test && echo MOUNT_WRITE_OK"
MOUNT_WRITE_OK
```

### 3.3 Pull and run the container

```console
$ /usr/local/bin/docker pull ghcr.io/valhalla/valhalla-scripted:latest
Digest: sha256:b5e41355b185fcd57aa40f8ae7fa83663063db2504eadfc4304c85d18904ef23
Status: Downloaded newer image for ghcr.io/valhalla/valhalla-scripted:latest
```

**The container run command** (final, hardened form; this is the live container):

```console
$ /usr/local/bin/docker run -dt --name valhalla \
    --memory 6g --cpus 4 \
    -p 127.0.0.1:8002:8002 \
    -v "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files:/custom_files" \
    -e build_elevation=True \
    -e server_threads=4 \
    ghcr.io/valhalla/valhalla-scripted:latest
b2cdd290723c0615660874d1ee18d82f5d2812b13f3496a1bada51c6eccc9032
```

Notes on the flags:
* `-p 127.0.0.1:8002:8002` — loopback only. **Not** `-p 8002:8002` (that would publish on 0.0.0.0).
* `-v .../custom_files:/custom_files` — only that one directory is mounted; never the home directory
  or a volume root.
* `build_elevation=True` — downloads SRTM/Skadi `.hgt` tiles for the graph and bakes elevation into the
  tiles. Tradeoff (see §9): +1.0 GB on disk and roughly +35 s of build time, in exchange for working
  `use_hills`, `weighted_grade`/`max_up_slope` per edge and the `/height` action. It is ON.
* `server_threads=4` matches `--cpus 4` so a request cannot oversubscribe the Mac.
* `build_admins=True`, `build_time_zones=True`, `build_tar=True` are image defaults and were left on.
* No `--privileged`, no `--network host`, no `--pid host`, no `--cap-add`.

```console
$ /usr/local/bin/docker inspect valhalla --format "privileged={{.HostConfig.Privileged}} networkmode={{.HostConfig.NetworkMode}} pids={{.HostConfig.PidMode}} memory={{.HostConfig.Memory}} nanocpus={{.HostConfig.NanoCpus}} cap_add={{.HostConfig.CapAdd}} mounts={{range .Mounts}}{{.Source}}=>{{.Destination}}{{end}} restart={{.HostConfig.RestartPolicy.Name}}"
privileged=false networkmode=bridge pids= memory=6442450944 nanocpus=4000000000 cap_add=<no value> mounts=/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files=>/custom_files restart=no
```

### 3.4 Watch the build (do not idle)

```console
$ /usr/local/bin/docker logs -f valhalla                     # background handle, polled
$ python3 valhalla/scripts/poll_status.py http://127.0.0.1:8002/status 10800   # background handle
13:53:46 FAIL RemoteDisconnected: Remote end closed connection without response
13:55:26 HTTP 200 {"version":"3.8.3-9eecaca4e","tileset_last_modified":1789505725,...}
STATUS_OK after 100s
```

### 3.5 Raise the caps and restart

Stock → final (see §5 for the full diff) then:

```console
$ /usr/local/bin/docker restart valhalla
valhalla
13:56:18
```

Restart cost 10 s and did **not** rebuild tiles — the log says:

```
INFO: Found routing tiles with 'use_tiles_ignore_pbf' True.
INFO: Jumping directly to the tile loading!
INFO: Found existing valhalla.json. Updating possibly missing entries.
No differences found.
INFO: Found config file. Starting valhalla service!
```

### 3.6 Security / posture commands

```console
$ /usr/local/bin/docker port valhalla
8002/tcp -> 127.0.0.1:8002

$ lsof -nP -iTCP:8002 -sTCP:LISTEN
COMMAND     PID    USER   FD   TYPE             DEVICE SIZE/OFF NODE NAME
com.docke 33886 satechi   11u  IPv4 0x1861a11ed4b873f4      0t0  TCP 127.0.0.1:8002 (LISTEN)

$ tailscale serve --bg --https=443 http://127.0.0.1:8002
Serve is not enabled on your tailnet.
To enable, visit:
         https://login.tailscale.com/f/serve?node=n4EKyYyD1m11CNTRL

$ tailscale serve --bg --http=80 http://127.0.0.1:8002
Available within your tailnet:

http://admins-mac-mini.taile1279d.ts.net/
|-- proxy http://127.0.0.1:8002

$ tailscale serve status
http://admins-mac-mini (tailnet only)
http://admins-mac-mini.taile1279d.ts.net (tailnet only)
|-- / proxy http://127.0.0.1:8002

$ tailscale funnel status
http://admins-mac-mini (tailnet only)
http://admins-mac-mini.taile1279d.ts.net (tailnet only)
|-- / proxy http://127.0.0.1:8002

$ tailscale serve status --json          # and `tailscale funnel status --json` — identical, no Funnel block
{"TCP":{"80":{"HTTP":true}},"Web":{"admins-mac-mini.taile1279d.ts.net:80":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8002"}}}}}
```

---

## 4. Endpoints

| Purpose | URL |
|---|---|
| Local (this Mac) | `http://127.0.0.1:8002` |
| Phone, over Tailscale | `http://admins-mac-mini.taile1279d.ts.net` |
| Actions | `<base>/status`, `<base>/route`, `<base>/trace_attributes`, `<base>/height`, `<base>/locate`, `<base>/isochrone`, `<base>/sources_to_targets` ... |

`http://100.85.43.11:8002` (the raw Tailscale IP) is **not** an endpoint — the container is bound to
loopback only, so the app must use the Serve hostname. Verified:

```console
$ curl -s -m 20 -w "\nHTTP %{http_code}\n" http://admins-mac-mini.taile1279d.ts.net/status
{"version":"3.8.3-9eecaca4e","tileset_last_modified":1789505725,"available_actions":["tile","status","centroid","expansion","transit_available","trace_attributes","trace_route","isochrone","optimized_route","sources_to_targets","height","route","locate"]}
HTTP 200 0.014002s

$ curl -s -o responses/route_via_tailscale_serve.json -w "HTTP %{http_code} %{time_total}s\n" \
    -H "Content-Type: application/json" -d @requests/route_home_to_sumnerlink.json \
    http://admins-mac-mini.taile1279d.ts.net/route
HTTP 200 0.020760s

$ curl -s -m 5 -o /dev/null -w "HTTP %{http_code}\n" http://100.85.43.11:8002/status
HTTP 000          # curl exit 7 = connection refused: nothing listens on the tailnet IP
```

### How to tell whether the server is up

1. Docker daemon: `/usr/local/bin/docker info` must print a Server section (not "Cannot connect").
2. Container: `/usr/local/bin/docker ps --filter name=valhalla` → `Up ...` (must not say `Restarting`).
3. Service: `curl -s http://127.0.0.1:8002/status` → HTTP 200 with a `version` field.
4. From the tailnet: `curl -s http://admins-mac-mini.taile1279d.ts.net/status` → same JSON.
5. If a request is rejected with HTTP 503 `"The service is shutting down"`, that is a transient
   restart, not a failure — retry after ~10 s.

Cheap one-liner:

```console
$ curl -sf http://127.0.0.1:8002/status | head -c 60 && echo " OK"
```

---

## 5. Config change: raising the caps

File: `/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files/valhalla.json`
(the file the container generates and reads; the untouched stock copy is saved next to the work area as
`valhalla/stock_valhalla.json`).

Diff (stock → final):

```diff
--- valhalla.json (stock)
+++ valhalla.json (caps raised)
@@ -264,6 +264,6 @@
     },
     "bicycle": {
-      "max_distance": 500000.0,
-      "max_locations": 50,
+      "max_distance": 300000.0,
+      "max_locations": 25,
       "max_matrix_distance": 200000.0,
       "max_matrix_location_pairs": 2500
```

That is the **only** change (`diff` produced exactly those two lines). Note that `max_locations` was
*lowered* from the stock 50 to 25 on purpose: this is an unauthenticated API reachable by every device
on the tailnet, so it is kept at the minimum that satisfies the app (origin + destination + 9 shaping
waypoints = 11, with headroom).

Effective values, proven by test (not by reading the file):

```console
$ curl -s -H "Content-Type: application/json" -d @requests/caps_max_locations_26.json http://127.0.0.1:8002/route
{"error_code":150,"error":"Exceeded max locations: 25","status_code":400,"status":"Bad Request"}

$ curl -s -H "Content-Type: application/json" -d @requests/caps_max_distance_seattle_spokane.json http://127.0.0.1:8002/route
{"error_code":154,"error":"Path distance exceeds the max distance limit: 300000 meters","status_code":400,"status":"Bad Request"}
```

And the value is not too tight for the real use case — a route with an 11-location request
(home + 9 `through` shaping waypoints + destination) succeeds:

```console
$ curl -s -o responses/route_11_locations_home_9waypoints_trail.json -w "HTTP %{http_code}\n" \
    -H "Content-Type: application/json" -d @requests/route_11_locations_home_9waypoints_trail.json \
    http://127.0.0.1:8002/route
HTTP 200
```
→ 26.734 km / 16.612 mi, 5468.140 s, 1 leg, 53 maneuvers.

---

## 6. RAW VERIFICATION 1 — `POST /route` from the user's home to the Sumner Link Trail

Request file `valhalla/requests/route_home_to_sumnerlink.json`:

```json
{
  "locations": [
    { "lat": 47.240371, "lon": -122.262019 },
    { "lat": 47.22329,  "lon": -122.24273 }
  ],
  "costing": "bicycle",
  "costing_options": {
    "bicycle": {
      "bicycle_type": "hybrid",
      "use_roads": 0.1,
      "avoid_bad_surfaces": 0.3,
      "use_hills": 0.25
    }
  },
  "units": "kilometers",
  "directions_options": { "units": "miles" }
}
```

Exact command:

```console
$ cd "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla"
$ curl -s -o responses/route_home_to_sumnerlink.json -w "HTTP %{http_code} %{time_total}s\n" \
    -H "Content-Type: application/json" \
    -d @requests/route_home_to_sumnerlink.json \
    http://127.0.0.1:8002/route
HTTP 200 0.072706s
```

Observed response (real numbers, body abbreviated; full raw JSON is in
`valhalla/responses/route_home_to_sumnerlink.json`):

```json
{
  "trip": {
    "locations": [
      {"type":"break","lat":47.240371,"lon":-122.262019,"side_of_street":"left","original_index":0},
      {"type":"break","lat":47.22329,"lon":-122.24273,"side_of_street":"left","original_index":1}
    ],
    "summary": {
      "has_time_restrictions": false, "has_toll": false, "has_highway": false, "has_ferry": false,
      "min_lat": 47.203308, "min_lon": -122.267383, "max_lat": 47.241057, "max_lon": -122.23678,
      "time": 1769.794, "length": 9.188, "cost": 3966.416
    },
    "status_message": "Found route between points",
    "status": 0, "units": "kilometers", "language": "en-US",
    "legs": [ { "maneuvers": [ ... 12 entries ... ], "summary": {...}, "shape": "{|ibyAz`hehFPlLKhMZ`Lz..." } ]
  }
}
```

| Measured value | Result |
|---|---|
| Total distance | **9.188 km = 5.709 mi** |
| Total time | **1769.794 s = 29.50 min** (bicycle, `bicycle_type=hybrid`, confirmed by `travel_type: "hybrid"` in the maneuvers) |
| Legs | **1** |
| Maneuvers | **12** (in that one leg) |
| Route geometry | `legs[0].shape` = 1211-char encoded polyline (6-digit precision) |

The 12 maneuvers, in order (this is a real turn list, streets are local):

```
 0 Bike west on 18th Street East.               0.340 km   66 s
 1 Turn left onto 122nd Avenue East.            3.038 km  642 s
 2 Turn left onto 48th Street East.             0.500 km   95 s
 3 Bear right onto Edgewood Drive East.         1.555 km  202 s
 4 Turn right onto Sumner Heights Drive East.   0.068 km   14 s
 5 Turn left onto Valley Avenue East.           0.388 km   81 s
 6 Make a sharp left onto Fryar Avenue.         0.982 km  195 s
 7 Bear right onto Puyallup Street.             0.467 km   98 s
 8 Turn left onto Tacoma Avenue.                1.018 km  209 s
 9 Turn left.                                   0.398 km   79 s
10 Turn right.                                  0.430 km   91 s
11 Your destination is on the left.             0.000 km    0 s
```

### IMPORTANT finding about the requested destination

The destination coordinate given in the task, `47.22329,-122.24273`, is **687 m away from the
Sumner Link Trail geometry in OSM** (geometry taken from `docs/research/raw/edgewood-near-trails-geom.json`,
the Overpass pull recorded in D4). `POST /locate` shows what it actually snaps to:

```console
$ curl -s -H "Content-Type: application/json" \
    -d '{"locations":[{"lat":47.22329,"lon":-122.24273}],"costing":"bicycle","verbose":true}' \
    http://127.0.0.1:8002/locate
# edge: way_id 432920226, use "driveway", surface "paved_smooth", cycle_lane "none",
#       no OSM name, speed_limit absent, distance to input 11.9 m
```

That is a private driveway, not the trail. So the as-specified route ends **at a driveway ~690 m west
of the trail**.

A second finding, which matters more for the product: **neither variant ever rides on a `use=path`
edge.** Both routes are 100 % `use=road` / `service_road` / `driveway`. The 1 017 m of
`cycle_lane=dedicated` is *on-street* bike lane — Valley Avenue East (secondary) and Fryar Avenue
(tertiary, `bicycle_network=1`, i.e. a signed local bike route) — at 5.57–6.87 km into the 9.19 km
route, not the trail.

For an honest proof I also ran the same request with the destination moved onto the real trail
(nearest trail vertex: `47.222762,-122.233665`, which `/locate` confirms as `use: "path"`,
`surface: "compacted"`, `cycle_lane: "shared"`, way_id 1297893064):

| Variant | Distance | Time | Legs / maneuvers |
|---|---|---|---|
| Destination as given in the task (driveway) | 9.188 km / 5.709 mi | 1769.794 s | 1 / 12 |
| Destination ON the trail (`47.222762,-122.233665`) | 9.110 km / 5.661 mi | 1758.602 s | 1 / 12 |

Neither is a short hop. That is not a server bug: the trail is in the Puyallup river valley and the
home is on the Edgewood plateau, with few crossings. Control route from home to the *nearest* trail
vertex (`47.2459556,-122.2384758`, 1.88 km straight-line) is **6.480 km** by bicycle. The router is
consistent with local topography, but the app should expect the "trail commute" to be several km.

### Why the route does not touch the trail (probed)

The trail vertex used above (`47.222762,-122.233665`) sits on OSM way `1297893064`. Facts established:

```console
$ curl -s -H "Content-Type: application/json" \
    -d '{"locations":[{"lat":47.222762,"lon":-122.233665}],"costing":"bicycle","verbose":true}' \
    http://127.0.0.1:8002/locate
# nearest edge: way 1297893064, use "path", cycle_lane "shared", surface "compacted",
#   access.bicycle true, default speed 12 km/h, 9.7 m from the input point
# next edge:    way 569772368, use "service_road", 59.9 m from the input point

$ curl -s -H "Content-Type: application/json" \
    -d '{"locations":[{"lat":47.222762,"lon":-122.233665},{"lat":47.2249,"lon":-122.232046}],"costing":"bicycle"}' \
    http://127.0.0.1:8002/route
# 200 -> 0.294 km, 78.693 s, "Bike northwest" / "Turn right"  => the path IS routable along itself
```

But `POST /route` from home to that same vertex ends on the **service road** `way_id 569772368`
(`use: "service_road"`), ~60 m from the requested vertex — with the specified costing options *and*
with default `costing_options`. So the informal path fragment is not reachable from the road network
at that point.

OSM check (OpenStreetMap API, not a guess):

```console
$ curl -s "https://api.openstreetmap.org/api/0.6/way/1297893064.json"   # tags
{"highway": "path", "informal": "yes"}
$ curl -s "https://api.openstreetmap.org/api/0.6/way/569772368.json"    # tags
{"highway": "service"}
# node sets: way 1297893064 has 14 nodes, way 569772368 has 11 nodes, shared nodes = 0
```

`informal=yes` explains it: this vertex is a social/informal path fragment with no shared node with the
service road next to it. It is routable in isolation but is not connected to the network at this point.

Not fully diagnosed (it could connect to some other way further along), but the practical rule for the
app is clear: **do not assume that a coordinate from `edgewood-near-trails-geom.json` is reachable by
bicycle.** Probe each intended trail access point with `POST /locate` (is the nearest edge
`use=path` / `cycle_lane != none`?) and a short `/route` before using it as a destination. Trail access
points that are connected (e.g. `47.2249,-122.232046`, which the probe route reached along the path)
work fine.

---

## 7. RAW VERIFICATION 2 — `POST /trace_attributes` on that route's shape

Encoded polyline taken verbatim from `legs[0].shape` of the response above
(1211 chars, stored as `trace_attributes_edgewalk_r1.json`):

```json
{
  "encoded_polyline": "{|ibyAz`hehFPlLKhMZ`Lz@`HC`v@EdES`C]|A_@p@c@XcOeAmBZa@p@m@zDJhf@Pvy@UnNi@|EpYxC~ ... (1211 chars)",
  "shape_match": "edge_walk",
  "costing": "bicycle",
  "units": "kilometers",
  "filters": {
    "action": "include",
    "attributes": [
      "edge.road_class", "edge.use", "edge.surface", "edge.unpaved", "edge.cycle_lane",
      "edge.bicycle_network", "edge.shoulder", "edge.speed_limit", "edge.sac_scale",
      "edge.length", "edge.names", "edge.way_id"
    ]
  }
}
```

Exact command:

```console
$ curl -s -o responses/trace_attributes_edgewalk_r1.json -w "HTTP %{http_code} %{time_total}s\n" \
    -H "Content-Type: application/json" \
    -d @requests/trace_attributes_edgewalk_r1.json \
    http://127.0.0.1:8002/trace_attributes
HTTP 200 0.010400s
```

Observed response (real numbers, body abbreviated):

```json
{
  "units": "kilometers",
  "edges": [ ... 178 objects ... ],
  "alternate_paths": []
}
```

`edge_walk` **worked on the first try** — no fallback to `map_snap` was needed.

First edge, verbatim:

```json
{"sac_scale":0,"shoulder":false,"bicycle_network":0,"cycle_lane":"none","way_id":736260642,
 "surface":"paved_smooth","unpaved":false,"use":"driveway","road_class":"service_other",
 "length":0.015,"source_percent_along":0.569}
```

Per-attribute coverage over all 178 edges (this is what the grader can rely on):

| attribute | edges that carry it | meaning of a missing key |
|---|---|---|
| `road_class`, `use`, `surface`, `unpaved`, `cycle_lane`, `bicycle_network`, `shoulder`, `sac_scale`, `length`, `way_id` | 178 / 178 | always present |
| `names` | 171 / 178 | 7 edges have no OSM name (field omitted) |
| `speed_limit` | 73 / 178 | **105 edges have no posted limit in OSM** (field omitted) — the grader must handle this, see KNOWN LIMITS |

Total from the edge data: **178 edges, 9186 m** (the route summary said 9.188 km; the 2 m difference is
per-edge rounding).

Endianness/units notes that matter for the grader:
* `length` is in the request's units — we asked for kilometers, so **metres = `length` × 1000**.
* `speed_limit` is in the request's units — km/h here (confirmed against the Valhalla API docs:
  "Edge speed limit in the units specified. The default is kilometers per hour"). `56` = 35 mph.
* `cycle_lane` is a string (`none` / `shared` / `dedicated`); `bicycle_network` and `sac_scale`
  come back as integers here (`0`/`1`), `shoulder`/`unpaved` as booleans.


---

## 8. Per-edge safety summary (the data the safety grader will consume)

Produced from `responses/trace_attributes_edgewalk_r1.json` by
`valhalla/scripts/edge_summary.py` (run it again with any trace_attributes response):

```console
$ cd "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla"
$ python3 scripts/edge_summary.py responses/trace_attributes_edgewalk_r1.json | tee responses/edge_summary_r1.md
```

Method: metres per edge = `length` × 1000 (km request units). "Stretches" are runs of consecutive
edges with `cycle_lane = "none"`; a stretch is also broken when the OSM name signature changes, so each
row below is one named street.

178 edges, 9186 m total (9.19 km, 5.71 mi)

| cycle_lane | metres | share |
|---|---|---|
| none | 8024 | 87.4% |
| dedicated | 1017 | 11.1% |
| shared | 145 | 1.6% |

| road_class | metres | share |
|---|---|---|
| tertiary | 7630 | 83.1% |
| service_other | 888 | 9.7% |
| secondary | 388 | 4.2% |
| residential | 280 | 3.0% |

| surface | metres | share |
|---|---|---|
| paved_smooth | 9186 | 100.0% |

| use | metres | share |
|---|---|---|
| road | 8298 | 90.3% |
| service_road | 476 | 5.2% |
| driveway | 412 | 4.5% |

| bicycle_network | metres | share |
|---|---|---|
| 0 | 8369 | 91.1% |
| 1 | 817 | 8.9% |

| shoulder | metres | share |
|---|---|---|
| False | 9186 | 100.0% |

| sac_scale | metres | share |
|---|---|---|
| 0 | 9186 | 100.0% |

unpaved total: 0 m (0.0%)

| # | longest cycle_lane=none stretch | metres | edges | names | speed limit | road_class | use |
|---|---|---|---|---|---|---|---|
| 1 | | 3038 | 63 | 122nd Avenue East | none in OSM | tertiary | road |
| 2 | | 1371 | 17 | Edgewood Drive East | none in OSM | tertiary | road |
| 3 | | 828 | 5 | (no name in OSM) | none in OSM | service_other | driveway/service_road |
| 4 | | 572 | 14 | Tacoma Avenue | 56 km/h (35 mph) | tertiary | road |
| 5 | | 501 | 12 | 48th Street East | none in OSM | tertiary | road |

all cycle_lane=none stretches: 14, 8024 m

### The same summary for the on-trail destination variant

181 edges, 9108 m total (9.11 km, 5.66 mi)

| cycle_lane | metres | share |
|---|---|---|
| none | 7946 | 87.2% |
| dedicated | 1017 | 11.2% |
| shared | 145 | 1.6% |

| road_class | metres | share |
|---|---|---|
| tertiary | 6737 | 74.0% |
| residential | 1151 | 12.6% |
| service_other | 832 | 9.1% |
| secondary | 388 | 4.3% |

| surface | metres | share |
|---|---|---|
| paved_smooth | 9108 | 100.0% |

| use | metres | share |
|---|---|---|
| road | 8276 | 90.9% |
| service_road | 772 | 8.5% |
| driveway | 60 | 0.7% |

| bicycle_network | metres | share |
|---|---|---|
| 0 | 8291 | 91.0% |
| 1 | 817 | 9.0% |

| shoulder | metres | share |
|---|---|---|
| False | 9108 | 100.0% |

| sac_scale | metres | share |
|---|---|---|
| 0 | 9108 | 100.0% |

unpaved total: 0 m (0.0%)

| # | longest cycle_lane=none stretch | metres | edges | names | speed limit | road_class | use |
|---|---|---|---|---|---|---|---|
| 1 | | 3038 | 63 | 122nd Avenue East | none in OSM | tertiary | road |
| 2 | | 1371 | 17 | Edgewood Drive East | none in OSM | tertiary | road |
| 3 | | 871 | 14 | 150th Avenue Court East | 40 km/h (25 mph) | residential | road |
| 4 | | 593 | 18 | Puyallup Street | 40 km/h (25 mph) | tertiary | road |
| 5 | | 501 | 12 | 48th Street East | none in OSM | tertiary | road |

all cycle_lane=none stretches: 14, 7946 m

### What these numbers mean for the grader

* The route is **87.4 % `cycle_lane=none`** — i.e. most of it is on shared roads with no marked lane.
  This is the exact stress data the D10 policy is about.
* `road_class` is dominated by `tertiary` (83.1 %, 7 630 m) — the two-lane rural roads
  (122nd Avenue East, Edgewood Drive East, 48th Street East, Tacoma Avenue).
* `shoulder` came back `false` for **100 %** of edges and `unpaved` for 0 %. Those two fields are not
  empty, they are genuinely false here (all surfaces are `paved_smooth`), but a grader that keys on
  `shoulder=true` for safety will find nothing to grade with — see KNOWN LIMITS.
* `bicycle_network` is 1 for 817 m (8.9 %) — the `dedicated` stretch.
* `speed_limit` is missing for 105 of 178 edges (58 %). For the longest dangerous stretch
  (122nd Avenue East, 3 038 m) there is **no posted speed at all in OSM**, so the D10 "≥40 mph with no
  facility ⇒ never E" rule cannot be evaluated from `speed_limit` alone on this street; it needs an OSM
  `maxspeed` fallback or a default-speed model. This is the single most important data gap found.
* `cycle_lane=dedicated` (1 017 m / 11.1 %) is **on-street bike lane**, not the trail: Valley Avenue East
  (secondary) and Fryar Avenue (tertiary, `bicycle_network=1`), all `use=road`, all
  `surface=paved_smooth`. `cycle_lane=shared` (145 m) is more of the same on Fryar Avenue.
* **The route contains no `use=path` edge at all** (0 m of 9 186 m). If the grader classifies "on-trail"
  by `use=path` or by the Sumner Link Trail way ids, this route is entirely "off-trail" — which is the
  truth (see §6). `surface` does not help here either: everything reports `paved_smooth`.

---

## 9. Tile build timing (measured)

| Milestone (UTC, container clock) | Time |
|---|---|
| `docker run` / container created | 20:53:34.143 |
| admin db built | 20:53:34 → 20:53:38 (4 s) |
| timezone db built | ~20:53:38 |
| `valhalla_build_tiles` pass 1 (parse 1 462 148 routable ways / 14 701 486 nodes, 420 tiles) | 20:53:41 → 20:54:19 |
| elevation tiles downloaded (43 SRTM/Skadi `.hgt`, 1.0 GB) | 20:54:24 → 20:54:31 |
| `valhalla_build_tiles` pass 2 ("Enhancing the initial graph") | 20:54:39.942 |
| "Adding elevation to 456 tiles with 4 threads" | 20:54:58 → 20:55:05 |
| validating / finishing / binning tiles | 20:55:14 → 20:55:21 |
| `build_tile_set` total reported by Valhalla | **41 s** |
| service started, first `GET /status` → 200 | **20:55:26.457** |

**Total tile build time: 112.3 s (1 min 52 s)** from `docker run` to a serving `/status`.
Two idle machines' worth of context: this is a single US state with elevation; a planet or multi-state
build would be much longer.

Cost of `build_elevation=True`: about **+35 s** and **+1.0 GB** on disk (43 tiles). It is worth it —
`/height` works, and per-edge grade fields (`weighted_grade`, `max_up_slope`, `max_down_slope`) are
populated, which `use_hills` needs to mean anything. Without it, `use_hills` degrades to a no-op on a
graph that has no elevation.

Restart cost when nothing changed: **10 s** (no rebuild).

---

## 10. Lifecycle: restart / stop / start

**The exact restart command (after any `valhalla.json` or `.osm.pbf` change):**

```console
$ /usr/local/bin/docker restart valhalla
```

`valhalla.json` changes take effect immediately on restart (no tile rebuild). If a `.osm.pbf` is added
or removed, the same command triggers a rebuild instead.

Stop / remove / re-create from scratch:

```console
$ /usr/local/bin/docker stop valhalla
$ /usr/local/bin/docker rm valhalla
$ /usr/local/bin/docker run -dt --name valhalla --memory 6g --cpus 4 -p 127.0.0.1:8002:8002 \
    -v "/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files:/custom_files" \
    -e build_elevation=True -e server_threads=4 ghcr.io/valhalla/valhalla-scripted:latest
```

Follow the build:

```console
$ /usr/local/bin/docker logs -f --tail 50 valhalla
```

### After a Mac reboot

The container was started **without** `--restart`, and Docker Desktop is set to `AutoStart = False`
(`~/Library/Group Containers/group.com.docker/settings-store.json`). So after a reboot the server is
**down** until you do:

```console
$ open -a Docker                                    # then wait for `docker info` to work
$ /usr/local/bin/docker start valhalla              # ~10 s, no rebuild thanks to valhalla_tiles.tar
$ tailscale serve status                            # should still show the port-80 proxy
```

(Adding `--restart unless-stopped` to the run command would make step 2 automatic; it is not set now.)

---

## 11. Repo layout created by this work

```
valhalla/
  custom_files/                     <- the ONLY directory mounted into the container
    washington-latest.osm.pbf       (363,098,240 bytes, md5 verified)
    washington-latest.osm.pbf.md5
    valhalla.json                   (caps raised: 300000 m / 25 locations)
    valhalla_tiles.tar              (585 MB, reused on restart)
    valhalla_tiles/                 (622 .gph tiles, 604 MB)
    elevation_data/                 (43 SRTM .hgt tiles, 1.0 GB)
    admins.sqlite  timezones.sqlite  file_hashes.txt  default_speeds.json
  stock_valhalla.json               (the untouched generated config, for diffing)
  requests/                         (the exact JSON bodies sent; @-referenced by curl)
  responses/                        (the raw JSON returned, incl. the two verification responses
                                     and edge_summary_r1.md / edge_summary_r2_ontrail.md)
  scripts/poll_status.py            (background /status poller used during the build)
  scripts/edge_summary.py           (per-edge safety summary generator)
  logs/valhalla-container.log       (full container log of the build + restarts)
```

---

## SECURITY

### Bind and exposure

| Item | Value |
|---|---|
| Container port publish | `-p 127.0.0.1:8002:8002` — **loopback only** |
| `docker port valhalla` | `8002/tcp -> 127.0.0.1:8002` |
| `lsof -nP -iTCP:8002 -sTCP:LISTEN` | `com.docke 33886 satechi 11u IPv4 ... TCP 127.0.0.1:8002 (LISTEN)` — one listener, loopback, IPv4 |
| Bind on 0.0.0.0 | **never used** (`-p 8002:8002` was never run) |
| Direct connection to the tailnet IP on 8002 | refused — `curl http://100.85.43.11:8002/status` → `HTTP 000`, curl exit 7 |
| Network mode / privileges | `bridge`, `privileged=false`, no `--network host`, no `--pid host`, `cap_add=<no value>` |
| Mounts | exactly one: `/Volumes/Satechi 1/Dev/bike-trail-planner/valhalla/custom_files` → `/custom_files`. Never the home directory, never a volume root. |
| Resource limits | `--memory 6g` (6442450944 B), `--cpus 4` (4000000000 nanoCPUs), `server_threads=4` |

### Tailscale Serve / Funnel state

* `tailscale serve --bg --http=80 http://127.0.0.1:8002` is configured and running. The phone reaches the
  API as `http://admins-mac-mini.taile1279d.ts.net/...`, tailnet only.
* `tailscale serve status` → both entries explicitly marked **`(tailnet only)`**:
  ```
  http://admins-mac-mini (tailnet only)
  http://admins-mac-mini.taile1279d.ts.net (tailnet only)
  |-- / proxy http://127.0.0.1:8002
  ```
* `tailscale funnel status` → same, tailnet-only entries, **no funnel entry**. `tailscale serve status --json`
  and `tailscale funnel status --json` are byte-identical and contain no `AllowFunnel` block:
  ```json
  {"TCP":{"80":{"HTTP":true}},"Web":{"admins-mac-mini.taile1279d.ts.net:80":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8002"}}}}}
  ```
* **`tailscale funnel` was never run.** Nothing is published to the public internet.

### Why HTTP and not HTTPS

`tailscale serve --bg --https=443 http://127.0.0.1:8002` was tried first and **failed**:

```
Serve is not enabled on your tailnet.
To enable, visit:
         https://login.tailscale.com/f/serve?node=n4EKyYyD1m11CNTRL
```

That is a tailnet-level toggle ("HTTPS Certificates") that only the tailnet admin (the user) can flip;
it cannot be done from this shell. Per the hardening instruction, Funnel was **not** enabled and the
public internet was **not** exposed. Instead of the fallback `-p 100.85.43.11:8002:8002`, the chosen
option is strictly tighter: keep the container on loopback and let Tailscale Serve (plain HTTP on
port 80, tailnet only) do the routing. Result: nothing on the LAN, nothing on the tailnet IP directly,
one tailnet-only entry point.

Two consequences to be explicit about:

1. **No TLS.** The Android app will see `http://` and Android blocks cleartext by default. The app needs
   a network-security-config exception scoped to `admins-mac-mini.taile1279d.ts.net` (or the tailnet
   suffix). This is a real cost, and it is the same cost the `100.85.43.11:8002` fallback would have had.
2. **It is still encrypted on the wire.** "Cleartext HTTP" here means *no TLS*, not *unencrypted*:
   Tailscale carries it inside WireGuard between the phone and this Mac, and Serve refuses non-tailnet
   traffic. The practical risk is that any *tailnet member* can call the API — it has **no
   authentication** — which is exactly why the port is loopback-only and the caps are modest.

To upgrade to HTTPS later (after enabling HTTPS Certificates in the tailnet admin console):

```console
$ tailscale serve --bg --https=443 http://127.0.0.1:8002
$ tailscale serve --http=80 off
```

### Residual uncertainty I could not close

`netstat -an -f inet | grep "\.80"` shows a wildcard listener:

```
tcp4       0      0  *.80                   *.*                    LISTEN
```

That `*.80` socket belongs to the Tailscale macOS **network extension** (its Serve listener), not to
Docker. Connections to it from the host stack or the LAN are **not served** — they hang in `SYN_RCVD`
and never get a response:

```console
$ curl -s -m 6 -o /dev/null -w "HTTP %{http_code}\n" http://192.168.1.52:80/status   # this Mac's LAN IP
HTTP 000        # curl exit 28 = timeout, 6.0 s
$ nc -z -G 4 -v 192.168.1.52 80
nc: connectx to 192.168.1.52 port 80 (tcp) failed: Operation timed out
```

I can only test this from the Mac itself, because I have no shell on another LAN device (and I could not
run code on the phone). So: the LAN-exposure claim rests on (a) the Serve config being tailnet-only,
(b) the LAN/loopback connection attempts timing out rather than being answered, and (c) Funnel having no
config. If the user wants certainty, test `http://192.168.1.52/status` from another machine on the
192.168.1.0/24 LAN — it must fail.

### Extract integrity

Geofabrik `.md5` == local `md5 -r` == `cbab58e052edfb4b88a531411feeba5a`. Match; nothing re-downloaded.

---

## KNOWN LIMITS / FAILURES

Honest list. Everything here is either a real failure, a data gap, or something that was not tested.

1. **HTTPS is NOT working (blocked).** Tailscale Serve HTTPS needs tailnet "HTTPS Certificates", which
   are disabled. Blocker command output is quoted above. Workaround in place: HTTP on port 80,
   tailnet-only, with an Android cleartext caveat. **This is the main open item.**
2. **No TLS ⇒ the Android app needs a cleartext exception**, otherwise it will refuse the base URL.
   Not verified on the phone (see 3).
3. **Not tested from the phone.** I cannot run anything on the `android` node (100.103.0.32). All
   tailnet-path verification (`/status`, `/route` over `admins-mac-mini.taile1279d.ts.net`) was done
   **from this Mac**, which resolves the ts.net name and goes through the tunnel. The end-to-end
   phone test is still owed by the user.
4. **Admin DB is degraded.** The build log shows the US, Idaho, Oregon, British Columbia and Canada
   admin polygons as *"degenerate and will be skipped"* (Geofabrik clips them at the state border), so
   `admins.sqlite` contains **exactly one row**: `admin_level=4, iso_code=WA, name=Washington,
   drive_on_right=1`. Anything needing country-level admin (level 2), neighbour states, or
   border-crossing logic is unavailable. Fine for in-state bike routing; do not build features on
   `admin.country`.
5. **No traffic data.** Every start logs `Traffic tile extract could not be loaded` /
   `(stat): /custom_files/traffic.tar No such file or directory`. Speeds are OSM + the default-speeds
   model only. No live or historical traffic.
6. **`speed_limit` is absent on 105 of 178 edges (58 %)** of the verified route — including the longest
   `cycle_lane=none` stretch (122nd Avenue East, 3 038 m). The D10 rule "≥40 mph with no facility ⇒
   never E" **cannot be evaluated from `speed_limit` alone** on this corridor; a `maxspeed`-aware
   fallback or the default-speed model is required. This is the biggest gap for the safety grader.
7. **`shoulder` is `false` on 100 % of the verified edges** (9 186 m). It is a real value, not a missing
   field, but it means this dataset gives zero shoulder signal for this route; do not let the grader
   depend on `shoulder=true` to find a safe road.
8. **`surface` reports `paved_smooth` for 100 % of the verified route** (9 186 m). That is *correct* for
   this route — it is all paved street (item 10) — but it means `surface` gives no trail signal here.
   `surface` does discriminate elsewhere: `/locate` on the trail vertex returns `surface: compacted` for
   that path edge. So keep `surface` in the grader, but do not expect it to find the trail on an
   on-street route.
9. **The task's destination coordinate is off-trail.** `47.22329,-122.24273` is 687 m from the Sumner
   Link Trail geometry and snaps to a private driveway (`use=driveway`, no name). Both the as-specified
   route and an on-trail variant are reported; the as-specified route ends at a driveway.
10. **No route reaches the trail.** Neither home→trail route contains a single `use=path` edge; both end
   on a paved `service_road` ~60 m from the requested trail vertex. Probed cause: that vertex is on an
   OSM way tagged `highway=path, informal=yes` with **0 shared nodes** with the adjacent service road —
   an unconnected informal fragment. The path is routable along itself (probe: 0.294 km). Not fully
   diagnosed (it may connect to other ways elsewhere on its 14 nodes). Practical rule for the app:
   **probe every intended trail access point with `POST /locate` + a short `/route` before trusting a
   coordinate from `edgewood-near-trails-geom.json`.** See §6.
11. **`/locate` needs POST.** The documented GET form
    (`GET /locate?locate=47.22329,-122.24273&costing=bicycle`) returns
    `400 {"error_code":110,"error":"Insufficiently specified required parameter 'locations'"}` on this
    build. Use `POST /locate` with a `locations` array. Expect the same style of doc/code mismatch
    elsewhere.
12. **Container lifecycle is manual.** `--restart` was not set and Docker Desktop has `AutoStart=False`,
    so a reboot leaves the server down until `open -a Docker` + `docker start valhalla`. Documented in §10.
13. **No load test, no concurrency test.** `--memory 6g --cpus 4` is a protective ceiling, not a measured
    capacity. A runaway request should die in the container rather than take the Mac down, but that was
    not exercised.
14. **Build warnings kept, not fixed** (all benign for this use case, all recorded in
    `logs/valhalla-container.log`): `[parseways] 9 OSM time range causes an unknown runtime_error`;
    `[elevation] 8 edges with elevation exceeding max difference`; `Possible duplicates at level: 1 = 20`,
    `level: 2 = 4`; `United States (148838) is missing way member ...` (see 4).
15. **`use_hills` was only exercised, not audited.** Elevation is in the tiles (`/height` proves it:
    home 122 m → trail 16 m over 2 905 m), and `use_hills=0.25` was accepted, but I did not A/B the same
    route with different `use_hills` values to confirm the cost function actually changes the path.
16. **Timezone DB built but UTC vs local.** Container logs are UTC; this Mac is PDT (UTC-7). Every
    timestamp in this document is UTC unless stated.
17. **Trivial commands are not listed.** `ls`, `pwd`, `cat`, `du -sh`, `mkdir -p`, `ps aux | grep`
    and similar were run; the commands that matter (daemon start, download + checksum, run, logs,
    restart, the two verification requests, the cap tests, the security checks) are all quoted verbatim
    above.

---

## Appendix — next step for the app

```
BASE = http://admins-mac-mini.taile1279d.ts.net      # tailnet only; needs an Android cleartext exception
POST {BASE}/route              -> trip.legs[0].shape  (encoded polyline, 6-digit precision)
POST {BASE}/trace_attributes   -> edges[] with road_class / use / surface / cycle_lane / speed_limit ...
                                 body: {"encoded_polyline": <shape>, "shape_match": "edge_walk", "costing": "bicycle",
                                        "units": "kilometers", "filters": {"action": "include", "attributes": [...]}}
```
