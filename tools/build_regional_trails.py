#!/usr/bin/env python3
"""Build the bundled REGIONAL trail dataset (DECISIONS.md D23, final answer (a)).

Bundles the regional OpenStreetMap bicycle / mountain-bike *route relations* for
Washington state + the Portland metro, with simplified geometry, so that
away-from-home planning has something offline to work with.

Inputs
  Washington : valhalla/custom_files/washington-latest.osm.pbf
               -- the OSM extract already on disk (the same one the self-hosted
               Valhalla server was built from). Read locally with pyosmium.
  Portland   : Overpass bbox query (45.0,-123.7 .. 46.0,-121.8, widened after the first
               box was measured to cut real trails in half). The raw response is cached
               under docs/discovery-raw/regional/ so the numbers are reproducible
               without re-querying.

Outputs
  docs/data/trails_regional.json  authoritative copy (assets/ is generated)
  docs/data/trails_regional.md    data note (counts, measurements, licence)

Build-time tool dependency: pyosmium (pip install osmium / uv pip install osmium).
The Android app gains NO new runtime dependency -- it only reads the JSON asset.

Run from the repo root:  python3 tools/build_regional_trails.py
"""
import argparse
import gzip
import hashlib
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

import numpy as np
import osmium

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WA_PBF_DEFAULT = os.path.join(ROOT, "valhalla", "custom_files", "washington-latest.osm.pbf")
RAW_DIR = os.path.join(ROOT, "docs", "discovery-raw", "regional")
OUT_JSON = os.path.join(ROOT, "docs", "data", "trails_regional.json")
OUT_MD = os.path.join(ROOT, "docs", "data", "trails_regional.md")

ROUTE_TYPES = ("bicycle", "mtb")

# Portland metro, widened after measuring truncation (S, W, N, E). The first box tried,
# 45.2..45.8 N / -123.2..-122.2 W, cut straight through real trails: Henry Hagg Lake sits at
# lon -123.25, so 21.1 km of a 21.5 km loop was clipped away, and the Banks-Vernonia State
# Trail was cut too. This box keeps the metro and the northern Willamette Valley.
PORTLAND_BBOX = (45.0, -123.7, 46.0, -121.8)
OVERPASS_MIRRORS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
OVERPASS_MAX_AGE_DAYS = 7.0     # rotate mirrors if timestamp_osm_base is older than this
OVERPASS_TIMEOUT_S = 300

def overpass_query(bbox=PORTLAND_BBOX):
    """Relations + their member ways with tags + every member node with coordinates.

    Two output statements on purpose: `.members out skel qt` carries the way -> node
    refs and the node coordinates, `way.members out tags qt` carries the way tags
    (needed to tell an off-street path from a signed on-street bike route).
    """
    s, w, n, e = bbox
    return (
        "[out:json][timeout:%d][bbox:%s,%s,%s,%s];\n"
        'rel["type"="route"]["route"~"^(bicycle|mtb)$"]->.routes;\n'
        ".routes out body;\n"
        ".routes > -> .members;\n"
        ".members out skel qt;\n"
        ".members out tags qt;\n"
        % (OVERPASS_TIMEOUT_S - 60, s, w, n, e)
    )

# Highway classes used to tell an off-street path from a signed on-street bike route.
# Measured in the data note: a large share of route=bicycle relations are on-street.
OFF_STREET_HIGHWAYS = frozenset(("cycleway", "path", "footway", "bridleway", "track",
                                 "pedestrian", "steps"))

# SAFETY GATE. The app grades trail geometry as Grade A by construction, with no traffic
# data (SafetyGrader.gradeTrailLeg, app/src/main/java/com/biketrailplanner/domain/
# SafetyGrader.kt:236, hard-codes Grade.A and onTrailGeometry = true). That is only sound
# for a genuine off-road path. A route=bicycle relation can be a SIGNED ROUTE ALONG ROADS
# (measured example: "Washington State Route 27 Bike Route", 2,629 m of road route per
# geometry point), and bundling one as a trail would count road miles as separated trail.
# So a relation is only bundled when at least this share of its CLIPPED member-way length
# is off-road. The measured share distribution is in docs/data/trails_regional.md.
OFF_ROAD_MIN_SHARE = 0.90
ON_STREET_HIGHWAYS = frozenset(("motorway", "trunk", "primary", "secondary", "tertiary",
                                "unclassified", "residential", "living_street", "service",
                                "road", "motorway_link", "trunk_link", "primary_link",
                                "secondary_link", "tertiary_link"))

# Drop a relation whose CLIPPED total is under this: not worth riding to.
MIN_LENGTH_KM = 1.0
# Drop individual geometry stubs under this. Separate from MIN_LENGTH_KM on purpose: a real
# 1.6 km trail mapped as three 0.5 km pieces must not be thrown away, but a 30 m leftover at
# a region edge is noise.
MIN_PIECE_KM = 0.25

# The region: Washington state + the Portland metro (D23). Everything is CLIPPED to the
# union of these boxes (S, W, N, E). A route relation can list members far outside the
# region -- the Washington .pbf keeps whole relations, so US Bicycle Route 87/95/97 arrive
# with their Alaska members -- and an unclipped relation lets the planner pick an absurd
# turnaround (measured: 3 relations claiming 1,127 km each).
REGION_BOXES = (
    (45.53, -124.87, 49.01, -116.90),   # Washington state bounding box
    (45.00, -123.70, 46.00, -121.80),   # Portland metro + northern Willamette Valley
)
# Insurance against a membership artifact pretending to be a ride. Raised from 200 km to
# 800 km on review (2026-09-16): the 1,127 km artifacts are handled by the clipping + the
# density guard, and 200 km was removing real off-road rail-trails (Palouse to Cascades
# 400.5 km, Washington Timber Trail 448.6 km, USBR 10 662.1 km). A long trail is not a
# problem for this app: it plans a 5-50 mile round trip and rides the part nearest the
# rider. Expected effect of 800 km: 0 relations dropped.
MAX_TRAIL_KM = 800.0
# Mean point spacing above this means the "geometry" is a straight line between far-apart
# members, not a mapped path (measured before: 4.6 km per point on the Alaska artifacts, and
# 2,629 / 2,681 m per point on the two road routes cited by the review). Applied to the
# CLIPPED SOURCE geometry, where OSM nodes are dense for a real path.
MAX_M_PER_POINT = 500.0
# The same guard on the SHIPPED (simplified) geometry needs a looser bound, because
# Douglas-Peucker removes collinear points and a real straight rail-trail genuinely keeps
# few. Measured on the shipped set: the sparsest real trails are Cascade Trail 793 m/point,
# Tolt Pipeline Trail 636, Yelm-Rainier-Tenino Trail 571; the artifacts cited in review are
# 2,629 and 2,681. 1,500 m/point sits at ~2x the worst real trail and ~1.75x under the
# smallest artifact, so it keeps every mapped trail and still rejects a membership artifact.
MAX_M_PER_POINT_SHIPPED = 1500.0
CANDIDATE_TOLERANCES_M = (10.0, 20.0)   # measured, see docs/data/trails_regional.md
# CHOSEN from the measurement, not guessed. Measured on all kept trails (2026-09-16):
#   10 m -> 59,714 points, 14,031.47 km (0.44% short of the source), max dev 10.0 m
#   20 m -> 44,028 points, 13,981.25 km (0.80% short),            max dev 20.0 m
# 20 m saves only ~0.3 MB. The file is inside the few-MB budget either way, so size does
# not decide: 10 m halves the length error and stays at the scale of a lane width, so the
# drawn shape keeps the correct side of a junction. Tolerance = 10 m.
SIMPLIFY_TOLERANCE_M = 10.0
LOOP_CLOSE_GAP_M = 100.0        # start/end closer than this => the shape closes on itself
DECISION_RATIONALE = (
    "10 m chosen over 20 m: 20 m would save only about 0.3 MB and the file is inside the "
    "few-MB budget either way, while 10 m halves the length error (0.44% short of the source "
    "geometry against 0.80%) and keeps the drawn shape on the correct side of a junction, "
    "because 10 m is about one lane width.")
COORD_DECIMALS = 5              # ~1.1 m; two orders of magnitude below the tolerance


# ---------------------------------------------------------------- geometry helpers
def haversine_m(a, b):
    lat1, lon1 = a
    lat2, lon2 = b
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    return 2.0 * 6371008.8 * math.asin(math.sqrt(h))


def extend_bbox(bbox, segments):
    """bbox = [min_lat, min_lon, max_lat, max_lon], rounded, or None when empty."""
    pts = [(p[0], p[1]) for s in segments for p in s]
    if not pts:
        return bbox
    lats = [p[0] for p in pts]
    lons = [p[1] for p in pts]
    if bbox is None:
        return [min(lats), min(lons), max(lats), max(lons)]
    return [min(bbox[0], min(lats)), min(bbox[1], min(lons)),
            max(bbox[2], max(lats)), max(bbox[3], max(lons))]


def in_region(p, boxes=REGION_BOXES):
    lat, lon = p
    return any(s <= lat <= n and w <= lon <= e for s, w, n, e in boxes)


def clip_to_region(segments, boxes=REGION_BOXES):
    """Keep only the in-region points, as contiguous pieces.

    A leg that crosses the boundary is dropped, so a clipped length is a slight
    under-estimate (by less than one leg per crossing).
    """
    out = []
    for seg in segments:
        cur = []
        for p in seg:
            if in_region(p, boxes):
                cur.append(p)
            else:
                if len(cur) >= 2:
                    out.append(cur)
                cur = []
        if len(cur) >= 2:
            out.append(cur)
    return out


def line_length_km(points):
    return sum(haversine_m(points[i], points[i + 1]) for i in range(len(points) - 1)) / 1000.0


def segments_length_km(segments):
    return sum(line_length_km(s) for s in segments)


def project_m(points):
    """Local equirectangular metres; fine at the 10-100 m scale we simplify at."""
    arr = np.asarray(points, dtype=float)
    lat0 = float(arr[:, 0].mean())
    x = (arr[:, 1] - arr[0, 1]) * 111320.0 * math.cos(math.radians(lat0))
    y = (arr[:, 0] - arr[0, 0]) * 110574.0
    return np.column_stack([x, y])


def _point_seg_dist(a, b, pts):
    """Distance from each row of pts to the segment a-b (all in projected metres)."""
    ab = b - a
    denom = float(ab @ ab)
    if denom == 0.0:
        return np.linalg.norm(pts - a, axis=1)
    t = ((pts - a) @ ab) / denom
    np.clip(t, 0.0, 1.0, out=t)
    proj = a + t[:, None] * ab
    return np.linalg.norm(pts - proj, axis=1)


def douglas_peucker_mask(xy, tol_m):
    """Return a boolean keep-mask for the Douglas-Peucker simplification of xy."""
    n = len(xy)
    keep = np.zeros(n, dtype=bool)
    keep[0] = keep[n - 1] = True
    stack = [(0, n - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        d = _point_seg_dist(xy[i], xy[j], xy[i + 1:j])
        k = int(np.argmax(d))
        if d[k] > tol_m:
            k += i + 1
            keep[k] = True
            stack.append((i, k))
            stack.append((k, j))
    return keep


def simplify_segment(points, tol_m):
    """-> (kept points, max deviation of the dropped points from the kept line)."""
    xy = project_m(points)
    mask = douglas_peucker_mask(xy, tol_m)
    idx = np.flatnonzero(mask)
    worst = 0.0
    for a, b in zip(idx[:-1], idx[1:]):
        if b <= a + 1:
            continue
        d = _point_seg_dist(xy[a], xy[b], xy[a + 1:b])
        if len(d):
            worst = max(worst, float(d.max()))
    kept = [points[i] for i in idx]
    return kept, worst


def simplify_segments(segments, tol_m):
    out = []
    worst = 0.0
    for s in segments:
        kept, dev = simplify_segment(s, tol_m)
        out.append(kept)
        worst = max(worst, dev)
    return out, worst


# ---------------------------------------------------------------- OSM reading
class _RelationCollector(osmium.SimpleHandler):
    """Pass 1 over a .pbf: the route relations we want and the ways they use."""

    def __init__(self):
        super().__init__()
        self.relations = []      # (relation id, tags, [way ids in member order])
        self.way_ids = set()
        self.relations_total = 0

    def relation(self, r):
        self.relations_total += 1
        tags = {t.k: t.v for t in r.tags}
        if tags.get("type") != "route" or tags.get("route") not in ROUTE_TYPES:
            return
        ways = [m.ref for m in r.members if m.type == "w"]
        self.relations.append((r.id, tags, ways))
        self.way_ids.update(ways)


class _WayCollector(osmium.SimpleHandler):
    """Pass 2 over a .pbf: node refs (and tags) for the wanted ways only."""

    def __init__(self, way_ids):
        super().__init__()
        self.way_ids = way_ids
        self.refs = {}
        self.way_tags = {}

    def way(self, w):
        if w.id in self.way_ids:
            self.refs[w.id] = [n.ref for n in w.nodes]
            self.way_tags[w.id] = {t.k: t.v for t in w.tags}


class _NodeCollector(osmium.SimpleHandler):
    """Pass 3 over a .pbf: coordinates for the nodes the wanted ways use."""

    def __init__(self, node_ids):
        super().__init__()
        self.node_ids = node_ids
        self.coords = {}

    def node(self, n):
        if n.id in self.node_ids:
            loc = n.location
            self.coords[n.id] = (loc.lat, loc.lon)


def read_wa_pbf(path):
    t0 = time.time()
    header = osmium.io.Reader(path).header()
    rel = _RelationCollector()
    rel.apply_file(path)
    ways = _WayCollector(rel.way_ids)
    ways.apply_file(path)
    node_ids = set()
    for refs in ways.refs.values():
        node_ids.update(refs)
    nodes = _NodeCollector(node_ids)
    nodes.apply_file(path)
    md5_side = None
    if os.path.exists(path + ".md5"):
        with open(path + ".md5") as fh:
            md5_side = fh.read().split()[0]
    digest = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    digest = digest.hexdigest()
    print("  [wa] %d relations, %d matched, %d member ways (%d present), %d nodes (%.1fs)"
          % (rel.relations_total, len(rel.relations), len(rel.way_ids), len(ways.refs),
             len(nodes.coords), time.time() - t0))
    print("  [wa] extract md5 %s (sidecar %s, match=%s)"
          % (digest, md5_side, digest == md5_side))
    return {
        "key": "wa",
        "label": "Washington state",
        "kind": "osm-pbf",
        "path": os.path.relpath(path, ROOT),
        "bytes": os.path.getsize(path),
        "md5": digest,
        "md5_sidecar": md5_side,
        "md5_matches_sidecar": digest == md5_side,
        "relations_total": rel.relations_total,
        "relations": [(rid, tags, [ways.refs[w] for w in wlist if w in ways.refs])
                      for rid, tags, wlist in rel.relations],
        "way_tags_by_rel": [[ways.way_tags.get(w, {}) for w in wlist if w in ways.refs]
                            for _rid, _tags, wlist in rel.relations],
        "member_ways_total": len(rel.way_ids),
        "member_ways_present": len(ways.refs),
        "coords": nodes.coords,
        "extract_timestamp": header.get("osmosis_replication_timestamp") or header.get("timestamp"),
        "replication_sequence": header.get("osmosis_replication_sequence_number"),
    }


def meta_name(raw_path):
    return os.path.basename(meta_path(raw_path)) if raw_path else ""


def meta_path(raw_path):
    """Meta sidecar path for a raw response, gzipped or not.

    <stamp>.json      -> <stamp>.meta.json
    <stamp>.json.gz   -> <stamp>.meta.json
    """
    base = raw_path[:-3] if raw_path.endswith(".gz") else raw_path
    if base.endswith(".json"):
        base = base[:-5]
    return base + ".meta.json"


def fetch_overpass(bbox=PORTLAND_BBOX, force=False):
    if not force:
        cached = newest_raw()
        if cached:
            print("  [or] re-using cached response", os.path.relpath(cached, ROOT))
            meta = None
            if os.path.exists(meta_path(cached)):
                with open(meta_path(cached)) as fh:
                    meta = json.load(fh)
            return load_overpass_json(cached), cached, meta
    os.makedirs(RAW_DIR, exist_ok=True)
    query = overpass_query(bbox)
    last_err = None
    for mirror in OVERPASS_MIRRORS:
        try:
            t0 = time.time()
            req = urllib.request.Request(
                mirror, data=query.encode("utf-8"),
                headers={"User-Agent": "bike-trail-planner/1.0 (regional trail bundle build)"})
            with urllib.request.urlopen(req, timeout=OVERPASS_TIMEOUT_S) as fh:
                body = fh.read()
            data = json.loads(body)
            if "elements" not in data:
                raise ValueError("no elements in response: %s" % str(data)[:200])
            stamp = (data.get("osm3s") or {}).get("timestamp_osm_base")
            age_days = _stamp_age_days(stamp)
            print("  [or] %s -> %d bytes, timestamp_osm_base=%s (%.1f days old, %.1fs)"
                  % (mirror, len(body), stamp, age_days, time.time() - t0))
            if age_days > OVERPASS_MAX_AGE_DAYS:
                last_err = "stale mirror (%s, %.1f days)" % (mirror, age_days)
                print("  [or] STALE, rotating mirror")
                continue
            out = os.path.join(RAW_DIR, "portland-overpass-%s.json.gz"
                               % stamp.replace(":", ""))
            # Stored gzipped: the response is ~18 MB raw and it is a cache, not a shipped
            # artifact. gzip is lossless, so the numbers stay reproducible from this file.
            with gzip.GzipFile(filename=out, mode="wb", mtime=0) as fh:
                fh.write(body)
            with open(os.path.join(RAW_DIR, "portland-query.overpassql"), "w") as fh:
                fh.write("// mirror: %s\n%s" % (mirror, query))
            meta = {
                "mirror": mirror,
                "query": query,
                "bytes": len(body),
                "bytes_gz": os.path.getsize(out),
                "timestamp_osm_base": stamp,
                "generator": data.get("generator"),
                "version": data.get("version"),
                "fetched_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
            with open(meta_path(out), "w") as fh:
                json.dump(meta, fh, indent=1)
            return data, out, meta
        except Exception as exc:                      # noqa: BLE001 - try the next mirror
            last_err = "%s: %s" % (mirror, exc)
            print("  [or] FAILED", last_err)
    raise SystemExit("Overpass failed on every mirror: %s" % last_err)


def _stamp_age_days(stamp):
    if not stamp:
        return 1e9
    dt = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    return (datetime.now(timezone.utc) - dt).total_seconds() / 86400.0


def newest_raw():
    if not os.path.isdir(RAW_DIR):
        return None
    cands = [os.path.join(RAW_DIR, f) for f in os.listdir(RAW_DIR)
             if f.startswith("portland-overpass-")
             and (f.endswith(".json") or f.endswith(".json.gz"))
             and not f.endswith(".meta.json")]
    return max(cands, key=os.path.getmtime) if cands else None


def load_overpass_json(path):
    """Read a raw response, plain or gzipped (the raw dump is kept gzipped)."""
    if path.endswith(".gz"):
        with gzip.open(path, "rt") as fh:
            return json.load(fh)
    with open(path) as fh:
        return json.load(fh)


def read_overpass_response(data, raw_path, meta=None):
    meta = meta or {}
    coords = {}
    way_refs = {}
    way_tag_map = {}
    for el in data["elements"]:
        if el["type"] == "node":
            if "lat" in el:
                coords[el["id"]] = (el["lat"], el["lon"])
        elif el["type"] == "way":
            if "nodes" in el:
                way_refs[el["id"]] = [n for n in el["nodes"]]
            if "tags" in el:
                way_tag_map[el["id"]] = el["tags"]
    relations = []
    way_tags = []
    for el in data["elements"]:
        if el["type"] != "relation":
            continue
        tags = el.get("tags", {})
        if tags.get("type") != "route" or tags.get("route") not in ROUTE_TYPES:
            continue
        wlist = [m["ref"] for m in el.get("members", []) if m["type"] == "way"]
        present = [w for w in wlist if w in way_refs]
        relations.append((el["id"], tags, [way_refs[w] for w in present]))
        way_tags.append([way_tag_map.get(w, {}) for w in present])
    print("  [or] %d relations matched, %d ways, %d nodes"
          % (len(relations), len(way_refs), len(coords)))
    return {
        "key": "portland",
        "label": "Portland metro, OR",
        "kind": "overpass",
        "bbox": list(PORTLAND_BBOX),
        "query": overpass_query(),
        "raw": os.path.relpath(raw_path, ROOT) if raw_path else None,
        "raw_bytes": meta.get("bytes") if meta else None,
        "raw_bytes_gz": (meta.get("bytes_gz") if meta and meta.get("bytes_gz")
                         else (os.path.getsize(raw_path) if raw_path else None)),
        "overpass_mirror": meta.get("mirror"),
        "overpass_version": data.get("version") or meta.get("version"),
        "timestamp_osm_base": (data.get("osm3s") or {}).get("timestamp_osm_base")
                              or meta.get("timestamp_osm_base"),
        "generator": data.get("generator") or meta.get("generator"),
        "relations_total": None,
        "relations": relations,
        "way_tags_by_rel": way_tags,
        "member_ways_total": len(way_refs),
        "member_ways_present": len(way_refs),
        "coords": coords,
        "extract_timestamp": (data.get("osm3s") or {}).get("timestamp_osm_base"),
        "replication_sequence": None,
    }


# ---------------------------------------------------------------- assembly
def way_runs(refs, coords):
    """Node refs of one way -> runs of consecutive points that exist locally.

    Ways clipped by the extract boundary (or a node missing) break the run instead
    of adding a straight-line jump -- the D4 mistake.
    """
    runs = []
    cur = []
    for r in refs:
        c = coords.get(r)
        if c is None:
            if len(cur) >= 2:
                runs.append(cur)
            cur = []
        else:
            cur.append(c)
    if len(cur) >= 2:
        runs.append(cur)
    return runs


def dedupe(points):
    out = [points[0]]
    for p in points[1:]:
        if p != out[-1]:
            out.append(p)
    return out


def greedy_chain(runs):
    """Join pieces that share an end node, in the order the relation lists them.

    Member order comes first, so an ordered relation chains exactly as written. What
    this adds is repair for the common case of an *unordered* relation (city bike
    networks): any remaining piece that shares an end node is appended or prepended
    until nothing else fits. Pieces are only ever joined at a shared node, so no
    straight-line jump is invented (the D4 measurement mistake).
    """
    unused = [list(r) for r in runs]
    out = []
    while unused:
        cur = unused.pop(0)
        joined = True
        while joined:
            joined = False
            for i, run in enumerate(unused):
                if cur[-1] == run[0]:
                    cur = cur + run[1:]
                elif cur[-1] == run[-1]:
                    cur = cur + run[::-1][1:]
                elif cur[0] == run[-1]:
                    cur = run[:-1] + cur
                elif cur[0] == run[0]:
                    cur = run[::-1][:-1] + cur
                else:
                    continue
                unused.pop(i)
                joined = True
                break
        out.append(cur)
    return out


def _all_runs(way_lists, coords, boxes):
    runs = []
    for refs in way_lists:
        runs.extend(clip_to_region(way_runs(refs, coords), boxes))
    return runs


def chain_runs(runs):
    """Join runs at shared end nodes and drop consecutive duplicates."""
    return [dedupe(s) for s in greedy_chain(runs) if len(s) >= 2]


def assemble_segments(way_lists, coords):
    runs = []
    for refs in way_lists:
        runs.extend(way_runs(refs, coords))
    return chain_runs(runs)


def largest_piece_share(tr):
    total = sum(line_length_km(s) for s in tr["_pieces"])
    if total <= 0.0:
        return 0.0
    return max(line_length_km(s) for s in tr["_pieces"]) / total


def is_off_road(way_tags):
    """True only for a way a road grader would be wrong to skip.

    `bicycle=designated` alone is NOT enough: it is also put on signed routes along
    residential streets. A way with no `highway` tag counts as unknown, and unknown is
    never treated as off-road.
    """
    hw = way_tags.get("highway")
    if hw in OFF_STREET_HIGHWAYS:
        return True
    if hw in ON_STREET_HIGHWAYS:
        return False
    return not hw and way_tags.get("bicycle") == "designated"


def surface_of(tags, member_tags):
    if tags.get("surface"):
        return tags["surface"]
    counts = {}
    for t in member_tags:
        s = t.get("surface")
        if s:
            counts[s] = counts.get(s, 0) + 1
    if not counts:
        return None
    return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]


def build_source(src, tolerances, boxes=REGION_BOXES):
    """-> (kept records, per-tolerance measurements, counts, drops)."""
    coords = src["coords"]
    kept = []
    drops = []              # dicts: id, name, km_source, km_clipped, code, detail
    unnamed = 0
    meas = {t: {"points": 0, "km": 0.0, "max_dev": 0.0} for t in tolerances}
    source_km_all = 0.0
    clipped_km_all = 0.0
    pre_lengths = []        # the length the OLD rule set would have shipped (unclipped)
    pre_bbox = None
    post_bbox = None
    shares = []             # off-road share of every relation that has classable geometry
    share_hist = {"0.0-0.1": 0, "0.1-0.2": 0, "0.2-0.3": 0, "0.3-0.4": 0, "0.4-0.5": 0,
                  "0.5-0.6": 0, "0.6-0.7": 0, "0.7-0.8": 0, "0.8-0.9": 0, "0.9-1.0": 0}

    def drop(rid, name, km_src, km_clip, code, detail):
        drops.append({"id": rid, "name": name, "length_km_source_unclipped": round(km_src, 1),
                      "length_km_clipped": round(km_clip, 1), "code": code, "reason": detail})

    for (rid, tags, way_lists), member_tags in zip(src["relations"], src["way_tags_by_rel"]):
        raw = assemble_segments(way_lists, coords)
        source_km = segments_length_km(raw) if raw else 0.0
        source_km_all += source_km
        pre_bbox = extend_bbox(pre_bbox, raw)
        if raw and source_km >= MIN_LENGTH_KM:
            pre_lengths.append(source_km)

        # Split the member ways by class and clip each way to the region:
        #   off_runs  -> the SHIPPED geometry (only honestly off-road ground)
        #   road_runs -> removed from the geometry, counted, and used for the gate share
        off_runs = []
        road_runs = []
        on_km = off_km = unk_km = 0.0
        for refs, wtags in zip(way_lists, member_tags):
            runs = clip_to_region(way_runs(refs, coords), boxes)
            if not runs:
                continue
            wkm = sum(line_length_km(r) for r in runs)
            if is_off_road(wtags):
                off_km += wkm
                off_runs.extend(runs)
            elif wtags.get("highway") in ON_STREET_HIGHWAYS:
                on_km += wkm
                road_runs.extend(runs)
            else:
                unk_km += wkm
                road_runs.extend(runs)
        classed_km = off_km + on_km + unk_km
        offroad_share = (off_km / classed_km) if classed_km > 0.0 else 0.0
        if classed_km > 0.0:
            shares.append(offroad_share)
            share_hist["%.1f-%.1f" % (min(int(offroad_share * 10), 9) / 10.0,
                                      min(int(offroad_share * 10), 9) / 10.0 + 0.1)] += 1

        # km accounting over ALL clipped member geometry, used for the "how much was
        # outside the region" provenance number.
        clipped_all_km = segments_length_km(chain_runs(_all_runs(way_lists, coords, boxes)))
        clipped_km_all += clipped_all_km
        name = tags.get("name")

        # Nothing classable at all: either the extract has no member geometry, or clipping
        # left none of it inside the region. Counted separately from the road routes.
        if classed_km <= 0.0:
            drop(rid, name, source_km, clipped_all_km, "no_geometry",
                 "no geometry in the extract" if not raw
                 else "nothing left inside the region after clipping")
            continue

        # SAFETY GATE: a route=bicycle relation that is a signed route along roads must not
        # be bundled, because the app grades every trail metre Grade A by construction.
        if offroad_share < OFF_ROAD_MIN_SHARE:
            drop(rid, name, source_km, clipped_all_km, "road_route",
                 "road route: only %.0f%% of the clipped member ways are off-road"
                 % (100.0 * offroad_share))
            continue

        # SHIPPED GEOMETRY: off-road pieces only. Road pieces are removed and are never
        # joined across (the app's Trail already carries several rideable spans, which is
        # how it handles the Sumner Link closure).
        road_km_removed = segments_length_km(chain_runs(road_runs)) if road_runs else 0.0
        pieces = [s for s in chain_runs(off_runs) if line_length_km(s) >= MIN_PIECE_KM]
        clipped_km = segments_length_km(pieces)
        if clipped_km < MIN_LENGTH_KM:
            if not pieces:
                drop(rid, name, source_km, clipped_all_km, "no_geometry",
                     "no geometry in the extract" if not raw
                     else "nothing left inside the region after clipping")
            else:
                drop(rid, name, source_km, clipped_all_km, "under_1km_off_road",
                     "only %.2f km of off-road geometry remains after removing %.2f km of "
                     "road pieces (under %.1f km)" % (clipped_km, road_km_removed,
                                                      MIN_LENGTH_KM))
            continue
        if clipped_km > MAX_TRAIL_KM:
            drop(rid, name, source_km, clipped_all_km, "over_max_length",
                 "longer than the %.0f km cap" % MAX_TRAIL_KM)
            continue
        points = sum(len(s) for s in pieces)
        if points < 3:
            drop(rid, name, source_km, clipped_all_km, "degenerate",
                 "degenerate geometry: %d clipped points (a straight line)" % points)
            continue
        m_per_point = 1000.0 * clipped_km / points
        if m_per_point > MAX_M_PER_POINT:
            drop(rid, name, source_km, clipped_all_km, "sparse_source",
                 "source geometry sparser than %.0f m per point (straight-line artifact)"
                 % MAX_M_PER_POINT)
            continue
        post_bbox = extend_bbox(post_bbox, pieces)


        per_tol = {}
        for t in tolerances:
            simpl, dev = simplify_segments(pieces, t)
            km = segments_length_km(simpl)
            per_tol[t] = (simpl, km, dev)
            meas[t]["points"] += sum(len(s) for s in simpl)
            meas[t]["km"] += km
            meas[t]["max_dev"] = max(meas[t]["max_dev"], dev)
        if not name:
            unnamed += 1
        main_piece = max(pieces, key=line_length_km)
        kept.append({
            "id": rid,
            "name": name,
            "route": tags["route"],
            "surface": surface_of(tags, member_tags),
            "region": src["key"],
            "length_km_clipped": None,      # filled below at the chosen tolerance
            "length_km_source_unclipped": round(source_km, 3),
            "offroad_share": round(offroad_share, 3),
            "offroad_km": round(off_km, 3),
            "onroad_km_removed": round(road_km_removed, 3),
            "loop": haversine_m(main_piece[0], main_piece[-1]) <= LOOP_CLOSE_GAP_M,
            "verified": False,
            "_pieces": pieces,
            "_per_tol": per_tol,
            "_clipped_points": points,
            "_clipped_km": clipped_km,
            "_source_km": source_km,
            "_source_points": sum(len(s) for s in raw),
            "_m_per_point": m_per_point,
            "_on_km": on_km,
            "_off_km": off_km,
            "_unk_km": unk_km,
        })

    def count_code(code):
        return sum(1 for d in drops if d["code"] == code)

    def examples(code, n=6):
        return [d for d in sorted([x for x in drops if x["code"] == code],
                                  key=lambda x: -x["length_km_source_unclipped"])][:n]

    classed = [(t["_off_km"], t["_on_km"]) for t in kept if (t["_off_km"] + t["_on_km"]) > 0]
    counts = {
        "relations_matched": len(src["relations"]),
        "class_mix_km_clipped": {
            # mix of the SOURCE relation's clipped member ways. Only the off-road part is
            # shipped; the on-road and unknown parts are removed from `segments`.
            "off_road": round(sum(t["_off_km"] for t in kept), 2),
            "on_road": round(sum(t["_on_km"] for t in kept), 2),
            "unknown_highway_tag": round(sum(t["_unk_km"] for t in kept), 2),
            "removed_from_shipped_geometry": round(
                sum(t["onroad_km_removed"] for t in kept), 2),
        },
        "offroad_share": {
            "threshold_kept_relations": OFF_ROAD_MIN_SHARE,
            "median": round(statistics.median(shares), 3) if shares else None,
            "min": round(min(shares), 3) if shares else None,
            "histogram": share_hist,
        },
        "kept_majority_on_road": sum(1 for off, on in classed if on > off),
        "kept_with_off_road_km": sum(1 for off, _on in classed if off > 0.0),
        "pieces": {
            "one_piece": sum(1 for t in kept if len(t["_pieces"]) == 1),
            "over_10_pieces": sum(1 for t in kept if len(t["_pieces"]) > 10),
            "largest_piece_share_over_0.9": sum(
                1 for t in kept if largest_piece_share(t) >= 0.9),
        },
        "most_fragmented": sorted(
            [{"id": t["id"], "name": t["name"], "pieces": len(t["_pieces"]),
              "length_km": round(t["_clipped_km"], 1)} for t in kept],
            key=lambda x: -x["pieces"])[:10],
        "kept": len(kept),
        "unnamed_kept": unnamed,
        "dropped_total": len(drops),
        "dropped_no_geometry": count_code("no_geometry"),
        "dropped_by_code": {
            "no_geometry": count_code("no_geometry"),
            "over_max_length": count_code("over_max_length"),
            "road_route": count_code("road_route"),
            "under_1km_off_road": count_code("under_1km_off_road"),
            "degenerate": count_code("degenerate"),
            "sparse_source_geometry": count_code("sparse_source"),
        },
        "dropped_km_by_code": {
            code: round(sum(d["length_km_clipped"] for d in drops if d["code"] == code), 2)
            for code in sorted({d["code"] for d in drops})
        },
        "dropped_road_route_examples": examples("road_route", 8),
        "dropped_under_1km_off_road_examples": examples("under_1km_off_road", 8),
        "road_geometry_removed_km": round(sum(t["onroad_km_removed"] for t in kept), 2),
        "dropped_over_max_length_examples": examples("over_max_length", 8),
        "dropped_sparse_source_examples": examples("sparse_source", 8),
        "dropped_examples": sorted(drops,
                                   key=lambda x: -x["length_km_source_unclipped"])[:12],
        "dropped_unnamed_largest": [
            {"id": d["id"], "length_km_clipped": d["length_km_clipped"],
             "length_km_source_unclipped": d["length_km_source_unclipped"],
             "reason": d["reason"]}
            for d in sorted([x for x in drops if not x["name"]],
                            key=lambda x: -x["length_km_clipped"])][:6],
        "km_source_sum_unclipped": round(source_km_all, 2),
        "km_clipped_sum": round(clipped_km_all, 2),
        "km_outside_region": round(source_km_all - clipped_km_all, 2),
        "source_points": sum(t["_source_points"] for t in kept),
        "clipped_points": sum(t["_clipped_points"] for t in kept),
        "before_clip": {
            "relations": len(pre_lengths),
            "km": round(sum(pre_lengths), 2),
            "min_km": round(min(pre_lengths), 3) if pre_lengths else None,
            "median_km": round(statistics.median(pre_lengths), 3) if pre_lengths else None,
            "max_km": round(max(pre_lengths), 1) if pre_lengths else None,
            "bbox_lat_lon": [round(x, 3) for x in pre_bbox] if pre_bbox else None,
        },
        "after_clip": {
            "relations": len(kept),
            "km": round(sum(t["_clipped_km"] for t in kept), 2),
            "min_km": round(min(t["_clipped_km"] for t in kept), 3) if kept else None,
            "median_km": round(statistics.median([t["_clipped_km"] for t in kept]), 3)
            if kept else None,
            "max_km": round(max(t["_clipped_km"] for t in kept), 1) if kept else None,
            "bbox_lat_lon": [round(x, 3) for x in post_bbox] if post_bbox else None,
        },
        "multi_segment_kept": sum(1 for t in kept if len(t["_pieces"]) > 1),
    }
    return kept, meas, counts, drops


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pbf", default=WA_PBF_DEFAULT, help="Washington OSM extract")
    ap.add_argument("--refetch", action="store_true", help="ignore the cached Overpass response")
    ap.add_argument("--no-write", action="store_true", help="measure only, write nothing")
    args = ap.parse_args()

    tolerances = sorted(set(CANDIDATE_TOLERANCES_M) | {SIMPLIFY_TOLERANCE_M})
    print("reading sources ...")
    wa = read_wa_pbf(args.pbf)
    data, raw_path, meta = fetch_overpass(force=args.refetch)
    or_ = read_overpass_response(data, raw_path, meta)

    sources = []
    trails = []
    for src in (wa, or_):
        kept, meas, counts, dropped = build_source(src, tolerances)
        src["_kept"] = kept
        src["_meas"] = meas
        src["_counts"] = counts
        src["_dropped"] = dropped
        trails.extend(kept)

    print("\nsimplification tradeoff (all kept trails, both sources):")
    total = {t: {"points": 0, "km": 0.0, "max_dev": 0.0} for t in tolerances}
    for src in (wa, or_):
        for t in tolerances:
            total[t]["points"] += src["_meas"][t]["points"]
            total[t]["km"] += src["_meas"][t]["km"]
            total[t]["max_dev"] = max(total[t]["max_dev"], src["_meas"][t]["max_dev"])
    src_points = sum(c["clipped_points"] for c in (wa["_counts"], or_["_counts"]))
    src_km = sum(c["km_clipped_sum"] for c in (wa["_counts"], or_["_counts"]))
    rows = [{"tolerance_m": t, "points": total[t]["points"],
             "km": round(total[t]["km"], 2),
             "max_deviation_m": round(total[t]["max_dev"], 1),
             "point_reduction_pct": round(100.0 * (1 - total[t]["points"] / max(src_points, 1)), 1)}
            for t in tolerances]
    for r in rows:
        print("  tol %5.1f m -> %7d points (%5.1f%% fewer), %8.2f km, max dev %5.1f m"
              % (r["tolerance_m"], r["points"], r["point_reduction_pct"], r["km"],
                 r["max_deviation_m"]))
    print("  clipped geometry before simplification: %d points, %.2f km" % (src_points, src_km))

    print("\nclipping to the region, and the caps (before -> after):")
    for src in (wa, or_):
        c = src["_counts"]
        b, a = c["before_clip"], c["after_clip"]
        print("  [%-8s] before: %3d relations, %8.1f km, min %7.3f / median %7.3f / max %8.1f,"
              " bbox %s" % (src["key"], b["relations"], b["km"], b["min_km"], b["median_km"],
                            b["max_km"], b["bbox_lat_lon"]))
        print("  [%-8s] after : %3d relations, %8.1f km, min %7.3f / median %7.3f / max %8.1f,"
              " bbox %s" % (src["key"], a["relations"], a["km"], a["min_km"], a["median_km"],
                            a["max_km"], a["bbox_lat_lon"]))
        print("  [%-8s] drops : %s | %.1f km outside the region"
              % (src["key"], ", ".join("%s %d" % (k, v)
                                       for k, v in sorted(c["dropped_by_code"].items())),
                 c["km_outside_region"]))
        print("  [%-8s] road geometry removed from shapes: %.1f km (under-1km drop: %d)"
              % (src["key"], c["class_mix_km_clipped"]["removed_from_shipped_geometry"],
                 c["dropped_by_code"]["under_1km_off_road"]))
        print("  [%-8s] shares: median %.2f, histogram %s"
              % (src["key"], c["offroad_share"]["median"], c["offroad_share"]["histogram"]))
    print("  (the two sources overlap; duplicate relation ids are dropped further below)")

    # finalise the shipped records at the chosen tolerance
    for tr in trails:
        simpl, km, _dev = tr["_per_tol"][SIMPLIFY_TOLERANCE_M]
        tr["length_km_clipped"] = round(km, 3)
        tr["segments"] = [[[round(la, COORD_DECIMALS), round(lo, COORD_DECIMALS)]
                           for la, lo in s] for s in simpl]

    # The two sources overlap (the Portland bbox reaches into Clark County, WA), so a few
    # relations arrive twice. Keep the Washington extract's copy -- it is the local one --
    # and record the ones dropped.
    seen = {}
    deduped = []
    dup_dropped = []
    for tr in trails:
        if tr["id"] in seen:
            dup_dropped.append({"id": tr["id"], "name": tr["name"],
                                "dropped_from_region": tr["region"],
                                "kept_from_region": seen[tr["id"]]["region"],
                                "length_km_clipped": tr["length_km_clipped"]})
            continue
        seen[tr["id"]] = tr
        deduped.append(tr)
    trails = deduped

    # Identical shipped geometry under different relation ids: a duplicate mapping in OSM.
    # Measured examples: relations 6575318 / 6575319 (unnamed, 2.75 km) and relations 6047 /
    # 1749027 (Yelm-Rainier-Tenino Trail / Yelm-Tenino Trail, 26.291 km, same 46 points).
    # Exact equality is the test on purpose: two routes that merely overlap keep different
    # point lists. The first occurrence wins (Washington before Portland, then lower id).
    geom_seen = {}
    geom_dropped = []
    unique = []
    for tr in trails:
        sig = hashlib.sha1(json.dumps(tr["segments"]).encode()).hexdigest()
        if sig in geom_seen:
            geom_dropped.append({"id": tr["id"], "name": tr["name"],
                                 "kept_id": geom_seen[sig]["id"],
                                 "kept_name": geom_seen[sig]["name"],
                                 "region": tr["region"],
                                 "length_km_clipped": tr["length_km_clipped"]})
            continue
        geom_seen[sig] = tr
        unique.append(tr)
    trails = unique

    # Guards on the SHIPPED geometry: the app only ever sees the simplified shape, which can
    # be sparser than the source (Douglas-Peucker removes collinear points).
    shipped_dropped = []
    survivors = []
    for tr in trails:
        pts = sum(len(s) for s in tr["segments"])
        km = tr["length_km_clipped"]
        mpp = 1000.0 * km / max(pts, 1)
        if pts < 3:
            shipped_dropped.append({"id": tr["id"], "name": tr["name"], "region": tr["region"],
                                    "length_km_clipped": km, "points": pts,
                                    "length_km_source_unclipped":
                                        tr["length_km_source_unclipped"],
                                    "m_per_point": None, "code": "degenerate"})
            continue
        if mpp > MAX_M_PER_POINT_SHIPPED:
            shipped_dropped.append({"id": tr["id"], "name": tr["name"], "region": tr["region"],
                                    "length_km_clipped": km, "points": pts,
                                    "length_km_source_unclipped":
                                        tr["length_km_source_unclipped"],
                                    "m_per_point": round(mpp, 1), "code": "sparse_shipped"})
            continue
        survivors.append(tr)
    trails = survivors

    # An empty name reaches the rider's screen and the rotation signature: label it honestly.
    for tr in trails:
        if not tr["name"]:
            tr["name"] = "Unnamed bike route (r%d)" % tr["id"]
    trails.sort(key=lambda t: (-t["length_km_clipped"], t["region"], t["id"]))
    print("  duplicate relation ids across sources: %d dropped (kept the wa copy)"
          % len(dup_dropped))
    for d in dup_dropped:
        print("    - %d %s (dropped from %s)" % (d["id"], d["name"], d["dropped_from_region"]))
    # The measurement table is computed over the SHIPPED set (after dedupe), so its
    # numbers add up to the file that is written.
    src_points = sum(tr["_clipped_points"] for tr in trails)
    src_km = sum(tr["_clipped_km"] for tr in trails)
    rows = []
    for t in tolerances:
        rows.append({
            "tolerance_m": t,
            "points": sum(len(s) for tr in trails for s in tr["_per_tol"][t][0]),
            "km": round(sum(tr["_per_tol"][t][1] for tr in trails), 2),
            "max_deviation_m": round(max(tr["_per_tol"][t][2] for tr in trails), 1),
            "point_reduction_pct": round(
                100.0 * (1 - sum(len(s) for tr in trails for s in tr["_per_tol"][t][0])
                         / max(src_points, 1)), 1),
        })
    print("\nsimplification tradeoff (shipped set, after dedupe):")
    for r in rows:
        print("  tol %5.1f m -> %7d points (%5.1f%% fewer), %8.2f km, max dev %5.1f m"
              % (r["tolerance_m"], r["points"], r["point_reduction_pct"], r["km"],
                 r["max_deviation_m"]))
    print("  clipped geometry before simplification: %d points, %.2f km" % (src_points, src_km))

    shipped_bbox = extend_bbox(None, [s for t in trails for s in t["segments"]])
    pre_boxes = [s["_counts"]["before_clip"]["bbox_lat_lon"] for s in (wa, or_)
                 if s["_counts"]["before_clip"]["bbox_lat_lon"]]
    before_bbox = [round(min(b[0] for b in pre_boxes), 3), round(min(b[1] for b in pre_boxes), 3),
                   round(max(b[2] for b in pre_boxes), 3), round(max(b[3] for b in pre_boxes), 3)]
    shipped_lengths = sorted(t["length_km_clipped"] for t in trails)

    # Every "after" number is computed from the SHIPPED records, so the JSON and the data
    # note cannot drift from the file.
    for src in (wa, or_):
        c = src["_counts"]
        mine = [t for t in trails if t["region"] == src["key"]]
        mine_drops = [d for d in shipped_dropped if d["region"] == src["key"]]
        lengths = sorted(t["length_km_clipped"] for t in mine)
        c["kept"] = len(mine)
        c["kept_bicycle"] = sum(1 for t in mine if t["route"] == "bicycle")
        c["kept_mtb"] = sum(1 for t in mine if t["route"] == "mtb")
        c["kept_loops"] = sum(1 for t in mine if t["loop"])
        c["unnamed_kept"] = sum(1 for t in mine if t["name"].startswith("Unnamed bike route (r"))
        c["multi_segment_kept"] = sum(1 for t in mine if len(t["segments"]) > 1)
        c["kept_km_clipped"] = round(sum(t["_clipped_km"] for t in mine), 2)
        c["kept_km_source_unclipped"] = round(sum(t["_source_km"] for t in mine), 2)
        c["kept_km_simplified"] = round(sum(t["length_km_clipped"] for t in mine), 2)
        c["kept_points"] = sum(len(s) for t in mine for s in t["segments"])
        c["class_mix_km_clipped"] = {
            "off_road": round(sum(t["_off_km"] for t in mine), 2),
            "on_road": round(sum(t["_on_km"] for t in mine), 2),
            "unknown_highway_tag": round(sum(t["_unk_km"] for t in mine), 2),
            "removed_from_shipped_geometry": round(
                sum(t["onroad_km_removed"] for t in mine), 2),
        }
        c["pieces"] = {
            "one_piece": sum(1 for t in mine if len(t["segments"]) == 1),
            "over_10_pieces": sum(1 for t in mine if len(t["segments"]) > 10),
            "largest_piece_share_over_0.9": sum(
                1 for t in mine if largest_piece_share(t) >= 0.9),
        }
        c["most_fragmented"] = sorted(
            [{"id": t["id"], "name": t["name"], "pieces": len(t["segments"]),
              "length_km": round(t["length_km_clipped"], 1)} for t in mine],
            key=lambda x: -x["pieces"])[:10]
        c["shipped"] = {
            "relations": len(mine),
            "km": round(sum(t["length_km_clipped"] for t in mine), 2),
            "min_km": round(lengths[0], 3) if lengths else None,
            "median_km": round(statistics.median(lengths), 3) if lengths else None,
            "max_km": round(lengths[-1], 1) if lengths else None,
            "bbox_lat_lon": [round(x, 3) for x in extend_bbox(
                None, [s for t in mine for s in t["segments"]])],
            "offroad_share_median": round(statistics.median(
                [t["offroad_share"] for t in mine]), 3) if mine else None,
        }
        c["dropped_shipped_degenerate"] = sum(1 for d in mine_drops
                                              if d["code"] == "degenerate")
        c["dropped_shipped_sparse_geometry"] = sum(1 for d in mine_drops
                                                   if d["code"] == "sparse_shipped")

    doc = {
        "schema_version": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generated_by": "tools/build_regional_trails.py",
        "decision": "docs/DECISIONS.md D23 final answer (a): bundle the regional route relations",
        "verified": False,
        "warning": ("UNVERIFIED regional data. No closure check, no surface audit and no measured "
                    "traffic. Every trail here must be loaded with verified=false; away from home "
                    "most roads grade UNGRADED. See docs/REGIONAL-TRAILS.md."),
        "licence": {
            "name": "ODbL 1.0 (OpenStreetMap)",
            "attribution": "(c) OpenStreetMap contributors",
            "note": ("This file is a Derivative Database of OpenStreetMap data and stays under "
                     "the ODbL."),
        },
        "region": {
            "description": ("Washington state + the Portland metro and the northern "
                            "Willamette Valley (D23)"),
            "boxes_s_w_n_e": [list(b) for b in REGION_BOXES],
            "clipping": ("geometry is clipped to the union of these boxes. Without this a "
                         "relation keeps every member the extract holds, including members "
                         "far outside the region (measured before: a coordinate range of "
                         "lat 42.307..55.354, lon -131.714..-116.996, i.e. Alaska)."),
            "boundary_leg_note": ("the leg that crosses a box edge is dropped, so a clipped "
                                  "length under-estimates the in-region length by less than "
                                  "one leg per crossing."),
        },
        "simplification": {
            "algorithm": "Douglas-Peucker",
            "tolerance_m": SIMPLIFY_TOLERANCE_M,
            "coordinate_decimals": COORD_DECIMALS,
            "rationale": DECISION_RATIONALE,
            "measurements": rows,
            "geometry_points_before_simplification": src_points,
            "km_before_simplification": round(src_km, 2),
        },
        "selection": {
            "route_types": list(ROUTE_TYPES),
            "min_length_km": MIN_LENGTH_KM,
            "max_length_km": MAX_TRAIL_KM,
            "max_m_per_point_source": MAX_M_PER_POINT,
            "max_m_per_point_shipped": MAX_M_PER_POINT_SHIPPED,
            "loop_close_gap_m": LOOP_CLOSE_GAP_M,
            "fields": ["id", "name", "route", "surface", "region",
                       "length_km_clipped", "length_km_source_unclipped",
                       "offroad_share", "offroad_km", "onroad_km_removed", "loop",
                       "verified", "segments"],
            "invariant": ("every shipped metre is off-road; `segments` contains no road "
                          "geometry"),
            "field_notes": {
                "length_km_clipped": ("measured on the SIMPLIFIED geometry after clipping to "
                                      "the region: the length the app may use"),
                "length_km_source_unclipped": ("the whole relation as assembled from the "
                                               "source, before clipping: provenance only, "
                                               "never rideable length"),
                "segments": ("one or more contiguous chains of [lat, lon]. EVERY metre of "
                             "these is off-road: on-road and unknown-highway member pieces "
                             "are removed at build time and never joined across, so the app "
                             "(which grades trail geometry Grade A by construction) cannot "
                             "grade a road metre as trail. Pieces may therefore be several "
                             "rideable spans, like the Sumner Link closure case."),
                "onroad_km_removed": ("clipped road geometry that belonged to this relation "
                                      "in OSM and was removed from `segments`"),
                "offroad_share": ("share of the SOURCE relation's CLIPPED member-way length "
                                  "whose highway class is off-road (cycleway/path/footway/"
                                  "bridleway/track/pedestrian/steps). Everything below %.2f "
                                  "is not bundled at all, and inside a bundled relation only "
                                  "the off-road part reaches `segments`."
                                  % OFF_ROAD_MIN_SHARE),
                "offroad_km": "the off-road part of offroad_share, in km",
            },
        },
        "sources": [],
        "counts": {
            "trails": len(trails),
            "km_clipped_total": round(sum(t["_clipped_km"] for t in trails), 2),
            "km_clipped_simplified": round(sum(t["_per_tol"][SIMPLIFY_TOLERANCE_M][1]
                                               for t in trails), 2),
            "geometry_points": sum(len(s) for t in trails
                                   for s in t["_per_tol"][SIMPLIFY_TOLERANCE_M][0]),
            "length_km_clipped": {
                "min": round(shipped_lengths[0], 3) if shipped_lengths else None,
                "median": round(statistics.median(shipped_lengths), 3) if shipped_lengths else None,
                "max": round(shipped_lengths[-1], 1) if shipped_lengths else None,
            },
            "bbox_lat_lon": [round(x, 3) for x in shipped_bbox] if shipped_bbox else None,
            "bbox_lat_lon_before_clipping": before_bbox,
            "duplicate_relation_ids_across_sources": {
                "count": len(dup_dropped),
                "kept_source": "wa (the local extract wins)",
                "examples": dup_dropped[:8],
            },
            "duplicate_geometry_same_name": {
                "count": len(geom_dropped),
                "note": ("identical shipped points under two relation ids: a duplicate "
                         "mapping in OSM (names may differ)"),
                "examples": geom_dropped[:8],
            },
            "criterion_shipped": {"max_m_per_point": MAX_M_PER_POINT_SHIPPED},
            "dropped_on_shipped_geometry": {
                "count": len(shipped_dropped),
                "degenerate_under_3_points": sum(1 for d in shipped_dropped
                                                 if d["code"] == "degenerate"),
                "sparser_than_%d_m_per_point" % MAX_M_PER_POINT_SHIPPED: sum(
                    1 for d in shipped_dropped if d["code"] == "sparse_shipped"),
                "criterion": ("measured on the simplified geometry the app actually reads, "
                              "not on the source geometry"),
                "examples": sorted(shipped_dropped,
                                   key=lambda d: -(d["m_per_point"] or 0))[:8],
            },
            "unnamed_labelled": {
                "count": sum(1 for t in trails
                             if t["name"].startswith("Unnamed bike route (r")),
                "label_format": "Unnamed bike route (r<relation id>)",
                "largest": [{"id": t["id"], "name": t["name"], "region": t["region"],
                             "length_km_clipped": t["length_km_clipped"],
                             "points": sum(len(s) for s in t["segments"])}
                            for t in trails
                            if t["name"].startswith("Unnamed bike route (r")][:8],
            },
            "dropped_total": sum(s["_counts"]["dropped_total"] for s in (wa, or_)),
            "by_region": {s["key"]: s["_counts"] for s in (wa, or_)},
        },
        "trails": [],
    }
    for src in (wa, or_):
        entry = {k: v for k, v in src.items() if not k.startswith("_")}
        entry.pop("relations", None)
        entry.pop("way_tags_by_rel", None)
        entry.pop("coords", None)
        doc["sources"].append({k: v for k, v in entry.items() if v is not None})

    for tr in trails:
        doc["trails"].append({
            "id": tr["id"], "name": tr["name"], "route": tr["route"],
            "surface": tr["surface"], "region": tr["region"],
            "length_km_clipped": tr["length_km_clipped"],
            "length_km_source_unclipped": tr["length_km_source_unclipped"],
            "offroad_share": tr["offroad_share"], "offroad_km": tr["offroad_km"],
            "onroad_km_removed": tr["onroad_km_removed"],
            "loop": tr["loop"], "verified": False, "segments": tr["segments"]})

    if args.no_write:
        print("\n--no-write: nothing written")
        return doc

    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    with open(OUT_JSON, "w") as fh:
        json.dump(doc, fh, separators=(",", ":"))
        fh.write("\n")
    size = os.path.getsize(OUT_JSON)
    print("\nwrote %s (%d trails, %.1f km clipped, %.2f MB)"
          % (os.path.relpath(OUT_JSON, ROOT), len(trails),
             doc["counts"]["km_clipped_simplified"], size / 1e6))

    write_note(doc, size, rows, wa, or_, dup_dropped, geom_dropped, shipped_dropped)
    return doc


def write_note(doc, size, rows, wa, or_, dup_dropped, geom_dropped, shipped_dropped):
    c = doc["counts"]
    wa_c, or_c = wa["_counts"], or_["_counts"]
    m = doc["simplification"]
    lc = c["length_km_clipped"]

    def pick(counts, key):
        for part in key.split("|"):
            counts = counts[part]
        return counts

    def row3(label, key, fmt="%d"):
        wa_v, or_v = pick(wa_c, key), pick(or_c, key)
        return "| %s | %s | %s | %s |" % (label, fmt % wa_v, fmt % or_v, fmt % (wa_v + or_v))

    def tot(key):
        return pick(wa_c, key) + pick(or_c, key)

    lines = []
    A = lines.append
    A("# Regional trail data (`trails_regional.json`)")
    A("")
    A("GENERATED by `tools/build_regional_trails.py` -- do not edit by hand.")
    A("Authoritative copy: this directory. The app copy at")
    A("`app/src/main/assets/trails_regional.json` is produced by `tools/build_assets.py`.")
    A("")
    A("Decision: `docs/DECISIONS.md` D23, final answer (a) -- bundle the regional")
    A("OpenStreetMap `route=bicycle` + `route=mtb` relations for Washington state + the")
    A("Portland metro and the northern Willamette Valley. The six near-home trails stay in")
    A("their own file (`trails.json`), because they are the only ones with measured traffic,")
    A("a closure check and a surface audit.")
    A("")
    A("**Every trail in this file is UNVERIFIED (`verified = false`).** See")
    A("`docs/REGIONAL-TRAILS.md` for what that does and does not mean, for the safety reason")
    A("why road-following route relations are excluded, and for the before/after")
    A("measurements of the clipping fix.")
    A("")
    A("## Sources")
    A("")
    A("| Region | Source | Extract / data timestamp |")
    A("|---|---|---|")
    A("| Washington | `%s` (local OSM extract, read with pyosmium), %.0f MB, md5 `%s` "
      "(matches the `.md5` beside it: %s) | %s (replication seq. %s) |"
      % (wa["path"], wa["bytes"] / 1e6, wa["md5"], wa["md5_matches_sidecar"],
         wa["extract_timestamp"], wa["replication_sequence"]))
    A("| Portland metro | Overpass bbox `%s` via `%s` | %s |"
      % (", ".join(str(x) for x in or_["bbox"]), or_["overpass_mirror"],
         or_["timestamp_osm_base"]))
    A("| Both | OSM data, licence ODbL 1.0 -- (c) OpenStreetMap contributors | |")
    A("")
    A("Raw Overpass response cached at `%s` (%d bytes, Overpass `%s`), with its query and"
      % (or_["raw"], or_["raw_bytes"] or 0, or_["overpass_version"]))
    A("mirror in `portland-query.overpassql` and `%s`." % os.path.basename(meta_name(or_["raw"])))
    A("")
    A("## Selection rules (in order)")
    A("")
    A("1. keep relations whose `type` is `route` and whose `route` is `bicycle` or `mtb`;")
    A("2. CLIP every geometry to the region = the union of the boxes `%s` (S, W, N, E):"
      % ", ".join(str(tuple(b)) for b in REGION_BOXES))
    A("   Washington state, the Portland metro and the northern Willamette Valley.")
    A("   **This step is what makes the lengths rideable.** A relation can list members far")
    A("   outside the region, and the Washington extract keeps whole relations, so without")
    A("   clipping the file held a coordinate range of lat 42.307..55.354, lon")
    A("   -131.714..-116.996 (Alaska) and three relations claiming 1,127 km each;")
    A("3. **SAFETY GATE -- off-road share >= %.2f.** The app grades trail geometry Grade A by"
      % OFF_ROAD_MIN_SHARE)
    A("   construction, with no traffic data (`SafetyGrader.gradeTrailLeg`,")
    A("   `app/src/main/java/com/biketrailplanner/domain/SafetyGrader.kt:236`). That is only")
    A("   sound for a real off-road path, so a `route=bicycle` relation that is a signed")
    A("   route along roads is not bundled. Share is measured over the CLIPPED member ways;")
    A("   off-road = `highway` in %s;" % ", ".join(sorted(OFF_STREET_HIGHWAYS)))
    A("   on-road = %s; anything"
      % ", ".join(sorted(ON_STREET_HIGHWAYS)))
    A("   else counts as unknown, and unknown is never off-road;")
    A("4. **SHIP OFF-ROAD GEOMETRY ONLY.** The on-road and unknown member pieces are removed")
    A("   from `segments`, so every shipped metre is off-road and the app's Grade A trail leg")
    A("   can never grade a road metre as trail. Pieces are never joined across a removed")
    A("   road piece, so a relation can ship as several rideable spans -- which the app's")
    A("   `Trail` already supports (`rideableSpans`, the Sumner Link closure case). A")
    A("   relation is kept only when at least **%.1f km** of off-road geometry remains;"
      % MIN_LENGTH_KM)
    A("   **%d** relation(s) fell below that and were dropped. Total road geometry removed"
      % (wa_c["dropped_by_code"]["under_1km_off_road"]
         + or_c["dropped_by_code"]["under_1km_off_road"]))
    A("   from the shipped shapes: **%.1f km** (Washington %.1f, Portland %.1f);"
      % (wa_c["class_mix_km_clipped"]["removed_from_shipped_geometry"]
         + or_c["class_mix_km_clipped"]["removed_from_shipped_geometry"],
         wa_c["class_mix_km_clipped"]["removed_from_shipped_geometry"],
         or_c["class_mix_km_clipped"]["removed_from_shipped_geometry"]))
    A("5. drop every clipped piece shorter than **%.0f m** (a leftover stub at a region"
      % (MIN_PIECE_KM * 1000.0))
    A("   edge), then drop the relation if its off-road total is under **%.1f km**;"
      % MIN_LENGTH_KM)
    A("6. drop any relation longer than **%.0f km** -- insurance against a membership"
      % MAX_TRAIL_KM)
    A("   artifact pretending to be a ride (the 1,127 km artifacts are handled by the")
    A("   clipping and the density guard). A LONG trail is fine: the app plans a 5-50 mile")
    A("   round trip (D20) and rides the part nearest the rider;")
    A("7. drop degenerate geometry (**fewer than 3 clipped points**: a straight line the")
    A("   trail walk cannot use);")
    A("8. drop geometry sparser than **%.0f m per point** (mean point spacing) on the"
      % MAX_M_PER_POINT)
    A("   CLIPPED SOURCE geometry, and sparser than **%.0f m per point** on the SHIPPED"
      % MAX_M_PER_POINT_SHIPPED)
    A("   simplified geometry -- Douglas-Peucker removes collinear points, so a real straight")
    A("   rail-trail is legitimately sparse once simplified (measured: 571-793 m/point),")
    A("   while a membership artifact is 2,600+ m/point;")
    A("9. drop a relation that the two sources both returned (the Portland bbox reaches into")
    A("   Clark County, WA), and a relation that duplicates another relation's exact points")
    A("   under a different id;")
    A("10. label an unnamed relation `Unnamed bike route (r<relation id>)` -- an empty name")
    A("    reaches the rider's screen and the rotation signature.")
    A("")

    A("## Region clipping: before -> after")
    A("")
    A("| | Washington | Portland metro | Total |")
    A("|---|---|---|---|")
    A("| relations before (unclipped) | %d | %d | %d |"
      % (wa_c["before_clip"]["relations"], or_c["before_clip"]["relations"],
         wa_c["before_clip"]["relations"] + or_c["before_clip"]["relations"]))
    A("| relations shipped | %d | %d | %d |"
      % (wa_c["shipped"]["relations"], or_c["shipped"]["relations"], c["trails"]))
    A("| km before (unclipped sum) | %.1f | %.1f | %.1f |"
      % (wa_c["before_clip"]["km"], or_c["before_clip"]["km"],
         wa_c["before_clip"]["km"] + or_c["before_clip"]["km"]))
    A("| km shipped | %.1f | %.1f | %.1f |"
      % (wa_c["shipped"]["km"], or_c["shipped"]["km"], c["km_clipped_simplified"]))
    A("| km dropped as outside the region | %.1f | %.1f | %.1f |"
      % (wa_c["km_outside_region"], or_c["km_outside_region"],
         wa_c["km_outside_region"] + or_c["km_outside_region"]))
    A("")
    A("Length distribution of a single entry (km):")
    A("")
    A("| | min | median | max |")
    A("|---|---|---|---|")
    A("| before clipping | %.3f | %.3f | %.1f |"
      % (wa_c["before_clip"]["min_km"], wa_c["before_clip"]["median_km"],
         wa_c["before_clip"]["max_km"]))
    A("| shipped | %.3f | %.3f | %.1f |" % (lc["min"], lc["median"], lc["max"]))
    A("")
    bb, bb0 = c["bbox_lat_lon"], c["bbox_lat_lon_before_clipping"]
    A("Coordinate range of the shipped geometry: **lat %.3f..%.3f, lon %.3f..%.3f**"
      % (bb[0], bb[2], bb[1], bb[3]))
    A("(before clipping: lat %.3f..%.3f, lon %.3f..%.3f -- this included the Alaska and"
      % (bb0[0], bb0[2], bb0[1], bb0[3]))
    A("British Columbia members that the clipping removes.)")
    A("")
    A("## Off-road share (the safety gate)")
    A("")
    A("Median off-road share of a SHIPPED relation: **%.2f** (Washington) / **%.2f**"
      % (wa_c["shipped"]["offroad_share_median"], or_c["shipped"]["offroad_share_median"]))
    A("(Portland). Distribution over every relation in each source that had classable")
    A("geometry, before the cut -- this is why %.2f was chosen:" % OFF_ROAD_MIN_SHARE)
    A("")
    A("| off-road share | Washington | Portland metro |")
    A("|---|---|---|")
    for bucket in ("0.0-0.1", "0.1-0.2", "0.2-0.3", "0.3-0.4", "0.4-0.5", "0.5-0.6",
                   "0.6-0.7", "0.7-0.8", "0.8-0.9", "0.9-1.0"):
        A("| %s | %d | %d |" % (bucket, wa_c["offroad_share"]["histogram"][bucket],
                                or_c["offroad_share"]["histogram"][bucket]))
    A("")
    A("The distribution is dominated by two clusters -- a real path network (0.9-1.0) and a")
    A("route along streets (0.0-0.1) -- with a real middle band between them (Washington:"
      " %d" % (wa_c["offroad_share"]["histogram"]["0.5-0.6"]
               + wa_c["offroad_share"]["histogram"]["0.6-0.7"]
               + wa_c["offroad_share"]["histogram"]["0.7-0.8"]
               + wa_c["offroad_share"]["histogram"]["0.8-0.9"]))
    A("relations at 0.5-0.9; Portland: %d). The cut is at **%.2f**, which was chosen because"
      % (or_c["offroad_share"]["histogram"]["0.5-0.6"]
         + or_c["offroad_share"]["histogram"]["0.6-0.7"]
         + or_c["offroad_share"]["histogram"]["0.7-0.8"]
         + or_c["offroad_share"]["histogram"]["0.8-0.9"], OFF_ROAD_MIN_SHARE))
    A("the grader is all-or-nothing: it marks the WHOLE trail leg Grade A, so every on-road")
    A("km inside a bundled relation is a road km graded as separated trail.")
    A("")
    A("- At 0.90 a 30 km trail may carry 3 km of street and still be bundled. The %d"
      % (wa_c["offroad_share"]["histogram"]["0.8-0.9"] + or_c["offroad_share"]["histogram"]["0.8-0.9"]))
    A("  relations in the 0.80-0.90 band were dropped for that reason; loosening the cut to")
    A("  0.80 would add them back, at the cost of grading up to 20% of their length as if it")
    A("  were a path.")
    A("- The band below 0.50 (%d relations) is a route along streets, not a trail, and is"
      % (sum(v for k, v in wa_c["offroad_share"]["histogram"].items() if k < "0.5")
         + sum(v for k, v in or_c["offroad_share"]["histogram"].items() if k < "0.5")))
    A("  never bundled.")
    A("")
    A("### Shipped geometry is 100% off-road")
    A("")
    A("Rule 4 removes the road pieces, so the invariant holds by construction: no shipped")
    A("metre is a road metre. The table below is the mix of the SOURCE relations that passed")
    A("the gate; the removed column is what rule 4 took out of the shapes:")
    A("")
    A("| | Washington | Portland metro | Total |")
    A("|---|---|---|---|")
    for label, k in (("off-road member-way km (source measure)", "off_road"),
                     ("on-road member-way km", "on_road"),
                     ("unknown `highway` km", "unknown_highway_tag"),
                     ("**removed from the shapes**", "removed_from_shipped_geometry")):
        A("| %s | %.1f | %.1f | %.1f |"
          % (label, wa_c["class_mix_km_clipped"][k], or_c["class_mix_km_clipped"][k],
             wa_c["class_mix_km_clipped"][k] + or_c["class_mix_km_clipped"][k]))
    A("")
    A("Worked example of what the gate costs, and why it is worth it: **Hagg Lake Trail**")
    A("(Portland) has 18.85 km off-road and 2.63 km on the park loop road, a share of 0.877, so")
    A("it is excluded -- at 0.90 an absolute invariant, those 2.63 km would have been graded")
    A("Grade A trail. The same is true of Washington Timber Trail (0.31) and Olympic")
    A("Discovery Trail (0.51), which is why neither is in the file even though the 800 km cap")
    A("would now keep them.")
    A("")
    A("The 0.80-0.90 band stays excluded. That is a **deliberate choice, not an oversight**:")
    A("`gradeTrailLeg` is all-or-nothing, so admitting that band would grade up to 20% of")
    A("those routes as if they were separated trail. Coverage is not the constraint (this")
    A("bundle already has %d trails and %.0f km); honesty of the grade is."
      % (c["trails"], c["km_clipped_simplified"]))
    A("")
    A("## What each filter dropped")
    A("")
    A("| filter | Washington | Portland metro | Total |")
    A("|---|---|---|---|")
    A(row3("relations matched", "relations_matched"))
    A(row3("shipped", "kept"))
    A(row3("no geometry in the extract, or nothing left inside the region / under %.1f km"
           % MIN_LENGTH_KM, "dropped_by_code|no_geometry"))
    A(row3("**road route (off-road share < %.2f)**" % OFF_ROAD_MIN_SHARE,
           "dropped_by_code|road_route"))
    A(row3("under %.1f km of off-road geometry left after removing road pieces" % MIN_LENGTH_KM,
           "dropped_by_code|under_1km_off_road"))
    A(row3("over the %.0f km cap" % MAX_TRAIL_KM, "dropped_by_code|over_max_length"))
    A(row3("degenerate source geometry (<3 points)", "dropped_by_code|degenerate"))
    A(row3("source geometry sparser than %.0f m/point" % MAX_M_PER_POINT,
           "dropped_by_code|sparse_source_geometry"))
    A(row3("dropped in total", "dropped_total"))
    A("")
    A("| filter on the SHIPPED geometry (threshold %.0f m/point) | Washington | Portland"
      " metro | Total |" % MAX_M_PER_POINT_SHIPPED)
    A("|---|---|---|---|")
    A(row3("degenerate shipped geometry (<3 points)", "dropped_shipped_degenerate"))
    A(row3("shipped geometry sparser than %.0f m/point" % MAX_M_PER_POINT_SHIPPED,
           "dropped_shipped_sparse_geometry"))
    A("")
    A("Unnamed relations are kept and labelled: **%d** shipped entries carry the label"
      % c["unnamed_labelled"]["count"])
    A("`Unnamed bike route (r<relation id>)`.")
    if c["unnamed_labelled"]["largest"]:
        A("")
        A("| relation | label now shipped | region | length | points |")
        A("|---|---|---|---|---|")
        for e in c["unnamed_labelled"]["largest"]:
            A("| %d | %s | %s | %.1f km | %d |"
              % (e["id"], e["name"], e["region"], e["length_km_clipped"], e["points"]))
        A("")
        A("Before this fix these records shipped with an empty `name`, so the rider's screen")
        A("and the rotation signature received `\"\"`; their length is unchanged. The two largest")
        A("unnamed entries in the previous build (the ones flagged in review: about 194 km from")
        A("1,109 points, and about 170 km from 856 points, both Washington) are **not in the")
        A("file at all** now -- the safety gate removed them as road routes:")
        A("")
        A("| relation | km clipped | why |")
        A("|---|---|---|")
        for e in sorted(wa_c["dropped_unnamed_largest"] + or_c["dropped_unnamed_largest"],
                        key=lambda x: -x["length_km_clipped"])[:5]:
            A("| %d | %.1f | %s |" % (e["id"], e["length_km_clipped"], e["reason"]))
    A("")
    A("Examples of road routes removed by the safety gate:")
    A("")
    A("| relation | name | km unclipped | km clipped | why |")
    A("|---|---|---|---|---|")
    for e in sorted(wa_c["dropped_road_route_examples"] + or_c["dropped_road_route_examples"],
                    key=lambda x: -x["length_km_source_unclipped"])[:10]:
        A("| %d | %s | %.1f | %.1f | %s |"
          % (e["id"], e["name"] or "(unnamed)", e["length_km_source_unclipped"],
             e["length_km_clipped"], e["reason"]))
    A("")
    A("Examples of the other filters (the biggest ones):")
    A("")
    A("| relation | name | km unclipped | km clipped | why |")
    A("|---|---|---|---|---|")
    for e in sorted(wa_c["dropped_over_max_length_examples"] +
                    or_c["dropped_over_max_length_examples"] +
                    wa_c["dropped_sparse_source_examples"] +
                    or_c["dropped_sparse_source_examples"] +
                    [d for d in shipped_dropped][:4],
                    key=lambda x: -x["length_km_source_unclipped"]
                    if "length_km_source_unclipped" in x else -x["length_km_clipped"])[:10]:
        A("| %d | %s | %.1f | %.1f | %s |"
          % (e["id"], e["name"] or "(unnamed)",
             e.get("length_km_source_unclipped", 0.0), e["length_km_clipped"],
             e.get("reason") or ("%s on the shipped geometry" % e["code"])))
    A("")
    A("## Kept lengths and counts")
    A("")
    A("| | Washington | Portland metro | Total |")
    A("|---|---|---|---|")
    A(row3("shipped", "kept"))
    A(row3("-- bicycle", "kept_bicycle"))
    A(row3("-- mtb", "kept_mtb"))
    A(row3("-- closed loops", "kept_loops"))
    A(row3("-- unnamed (now labelled)", "unnamed_kept"))
    A(row3("-- more than one geometry piece", "multi_segment_kept"))
    A("| km, clipped and simplified (shipped, all off-road) | %.1f | %.1f | %.1f |"
      % (wa_c["kept_km_simplified"], or_c["kept_km_simplified"], c["km_clipped_simplified"]))
    A("| road geometry removed from the shapes | %.1f | %.1f | %.1f |"
      % (wa_c["class_mix_km_clipped"]["removed_from_shipped_geometry"],
         or_c["class_mix_km_clipped"]["removed_from_shipped_geometry"],
         wa_c["class_mix_km_clipped"]["removed_from_shipped_geometry"]
         + or_c["class_mix_km_clipped"]["removed_from_shipped_geometry"]))
    A("| geometry points (shipped) | %d | %d | %d |"
      % (wa_c["kept_points"], or_c["kept_points"], c["geometry_points"]))
    A("")
    A("Size on disk: **%.2f MB** (`docs/data/trails_regional.json`)." % (size / 1e6))
    A("")
    A("These lengths are the SUM of the relation lengths and OSM relations overlap:")
    A("long-distance routes are assembled from the same ways as the local ones, so this is")
    A("not the number of distinct km of trail in the region.")
    A("")
    A("## Geometry pieces")
    A("")
    A("Members are joined only where two ways share an end node, so no straight-line jump is")
    A("invented (the D4 measurement mistake). Relations whose members are listed out of order")
    A("are repaired greedily; the rest stay as separate pieces.")
    A("")
    A("| | Washington | Portland metro | Total |")
    A("|---|---|---|---|")
    A(row3("relations that chain into ONE piece", "pieces|one_piece"))
    A(row3("relations in more than 10 pieces", "pieces|over_10_pieces"))
    A(row3("relations whose largest piece is >=90% of their length",
           "pieces|largest_piece_share_over_0.9"))
    A("")
    A("The most fragmented shipped relations (network-scale relations, not a single rideable")
    A("trail):")
    A("")
    A("| relation | name | pieces | length |")
    A("|---|---|---|---|")
    for e in sorted(wa_c["most_fragmented"] + or_c["most_fragmented"],
                    key=lambda x: -x["pieces"])[:8]:
        A("| %d | %s | %d | %.1f km |"
          % (e["id"], e["name"] or "(unnamed)", e["pieces"], e["length_km"]))
    A("")
    A("## The longest shipped entries (sanity check, not a recommendation)")
    A("")
    A("| relation | name | route | region | length | off-road | pieces | loop |")
    A("|---|---|---|---|---|---|---|---|")
    for t in doc["trails"][:10]:
        A("| %d | %s | %s | %s | %.1f km | %.2f | %d | %s |"
          % (t["id"], t["name"], t["route"], t["region"], t["length_km_clipped"],
             t["offroad_share"], len(t["segments"]), "yes" if t["loop"] else "no"))
    A("")
    A("## Duplicate mappings removed")
    A("")
    A("- **%d** relation id(s) returned by both sources (the Portland bbox reaches into"
      % len(dup_dropped))
    A("  Clark County, WA); the Washington extract's copy is kept.")
    A("- **%d** relation(s) with identical points under a different relation id (names may"
      % len(geom_dropped))
    A("  differ): a duplicate mapping in OSM. Exact point equality is the test, so two routes")
    A("  that merely overlap are both kept.")
    A("")
    if geom_dropped:
        A("| relation | name | kept relation | region | length |")
        A("|---|---|---|---|---|")
        for d in geom_dropped[:8]:
            A("| %d | %s | %d | %s | %.1f km |"
              % (d["id"], d["name"] or "(unnamed)", d["kept_id"], d["region"],
                 d["length_km_clipped"]))
        A("")
    A("## Simplification measurements")
    A("")
    A("Douglas-Peucker, applied per geometry piece, over the shipped set:")
    A("")
    A("| tolerance | geometry points | reduction | total km | max deviation |")
    A("|---|---|---|---|---|")
    for r in rows:
        A("| %.1f m | %d | %.1f%% | %.2f | %.1f m |"
          % (r["tolerance_m"], r["points"], r["point_reduction_pct"], r["km"],
             r["max_deviation_m"]))
    A("| (clipped, unsimplified) | %d | -- | %.2f | -- |"
      % (m["geometry_points_before_simplification"], m["km_before_simplification"]))
    A("")
    A("**Chosen tolerance: %.1f m.** %s" % (m["tolerance_m"], m["rationale"]))
    A("")
    A("## Records")
    A("")
    A("One record per relation, with these fields:")
    A("")
    for f in doc["selection"]["fields"]:
        A("- `%s`" % f)
    A("")
    A("Exactly what the length and shape fields mean:")
    A("")
    for k, v in doc["selection"]["field_notes"].items():
        A("- `%s` -- %s" % (k, v))
    A("")
    A("So `length_km_clipped` is the rideable length inside the region, and")
    A("`length_km_source_unclipped` is the whole relation as the source holds it. `loop` is")
    A("true when the largest piece starts and ends within %.0f m of itself." % LOOP_CLOSE_GAP_M)
    A("")
    A("## Licence")
    A("")
    A("OpenStreetMap data, **ODbL 1.0**. The app must display")
    A('"(c) OpenStreetMap contributors" wherever this data is shown. This file is a')
    A("Derivative Database and stays under the ODbL. See `docs/DATA-LICENSES.md`.")
    A("")
    A("## Build-time dependency")
    A("")
    A("Reading the local Washington `.pbf` needs **pyosmium** (`uv pip install osmium`).")
    A("The Android app gains **no** new runtime dependency: it only reads the JSON.")
    A("")
    with open(OUT_MD, "w") as fh:
        fh.write("\n".join(lines))
    print("wrote %s (%d bytes)" % (os.path.relpath(OUT_MD, ROOT), os.path.getsize(OUT_MD)))


if __name__ == "__main__":
    main()
