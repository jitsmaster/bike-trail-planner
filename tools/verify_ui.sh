#!/usr/bin/env bash
# Structural UI verification for the Bike Trail Planner app (docs/UI.md section 7).
#
# Usage: tools/verify_ui.sh [adb-device-id]
#
# Requirement: the app is installed and was already driven to each screen (or pass args to drive it).
# This script only inspects: it dumps the UI tree for the current screen, saves a screenshot, and
# runs tools/verify_ui.py. Run it once per screen:
#
#   $ tools/verify_ui.sh emulator-5554 plan
#   $ tools/verify_ui.sh emulator-5554 result
#   $ tools/verify_ui.sh emulator-5554 settings
set -euo pipefail

ADB_BIN="/opt/homebrew/bin/adb"
DEVICE="${1:-emulator-5554}"
SCREEN="${2:-plan}"
OUT="${3:-/tmp/btp-ui}"
mkdir -p "$OUT"

ADB="$ADB_BIN -s $DEVICE"
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null
$ADB shell cat /sdcard/ui.xml > "$OUT/$SCREEN.xml"
$ADB shell screencap -p /sdcard/shot.png >/dev/null
$ADB pull /sdcard/shot.png "$OUT/$SCREEN.png" >/dev/null

echo "== strings ($SCREEN) =="
python3 "$(dirname "$0")/verify_ui.py" strings "$OUT/$SCREEN.xml" --screen "$SCREEN"
echo "== colours ($SCREEN) =="
python3 "$(dirname "$0")/verify_ui.py" colors "$OUT/$SCREEN.png"
echo "== basemap ($SCREEN) =="
python3 "$(dirname "$0")/verify_ui.py" map "$OUT/$SCREEN.xml" "$OUT/$SCREEN.png"
