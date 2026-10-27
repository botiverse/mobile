#!/usr/bin/env bash
# Self-test for .github/actions/gradle-cache-policy/apply.sh (task #786).
# Exercises the fail-closed policy parser against good and bad fixtures.
set -euo pipefail

apply="$(cd "$(dirname "$0")/../.." && pwd)/.github/actions/gradle-cache-policy/apply.sh"
test -f "$apply"

run_case() {
  local name="$1" expect="$2" epoch="$3" quarantine="$4"
  local dir env_file status output
  dir="$(mktemp -d)"
  mkdir -p "$dir/.github"
  [[ "$epoch" == "ABSENT" ]] || printf '%b' "$epoch" > "$dir/.github/gradle-cache-epoch"
  [[ "$quarantine" == "ABSENT" ]] || printf '%b' "$quarantine" > "$dir/.github/gradle-cache-quarantine"
  env_file="$dir/github_env"
  : > "$env_file"
  status=0
  output="$(GITHUB_WORKSPACE="$dir" GITHUB_ENV="$env_file" bash "$apply" 2>&1)" || status=$?
  if [[ "$expect" == "pass" && "$status" -ne 0 ]]; then
    echo "FAIL $name: expected pass, got status $status: $output" >&2; exit 1
  fi
  if [[ "$expect" == "fail" && "$status" -eq 0 ]]; then
    echo "FAIL $name: expected failure, parser accepted input" >&2; exit 1
  fi
  LAST_ENV_FILE="$env_file"
  echo "ok $name"
}

# Happy paths.
run_case single-epoch-no-quarantine pass '7\n' '# only comments\n\n'
grep -q '^GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=7-$' "$LAST_ENV_FILE"
grep -q '^RAFT_GRADLE_BUILD_CACHE_EPOCH=7$' "$LAST_ENV_FILE"
! grep -q 'SKIP_RESTORE' "$LAST_ENV_FILE"

run_case epoch-token-chars pass 'epoch_2026-07.24\n' '# none\n'
grep -q '^GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=epoch_2026-07.24-$' "$LAST_ENV_FILE"
grep -q '^RAFT_GRADLE_BUILD_CACHE_EPOCH=epoch_2026-07.24$' "$LAST_ENV_FILE"

run_case quarantine-both-bundles pass '3\n' '# incident\ndependencies\ntransforms\n'
grep -q '^GRADLE_BUILD_ACTION_SKIP_RESTORE=dependencies,transforms$' "$LAST_ENV_FILE"

# Byte-contract paths (shared with the Python checker): final LF optional, CR rejected.
run_case epoch-no-final-lf pass '7' '# only comments\n'
grep -q '^GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=7-$' "$LAST_ENV_FILE"

run_case quarantine-trailing-entry-no-final-lf pass '3\n' '# incident\ndependencies'
grep -q '^GRADLE_BUILD_ACTION_SKIP_RESTORE=dependencies$' "$LAST_ENV_FILE"

run_case epoch-crlf fail '1\r\n' '\n'
run_case quarantine-crlf fail '1\n' 'dependencies\r\n'
run_case epoch-extra-blank-line fail '1\n\n' '\n'

# Fail-closed paths.
run_case missing-epoch fail ABSENT 'dependencies\n'
run_case missing-quarantine fail '1\n' ABSENT
run_case two-line-epoch fail '1\n2\n' '\n'
run_case epoch-comment fail '# 1\n' '\n'
run_case epoch-bad-char fail 'v1;rm -rf\n' '\n'
run_case epoch-too-long fail 'a123456789a123456789a123456789a12\n' '\n'
run_case bundle-not-allowlisted fail '1\n' 'configuration-cache\n'
run_case bundle-duplicate fail '1\n' 'dependencies\ndependencies\n'
run_case bundle-inline-whitespace fail '1\n' 'dependencies transforms\n'

echo "gradle-cache-policy self-test: all cases passed"
