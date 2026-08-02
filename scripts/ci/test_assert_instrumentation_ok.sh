#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$ROOT/scripts/ci/assert-instrumentation-ok.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  local description="$1"
  shift
  if "$@" >"$TMP_ROOT/negative.out" 2>"$TMP_ROOT/negative.err"; then
    fail "$description unexpectedly succeeded"
  fi
}

# GREEN: exact minimum.
printf 'INSTRUMENTATION_STATUS_CODE: 0\n.\nTime: 1.23\n\nOK (6 tests)\n' > "$TMP_ROOT/ok6.txt"
"$GUARD" "$TMP_ROOT/ok6.txt" 6 | grep -q 'Instrumentation OK: 6 tests'

# GREEN: growth beyond the minimum stays green (the old grep gate broke here).
"$GUARD" "$TMP_ROOT/ok6.txt" 2 | grep -q '>= 2'

# GREEN: singular form.
printf 'OK (1 test)\n' > "$TMP_ROOT/ok1.txt"
"$GUARD" "$TMP_ROOT/ok1.txt" 1 >/dev/null

# GREEN: appended noise before the summary (tee -a call sites).
printf 'instrumentation:pkg/androidx.test.runner.AndroidJUnitRunner\nOK (2 tests)\n' > "$TMP_ROOT/ok2-appended.txt"
"$GUARD" "$TMP_ROOT/ok2-appended.txt" 2 >/dev/null

# RED: fewer tests than the minimum.
expect_failure "count below minimum" "$GUARD" "$TMP_ROOT/ok1.txt" 2
grep -q 'expected at least 2' "$TMP_ROOT/negative.err"

# RED: JUnit failure summary.
printf 'Tests run: 6,  Failures: 1\nFAILURES!!!\n' > "$TMP_ROOT/failed.txt"
expect_failure "FAILURES output" "$GUARD" "$TMP_ROOT/failed.txt" 1
grep -q 'failure markers' "$TMP_ROOT/negative.err"

# RED: crashed process.
printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n' > "$TMP_ROOT/crash.txt"
expect_failure "process crash" "$GUARD" "$TMP_ROOT/crash.txt" 1

# RED: zero-test / skipped run.
printf 'OK (0 tests)\n' > "$TMP_ROOT/zero.txt"
expect_failure "zero tests" "$GUARD" "$TMP_ROOT/zero.txt" 1

# RETRYABLE: an offline device is infrastructure failure, not a real-test failure.
printf 'adb: device offline\n' > "$TMP_ROOT/nosummary.txt"
set +e
"$GUARD" "$TMP_ROOT/nosummary.txt" 1 >"$TMP_ROOT/negative.out" 2>"$TMP_ROOT/negative.err"
offline_rc=$?
set -e
test "$offline_rc" -eq 2 || fail "offline device returned $offline_rc instead of retryable exit 2"
grep -q 'Instrumentation aborted by infrastructure (retryable)' "$TMP_ROOT/negative.err"

# RED: missing file.
expect_failure "missing file" "$GUARD" "$TMP_ROOT/does-not-exist.txt" 1

# RED: malformed minimum.
expect_failure "malformed min" "$GUARD" "$TMP_ROOT/ok6.txt" six

echo "assert-instrumentation-ok self-test passed"
