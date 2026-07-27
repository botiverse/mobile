#!/usr/bin/env bash

set -euo pipefail

legacy_signer_sha256="b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45"
rotated_signer_sha256="5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1"

usage() {
  cat >&2 <<'USAGE'
Usage: verify_android_apk_with_rotation.sh \
  --apk <rotated-signed.apk> \
  --build-tools-dir <android-build-tools-dir> \
  --verify-log <path>
USAGE
  exit 2
}

apk=""
build_tools_dir=""
verify_log=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      apk="${2:-}"
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

[[ -n "$apk" && -n "$build_tools_dir" && -n "$verify_log" ]] || usage
[[ -f "$apk" ]] || { echo "APK does not exist: $apk" >&2; exit 1; }
[[ -x "$build_tools_dir/zipalign" ]] || { echo "zipalign not found under $build_tools_dir" >&2; exit 1; }
[[ -x "$build_tools_dir/apksigner" ]] || { echo "apksigner not found under $build_tools_dir" >&2; exit 1; }

mkdir -p "$(dirname "$verify_log")"
"$build_tools_dir/zipalign" -c -P 16 4 "$apk"
# Verify the frozen API 24-32 v2 contract even when the manifest minSdk is 29.
"$build_tools_dir/apksigner" verify \
  --min-sdk-version 24 \
  --verbose \
  --print-certs \
  "$apk" 2>&1 | tee "$verify_log"

grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" "$verify_log"
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" "$verify_log"
grep -Fq "Verified using v3.1 scheme (APK Signature Scheme v3.1): true" "$verify_log"

legacy_line="$(grep -E 'Signer \(minSdkVersion=[0-9]+, maxSdkVersion=32\) certificate SHA-256 digest:' "$verify_log" | head -n 1)"
rotated_line="$(grep -E 'Signer \(minSdkVersion=33, maxSdkVersion=[0-9]+\) certificate SHA-256 digest:' "$verify_log" | head -n 1)"
[[ "${legacy_line,,}" == *"$legacy_signer_sha256"* ]] || {
  echo "API <=32 signer does not match the approved legacy certificate" >&2
  exit 1
}
[[ "${rotated_line,,}" == *"$rotated_signer_sha256"* ]] || {
  echo "API >=33 signer does not match the approved Botiverse certificate" >&2
  exit 1
}
