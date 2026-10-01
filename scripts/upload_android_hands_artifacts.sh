#!/usr/bin/env bash
set -euo pipefail

: "${HANDS_API:?HANDS_API is required}"
: "${HANDS_BEARER_TOKEN:?HANDS_BEARER_TOKEN is required}"
: "${HANDS_APP_SLUG:=raft-android}"
: "${SOURCE_REPOSITORY:?SOURCE_REPOSITORY is required}"
: "${SOURCE_COMMIT_SHA:?SOURCE_COMMIT_SHA is required}"
: "${CI_RUN_ID:?CI_RUN_ID is required}"
: "${PACKAGE_NAME:?PACKAGE_NAME is required}"
: "${VERSION_NAME:?VERSION_NAME is required}"
: "${VERSION_CODE:?VERSION_CODE is required}"
: "${AAB_PATH:?AAB_PATH is required}"
: "${APK_PATH:?APK_PATH is required}"

[[ "$SOURCE_COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'invalid source commit' >&2; exit 2; }
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo 'invalid version code' >&2; exit 2; }
for f in "$AAB_PATH" "$APK_PATH"; do [[ -f "$f" ]] || { echo "missing artifact: $f" >&2; exit 2; }; done

sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
size() { wc -c < "$1" | tr -d '[:space:]'; }
if [[ -z "${HANDS_ANDROID_APP_ID:-}" ]]; then
  HANDS_ANDROID_APP_ID="$(curl --fail-with-body --silent --show-error -H "Authorization: Bearer $HANDS_BEARER_TOKEN" "${HANDS_API%/}/api/apps" | jq -er --arg slug "$HANDS_APP_SLUG" '.apps[] | select(.slug==$slug) | .id' | head -n1)"
fi
[[ -n "$HANDS_ANDROID_APP_ID" ]] || { echo 'unable to resolve Hands app id' >&2; exit 2; }
json=$(jq -n \
  --arg repo "$SOURCE_REPOSITORY" --arg commit "$SOURCE_COMMIT_SHA" --arg run "$CI_RUN_ID" \
  --arg pkg "$PACKAGE_NAME" --arg vn "$VERSION_NAME" --argjson vc "$VERSION_CODE" \
  --arg cert "${UPLOAD_KEY_CERT_SHA256:-}" \
  --arg af "$(basename "$AAB_PATH")" --arg ak "$(size "$AAB_PATH")" --arg ah "$(sha256 "$AAB_PATH")" \
  --arg pf "$(basename "$APK_PATH")" --arg pk "$(size "$APK_PATH")" --arg ph "$(sha256 "$APK_PATH")" \
  '{source:{repository:$repo,commit_sha:$commit,ci_run_id:$run},package_name:$pkg,version_name:$vn,version_code:$vc,upload_key_cert_sha256:$cert,artifacts:[{kind:"aab",filename:$af,size_bytes:($ak|tonumber),sha256:$ah},{kind:"apk",filename:$pf,size_bytes:($pk|tonumber),sha256:$ph}]}' )

auth=( -H "Authorization: Bearer $HANDS_BEARER_TOKEN" -H 'Content-Type: application/json' )
base="${HANDS_API%/}/api/apps/${HANDS_ANDROID_APP_ID}/android-release-artifacts"
created=$(curl --fail-with-body --silent --show-error "${auth[@]}" -X POST "$base" --data-binary "$json")
build_id=$(jq -er '.build_id' <<<"$created")

upload_asset() {
  local kind=$1 path=$2
  local asset_id url method headers complete
  asset_id=$(jq -er --arg k "$kind" '.artifacts[] | select(.kind==$k) | .asset_id' <<<"$created")
  method=$(jq -er --arg k "$kind" '.artifacts[] | select(.kind==$k) | .upload.method' <<<"$created")
  [[ "$method" == PUT ]] || { echo "unexpected upload method" >&2; return 1; }
  url=$(jq -er --arg k "$kind" '.artifacts[] | select(.kind==$k) | .upload.url' <<<"$created")
  headers=$(jq -er --arg k "$kind" '.artifacts[] | select(.kind==$k) | (.upload.headers // {}) | to_entries | map([.key,.value])' <<<"$created")
  complete=$(jq -er --arg k "$kind" '.artifacts[] | select(.kind==$k) | .complete_url' <<<"$created")
  header_args=(); while read -r k v; do header_args+=( -H "$k: $v" ); done < <(jq -r '.[] | @tsv' <<<"$headers")
  curl --fail-with-body --silent --show-error -X PUT "${header_args[@]}" --upload-file "$path" "$url" >/dev/null
  case "$complete" in
    "${HANDS_API%/}"/*) complete_target="$complete" ;;
    http://*|https://*) echo "foreign completion origin" >&2; return 1 ;;
    /*) complete_target="${HANDS_API%/}$complete" ;;
    *) complete_target="${HANDS_API%/}/$complete" ;;
  esac
  curl --fail-with-body --silent --show-error "${auth[@]}" -X POST "$complete_target" >/dev/null
}
upload_asset aab "$AAB_PATH"
upload_asset apk "$APK_PATH"
receipt=$(curl --fail-with-body --silent --show-error "${auth[@]}" "$base/$build_id")
[[ "$(jq -r .status <<<"$receipt")" == ready ]] || { echo 'Hands receipt not ready' >&2; exit 1; }
[[ "$(jq '[.artifacts[] | select(.status=="sealed")] | length' <<<"$receipt")" == 2 ]] || { echo 'Hands receipt missing sealed assets' >&2; exit 1; }
jq -e --arg id "$build_id" --argjson expected "$json" '
  .build_id == $id and .source == $expected.source and
  .package_name == $expected.package_name and .version_name == $expected.version_name and
  .version_code == $expected.version_code and
  .upload_key_cert_sha256 == $expected.upload_key_cert_sha256 and
  ([.artifacts[] | {kind,filename,size_bytes,sha256}] | sort_by(.kind)) ==
  ($expected.artifacts | sort_by(.kind)) and
  all(.artifacts[]; .verified_sha256 == .sha256 and .verified_size_bytes == .size_bytes)
' <<<"$receipt" >/dev/null || { echo 'Hands receipt identity or digest mismatch' >&2; exit 1; }
echo "build_id=$build_id"
echo "status=ready"
