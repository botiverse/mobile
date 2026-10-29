#!/usr/bin/env bash
# Self-test for scripts/lib/hands_retry.sh against a stub Hands CLI.
#
# Each case asserts the exit code, how many times the stub ran, and what reached
# stdout, so a wrapper that retried a 4xx or printed the release line twice fails.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/hands_retry.sh
. "${SCRIPT_DIR}/../lib/hands_retry.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export HANDS_RETRY_DELAYS="0 0 0"

failures=0
fail() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# stub_cli <fail count> <failure message> <failure exit>: fails the first N runs, then
# prints a publish result the way the Hands CLI does.
stub_cli() {
  local fails="$1" message="$2" code="$3" n
  n=$(($(cat "${WORK}/count" 2>/dev/null || echo 0) + 1))
  echo "${n}" >"${WORK}/count"
  if [ "${n}" -le "${fails}" ]; then
    echo "Uploading APK (attempt-local progress)"
    echo "${message}" >&2
    return "${code}"
  fi
  echo "build_id=build-${n}"
  echo "  release: rel-${n}"
}

# run_case <name> <want exit> <want runs> <fails> <message> <exit>
run_case() {
  local name="$1" want="$2" want_runs="$3" got=0
  rm -f "${WORK}/count"
  hands_retry stub_cli "$4" "$5" "$6" >"${WORK}/${name}.out" 2>"${WORK}/${name}.err" || got=$?
  [ "${got}" = "${want}" ] || fail "${name}: expected exit ${want}, got ${got}"
  [ "$(cat "${WORK}/count")" = "${want_runs}" ] || fail "${name}: expected ${want_runs} runs, got $(cat "${WORK}/count")"
}

run_case transient-503 0 3 2 "Error: 503 Service Unavailable" 1
[ "$(cat "${WORK}/transient-503.out")" = "$(printf 'build_id=build-3\n  release: rel-3')" ] ||
  fail "transient-503: stdout is not exactly the final attempt's output: $(cat "${WORK}/transient-503.out")"
grep -q 'attempt 1/3 failed (transient, exit 1): Error: 503 Service Unavailable' "${WORK}/transient-503.err" ||
  fail "transient-503: attempt 1 not logged"
grep -q 'attempt 2/3 failed (transient' "${WORK}/transient-503.err" || fail "transient-503: attempt 2 not logged"
grep -q 'Uploading APK' "${WORK}/transient-503.err" || fail "transient-503: failed attempt stdout not sent to stderr"

run_case network 0 2 1 "TypeError: fetch failed (ECONNRESET)" 1
run_case gateway 0 2 1 "Error: 504 Gateway Timeout" 1

run_case client-400 1 1 5 "Error: 400 Bad Request: version_code already exists" 1
[ ! -s "${WORK}/client-400.out" ] || fail "client-400: failed output leaked to stdout"
grep -q '400 Bad Request' "${WORK}/client-400.err" || fail "client-400: CLI error text not shown"
grep -q 'transient' "${WORK}/client-400.err" && fail "client-400: a 4xx was treated as transient"

run_case guard-exit 64 1 5 "error: required exact native symbols are missing" 64

run_case exhausted 7 3 5 "Error: 503 Service Unavailable" 7
grep -q 'attempt 3/3 failed (exit 7); giving up' "${WORK}/exhausted.err" || fail "exhausted: final attempt not logged"

run_case asset-unavailable 1 1 5 'Error: 503 Service Unavailable {"code":"ASSET_UPLOAD_UNAVAILABLE"}' 1
grep -q 'transient' "${WORK}/asset-unavailable.err" && fail "asset-unavailable: config error was treated as transient"

probe_verbose() { echo "verbose=${HANDS_VERBOSE:-unset}"; }
[ "$(hands_retry probe_verbose 2>/dev/null)" = "verbose=1" ] || fail "HANDS_VERBOSE is not on by default"
[ "$(HANDS_VERBOSE=0 hands_retry probe_verbose 2>/dev/null)" = "verbose=0" ] || fail "an explicit HANDS_VERBOSE is overridden"
[ "${HANDS_VERBOSE:-unset}" = "unset" ] || fail "HANDS_VERBOSE leaked out of hands_retry"

if [ "${failures}" -ne 0 ]; then
  echo "hands_retry: ${failures} failure(s)" >&2
  exit 1
fi
echo "hands_retry: PASS"
