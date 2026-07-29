#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat >&2 <<'USAGE'
Usage: sign_android_apk_with_rotation.sh \
  --input <unsigned-or-signed.apk> \
  --output <rotated-signed.apk> \
  --build-tools-dir <android-build-tools-dir> \
  [--verify-log <path>]

Required environment variables:
  SLOCK_LEGACY_KEYSTORE_FILE
  SLOCK_LEGACY_KEYSTORE_PASS
  SLOCK_LEGACY_KEY_ALIAS
  SLOCK_LEGACY_KEY_PASS
  SLOCK_ROTATED_KEYSTORE_FILE
  SLOCK_ROTATED_KEYSTORE_PASS
  SLOCK_ROTATED_KEY_ALIAS
  SLOCK_ROTATED_KEY_PASS
  SLOCK_SIGNING_LINEAGE_FILE
USAGE
  exit 2
}

input=""
output=""
build_tools_dir=""
verify_log=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      input="${2:-}"
      shift 2
      ;;
    --output)
      output="${2:-}"
      shift 2
      ;;
    --build-tools-dir)
      build_tools_dir="${2:-}"
      shift 2
      ;;
    --verify-log)
      verify_log="${2:-}"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

[[ -n "$input" && -n "$output" && -n "$build_tools_dir" ]] || usage
[[ -f "$input" ]] || { echo "Input APK does not exist: $input" >&2; exit 1; }
[[ -x "$build_tools_dir/zipalign" ]] || { echo "zipalign not found under $build_tools_dir" >&2; exit 1; }
[[ -x "$build_tools_dir/apksigner" ]] || { echo "apksigner not found under $build_tools_dir" >&2; exit 1; }

required_env=(
  SLOCK_LEGACY_KEYSTORE_FILE
  SLOCK_LEGACY_KEYSTORE_PASS
  SLOCK_LEGACY_KEY_ALIAS
  SLOCK_LEGACY_KEY_PASS
  SLOCK_ROTATED_KEYSTORE_FILE
  SLOCK_ROTATED_KEYSTORE_PASS
  SLOCK_ROTATED_KEY_ALIAS
  SLOCK_ROTATED_KEY_PASS
  SLOCK_SIGNING_LINEAGE_FILE
)
for name in "${required_env[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing required signing value: $name" >&2; exit 1; }
done

[[ -f "$SLOCK_LEGACY_KEYSTORE_FILE" ]] || { echo "Legacy keystore file is missing" >&2; exit 1; }
[[ -f "$SLOCK_ROTATED_KEYSTORE_FILE" ]] || { echo "Rotated keystore file is missing" >&2; exit 1; }
[[ -f "$SLOCK_SIGNING_LINEAGE_FILE" ]] || { echo "Signing lineage file is missing" >&2; exit 1; }

work_dir="$(mktemp -d -t raft-android-rotation-signing-XXXXXX)"
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

aligned="$work_dir/aligned.apk"
signed="$work_dir/signed.apk"
resolved_verify_log="${verify_log:-$work_dir/apksigner-verify.log}"
mkdir -p "$(dirname "$output")" "$(dirname "$resolved_verify_log")"

"$build_tools_dir/zipalign" -P 16 -f 4 "$input" "$aligned"
# Keep the v2 block required by the frozen artifact contract even when the
# manifest minSdk is 29; otherwise apksigner correctly optimizes it away.
"$build_tools_dir/apksigner" sign \
  --ks "$SLOCK_LEGACY_KEYSTORE_FILE" \
  --ks-key-alias "$SLOCK_LEGACY_KEY_ALIAS" \
  --ks-pass env:SLOCK_LEGACY_KEYSTORE_PASS \
  --key-pass env:SLOCK_LEGACY_KEY_PASS \
  --next-signer \
  --ks "$SLOCK_ROTATED_KEYSTORE_FILE" \
  --ks-key-alias "$SLOCK_ROTATED_KEY_ALIAS" \
  --ks-pass env:SLOCK_ROTATED_KEYSTORE_PASS \
  --key-pass env:SLOCK_ROTATED_KEY_PASS \
  --lineage "$SLOCK_SIGNING_LINEAGE_FILE" \
  --min-sdk-version 24 \
  --rotation-min-sdk-version 33 \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$signed" \
  "$aligned"

"$script_dir/verify_android_apk_with_rotation.sh" \
  --apk "$signed" \
  --build-tools-dir "$build_tools_dir" \
  --verify-log "$resolved_verify_log"

cp "$signed" "$output"
