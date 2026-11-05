#!/usr/bin/env bash
# Verifies the vendored message-card.v1 vector (RFC 082 card messages, v2.0: the card
# header and the Markdown, Fields and Divider elements) against its pinned digest.
#
# Provenance: byte-exact copy of github.com/botiverse/slock
#   @ WEB_HEAD : WEB_PATH (and its .sha256 sibling), below (slock #13224).
# Content at a pinned commit is immutable, so the check is fully local — no
# network fetch or cross-repo token. To update the vector: fetch the new Web
# files, vendored-copy both, and bump WEB_HEAD + EXPECTED_SHA256 together (and
# the digest in MessageCardVectorTest.kt).
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="1fe74e604812110b8a91104943c44e83334b60d5"
readonly WEB_PATH="packages/shared/src/messageCard.v1.vector.json"
readonly EXPECTED_SHA256="ded479113f6850b08996827942e83945964a1a3a38586bef985a5f2d5e67ee71"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/messagecard/messageCard.v1.vector.json"
readonly VENDORED_DIGEST_PATH="compose/shared/src/androidUnitTest/resources/messagecard/messageCard.v1.vector.sha256"

for path in "$VENDORED_PATH" "$VENDORED_DIGEST_PATH"; do
  if [[ ! -f "$path" ]]; then
    echo "Missing vendored message-card.v1 vector file: $path" >&2
    exit 1
  fi
done

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$VENDORED_PATH" | awk '{print $1}')"
else
  actual_sha256="$(shasum -a 256 "$VENDORED_PATH" | awk '{print $1}')"
fi

if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored message-card.v1 vector drifted from ${WEB_REPOSITORY}@${WEB_HEAD}:${WEB_PATH}" >&2
  echo "expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

# The Web side pins the same digest in its own .sha256 file; both copies must agree.
web_digest="$(awk '{print $1}' "$VENDORED_DIGEST_PATH")"
if [[ "$web_digest" != "$EXPECTED_SHA256" ]]; then
  echo "Vendored ${VENDORED_DIGEST_PATH} ($web_digest) disagrees with EXPECTED_SHA256 ($EXPECTED_SHA256)" >&2
  exit 1
fi

echo "message-card.v1 vector verified: $EXPECTED_SHA256"
