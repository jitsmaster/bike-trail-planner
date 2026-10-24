#!/usr/bin/env python3
"""Stream a simulated bike ride into an emulator's GPS so the ride recorder can be tested without
riding (D51, docs/RIDE-RECORDER.md section 4).

This drives the REAL `AndroidLocationSource`: the app reads the platform location provider, and the
platform is fed NMEA sentences through the emulator console. Nothing in the app is mocked.

Usage:
    python3 tools/simulate_ride.py --serial emulator-5554 \
        --lat 47.240371 --lon -122.262019 --distance-m 1500 --speed-mps 5 --interval-s 2

Then, on the device, start a recording from the app (or the debug "Simulate a ride" control) and
watch the notification. `--dry-run` prints the sentences without sending anything.

Notes:
  * the emulator must already be running and `adb reverse tcp:8002 tcp:8002` set if the app plans;
  * grant the app location permission (`adb shell pm grant com.biketrailplanner
    android.permission.ACCESS_FINE_LOCATION`) before recording;
  * `adb emu geo nmea` only accepts one sentence at a time; this script paces them in real time.
"""
import argparse
import math
import subprocess
import sys
import time

METRES_PER_DEG_LAT = 111_320.0


def rectangle(origin_lat, origin_lon, perimeter_m):
    """A closed rectangle of the requested perimeter, starting and ending at the origin."""
    side = perimeter_m / 4.0
    kx = METRES_PER_DEG_LAT * math.cos(math.radians(origin_lat))
    dlat = side / METRES_PER_DEG_LAT
    dlon = side / kx
    corners = [
        (origin_lat, origin_lon),
        (origin_lat, origin_lon + dlon),
        (origin_lat + dlat, origin_lon + dlon),
        (origin_lat + dlat, origin_lon),
        (origin_lat, origin_lon),
    ]
    dense = []
    for a, b in zip(corners, corners[1:]):
        seg = math.hypot((b[0] - a[0]) * METRES_PER_DEG_LAT, (b[1] - a[1]) * kx)
        n = max(1, int(seg / 5))
        for i in range(n):
            t = i / n
            dense.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    dense.append(corners[-1])
    return dense


def resample(points, step_m):
    """Re-space a polyline to one point every step_m metres, by arc length."""
    out = [points[0]]
    carry = 0.0
    for a, b in zip(points, points[1:]):
        seg = math.hypot((b[0] - a[0]) * METRES_PER_DEG_LAT,
                         (b[1] - a[1]) * METRES_PER_DEG_LAT * math.cos(math.radians(a[0])))
        if seg <= 1e-6:
            continue
        travelled = step_m - carry
        while travelled <= seg:
            t = travelled / seg
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            travelled += step_m
        carry = seg - (travelled - step_m)
    out.append(points[-1])
    return out


def _deg_min(value, deg_digits):
    d = int(abs(value))
    minutes = (abs(value) - d) * 60.0
    return f"{d:0{deg_digits}d}{minutes:07.4f}"


def nmea_gga(lat, lon, t_seconds, hdop=0.9, sats=8):
    """One $GPGGA sentence with a correct checksum. The emulator parses this into a location fix."""
    h = int(t_seconds // 3600) % 24
    m = int(t_seconds // 60) % 60
    s = t_seconds % 60
    ts = f"{h:02d}{m:02d}{s:05.2f}"
    lat_s = f"{_deg_min(lat, 2)},{'N' if lat >= 0 else 'S'}"
    lon_s = f"{_deg_min(lon, 3)},{'E' if lon >= 0 else 'W'}"
    body = f"GPGGA,{ts},{lat_s},{lon_s},1,{sats:02d},{hdop:.1f},0.0,M,0.0,M,,"
    checksum = 0
    for ch in body:
        checksum ^= ord(ch)
    return f"${body}*{checksum:02X}"


def send(adb, serial, sentence):
    return subprocess.run(
        [adb, "-s", serial, "emu", "geo", "nmea", sentence],
        capture_output=True, text=True, timeout=60,
    )


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--lat", type=float, required=True)
    ap.add_argument("--lon", type=float, required=True)
    ap.add_argument("--distance-m", type=float, default=1500.0, help="loop perimeter in metres")
    ap.add_argument("--speed-mps", type=float, default=5.0, help="~11 mph by default")
    ap.add_argument("--interval-s", type=float, default=2.0, help="seconds between NMEA sentences")
    ap.add_argument("--dry-run", action="store_true", help="print the sentences, send nothing")
    ap.add_argument("--start-time", type=float, default=0.0, help="NMEA clock seconds to start at")
    args = ap.parse_args()

    if args.speed_mps <= 0 or args.interval_s <= 0:
        ap.error("--speed-mps and --interval-s must be positive")

    step_m = args.speed_mps * args.interval_s
    track = resample(rectangle(args.lat, args.lon, args.distance_m), step_m)

    print(f"# {len(track)} fixes over {args.distance_m:.0f} m at {args.speed_mps:.1f} m/s "
          f"({args.interval_s:.1f} s apart)", file=sys.stderr)
    if not args.dry_run:
        print(f"# sending to {args.serial} via `{args.adb} emu geo nmea`", file=sys.stderr)

    for i, (lat, lon) in enumerate(track):
        sentence = nmea_gga(lat, lon, args.start_time + i * args.interval_s)
        if args.dry_run:
            print(sentence)
        else:
            result = send(args.adb, args.serial, sentence)
            if result.returncode != 0:
                print(f"adb failed: {result.stderr.strip()}", file=sys.stderr)
                return 1
            time.sleep(args.interval_s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
