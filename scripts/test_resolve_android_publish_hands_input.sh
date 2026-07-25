#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOLVER="${ROOT_DIR}/scripts/resolve_android_publish_hands_input.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_value() {
  local expected="$1"
  local canonical="$2"
  local legacy="$3"
  local default_value="$4"
  local actual

  actual="$("${RESOLVER}" "${canonical}" "${legacy}" "${default_value}" 2>/dev/null)"
  [ "${actual}" = "${expected}" ] ||
    fail "expected ${expected}, got ${actual} for canonical='${canonical}' legacy='${legacy}' default='${default_value}'"
}

assert_failure() {
  if "${RESOLVER}" "$@" >/dev/null 2>&1; then
    fail "expected resolver failure for: $*"
  fi
}

assert_value false "" "" false
assert_value true "" "" true
assert_value true true "" false
assert_value false false "" true
assert_value true "" true false
assert_value false "" false true
assert_value true true true false
assert_value false false false true
assert_value true " TRUE " "" false
assert_value false "" 0 true

assert_failure true false false
assert_failure false true false
assert_failure maybe "" false
assert_failure "" invalid false
assert_failure "" "" ""

echo "publish_hands input resolver contract: PASS"
