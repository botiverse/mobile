#!/usr/bin/env bash
# Put a CI-built Play AAB that android-release.yml already uploaded to Hands on the
# Google Play internal testing track.
#
# Release rule (artin, 2026-10-29): every Android release goes to Play internal
# testing by default, like iOS goes to the TestFlight internal group. Closed,
# open and production tracks still need artin's explicit approval, so this script
# only ever uses --track internal.
#
# Run it as a signed-in Hands human or agent with the publisher role
# (`hands login`). By policy, Google Play writes run under an accountable identity,
# never CI's deploy token, so CI does not run this step. (Hands does not enforce
# that for promote yet: it treats a deploy token as an agent. Do not rely on it.)
#
# The Hands draft this creates exists only to carry the Play promotion. Never
# publish it on Hands: it would ship the Play variant to the Hands channel.
#
# Usage: scripts/play_internal_promote.sh <hands_build_id> [acceptance note]
set -euo pipefail

build_id="${1:?usage: $0 <hands_build_id> [acceptance note]}"
note="${2:-}"
app="${HANDS_ANDROID_APP_ID:-a78f3891-f980-4f48-80d7-2a58e93a99aa}"
read -r -a hands <<<"${HANDS_CLI:-npx -y @botiverse/hands-cli@0.5.34}"

api() { "${hands[@]}" api "$@" --json; }

bundle="$(api GET "/api/apps/$app/android-release-artifacts/$build_id")"
jq -e '.status == "ready" and ([.artifacts[] | select(.kind == "aab" and .status == "sealed")] | length == 1)' \
  <<<"$bundle" >/dev/null || { echo "Hands build $build_id is not ready with one sealed AAB" >&2; jq . <<<"$bundle" >&2; exit 1; }
aab_id="$(jq -er '.artifacts[] | select(.kind == "aab") | .asset_id' <<<"$bundle")"
version_code="$(jq -er .version_code <<<"$bundle")"
run_id="$(jq -er .source.ci_run_id <<<"$bundle")"
commit="$(jq -er .source.commit_sha <<<"$bundle")"
echo "Hands build $build_id: versionCode $version_code, source ${commit:0:8}, CI run $run_id"

note="${note:-Play variant versionCode $version_code from ${commit:0:8}, CI run $run_id: build and upload-key signature verified in CI. Internal testing track only, by the default release rule (artin 2026-10-29).}"

release="$(api POST "/api/apps/$app/releases/draft" --data "$(jq -nc --arg b "$build_id" '{build_id: $b}')")"
release_id="$(jq -er .id <<<"$release")"
echo "Hands draft $release_id (do NOT publish on Hands)"

revision="$(jq -er .revision <<<"$release")"
api POST "/api/apps/$app/releases/$release_id/receipts/acceptance" --data "$(jq -nc \
  --arg a "$aab_id" --arg n "$note" --argjson r "$revision" \
  '{artifact_id: $a, verdict: "pass", matrix_ref: $n, expected_revision: $r}')" >/dev/null

# A written acceptance bumps the revision: re-read before promoting.
revision="$(api GET "/api/apps/$app/releases/$release_id" | jq -er .release.revision)"
promote="$(api POST "/api/apps/$app/releases/$release_id/distributions/play/promote" --data "$(jq -nc \
  --arg n "$note" --argjson r "$revision" '{track: "internal", expected_revision: $r, approval: {note: $n}}')")"
jq -e '.track == "internal"' <<<"$promote" >/dev/null || { echo "Play promotion did not confirm the internal track" >&2; jq . <<<"$promote" >&2; exit 1; }
echo "Play internal: versionCode $(jq -r .version_code <<<"$promote"), receipt $(jq -r .receipt_id <<<"$promote")"
echo "release_id=$release_id"
