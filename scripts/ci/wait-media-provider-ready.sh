#!/usr/bin/env bash
# Blocks until MediaProvider reports a completed scan of external_primary, or fails.
#
# Success requires an explicit signal; anything else is a failure:
#   1. `content call ... scan_volume` must print a `Result:` line and no exception/error text.
#      (`content` exits 0 even when the provider throws, so the exit code alone is not a signal.)
#   2. Otherwise poll logcat for MediaProvider's "Scanned external_primary" line.
# Every adb call has its own deadline, and the whole wait has an overall deadline.
#
# Env (seconds): MEDIA_READY_TIMEOUT (default 120), MEDIA_READY_CALL_TIMEOUT (default 60),
#                MEDIA_READY_POLL_INTERVAL (default 1)
# Prints one line: media_ready=<scan_volume|logcat|timeout> seconds=<n> [detail=...]
set -uo pipefail

overall="${MEDIA_READY_TIMEOUT:-120}"
call_timeout="${MEDIA_READY_CALL_TIMEOUT:-60}"
poll="${MEDIA_READY_POLL_INTERVAL:-1}"
started=$SECONDS
deadline=$((SECONDS + overall))

remaining() {
  local left=$((deadline - SECONDS))
  [ "$left" -lt 1 ] && left=1
  [ "$left" -gt "$1" ] && left="$1"
  echo "$left"
}

call_out="$(timeout "$(remaining "$call_timeout")" adb shell content call --uri content://media \
  --method scan_volume --arg external_primary 2>&1)"
call_rc=$?
call_line="$(printf '%s' "$call_out" | tr -d '\r' | tr '\n' ' ' | cut -c1-200)"
if [ "$call_rc" -eq 0 ] &&
  printf '%s\n' "$call_out" | grep -q '^Result:' &&
  ! printf '%s\n' "$call_out" | grep -qiE 'exception|error|denied|not found'; then
  echo "media_ready=scan_volume seconds=$((SECONDS - started))"
  exit 0
fi

while [ "$SECONDS" -lt "$deadline" ]; do
  if timeout "$(remaining 15)" adb logcat -d -s MediaProvider 2>/dev/null |
    grep -q "Scanned external_primary"; then
    echo "media_ready=logcat seconds=$((SECONDS - started)) scan_call_rc=$call_rc scan_call=\"$call_line\""
    exit 0
  fi
  sleep "$poll"
done
echo "media_ready=timeout seconds=$((SECONDS - started)) scan_call_rc=$call_rc scan_call=\"$call_line\""
exit 1
