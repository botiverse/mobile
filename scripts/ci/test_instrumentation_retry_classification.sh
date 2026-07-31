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

# A real failure that co-occurs with an abort marker stays fatal (never masked).
printf 'FAILURES!!!\nINSTRUMENTATION_ABORTED: System has crashed\n' > "$tmp/both.txt"
"$assert" "$tmp/both.txt" 1; check "FAILURES!!! + abort -> 1 (real wins)" 1 $?

printf 'OK (0 tests)\n' > "$tmp/zero.txt"
"$assert" "$tmp/zero.txt" 1; check "zero-test -> 1 (fatal)" 1 $?

printf 'no summary here\n' > "$tmp/none.txt"
"$assert" "$tmp/none.txt" 1; check "no OK summary -> 1 (fatal)" 1 $?

# --- retry wrapper ----------------------------------------------------------
counter="$tmp/counter"

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

if [[ "$fail" -ne 0 ]]; then
  echo "instrumentation retry/classification test FAILED" >&2
  exit 1
fi
echo "instrumentation retry/classification test PASSED"
