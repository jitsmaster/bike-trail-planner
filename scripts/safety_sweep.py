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
