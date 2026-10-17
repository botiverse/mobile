#!/usr/bin/env bash
# Own only this job's temporary directory and newly created simulators.
# boot --shards N: create N job-owned simulators and boot them in parallel;
# each shard captures its own slice in its own UDID. Default remains 1 —
# behaviour identical to the pre-sharding version.
set -euo pipefail
shards_of() {
  case "${IOS_CAPTURE_SHARDS:-1}" in
    ''|*[!0-9]*) echo 'IOS_CAPTURE_SHARDS must be a positive integer' >&2; exit 1 ;;
    *) echo "${IOS_CAPTURE_SHARDS:-1}" ;;
  esac
}
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
    if [ "${1:-}" = "boot" ]; then shift; fi
    while [ $# -gt 0 ]; do
      case "$1" in
        --shards) IOS_CAPTURE_SHARDS="$2"; shift 2 ;;
        *) echo "unknown boot option: $1" >&2; exit 2 ;;
      esac
    done
    shards=$(shards_of)
    runtime=$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
rs = [r for r in json.load(sys.stdin)["runtimes"] if r["isAvailable"] and (r.get("platform") == "iOS" or r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-"))]
if not rs: sys.exit("No available iOS Simulator runtime; install it in Xcode before using this runner")
print(max(rs, key=lambda r: tuple(map(int, r["version"].split("."))))["identifier"])
')
    devtype=$(xcrun simctl list devicetypes -j | python3 -c 'import json,sys,os; print(next(d["identifier"] for d in json.load(sys.stdin)["devicetypes"] if d["name"] == os.environ["SIM_DEVICE"]))')
    : > "$IOS_CAPTURE_TEMP/simulator-udids"
    i=0
    while [ "$i" -lt "$shards" ]; do
      if [ "$shards" -eq 1 ]; then
        devname="visualcap-$(basename "$IOS_CAPTURE_TEMP")"
      else
        devname="visualcap-$(basename "$IOS_CAPTURE_TEMP")-s$i"
      fi
      udid=$(xcrun simctl create "$devname" "$devtype" "$runtime")
      # Save ownership before boot: even a failed boot must delete this device.
      printf '%s\n' "$udid" >> "$IOS_CAPTURE_TEMP/simulator-udids"
      echo "SHARD_UDID_$i=$udid" >> "${GITHUB_ENV:?}"
      i=$((i+1))
    done
    # Back-compat: shard 0's UDID stays in UDID/simulator-udid for
    # single-shard callers and the original cleanup contract.
    udid0=$(head -1 "$IOS_CAPTURE_TEMP/simulator-udids")
    printf '%s\n' "$udid0" > "$IOS_CAPTURE_TEMP/simulator-udid"
    echo "UDID=$udid0" >> "${GITHUB_ENV:?}"
    echo "IOS_CAPTURE_SHARDS=$shards" >> "${GITHUB_ENV:?}"
    # Boots are independent; run them in parallel and wait for all.
    pids=
    while IFS= read -r udid; do
      xcrun simctl boot "$udid" &
      pids="$pids $!"
    done < "$IOS_CAPTURE_TEMP/simulator-udids"
    for pid in $pids; do wait "$pid"; done
    while IFS= read -r udid; do xcrun simctl bootstatus "$udid" -b; done < "$IOS_CAPTURE_TEMP/simulator-udids"
    ;;
  cleanup)
    [ -n "${IOS_CAPTURE_TEMP:-}" ] || exit 0
    case "$IOS_CAPTURE_TEMP" in
      "${RUNNER_TEMP:?}"/ios-visual.*) ;;
      *) echo 'Refusing cleanup outside job-owned temporary directory' >&2; exit 1 ;;
    esac
    # simulator-udids is the multi-shard list; simulator-udid is the original
    # single-device marker. Union both — either one alone pins ownership.
    udid_list=$(mktemp "${RUNNER_TEMP:?}/ios-udids.XXXXXX")
    cat "$IOS_CAPTURE_TEMP/simulator-udids" "$IOS_CAPTURE_TEMP/simulator-udid" 2>/dev/null | sort -u > "$udid_list" || true
    if [ -s "$udid_list" ]; then
      # Validate every recorded UDID before touching any device: a foreign
      # line must refuse the whole cleanup, not delete the good ones first.
      while IFS= read -r udid; do
        [[ "$udid" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]] || exit 1
      done < "$udid_list"
      while IFS= read -r udid; do
        # Shutdown can fail if already stopped. RUNNER_TEMP is cleared after
        # the job, so a failed delete needs an uploaded recovery receipt.
        xcrun simctl shutdown "$udid" || true
        status=0
        xcrun simctl delete "$udid" || status=$?
        if [ "$status" -ne 0 ]; then
          diagnostics="${GITHUB_WORKSPACE:?}/visual-testing-results/ios-diagnostics"
          mkdir -p "$diagnostics"
          python3 - "$udid" "$diagnostics/simulator-cleanup-failure.json" <<'PYRECEIPT'
import json, os, sys
with open(sys.argv[2], "w") as out:
    json.dump({"udid": sys.argv[1], "runner": os.environ.get("RUNNER_NAME"),
               "runId": os.environ.get("GITHUB_RUN_ID"),
               "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
               "temporaryDirectory": os.path.basename(os.environ["IOS_CAPTURE_TEMP"])}, out, indent=2)
PYRECEIPT
          exit "$status"
        fi
      done < "$udid_list"
    fi
    rm -f "$udid_list"
    rm -rf -- "$IOS_CAPTURE_TEMP"
    ;;
  *) echo 'Usage: ios_visual_runner.sh prepare|boot [--shards N]|cleanup' >&2; exit 2 ;;
esac
