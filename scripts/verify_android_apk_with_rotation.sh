#!/usr/bin/env bash

set -euo pipefail

legacy_signer_sha256="b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45"
rotated_signer_sha256="5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1"

usage() {
  cat >&2 <<'USAGE'
Usage: verify_android_apk_with_rotation.sh \
  --apk <rotated-signed.apk> \
  --build-tools-dir <android-build-tools-dir> \
  --verify-log <path> \
  [--v1-verify-log <path>]   (default: <verify-log without .log>-v1.log)
USAGE
  exit 2
}

apk=""
build_tools_dir=""
verify_log=""
v1_verify_log=""

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
    --v1-verify-log)
      v1_verify_log="${2:-}"
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
[[ -n "$v1_verify_log" ]] || v1_verify_log="${verify_log%.log}-v1.log"
[[ "$v1_verify_log" != "$verify_log" ]] || { echo "--v1-verify-log must differ from --verify-log" >&2; exit 1; }

mkdir -p "$(dirname "$verify_log")" "$(dirname "$v1_verify_log")"
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

# v1 (JAR signing), issue #2518. The rotation signing writes a v1 signature with the
# legacy key (META-INF/<legacy alias>.SF/.RSA). Android 7.0+ never reads it, so the
# --min-sdk-version 24 pass above prints "Verified using v1 scheme (JAR signing): false".
# That means "v1 was not used at that minSdk"; it does not mean v1 is missing or invalid.
# v1-first tools (keytool -printcert -jarfile, some scanners and MDM) still read it as the
# signer. A second pass checks only the platforms that rely on v1 (API 23).
# Its output goes to a separate log because release and alpha workflows parse every
# "Verified using ..." and "certificate SHA-256 digest:" line of --verify-log.
# Two separate questions, in this order, so a valid identity can never hide a broken
# signature:
#   1. integrity: does the v1 signature verify over the APK's entries?
#   2. identity: is its one signer the approved legacy (API <=32) certificate?
v1_status=0
"$build_tools_dir/apksigner" verify \
  --min-sdk-version 23 \
  --max-sdk-version 23 \
  --verbose \
  --print-certs \
  "$apk" >"$v1_verify_log" 2>&1 || v1_status=$?
cat "$v1_verify_log"

if [[ "$v1_status" -ne 0 ]] || ! grep -Fxq "Verified using v1 scheme (JAR signing): true" "$v1_verify_log"; then
  echo "FAIL: v1 (JAR) signature does not verify (apksigner exit $v1_status); the v1 signer identity was not checked" >&2
  exit 1
fi
grep -Fxq "Number of signers: 1" "$v1_verify_log" || {
  echo "FAIL: v1 (JAR) signature verifies but does not have exactly one signer" >&2
  exit 1
}
v1_signer_line="$(grep -E '^Signer #1 certificate SHA-256 digest:' "$v1_verify_log" | head -n 1)"
[[ "${v1_signer_line,,}" == *"$legacy_signer_sha256"* ]] || {
  echo "FAIL: v1 (JAR) signature verifies but its signer is not the approved legacy certificate" >&2
  exit 1
}
