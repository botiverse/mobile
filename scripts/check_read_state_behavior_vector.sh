#!/usr/bin/env bash
# Verifies the vendored Web read-state authority vector against its pinned digest.
#
# Provenance: byte-exact copy of github.com/botiverse/slock
#   @ WEB_HEAD : WEB_PATH (below).
# Content at a pinned commit is immutable, so the check is fully local — no
# network fetch or cross-repo token. To update the vector: fetch the new Web
# file, vendored-copy it, and bump WEB_HEAD + EXPECTED_SHA256 together.
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="22b14466900d22f25c58d2d4d24dff4f2912ae75"
readonly WEB_PATH="packages/web/tests/fixtures/readStateBehavior.v1.json"
readonly EXPECTED_SHA256="cbdf80669422af3dfdf62742180668ad952daabd4ac41336f8ba2ef49980befc"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/sync/read-state-behavior-v1.json"

if [[ ! -f "$VENDORED_PATH" ]]; then
  echo "Missing vendored read-state vector: $VENDORED_PATH" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$VENDORED_PATH" | awk '{print $1}')"
else
  actual_sha256="$(shasum -a 256 "$VENDORED_PATH" | awk '{print $1}')"
fi

if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored read-state vector drifted from ${WEB_REPOSITORY}@${WEB_HEAD}:${WEB_PATH}" >&2
  echo "expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

echo "Read-state authority vector verified: $EXPECTED_SHA256"
