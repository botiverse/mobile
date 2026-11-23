#!/usr/bin/env bash
# Runs one `xcodebuild ... test` command with its output in LOG and its result bundle at BUNDLE.
# If it fails because the simulator's test runner never connected ("The test runner hung before
# establishing connection" — a hosted-runner simulator flake: runs 45059027125, 45101806901 and
# others on 11-22/23 passed unchanged on a plain rerun), it reboots that simulator and runs the
# command once more. Any other failure, or a second hang, returns xcodebuild's status unchanged.
#
# usage: xctest_retry_on_runner_hang.sh LOG BUNDLE DEVICE_ID -- xcodebuild ... test
set -uo pipefail

log=$1
bundle=$2
device_id=$3
shift 3
if [ "${1:-}" = "--" ]; then
  shift
fi

attempt() {
  # xcodebuild refuses a result bundle path that already exists.
  rm -rf "$bundle"
  "$@" > "$log" 2>&1
}

attempt "$@"
status=$?
if [ "$status" -eq 0 ]; then
  exit 0
fi

if grep -qi "test runner hung before establishing connection" "$log"; then
  echo "::warning::The XCTest runner hung before connecting to the simulator (infrastructure flake); rebooting the simulator and running the tests once more."
  mv "$log" "$log.attempt1"
  xcrun simctl shutdown "$device_id" > /dev/null 2>&1 || true
  xcrun simctl bootstatus "$device_id" -b > /dev/null 2>&1 || true
  attempt "$@"
  status=$?
fi
exit "$status"
