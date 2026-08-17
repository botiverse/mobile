#!/usr/bin/env bash
# Verifies the byte-exact Phase 1 server fixture vendored for Mobile parser tests.
set -euo pipefail

readonly UPSTREAM_REPOSITORY="botiverse/slock"
readonly UPSTREAM_HEAD="7b858a79178f78870b1c96d7377656c6e32c0136"
readonly UPSTREAM_PATH="packages/server/test-contracts/unread-summary-activity.v1.json"
readonly EXPECTED_SHA256="22bc4d85498aa2e476fa4424956746c17fa8bdf31763b7471eaab52c43e8c0ef"
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
