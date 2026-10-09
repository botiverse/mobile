#!/usr/bin/env bash
# Blocks until MediaProvider has logged a completed scan of external_primary in the current boot,
# or fails.
#
# The only accepted success signal is MediaProvider's own completion log line
# "Scanned external_primary ..." (boot scan with REASON_MOUNTED, or the demanded scan triggered
# below). The `content call ... scan_volume` result is NOT a signal: `content` exits 0 when the
# provider throws, and Android 12 MediaProvider swallows a missing-volume error ("Failed to find
# volume") and returns the same empty Bundle as a real scan. The call only triggers a scan; its
# output is reported for diagnosis.
#
# Boot fence: the emulator's logcat is in-memory and starts empty at every boot, so a line found
# in `logcat -d` belongs to the current boot. Callers reboot before re-using this in a new boot.
#
# Every adb call has its own deadline, and the whole wait has an overall deadline.
# Env (seconds): MEDIA_READY_TIMEOUT (default 120), MEDIA_READY_CALL_TIMEOUT (default 60),
#                MEDIA_READY_POLL_INTERVAL (default 1)
# Prints one line: media_ready=<logcat|timeout> seconds=<n> scan_call_rc=<rc> scan_call="<output>"
set -uo pipefail

overall="${MEDIA_READY_TIMEOUT:-120}"
call_timeout="${MEDIA_READY_CALL_TIMEOUT:-60}"
poll="${MEDIA_READY_POLL_INTERVAL:-1}"
started=$SECONDS
deadline=$((SECONDS + overall))
signal="Scanned external_primary"

remaining() {
  local left=$((deadline - SECONDS))
  [ "$left" -lt 1 ] && left=1
  [ "$left" -gt "$1" ] && left="$1"
  echo "$left"
}

scanned_logged() {
  timeout "$(remaining 15)" adb logcat -d -s MediaProvider 2>/dev/null | grep -q "$signal"
}

call_rc=skipped
call_line=""
if ! scanned_logged; then
  call_out="$(timeout "$(remaining "$call_timeout")" adb shell content call --uri content://media \
    --method scan_volume --arg external_primary 2>&1)"
  call_rc=$?
  call_line="$(printf '%s' "$call_out" | tr -d '\r' | tr '\n' ' ' | cut -c1-200)"
fi

while true; do
  if scanned_logged; then
    echo "media_ready=logcat seconds=$((SECONDS - started)) scan_call_rc=$call_rc scan_call=\"$call_line\""
    exit 0
  fi
  [ "$SECONDS" -ge "$deadline" ] && break
  sleep "$poll"
done
echo "media_ready=timeout seconds=$((SECONDS - started)) scan_call_rc=$call_rc scan_call=\"$call_line\""
exit 1
