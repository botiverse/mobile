#!/usr/bin/env bash
# Real signed fixtures for scripts/qa/AppIdentityApkSigner.java: four rotation policies signed
# with fresh keys, inspected at six released SDKs, each selection compared with the certificate
# keytool exports for the key Android uses there (24 cases), and a corrupted APK refused.
# Needs JDK 17+ (java, keytool) and Android build-tools 36.0.0 (APKSIGNER_JAR overrides the path).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jar="${APKSIGNER_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools/36.0.0/lib/apksigner.jar}"
[[ -f "$jar" ]] || { echo "apksigner.jar of build-tools 36.0.0 not found: $jar" >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pass="fixture-pass"

for key in a b c; do
  keytool -genkeypair -storetype PKCS12 -keystore "$work/$key.p12" -storepass "$pass" -keypass "$pass" \
    -alias k -keyalg EC -groupname secp256r1 -dname "CN=identity-fixture-$key" -validity 3650 -noprompt >/dev/null 2>&1
  keytool -exportcert -storetype PKCS12 -keystore "$work/$key.p12" -storepass "$pass" -alias k -file "$work/$key.der" >/dev/null 2>&1
  declare "cert_$key=$(sha256sum "$work/$key.der" | cut -d' ' -f1)"
done

java -cp "$jar" "$repo_root/scripts/qa/AppIdentityApkSignerFixtures.java" "$work" "$pass"

sdks=(29 32 33 34 35 36)
# Which key Android uses per SDK above. A preview-targeted rotation at 35 keeps the old key on
# stable API 35.
declare -A expected=(
  [rotation-35]="a a a a b b"
  [dev-release-35]="a a a a a b"
  [rotations-33-35]="a a b b c c"
  [rotations-35-36]="a a a a b c"
)
failures=0
cases=0
for policy in rotation-35 dev-release-35 rotations-33-35 rotations-35-36; do
  read -r -a keys <<<"${expected[$policy]}"
  for i in "${!sdks[@]}"; do
    sdk="${sdks[$i]}"
    want_var="cert_${keys[$i]}"
    want="${!want_var}"
    out="$(java -cp "$jar" "$repo_root/scripts/qa/AppIdentityApkSigner.java" "$work/$policy.apk" "$sdk" || true)"
    cases=$((cases + 1))
    if [[ "$out" != "{\"result\":\"SIGNER_SELECTED\",\"android_sdk\":$sdk,\"signer_sha256\":\"$want\"}" ]]; then
      echo "FAIL $policy API $sdk: expected key ${keys[$i]}, got $out" >&2
      failures=$((failures + 1))
    fi
  done
done

# A tampered APK (one byte of the signed content changed) is refused, never given a signer.
cp "$work/rotation-35.apk" "$work/tampered.apk"
printf '\x01' | dd of="$work/tampered.apk" bs=1 seek=40 conv=notrunc status=none
if out="$(java -cp "$jar" "$repo_root/scripts/qa/AppIdentityApkSigner.java" "$work/tampered.apk" 35)"; then
  echo "FAIL tampered APK accepted: $out" >&2
  failures=$((failures + 1))
elif [[ "$out" != '{"result":"APK_SIGNER_UNVERIFIED"}' ]]; then
  echo "FAIL tampered APK: unexpected output $out" >&2
  failures=$((failures + 1))
fi

if ((failures)); then
  echo "app identity APK signer: $failures failure(s) of $((cases + 1))" >&2
  exit 1
fi
echo "app identity APK signer: PASS ($cases signer selections + tampered refusal)"
