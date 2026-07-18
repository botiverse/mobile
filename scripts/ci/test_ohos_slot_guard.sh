#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$ROOT/scripts/ci/ohos-slot-guard.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

FAKE_HDC="$ROOT/scripts/ci/testdata/fake-ohos-hdc.sh"

export HDC_REAL="$FAKE_HDC"
export FAKE_HDC_LOG="$TMP_ROOT/hdc.log"
export OHOS_SLOT_LOCK_ROOT="$TMP_ROOT/locks"
export OHOS_EXPECT_HOST_USER="$(id -un)"
export OHOS_SLOT_ID=ohos-emulator-api23-slot-1
export OHOS_SERIAL=127.0.0.1:16001
export OHOS_INSTANCE_UUID=2b3d0abd-a4b0-489e-91e0-165ef3cf9734
export OHOS_EXPECT_MODEL=emulator
export OHOS_EXPECT_API=23
export OHOS_EXPECT_SOFTWARE_CONTAINS=6.1.0.115
export OHOS_SLOT_LOCK_OWNER=test-run:1:probe
export FAKE_MARKER="$OHOS_SLOT_ID|$OHOS_INSTANCE_UUID"

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

"$GUARD" verify | grep -q 'slot=ohos-emulator-api23-slot-1'
grep -q -- '-p -t 127.0.0.1:16001 shell cat' "$FAKE_HDC_LOG"

"$GUARD" acquire | grep -q 'owner=test-run:1:probe'
expect_failure 'double acquire' "$GUARD" acquire

OHOS_SLOT_LOCK_OWNER=other-run:1:probe \
  expect_failure 'foreign release' "$GUARD" release
"$GUARD" release | grep -q 'released slot=ohos-emulator-api23-slot-1'

"$GUARD" hdc shell echo guarded | grep -q 'fake-shell-ok'
tail -1 "$FAKE_HDC_LOG" | grep -q -- '-p -t 127.0.0.1:16001 shell echo guarded'

FAKE_MARKER=ohos-emulator-api23-slot-2\|different \
  expect_failure 'wrong marker' "$GUARD" verify
OHOS_SERIAL=127.0.0.1:5555 \
  expect_failure 'non-dedicated port' "$GUARD" verify
OHOS_SERIAL= \
  expect_failure 'missing serial' "$GUARD" verify
OHOS_EXPECT_HOST_USER=definitely-not-the-current-user \
  expect_failure 'wrong host user' "$GUARD" verify

printf 'PASS: ohos-slot-guard contract tests\n'
