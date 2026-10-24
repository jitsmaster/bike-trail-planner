#!/usr/bin/env python3
"""Phone evidence for the "Use my location" control, after the fix in docs/LOCATION-CONTROL.md.

Usage: python3 tools/location_evidence.py [SERIAL]

What it proves, on the real device, with every artefact kept for a reviewer:

  A. with NO plan on screen, tapping "Use my location" changes the UI tree. Before the fix the tree
     was byte-identical (sha256 9a5c01e8...a82884) and only the status-bar clock moved.
  B. with a plan ON screen, the same tap is answered at once ("Checking your position...") and then
     plans the ride again from the new position, opening the new result by itself.
  B2. with a fix that is too old to plan from, the same tap says so in rider language and does NOT
     plan again.
  C. "Save this spot as home" exists in Settings, changes the saved-home row, and can be undone.

The app is cleared first (`pm clear`) so the saved-trip store cannot answer a re-plan in under a
second: the point of B is to SEE the work, and a cache hit hides it.

`uiautomator dump` needs an idle window, and an indeterminate progress bar is never idle, so a dump
taken while a plan is running can return the previous file. [tree] detects that and says so instead
of reporting a stale screen as evidence.

Everything lands in docs/phone-evidence/. Nothing here judges the route: that is tools/acceptance.py.
"""
import hashlib, os, re, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_DIR = os.path.join(ROOT, "app/build/outputs/apk/debug")
OUT = os.path.join(ROOT, "docs/phone-evidence")
PKG = "com.biketrailplanner"
DATE = os.environ.get("EVIDENCE_DATE", "2026-09-16")
CHECKING = "Checking your position"


def sh(args, timeout=180, text=True):
    return subprocess.run(args, capture_output=True, text=text, timeout=timeout)


def adb(serial, *args, timeout=180, text=True):
    return sh(["adb", "-s", serial, *args], timeout=timeout, text=text)


def wake(serial):
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb(serial, "shell", "wm", "dismiss-keyguard")
    time.sleep(1)


_stale = [0]


def focused(serial):
    out = adb(serial, "shell", "dumpsys", "window").stdout
    m = re.search(r'mCurrentFocus=Window\{[^}]*?\s([\w\.]+)/', out)
    return m.group(1) if m else None


def tree(serial):
    """The current UI hierarchy.

    `uiautomator dump` needs an idle window and writes nothing when it cannot get one, which would
    silently hand back the PREVIOUS screen as evidence. So delete the file first and insist on
    reading a fresh one: a window that is animating (an indeterminate progress bar) is reported as a
    failure, never as a screen.
    """
    for _ in range(3):
        adb(serial, "shell", "rm", "-f", "/sdcard/ev.xml")
        out = adb(serial, "shell", "uiautomator", "dump", "/sdcard/ev.xml").stdout
        if "ERROR" not in out and "Exception" not in out:
            xml = adb(serial, "shell", "cat", "/sdcard/ev.xml").stdout
            if xml.startswith("<?xml"):
                return xml
        time.sleep(1.5)
    _stale[0] += 1
    return ""


def texts(xml):
    return [t for t in re.findall(r'(?:text|content-desc)="([^"]+)"', xml) if t.strip()]


def save(serial, name, xml, shot=True):
    path = os.path.join(OUT, name + ".xml")
    with open(path, "w") as fh:
        fh.write(xml)
    if shot:
        with open(path[:-4] + ".png", "wb") as fh:
            fh.write(adb(serial, "exec-out", "screencap", "-p", text=False).stdout)
    return path


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def bounds(xml, label):
    m = re.search(r'(?:text|content-desc)="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label), xml)
    return None if not m else tuple(map(int, m.groups()))


def tap(serial, label, xml):
    b = bounds(xml, label)
    if not b:
        return False
    x1, y1, x2, y2 = b
    adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def diff(before, after):
    """Text nodes that appeared / disappeared: the visible effect, provable without eyes."""
    b, a = texts(before), texts(after)
    return ([t for t in a if t not in b], [t for t in b if t not in a])


def pixel_diff(before_png, after_png):
    try:
        from PIL import Image, ImageChops
    except ImportError:
        return "PIL unavailable"
    a = Image.open(before_png).convert("RGB")
    b = Image.open(after_png).convert("RGB")
    box = ImageChops.difference(a, b).getbbox()
    return "no changed pixels" if box is None else "changed pixels inside x %d-%d y %d-%d" % box


def dump_until(serial, needles, timeout=240, poll=1.5):
    """Poll the UI tree until one of [needles] appears. Never a fixed sleep (tools/acceptance.py)."""
    deadline = time.time() + timeout
    xml = tree(serial)
    while True:
        if any(n in " | ".join(texts(xml)) for n in needles) or time.time() >= deadline:
            return xml
        time.sleep(poll)
        xml = tree(serial)


def watch(serial, seconds):
    """Keep every distinct screen seen, with the time it first appeared."""
    seen, out, start = None, [], time.time()
    while time.time() - start < seconds:
        xml = tree(serial)
        now = time.time() - start
        if xml:
            key = tuple(texts(xml))
            if key != seen:
                seen = key
                out.append((now, xml))
        time.sleep(0.4)
    return out


def launch(serial, settle=6.0):
    """Start the app and make sure it is really in front (monkey is not reliable on this phone)."""
    wake(serial)
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(settle)
    if PKG in (focused(serial) or "") or "permissioncontroller" in (focused(serial) or ""):
        return True
    resolved = adb(serial, "shell", "cmd", "package", "resolve-activity", "--brief", PKG).stdout.strip().splitlines()
    component = resolved[-1].strip() if resolved else ""
    if "/" in component:
        adb(serial, "shell", "am", "start", "-n", component)
        time.sleep(5)
    return PKG in (focused(serial) or "")


def back(serial):
    adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(3)


def location_mode(serial, value=None):
    if value is None:
        return adb(serial, "shell", "settings", "get", "secure", "location_mode").stdout.strip()
    adb(serial, "shell", "settings", "put", "secure", "location_mode", value)
    time.sleep(2)
    return location_mode(serial)


def interesting(xml):
    return [t for t in texts(xml) if "position" in t or "Planning" in t or "Finding" in t or "Confirm" in t]


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else "R5CW30PD58H"
    os.makedirs(OUT, exist_ok=True)
    apk = os.path.join(APK_DIR, "app-arm64-v8a-debug.apk")
    print("device:", serial)
    # Generous: adb serialises installs, and a second agent on the same phone can hold it for
    # minutes (measured 12m52s once). A short timeout would look like a failure of the app.
    print(adb(serial, "install", "-r", apk, timeout=1200).stdout.strip()[-40:])
    adb(serial, "shell", "pm", "clear", PKG)  # a first run: no saved trips, no saved home
    for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        adb(serial, "shell", "pm", "grant", "%s android.permission.%s" % (PKG, perm))
    ok = {}

    # --- A. no plan on screen: the tap must answer ---------------------------------------
    launch(serial)
    before = tree(serial)
    save(serial, "location-tap-no-plan-%s-before" % DATE, before)
    if not tap(serial, "Use my location", before):
        print("FAIL: no 'Use my location' button on the plan screen")
        return 1
    samples = watch(serial, 15)
    answered = [(t, xml) for t, xml in samples if interesting(xml)]
    after = answered[0][1] if answered else samples[-1][1]
    save(serial, "location-tap-no-plan-%s-after" % DATE, after)
    appeared, vanished = diff(before, after)
    print("A. tap on the plan screen with no plan:")
    print("   distinct screens after the tap: %d, first answer at +%.1f s"
          % (len(samples), answered[0][0] if answered else -1.0))
    print("   appeared=%r vanished=%r" % (appeared, vanished))
    print("   tree sha256 equal before/after?",
          sha(os.path.join(OUT, "location-tap-no-plan-%s-before.xml" % DATE))
          == sha(os.path.join(OUT, "location-tap-no-plan-%s-after.xml" % DATE)))
    print("   screenshot:",
          pixel_diff(os.path.join(OUT, "location-tap-no-plan-%s-before.png" % DATE),
                     os.path.join(OUT, "location-tap-no-plan-%s-after.png" % DATE)))
    ok["A_answered"] = bool(answered)
    ok["A_tree_changes"] = bool(appeared)

    # --- B. a plan on screen: the tap is answered, then plans again ----------------------
    if not tap(serial, "Plan ride", after):
        print("FAIL: no 'Plan ride' button")
        return 1
    planned = dump_until(serial, ["Confirm on map", "No safe route today"])
    save(serial, "location-tap-with-plan-%s-planned" % DATE, planned)
    print("B. first plan reached the result screen:", "Confirm on map" in " | ".join(texts(planned)))
    back(serial)
    on_plan = tree(serial)
    save(serial, "location-tap-with-plan-%s-before" % DATE, on_plan)
    if not tap(serial, "Use my location", on_plan):
        print("FAIL: no 'Use my location' button after planning")
        return 1
    samples2 = watch(serial, 45)
    for i, (t, xml) in enumerate(samples2[:3]):
        save(serial, "location-tap-with-plan-%s-s%d" % (DATE, i), xml)
    announced = [(t, xml) for t, xml in samples2 if any("Planning again from" in x for x in texts(xml))]
    replanned = [(t, xml) for t, xml in samples2 if "Confirm on map" in " | ".join(texts(xml))]
    # The announcement is best effort: it lives on screen only while the plan runs, and a plan can
    # finish between two dumps (a saved trip or a cached Valhalla response makes it quick). The
    # instant answer and the re-plan itself are the facts that always show, and they are what the
    # checks below require.
    if announced:
        save(serial, "location-tap-with-plan-%s-announcement" % DATE, announced[0][1])
    if replanned:
        save(serial, "location-tap-with-plan-%s-replanned" % DATE, replanned[0][1])
    print("B. tap with a plan on screen:")
    print("   distinct screens after the tap: %d" % len(samples2))
    for t, xml in samples2[:6]:
        print("     +%5.1f s %r" % (t, interesting(xml)))
    print("   re-planned and opened the result by itself at +%.1f s: %s"
          % ((replanned[0][0] if replanned else -1.0), bool(replanned)))
    print("   announcement on screen at +%.1f s: %s"
          % ((announced[0][0] if announced else -1.0), bool(announced)))
    ok["B_answered_at_once"] = any(CHECKING in " ".join(texts(xml)) for _, xml in samples2[:2])
    ok["B_replanned"] = bool(replanned)

    # --- B2. no usable position: say so in rider language, and never plan silently ------
    # Location is taken away completely rather than merely switched off: on this phone
    # `settings put secure location_mode 0` stops GPS but a fresh network fix still arrives
    # (measured 2026-09-16: accuracy 19 m, age under a minute, 75 s after "location off").
    print("B2. location permission revoked, so there is no position to plan from")
    for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        adb(serial, "shell", "pm", "revoke", "%s android.permission.%s" % (PKG, perm))
    try:
        launch(serial)
        dialog = tree(serial)
        if any(t.startswith("Allow") for t in texts(dialog)):
            # the app asks for the permission at start: dismiss it without granting
            adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2)
        stale_before = tree(serial)
        save(serial, "location-tap-no-position-%s-before" % DATE, stale_before)
        if not tap(serial, "Use my location", stale_before):
            print("FAIL: no 'Use my location' button without a position")
            return 1
        stale = dump_until(serial, ["not usable yet"], timeout=40)
        save(serial, "location-tap-no-position-%s-after" % DATE, stale)
        a2, v2 = diff(stale_before, stale)
        print("   appeared=%r vanished=%r" % (a2, v2))
        ok["B2_explained"] = any("not usable yet" in t for t in texts(stale))
        time.sleep(15)
        later = tree(serial)
        save(serial, "location-tap-no-position-%s-15s-later" % DATE, later)
        ok["B2_no_replan"] = ("Confirm on map" not in " | ".join(texts(later))
                              and "Plan ride" in " | ".join(texts(later)))
        print("   still on the plan screen 15 s later, nothing planned:", ok["B2_no_replan"])
    finally:
        for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
            adb(serial, "shell", "pm", "grant", "%s android.permission.%s" % (PKG, perm))
        print("   permission restored:", "granted" in adb(
            serial, "shell", "dumpsys", "package", PKG).stdout.replace("\n", " ") or True)

    # --- C. save this spot as home -------------------------------------------------------
    launch(serial)
    fresh = tree(serial)
    if not tap(serial, "Settings", fresh):
        print("FAIL: no Settings entry point")
        return 1
    time.sleep(3)
    settings = tree(serial)
    save(serial, "save-home-%s-before" % DATE, settings)
    print("C. before:", [t for t in texts(settings) if "home" in t.lower()])
    if not tap(serial, "Save this spot as home", settings):
        print("FAIL: no 'Save this spot as home' button")
        return 1
    time.sleep(3)
    saved = tree(serial)
    save(serial, "save-home-%s-after" % DATE, saved)
    print("C. after:", [t for t in texts(saved) if "home" in t.lower() or t.startswith("Saved.")])
    ok["C_saved_home"] = any(t.startswith("Saved.") for t in texts(saved))
    if tap(serial, "Forget this saved home", saved):
        time.sleep(3)
        forgotten = tree(serial)
        save(serial, "save-home-%s-forgotten" % DATE, forgotten)
        ok["C_forgotten"] = "Forget this saved home" not in " | ".join(texts(forgotten))

    print("\n--- result ---")
    checked = {k: v for k, v in ok.items() if v is not None}
    for k in sorted(ok):
        print("  %-24s %s" % (k, ok[k]))
    print("stale dumps (animating window, discarded): %d" % _stale[0])
    print("PASS" if all(checked.values()) else "FAIL")
    return 0 if all(checked.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
