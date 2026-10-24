#!/usr/bin/env python3
"""Safety-grade a Valhalla bicycle route against the D10 "capped caution" ladder.

Request shapes are the ones verified in docs/SERVER.md (sections 6-8). Two POSTs per route:
  1. POST /route            -> trip.legs[0].shape (encoded polyline)
  2. POST /trace_attributes -> one object per edge with road_class / use / surface /
                               cycle_lane / bicycle_network / speed_limit / length / names / way_id
then buckets edge metres into grades A-E (plus REJECT and GAP).

GRADE LADDER (decision D10; evaluated in this order)
  A  : edge.use in (cycleway, path, mountain_bike) OR cycle_lane == separated
       OR bicycle_network is set (non-zero)
  B  : cycle_lane == dedicated AND speed_limit <= 25 mph
  C  : road_class in (residential, service_other) OR use == living_street, AND speed_limit <= 25 mph
  D  : road_class in (tertiary, secondary) at 30-35 mph with no dedicated lane
  E  : road_class in (primary, trunk, motorway), OR speed_limit >= 40 mph with no dedicated lane
  REJECT : surface in (dirt, rough, impassable) -> edge dropped outright
  GAP : matches no rule above. Always reported, never folded into A/B/C.

MISSING SPEED LIMIT. This Valhalla build omits `speed_limit` on 105 of 178 edges (58 %) of the
baseline route, and `edge.lanes` is not returned at all on this build (0/178), so "grade using
road_class and lanes" degenerates to road_class only. Three assumption modes are computed and ALL
are reported; none is silently preferred:
  strict    : missing speed limit => the edge cannot be graded => treated as D
  lenient   : missing => OSM `maxspeed` observed elsewhere on the same named street (same
              road_class), else Valhalla's own WA default speed table
              (valhalla/custom_files/default_speeds.json, "suburban" row)
  generous  : missing => assumed posted at 25 mph (the most favourable assumption possible)

ORDER NOTE: the A test runs BEFORE the missing-speed-limit rule, because a cycleway/path edge has
no posted car speed limit by nature; grading a path "D because speed_limit is missing" is nonsense.
All other grades are speed-dependent.
"""
import json, sys, pathlib, time, urllib.request, urllib.error

VALHALLA = "http://127.0.0.1:8002"
HOME = {"lat": 47.240371, "lon": -122.262019}

EDGE_ATTRS = [
    "edge.road_class", "edge.use", "edge.surface", "edge.unpaved", "edge.cycle_lane",
    "edge.bicycle_network", "edge.shoulder", "edge.speed_limit", "edge.speed", "edge.lanes",
    "edge.sac_scale", "edge.length", "edge.names", "edge.way_id", "edge.cycleway",
    "edge.weighted_grade", "edge.begin_shape_index",
]

KMH_PER_MPH = 1.609344

HIGHWAY_CLASS = {"trunk": "trunk", "primary": "primary", "secondary": "secondary",
                 "tertiary": "tertiary", "unclassified": "unclassified",
                 "residential": "residential", "service": "service_other"}

WA_DEFAULT_MPH = {  # valhalla/custom_files/default_speeds.json, US/WA, mph
    "rural":    {"motorway":105,"trunk":90,"primary":74,"secondary":59,"tertiary":51,"unclassified":31,"residential":21,"service_other":7},
    "suburban": {"motorway":97, "trunk":66,"primary":45,"secondary":44,"tertiary":39,"unclassified":21,"residential":18,"service_other":9},
    "urban":    {"motorway":94, "trunk":67,"primary":39,"secondary":35,"tertiary":33,"unclassified":18,"residential":18,"service_other":10},
}
WA_DEFAULT_ROW = "suburban"
GENEROUS_MISSING_MPH = 25
DEDICATED = ("dedicated", "separated")
BAD_SURFACE = ("dirt", "rough", "impassable")
MODES = ("strict", "lenient", "generous", "lenient_repair", "generous_repair")


def vpost(action, body, tag=None, base=VALHALLA, retries=3, timeout=120):
    """POST JSON to a Valhalla action. Stdlib only (no third-party deps)."""
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
    if tag:
        pathlib.Path("docs/safety-probe-raw/requests").mkdir(parents=True, exist_ok=True)
        pathlib.Path("docs/safety-probe-raw/responses").mkdir(parents=True, exist_ok=True)
        pathlib.Path(f"docs/safety-probe-raw/requests/{tag}.json").write_text(json.dumps(body, indent=1))
        pathlib.Path(f"docs/safety-probe-raw/responses/{tag}.json").write_text(json.dumps(j, indent=1))
    return status, j


def route(locations, costing="bicycle", costing_options=None, tag="r", units="kilometers"):
    body = {"locations": locations, "costing": costing, "units": units,
            "directions_options": {"units": "miles"}}
    if costing_options:
        body["costing_options"] = {costing: costing_options}
    return vpost("route", body, tag)


def trace(shape, costing="bicycle", tag="t", units="kilometers"):
    body = {"encoded_polyline": shape, "shape_match": "edge_walk", "costing": costing,
            "units": units, "filters": {"action": "include", "attributes": EDGE_ATTRS}}
    return vpost("trace_attributes", body, tag)


def metres(edge, units="kilometers"):
    return edge["length"] * (1000.0 if units == "kilometers" else 1609.344)


def grade_edge(e, mode, street_limits):
    """Return (grade, assumed_mph_or_None).

    mode is one of MODES. The *_repair variants resolve the ladder's own holes, which are:
      * tertiary/secondary at 26-29 mph and at 36-39 mph with no dedicated lane matches NO rule
        (D covers 30-35, E starts at 40). 39 mph is not a contrived number: it is Valhalla's own
        default speed for a WA suburban tertiary road, so every unposted tertiary road lands here.
      * tertiary/secondary WITH a dedicated lane at >25 mph matches no rule
        (B caps at 25, D requires no dedicated lane, E requires no dedicated lane).
    A *_repair mode does not invent a new policy: it only EXTENDS an existing band, and the
    direction of every repair is toward the LOWER grade (D band widened to 26-39, low-speed
    shared tertiary/secondary -> C, dedicated lane -> B). Repairs can therefore only help a route,
    never hurt it, so a failure under *_repair is the strongest form of the failure claim.
    """
    repair = mode.endswith("_repair")
    base = mode[:-7] if repair else mode
    if e.get("surface") in BAD_SURFACE:
        return "REJECT", None
    use = e.get("use", ""); cl = e.get("cycle_lane", "none")
    bn = e.get("bicycle_network", 0) or 0; rc = e.get("road_class", "")
    nm = (e.get("names") or [""])[0]

    # --- A is facility-based, evaluated first (see ORDER NOTE) ---
    if use in ("cycleway", "path", "mountain_bike") or cl == "separated" or bn:
        return "A", None

    sl_kmh = e.get("speed_limit") or 0
    assumed = None
    if sl_kmh:
        sl = sl_kmh / KMH_PER_MPH
    elif base == "strict":
        return "D", None            # ungradable -> D (D10 strict reading)
    elif base == "generous":
        sl = GENEROUS_MISSING_MPH; assumed = sl
    else:
        way_mph, name_mph = street_limits
        if e.get("way_id") in way_mph:
            sl = way_mph[e["way_id"]]
        elif f"{nm}|{rc}" in name_mph:
            sl = name_mph[f"{nm}|{rc}"]
        else:
            sl = WA_DEFAULT_MPH[WA_DEFAULT_ROW].get(rc, 35)
        assumed = sl

    # --- E ---
    if rc in ("primary", "trunk", "motorway") or (sl >= 40 and cl not in DEDICATED):
        return "E", assumed
    # --- D ---
    if rc in ("tertiary", "secondary") and cl not in DEDICATED:
        if 30 <= sl <= 35:
            return "D", assumed
        if repair:
            if sl <= 25:
                return "C", assumed
            if sl <= 39:
                return "D", assumed
    # --- B ---
    if cl == "dedicated" and sl <= 25:
        return "B", assumed
    if repair and cl in DEDICATED and rc in ("tertiary", "secondary"):
        return "B", assumed
    # --- C ---
    if (rc in ("residential", "service_other") or use == "living_street") and sl <= 25:
        return "C", assumed
    return "GAP", assumed


def breakdown(edges, mode, street_limits, units="kilometers"):
    per, named = {}, {}
    for e in edges:
        g, assumed = grade_edge(e, mode, street_limits)
        m = metres(e, units)
        per[g] = per.get(g, 0.0) + m
        rec = named.setdefault(g, {})
        key = (", ".join(e.get("names") or ["(no name)"]) or "(no name)",
               e.get("road_class", ""), e.get("cycle_lane", "none"))
        r = rec.setdefault(key, {"m": 0.0, "n": 0, "assumed_mph": set()})
        r["m"] += m; r["n"] += 1
        if assumed is not None:
            r["assumed_mph"].add(round(assumed))
    total = sum(per.values()) or 1.0
    return per, named, total


def report(edges, street_limits, units="kilometers", label=""):
    out = {"label": label, "n_edges": len(edges), "modes": {}}
    for mode in MODES:
        per, named, total = breakdown(edges, mode, street_limits, units)
        out["modes"][mode] = {
            "metres": {k: round(v, 1) for k, v in sorted(per.items())},
            "share": {k: round(100 * v / total, 2) for k, v in sorted(per.items())},
            "total_m": round(total, 1),
            "named": {g: [{"street": k[0], "road_class": k[1], "cycle_lane": k[2],
                           "m": round(v["m"], 1), "edges": v["n"],
                           "assumed_mph": sorted(v["assumed_mph"])}
                          for k, v in sorted(v2.items(), key=lambda kv: -kv[1]["m"])]
                      for g, v2 in named.items()},
        }
    return out


def verdict(rep, mode, d_cap_m=804.672, d_cap_share=10.0):
    """D10 test: grades A/B/C only, <=10 % and <=0.5 mi (804.672 m) of D, never E."""
    mm = rep["modes"][mode]["metres"]
    d = mm.get("D", 0.0); e = mm.get("E", 0.0)
    gap = mm.get("GAP", 0.0); rej = mm.get("REJECT", 0.0)
    tot = rep["modes"][mode]["total_m"]
    fails = []
    if e > 0: fails.append(f"E = {e:.0f} m")
    if gap > 0: fails.append(f"GAP (ladder-unclassified) = {gap:.0f} m")
    if rej > 0: fails.append(f"REJECT (dirt/rough/impassable) = {rej:.0f} m")
    if d > d_cap_m: fails.append(f"D = {d:.0f} m > {d_cap_m:.0f} m cap")
    if tot and 100 * d / tot > d_cap_share: fails.append(f"D = {100*d/tot:.1f}% > {d_cap_share:.0f}% cap")
    return {"mode": mode, "pass": not fails, "fails": fails,
            "A_m": round(mm.get("A", 0.0), 1), "B_m": round(mm.get("B", 0.0), 1),
            "C_m": round(mm.get("C", 0.0), 1),
            "D_m": round(d, 1), "E_m": round(e, 1), "GAP_m": round(gap, 1),
            "REJECT_m": round(rej, 1), "total_m": round(tot, 1),
            "D_share_pct": round(100 * d / tot, 2) if tot else None}


def load_osm_speed_tables(path="docs/safety-probe-raw/osm/roads-tags-compact.json"):
    """Read the OSM highway extract already on disk (source overpass-api.de, osm base
    2026-09-15T21:06:55Z - the same week as the Valhalla tile build) and build two tables:
      way_mph  : OSM way id -> posted maxspeed in mph, for that exact way
      name_mph : (name, road_class) -> MINIMUM mph posted anywhere on that named road class
    The minimum is used deliberately: it is the most FAVOURABLE assumption for the rider, so a
    route that still fails under it fails robustly.
    The file is a compact derivative; its provenance and the full extract's size are recorded in
    docs/SAFETY-PROBE.md (the 11.2 MB original was deleted under the 2 MB raw-file rule)."""
    p = pathlib.Path(path)
    if not p.exists():
        return {}, {}
    doc = json.loads(p.read_text())
    ways = doc.get("ways")
    if ways is None:  # full overpass form, one element per way
        ways = [{"w": e["id"], **{k: v for k, v in e.get("tags", {}).items()}}
                for e in doc["result"]["elements"] if e["type"] == "way"]
    way_mph, name_mph = {}, {}
    for w in ways:
        t = w.get("tags", w)
        mph = parse_maxspeed(t.get("maxspeed", t.get("m")))
        if mph is None:
            continue
        way_mph[w["w"] if "w" in w else w["id"]] = mph
        nm, hw = t.get("name", t.get("n")), t.get("highway", t.get("h"))
        cls = HIGHWAY_CLASS.get(hw)
        if nm and cls:
            k = f"{nm}|{cls}"
            name_mph[k] = min(mph, name_mph.get(k, mph))
    return way_mph, name_mph


def parse_maxspeed(raw):
    """OSM maxspeed -> mph. Returns None for non-numeric values (none/signals/walk/...)."""
    if raw is None:
        return None
    t = str(raw).strip().lower()
    num = ""
    for ch in t:
        if ch.isdigit():
            num += ch
        elif num:
            break
    if not num:
        return None
    v = int(num)
    if "km" in t or "kph" in t:
        return round(v / KMH_PER_MPH)
    return v


def load_osm_speed_tables(path="docs/safety-probe-raw/osm/roads-bbox.json"):
    """Read the OSM extract already on disk (overpass-api.de, osm base 2026-09-15T21:06:55Z,
    the same week as the Valhalla tile build) and build two lookup tables:
      way_mph  : OSM way id -> posted maxspeed in mph, for that exact way
      name_mph : (name, road_class) -> MINIMUM mph posted anywhere on that named road class
    The minimum is used deliberately: it is the most FAVOURABLE assumption for the rider, so a
    route that still fails under it fails robustly."""
    p = pathlib.Path(path)
    if not p.exists():
        return {}, {}
    ways = [e for e in json.loads(p.read_text())["result"]["elements"] if e["type"] == "way"]
    way_mph, name_mph = {}, {}
    for w in ways:
        t = w.get("tags", {})
        mph = parse_maxspeed(t.get("maxspeed"))
        if mph is None:
            continue
        way_mph[w["id"]] = mph
        nm, hw = t.get("name"), t.get("highway")
        cls = HIGHWAY_CLASS.get(hw)
        if nm and cls:
            k = f"{nm}|{cls}"
            name_mph[k] = min(mph, name_mph.get(k, mph))
    return way_mph, name_mph


if __name__ == "__main__":
    job = json.loads(pathlib.Path(sys.argv[1]).read_text())
    street_limits = load_osm_speed_tables()
    tag = job["tag"]
    st, rj = route([HOME, job["destination"]], costing_options=job.get("costing_options"), tag=tag)
    print("route HTTP", st)
    if st != 200:
        print(json.dumps(rj)[:500]); sys.exit(1)
    print("summary", json.dumps(rj["trip"]["summary"]))
    print("arrived_at", json.dumps(rj["trip"]["locations"][-1]))
    st2, tj = trace(rj["trip"]["legs"][0]["shape"], tag="trace_" + tag)
    print("trace HTTP", st2, "edges", len(tj.get("edges", [])))
    rep = report(tj["edges"], street_limits, label=tag)
    rep["route_summary"] = rj["trip"]["summary"]
    rep["arrived_at"] = rj["trip"]["locations"][-1]
    pathlib.Path(f"docs/safety-probe-raw/responses/report_{tag}.json").write_text(json.dumps(rep, indent=1))
    print(json.dumps(rep["modes"]["lenient"]["metres"], indent=1))
    print(json.dumps(rep["modes"]["lenient"]["named"], indent=1))
    for mode in MODES:
        print(mode, json.dumps(verdict(rep, mode)))
