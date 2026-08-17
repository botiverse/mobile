#!/usr/bin/env bash
# Verifies the byte-exact Phase 1 server fixture vendored for Mobile parser tests.
set -euo pipefail

readonly UPSTREAM_REPOSITORY="botiverse/slock"
readonly UPSTREAM_HEAD="803e8c8e89398c014e3664fd3e3c140bd982aba3"
readonly UPSTREAM_PATH="packages/server/test-contracts/unread-summary-activity.v1.json"
readonly EXPECTED_SHA256="abfaacbe2c548a16a72060d00fb204ec45c39d1d4c1406c73a8cdbe7a6fa7e01"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/sync/unread-summary-activity.v1.json"

if [[ ! -f "$VENDORED_PATH" ]]; then
  echo "Missing vendored server Activity-unread fixture: $VENDORED_PATH" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$VENDORED_PATH" | awk '{print $1}')"
else
  actual_sha256="$(shasum -a 256 "$VENDORED_PATH" | awk '{print $1}')"
fi

if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored Activity-unread fixture drifted from ${UPSTREAM_REPOSITORY}@${UPSTREAM_HEAD}:${UPSTREAM_PATH}" >&2
  echo "expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

echo "Server Activity-unread fixture verified: $EXPECTED_SHA256"
