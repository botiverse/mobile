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
printf '%s\n' "$@" > "$VERIFY_ARGS_LOG"
cat <<VERIFY
Verifies
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

echo "sign_android_apk_with_rotation: PASS"
