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

# Hard failure markers from the JUnit runner / instrumentation process.
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$file"; then
  echo "Instrumentation reported failure markers in $file:" >&2
  grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|Tests run:' "$file" >&2 || true
  exit 1
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
