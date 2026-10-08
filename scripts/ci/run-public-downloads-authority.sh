#!/usr/bin/env bash
# Runs the public-Downloads authority instrumentation on the current emulator and always
# captures the framework logcat that explains a cross-UID read denial.
#
# Readiness precondition (a stabilisation step, NOT a verified root-cause fix): the API 31 lane
# intermittently fails the cross-UID read with MediaProvider.checkAccess denying a granted URI
# ("<pkg> has no access to content://media/external/downloads/N"). The one captured failure
# (run 37855699822) overlapped MediaProvider's first-boot database creation/migration, which is
# when the single CI run executes. Why the grant is not honoured there is still under
# investigation. Before the test, wait-media-provider-ready.sh requires an explicit readiness
# signal (a verified scan_volume Result, else the logged boot scan) within a deadline; a
# readiness timeout fails the run. This narrows coverage of
# the boot phase on purpose; the cross-UID and byte assertions are unchanged and not retried.
#
# Usage: run-public-downloads-authority.sh <api-level> [repeat] [mode]
#   repeat (default 1): run the class N times back to back.
#   mode (default ci):
#     ci        wait for MediaProvider readiness, then run (normal CI behaviour)
#     reboot    reboot before every iteration after the first and run immediately, without the
#               readiness wait (reproduction of the boot-phase denial)
#     ab-fresh  per iteration: clear MediaProvider data and reboot (fresh-AVD-like first boot);
#               odd iterations run immediately (control), even iterations wait first
set -uo pipefail

api="${1:?usage: run-public-downloads-authority.sh <api-level> [repeat] [mode]}"
repeat="${2:-1}"
mode="${3:-ci}"
here="$(cd "$(dirname "$0")" && pwd)"
logcat_file="attachment-public-downloads-api${api}-logcat.txt"
summary_file="attachment-public-downloads-api${api}-repeat-summary.txt"
media_pkg="$(adb shell pm list packages 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep -E '^com\.(google\.)?android\.providers\.media\.module$' | head -n 1)"

boot_timeout="${BOOT_TIMEOUT:-300}"

wait_for_boot() {
  local deadline=$((SECONDS + boot_timeout))
  timeout "$boot_timeout" adb wait-for-device || { echo "boot=wait_for_device_timeout"; return 1; }
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$(timeout 15 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
    sleep 1
  done
  echo "boot=boot_completed_timeout"
  return 1
}

# Reboots and waits for the new boot; any failure is reported and must fail the iteration.
reboot_device() {
  timeout 60 adb reboot >/dev/null 2>&1 || { echo "boot=reboot_command_failed"; return 1; }
  wait_for_boot || return 1
  adb logcat -G 16M >/dev/null 2>&1 || true
}

wait_for_media_ready() {
  "$here/wait-media-provider-ready.sh"
}

: > "$summary_file"
: > "$logcat_file"
adb logcat -G 16M >/dev/null 2>&1 || true
overall=0
for i in $(seq 1 "$repeat"); do
  wait_media=true
  case "$mode" in
    reboot)
      wait_media=false
      if [ "$i" -gt 1 ]; then
        adb logcat -d -v threadtime >> "$logcat_file" 2>/dev/null || true
        if ! boot="$(reboot_device)"; then
          echo "iteration=$i wait=false $boot rc=1" | tee -a "$summary_file"
          overall=1
          continue
        fi
      fi
      ;;
    ab-fresh)
      adb logcat -d -v threadtime >> "$logcat_file" 2>/dev/null || true
      if [ -n "$media_pkg" ]; then adb shell pm clear "$media_pkg" >/dev/null 2>&1 || true; fi
      if [ $((i % 2)) -eq 1 ]; then wait_media=false; fi
      if ! boot="$(reboot_device)"; then
        # A failed reboot is a failed iteration, never a valid control or fix sample.
        echo "iteration=$i wait=$wait_media $boot rc=1" | tee -a "$summary_file"
        overall=1
        continue
      fi
      ;;
  esac
  ready="media_ready=skipped"
  if [ "$wait_media" = true ]; then
    if ! ready="$(wait_for_media_ready)"; then
      # Never test on an unready MediaProvider: a timeout is a failed run, not a skip.
      echo "iteration=$i wait=true $ready rc=1" | tee -a "$summary_file"
      overall=1
      continue
    fi
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
  echo "iteration=$i wait=$wait_media $ready rc=$rc" | tee -a "$summary_file"
  if [ "$rc" -ne 0 ]; then
    overall=$rc
    grep -h "external reader failed" "$out" | head -n 1 | tee -a "$summary_file" || true
  fi
done
adb logcat -d -v threadtime >> "$logcat_file" 2>/dev/null || true
grep -E "MediaProvider|MediaUpgradeReceiver|has no access|ExternalReadProbe" \
  "$logcat_file" | tail -n 400 > "attachment-public-downloads-api${api}-logcat-focus.txt" || true
{
  # Boot/reboot failures are reported separately and never count as experiment samples.
  samples="$(grep '^iteration=' "$summary_file" | grep -v ' boot=' || true)"
  echo "boot_failures=$(grep -c ' boot=' "$summary_file")"
  echo "control_failures=$(grep -c 'wait=false .*rc=[1-9]' <<<"$samples") of $(grep -c 'wait=false' <<<"$samples")"
  echo "wait_failures=$(grep -c 'wait=true .*rc=[1-9]' <<<"$samples") of $(grep -c 'wait=true' <<<"$samples")"
} | tee -a "$summary_file"
exit "$overall"
