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
#   INSTRUMENTATION_MAX_ATTEMPTS         total attempts incl. the first (default 3)
#   INSTRUMENTATION_RETRY_BACKOFF_SECONDS  sleep before each retry (default 10),
#                                          letting a crashed emulator/ADB recover
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
backoff="${INSTRUMENTATION_RETRY_BACKOFF_SECONDS:-10}"
if ! [[ "$attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "INSTRUMENTATION_MAX_ATTEMPTS must be a positive integer, got: $attempts" >&2
  exit 1
fi
if ! [[ "$backoff" =~ ^[0-9]+$ ]]; then
  echo "INSTRUMENTATION_RETRY_BACKOFF_SECONDS must be a non-negative integer, got: $backoff" >&2
  exit 1
fi

for ((attempt = 1; attempt <= attempts; attempt++)); do
  # Run the instrumentation command; tee keeps live output while capturing the
  # file the assertion classifies. Capture the command's OWN exit via PIPESTATUS
  # (tee's exit would otherwise mask it): am instrument commonly exits 0 with a
  # failure in the body, but a non-zero command exit alongside an OK-looking body
  # (e.g. a crash after the summary printed, or a dropped adb connection) must
  # NOT be reported green.
  "$@" 2>&1 | tee "$file"
  cmd_rc="${PIPESTATUS[0]}"

  # Classify the captured output. Do NOT wrap in `if`: after `if cmd; then...; fi`
  # with a false condition and no else, `$?` is the `if` status (0), not cmd's.
  "$here/assert-instrumentation-ok.sh" "$file" "$min"
  assert_rc=$?

  if [[ "$assert_rc" -eq 2 ]]; then
    : # infra-abort (recognized markers) — retryable; fall through to backoff
  elif [[ "$assert_rc" -eq 0 && "$cmd_rc" -eq 0 ]]; then
    exit 0
  elif [[ "$assert_rc" -eq 0 && "$cmd_rc" -ne 0 ]]; then
    # OK-looking body but the command itself failed and no infra marker matched:
    # a masked crash. Fail fast — never report this green, never retry blindly.
    echo "run-instrumentation-with-retry.sh: command exited ${cmd_rc} despite an OK summary in ${file} (masked crash) — failing" >&2
    exit 1
  else
    # assert_rc == 1: real test failure — fail fast (retrying masks a regression).
    exit "$assert_rc"
  fi

  # Preserve this attempt's evidence before it is overwritten, so retries remain
  # auditable (which attempts aborted, how many) instead of clobbering the file.
  cp -f "$file" "${file}.attempt-${attempt}" 2>/dev/null || true
  echo "::warning::infra-abort on attempt ${attempt}/${attempts} for ${file} (cmd_rc=${cmd_rc}); evidence at ${file}.attempt-${attempt}" >&2
  if [[ "$attempt" -lt "$attempts" && "$backoff" -gt 0 ]]; then
    sleep "$backoff"
  fi
done

echo "run-instrumentation-with-retry.sh: infra-abort persisted after ${attempts} attempts for ${file}" >&2
exit 1
