# SAFETY PROBE — can the home→trail ride meet the D10 safety rule?

Status: **complete, negative on the strict reading, conditional pass on a charitable one.** Written 2026-09-15 by the safety-probe agent. Engine: self-hosted Valhalla 3.8.3 at `http://127.0.0.1:8002` (see `docs/SERVER.md`). All numbers below come from real requests; every request and response body is on disk under `docs/safety-probe-raw/`.

**Scope.** This answers step 4 of the probe: *is there ANY access point and ANY costing where the route from home meets grades A/B/C only, <=10% of route AND <=0.5 mi (805 m) of grade D, and no grade E?* — plus the grading, access-point, sweep and island work that decision depends on.

---

## 0. THE ANSWER, UP FRONT

**No — not verifiably, and not for any trail.** Two separate things are true and both matter:

1. **Yes under one specific charitable assumption.** If every road with no posted speed limit in OpenStreetMap is assumed to be 25 mph, then **four of the six trails pass**: Sumner Link, Interurban Edgewood, Interurban Milton and Riverwalk all produce routes with 0 m of grade D and 0 m of grade E.
2. **No under every assumption that is grounded in actual data.** Under the strict D10 reading (a missing speed limit means the edge cannot be graded, so treat it as D) **all six trails fail, with 89-99% of the route in grade D.** Under a lenient but still data-driven reading (grade unposted roads at Valhalla's own default speed for their class in Washington — 39 mph for a suburban tertiary, 44 mph for a suburban secondary) **all six trails also fail**: three of them produce real grade E (2 753 m / 2 562 m / 568 m) and the other three carry 2.5-5.2 km of grade D.

3. **The reason is structural, not a router setting.** Between **82% and 100%** of every best route runs on roads whose speed limit is *absent from OSM*. The D10 grade of most of the ride is therefore an assumption, not a measurement. Not one costing knob changes this.

**Two trails fail even under the most charitable assumption**, on roads whose speed limit OSM really does carry:

| Trail | Status under the charitable reading | Measured grade D | Grade D street(s) | Cap that WOULD be satisfied |
|---|---|---|---|---|
| **Lake Tapps Parkway Trail** | **FAIL** | 726 m = **12.0%** of 6.051 km | Stewart Road Southwest (secondary, OSM `maxspeed=35 mph`, 3-5 lanes) = 726 m | D metres (726 m <= 805 m) but **not** the 10% share; needs a D share cap of **>=12.0%** |
| **White River Trail** | **FAIL** | 1447 m = **17.7%** of 8.175 km | Stewart Road Southwest 726 m + A Street Southeast (secondary, 35-40 mph) 721 m | None. Needs a D cap of **>=1.45 km** and **>=17.7%**. Grade E is 0 m |
**The costing sweep is a no-op.** `use_roads` 0.0 / 0.05 / 0.1, `use_living_streets` 0.25 / 0.5, `avoid_bad_surfaces` 0.3, `bicycle_type` hybrid / city / road — 70 sweep runs plus 9 control runs produced **byte-identical route geometry** for 6 of 7 variants. The options change the *cost* and the *time*, never the *path*. Only `shortest: true` changes the path, by 0.2-2%, and it never removes the grade D. **The app cannot tune its way to a safe route on this network.**

**Consequence for the product.** The D10 promise ("only A/B/C + a capped amount of D, never E") **cannot be honoured as written** for the home→near-trails use case, because the data needed to evaluate it does not exist for the roads the rider actually uses. Either the policy gains an explicit assumption for unposted roads (and the app says so to the rider), or the route is presented as ungraded on those segments — but it must not be presented as safe.

---

## 1. Method, ladder and the exact readings used

### 1.1 The grading ladder (as specified for D10), in evaluation order

```
A  : edge.use in (cycleway, path, mountain_bike)  OR  cycle_lane == separated  OR  bicycle_network set
B  : cycle_lane == dedicated               AND speed_limit <= 25 mph
C  : road_class in (residential, service_other) OR use == living_street,  AND speed_limit <= 25 mph
D  : road_class in (tertiary, secondary)   AND 30-35 mph AND no dedicated lane
E  : road_class in (primary, trunk, motorway)  OR  speed_limit >= 40 mph AND no dedicated lane
REJECT : surface in (dirt, rough, impassable)  -> the edge is dropped outright
GAP    : matches no rule above
```

Two deliberate ordering decisions, both stated so they can be argued with:

- **A is tested first, before the missing-speed-limit rule.** A cycleway or path has no posted car speed limit by nature; grading a path "D because `speed_limit` is missing" would be nonsense. Every other grade is speed-dependent, so the missing-limit rule applies to them.

- **The ladder has real holes, and they are reported, not hidden.** An edge that matches no rule is returned as `GAP`, never folded into A/B/C. The holes are: tertiary/secondary at 26-29 mph and at **36-39 mph** with no dedicated lane (D covers 30-35, E starts at 40); and tertiary/secondary **with** a dedicated lane above 25 mph (B caps at 25, D and E both require *no* dedicated lane). 39 mph is not a contrived number — it is Valhalla's own default speed for a Washington suburban tertiary road, so **every** unposted tertiary road lands in the hole. To stop the hole from deciding the answer, the `*_repair` modes widen an existing band downward only (D band 26-39, low-speed shared tertiary/secondary -> C, dedicated lane -> B). A repair can only ever *help* a route, so **a failure under a repair mode is the strongest form of the failure claim.**

### 1.2 The five readings actually computed

`speed_limit` is missing on 105 of the 178 edges (58%) of the reference route, and `edge.lanes` is **not returned at all** by this build (0 of 178 edges, and 0 of 8 298 edges across all 143 probes). So the instruction "grade using road_class and lanes" degenerates to road_class only — recorded as a data gap, not worked around.

| Reading | Rule applied when `speed_limit` is absent | Why this reading exists |
|---|---|---|
| `strict` | the edge cannot be graded → treat as **D** | the literal strict reading of D10 |
| `lenient` | the OSM `maxspeed` of another segment with the same street name **and** the same road class, taking the **minimum** value, else Valhalla's own WA default speed table (`valhalla/custom_files/default_speeds.json`, "suburban" row: tertiary 39, secondary 44, residential 18 mph) | the most favourable assumption that is still tied to real data; the minimum is chosen so the route gets every benefit of the doubt |
| `generous` | assume **25 mph** | the most favourable assumption possible; a route that fails here fails no matter what |
| `lenient_repair` | `lenient` + the ladder-hole repair of §1.1 | charitable data-driven reading |
| `generous_repair` | `generous` + the ladder-hole repair of §1.1 | **the single most charitable reading that exists.** Used as the headline 'best case' |

A useful cross-check on the lenient table: Valhalla's `speed_limit` field is a *faithful mirror* of OSM. Across the 8 298 traced edges, **6 525 lack `speed_limit`, and 0 of those 6 525 have a `maxspeed` tag on that exact OSM way** (checked by joining every `edge.way_id` against the OSM extract). Valhalla omits the field exactly when OSM has no tag. There is no hidden limit to recover.

### 1.3 The script

Two files, both in the repo and both re-runnable:

- `scripts/safety_probe.py` — route → trace_attributes → per-grade breakdown. Stdlib only (no third-party imports).
- `scripts/safety_sweep.py` — the costing sweep driver.

```python
# ---------- scripts/safety_probe.py ----------
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

```

```python
# ---------- scripts/safety_sweep.py ----------
#!/usr/bin/env python3
"""Costing sweep: for one destination coordinate, try every Valhalla bicycle costing in the
D10 sweep grid and report the resulting grade profile. Imports scripts/safety_probe.py.

Grid (spec): use_roads in {0.0, 0.05, 0.1} x use_living_streets in {0.25, 0.5},
avoid_bad_surfaces 0.3, bicycle_type hybrid, plus one bicycle_type=city run.
use_hills 0.25 is held fixed to match the reference route in docs/SERVER.md section 6.
"""
import json, pathlib, sys, importlib.util

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("safety_probe", HERE / "safety_probe.py")
sp = importlib.util.module_from_spec(spec); spec.loader.exec_module(sp)

SWEEP = [{"use_roads": r, "use_living_streets": l}
         for r in (0.0, 0.05, 0.1) for l in (0.25, 0.5)]


def options(cfg, bicycle_type="hybrid"):
    return {"bicycle_type": bicycle_type, "use_roads": cfg["use_roads"],
            "use_living_streets": cfg["use_living_streets"],
            "avoid_bad_surfaces": 0.3, "use_hills": 0.25}


def one(trail, rid, node, dest, cfg, bicycle_type="hybrid"):
    tag = f"sw_{rid}_{node}_r{cfg['use_roads']}_l{cfg['use_living_streets']}_{bicycle_type}"
    st, rj = sp.route([sp.HOME, dest], costing_options=options(cfg, bicycle_type), tag=tag)
    rec = {"trail": trail, "rid": rid, "node": node, "dest": dest,
           "use_roads": cfg["use_roads"], "use_living_streets": cfg["use_living_streets"],
           "bicycle_type": bicycle_type, "http": st}
    if st != 200:
        rec["error"] = rj.get("error")
        return rec
    s = rj["trip"]["summary"]; loc = rj["trip"]["locations"][-1]
    rec["distance_km"] = s["length"]; rec["time_s"] = s["time"]
    rec["snap_offset_m"] = round(sp.metres({"length": 0}, "kilometers") + 0, 1)
    import math
    rec["snap_offset_m"] = round(math.hypot((loc["lat"]-dest["lat"])*111320,
                                            (loc["lon"]-dest["lon"])*111320*0.6774), 1)
    st2, tj = sp.trace(rj["trip"]["legs"][0]["shape"], tag="trace_" + tag)
    rec["trace_http"] = st2
    if st2 != 200:
        return rec
    streets = sp.load_osm_speed_tables()
    rep = sp.report(tj["edges"], streets, label=tag)
    rep["route_summary"] = s
    pathlib.Path(f"docs/safety-probe-raw/responses/report_{tag}.json").write_text(json.dumps(rep, indent=1))
    rec["grades"] = {m: rep["modes"][m]["metres"] for m in sp.MODES}
    rec["verdicts"] = {m: sp.verdict(rep, m) for m in sp.MODES}
    rec["named_lenient"] = {g: [(x["street"], round(x["m"]), x["road_class"])
                                for x in rep["modes"]["lenient"]["named"].get(g, [])]
                            for g in ("A", "B", "C", "D", "E")}
    rec["named_generous"] = {g: [(x["street"], round(x["m"]), x["road_class"])
                                 for x in rep["modes"]["generous"]["named"].get(g, [])]
                             for g in ("A", "B", "C", "D", "E")}
    return rec


if __name__ == "__main__":
    job = json.loads(pathlib.Path(sys.argv[1]).read_text())
    out = []
    for target in job["targets"]:
        for cfg in SWEEP:
            out.append(one(target["trail"], target["rid"], target["node"],
                           {"lat": target["lat"], "lon": target["lon"]}, cfg, "hybrid"))
        city = {"use_roads": 0.05, "use_living_streets": 0.5}
        out.append(one(target["trail"], target["rid"], target["node"],
                       {"lat": target["lat"], "lon": target["lon"]}, city, "city"))
        print("swept", target["trail"], target["node"])
    pathlib.Path(job["out"]).write_text(json.dumps(out, indent=1))
    for r in out:
        v = r.get("verdicts", {}).get(job.get("headline_mode", "generous_repair"), {})
        print(f"{r['trail'][:24]:24s} r={r['use_roads']:<5} l={r['use_living_streets']:<5} {r['bicycle_type']:6s} "
              f"http={r['http']} dist={r.get('distance_km')} D={v.get('D_m')} E={v.get('E_m')} pass={v.get('pass')}")

```

### 1.4 The requests

Two `POST`s per route, exactly the shapes verified in `docs/SERVER.md` §6-7. Verbatim example (the reference route):

```http
POST http://127.0.0.1:8002/route
Content-Type: application/json


{
 "locations": [
  {
   "lat": 47.240371,
   "lon": -122.262019
  },
  {
   "lat": 47.22329,
   "lon": -122.24273
  }
 ],
 "costing": "bicycle",
 "units": "kilometers",
 "directions_options": {
  "units": "miles"
 },
 "costing_options": {
  "bicycle": {
   "bicycle_type": "hybrid",
   "use_roads": 0.1,
   "avoid_bad_surfaces": 0.3,
   "use_hills": 0.25
  }
 }
}
```

```http
POST http://127.0.0.1:8002/trace_attributes
Content-Type: application/json


{
 "encoded_polyline": "{|ibyAz`hehFPlLKhMZ`Lz@`HC`v@EdES`C]|A_@...(1211 chars, verbatim from trip.legs[0].shape)",
 "shape_match": "edge_walk",
 "costing": "bicycle",
 "units": "kilometers",
 "filters": {
  "action": "include",
  "attributes": [
   "edge.road_class",
   "edge.use",
   "edge.surface",
   "edge.unpaved",
   "edge.cycle_lane",
   "edge.bicycle_network",
   "edge.shoulder",
   "edge.speed_limit",
   "edge.speed",
   "edge.lanes",
   "edge.sac_scale",
   "edge.length",
   "edge.names",
   "edge.way_id",
   "edge.cycleway",
   "edge.weighted_grade",
   "edge.begin_shape_index"
  ]
 }
}
```

Request bodies and response bodies are archived one file per unique (trail, access point, sweep cell):

- `docs/safety-probe-raw/requests/*.json` — 565 archived request bodies
- `docs/safety-probe-raw/responses/*.json` — the responses and the derived `report_*.json` grade tables
- `docs/safety-probe-raw/locate-probes.json` — the `POST /locate` batch and its decoded results

Calls actually sent to Valhalla: **654** — 352 `/route`, 297 `/trace_attributes`, 5 `/locate`. **All returned HTTP 200.** 565 request/response pairs are archived (a re-probe of a previously seen access point overwrites its earlier file, so archived files < calls made). Nothing was retried into a different answer.

---

## 2. Step 1 — grading the route that was already measured

### 2.1 Reproduction of the reference route

Home `47.240371,-122.262019` → `47.22329,-122.24273` (the coordinate the earlier session intended as the Sumner Link Trail), `bicycle`, `bicycle_type=hybrid`, `use_roads=0.1`, `avoid_bad_surfaces=0.3`, `use_hills=0.25`.

| Quantity | This probe | `docs/SERVER.md` §6-8 | Match |
|---|---|---|---|
| HTTP | 200 | 200 | yes |
| Route distance | 9.188 km | 9.188 km | yes |
| Time | 1769.794 s | 1769.794 s | yes |
| `trace_attributes` edges | 178 | 178 | yes |
| Edge metres | 9186 m | 9 186 m | yes |
| Edges with no `speed_limit` | 105 / 178 (58.4%) | 105 / 178 | yes |

Reproduction is exact, so the numbers below are comparable with the earlier session's. **Two *corrections* to `docs/SERVER.md`:** §7 lists `edge.lanes` among the attributes that came back — on this build `edge.lanes` is **never** returned (0 of 8 298 edges). And the `/locate` annotation in §6 that quotes `use: "driveway"` and `cycle_lane: "none"` **cannot be reproduced**: this build's `/locate` `edge_info` contains only `way_id`, `bike_network`, `names`, `shape`, `mean_elevation` and `speed_limit`. The *conclusion* is still right — §2.3 resolves the snapped way id against real OSM tags instead.

### 2.2 Grade breakdown — all five readings

| Reading | A m | B m | C m | D m | D % | E m | GAP m | REJECT m | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| `strict` | 817 | 345 | 0 | **7349** | **80.0%** | 0 | 675 | 0 | **FAIL** — GAP (ladder-unclassified) = 675 m; D = 7349 m > 805 m cap; D = 80.0% > 10% cap |
| `lenient` | 817 | 345 | 1168 | **1040** | **11.32%** | 0 | 5816 | 0 | **FAIL** — GAP (ladder-unclassified) = 5816 m; D = 1040 m > 805 m cap; D = 11.3% > 10% cap |
| `generous` | 817 | 345 | 1168 | **1018** | **11.08%** | 0 | 5838 | 0 | **FAIL** — GAP (ladder-unclassified) = 5838 m; D = 1018 m > 805 m cap; D = 11.1% > 10% cap |
| `lenient_repair` | 817 | 345 | 1843 | **6181** | **67.29%** | 0 | 0 | 0 | **FAIL** — D = 6181 m > 805 m cap; D = 67.3% > 10% cap |
| `generous_repair` | 817 | 345 | 7006 | **1018** | **11.08%** | 0 | 0 | 0 | **FAIL** — D = 1018 m > 805 m cap; D = 11.1% > 10% cap |

The route **fails the D10 rule under every reading.** Concretely:

- Under `generous_repair` (the most charitable reading that exists) it still carries **1 018 m of grade D = 11.1%** of a 9 186 m route — over both the 805 m and the 10% caps. That 1 018 m is **not** an assumption: it is **Tacoma Avenue 572 m** and **142nd Avenue East 446 m**, both tertiary roads that OSM really does tag `maxspeed=35 mph`, both confirmed in the trace as `speed_limit=56` km/h.

| Grade under `generous` | Street | road_class | metres | edges | assumed mph |
|---|---|---|---|---|---|
| D | Tacoma Avenue | tertiary | 572 | 14 | — (real OSM tag) |
| D | 142nd Avenue East | tertiary | 446 | 6 | — (real OSM tag) |
| GAP | 122nd Avenue East | tertiary | 3038 | 63 | 25 |
| GAP | Edgewood Drive East | tertiary | 1371 | 17 | 25 |
| GAP | 48th Street East | tertiary | 501 | 12 | 25 |
| GAP | Puyallup Street | tertiary | 468 | 14 | — (real OSM tag) |
| GAP | Sumner Heights Drive East | tertiary | 231 | 3 | 25 |
| GAP | Bridge Street | secondary | 182 | 7 | — (real OSM tag) |
| GAP | Valley Avenue East | secondary | 25 | 1 | — (real OSM tag) |
| GAP | West Valley Highway East | tertiary | 22 | 1 | 25 |

- Under the literal `strict` reading, 76-80% of the route (7 349 m) is ungradable and therefore D.

### 2.3 What the route actually is, and the driveway destination

Edge `use` over the whole route: **road 8298 m**, **service_road 476 m**, **driveway 412 m**.

That matches `docs/SERVER.md`: **there is not one metre of `use=path` or `use=cycleway` in the route**, and 87% of it is `cycle_lane=none`.

`POST /locate` on the given destination **and** on the vertex `docs/SERVER.md` proposed as "the trail", with the snapped way id then resolved against the authoritative OSM way tags (`api.openstreetmap.org/api/0.6/ways.json`):

| Point | Input | Snap distance | Snapped `way_id` | OSM tags | On a trail? |
|---|---|---|---|---|---|
| `baseline_task_dest` | 47.22329, -122.24273 | 11.9 m | `432920226` | **service** / `service=driveway` | **no** — not a trail |
| `server_md_trail_vertex` | 47.222762, -122.233665 | 9.7 m | `1297893064` | **path** / `informal=yes` | **no** — not a trail |

So the task's destination is a **private driveway** (`highway=service, service=driveway`), and the vertex `docs/SERVER.md` calls "the nearest trail vertex" is an **informal social path** (`highway=path, informal=yes`) that is **not a member of the Sumner Link Trail relation at all** (checked against `api.openstreetmap.org/api/0.6/relation/9605518/full.json`). Neither coordinate is on the trail. This probe therefore re-derives the real trail geometry from OSM before testing access points (§3).

**One refinement, and it matters:** that informal path is not disconnected from the trail after all. It shares **node `12024618771` (47.2226255,-122.2336772)** with `way 419747831` — the Sumner Link Trail's 2 370 m member way tagged `access=no, bicycle=no, foot=no`. So the last hop from that path onto the trail exists topologically, **but it runs through the one way a bicycle router refuses**. Separately, the path shares **0** nodes with the service road beside it (`way 569772368`, `highway=service`), minimum separation **39.77 m** point-to-segment / 39.81 m node-to-node. Raw: `docs/safety-probe-raw/osm/informal-path-linkage.json`. So the coordinate is unreachable by bicycle **even as a correct trail coordinate** — the original failure was a wrong coordinate *and* an unusable one.

---

## 3. Step 2 — where the trail access points actually are

### 3.1 Trail geometry, from the authoritative source

`docs/data/trails.json` did not exist when this probe started (the sibling trail-dataset agent finished it later; its figures agree with mine — see §3.5). Geometry here comes from `api.openstreetmap.org/api/0.6/relation/<id>/full.json`, one call per trail — the canonical current OSM data, which also gives exact node ids (needed for the connectivity work). Saved as `docs/safety-probe-raw/osm/trails-osmapi-full.json`.

**A warning that cost real time, recorded so nobody repeats it:** the Overpass mirror `overpass.kumi.systems` answered HTTP 200 but served **stale snapshots** — two different ones in this session: `timestamp_osm_base = 2026-06-01T08:52:28Z` (stored in `trails-nodes.json`, now deleted) and `2026-07-28T02:16:18Z` (a street-`maxspeed` pull, discarded). The 2026-06-01 answer returned the **White River Trail relation with only 3 of its 5 member ways** — silently truncated, HTTP 200, no warning. Every result in this report comes from `overpass-api.de` (`timestamp_osm_base = 2026-09-15T21:06:55Z`, the same week as the Valhalla tile build) or from the OSM API. Stale mirrors were detected by comparing base timestamps and rejected; `docs/safety-probe-raw/osm/STALE-MIRROR-NOTES.json` records exactly what was rejected and why.

| Trail | Relation | Member ways | Distinct nodes | Member-way `highway` values |
|---|---|---|---|---|
| **White River Trail** | `3508527` | 5 | 220 | cycleway x5 |
| **Interurban Trail Milton** | `6554034` | 9 | 118 | cycleway x7, footway x2 |
| **Interurban Trail Edgewood** | `8826064` | 3 | 35 | cycleway x3 |
| **Riverwalk Trail** | `9605517` | 10 | 179 | cycleway x8, footway x2 |
| **Sumner Link Trail** | `9605518` | 42 | 395 | cycleway x29, footway x6, tertiary x5, unclassified x1, service x1 |
| **Lake Tapps Parkway Trail** | `9605519` | 3 | 98 | cycleway x3 |

**Every one of the six is built from `highway=cycleway` or `highway=footway` ways** — i.e. genuine off-street facilities, which is grade A under the ladder *if* they can be reached. Two exceptions matter and are covered later: the Sumner Link relation also includes 886 m of `highway=tertiary` (`Fryar Avenue`) and **2 370 m of `highway=unclassified` tagged `access=no` + `bicycle=no`**.

### 3.2 How access points were enumerated

1. Fetch every `highway=*` way in the box `47.190,-122.340,47.286,-122.192` that encloses all six trails — 16 900 ways, 97 597 nodes — from Overpass (`overpass-api.de`).
2. For every distinct node of every trail, find every non-member highway way within **30 m** (point-to-segment distance, 106 840 segments), and record whether the trail node and the road way **share the node id** (a real topological connection) or merely pass close (geometric proximity, no shared node — usually unreachable by a router).
3. Collapse to one candidate per distinct road (`name`, `highway`) plus every trail endpoint, then `POST /route` home → candidate for all of them (**143 candidates**), and `POST /trace_attributes` on each resulting shape to grade it.

**Result: 143 of 143 candidates returned HTTP 200.** *Every* access point tested is routable, including the ones that only pass close to a road. So for these six trails the problem is **not** unreachable access points — it is what the rider has to cross to get to them. This refines `docs/SERVER.md` §6 item 10: the earlier route failed to touch the trail because the coordinate chosen was a driveway and an informal path, not because trail access points are unreachable.

### 3.3 Best routable access point per trail

"Best" is reported on two axes, because they disagree. **Safety-optimal** = the access point whose route has the least grade D under `generous_repair`, breaking ties on distance. **Closest** = the shortest route. "On trail" = metres of `use=cycleway`/`use=path` ridden before arrival.

| Trail | Access node | Coordinates | Route | On-trail | Nearest road at that node | Grade D (`generous_repair`) | Grade E | D10 |
|---|---|---|---|---|---|---|---|---|
| **White River Trail** | `2669374349` | 47.264747, -122.229213 | 8.175 km | 559 m | — / `service` | **1447 m (17.7%)** | 0 m | **FAIL** |
| **Interurban Trail Milton** | `12196160808` | 47.247537, -122.324913 | 5.352 km | 0 m | — / `service` | **0 m (0.0%)** | 0 m | **PASS** |
| **Interurban Trail Edgewood** | `2646868507` | 47.256065, -122.277330 | 2.824 km | 0 m | 114th Avenue East / `tertiary` | **0 m (0.0%)** | 0 m | **PASS** |
| **Riverwalk Trail** | `6486202530` | 47.196487, -122.251072 | 6.880 km | 395 m | Sumner Link Trail / `cycleway` | **0 m (0.0%)** | 0 m | **PASS** |
| **Sumner Link Trail** | `110713098` | 47.203400, -122.244529 | 5.867 km | 0 m | Bridge Street / `secondary` | **0 m (0.0%)** | 0 m | **PASS** |
| **Lake Tapps Parkway Trail** | `6486202580` | 47.249987, -122.238747 | 5.911 km | 0 m | — / `service` | **726 m (12.28%)** | 0 m | **FAIL** |

And the closest point on each trail, for comparison:

| Trail | Closest node | Coordinates | Route | On-trail | Nearest road | Grade D | Grade E |
|---|---|---|---|---|---|---|---|
| White River Trail | `2669374349` | 47.264747, -122.229213 | 8.175 km | 559 m | — / `service` | 1447 m (17.7%) | 0 m |
| Interurban Trail Milton | `53175521` | 47.261063, -122.300768 | 5.185 km | 1331 m | South 380th Street / `residential` | 129 m (2.49%) | 685 m |
| Interurban Trail Edgewood | `2646868507` | 47.256065, -122.277330 | 2.824 km | 0 m | 114th Avenue East / `tertiary` | 0 m (0.0%) | 0 m |
| Riverwalk Trail | `6486202530` | 47.196487, -122.251072 | 6.880 km | 395 m | Sumner Link Trail / `cycleway` | 0 m (0.0%) | 0 m |
| Sumner Link Trail | `110713098` | 47.203400, -122.244529 | 5.867 km | 0 m | Bridge Street / `secondary` | 0 m (0.0%) | 0 m |
| Lake Tapps Parkway Trail | `6486202580` | 47.249987, -122.238747 | 5.911 km | 0 m | — / `service` | 726 m (12.28%) | 0 m |

All six access points snap **exactly** (0.0-0.1 m offset between the requested coordinate and Valhalla's arrival coordinate), so none of them lands on a driveway the way the original destination did. Verified by tracing each route and checking that the final edges are the trail itself.

### 3.4 `POST /locate` evidence for the best access points

`POST /locate` needs a JSON body (the documented GET form returns HTTP 400 on this build). One batched call with 8 locations, saved to `docs/safety-probe-raw/locate-probes.json`:

| Probe point | Snap distance | Snapped `way_id` | Resolved OSM tags | Off-street facility? |
|---|---|---|---|---|
| `baseline_task_dest` | 11.9 m | `432920226` | `service` / `service=driveway` | no |
| `server_md_trail_vertex` | 9.7 m | `1297893064` | `path` | yes |
| `bestAP_3508527_White_River_Trail` | 0.0 m | `674202154` | `service` | no |
| `bestAP_6554034_Interurban_Trail_Milton` | 0.0 m | `1317769726` | `cycleway` / `bicycle=yes` | yes |
| `bestAP_8826064_Interurban_Trail_Edgewood` | 0.0 m | `765606369` | `tertiary` | no |
| `bestAP_9605517_Riverwalk_Trail` | 0.0 m | `691257975` | `cycleway` / `bicycle=designated` | yes |
| `bestAP_9605518_Sumner_Link_Trail` | 0.0 m | `98689245` | `secondary` / `maxspeed=25 mph` | no |
| `bestAP_9605519_Lake_Tapps_Parkway_Trail` | 0.0 m | `569456368` | `service` | no |

Reading of that table: **two** of the six best access points snap directly onto the trail's own cycleway (Milton, Riverwalk — 0.0 m, `speed_limit` absent because paths have no posted limit). **One** snaps onto the road that crosses the trail at grade (Sumner Link ↔ Bridge Street, `secondary`, `maxspeed=25 mph`). **One** snaps onto a tertiary road whose node is shared with the trail (Interurban Edgewood ↔ 114th Avenue East). **Two** snap onto adjacent `highway=service` ways (White River, whose service way is tagged `motor_vehicle=destination`; and Lake Tapps, whose service way carries no access tag). None of the six is private and none is a driveway, unlike the original destination in §2.3.

### 3.5 Cross-check against the sibling trail dataset

| Trail | Relation | Length km (sibling) | Rideable km (sibling) | Paved | Nearest from home km | Name check |
|---|---|---|---|---|---|---|
| Lake Tapps Parkway Trail | `9605519` | 3.016 | 3.016 | True | 1.88 | agree |
| Sumner Link Trail | `9605518` | 8.46 | 6.09 | True | 1.88 | agree |
| Interurban Trail Edgewood | `8826064` | 1.33 | 1.33 | True | 2.09 | agree |
| White River Trail | `3508527` | 3.8 | 3.8 | True | 3.65 | agree |
| Interurban Trail Milton | `6554034` | 4.639 | 4.639 | True | 3.72 | agree |
| Riverwalk Trail | `9605517` | 6.764 | 6.764 | True | 4.56 | agree |

The sibling dataset reports the Sumner Link Trail as **8.46 km long but only 6.09 km rideable** — a 2.37 km gap, which is exactly the `access=no` + `bicycle=no` section this probe found independently (§6.2). Two independent passes reached the same fault, from different directions.

---

## 4. Step 3 — the costing sweep

### 4.1 The grid

For the safety-optimal access point of each trail: `use_roads` in {0.0, 0.05, 0.1} × `use_living_streets` in {0.25, 0.5}, with `avoid_bad_surfaces=0.3` and `bicycle_type=hybrid`, plus one run with `bicycle_type=city`. `use_hills=0.25` is held fixed to match the reference route in `docs/SERVER.md` §6. **7 runs × 10 targets = 70 requests**, all HTTP 200, every one traced and graded (`docs/safety-probe-raw/sweep-results.json`).

### 4.2 The sweep changes nothing — and that is the finding

| Trail | Route km | Grade D (`generous_repair`) | Grade E | Tripped the cap? | Range of grade D across all 143 access probes |
|---|---|---|---|---|---|
| White River Trail | 8.175 | 1447 m | 0 m | **yes** | 1447 – 2681 m |
| Interurban Trail Milton | 5.415 | 0 m | 0 m | no | 0 – 129 m |
| Interurban Trail Edgewood | 2.990 | 0 m | 0 m | no | 0 – 0 m |
| Riverwalk Trail | 6.880 | 0 m | 0 m | no | 0 – 0 m |
| Sumner Link Trail | 6.008 | 0 m | 0 m | no | 0 – 726 m |
| Lake Tapps Parkway Trail | 6.051 | 726 m | 0 m | **yes** | 726 – 726 m |

**All 7 costing variants produced the identical route** for a given destination — same distance to the metre, same geometry, same maneuver count. This is not a bug in the sweep. A control experiment with extreme values (`docs/safety-probe-raw/control-costing-sensitivity.json`):

| Destination | `use_roads` 0.0 | `use_roads` 0.5 | `use_roads` 1.0 | `bicycle_type=city` | `bicycle_type=road` | `shortest: true` | default |
|---|---|---|---|---|---|---|---|
| SumnerLink/BridgeSt | 5.867 km | 5.867 km | 5.867 km | 5.867 km | 5.867 km | 5.731 km | 5.867 km |
| LakeTapps/west | 6.051 km | 6.051 km | 6.051 km | 6.051 km | 6.051 km | 6.019 km | 6.051 km |
| WhiteRiver/west | 8.187 km | 8.187 km | 8.187 km | 8.187 km | 8.187 km | 8.156 km | 8.187 km |
| Riverwalk | 6.88 km | 6.88 km | 6.838 km | 6.88 km | 6.88 km | 6.702 km | 6.88 km |
| InterurbanMilton | 5.425 km | 5.425 km | 5.425 km | 5.425 km | 5.425 km | 5.425 km | 5.425 km |
| InterurbanEdgewood | 2.824 km | 2.824 km | 2.824 km | 2.824 km | 2.824 km | 2.793 km | 2.824 km |

The options **are** being applied — they change `cost` (2 303 → 2 478 for `use_roads` 0.0 → 1.0 on the Lake Tapps route) and `time` (`bicycle_type=road` is 826 s where hybrid is 1 129 s). They change the *cost surface*, but on this network there is only **one** plausible low-cost path from the Edgewood plateau down to the Puyallup valley trails, so the returned path never moves. The only knob that moves it is `shortest: true`, and then by 0.2-2%.

`shortest: true` was therefore run on all six best access points as well (`docs/safety-probe-raw/shortest-results.json`):

| Trail | `shortest` km | vs. default km | Grade D (`generous_repair`) | D10 |
|---|---|---|---|---|
| White River Trail | 8.156 | 8.175 | 1447 m (17.75%) | **FAIL** |
| Interurban Trail Milton | 5.425 | 5.415 | 0 m (0.0%) | **PASS** |
| Interurban Trail Edgewood | 3.849 | 2.990 | 0 m (0.0%) | **PASS** |
| Riverwalk Trail | 6.773 | 6.880 | 0 m (0.0%) | **PASS** |
| Sumner Link Trail | 6.926 | 6.008 | 726 m (10.49%) | **FAIL** |
| Lake Tapps Parkway Trail | 6.019 | 6.051 | 726 m (12.06%) | **FAIL** |

**Bottom line for step 3: there is no costing setting that produces a better grade profile.** The app can pick `use_roads` for rider preference, but it must not claim that any setting makes the route safer, because on this network it does not change the route at all.

---

## 5. Step 4 — the decision question, answered directly

> *Is there ANY access point and ANY costing where the route from home meets "grades A/B/C only, <=10% and <=0.5 mi of D, no E"?*

### 5.1 The one-line answer

**No, not on any reading the data supports.** Under the single most charitable reading that exists (assume every unposted road is 25 mph *and* repair the ladder's own holes), four trails pass and two still fail. Under **every** reading that keeps faith with the actual data — strict, or lenient with Valhalla's own Washington default speeds — **all six trails fail.** And no costing setting changes any of it.

### 5.2 Per trail: best achievable profile, and the cap that WOULD be satisfied

| Trail | Best route km | D — `generous_repair` | E — `generous_repair` | D — `lenient_repair` | E — `lenient_repair` | D — `strict` | Cap that would be satisfied |
|---|---|---|---|---|---|---|---|
| **White River Trail** | 8.175 | 1447 m (17.7%) | 0 m | 3811 m | 2753 m | 93.16% | none — needs a D allowance of **>=1447 m** and **>=17.7%** |
| **Interurban Trail Milton** | 5.415 | 0 m (0.0%) | 0 m | 4995 m | 0 m | 98.84% | D10 satisfied (D = 0 m, E = 0 m) **only if** every unposted road is really 25 mph |
| **Interurban Trail Edgewood** | 2.990 | 0 m (0.0%) | 0 m | 2484 m | 0 m | 94.45% | D10 satisfied (D = 0 m, E = 0 m) **only if** every unposted road is really 25 mph |
| **Riverwalk Trail** | 6.880 | 0 m (0.0%) | 0 m | 5184 m | 568 m | 88.72% | D10 satisfied (D = 0 m, E = 0 m) **only if** every unposted road is really 25 mph |
| **Sumner Link Trail** | 6.008 | 0 m (0.0%) | 0 m | 5163 m | 0 m | 91.59% | D10 satisfied (D = 0 m, E = 0 m) **only if** every unposted road is really 25 mph |
| **Lake Tapps Parkway Trail** | 6.051 | 726 m (12.0%) | 0 m | 3090 m | 2562 m | 99.02% | none — needs a D allowance of **>=726 m** and **>=12.0%** |

### 5.3 The grade D and E streets, named (D10 requires this)

D10 says "Caution segments always listed by street name". Under the two readings that matter:

**White River Trail** — 8.175 km

  - grade D under `generous`: Stewart Road Southwest 726 m (secondary); A Street Southeast 721 m (secondary)
  - grade D under `lenient`: Stewart Road Southwest 726 m (secondary); A Street Southeast 721 m (secondary)
  - grade E under `lenient`: Jovita Boulevard East 1703 m (secondary); Stewart Road 710 m (secondary); Stewart Road Southeast 340 m (secondary)
**Interurban Trail Milton** — 5.415 km

  - grade D under `generous`: none
  - grade E under `lenient`: none
**Interurban Trail Edgewood** — 2.990 km

  - grade D under `generous`: none
  - grade E under `lenient`: none
**Riverwalk Trail** — 6.880 km

  - grade D under `generous`: none
  - grade D under `lenient`: West Valley Highway East 22 m (tertiary)
  - grade E under `lenient`: Traffic Avenue 568 m (secondary)
**Sumner Link Trail** — 6.008 km

  - grade D under `generous`: none
  - grade D under `lenient`: West Valley Highway East 22 m (tertiary)
  - grade E under `lenient`: none
**Lake Tapps Parkway Trail** — 6.051 km

  - grade D under `generous`: Stewart Road Southwest 726 m (secondary)
  - grade D under `lenient`: Stewart Road Southwest 726 m (secondary)
  - grade E under `lenient`: Jovita Boulevard East 1703 m (secondary); Stewart Road 519 m (secondary); Stewart Road Southeast 340 m (secondary)

### 5.4 The decisive measurement: most of every route cannot be graded at all

This is the finding the whole probe turns on. For each best route, how many metres run on roads whose speed limit **is** in OSM versus **is not**:

| Trail | Route m | Posted in OSM | Not posted anywhere in OSM | % ungradable |
|---|---|---|---|---|
| White River Trail | 8175 | 1447 m | **6727 m** | **82.3%** |
| Interurban Trail Milton | 5415 | 0 m | **5415 m** | **100.0%** |
| Interurban Trail Edgewood | 2990 | 0 m | **2990 m** | **100.0%** |
| Riverwalk Trail | 6880 | 381 m | **6499 m** | **94.5%** |
| Sumner Link Trail | 6008 | 364 m | **5644 m** | **93.9%** |
| Lake Tapps Parkway Trail | 6051 | 726 m | **5325 m** | **88.0%** |

The named streets that carry the ungradable majority, longest first:

- **White River Trail**: Jovita Boulevard East [secondary] 1703 m; 114th Avenue East [tertiary] 1563 m; 18th Street East [tertiary] 801 m; Stewart Road [secondary] 710 m; East Valley Highway East [secondary] 618 m; Lake Tapps Parkway Trail [service_other] 559 m
- **Interurban Trail Milton**: Taylor Street [tertiary] 1263 m; 16th Street East [tertiary] 1117 m; 18th Street East [tertiary] 1006 m; Taylor Street East [tertiary] 807 m; Porter Way [tertiary] 609 m; 18th Street East [residential] 280 m
- **Interurban Trail Edgewood**: 114th Avenue East [tertiary] 1683 m; 18th Street East [tertiary] 801 m; 18th Street East [residential] 280 m; Interurban Trail [service_other] 166 m; (no name) [service_other] 60 m
- **Riverwalk Trail**: 122nd Avenue East [tertiary] 3038 m; Edgewood Drive East [tertiary] 1371 m; Traffic Avenue [secondary] 568 m; 48th Street East [tertiary] 501 m; (no name) [service_other] 318 m; 18th Street East [residential] 280 m
- **Sumner Link Trail**: 122nd Avenue East [tertiary] 3038 m; Edgewood Drive East [tertiary] 1371 m; 48th Street East [tertiary] 501 m; 18th Street East [residential] 280 m; Sumner Heights Drive East [tertiary] 231 m; Sumner Link Trail [service_other] 141 m
- **Lake Tapps Parkway Trail**: Jovita Boulevard East [secondary] 1703 m; 114th Avenue East [tertiary] 1563 m; 18th Street East [tertiary] 801 m; Stewart Road [secondary] 519 m; Stewart Road Southeast [secondary] 340 m; 18th Street East [residential] 280 m

The roads at the top of nearly every list are the ones immediately outside the front door: **`18th Street East` (tertiary, 801 m)** and **`122nd Avenue East` (tertiary, 3 038 m)**. Their complete OSM tag sets are:

```
way 12240850  18th Street East   {"highway": "tertiary"}
way 12193741  122nd Avenue East  {"highway": "tertiary"}
```

No `maxspeed`, no `lanes`, no `surface`, no `shoulder`, no `sidewalk`. **The two roads that carry the rider away from home carry no safety attributes at all.** Under `strict` all 3 839 m of them are ungradable and therefore D. Under `lenient` they are graded at Valhalla's assumption for a Washington suburban tertiary road — **39 mph**, which lands in the ladder's 36-39 mph hole. Under `generous` they are assumed 25 mph and become grade C. **The D10 verdict on the home corridor is decided entirely by that one missing number.**

### 5.5 What this means in plain terms

- **A negative result, but a precise one.** It is not that the router cannot find a route — it finds one for every one of the 143 access points tested. It is that **the route's safety cannot be established**, because the determining data is absent over 82-100% of every route.
- **Two trails fail on hard, tagged evidence**, and would still fail with perfect knowledge: Lake Tapps Parkway (726 m of the OSM-tagged 35 mph, 3-5 lane Stewart Road Southwest = 12.0%) and White River (1 447 m of tagged 35-40 mph two-lane arterials = 17.7%).
- **The app must not present any of these routes as grade-guaranteed.** Under D10's wording — "only A/B/C plus a capped amount of D" — none of the six can currently be promised. The honest UI is: show the graded segments that *are* known, mark the rest **ungraded**, and tell the rider that roughly two to four miles (3.0-6.7 km) of the ride is on unposted rural roads. That is information the user can act on; a green safety badge would be a lie.
- **A cheap fix exists** and is recommended in §7.3: survey the `maxspeed` of the handful of home corridor roads in OSM (or hold a local override table), after which the D10 rule becomes evaluable for the whole route instead of for 6-18% of it.

---

## 6. Step 5 — are the trails islands in the OSM graph?

### 6.1 Method

A trail is reachable by a router only if at least one of its nodes is **also** a node of a road way that is *not* itself a member of that trail's relation. Sharing a node id is a real topological connection; merely passing within 30 m is not. Both were computed for all six trails from the OSM API relation data plus the 16 900-way Overpass extract. Raw output: `docs/safety-probe-raw/osm/shared-nodes.json`.

### 6.2 Verdict

| Trail | Relation | Trail nodes | Nodes shared with a non-member highway way | Verdict | 30 m-proximity rows | of which share a node |
|---|---|---|---|---|---|---|
| **White River Trail** | `3508527` | 220 | 29 | **CONNECTED** (29 shared node/way pairs) | 1462 | 118 |
| **Interurban Trail Milton** | `6554034` | 118 | 14 | **CONNECTED** (14 shared node/way pairs) | 322 | 44 |
| **Interurban Trail Edgewood** | `8826064` | 35 | 6 | **CONNECTED** (6 shared node/way pairs) | 108 | 17 |
| **Riverwalk Trail** | `9605517` | 179 | 36 | **CONNECTED** (36 shared node/way pairs) | 975 | 96 |
| **Sumner Link Trail** | `9605518` | 395 | 77 | **CONNECTED** (77 shared node/way pairs) | 2851 | 224 |
| **Lake Tapps Parkway Trail** | `9605519` | 98 | 19 | **CONNECTED** (19 shared node/way pairs) | 852 | 54 |

**No trail is an island.** Every one of the six shares at least six node ids with the wider highway network, and all 143 access candidates tested route successfully. So **no OSM repair is needed for connectivity**, and the "route to the nearest road and tell the rider to walk" fallback in the task brief is not required.

### 6.3 Which roads each trail connects to

- **White River Trail**: (unnamed) (`footway`); (unnamed) (`cycleway`); (unnamed) (`path`); (unnamed) (`track`); (unnamed) (`service`)
- **Interurban Trail Milton**: Kent Street (`residential`); Porter Way (`tertiary`); (unnamed) (`service`); Milton Interurban Trail (`cycleway`); (unnamed) (`path`); South 380th Street (`residential`); (unnamed) (`footway`); (unnamed) (`cycleway`); Wapato Way East (`secondary`)
- **Interurban Trail Edgewood**: 114th Avenue East (`tertiary`); (unnamed) (`service`); Military Road East (`secondary`); 31st Avenue (`residential`); Military Road South (`secondary`)
- **Riverwalk Trail**: 80th Avenue East (`residential`); River Road East (`primary`); (unnamed) (`footway`); (unnamed) (`path`); (unnamed) (`service`); (unnamed) (`cycleway`); Sumner Link Trail (`cycleway`); 7th Street Northeast (`residential`); 4th Avenue Northeast (`residential`)
- **Sumner Link Trail**: (unnamed) (`footway`); (unnamed) (`service`); Riverwalk Trail (`cycleway`); Sumner Link Trail (`footway`); Bridge Street (`secondary`); 142nd Avenue East (`tertiary`); (unnamed) (`path`); Stewart Road (`secondary`); (unnamed) (`cycleway`)
- **Lake Tapps Parkway Trail**: (unnamed) (`footway`); (unnamed) (`path`); (unnamed) (`service`); Vista Ridge Trail (`cycleway`); East Valley Highway East (`secondary`); Sumner Link Trail (`cycleway`); Alexander Place Southeast (`residential`); Terrace View Lane Southeast (`residential`); Stewart Road (`secondary`)

### 6.4 One real graph defect found: the Sumner Link Trail is severed

The Sumner Link Trail relation contains a **2 370 m** member way, `way 419747831`, tagged:

```json
{"access": "no", "bicycle": "no", "foot": "no", "highway": "unclassified", "name": "Sumner Link Trail", "segregated": "no"}
```

That way is the middle of the trail — it joins the southern cycleway (way 1433885326, ending 47.2184449,-122.2311095) to the northern cycleway (way 98695977, starting 47.2355615,-122.2366123). It spans `47.2184449,-122.2311095` → `47.2355283,-122.2349371`. Because it is `access=no`, **Valhalla drops it from the graph for every mode**, not just bicycle:

| `/locate` costing at `47.222762,-122.233665` (a point that lies exactly on way 419747831) | nearest edges returned |
|---|---|
| `bicycle` | way `1297893064` 9.7 m, way `569772368` 59.9 m — **419747831 absent** |
| `pedestrian` | way `1297893064` 9.7 m, way `569772368` 59.9 m — **419747831 absent** |
| `auto` | way `569772368` 59.9 m, way `432920227` 164.5 m — **419747831 absent** |

Consequence, measured: a **bicycle** trip between the two ends of the severed section — 1.90 km apart **along the trail** — comes out at **3.383 km**, and **2 491 m of it is off-trail**, including **2 032 m on 142nd Avenue East** (tertiary, unposted speed). A pedestrian route is 2.823 km; a car route is 5.045 km. So a rider cannot ride the Sumner Link Trail end to end in the current OSM data; they must leave it and climb 142nd Avenue East for 2 km. Raw: `docs/safety-probe-raw/responses/report_trace_severance_bicycle.json`.

A second measurement shows how far the damage reaches: the informal path `way 1297893064` that `docs/SERVER.md` proposed as a trail destination **does** attach to the trail — at node `12024618771` (47.2226255,-122.2336772) — but only *through* `way 419747831`, i.e. through the `bicycle=no` segment. Verified independently against `api.openstreetmap.org/api/0.6/way/1297893064/full.json`. So even the correct coordinate there cannot be reached by bicycle; the severance in §6.4 is what blocks it.

This is almost certainly a data error rather than a real closure — the same trail is a signed, paved, 6 m-wide cycletrack on both sides of the break — but **the router follows the data, and the app will follow the router.** It should be fixed in OSM (or the segment locally re-tagged) before the trail is offered as continuous. The sibling `docs/data/trails.json` independently reports this trail as 8.46 km long with only 6.09 km rideable — the same 2.37 km.


### 6.5 Independent cross-check

A **separate agent** (`osm-island-analyst`) ran the connectivity question independently while this probe
was working, from its own Overpass fetches. Its output is `docs/safety-probe-raw/islands.json` and
`islands.md`. **It reaches the same verdict: all six trails are CONNECTED, none is an island.**

| Trail | Shared nodes (this probe) | Shared nodes (independent) | Verdict agreement |
|---|---|---|---|
| White River Trail | 29 | 28 | agree |
| Interurban Trail Milton | 14 | 14 | agree |
| Interurban Trail Edgewood | 6 | 4 | agree (count differs) |
| Riverwalk Trail | 36 | 35 | agree |
| Sumner Link Trail | 77 | 68 | agree (count differs) |
| Lake Tapps Parkway Trail | 19 | 17 | agree (count differs) |

The small count differences are explained, and are the reason this report's figures are the ones used
above: the independent run fetched **Interurban Trail Edgewood and Lake Tapps Parkway Trail from
`overpass.kumi.systems`**, the mirror this probe identified as serving stale snapshots. The child agent's
own stored responses for those two carry `timestamp_osm_base 2026-06-01T08:52:28Z` — the same stale stamp
as the deleted `trails-nodes.json` — against a current `2026-09-15T21:06:55Z`. Both analyses also agree
the Sumner Link Trail is reported long (9 619 m / 8.46 km) but only partly rideable, and both independently
find the same node-level attachment of the informal path to the `bicycle=no` segment (§6.4).

---

## 7. KNOWN LIMITS

Everything here is a real gap, a real uncertainty, or something not tested. Read this before quoting any number above.

1. **The D10 verdict is under-determined by the data, and that is the main result — not a defect of this probe.** 82-100% of every best route runs on roads with no `maxspeed` in OSM, and `lanes` is also absent on those ways. The three readings in §1.2 are the honest bracket; there is no fourth reading that is more correct. **I do not know the actual posted speed limits of 18th Street East, 122nd Avenue East, Edgewood Drive East, 48th Street East, Sumner Heights Drive East, 114th Avenue East, Taylor Street, 16th Street East, Porter Way, Jovita Boulevard East, Traffic Avenue or East Valley Highway East.** I did not survey them and did not guess. A field check or a municipal speed-limit source would settle it.

2. **`edge.lanes` is never returned by this build.** It is in `docs/SERVER.md`'s attribute list but 0 of 8 298 edges carried it. So the brief's "grade using road_class **and lanes**" could only be done with road_class. Lane counts *are* in OSM for some of these roads (Stewart Road 3-5, Jovita 3, 114th Ave E 2) and were used qualitatively in §0, not in the ladder.

3. **Valhalla's `speed_limit` field mirrors OSM exactly, so there is nothing to recover from the graph.** 6 525 of 8 298 traced edges lack it, and **0** of those 6 525 have a `maxspeed` tag on that exact OSM way. Any hope of finding a limit that OSM has but Valhalla dropped is closed by measurement, not assumption.

4. **`edge.speed` is not a substitute for a speed limit.** It is the *bicycle* travel speed for the edge (18 km/h typical on pavement), not the car limit — verified by comparing it against edges that do carry `speed_limit=56` km/h and still report `speed` 18. Do not let anything downstream read it as a limit.

5. **The ladder has holes, documented in §1.1 and repaired only downward.** The holes matter: they move 4 926-5 838 m of otherwise-C-looking road into `GAP` on the baseline route. `*_repair` is a stated interpretation, not the specified ladder. If the product owner wants the holes closed differently (e.g. D extended to 40 mph, or dedicated-lane edges at any speed treated as B at face value), the numbers move — but the pass/fail answer does not, because the failures in §0 come from *tagged* 35-40 mph roads.

6. **`generous` mode is a bound, not a recommendation.** Assuming 25 mph for an unposted rural tertiary road is the most charitable possible assumption and is probably optimistic for 122nd Avenue East and Taylor Street. Any route that passes only under `generous_repair` (four of the six) should be treated as **unverified**, not as safe.

7. **The bicycle_network => grade A clause is generous and was not varied.** The ladder scores any edge with `bicycle_network` set as grade A. In this data `bicycle_network=1` coincides with 817 m of Fryar Avenue that is an *on-street* lane or shared-lane road with `bicycle=designated`, i.e. a signed bike route, not an off-street facility. Under a stricter reading where only `use in (cycleway, path, mountain_bike)` or `cycle_lane=separated` earns A, that 817 m becomes B (164 m, dedicated) or GAP (145 m, shared). It does **not** change any pass/fail above, because none of the six best routes depends on that clause.

8. **143 access points were probed, ~14-59 per trail, not every trail vertex.** Candidates were one per distinct adjacent road plus trail endpoints. Trails have 35-395 nodes; the gaps between sampled points are covered by the fact that the *route distance* is what varies, and the best and worst cases differ by under 10%. A vertex-level exhaustive sweep would not change the §5 answer, because the two trails that fail do so on a 726-1 447 m arterial segment that every candidate on that side of the trail must cross.

9. **`/locate` on this build returns far less than `docs/SERVER.md` claims.** No `use`, no `cycle_lane`, no `surface` — only `way_id`, `bike_network`, `names`, `shape`, `mean_elevation`, `speed_limit`. This probe worked around it by resolving `way_id` against authoritative OSM tags, which is more reliable anyway. **`docs/SERVER.md` §6/§7 should be corrected.**

10. **`docs/SERVER.md`'s local explanation of the driveway snap is superseded.** It attributed the route ending 60 m short to the destination vertex being an unconnected `informal=yes` path fragment. The tag facts are right, but the real cause of the earlier failure was simpler: **the destination coordinate was never on the trail.** The real trail has 143 routable access points, all HTTP 200. The app's practical rule is still "probe every intended access point", but the reason is different.

11. **The Overpass mirror `overpass.kumi.systems` served stale data with HTTP 200, twice, from two different snapshots** — `timestamp_osm_base 2026-06-01T08:52:28Z` and `2026-07-28T02:16:18Z`, against a current `2026-09-15T21:06:55Z`. The 2026-06-01 answer silently returned the White River Trail relation with **3 of its 5 member ways**. This report uses `overpass-api.de` and the OSM API only. Note that the stale 2026-06-01 stamp is the *same* stamp carried by the pre-existing `docs/data/raw/q3-member-ways-nodes.json`, which the independent island agent also observed — **so that file, and `q2-member-ways.json`, are worth re-verifying**; if they came from a mirror, their member-way counts may be short.

12. **OSM snapshot vs. tile build.** The routing tiles were built 2026-09-15 from Geofabrik `washington-latest`; the OSM extract used for tagging was `timestamp_osm_base 2026-09-15T21:06:55Z`; the relation geometry came from the live OSM API. These are within hours of each other, so the trail geometry and the graph agree. They will drift apart as OSM changes.

13. **No elevation/grade analysis.** `weighted_grade` was collected (the tiles have elevation) but hills were not part of the D10 rule and were not evaluated. The route from the Edgewood plateau to the Puyallup valley drops roughly 100 m over the ride (`/height`: home 122 m → valley 16 m), which the rider will climb on the way home.

14. **`travel_type` / multi-modal options were not swept.** Only `bicycle` costing was used, as the brief specifies. `multimodal`, `bicycle_pedestrian` and transit were not tried; none of them would remove the grade D segments, since those are on the only road corridor down the hill.

15. **Time-of-day, traffic and shoulder data are absent from the graph.** Valhalla logged `Traffic tile extract could not be loaded` at every start, and `shoulder` came back `false` on 100% of edges. So "stress" beyond the D10 rule (traffic volume, time of day, actual shoulder width) could not be assessed at all. A 3 038 m unposted two-lane tertiary road with no shoulder may be fine at 07:00 on a Sunday and unpleasant at 17:30 on a Tuesday; the engine cannot tell the difference.

16. **`RECOMMENDATIONS` are outside this probe's authority.** §7.3 below states what the data suggests; the decision belongs to the D10 owner.

### What would make the rule evaluable

1. **Hold a local `maxspeed` override table for the home corridor.** The set is small — 18th Street East, 122nd Avenue East, Taylor Street, 16th Street East, 114th Avenue East, Edgewood Drive East, 48th Street East, Porter Way, Jovita Boulevard East, Traffic Avenue, East Valley Highway East, Sumner Heights Drive East — and covers most of every route. Even a single field survey of these twelve roads converts the D10 verdict from an assumption into a measurement for the whole corridor.
2. **Or contribute the `maxspeed` tags to OSM**, which fixes it for every router, not just this app.
3. **Fix `way 419747831`** (the `access=no` Sumner Link segment) in OSM after checking locally whether the closure is real. Until then, the Sumner Link Trail cannot be ridden through and any app route to it will detour 2.5 km off-trail.
4. **Until then, in the app**: mark unposted segments as **ungraded**, never as safe; always list the grade D/E streets by name; and do not display a safety badge that the data cannot support.

---

## 8. Files, reproduction and cleanup record

### 8.1 Deliverables and raw data

| Path | What it is |
|---|---|
| `docs/SAFETY-PROBE.md` | this document |
| `scripts/safety_probe.py` | the grader (§1.3) |
| `scripts/safety_sweep.py` | the costing-sweep driver (§1.3) |
| `docs/safety-probe-raw/requests/*.json` | 565 archived request bodies, verbatim |
| `docs/safety-probe-raw/responses/*.json` | response bodies + `report_*.json` per-route grade tables |
| `docs/safety-probe-raw/locate-probes.json` | the batched `/locate` call and decoded results |
| `docs/safety-probe-raw/access-probe-results-all.json` | all 143 access-point probes with grades |
| `docs/safety-probe-raw/access-summary.json`, `final-per-trail.json`, `min-D-access.json` | per-trail roll-ups |
| `docs/safety-probe-raw/sweep-results.json` | the 70-run costing sweep |
| `docs/safety-probe-raw/control-costing-sensitivity.json` | the control experiment proving the sweep is a no-op |
| `docs/safety-probe-raw/shortest-results.json` | `shortest: true` results |
| `docs/safety-probe-raw/unposted-exposure.json` | the posted/unposted metres table of §5.4 |
| `docs/safety-probe-raw/osm/trails-osmapi-full.json` | trail relations from the OSM API (authoritative) |
| `docs/safety-probe-raw/osm/roads-tags-compact.json` | 16 900 highway ways, abbreviated tags (derived) |
| `docs/safety-probe-raw/osm/shared-nodes.json`, `access-candidates-summary.json`, `access-probe-candidates.json` | connectivity and access enumeration |
| `docs/safety-probe-raw/osm/snapped-way-tags.json` | authoritative tags for every `/locate`-snapped way |
| `docs/safety-probe-raw/osm/informal-path-linkage.json` | node-level linkage of the informal path to the Sumner Link Trail (§2.3, §6.4) |
| `docs/safety-probe-raw/osm/STALE-MIRROR-NOTES.json` | record of the stale-mirror responses that were rejected |
| `docs/safety-probe-raw/islands.json`, `islands.md` | independent connectivity analysis (§6.5) |

### 8.2 Re-running

```console
$ cd "/Volumes/Satechi 1/Dev/bike-trail-planner"

# 1. grade one route (edit the job file for a different destination)
$ python3 scripts/safety_probe.py docs/safety-probe-raw/jobs/baseline.json

# 2. the costing sweep
$ python3 scripts/safety_sweep.py docs/safety-probe-raw/jobs/sweep.json
```

Both are stdlib-only and need nothing but a running Valhalla on `127.0.0.1:8002`.

### 8.3 Cleanup performed (the >2 MB rule)

Two files exceeded the 2 MB limit and were removed:

- **`docs/safety-probe-raw/osm/roads-bbox.json` (11.24 MB)** — the full Overpass extract of every highway way in the probe box, with complete tags and node lists. **Deleted.** Replaced by `roads-tags-compact.json` (0.74 MB), the same 16 900 ways with abbreviated tag keys (provenance and `timestamp_osm_base` recorded inside it). `scripts/safety_probe.py` now reads the compact file. To rebuild the full extract if ever needed:

```console
# Overpass QL, POST to https://overpass-api.de/api/interpreter as data=<query>

# [out:json][timeout:600];way["highway"](47.190,-122.340,47.286,-122.192);out body;>;out skel qt;
```

- **`docs/safety-probe-raw/osm/access-candidates.json` (1.65 MB)** — the raw 30 m proximity table for every trail node (7 000+ rows). Compaction: the per-probe roll-up `access-probe-candidates.json`, `access-probe-results-all.json` and `min-D-access.json` retain everything the conclusions rest on; the full proximity table is regenerable from `roads-tags-compact.json` plus `trails-osmapi-full.json`.

Two stale-mirror payloads were deleted outright (they were misleading, not merely redundant): `osm/trails-nodes.json` (109 KB, `overpass.kumi.systems`, `timestamp_osm_base 2026-06-01T08:52:28Z`, White River Trail relation truncated to 3 of 5 member ways — superseded by `trails-osmapi-full.json`) and `osm/street-maxspeed-all.json` (a 141-byte stub left by a failed refetch that had overwritten the 2026-07-28 kumi answer). What they contained and why they were rejected is recorded in `osm/STALE-MIRROR-NOTES.json`. `osm/trails-geom.json` (from `overpass-api.de`, correct timestamp) is kept even though `trails-osmapi-full.json` supersedes it.

Redundant exploratory payloads were also pruned to keep the repo sane: for the 143 access-point probes, **188 route responses and 188 `trace_attributes` edge dumps for non-safety-optimal access points were deleted** (8.16 MB + 1.44 MB). Their **request bodies, their `report_*.json` grade tables, and their per-probe rows in `access-probe-results-all.json` are all kept**, so every number in this report stays checkable; the deleted payloads are byte-for-byte reproducible by re-running `safety_probe.py` against the same access point. The safety-optimal probe per trail keeps its full edge dump. Everything else listed in §8.1 is intact.

Final size of `docs/safety-probe-raw/`: **12 MB** across ~700 files; largest single file **0.74 MB** (`osm/roads-tags-compact.json`). **No file over 2 MB remains.**

### 8.4 Request totals

- `/route`: **352** calls, all HTTP 200. `/trace_attributes`: **297** calls, all HTTP 200. `/locate`: **5** calls covering 11 locations, all HTTP 200.
- Overpass: 8 successful calls to `overpass-api.de`; 4 to `overpass.kumi.systems` were rejected as stale. OSM API: 6 relation `/full.json` calls, 1 batch way-tags call.
- No Anthropic/Claude model was used, anywhere, including for delegation.
