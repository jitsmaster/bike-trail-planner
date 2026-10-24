#!/usr/bin/env python3
"""Route home -> every access point of the three corridor trails and grade each route.

Writes docs/corridor-raw/ap-sweep.json (summary) and raw request/response JSON per probe.
"""
import json, pathlib, sys, time
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import corridor_probe as cp

ROOT = cp.ROOT
CORRIDOR = ["Lake Tapps Parkway Trail", "Sumner Link Trail", "Riverwalk Trail"]


def build():
    rows = cp.load_traffic(ROOT / "app/src/main/assets/traffic.json")
    limrows = cp.load_limits_rows(ROOT / "app/src/main/assets/limits.json")
    for r in limrows:
        if r["aadt"]:
            rows.append(cp.Count(r["name"], r["aadt"], r["year"], r["confidence"] or "measured",
                                 r["lts"], cp.band_for(r["aadt"]),
                                 [r["osm_way_id"]] if r["osm_way_id"] else [], [r["name"]],
                                 r["source"] or "assets/limits.json"))
    return cp.Grader(cp.TrafficTable(rows), cp.Limits(limrows))


def main():
    grader = build()
    aps = json.load(open(ROOT / "app/src/main/assets/access_points.json"))["access_points"]
    targets = [a for a in aps if a["trail"] in CORRIDOR]
    targets.sort(key=lambda a: (CORRIDOR.index(a["trail"]), a["straight_line_m_from_home"]))
    out = {}
    for i, a in enumerate(targets):
        key = f'{a["trail"]}|{a["osm_node_id"]}'
        tag = f'ap_{a["trail"].split()[0]}_{a["osm_node_id"]}'
        st, j = cp.route_app([cp.HOME, (a["lat"], a["lon"])], tag=tag)
        rec = {"trail": a["trail"], "osm_node_id": a["osm_node_id"], "lat": a["lat"], "lon": a["lon"],
               "highway": a["highway"], "on_road": a["on_road"],
               "straight_line_m": a["straight_line_m_from_home"],
               "probe_route_km": a.get("measured_route_km_from_home")}
        if st != 200:
            rec["error"] = f"HTTP {st}"
            out[key] = rec
            continue
        shape = j["trip"]["legs"][0]["shape"]
        rec["route_km"] = j["trip"]["summary"]["length"]
        rec["route_min"] = round(j["trip"]["summary"]["time"] / 60.0, 1)
        segs, t = cp.grade_route(shape, tag=tag + "_trace", grader=grader)
        if segs is None:
            rec["error"] = "trace failed"
            out[key] = rec
            continue
        b = cp.breakdown(segs)
        rec["breakdown"] = {k: round(v) for k, v in b["by_grade"].items()}
        rec["ungraded_m"] = round(b["ungraded_m"])
        rec["total_m"] = round(b["total_m"])
        rec["d10"] = cp.d10(b)
        rec["named"] = [{"name": n["name"], "grade": n["grade"], "m": round(n["m"])} for n in cp.named(segs)[:12]]
        rec["shape"] = shape
        out[key] = rec
        print(f'[{i+1}/{len(targets)}] {a["trail"][:12]:12s} {a["osm_node_id"]} {rec["route_km"]:.2f}km '
              f'D={rec["d10"]["d_m"]:.0f}m E={rec["d10"]["e_m"]:.0f}m pass={rec["d10"]["passes"]}', flush=True)
    (ROOT / "docs/corridor-raw/ap-sweep.json").write_text(json.dumps(out, indent=1))
    print("wrote ap-sweep.json", len(out))


if __name__ == "__main__":
    main()
