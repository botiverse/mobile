#!/usr/bin/env bash
set -euo pipefail

readonly WEB_REPOSITORY="botiverse/slock"
readonly WEB_HEAD="22b14466900d22f25c58d2d4d24dff4f2912ae75"
readonly WEB_PATH="packages/web/tests/fixtures/readStateBehavior.v1.json"
readonly EXPECTED_SHA256="cbdf80669422af3dfdf62742180668ad952daabd4ac41336f8ba2ef49980befc"
readonly VENDORED_PATH="compose/shared/src/androidUnitTest/resources/sync/read-state-behavior-v1.json"

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required to verify the private Web authority artifact" >&2
  exit 1
fi

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT

gh api "repos/${WEB_REPOSITORY}/contents/${WEB_PATH}?ref=${WEB_HEAD}" --jq '.content' \
  | tr -d '\n' \
  | base64 --decode > "$tmp_file"

actual_sha256="$(shasum -a 256 "$tmp_file" | awk '{print $1}')"
if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
  echo "Web read-state authority SHA mismatch: expected $EXPECTED_SHA256, got $actual_sha256" >&2
  exit 1
fi

if ! cmp -s "$tmp_file" "$VENDORED_PATH"; then
  echo "Vendored mobile read-state vector differs from Web $WEB_HEAD:$WEB_PATH" >&2
  exit 1
fi

echo "Read-state authority vector verified: $EXPECTED_SHA256"
