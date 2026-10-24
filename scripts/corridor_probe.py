#!/usr/bin/env python3
"""Corridor-access probe: mirror the app's D10/D14 grader in Python, then drive Valhalla.

Everything the app does is reproduced here from source (read-only):
  app/src/main/java/com/biketrailplanner/domain/SafetyGrader.kt
  app/src/main/java/com/biketrailplanner/domain/TrafficTable.kt
  app/src/main/java/com/biketrailplanner/domain/LimitsTable.kt
  app/src/main/java/com/biketrailplanner/domain/StreetNames.kt
  app/src/main/java/com/biketrailplanner/data/{TrafficLoader,LimitsLoader,TrailRepository}.kt
  assets/config.json, assets/traffic.json, assets/limits.json

Raw requests and responses land in docs/corridor-raw/{requests,responses}/<tag>.json
"""
import json, math, os, pathlib, re, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = ROOT / "docs" / "corridor-raw"
VALHALLA = "http://127.0.0.1:8002"
HOME = (47.240371, -122.262019)          # assets/config.json

# ----------------------------------------------------------------------------- Valhalla client

EDGE_ATTRS = [
    "edge.road_class", "edge.use", "edge.surface", "edge.unpaved", "edge.cycle_lane",
    "edge.bicycle_network", "edge.shoulder", "edge.speed_limit", "edge.lanes", "edge.length",
    "edge.names", "edge.way_id", "edge.cycleway", "edge.begin_shape_index", "edge.end_shape_index",
    "edge.speed", "edge.traversability",
]

CALLS = []          # (tag, body, status, response)


def vpost(action, body, tag=None, base=VALHALLA, retries=3, timeout=180):
    data = json.dumps(body).encode()
    req = urllib.request.Request(f"{base}/{action}", data=data,
                                 headers={"Content-Type": "application/json"}, method="POST")
    status, payload = None, None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                status = resp.status
                payload = resp.read().decode()
            break
        except urllib.error.HTTPError as e:
            status = e.code
            payload = e.read().decode()
            break
        except Exception:
            if attempt == retries - 1:
                raise
            time.sleep(2)
    try:
        j = json.loads(payload)
    except Exception:
        j = {"_non_json": (payload or "")[:2000]}
    CALLS.append((tag, body, status, j))
    if tag:
        (RAW / "requests").mkdir(parents=True, exist_ok=True)
        (RAW / "responses").mkdir(parents=True, exist_ok=True)
        (RAW / "requests" / f"{tag}.json").write_text(json.dumps(body, indent=1))
        (RAW / "responses" / f"{tag}.json").write_text(json.dumps(j, indent=1))
    return status, j


def route(locations, costing="bicycle", costing_options=None, tag=None,
          exclude_polygons=None, units="kilometers", extra=None):
    body = {"locations": [{"lat": p[0], "lon": p[1]} for p in locations],
            "costing": costing, "units": units, "directions_options": {"units": "miles"}}
    if costing_options:
        body["costing_options"] = {costing: costing_options}
    if exclude_polygons:
        body["exclude_polygons"] = [ring for ring in exclude_polygons for ring in ([ring] if isinstance(ring[0][0], float) else ring)]
    if extra:
        body.update(extra)
    return vpost("route", body, tag)


def trace(shape, costing="bicycle", tag=None, units="kilometers"):
    body = {"encoded_polyline": shape, "shape_match": "edge_walk", "costing": costing,
            "units": units, "filters": {"action": "include", "attributes": EDGE_ATTRS}}
    return vpost("trace_attributes", body, tag)


def decode_polyline6(s):
    """Valhalla encoded polyline (precision 6)."""
    coords, index, lat, lon = [], 0, 0, 0
    while index < len(s):
        for is_lon in (False, True):
            shift, result = 0, 0
            while True:
                b = ord(s[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if (result & 1) else (result >> 1)
            if is_lon:
                lon += delta
            else:
                lat += delta
        coords.append((lat / 1e6, lon / 1e6))
    return coords


def metres(a, b):
    R = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


# ----------------------------------------------------------------------------- app grading model

CURRENT_YEAR = 2026
STALE_YEARS = 10
LOW_MAX, MEDIUM_MAX = 1500, 7000


def normalise(name):
    words = {"blvd": "boulevard", "ave": "avenue", "av": "avenue", "st": "street", "rd": "road",
             "dr": "drive", "hwy": "highway", "ln": "lane", "ct": "court", "pl": "place",
             "pkwy": "parkway", "cir": "circle", "ter": "terrace", "trl": "trail", "sq": "square",
             "e": "east", "w": "west", "n": "north", "s": "south", "ne": "northeast",
             "nw": "northwest", "se": "southeast", "sw": "southwest"}
    s = re.sub(r"[^a-z0-9 ]", " ", (name or "").lower())
    return " ".join(words.get(t, t) for t in s.split() if t)


def band_for(aadt):
    if aadt is None or aadt <= 0:
        return "unknown"
    if aadt < LOW_MAX:
        return "low"
    if aadt <= MEDIUM_MAX:
        return "medium"
    return "high"


class Count:
    __slots__ = ("road_name", "aadt", "year", "confidence", "lts", "band", "way_ids", "names", "source")

    def __init__(self, road_name, aadt, year, confidence, lts, band, way_ids, names, source):
        self.road_name, self.aadt, self.year, self.confidence = road_name, aadt, year, confidence
        self.lts, self.band, self.way_ids, self.names, self.source = lts, band, way_ids, names, source

    @property
    def usable(self):
        return self.aadt is not None and self.aadt > 0 and self.confidence in ("measured", "stale")

    @property
    def text(self):
        if self.aadt is None:
            return "volume not known"
        t = f"{self.aadt} vpd" + (f" ({self.year})" if self.year else "")
        if self.confidence == "stale":
            t += " STALE"
        elif self.confidence == "estimate":
            t += " estimate, not a count"
        return t


class TrafficTable:
    def __init__(self, counts):
        self.counts = counts
        self.by_way = {}
        self.by_name = {}
        for c in counts:
            for w in c.way_ids:
                self.by_way[w] = c
            for n in list(c.names) + [c.road_name]:
                self.by_name[normalise(n)] = c

    def lookup(self, way_ids, names):
        for i in way_ids:
            if i in self.by_way:
                return self.by_way[i], "way id"
        for n in names:
            if normalise(n) in self.by_name:
                return self.by_name[normalise(n)], "street name"
        return None, None


def load_traffic(path):
    rows = json.load(open(path))
    out = []
    for o in rows:
        way_ids = o.get("osm_way_ids") or ([o["osm_way_id"]] if o.get("osm_way_id") else [])
        name = o.get("road_name") or o.get("name") or (f"way {way_ids[0]}" if way_ids else None)
        if name is None:
            continue
        raw = o.get("aadt")
        aadt = raw if (raw is not None and raw > 0) else None
        conf = "unknown" if aadt is None else (o.get("aadt_confidence") or "measured")
        band = "unknown" if aadt is None else (o.get("volume_band") or band_for(aadt))
        out.append(Count(name, aadt, o.get("aadt_year"), conf, o.get("lts"), band, way_ids,
                         [name], o.get("source") or "unspecified"))
    return out


def load_limits_rows(path):
    root = json.load(open(path))
    rows = []
    roads = root.get("roads") or {}
    if isinstance(roads, dict):
        for key, o in roads.items():
            rows.append({"name": o.get("road_name") or o.get("name") or key,
                         "speed_mph": o.get("posted_speed_mph") or o.get("speed_mph") or o.get("speed"),
                         "osm_way_id": o.get("osm_way_id") or o.get("way_id"),
                         "aadt": o.get("aadt"), "year": o.get("aadt_year"),
                         "confidence": o.get("aadt_confidence"), "lts": o.get("lts"),
                         "source": o.get("source")})
    else:
        for o in roads:
            rows.append({"name": o.get("name") or o.get("road_name"),
                         "speed_mph": o.get("speed_mph") or o.get("posted_speed_mph"),
                         "osm_way_id": o.get("osm_way_id"), "aadt": o.get("aadt"),
                         "year": o.get("aadt_year"), "confidence": o.get("aadt_confidence"),
                         "lts": o.get("lts"), "source": o.get("source")})
    return rows


class Limits:
    def __init__(self, rows):
        self.rows = rows
        self.by_way = {r["osm_way_id"]: r for r in rows if r["osm_way_id"] and r["speed_mph"]}
        self.by_name = {normalise(r["name"]): r for r in rows if r["name"] and r["speed_mph"]}

    def override(self, way_ids, names):
        for i in way_ids:
            if i in self.by_way:
                return self.by_way[i]
        for n in names:
            if normalise(n) in self.by_name:
                return self.by_name[normalise(n)]
        return None


TRAIL_USES = {"cycleway", "path", "mountain_bike"}
REJECT_SURFACES = {"dirt", "rough", "impassable"}
RESIDENTIAL_CLASSES = {"residential", "service_other", "service"}
CAUTION_CLASSES = {"tertiary", "secondary"}
NEVER_CLASSES = {"primary", "trunk", "motorway"}
BETTER_THAN_C = {"A", "B"}


class Grader:
    """Exact mirror of SafetyGrader.kt + TrafficTable.kt."""

    def __init__(self, traffic, limits):
        self.traffic, self.limits = traffic, limits

    def grade(self, edge):
        names = edge.get("names") or []
        names = [names] if isinstance(names, str) else list(names)
        way_id = edge.get("way_id")
        ov = self.limits.override([way_id], names)
        osm_kph = edge.get("speed_limit")
        osm_mph = None if osm_kph is None else int(round(osm_kph * 0.621371))
        if ov and ov["speed_mph"]:
            speed, src = int(ov["speed_mph"]), "override"
        elif osm_mph is not None:
            speed, src = osm_mph, "osm"
        else:
            speed, src = None, "none"

        road = edge.get("road_class")
        use = edge.get("use")
        lane = edge.get("cycle_lane") or "none"
        surface = edge.get("surface")
        separate = use in TRAIL_USES
        protected = lane == "dedicated"
        separated = lane == "separated"
        bnet = edge.get("bicycle_network") or 0

        g, reason, hole = None, "", False
        if surface in REJECT_SURFACES:
            g, reason = "REJECTED", f"D11: surface={surface} is rejected"
        elif road in NEVER_CLASSES and not (separate or separated or protected):
            g, reason = "E", f"road_class={road} is a D10 never-E road"
        elif speed is not None and speed >= 40 and not (separate or separated or protected):
            g, reason = "E", f"{speed} mph with no dedicated lane or separated path"
        elif separate:
            g, reason = "A", f"use={use} is an off-street facility"
        elif separated:
            g, reason = "A", "cycle_lane=separated"
        elif bnet > 0:
            g, reason = "A", f"bicycle_network={bnet}"
        elif protected and speed is not None and speed <= 25:
            g, reason = "B", f"cycle_lane=dedicated at {speed} mph"
        elif speed is not None and speed <= 25 and (road in RESIDENTIAL_CLASSES or use == "living_street"):
            g, reason = "C", f"{road or use} at {speed} mph"
        elif speed is not None and 30 <= speed <= 35 and road in CAUTION_CLASSES:
            g, reason = "D", f"{road} at {speed} mph with no dedicated lane"
        elif speed is None:
            g, reason = "UNGRADED", f"no maxspeed in OSM and no override for {names[0] if names else 'way %s' % way_id}"
        else:
            g, reason = "D", f"ladder hole: {road or use} at {speed} mph matches no rule, graded conservatively as D"
            hole = True

        count, kind = self.traffic.lookup([way_id], names)
        if count is None:
            return {"grade": g, "reason": reason, "speed_mph": speed, "speed_source": src,
                    "hole": hole, "volume": None, "match_kind": None, "lts": None,
                    "way_id": way_id, "names": names, "road_class": road, "use": use,
                    "cycle_lane": lane, "surface": surface, "length_km": edge.get("length")}

        stale_years = None
        if count.year is not None and CURRENT_YEAR - count.year > STALE_YEARS:
            stale_years = CURRENT_YEAR - count.year
        effective = count
        if count.confidence == "measured" and stale_years is not None:
            effective = Count(count.road_name, count.aadt, count.year, "stale", count.lts,
                              count.band, count.way_ids, count.names, count.source)
        true_facility = separate or separated
        if effective.usable and not true_facility:
            if effective.band == "low":
                if g in ("D", "UNGRADED"):
                    g, reason, hole = "C", f"measured {effective.text}, under {LOW_MAX} vpd: low volume cannot be grade D", False
            elif effective.band == "medium":
                if g == "D" and hole and speed is not None and speed <= 25:
                    g, reason, hole = "C", f"measured {effective.text} with a known {speed} mph limit: a shared quiet road", False
            elif effective.band == "high":
                if not (g in ("E", "B") or (g == "A" and true_facility)):
                    reason = (f"measured {effective.text}, over {MEDIUM_MAX} vpd: a road this busy is "
                              + ("not a bicycle route" if g == "A" else "never a quiet shared road"))
                    g, hole = "D", False
        cap_reasons = []
        if stale_years is not None or count.confidence == "stale":
            cap_reasons.append("the count is " + (f"{stale_years} years old" if stale_years else "stale"))
        if count.lts is not None and count.lts >= 4:
            cap_reasons.append(f"published LTS {count.lts}")
        if cap_reasons and not true_facility and g in BETTER_THAN_C:
            g, reason = "C", "capped at C (D14): " + " and ".join(cap_reasons)
        return {"grade": g, "reason": reason, "speed_mph": speed, "speed_source": src, "hole": hole,
                "volume": effective.text, "volume_usable": effective.usable,
                "match_kind": kind, "lts": count.lts, "way_id": way_id, "names": names,
                "road_class": road, "use": use, "cycle_lane": lane, "surface": surface,
                "length_km": edge.get("length"), "aadt": effective.aadt, "year": effective.year,
                "confidence": effective.confidence, "band": effective.band if effective.usable else "unknown"}


def grade_route(shape, tag=None, grader=None):
    """Trace a Valhalla shape and grade every edge, mirroring Planner.gradeEdges()."""
    status, t = trace(shape, tag=tag)
    if "edges" not in t:
        return None, t
    segs = []
    for e in t["edges"]:
        g = grader.grade(e)
        g["length_m"] = e.get("length", 0.0) * 1000.0
        segs.append(g)
    return segs, t


def breakdown(segs, repeat=1):
    out = {}
    total = 0.0
    ungraded = 0.0
    for s in segs:
        m = s["length_m"] * repeat
        total += m
        if s["grade"] == "UNGRADED":
            ungraded += m
        else:
            out[s["grade"]] = out.get(s["grade"], 0.0) + m
    graded = total - ungraded
    return {"total_m": total, "graded_m": graded, "ungraded_m": ungraded, "by_grade": out}


def d10(breakdown_d, d_cap_share=0.10, d_cap_miles=0.5):
    """Mirror of SafetyGrader.evaluateD10 + the D13 factor check."""
    graded = breakdown_d["graded_m"]
    dm = breakdown_d["by_grade"].get("D", 0.0)
    em = breakdown_d["by_grade"].get("E", 0.0)
    v = []
    if em > 0:
        v.append(f"grade E: {em:.0f} m, and D10 allows none")
    if graded > 1.0:
        share = dm / graded
        miles = dm / 1609.344
        if share > d_cap_share:
            v.append(f"grade D is {share*100:.1f}% of the graded route (cap {d_cap_share*100:.0f}%)")
        if miles > d_cap_miles:
            v.append(f"grade D is {miles:.2f} mi (cap {d_cap_miles:.2f} mi)")
    return {"passes": not v, "violations": v,
            "d_m": dm, "e_m": em, "d_share_of_graded": (dm / graded if graded > 1 else None),
            "d_miles": dm / 1609.344, "ungraded_share": breakdown_d["ungraded_m"] / breakdown_d["total_m"] if breakdown_d["total_m"] else 0.0}


def named(segs, repeat=1):
    acc = {}
    for s in segs:
        key = ((s["names"] or ["(unnamed)"])[0], s["grade"])
        acc[key] = acc.get(key, 0.0) + s["length_m"] * repeat
    return sorted(({"name": k[0], "grade": k[1], "m": v} for k, v in acc.items()),
                  key=lambda x: -x["m"])


# --- the app's exact costing (ValhallaClient.routeRequest) -------------------------------
APP_COSTING = {"bicycle_type": "hybrid", "use_roads": 0.1, "avoid_bad_surfaces": 0.3, "use_hills": 0.25}


def route_app(locations, tag=None, exclude_polygons=None, extra=None):
    """POST /route with exactly the body the app builds."""
    return route(locations, costing="bicycle", costing_options=dict(APP_COSTING), tag=tag,
                 exclude_polygons=exclude_polygons, extra=extra)


def fix_exclude_body(body, polygons):
    """exclude_polygons: list of polygons; each polygon = [ring]; ring = [(lat,lon), ...]."""
    body["exclude_polygons"] = [
        [[{"lat": p[0], "lon": p[1]} for p in ring] for ring in poly] for poly in polygons
    ]
    return body
