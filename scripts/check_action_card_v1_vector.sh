#!/usr/bin/env bash
# Verifies the vendored action-card.v1 vector (first-party action cards described
# as data, `presentation.card`) against its pinned digest.
#
# Provenance: byte-exact copy of github.com/botiverse/slock
#   @ WEB_HEAD : WEB_PATH (and its .sha256 sibling), below.
# Content at a pinned commit is immutable, so the check is fully local — no
# network fetch or cross-repo token. To update the vector: fetch the new Web
# files, vendored-copy both, and bump WEB_HEAD + EXPECTED_SHA256 together.
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="05e3873718324869e0b687493a0ad385c8b59542"
readonly WEB_PATH="packages/shared/src/actionCard.v1.vector.json"
readonly EXPECTED_SHA256="df99645e2c3612d83aa4615bdbb345cc1d8f04f82da16affdf6e74389710a0e8"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/actioncard/actionCard.v1.vector.json"
readonly VENDORED_DIGEST_PATH="compose/shared/src/androidUnitTest/resources/actioncard/actionCard.v1.vector.sha256"

for path in "$VENDORED_PATH" "$VENDORED_DIGEST_PATH"; do
  if [[ ! -f "$path" ]]; then
    echo "Missing vendored action-card.v1 vector file: $path" >&2
    exit 1
  fi
done

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$VENDORED_PATH" | awk '{print $1}')"
else
  actual_sha256="$(shasum -a 256 "$VENDORED_PATH" | awk '{print $1}')"
fi

if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored action-card.v1 vector drifted from ${WEB_REPOSITORY}@${WEB_HEAD}:${WEB_PATH}" >&2
  echo "expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

# The Web side pins the same digest in its own .sha256 file; both copies must agree.
web_digest="$(awk '{print $1}' "$VENDORED_DIGEST_PATH")"
if [[ "$web_digest" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored ${VENDORED_DIGEST_PATH} ($web_digest) disagrees with EXPECTED_SHA256 ($EXPECTED_SHA256)" >&2
  exit 1
fi

echo "action-card.v1 vector verified: $EXPECTED_SHA256"
