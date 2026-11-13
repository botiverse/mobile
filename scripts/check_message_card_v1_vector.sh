#!/usr/bin/env bash
# Verifies the vendored message-card.v1 vector (RFC 082 card messages, v2.0: the card
# header and the Markdown, Fields and Divider elements) against its pinned digest.
#
# Provenance: byte-exact copy of github.com/botiverse/slock
#   @ WEB_HEAD : WEB_PATH (and its .sha256 sibling), below (slock #13224 and #13237, staging; the acting_buttons_as_stored case from slock #14723, RFC 086 step 1).
# Content at a pinned commit is immutable, so the check is fully local — no
# network fetch or cross-repo token. To update the vector: fetch the new Web
# files, vendored-copy both, and bump WEB_HEAD + EXPECTED_SHA256 together (and
# the digest in MessageCardVectorTest.kt).
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="3a036f809943760a0fee9b6250784a3964b2b720"
readonly WEB_PATH="packages/shared/src/messageCard.v1.vector.json"
readonly EXPECTED_SHA256="19ec57295301cd1d9edde274776211c99e008020e446b0d560ea70424ee034c1"
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
