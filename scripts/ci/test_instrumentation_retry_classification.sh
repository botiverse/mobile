#!/usr/bin/env bash
# Regression test for the emulator infra-abort de-flake lever:
#   assert-instrumentation-ok.sh must classify infra-abort (exit 2, retryable)
#   distinctly from a real test failure (exit 1, fatal) and success (exit 0),
#   and run-instrumentation-with-retry.sh must retry ONLY on infra-abort while
#   failing fast on a real failure (retrying a regression would mask it).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0

check() { # <desc> <expected-exit> <actual-exit>
  if [[ "$2" -ne "$3" ]]; then
    echo "FAIL: $1 (expected exit $2, got $3)" >&2
    fail=1
  else
    echo "ok: $1"
  fi
}

assert="$here/assert-instrumentation-ok.sh"
retry="$here/run-instrumentation-with-retry.sh"

# --- classification ---------------------------------------------------------
printf 'OK (3 tests)\n' > "$tmp/ok.txt"
"$assert" "$tmp/ok.txt" 1; check "success -> 0" 0 $?

printf 'Failures:\nFAILURES!!!\nTests run: 3,  Failures: 1\n' > "$tmp/fail.txt"
"$assert" "$tmp/fail.txt" 1; check "FAILURES!!! -> 1 (fatal)" 1 $?

printf 'Process crashed\n' > "$tmp/crash.txt"
"$assert" "$tmp/crash.txt" 1; check "app Process crashed -> 1 (fatal, real)" 1 $?

printf 'INSTRUMENTATION_ABORTED: System has crashed\n' > "$tmp/abort.txt"
"$assert" "$tmp/abort.txt" 1; check "INSTRUMENTATION_ABORTED/System has crashed -> 2 (retryable)" 2 $?

printf 'am broadcast ... failed: Broken pipe\n' > "$tmp/pipe.txt"
"$assert" "$tmp/pipe.txt" 1; check "Broken pipe -> 2 (retryable)" 2 $?

printf 'adb: device offline\n' > "$tmp/offline.txt"
"$assert" "$tmp/offline.txt" 1; check "adb: device offline -> 2 (retryable)" 2 $?

printf 'error: device '\''emulator-5554'\'' not found\n' > "$tmp/notfound.txt"
"$assert" "$tmp/notfound.txt" 1; check "device not found -> 2 (retryable)" 2 $?

# A real failure that co-occurs with an abort marker stays fatal (never masked).
printf 'FAILURES!!!\nINSTRUMENTATION_ABORTED: System has crashed\n' > "$tmp/both.txt"
"$assert" "$tmp/both.txt" 1; check "FAILURES!!! + abort -> 1 (real wins)" 1 $?

printf 'OK (0 tests)\n' > "$tmp/zero.txt"
"$assert" "$tmp/zero.txt" 1; check "zero-test -> 1 (fatal)" 1 $?

printf 'no summary here\n' > "$tmp/none.txt"
"$assert" "$tmp/none.txt" 1; check "no OK summary -> 1 (fatal)" 1 $?

# --- retry wrapper ----------------------------------------------------------
export INSTRUMENTATION_RETRY_BACKOFF_SECONDS=0  # keep the test fast
counter="$tmp/counter"

# masked crash: command exits non-zero but body looks OK -> fatal, never green.
gen_masked_crash() { echo "OK (1 tests)"; exit 7; }
export -f gen_masked_crash
INSTRUMENTATION_MAX_ATTEMPTS=3 "$retry" "$tmp/masked.txt" 1 -- bash -c gen_masked_crash
check "retry: OK-body but cmd exit 7 -> 1 (masked crash, not green)" 1 $?

# infra-abort on the first attempt, then success -> overall 0 after retry.
echo 0 > "$counter"
gen_flaky() {
  local n; n=$(<"$counter"); n=$((n + 1)); echo "$n" > "$counter"
  if [[ "$n" -lt 2 ]]; then
    echo "INSTRUMENTATION_ABORTED: System has crashed"
  else
    echo "OK (1 tests)"
  fi
}
export -f gen_flaky
export counter
INSTRUMENTATION_MAX_ATTEMPTS=3 "$retry" "$tmp/flaky.txt" 1 -- bash -c gen_flaky
check "retry: infra-abort then OK -> 0" 0 $?
check "retry: infra-abort then OK ran twice" 2 "$(<"$counter")"
if [[ -f "$tmp/flaky.txt.attempt-1" ]]; then echo "ok: retry: prior-attempt evidence archived"; else echo "FAIL: retry: prior-attempt evidence archived (missing)" >&2; fail=1; fi

# real failure -> fail fast, exactly one attempt (no retry).
echo 0 > "$counter"
gen_realfail() {
  local n; n=$(<"$counter"); n=$((n + 1)); echo "$n" > "$counter"
  echo "FAILURES!!!"
}
export -f gen_realfail
INSTRUMENTATION_MAX_ATTEMPTS=3 "$retry" "$tmp/rf.txt" 1 -- bash -c gen_realfail
check "retry: real failure -> 1 (fatal)" 1 $?
check "retry: real failure ran once (no retry)" 1 "$(<"$counter")"

# persistent infra-abort -> exhausts attempts and fails (not masked green).
echo 0 > "$counter"
gen_always_abort() {
  local n; n=$(<"$counter"); n=$((n + 1)); echo "$n" > "$counter"
  echo "INSTRUMENTATION_ABORTED: System has crashed"
}
export -f gen_always_abort
INSTRUMENTATION_MAX_ATTEMPTS=2 "$retry" "$tmp/persist.txt" 1 -- bash -c gen_always_abort
check "retry: persistent infra-abort -> 1 (not masked)" 1 $?
check "retry: persistent infra-abort exhausted attempts" 2 "$(<"$counter")"

# app killed by the system because a provider process died -> retryable;
# the same "Process crashed" without that logcat evidence stays fatal.
echo 0 > "$counter"
gen_dying_provider() {
  local n; n=$(<"$counter"); n=$((n + 1)); echo "$n" > "$counter"
  if [[ "$n" -lt 2 ]]; then
    echo "INSTRUMENTATION_RESULT: shortMsg=Process crashed."
  else
    echo "OK (1 tests)"
  fi
}
export -f gen_dying_provider
printf '%s\n' "10-20 06:47:23.031   519  1100 I ActivityManager: Killing 6012:build.raft.app.connectedtest/u0a192 (adj 0): depends on provider com.google.android.gms/.fonts.provider.FontsProvider in dying proc com.google.android.gms.persistent (adj -10000)" > "$tmp/logcat-dying.txt"
INSTRUMENTATION_LOGCAT_DUMP_CMD="cat $tmp/logcat-dying.txt" \
  INSTRUMENTATION_MAX_ATTEMPTS=3 "$retry" "$tmp/dying.txt" 1 -- bash -c gen_dying_provider
check "retry: app killed via dying provider then OK -> 0" 0 $?
check "retry: app killed via dying provider retried once" 2 "$(<"$counter")"

echo 0 > "$counter"
gen_app_crash() {
  local n; n=$(<"$counter"); n=$((n + 1)); echo "$n" > "$counter"
  echo "INSTRUMENTATION_RESULT: shortMsg=Process crashed."
}
export -f gen_app_crash
printf 'FATAL EXCEPTION: main\nProcess: build.raft.app.connectedtest, PID: 6012\n' > "$tmp/logcat-crash.txt"
INSTRUMENTATION_LOGCAT_DUMP_CMD="cat $tmp/logcat-crash.txt" \
  INSTRUMENTATION_MAX_ATTEMPTS=3 "$retry" "$tmp/appcrash.txt" 1 -- bash -c gen_app_crash
check "retry: real app crash -> 1 (fatal)" 1 $?
check "retry: real app crash ran once (no retry)" 1 "$(<"$counter")"

if [[ "$fail" -ne 0 ]]; then
  echo "instrumentation retry/classification test FAILED" >&2
  exit 1
fi
echo "instrumentation retry/classification test PASSED"
