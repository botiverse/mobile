#!/usr/bin/env bash
# Runs an instrumentation command, captures its output to a file, and asserts
# success via assert-instrumentation-ok.sh — retrying ONLY on an infrastructure
# abort (assert exit 2, e.g. emulator/ADB/system death), never on a real test
# failure (assert exit 1), which must fail fast so a regression is not masked.
#
# This is the connected-lane de-flake lever from the emulator flaky audit
# (notes/emulator-flaky-audit-2026-07-30.md): a lone emulator-system crash used
# to red the job identically to a genuine FAILURES!!! and, in a blocking merge
# queue, stall it for everyone. Bounded-retrying infra aborts (and only those)
# keeps the emulator lane admissible to merge_group required once de-flaked.
#
# Usage:
#   run-instrumentation-with-retry.sh <output-file> <min-tests> -- <command...>
# Env:
#   INSTRUMENTATION_MAX_ATTEMPTS  total attempts including the first (default 3)
set -uo pipefail

file="${1:?usage: run-instrumentation-with-retry.sh <output-file> <min-tests> -- <command...>}"
min="${2:?usage: run-instrumentation-with-retry.sh <output-file> <min-tests> -- <command...>}"
shift 2
if [[ "${1:-}" == "--" ]]; then
  shift
fi
if [[ $# -eq 0 ]]; then
  echo "run-instrumentation-with-retry.sh: missing command after --" >&2
  exit 1
fi

here="$(cd "$(dirname "$0")" && pwd)"
attempts="${INSTRUMENTATION_MAX_ATTEMPTS:-3}"
if ! [[ "$attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "INSTRUMENTATION_MAX_ATTEMPTS must be a positive integer, got: $attempts" >&2
  exit 1
fi

for ((attempt = 1; attempt <= attempts; attempt++)); do
  # Re-run the instrumentation command; tee keeps live output while capturing
  # the file the assertion classifies. The command's own exit code is not the
  # verdict (am instrument often exits 0 with failures in the body) — the
  # captured output is, via assert-instrumentation-ok.sh.
  "$@" 2>&1 | tee "$file"

  # Capture the assertion's exit code directly. Do NOT wrap the call in `if`:
  # after `if cmd; then ...; fi` with a false condition and no else, `$?` is the
  # `if` statement's status (0), not cmd's — which would mask failures/aborts.
  "$here/assert-instrumentation-ok.sh" "$file" "$min"
  rc=$?
  if [[ "$rc" -eq 0 ]]; then
    exit 0
  fi
  if [[ "$rc" -ne 2 ]]; then
    # Real failure (exit 1) or bad usage — fail fast, never retry.
    exit "$rc"
  fi
  echo "::warning::infra-abort on attempt ${attempt}/${attempts} for ${file}; retrying" >&2
done

echo "run-instrumentation-with-retry.sh: infra-abort persisted after ${attempts} attempts for ${file}" >&2
exit 1
