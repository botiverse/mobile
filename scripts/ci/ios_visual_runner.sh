#!/usr/bin/env bash
# Own only this job's temporary directory and newly created simulator.
set -euo pipefail
case "${1:-}" in
  prepare)
    root=$(mktemp -d "${RUNNER_TEMP:?}/ios-visual.XXXXXX")
    echo "IOS_CAPTURE_TEMP=$root" >> "${GITHUB_ENV:?}"
    xcodebuild -version
    xcrun simctl help >/dev/null
    python3 --version
    pod --version
    ;;
  boot)
    : "${IOS_CAPTURE_TEMP:?}" "${SIM_DEVICE:?}"
    runtime=$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
rs = [r for r in json.load(sys.stdin)["runtimes"] if r["isAvailable"] and r["platform"] == "iOS"]
if not rs: sys.exit("No available iOS Simulator runtime; install it in Xcode before using this runner")
print(max(rs, key=lambda r: tuple(map(int, r["version"].split("."))))["identifier"])
')
    devtype=$(xcrun simctl list devicetypes -j | python3 -c 'import json,sys,os; print(next(d["identifier"] for d in json.load(sys.stdin)["devicetypes"] if d["name"] == os.environ["SIM_DEVICE"]))')
    udid=$(xcrun simctl create "visualcap-$(basename "$IOS_CAPTURE_TEMP")" "$devtype" "$runtime")
    # Save ownership before boot: even a failed boot must delete this device.
    printf '%s\n' "$udid" > "$IOS_CAPTURE_TEMP/simulator-udid"
    echo "UDID=$udid" >> "${GITHUB_ENV:?}"
    xcrun simctl boot "$udid"
    xcrun simctl bootstatus "$udid" -b
    ;;
  cleanup)
    [ -n "${IOS_CAPTURE_TEMP:-}" ] || exit 0
    case "$IOS_CAPTURE_TEMP" in
      "${RUNNER_TEMP:?}"/ios-visual.*) ;;
      *) echo 'Refusing cleanup outside job-owned temporary directory' >&2; exit 1 ;;
    esac
    if [ -f "$IOS_CAPTURE_TEMP/simulator-udid" ]; then
      udid=$(cat "$IOS_CAPTURE_TEMP/simulator-udid")
      [[ "$udid" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]] || exit 1
      # Shutdown can fail if already stopped. Deletion must succeed before
      # removing the ownership record; otherwise retain it for diagnosis.
      xcrun simctl shutdown "$udid" || true
      xcrun simctl delete "$udid"
    fi
    rm -rf -- "$IOS_CAPTURE_TEMP"
    ;;
  *) echo 'Usage: ios_visual_runner.sh prepare|boot|cleanup' >&2; exit 2 ;;
esac
