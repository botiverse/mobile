#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
signer="$repo_root/scripts/sign_android_apk_with_rotation.sh"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

mkdir -p "$fixture_root/build-tools"
printf 'fixture-apk\n' > "$fixture_root/input.apk"
printf 'legacy\n' > "$fixture_root/legacy.jks"
printf 'rotated\n' > "$fixture_root/rotated.jks"
printf 'lineage\n' > "$fixture_root/lineage.bin"

cat > "$fixture_root/build-tools/zipalign" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$ZIPALIGN_ARGS_LOG"
if [[ "$1" == "-c" ]]; then
  exit 0
fi
cp "${@: -2:1}" "${@: -1}"
SH

cat > "$fixture_root/build-tools/apksigner" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "sign" ]]; then
  printf '%s\n' "$@" > "$SIGN_ARGS_LOG"
  output=""
  previous=""
  for value in "$@"; do
    if [[ "$previous" == "--out" ]]; then
      output="$value"
      break
    fi
    previous="$value"
  done
  cp "${@: -1}" "$output"
  exit 0
fi
if [[ " $* " == *" --max-sdk-version 23 "* ]]; then
  # v1 (JAR) pass: what API 23 would check.
  printf '%s\n' "$@" > "$VERIFY_V1_ARGS_LOG"
  cat <<VERIFY
Verified using v1 scheme (JAR signing): ${MOCK_V1_RESULT:-true}
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: ${MOCK_V1_SIGNERS:-1}
Signer #1 certificate SHA-256 digest: ${MOCK_V1_DIGEST:-b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45}
VERIFY
  exit "${MOCK_V1_EXIT:-0}"
fi
printf '%s\n' "$@" > "$VERIFY_ARGS_LOG"
cat <<VERIFY
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): ${MOCK_V2_RESULT:-true}
Verified using v3 scheme (APK Signature Scheme v3): ${MOCK_V3_RESULT:-true}
Verified using v3.1 scheme (APK Signature Scheme v3.1): ${MOCK_V31_RESULT:-true}
Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: 5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1
Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45
VERIFY
SH
chmod +x "$fixture_root/build-tools/zipalign" "$fixture_root/build-tools/apksigner"

export SIGN_ARGS_LOG="$fixture_root/sign-args.log"
export VERIFY_ARGS_LOG="$fixture_root/verify-args.log"
export VERIFY_V1_ARGS_LOG="$fixture_root/verify-v1-args.log"
export ZIPALIGN_ARGS_LOG="$fixture_root/zipalign-args.log"
export SLOCK_LEGACY_KEYSTORE_FILE="$fixture_root/legacy.jks"
export SLOCK_LEGACY_KEYSTORE_PASS="legacy-store-pass"
export SLOCK_LEGACY_KEY_ALIAS="upload"
export SLOCK_LEGACY_KEY_PASS="legacy-key-pass"
export SLOCK_ROTATED_KEYSTORE_FILE="$fixture_root/rotated.jks"
export SLOCK_ROTATED_KEYSTORE_PASS="rotated-store-pass"
export SLOCK_ROTATED_KEY_ALIAS="raft-android-release"
export SLOCK_ROTATED_KEY_PASS="rotated-key-pass"
export SLOCK_SIGNING_LINEAGE_FILE="$fixture_root/lineage.bin"

"$signer" \
  --input "$fixture_root/input.apk" \
  --output "$fixture_root/output.apk" \
  --build-tools-dir "$fixture_root/build-tools" \
  --verify-log "$fixture_root/verify.log"

cmp -s "$fixture_root/input.apk" "$fixture_root/output.apk"
grep -Fxq -- "--next-signer" "$fixture_root/sign-args.log"
grep -Fxq -- "--lineage" "$fixture_root/sign-args.log"
grep -Fxq -- "$fixture_root/lineage.bin" "$fixture_root/sign-args.log"
awk 'previous == "--min-sdk-version" && $0 == "24" { found = 1 } { previous = $0 } END { exit !found }' "$fixture_root/sign-args.log"
awk 'previous == "--min-sdk-version" && $0 == "24" { found = 1 } { previous = $0 } END { exit !found }' "$fixture_root/verify-args.log"
grep -Fxq -- "--rotation-min-sdk-version" "$fixture_root/sign-args.log"
grep -Fxq -- "33" "$fixture_root/sign-args.log"
grep -Fxq -- "env:SLOCK_LEGACY_KEYSTORE_PASS" "$fixture_root/sign-args.log"
grep -Fxq -- "env:SLOCK_ROTATED_KEYSTORE_PASS" "$fixture_root/sign-args.log"
grep -Eq '^-P 16 -f 4 ' "$fixture_root/zipalign-args.log"
grep -Eq '^-c -P 16 4 ' "$fixture_root/zipalign-args.log"
# v1 pass: API 23 only, its own log next to --verify-log, never appended to it (the
# release workflow parses every digest / "Verified using" line of --verify-log).
awk 'previous == "--min-sdk-version" && $0 == "23" { found = 1 } { previous = $0 } END { exit !found }' "$fixture_root/verify-v1-args.log"
awk 'previous == "--max-sdk-version" && $0 == "23" { found = 1 } { previous = $0 } END { exit !found }' "$fixture_root/verify-v1-args.log"
grep -Fxq "Verified using v1 scheme (JAR signing): true" "$fixture_root/verify-v1.log"
if grep -Fq "Signer #1" "$fixture_root/verify.log"; then
  echo "v1 pass output leaked into --verify-log" >&2
  exit 1
fi

if env -u SLOCK_SIGNING_LINEAGE_FILE "$signer" \
  --input "$fixture_root/input.apk" \
  --output "$fixture_root/should-not-exist.apk" \
  --build-tools-dir "$fixture_root/build-tools" >/dev/null 2>&1; then
  echo "Expected missing lineage configuration to fail" >&2
  exit 1
fi

if MOCK_V2_RESULT=false "$signer" \
  --input "$fixture_root/input.apk" \
  --output "$fixture_root/should-not-pass-v2.apk" \
  --build-tools-dir "$fixture_root/build-tools" >/dev/null 2>&1; then
  echo "Expected missing v2 verification to fail" >&2
  exit 1
fi

if MOCK_V3_RESULT=false "$signer" \
  --input "$fixture_root/input.apk" \
  --output "$fixture_root/should-not-pass-v3.apk" \
  --build-tools-dir "$fixture_root/build-tools" >/dev/null 2>&1; then
  echo "Expected missing v3 verification to fail" >&2
  exit 1
fi

if MOCK_V31_RESULT=false "$signer" \
  --input "$fixture_root/input.apk" \
  --output "$fixture_root/should-not-pass-v31.apk" \
  --build-tools-dir "$fixture_root/build-tools" >/dev/null 2>&1; then
  echo "Expected missing v3.1 verification to fail" >&2
  exit 1
fi

expect_v1_failure() {
  local label="$1" expected="$2"
  shift 2
  local err="$fixture_root/v1-failure.err"
  if env "$@" "$signer" \
    --input "$fixture_root/input.apk" \
    --output "$fixture_root/should-not-pass-v1.apk" \
    --build-tools-dir "$fixture_root/build-tools" >/dev/null 2>"$err"; then
    echo "Expected $label to fail" >&2
    exit 1
  fi
  grep -Fq "$expected" "$err" || {
    echo "Expected $label to fail with: $expected" >&2
    cat "$err" >&2
    exit 1
  }
}

# Integrity failures report integrity, never a signer verdict.
expect_v1_failure "unverified v1 signature" "v1 (JAR) signature does not verify" MOCK_V1_RESULT=false
expect_v1_failure "v1 verification error" "v1 (JAR) signature does not verify" MOCK_V1_EXIT=1
expect_v1_failure "v1 extra signer" "does not have exactly one signer" MOCK_V1_SIGNERS=2
expect_v1_failure "v1 unexpected signer" "its signer is not the approved legacy certificate" \
  MOCK_V1_DIGEST=5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1

echo "sign_android_apk_with_rotation: PASS"
