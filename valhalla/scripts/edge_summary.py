#!/usr/bin/env python3
"""Per-edge safety summary from a Valhalla /trace_attributes response.

Usage: python3 edge_summary.py <trace_attributes.json> [<trace_attributes.json> ...]

Consumes the edge filters the safety grader needs:
  edge.road_class, edge.use, edge.surface, edge.unpaved, edge.cycle_lane,
  edge.bicycle_network, edge.shoulder, edge.speed_limit, edge.sac_scale,
  edge.length, edge.names, edge.way_id
NOTE: edge.length is in the request's units (kilometers here); metres = length * 1000.
NOTE: edge.speed_limit is in the request's units (km/h here).
NOTE: a missing "speed_limit" / "names" key means OSM has no value for that edge.
"""
import json
import sys
from collections import defaultdict

KMH_TO_MPH = 0.621371


def totals(edges, key, default="<missing>"):
    acc = defaultdict(float)
    for e in edges:
        acc[e.get(key, default)] += e["length"] * 1000.0
    return acc


def name_stretches(edges):
    """Group consecutive edges with cycle_lane == 'none' into stretches and break a
    stretch whenever the OSM name signature changes."""
    out, cur = [], None
    for e in edges:
        if e.get("cycle_lane") != "none":
            if cur:
                out.append(cur)
            cur = None
            continue
        names = tuple(e.get("names") or [])
        if cur is None or names != cur["names"]:
            if cur:
                out.append(cur)
            cur = {"names": names, "m": 0.0, "edges": 0, "speed_limits": set(),
                   "road_class": e.get("road_class"), "surface": set(), "use": set(),
                   "way_ids": set(), "bicycle_network": set()}
        cur["m"] += e["length"] * 1000.0
        cur["edges"] += 1
        if "speed_limit" in e:
            cur["speed_limits"].add(e["speed_limit"])
        cur["surface"].add(e.get("surface"))
        cur["use"].add(e.get("use"))
        cur["way_ids"].add(e.get("way_id"))
        cur["bicycle_network"].add(e.get("bicycle_network"))
    if cur:
        out.append(cur)
    return out


def fmt_table(title, acc, total):
    lines = ["| %s | metres | share |" % title, "|---|---|---|"]
    for k, v in sorted(acc.items(), key=lambda kv: -kv[1]):
        lines.append("| %s | %.0f | %.1f%% |" % (k, v, 100.0 * v / total if total else 0.0))
    return "\n".join(lines)


def report(path):
    ta = json.load(open(path))
    edges = ta.get("edges", [])
    total = sum(e["length"] for e in edges) * 1000.0
    print("# %s" % path)
    print("%d edges, %.0f m total (%.2f km, %.2f mi)\n" % (
        len(edges), total, total / 1000.0, total / 1000.0 * KMH_TO_MPH))
    for key in ("cycle_lane", "road_class", "surface", "use", "bicycle_network",
                "shoulder", "sac_scale"):
        print(fmt_table(key, totals(edges, key), total))
        print()
    unpaved = sum(e["length"] * 1000.0 for e in edges if e.get("unpaved"))
    print("unpaved total: %.0f m (%.1f%%)\n" % (unpaved, 100.0 * unpaved / total if total else 0.0))
    st = sorted(name_stretches(edges), key=lambda s: -s["m"])[:5]
    print("| # | longest cycle_lane=none stretch | metres | edges | names | speed limit | road_class | use |")
    print("|---|---|---|---|---|---|---|---|")
    for i, s in enumerate(st, 1):
        names = ", ".join(s["names"]) or "(no name in OSM)"
        if s["speed_limits"]:
            lim = ", ".join("%g km/h (%.0f mph)" % (v, v * KMH_TO_MPH)
                            for v in sorted(s["speed_limits"]))
        else:
            lim = "none in OSM"
        print("| %d | | %.0f | %d | %s | %s | %s | %s |" % (
            i, s["m"], s["edges"], names, lim, s["road_class"],
            "/".join(sorted(x for x in s["use"] if x))))
    print()
    print("all cycle_lane=none stretches: %d, %.0f m" % (
        len(name_stretches(edges)), sum(s["m"] for s in name_stretches(edges))))


if __name__ == "__main__":
    for p in sys.argv[1:]:
        report(p)
