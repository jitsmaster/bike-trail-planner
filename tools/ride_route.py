#!/usr/bin/env python3
"""Build a real 1-hour round trip (home -> Milton Interurban Trail -> home) and dump its RAW ROAD
LIST, so the ride can be replayed into the emulator and handed back to Google Maps.

Why a script and not the app: the rider asked for "raw data first as list of roads". This produces
exactly that - the ordered edges Valhalla actually matched - and nothing else. The app's job is to
record the same list from GPS and pick stops from it; this file is the reference answer.

Shape assembly matters and is deliberate
---------------------------------------
`/route` will NOT ride the Milton Interurban Trail: asked to go from the trail's north end to its
south end, Valhalla answers with Milton Way and 23rd Ave every time (measured, 2026-10-25), even
with the app's own bicycle costing. `trace_attributes` on the trail's own geometry, however, snaps
the whole 4.64 km to `way_id 315246010 "Milton Interurban Trail"` (use=cycleway, bicycle_network=1),
so the trail IS in the graph - the router just does not choose it between those two points.

So the trip is assembled from the pieces that are each individually true:

    home ──(Valhalla route)──► trail north end
    trail north end ──(OSM trail geometry)──► south end ──► back to north end
    trail north end ──(the Valhalla route, reversed)──► home

then the WHOLE assembled shape is handed to `trace_attributes` once. That single trace is the raw
road list, and it is what the app will produce from the recorded GPS.

Usage:
    python3 tools/ride_route.py                    # write build/sim/ride-shape.json + ride-roads.json
    python3 tools/ride_route.py --duration-s 3600  # target ride length (default one hour)
"""
import argparse
import json
import math
import os
import sys
import urllib.request

HOME = (47.240371, -122.2620198)          # 12424 18th St E, Edgewood, WA 98372
TRAIL_ASSET = os.path.join("app", "src", "main", "assets", "trails.json")
TRAIL_NAME = "Interurban Trail Milton"
OUT_DIR = os.path.join("build", "sim")

# The app's own bicycle costing (data/ValhallaClient.kt routeRequest), so the shape is the shape the
# app would plan. Defaults alone prefer Milton Way; these prefer paths and quiet roads.
COSTING_OPTIONS = {
    "bicycle": {
        "bicycle_type": "hybrid",
        "use_roads": 0.1,
        "avoid_bad_surfaces": 0.3,
        "use_hills": 0.25,
    }
}

EDGE_ATTRIBUTES = [
    "edge.way_id", "edge.names", "edge.road_class", "edge.use", "edge.surface",
    "edge.cycle_lane", "edge.bicycle_network", "edge.speed_limit", "edge.length",
]


# ------------------------------------------------------------------ polyline6 (Valhalla's encoding)

def decode_polyline(encoded, precision=6):
    factor = 10.0 ** precision
    index, lat, lon, out = 0, 0, 0, []
    while index < len(encoded):
        for target in ("lat", "lon"):
            shift, result = 0, 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else (result >> 1)
            if target == "lat":
                lat += delta
            else:
                lon += delta
        out.append((lat / factor, lon / factor))
    return out


def encode_polyline(points, precision=6):
    factor = 10.0 ** precision
    out, prev_lat, prev_lon = [], 0, 0

    def chunk(value):
        v = ~(value << 1) if value < 0 else (value << 1)
        s = ""
        while v >= 0x20:
            s += chr((0x20 | (v & 0x1F)) + 63)
            v >>= 5
        return s + chr(v + 63)

    for lat, lon in points:
        ilat, ilon = int(round(lat * factor)), int(round(lon * factor))
        out.append(chunk(ilat - prev_lat))
        out.append(chunk(ilon - prev_lon))
        prev_lat, prev_lon = ilat, ilon
    return "".join(out)


# ------------------------------------------------------------------ geometry helpers

def metres(a, b):
    r = 6371000.0
    la1, lo1 = math.radians(a[0]), math.radians(a[1])
    la2, lo2 = math.radians(b[0]), math.radians(b[1])
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def length(points):
    return sum(metres(a, b) for a, b in zip(points, points[1:]))


def dedupe_consecutive(points, tolerance_m=0.5):
    out = [points[0]]
    for p in points[1:]:
        if metres(out[-1], p) >= tolerance_m:
            out.append(p)
    return out


# ------------------------------------------------------------------ Valhalla

def post(base, path, body):
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def route(base, locations):
    body = {
        "locations": [{"lat": lat, "lon": lon} for lat, lon in locations],
        "costing": "bicycle",
        "costing_options": COSTING_OPTIONS,
        "units": "kilometers",
    }
    return post(base, "/route", body)


def trace_attributes(base, shape_points):
    """The raw road list: ordered edges with the OSM way id and name for each."""
    body = {
        "encoded_polyline": encode_polyline(shape_points),
        "shape_match": "edge_walk",
        "costing": "bicycle",
        "units": "kilometers",
        "filters": {"action": "include", "attributes": EDGE_ATTRIBUTES},
    }
    return post(base, "/trace_attributes", body)


def trace_with_fallback(base, shape_points):
    """SPEC section 12: edge_walk first, then map_snap. Report which one answered."""
    for mode in ("edge_walk", "map_snap"):
        body = {
            "encoded_polyline": encode_polyline(shape_points),
            "shape_match": mode,
            "costing": "bicycle",
            "units": "kilometers",
            "filters": {"action": "include", "attributes": EDGE_ATTRIBUTES},
        }
        try:
            result = post(base, "/trace_attributes", body)
        except Exception as exc:                                  # noqa: BLE001 - reported, not raised
            print(f"  {mode}: {exc}", file=sys.stderr)
            continue
        if result.get("edges"):
            return result, mode
    return {"edges": []}, "none"


# ------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--valhalla", default="http://127.0.0.1:8002")
    ap.add_argument("--duration-s", type=float, default=3600.0,
                    help="the ride length to aim for; the feed speed is derived from it")
    ap.add_argument("--out-dir", default=OUT_DIR)
    ap.add_argument("--no-trail", action="store_true",
                    help="route everything (Valhalla will use Milton Way, not the trail)")
    args = ap.parse_args()

    with open(TRAIL_ASSET) as fh:
        trails = json.load(fh)
    trail = next(t for t in trails if t["name"] == TRAIL_NAME)
    trail_points = [tuple(p) for p in trail["geometry"]]
    north_end, south_end = trail_points[0], trail_points[-1]
    print(f"trail: {TRAIL_NAME} {trail['length_km']:.3f} km, {len(trail_points)} points", file=sys.stderr)
    print(f"  north end {north_end}   south end {south_end}", file=sys.stderr)

    out_leg = route(args.valhalla, [HOME, north_end])
    out_shape = decode_polyline(out_leg["trip"]["legs"][0]["shape"])
    out_km = out_leg["trip"]["legs"][0]["summary"]["length"]
    print(f"out leg: {out_km:.2f} km, {len(out_shape)} points", file=sys.stderr)

    if args.no_trail:
        shape = out_shape + list(reversed(out_shape))
    else:
        shape = out_shape + trail_points + list(reversed(trail_points)) + list(reversed(out_shape))
    shape = dedupe_consecutive(shape)
    total = length(shape)
    speed = total / args.duration_s
    print(f"assembled: {total / 1000.0:.2f} km, {len(shape)} points "
          f"-> {speed:.3f} m/s gives exactly {args.duration_s / 60:.1f} min", file=sys.stderr)

    traced, mode = trace_with_fallback(args.valhalla, shape)
    edges = traced.get("edges", [])
    print(f"trace_attributes: {len(edges)} edges matched with {mode}", file=sys.stderr)

    # The raw road list: consecutive edges on the same way collapse into one road stretch, because a
    # road that the router split into three edges is still one road to the rider.
    roads, cursor = [], 0.0
    for edge in edges:
        names = edge.get("names") or []
        name = names[0] if names else "(unnamed)"
        length_m = float(edge.get("length", 0.0)) * 1000.0
        road = {
            "way_id": edge.get("way_id"),
            "name": name,
            "road_class": edge.get("road_class"),
            "use": edge.get("use"),
            "surface": edge.get("surface"),
            "cycle_lane": edge.get("cycle_lane"),
            "bicycle_network": edge.get("bicycle_network"),
            "speed_limit_kph": edge.get("speed_limit"),
            "start_m": round(cursor, 1),
            "length_m": round(length_m, 1),
        }
        cursor += length_m
        if roads and roads[-1]["way_id"] == road["way_id"] and roads[-1]["name"] == road["name"]:
            roads[-1]["length_m"] = round(roads[-1]["length_m"] + road["length_m"], 1)
        else:
            roads.append(road)

    os.makedirs(args.out_dir, exist_ok=True)
    shape_path = os.path.join(args.out_dir, "ride-shape.json")
    roads_path = os.path.join(args.out_dir, "ride-roads.json")
    payload = {
        "home": HOME,
        "trail": TRAIL_NAME,
        "trail_north_end": north_end,
        "trail_south_end": south_end,
        "distance_m": round(total, 1),
        "duration_s": args.duration_s,
        "speed_mps": round(speed, 4),
        "points": [[round(lat, 6), round(lon, 6)] for lat, lon in shape],
    }
    with open(shape_path, "w") as fh:
        json.dump(payload, fh, indent=1)
    with open(roads_path, "w") as fh:
        json.dump({"trace_mode": mode, "distance_m": round(cursor, 1), "roads": roads}, fh, indent=1)

    print(f"\nwrote {shape_path} and {roads_path}", file=sys.stderr)
    print(f"\nRAW ROAD LIST ({len(roads)} roads, {cursor / 1000.0:.2f} km matched)\n")
    print(f"{'#':>3}  {'at m':>7}  {'len m':>7}  {'way id':>11}  {'class':<14} {'use':<12} name")
    for i, road in enumerate(roads, 1):
        print(f"{i:>3}  {road['start_m']:>7.0f}  {road['length_m']:>7.0f}  {str(road['way_id']):>11}  "
              f"{str(road['road_class']):<14} {str(road['use']):<12} {road['name']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
