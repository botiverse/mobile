#!/usr/bin/env bash
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="47e3545bbea1364ca5a209a0e2d5218779c7d762"
readonly WEB_PATH="packages/shared/src/canonicalMessageV2.vector.json"
readonly EXPECTED_SHA256="9ac66a83515df1e7eada19b864d5b41c6038a1a559de18249479455af4e9086e"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/sync/canonical-message-v2-reaction.vector.json"

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required to verify the private Web canonical-message V2 vector" >&2
  exit 1
fi

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT

gh api "repos/${WEB_REPOSITORY}/contents/${WEB_PATH}?ref=${WEB_HEAD}" --jq '.content' \
  | tr -d '\n' \
  | base64 --decode > "$tmp_file"

actual_sha256="$(shasum -a 256 "$tmp_file" | awk '{print $1}')"
if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Web canonical-message V2 vector SHA mismatch: expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

if ! cmp -s "$tmp_file" "$VENDORED_PATH"; then
  echo "Vendored mobile canonical-message V2 vector differs from Web $WEB_HEAD:$WEB_PATH" >&2
  exit 1
fi

echo "Canonical-message V2 reaction vector verified: $EXPECTED_SHA256"
