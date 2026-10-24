#!/usr/bin/env python3
"""Single source of truth for the Bike Trail Planner launcher icon.

Design (docs/APP-ICON.md): a cyclist riding along a trail, drawn as a near-silhouette so it still
reads at 48 dp. Every primitive lives in the PRIMITIVES dict below; this one script emits

  * the VectorDrawable layers          app/src/main/res/drawable/ic_launcher_{background,foreground}.xml
  * the adaptive-icon XML              app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
  * the legacy raster fallbacks        app/src/main/res/mipmap-*/ic_launcher{,_round}.png
  * the docs preview                   docs/app-icon-preview.png

and then verifies its own output: the ink of every emitted PNG must sit inside the adaptive-icon
safe zone (the central 66x66 of the 108x108 viewport, i.e. x,y in [21, 87]), coverage must stay in a
sane band, and the 48 px render is printed as ASCII art so the shape can be judged without eyes.

Regenerate with:  python3 tools/make_icon.py
"""
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
DOCS = os.path.join(ROOT, "docs")

# ---------------------------------------------------------------- canvas + palette
CANVAS = 108.0                 # adaptive-icon layer size, in dp
SAFE_LO, SAFE_HI = 21.0, 87.0  # guaranteed-visible square: the central 66x66
DESIGN_LO, DESIGN_HI = 23.0, 85.0  # self-imposed margin so antialiasing cannot reach the edge

# The same hexes live in res/values/colors.xml, and check() asserts they match, so the icon cannot
# drift from the app palette (Theme.kt MapsPalette):
#   #1A73E8 = MapsPalette.primary             -> colors.xml primary_light
#   #174EA6 = MapsPalette.onPrimaryContainer  -> colors.xml ic_launcher_background_end
#   #FFFFFF = MapsPalette.surface             -> colors.xml surface_light
#   #D2E3FC = MapsPalette.primaryContainer    -> colors.xml ic_launcher_trail
PALETTE = {
    "bg_start": "#FF1A73E8",
    "bg_end":   "#FF174EA6",
    "art":      "#FFFFFFFF",
    "trail":    "#FFD2E3FC",
}

# ---------------------------------------------------------------------------- geometry
# Every named coordinate of the artwork, in 108-space (the VectorDrawable viewport).
JOINTS = {
    "rear_hub":   (35.0, 64.0),   # wheel centres; the frame attaches relative to these
    "front_hub":  (73.0, 64.0),
    "bottom_brk": (53.0, 67.0),   # crank centre, below hub height just like a real bike
    "saddle":     (48.0, 50.0),
    "bar":        (70.0, 50.5),   # top of the head tube, where the hands are
    "bar_tip":    (75.0, 49.0),   # handlebar stub, forward of the stem
    "hip":        (47.6, 49.0),
    "shoulder":   (60.0, 38.5),
    "head":       (63.2, 32.6),
    "knee":       (57.5, 58.5),
    "pedal":      (52.0, 66.5),
}
WHEEL = {"r": 9.6, "stroke": 4.4}   # ring: outer radius 11.8, hole radius 7.4 (6.6 px of hole at 48 px)
RIM = WHEEL["r"]


def _rim(joint, towards, radius=RIM):
    """A point `radius` away from `joint`, on the way to `towards`.

    Frame members end here instead of at the hub: the round cap then stays inside the ring band and
    the hole, which is the whole reason a ring reads as a wheel rather than a dot, stays empty.
    """
    (x0, y0), (x1, y1) = JOINTS[joint], JOINTS[towards]
    dx, dy = x1 - x0, y1 - y0
    d = math.hypot(dx, dy)
    return (x0 + dx / d * radius, y0 + dy / d * radius)


# ONE dict of primitives, all plain numbers. Both emitters read this and nothing else.
PRIMITIVES = {
    "joints": JOINTS,
    # The trail: a gentle rise and fall, sampled from a Catmull-Rom spline so both emitters share it.
    "trail": [
        {"z": 10, "color": "trail", "width": 5.6,
         "control": [(27.0, 79.0), (40.0, 77.6), (54.0, 77.2), (68.0, 77.6), (81.0, 79.1)]},
    ],
    # Filled shapes. The torso is a quad and not a stick, because a mass reads at 48 px and a line
    # does not.
    "polygons": [
        {"z": 40, "color": "art",
         "points": [(49.93, 51.75), (62.71, 41.71), (57.29, 35.29), (45.27, 46.25)]},
    ],
    "lines": [
        # bicycle frame, each stay landing on its wheel's rim
        {"z": 30, "color": "art", "width": 4.2, "a": _rim("rear_hub", "bottom_brk"), "b": "bottom_brk"},
        {"z": 30, "color": "art", "width": 4.2, "a": _rim("rear_hub", "saddle"), "b": "saddle"},
        {"z": 30, "color": "art", "width": 4.2, "a": "saddle", "b": "bottom_brk"},
        {"z": 30, "color": "art", "width": 4.2, "a": "bottom_brk", "b": "bar"},
        {"z": 30, "color": "art", "width": 4.2, "a": _rim("front_hub", "bar"), "b": "bar"},
        {"z": 30, "color": "art", "width": 4.2, "a": "bar", "b": "bar_tip"},
        # rider
        {"z": 45, "color": "art", "width": 4.2, "a": "shoulder", "b": "bar"},
        {"z": 45, "color": "art", "width": 5.0, "a": "hip", "b": "knee"},
        {"z": 45, "color": "art", "width": 5.0, "a": "knee", "b": "pedal"},
    ],
    # Wheels. No hub dot: a dot in the middle is exactly what turns a wheel back into a disc at 48 px.
    "circles": [
        {"z": 25, "color": "art", "cx": "rear_hub", "r": WHEEL["r"], "stroke": WHEEL["stroke"]},
        {"z": 25, "color": "art", "cx": "front_hub", "r": WHEEL["r"], "stroke": WHEEL["stroke"]},
        {"z": 50, "color": "art", "cx": "head", "r": 5.6, "fill": True},
    ],
}


# ------------------------------------------------------------------------- geometry maths
def _rgb(hex_str):
    h = hex_str.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (2, 4, 6))


def _catmull_rom(control, samples=14):
    """Dense polyline through the control points. The SAME sampling feeds the vector and the raster."""
    if len(control) < 3:
        return [tuple(p) for p in control]
    p = [control[0]] + list(control) + [control[-1]]
    out = []
    for i in range(len(control) - 1):
        p0, p1, p2, p3 = p[i], p[i + 1], p[i + 2], p[i + 3]
        for j in range(samples):
            t = j / samples
            t2, t3 = t * t, t * t * t
            out.append((
                0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t
                       + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                       + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3),
                0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t
                       + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                       + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3),
            ))
    out.append(tuple(control[-1]))
    return out


def _pt(name):
    return PRIMITIVES["joints"][name] if isinstance(name, str) else tuple(name)


def draw_list():
    """Flatten PRIMITIVES into z-ordered draw ops, shared by the vector and the raster emitters."""
    ops = []
    for spec in PRIMITIVES["trail"]:
        ops.append((spec["z"], "curve", {"pts": _catmull_rom(spec["control"]),
                                         "width": spec["width"], "color": spec["color"]}))
    for spec in PRIMITIVES["polygons"]:
        ops.append((spec["z"], "polygon", {"points": spec["points"], "color": spec["color"]}))
    for spec in PRIMITIVES["lines"]:
        ops.append((spec["z"], "line", {"pts": [_pt(spec["a"]), _pt(spec["b"])],
                                        "width": spec["width"], "color": spec["color"]}))
    for spec in PRIMITIVES["circles"]:
        ops.append((spec["z"], "circle", {"c": _pt(spec["cx"]), "r": spec["r"],
                                          "stroke": spec.get("stroke"), "color": spec["color"]}))
    ops.sort(key=lambda o: o[0])
    return ops


def art_bbox():
    """Geometric bbox of the art in 108-space, half-strokes included."""
    xs, ys = [], []
    for _, kind, p in draw_list():
        if kind in ("curve", "line"):
            h = p["width"] / 2.0
            for x, y in p["pts"]:
                xs += [x - h, x + h]
                ys += [y - h, y + h]
        elif kind == "circle":
            r = p["r"] + (p["stroke"] or 0.0) / 2.0
            xs += [p["c"][0] - r, p["c"][0] + r]
            ys += [p["c"][1] - r, p["c"][1] + r]
        else:
            for x, y in p["points"]:
                xs.append(x)
                ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


# ------------------------------------------------------------------------------ emitters
def _hex_ref(color_key):
    """The Android colour resource that holds this palette entry."""
    return {"art": "@color/surface_light", "trail": "@color/ic_launcher_trail"}[color_key]


def _fmt(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def _circle_path(c, r):
    x, y = c
    return ("M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 -%s,0"
            % (_fmt(x - r), _fmt(y), _fmt(r), _fmt(r), _fmt(2 * r),
               _fmt(r), _fmt(r), _fmt(2 * r)))


def _polyline_path(pts):
    return ("M%s,%s" % (_fmt(pts[0][0]), _fmt(pts[0][1]))
            + " " + " ".join("L%s,%s" % (_fmt(x), _fmt(y)) for x, y in pts[1:]))


def vector_foreground_xml():
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- GENERATED by tools/make_icon.py - do not edit by hand.',
           '     Cyclist on a trail, drawn inside the adaptive-icon safe zone (x,y in [21,87] of the',
           '     108x108 viewport). Regenerate: python3 tools/make_icon.py -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"',
           '    android:height="108dp"',
           '    android:viewportWidth="108"',
           '    android:viewportHeight="108">']
    for _, kind, p in draw_list():
        if kind in ("curve", "line"):
            out += ['    <path',
                    '        android:pathData="%s"' % _polyline_path(p["pts"]),
                    '        android:fillColor="#00000000"',
                    '        android:strokeColor="%s"' % _hex_ref(p["color"]),
                    '        android:strokeWidth="%s"' % _fmt(p["width"]),
                    '        android:strokeLineCap="round"',
                    '        android:strokeLineJoin="round" />']
        elif kind == "circle":
            out.append('    <path')
            out.append('        android:pathData="%s"' % _circle_path(p["c"], p["r"]))
            if p["stroke"]:
                out += ['        android:fillColor="#00000000"',
                        '        android:strokeColor="%s"' % _hex_ref(p["color"]),
                        '        android:strokeWidth="%s"' % _fmt(p["stroke"]),
                        '        android:strokeLineCap="round"',
                        '        android:strokeLineJoin="round" />']
            else:
                out.append('        android:fillColor="%s" />' % _hex_ref(p["color"]))
        else:
            closed = list(p["points"]) + [p["points"][0]]
            out += ['    <path',
                    '        android:pathData="%s"' % _polyline_path(closed),
                    '        android:fillColor="%s" />' % _hex_ref(p["color"])]
    out.append('</vector>')
    return "\n".join(out) + "\n"


def vector_background_xml():
    """One linear gradient, corner to corner, in the app's own two blues."""
    return """<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/make_icon.py - do not edit by hand.
     App brand blue -> the dark blue already used for content-on-primary.
     Regenerate: python3 tools/make_icon.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108"
                android:startColor="@color/primary_light"
                android:endColor="@color/ic_launcher_background_end" />
        </aapt:attr>
    </path>
</vector>
"""


def adaptive_xml():
    return """<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/make_icon.py - do not edit by hand.
     minSdk is 26, so every device that can run the app inflates this instead of the PNGs below. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""


# ------------------------------------------------------------------------------ rasteriser
SS = 8  # supersample factor, for antialiasing


def gradient_image(size):
    """The background: one linear gradient, top-left corner to bottom-right corner.

    Pure PIL/bytes so that `python3 tools/make_icon.py` needs nothing but Pillow. The diagonal ramp
    only takes the values k/(2*(size-1)), so it is built once as a lookup table and sliced per row.
    """
    a = _rgb(PALETTE["bg_start"])
    b = _rgb(PALETTE["bg_end"])
    denom = 2.0 * (size - 1) if size > 1 else 1.0
    lut = [bytes(int(round(a[c] + (b[c] - a[c]) * (k / denom))) for c in range(3))
           for k in range(2 * size - 1)]
    data = b"".join(b"".join(lut[y:y + size]) for y in range(size))
    return Image.frombytes("RGB", (size, size), data)


def art_layer(size):
    """The foreground art as an RGBA image at `size` px: drawn supersampled, then box-filtered down."""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = big / CANVAS

    def px(p):
        return (p[0] * k, p[1] * k)

    for _, kind, p in draw_list():
        col = _rgb(PALETTE[p["color"]]) + (255,)
        if kind in ("line", "curve"):
            pts = [px(q) for q in p["pts"]]
            w = max(1, int(round(p["width"] * k)))
            d.line(pts, fill=col, width=w, joint="curve")
            r = w / 2.0
            for q in (pts[0], pts[-1]):  # round caps, matching strokeLineCap="round"
                d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=col)
        elif kind == "polygon":
            d.polygon([px(q) for q in p["points"]], fill=col)
        else:
            cx, cy = px(p["c"])
            r = p["r"] * k
            if p["stroke"]:
                d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col,
                          width=max(1, int(round(p["stroke"] * k))))
            else:
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
    return img.resize((size, size), Image.LANCZOS)


def render(size, round_icon):
    img = gradient_image(size).convert("RGBA")
    img.alpha_composite(art_layer(size))
    if round_icon:
        big = size * SS
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).ellipse([1, 1, big - 1, big - 1], fill=255)
        img.putalpha(mask.resize((size, size), Image.LANCZOS))
    return img


# ------------------------------------------------------------------------------ verification
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
INK_THRESHOLD = 24  # max-channel distance from the ideal background gradient


def flat_and_bg(path):
    """(RGB pixels of the PNG composited over the ideal background, background pixels, size).

    Everything here reads the file back off disk and compares it against the analytically rendered
    background gradient, so the numbers describe the shipped asset and not the generator's intent.
    """
    img = Image.open(path).convert("RGBA")
    size = img.size[0]
    bg = gradient_image(size).convert("RGB")
    flat = Image.alpha_composite(bg.convert("RGBA"), img).convert("RGB")
    return list(flat.getdata()), list(bg.getdata()), size


def ink_rows(flat, bg, size):
    """Ink = a pixel that differs from the background gradient by more than INK_THRESHOLD."""
    return [[max(abs(flat[y * size + x][c] - bg[y * size + x][c]) for c in range(3)) > INK_THRESHOLD
             for x in range(size)] for y in range(size)]


def bbox_of(rows):
    xs = [x for row in rows for x, hit in enumerate(row) if hit]
    ys = [y for y, row in enumerate(rows) if any(row)]
    return (min(xs), min(ys), max(xs), max(ys)) if xs else None


def count(rows):
    return sum(sum(row) for row in rows)


def ascii_panel(values, size, chars):
    return ["".join(chars[min(len(chars) - 1, int(values[y][x] * (len(chars) - 1)))]
                    for x in range(size)) for y in range(size)]


def check():
    ok = True
    print("=" * 84)
    print("ICON VERIFICATION   safe zone = central 66x66 of the 108x108 viewport, i.e. x,y in [21,87]")
    print("=" * 84)

    bx0, by0, bx1, by1 = art_bbox()
    print("authoring bbox of PRIMITIVES: x %.2f..%.2f  y %.2f..%.2f  (self-imposed limit [%.1f,%.1f])"
          % (bx0, bx1, by0, by1, DESIGN_LO, DESIGN_HI))
    if bx0 < DESIGN_LO or bx1 > DESIGN_HI or by0 < DESIGN_LO or by1 > DESIGN_HI:
        print("FAIL: authoring bbox leaves the self-imposed [%.1f,%.1f] margin" % (DESIGN_LO, DESIGN_HI))
        ok = False

    print()
    print("%-8s %-18s %4s %-22s %-10s %7s %9s  %s"
          % ("bucket", "file", "px", "ink bbox", "allowed", "ink px", "coverage", "verdict"))
    ascii48 = None
    for name in sorted(SIZES, key=lambda k: SIZES[k]):
        size = SIZES[name]
        for round_icon in (False, True):
            base = "ic_launcher_round.png" if round_icon else "ic_launcher.png"
            path = os.path.join(RES, "mipmap-" + name, base)
            flat, bg, sz = flat_and_bg(path)
            assert sz == size, (path, sz, size)
            rows = ink_rows(flat, bg, sz)
            bb = bbox_of(rows)
            ink = count(rows)
            cov = 100.0 * ink / (size * size)
            lo = int(math.ceil(SAFE_LO / CANVAS * size))
            hi = int(math.floor(SAFE_HI / CANVAS * size)) - 1
            inside = bb is not None and bb[0] >= lo and bb[1] >= lo and bb[2] <= hi and bb[3] <= hi
            print("%-8s %-18s %4d (%3d,%3d)-(%3d,%3d)   %2d..%-5d %7d %8.2f%%  %s"
                  % (name, base, size, bb[0], bb[1], bb[2], bb[3], lo, hi, ink, cov,
                     "INSIDE SAFE ZONE" if inside else "*** OUTSIDE SAFE ZONE ***"))
            if not inside:
                print("   FAIL: %s ink leaves the safe zone" % base)
                ok = False
            if not (2.0 <= cov <= 35.0):
                print("   FAIL: coverage %.2f%% is outside the sane band 2%%..35%%" % cov)
                ok = False
            if ink > 0.9 * size * size:
                print("   FAIL: solid blob")
                ok = False
            if name == "mdpi" and not round_icon:
                ascii48 = path

    # --- legibility, read back from the real 48 px asset on disk
    path = ascii48
    flat, bg, size = flat_and_bg(path)
    mask = ink_rows(flat, bg, size)

    # The trail colour is close to the antialiased edge of the white art, so a pure colour test
    # would light up every outline. Instead the emitted trail geometry is tested against pixels that
    # the PNG really does have ink in: "where the trail is, and is actually drawn".
    trail_spec = PRIMITIVES["trail"][0]
    tpts = _catmull_rom(trail_spec["control"])
    half = trail_spec["width"] / 2.0 + 0.6

    def on_trail(px, py):
        for (ax, ay), (bx, by) in zip(tpts, tpts[1:]):
            vx, vy = bx - ax, by - ay
            seg2 = vx * vx + vy * vy
            t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((px - ax) * vx + (py - ay) * vy) / seg2))
            dx, dy = px - (ax + t * vx), py - (ay + t * vy)
            if dx * dx + dy * dy <= half * half:
                return True
        return False

    alpha_rows, trail_rows = [], []
    for y in range(size):
        arow, trow = [], []
        for x in range(size):
            p, q = flat[y * size + x], bg[y * size + x]
            den = max(255 - q[0], 255 - q[1], 255 - q[2], 1)
            arow.append(min(1.0, max(0.0, max(p[c] - q[c] for c in range(3)) / den)))
            trow.append(1.0 if (mask[y][x]
                                and on_trail((x + 0.5) * CANVAS / size, (y + 0.5) * CANVAS / size))
                        else 0.0)
        alpha_rows.append(arow)
        trail_rows.append(trow)

    def panel(rows, chars, title, legend):
        print()
        print(title)
        print("    +" + "-" * size + "+")
        for i, row in enumerate(ascii_panel(rows, size, chars)):
            print("%3d |%s|" % (i, row))
        print("    +" + "-" * size + "+")
        print(legend)

    rel = os.path.relpath(path, ROOT)
    binary = [[1.0 if v >= 0.5 else 0.0 for v in row] for row in alpha_rows]
    panel(binary, " #",
          "SHAPE at 48x48 (%s): '#' is a pixel the art covers at least half of" % rel,
          "columns 0..%d, rows 0..%d" % (size - 1, size - 1))
    panel(alpha_rows, " .:-=+*#%@",
          "INK at 48x48 (%s): ' ' = pure background, @ = full art" % rel,
          "legend: . : - = + * # % @ = increasing coverage")
    panel(trail_rows, " o",
          "TRAIL at 48x48 (%s): 'o' = the trail geometry where the PNG has ink" % rel,
          "legend: o = trail")

    lower = mask[size // 2:]
    left = sum(sum(row[:size * 11 // 24]) for row in lower)
    right = sum(sum(row[size * 13 // 24:]) for row in lower)
    holes = 0
    for joint in ("rear_hub", "front_hub"):
        cx, cy = PRIMITIVES["joints"][joint]
        cx, cy = int(cx / CANVAS * size), int(cy / CANVAS * size)
        patch = [row[max(0, cx - 4):cx + 5] for row in mask[max(0, cy - 4):cy + 5]]
        holes += 1 if sum(1 for row in patch for hit in row if not hit) > 8 else 0
    print()
    print("legibility probes at 48 px: lower-half ink, left wheel=%d right wheel=%d; "
          "wheel-enclosed background (a real hole, not a disc)=%d/2" % (left, right, holes))
    if holes < 2 or left == 0 or right == 0:
        print("   FAIL: at 48 px the two wheels are not two distinct rings")
        ok = False

    # --- cross-checks on the hand-maintained files
    colors = open(os.path.join(RES, "values/colors.xml")).read()
    for key in sorted(PALETTE):
        if PALETTE[key] not in colors:
            print("FAIL: %s (%s) is missing from res/values/colors.xml" % (key, PALETTE[key]))
            ok = False
    manifest = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml")).read()
    for attr in ('android:icon="@mipmap/ic_launcher"', 'android:roundIcon="@mipmap/ic_launcher_round"'):
        if attr not in manifest:
            print("FAIL: AndroidManifest.xml is missing %s" % attr)
            ok = False
    print()
    print("RESULT: %s" % ("PASS" if ok else "FAIL"))
    return ok


# ------------------------------------------------------------------------------------ writer
def write_all():
    files = {
        os.path.join(RES, "drawable/ic_launcher_foreground.xml"): vector_foreground_xml(),
        os.path.join(RES, "drawable/ic_launcher_background.xml"): vector_background_xml(),
        os.path.join(RES, "mipmap-anydpi-v26/ic_launcher.xml"): adaptive_xml(),
        os.path.join(RES, "mipmap-anydpi-v26/ic_launcher_round.xml"): adaptive_xml(),
    }
    for name, size in SIZES.items():
        files[os.path.join(RES, "mipmap-" + name, "ic_launcher.png")] = render(size, False)
        files[os.path.join(RES, "mipmap-" + name, "ic_launcher_round.png")] = render(size, True)
    for path, payload in files.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if isinstance(payload, str):
            with open(path, "w") as fh:
                fh.write(payload)
        else:
            payload.save(path)
        print("wrote %s" % os.path.relpath(path, ROOT))
    ppath = os.path.join(DOCS, "app-icon-preview.png")
    preview_image().save(ppath)
    print("wrote %s" % os.path.relpath(ppath, ROOT))


def mask_icon(panel, circular):
    icon = render(panel, False).convert("RGB")
    big = panel * SS
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    if circular:
        d.ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(panel * 0.22) * SS, fill=255)
    mask = mask.resize((panel, panel), Image.LANCZOS)
    out = Image.new("RGB", (panel, panel), (255, 255, 255))
    out.paste(icon, (0, 0), mask)
    return out


def preview_image(panel=512):
    """512 px icon, then the same icon behind a circular and a rounded-square launcher mask."""
    strip = Image.new("RGB", (panel * 3, panel), (255, 255, 255))
    strip.paste(render(panel, False).convert("RGB"), (0, 0))
    strip.paste(mask_icon(panel, True), (panel, 0))
    strip.paste(mask_icon(panel, False), (panel * 2, 0))
    return strip


if __name__ == "__main__":
    write_all()
    print()
    sys.exit(0 if check() else 1)
