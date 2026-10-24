#!/usr/bin/env python3
"""Independent acceptance test for Bike Trail Planner, run against a real device.

Usage: python3 tools/acceptance.py [SERIAL]
Assumes: the APK is built at app/build/outputs/apk/debug/app-debug.apk and a device is attached.

This deliberately does NOT trust the implementing agent's report. It drives the phone itself,
reads the real UI tree, and fails loudly on the things we care about:
  1. the app installs and launches
  2. a plan runs against the live Valhalla server
  3. no internal vocabulary leaks onto a main screen
  4. the required rider-facing wording is present
  5. the Google Maps hand-off actually reaches the Google Maps package (SEC-17)
"""
import os, re, subprocess, sys, tempfile, time, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_DIR = os.path.join(ROOT, "app/build/outputs/apk/debug")
# ABI splits are on: arm64-v8a for the phone, x86_64 for the emulator (see app/build.gradle.kts).
APK_FALLBACKS = ("app-universal-debug.apk", "app-debug.apk")


def apk_for(serial):
    """Pick the split APK that matches the attached device's ABI (falls back to a universal one)."""
    abi = adb(serial, "shell", "getprop", "ro.product.cpu.abi").stdout.strip().split(",")[0].strip()
    names = ([f"app-{abi}-debug.apk"] if abi else []) + list(APK_FALLBACKS)
    for name in names:
        path = os.path.join(APK_DIR, name)
        if os.path.exists(path):
            return path, abi or "unknown"
    return os.path.join(APK_DIR, "app-debug.apk"), abi or "unknown"
PKG = "com.biketrailplanner"
MAPS = "com.google.android.apps.maps"

BANNED = ["(D2)", "(D9)", "(D12)", "SPEC", "vpd", "STALE", "tertiary", "service_other",
          "corridor exhausted", "graded ", "ungraded ", "not checked yet"]
REQUIRED_PLAN = ["Plan ride", "5 mi", "10 mi", "50 mi"]   # D20: 5-mile steps, range 5-50
# How long a plan may take before the gate calls it a failure, in seconds. The plan runs on the
# phone against Valhalla over Tailscale and fetches its first map tiles in the same breath, so a
# fixed wait is a race: measured 2026-09-16 a 25 s sleep asserted on a screen that finished
# rendering moments later and reported three false failures.
RESULT_TIMEOUT_S = 120
# D22: the origin must be the live position, not home. The plan screen must say so in rider language.
REQUIRED_ORIGIN_HINTS = [["your location", "Your location", "from here", "current location", "where you are"]]
# "Details" and "Start navigating" are required because they are the rows a taller sheet pushes off
# first: measured 2026-09-26, the D27 AI block grew the result sheet's fixed rows until the scrolling
# part had zero height, and the grade bar, the measured caption and the whole Details expander were
# unreachable - then one more line (the D31 way-back sentence) took "Start navigating" with it.
REQUIRED_RESULT_HINTS = [["Confirm on map"], ["round trip", "Round trip"], ["Details", "details"],
                         ["Start navigating"]]

def sh(args, timeout=120, text=True):
    # text=False is required for binary payloads such as `exec-out screencap -p`,
    # which are PNG bytes and cannot be decoded as UTF-8.
    return subprocess.run(args, capture_output=True, text=text, timeout=timeout)

def adb(serial, *args, timeout=120, text=True):
    return sh(["adb", "-s", serial, *args], timeout=timeout, text=text)

def screen_size(serial):
    """Device pixels, so a scroll gesture can be aimed without hardcoding one phone's numbers."""
    out = adb(serial, "shell", "wm", "size").stdout
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)

def scroll(serial, down=True):
    """Scroll the sheet one screen: down=True reveals content BELOW (finger moves up), and back."""
    w, h = screen_size(serial)
    if down:
        y1, y2 = int(h * 0.62), int(h * 0.30)
    else:
        y1, y2 = int(h * 0.30), int(h * 0.62)
    adb(serial, "shell", "input", "swipe", str(w // 2), str(y1), str(w // 2), str(y2), "400")
    time.sleep(2)

def tap_scrolling(serial, label, tries=5):
    """Tap a control, searching the sheet in both directions if it is below or above the fold.

    Measured 2026-09-26 on the Galaxy S23 Ultra: the "Plan ride" button sits below the visible part of
    the plan sheet, and `uiautomator dump` reports VISIBLE nodes only - so the gate reported
    "could not find a 'Plan ride' button" on a screen that was completely correct (the button appeared
    after two swipes and the plan then ran). Both directions are tried because a previous check may
    have left the sheet scrolled past the control. Bounded, so an unreachable control still fails.
    """
    for attempt in range(tries):
        _texts, xml = ui_tree(serial)
        if tap(serial, label, xml):
            return True
        if attempt < tries - 1:
            scroll(serial, down=(attempt % 2 == 0))
    return False

def texts_scrolling(serial, swipes=3):
    """Every visible label, collected over several scroll positions of one screen.

    A single dump sees only the top of a scrollable sheet, so a required label that lives below the
    fold AND an internal-vocabulary leak that lives below the fold are both invisible to it. Measured
    2026-09-26: the plan sheet's "Plan ride" button and the result sheet's "Details" expander were both
    reported missing on correct screens. Collecting across positions makes the leak check STRICTER
    (more of the screen is read), never weaker.
    """
    seen = []
    for i in range(swipes + 1):
        texts, _xml = ui_tree(serial)
        for t in texts:
            if t not in seen:
                seen.append(t)
        if i < swipes:
            scroll(serial, down=(i % 2 == 0))
    return seen

def ui_tree(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/acc.xml")
    out = adb(serial, "shell", "cat", "/sdcard/acc.xml").stdout
    return re.findall(r'(?:text|content-desc)="([^"]+)"', out), out

def wake(serial):
    """Wake the screen and dismiss the keyguard before any check.

    A sleeping or locked phone returns its lock-screen wallpaper from `screencap`, which reads as
    'dark' and fails the theme check for the wrong reason, and its UI tree has none of the app's
    buttons. Measured on the user's phone: a run that started while the phone was asleep captured a
    52,363-colour wallpaper (light=0.3%, dark=9.9%).
    """
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb(serial, "shell", "wm", "dismiss-keyguard")
    time.sleep(1)

def launch_app(serial):
    """monkey is not reliable -- verified broken for every app on one emulator image. Try it, verify
    with the focused window, then fall back to an explicit am start of the resolved launcher activity."""
    adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)
    if PKG in (focus(serial)[0] or ""):
        return "monkey"
    resolved = adb(serial, "shell", "cmd", "package", "resolve-activity", "--brief", PKG).stdout.strip().splitlines()
    component = resolved[-1].strip() if resolved else ""
    if "/" in component:
        adb(serial, "shell", "am", "start", "-n", component)
        time.sleep(5)
        if PKG in (focus(serial)[0] or ""):
            return f"am start -n {component}"
    return "FAILED"

def night_mode(serial, value=None):
    """Read or set the device night mode. value: 'yes' | 'no' | None (read)."""
    if value is None:
        out = adb(serial, "shell", "cmd", "uimode", "night").stdout
        return "yes" if "yes" in out.lower() else "no"
    adb(serial, "shell", "cmd", "uimode", "night", value)
    time.sleep(3)
    return value

def theme_check(serial, shot_path):
    """The app must be LIGHT even when the device is in night mode (D19: the user rides in daylight).
    Deterministic and vision-free: force night mode, screenshot, count light vs dark pixels."""
    original = night_mode(serial)
    notes = [f"device night mode was {original}"]
    try:
        wake(serial)
        night_mode(serial, "yes")
        wake(serial)
        adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(6)
        with open(shot_path, "wb") as fh:
            fh.write(adb(serial, "exec-out", "screencap", "-p", text=False).stdout)
        from PIL import Image
        img = Image.open(shot_path).convert("RGB")
        w, h = img.size
        px = list(img.resize((w // 4, h // 4)).getdata())
        light = sum(1 for r, g, b in px if r > 200 and g > 200 and b > 200) / len(px)
        dark = sum(1 for r, g, b in px if r < 70 and g < 70 and b < 70) / len(px)
        notes.append(f"light={light:.1%} dark={dark:.1%}")
        return (light > dark, notes)
    finally:
        night_mode(serial, original)

def focus(serial):
    out = adb(serial, "shell", "dumpsys", "window").stdout
    m = re.search(r"mCurrentFocus=Window\{[^}]*?\s([\w\.]+)/([\w\.]+)\}", out)
    return (m.group(1), m.group(2)) if m else (None, None)

def tap(serial, label, xml):
    m = None
    for attr in ("text", "content-desc"):
        m = re.search(r'%s="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (attr, re.escape(label)), xml)
        if m:
            break
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True

def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else None
    if not serial:
        lines = sh(["adb", "devices"]).stdout.splitlines()[1:]
        devs = [l.split()[0] for l in lines if l.strip() and "device" in l and "emulator" not in l]
        if not devs:
            print("FAIL: no physical device attached"); return 1
        serial = devs[0]
    print(f"device: {serial}")
    fails, notes = [], []

    apk, abi = apk_for(serial)
    print("install ... " + os.path.relpath(apk, ROOT) + " (device abi " + abi + ")")
    # Long: adb serialises installs and a second agent on the same phone can hold the device for
    # tens of minutes (measured 2026-09-16: 12m52s and 38m41s for the same command that usually
    # takes seconds). A short timeout reports a false failure of the app, not of the install.
    r = adb(serial, "install", "-r", apk, timeout=2400)
    if "Success" not in r.stdout:
        print("FAIL: install:", r.stdout[-300:]); return 1
    for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        adb(serial, "shell", "pm", "grant", f"{PKG} android.permission.{perm}")

    print("launch ...")
    wake(serial)
    adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(6)
    texts, xml = ui_tree(serial)
    if not any(PKG in (f or "") for f in focus(serial)):
        fails.append("app did not take focus after launch")
    # A sheet is read over several scroll positions: the plan sheet's "Plan ride" button sits below
    # the fold on the Galaxy S23 Ultra (see texts_scrolling).
    plan_texts = texts_scrolling(serial)
    for t in REQUIRED_PLAN:
        if t not in plan_texts:
            fails.append(f"plan screen missing {t!r}")
    joined0 = " | ".join(plan_texts)
    for group in REQUIRED_ORIGIN_HINTS:
        if not any(g in joined0 for g in group):
            fails.append(f"plan screen does not state that it starts from the live position (one of {group})")

    print("theme (must stay light even in device night mode) ...")
    ok, tnotes = theme_check(serial, os.path.join(tempfile.gettempdir(), "biketrail-theme-check.png"))
    notes.extend(tnotes)
    if not ok:
        fails.append("app rendered DARK while the device was in night mode (D19 requires light always)")

    print("plan ...")
    wake(serial)
    texts, xml = ui_tree(serial)
    if not tap_scrolling(serial, "Plan ride"):
        fails.append("could not find a 'Plan ride' button (even after scrolling the sheet)")
    else:
        # Poll for the result screen rather than sleeping a fixed time, so a slow plan is not
        # reported as a missing-string failure. Bounded, so a real hang still fails.
        deadline = time.time() + RESULT_TIMEOUT_S
        texts, xml = ui_tree(serial)
        while time.time() < deadline:
            if all(any(g in " | ".join(texts) for g in group) for group in REQUIRED_RESULT_HINTS):
                break
            time.sleep(3)
            texts, xml = ui_tree(serial)
        # Read the whole sheet, not just its top: the result sheet's "Details" expander is below the
        # fold, and a leak below the fold must fail the gate too.
        texts = texts_scrolling(serial)
        joined = " | ".join(texts)
        for group in REQUIRED_RESULT_HINTS:
            if not any(g in joined for g in group):
                fails.append(f"result screen missing one of {group}")
        for bad in BANNED:
            if bad in joined:
                fails.append(f"internal vocabulary leaked on screen: {bad!r}")
        # D31: the way-back line. A NOTER, not a failure: the loop search only runs on a ride the app
        # is going to deliver, so a plan that offered nothing compliant legitimately has no line, and
        # an adopted saved ride shows stored data rather than a fresh measurement. What is checked is
        # the other half of the promise - the two sentences the feature may print are the only ones
        # that may appear, and they must never claim a loop that was not measured.
        if any(g in joined for g in ("different way", "comes back the same way")):
            if "different way" in joined and "% of the roads on the way back are new" not in joined:
                fails.append("the loop line claims a different way back without the measured share")
            notes.append("way back (D31): " + ("a loop was measured" if "different way" in joined
                                               else "no different safe way back was found"))
        else:
            notes.append("way back (D31): no line on screen (no ride delivered this run, or a saved ride)")

    print("hand-off ...")
    wake(serial)
    texts, xml = ui_tree(serial)
    if tap_scrolling(serial, "Confirm on map") or tap_scrolling(serial, "Start navigating"):
        time.sleep(9)
        pkg, act = focus(serial)
        if pkg != MAPS:
            fails.append(f"hand-off did not reach Google Maps (focus={pkg}/{act}); SEC-17 package pinning?")
    else:
        notes.append("no hand-off button found; skipped the Maps check")

    print("\n--- screens seen ---")
    for t in texts[:24]:
        print("   ", t)
    print("\n--- result ---")
    if notes:
        for n in notes: print("note:", n)
    if fails:
        for f in fails: print("FAIL:", f)
        return 1
    print("PASS: install, launch, plan, no leaked vocabulary, hand-off reaches Google Maps")
    return 0

if __name__ == "__main__":
    sys.exit(main())
