#!/usr/bin/env bash
# Verifies the vendored Web canonical-message V2 reaction vector against its
# pinned digest.
#
# Provenance: byte-exact copy of github.com/botiverse/slock
#   @ WEB_HEAD : WEB_PATH (below).
# Content at a pinned commit is immutable, so the check is fully local — no
# network fetch or cross-repo token. To update the vector: fetch the new Web
# file, vendored-copy it, and bump WEB_HEAD + EXPECTED_SHA256 together.
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="704d69f4379f4f87ea14212c3ebf03b1cf6aa55d"
readonly WEB_PATH="packages/shared/src/canonicalMessageV2.vector.json"
readonly EXPECTED_SHA256="905812d366f8a700753596c01c678262f0aa5b5eb158859e9b791fb3c3a7fa40"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/sync/canonical-message-v2-reaction.vector.json"

if [[ ! -f "$VENDORED_PATH" ]]; then
  echo "Missing vendored canonical-message V2 vector: $VENDORED_PATH" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$VENDORED_PATH" | awk '{print $1}')"
else
  actual_sha256="$(shasum -a 256 "$VENDORED_PATH" | awk '{print $1}')"
fi

if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored canonical-message V2 vector drifted from ${WEB_REPOSITORY}@${WEB_HEAD}:${WEB_PATH}" >&2
  echo "expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

echo "Canonical-message V2 reaction vector verified: $EXPECTED_SHA256"
