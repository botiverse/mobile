#!/usr/bin/env bash
# Runs the public-Downloads authority instrumentation on the current emulator and always
# captures the framework logcat that explains a cross-UID read denial (MediaProvider,
# URI grants, ActivityManager). The API 31 lane intermittently reports
# "external reader failed: SecurityException"; the probe only sees the exception, while
# the denying check is logged by system_server/MediaProvider.
#
# Usage: run-public-downloads-authority.sh <api-level> [repeat] [reboot]
#   repeat (default 1) re-runs the class back to back to reproduce intermittent failures;
#   reboot=true reboots the emulator before every iteration after the first, so each run
#   starts right after BOOT_COMPLETED like the single CI run does (MediaProvider's boot
#   SCAN_VOLUME is still in flight). The script exits non-zero if any iteration fails.
set -uo pipefail

api="${1:?usage: run-public-downloads-authority.sh <api-level> [repeat]}"
repeat="${2:-1}"
reboot="${3:-false}"
here="$(cd "$(dirname "$0")" && pwd)"
logcat_file="attachment-public-downloads-api${api}-logcat.txt"
summary_file="attachment-public-downloads-api${api}-repeat-summary.txt"

adb logcat -c || true
: > "$summary_file"
: > "$logcat_file"
overall=0
for i in $(seq 1 "$repeat"); do
  if [ "$reboot" = true ] && [ "$i" -gt 1 ]; then
    adb logcat -d -v threadtime >> "$logcat_file" 2>/dev/null || true
    adb reboot
    adb wait-for-device
    for _ in $(seq 1 120); do
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
      sleep 1
    done
    adb logcat -c || true
  fi
  if [ "$repeat" -eq 1 ]; then
    out="attachment-public-downloads-api${api}-instrumentation.txt"
  else
    out="attachment-public-downloads-api${api}-instrumentation-${i}.txt"
  fi
  "$here/run-instrumentation-with-retry.sh" "$out" 1 -- adb shell am instrument -w -r \
    -e class build.raft.app.shared.AndroidAttachmentPublicDownloadsConnectedTest \
    build.raft.app.connectedtest.test/androidx.test.runner.AndroidJUnitRunner
  rc=$?
  echo "iteration=$i rc=$rc" | tee -a "$summary_file"
  if [ "$rc" -ne 0 ]; then
    overall=$rc
    grep -h "external reader failed" "$out" | head -n 1 | tee -a "$summary_file" || true
  fi
done
adb logcat -d -v threadtime >> "$logcat_file" 2>/dev/null || true
grep -E "MediaProvider|UriGrants|SecurityException|ActivityManager: Permission|ExternalReadProbe" \
  "$logcat_file" | tail -n 200 > "attachment-public-downloads-api${api}-logcat-focus.txt" || true
echo "failures=$(grep -c 'rc=[1-9]' "$summary_file") of $repeat" | tee -a "$summary_file"
exit "$overall"
