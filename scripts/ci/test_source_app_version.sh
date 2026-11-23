#!/usr/bin/env bash
# Self-test for scripts/ci/source_app_version.sh (temp dirs only, no network).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SV="$ROOT/ci/source_app_version.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

pass=0; fail=0
expect() {
  local desc="$1" got="$2" want="$3"
  if [ "$got" = "$want" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $desc (got=$got want=$want)" >&2; fi
}
fails() {
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then fail=$((fail+1)); echo "FAIL: $desc (expected a failure)" >&2; else pass=$((pass+1)); fi
}

# Numeric, not lexical, order: 1.10.0 > 1.9.1 and 1.29.0 > 1.28.0 (lexical sort would pick 1.9.1).
mkdir -p "$tmp/main/change-logs/"{1.9.1,1.10.0,1.28.0,1.29.0/changesets}
expect "main heads to the next release" "$("$SV" "$tmp/main")" "1.29.0"

# A release branch has no newer directory than its own version.
mkdir -p "$tmp/release/change-logs/"{1.26.0,1.27.0,1.28.0}
expect "release branch is its own version" "$("$SV" "$tmp/release")" "1.28.0"

# Files and non-version directories are ignored.
mkdir -p "$tmp/noise/change-logs/"{1.2.3,2.0.0-rc1,templates}
touch "$tmp/noise/change-logs/9.9.9" "$tmp/noise/change-logs/README.md"
expect "ignores files and non-X.Y.Z names" "$("$SV" "$tmp/noise")" "1.2.3"

# Major/minor/patch each compare numerically.
mkdir -p "$tmp/order/change-logs/"{1.99.0,2.0.0,1.100.1}
expect "major wins over minor" "$("$SV" "$tmp/order")" "2.0.0"

mkdir -p "$tmp/empty/change-logs"
fails "no version directory" "$SV" "$tmp/empty"
fails "no change-logs directory" "$SV" "$tmp/missing"

# This repository itself resolves to an X.Y.Z.
real=$("$SV" "$ROOT/..")
if [[ "$real" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: repo version '$real'" >&2; fi

echo "source_app_version tests: $pass pass / $fail fail"
[ "$fail" -eq 0 ]
