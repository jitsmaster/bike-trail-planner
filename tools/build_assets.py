#!/usr/bin/env python3
"""Regenerate the app's bundled assets from the authoritative sources in docs/.

Sources (never modified by this script):
  docs/data/trails.json                          -> app/src/main/assets/trails.json
  docs/data/trails_regional.json                 -> app/src/main/assets/trails_regional.json
  docs/safety-probe-raw/access-probe-results-all.json -> app/src/main/assets/access_points.json
  docs/data/trail-corridor.md, docs/data/gap-link-routes.json -> trail_links.json (hand-written facts)
  docs/data/trails.json bike_access_conflicts, docs/SAFETY-PROBE.md 6.4 -> trail_closures.json

Run from the repo root:  python3 tools/build_assets.py
"""
import json, os, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")


def read(rel):
    with open(os.path.join(ROOT, rel)) as fh:
        return json.load(fh)


def write(name, obj):
    path = os.path.join(ASSETS, name)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=1, sort_keys=False)
        fh.write("\n")
    print("wrote", os.path.relpath(path, ROOT), os.path.getsize(path), "bytes")


def main():
    os.makedirs(ASSETS, exist_ok=True)

    # 1. the trail dataset, verbatim
    write("trails.json", read("docs/data/trails.json"))

    # 1b. the regional trail bundle (DOCS/DECISIONS.md D23): Washington + Portland metro
    #     route=bicycle/mtb relations, produced by tools/build_regional_trails.py.
    #     Copied byte for byte -- the producer owns the formatting, and the geometry is
    #     large enough that re-indenting it here would inflate the APK for nothing.
    regional = os.path.join(ROOT, "docs", "data", "trails_regional.json")
    if os.path.exists(regional):
        dest = os.path.join(ASSETS, "trails_regional.json")
        shutil.copyfile(regional, dest)
        print("copied", os.path.relpath(dest, ROOT), os.path.getsize(dest), "bytes")
    else:
        print("trails_regional.json skipped (run tools/build_regional_trails.py first)")

    # 2. the access points measured by the safety probe (143 routable candidates)
    probe = read("docs/safety-probe-raw/access-probe-results-all.json")
    aps = []
    for r in probe:
        aps.append({
            "trail": r["trail"],
            "osm_relation_id": r["rid"],
            "osm_node_id": r["node"],
            "lat": r["lat"],
            "lon": r["lon"],
            "on_road": r.get("road_name"),
            "highway": r.get("highway"),
            "shares_trail_node": bool(r.get("shared_node")),
            "straight_line_m_from_home": r["straight_m"],
            "measured_route_km_from_home": r.get("distance_km"),
            "snap_offset_m": r.get("snap_offset_m"),
        })
    write("access_points.json", {
        "schema_version": 1,
        "source": "docs/safety-probe-raw/access-probe-results-all.json",
        "measured": "2026-09-15",
        "note": ("143 candidates, one per distinct road adjacent to each trail plus every trail "
                 "endpoint. All 143 returned HTTP 200 from POST /route. The app re-routes and "
                 "re-grades every candidate it uses; these numbers are only used to rank them."),
        "count": len(aps),
        "access_points": aps,
    })

    # 3. the measured traffic-volume table. The traffic-data producer owns this file: if it
    #    has written one under docs/, copy it in; otherwise keep the table that is already
    #    in assets (seeded from docs/SPEC.md section 6b and docs/research/traffic-data-wa.md).
    produced = None
    for candidate in ("assets/traffic.json", "docs/traffic.json", "docs/data/traffic.json", "docs/research/traffic.json"):
        if os.path.exists(os.path.join(ROOT, candidate)):
            produced = candidate
            break
    if produced:
        with open(os.path.join(ROOT, produced)) as fh:
            # The producer's file is copied through unchanged: it owns that schema.
            data = json.load(fh)
            write("traffic.json", data)
        print("traffic.json replaced from", produced)
    else:
        print("traffic.json left as seeded (no producer file found under docs/)")

    # 4. speed overrides: same rule, the producer owns the file.
    for candidate in ("assets/limits.json", "docs/limits.json", "docs/data/limits.json"):
        if os.path.exists(os.path.join(ROOT, candidate)):
            with open(os.path.join(ROOT, candidate)) as fh:
                write("limits.json", json.load(fh))
            print("limits.json replaced from", candidate)
            break


if __name__ == "__main__":
    main()
