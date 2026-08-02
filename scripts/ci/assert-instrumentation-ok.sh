#!/usr/bin/env bash
# Asserts a captured `am instrument -w [-r]` output reports a fully successful
# run with at least MIN_TESTS tests executed.
#
# Replaces hardcoded `grep -Fq 'OK (N tests)'` gates: those broke with an
# unrelated-looking "grep found nothing" whenever a test was added to or
# removed from the instrumented class. A minimum keeps the intent (the class
# actually ran, nothing silently skipped to zero) while tolerating growth.
#
# Usage: assert-instrumentation-ok.sh <output-file> <min-tests>
# Exit codes:
#   0  success (>= min-tests ran, no failure/abort markers)
#   1  fatal   (real test failure, skipped/zero-test run, missing OK summary,
#               bad usage) — the caller MUST NOT retry
#   2  infra-abort (emulator/ADB/system death) — retryable by the caller
set -euo pipefail

file="${1:?usage: assert-instrumentation-ok.sh <output-file> <min-tests>}"
min="${2:?usage: assert-instrumentation-ok.sh <output-file> <min-tests>}"

if [[ ! -f "$file" ]]; then
  echo "Missing instrumentation output file: $file" >&2
  exit 1
fi
if ! [[ "$min" =~ ^[0-9]+$ ]]; then
  echo "min-tests must be a non-negative integer, got: $min" >&2
  exit 1
fi

# Real test-failure markers from the JUnit runner / instrumented app process.
# Checked FIRST and always fatal: a genuine regression must fail fast — retrying
# it would mask the failure. (A real failure that co-occurs with an infra abort
# marker below is still classified fatal here, never retryable.)
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$file"; then
  echo "Instrumentation reported test-failure markers in $file:" >&2
  grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Tests run:' "$file" >&2 || true
  exit 1
fi

# Infrastructure-abort markers (emulator/ADB/system death, not a test verdict):
# the run was interrupted before completing, so the caller may bounded-retry it.
# Distinct exit code 2 lets run-instrumentation-with-retry.sh retry ONLY these
# (never a real failure above). See notes/emulator-flaky-audit-2026-07-30.md.
if grep -Eq 'INSTRUMENTATION_ABORTED|System has crashed|Broken pipe|device ([^ ]+ )?(offline|not found)|no devices/emulators found|emulator: ERROR|[Bb]oot.*(timed out|timeout)|adb.*(offline|not responding|no such device)' "$file"; then
  echo "Instrumentation aborted by infrastructure (retryable) in $file:" >&2
  grep -E 'INSTRUMENTATION_ABORTED|System has crashed|Broken pipe|device ([^ ]+ )?(offline|not found)|no devices/emulators found|emulator: ERROR|[Bb]oot.*(timed out|timeout)|adb.*(offline|not responding|no such device)' "$file" >&2 || true
  exit 2
fi

# A skipped or assumption-failed run must not pass as green.
if grep -Eiq '(^|[^0-9])0 tests|skipped|ignored|assumption.*fail' "$file"; then
  echo "Instrumentation reported a skipped or zero-test run in $file" >&2
  exit 1
fi

ok_count="$(grep -Eo 'OK \([0-9]+ tests?\)' "$file" | grep -Eo '[0-9]+' | tail -n 1 || true)"
if [[ -z "$ok_count" ]]; then
  echo "No 'OK (N tests)' summary found in $file" >&2
  exit 1
fi
if (( ok_count < min )); then
  echo "Instrumentation ran $ok_count tests, expected at least $min ($file)" >&2
  exit 1
fi

echo "Instrumentation OK: $ok_count tests (>= $min) in $file"
