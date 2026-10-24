#!/usr/bin/env python3
"""Structural UI checks for the Bike Trail Planner app (docs/UI.md section 7).

The agent that writes this app cannot see the screen, so the checks are structural:

  verify_ui.py strings <ui-dump.xml> [--screen plan|result|settings]
      fail if any banned string appears, and require the strings that must appear

  verify_ui.py colors <screenshot.png>
      report the dominant colours, and flag a missing primary or surface token

Exit code 0 means every check passed.
"""
import re
import sys
import zlib

# docs/UI.md section 1 and section 7.
# Codes are matched as whole words and case-sensitively (so "suspect" does not trip SPEC);
# vocabulary is matched case-insensitively as a whole word or phrase.
BANNED_CODES = ["D2", "D9", "D12", "D13", "D14", "D15", "D16", "D17", "D18", "SPEC", "AADT", "LTS"]
BANNED_WORDS = [
    "vpd", "STALE", "tertiary", "service_other", "cycleway", "bicycle_network",
    "trace_attributes", "capped", "changeset", "osm_relation",
]
BANNED_PHRASES = [
    "corridor exhausted", "graded ", "ungraded ", "residential street class",
    "way 419747831", "route relation", "assets/",
]
BANNED_PLAN_RESULT = ["http://", "https://", "home 47.", "47.24", "-122.26", "Valhalla", "server"]
COORD = re.compile(r"-?\d{2}\.\d{4,}\s*,\s*-?\d{2,3}\.\d{4,}")

# D22: the pill says "from here" - the ride is planned around the LIVE position, not the saved home.
# The verifier asked for "from home" until 2026-09-21 and failed every run on a correct screen.
REQUIRED_PLAN = ["Plan a ride from here", "Round-trip distance", "Plan ride", "Bike Trail Planner"]
REQUIRED_RESULT = ["Confirm on map", "Details", "round trip"]
REQUIRED_SETTINGS = ["OpenStreetMap", "ODbL", "WSDOT", "Pierce County", "PSRC", "Server address"]
# D21: the basemap is a GL surface, so uiautomator cannot see it. These are the proxy signals:
# the corner attribution the map must carry, the absence of the "unavailable" note, and a
# screenshot with enough distinct colours to be a rendered map rather than a flat canvas.
MAP_ATTRIBUTION_HINT = "openstreetmap contributors"
MAP_UNAVAILABLE = "map unavailable"

# docs/UI.md section 2
TOKENS = {
    "primary #1A73E8": (0x1A, 0x73, 0xE8),
    "surface #FFFFFF": (0xFF, 0xFF, 0xFF),
    "sheet #F1F3F4": (0xF1, 0xF3, 0xF4),
    "grade A #188038": (0x18, 0x80, 0x38),
    "grade C #F9AB00": (0xF9, 0xAB, 0x00),
    "not measured #9AA0A6": (0x9A, 0xA0, 0xA6),
}


def texts(xml: str):
    return [t for t in re.findall(r'text="([^"]*)"', xml) if t]


def check_map(path_xml: str, path_png: str) -> int:
    xml = open(path_xml, encoding="utf-8").read().lower()
    shown = " || ".join(texts(open(path_xml, encoding="utf-8").read())).lower()
    failures = []
    if MAP_ATTRIBUTION_HINT not in shown:
        failures.append("the map attribution is not on screen (D21 licence requirement)")
    if MAP_UNAVAILABLE in shown:
        failures.append("the basemap reported itself unavailable")
    width, height, channels, pixels = read_png(path_png)
    counts = {}
    for i in range(0, len(pixels), channels * 7):  # sample every 7th pixel
        rgb = (pixels[i], pixels[i + 1], pixels[i + 2])
        counts[rgb] = counts.get(rgb, 0) + 1
    total = sum(counts.values())
    light = sum(n for rgb, n in counts.items() if min(rgb) > 200) / max(total, 1)
    print("map screenshot: %d distinct colours (sampled), light share %.1f%%" % (len(counts), 100.0 * light))
    if len(counts) < 60:
        failures.append("the screenshot has only %d distinct colours: no map is being drawn" % len(counts))
    if light < 0.5:
        failures.append("the map area is not light: %.1f%% light" % (100.0 * light))
    for f in failures:
        print("  FAIL " + f)
    if not failures:
        print("  OK: basemap present (attribution on screen, light tiles rendered)")
    return 1 if failures else 0


def check_strings(path: str, screen: str) -> int:
    xml = open(path, encoding="utf-8").read()
    shown = " || ".join(texts(xml))
    failures = []
    for code in BANNED_CODES:
        if re.search(r"\b" + re.escape(code) + r"\b", shown):
            failures.append("banned code %r is on screen" % code)
    for word in BANNED_WORDS:
        if re.search(r"\b" + re.escape(word) + r"\b", shown, re.IGNORECASE):
            failures.append("banned word %r is on screen" % word)
    for phrase in BANNED_PHRASES:
        if phrase.lower() in shown.lower():
            failures.append("banned phrase %r is on screen" % phrase)
    for bad in (BANNED_PLAN_RESULT if screen in ("plan", "result") else []):
        if bad.lower() in shown.lower():
            failures.append("banned string %r is on screen" % bad)
    if screen in ("plan", "result"):
        for m in COORD.findall(shown):
            failures.append("raw coordinate %r is on screen" % m)
    required = {"plan": REQUIRED_PLAN, "result": REQUIRED_RESULT, "settings": REQUIRED_SETTINGS}.get(screen, [])
    for want in required:
        if want.lower() not in shown.lower():
            failures.append("required string %r is missing" % want)
    print("screen=%s nodes=%d" % (screen, len(texts(xml))))
    for f in failures:
        print("  FAIL " + f)
    if not failures:
        print("  OK: no banned strings, all required strings present")
    return 1 if failures else 0


def read_png(path: str):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, width, height, bitdepth, colortype, idat = 8, 0, 0, 0, 0, b""
    while pos < len(data):
        length = int.from_bytes(data[pos:pos + 4], "big")
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, bitdepth, colortype = (
                int.from_bytes(chunk[0:4], "big"), int.from_bytes(chunk[4:8], "big"), chunk[8], chunk[9],
            )
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
        pos += 12 + length
    assert bitdepth == 8, "only 8-bit PNGs are supported"
    channels = {2: 3, 6: 4, 0: 1}[colortype]
    raw = zlib.decompress(idat)
    stride = width * channels
    out = bytearray(height * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = prev[i]
            c = prev[i - channels] if i >= channels else 0
            if filt == 1:
                line[i] = (line[i] + a) & 0xFF
            elif filt == 2:
                line[i] = (line[i] + b) & 0xFF
            elif filt == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 0xFF
            elif filt == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, channels, bytes(out)


def check_colors(path: str) -> int:
    width, height, channels, pixels = read_png(path)
    counts = {}
    for i in range(0, len(pixels), channels):
        rgb = (pixels[i], pixels[i + 1], pixels[i + 2])
        counts[rgb] = counts.get(rgb, 0) + 1
    total = width * height
    top = sorted(counts.items(), key=lambda kv: -kv[1])[:10]
    print("image %dx%d, %d distinct colours" % (width, height, len(counts)))
    for rgb, n in top:
        print("  #%02X%02X%02X  %5.1f%%" % (rgb[0], rgb[1], rgb[2], 100.0 * n / total))
    missing = []
    for name, token in TOKENS.items():
        if not any(_close(rgb, token) for rgb in counts):
            missing.append(name)
    print("tokens present: " + ", ".join(n for n in TOKENS if n not in missing))
    if missing:
        print("  NOTE not present in this screenshot: " + ", ".join(missing))
    return 0


def _close(rgb, token, tol=6):
    return all(abs(rgb[i] - token[i]) <= tol for i in range(3))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    mode, target = sys.argv[1], sys.argv[2]
    if mode == "strings":
        screen = "plan"
        if "--screen" in sys.argv:
            screen = sys.argv[sys.argv.index("--screen") + 1]
        sys.exit(check_strings(target, screen))
    elif mode == "colors":
        sys.exit(check_colors(target))
    elif mode == "map":
        # verify_ui.py map <ui-dump.xml> <screenshot.png>
        sys.exit(check_map(target, sys.argv[3]))
    else:
        print(__doc__)
        sys.exit(2)
