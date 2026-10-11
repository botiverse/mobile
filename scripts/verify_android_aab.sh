#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo 'Usage: verify_android_aab.sh AAB EXPECTED_CERT_SHA256 RECEIPT_JSON [ORIGINAL_AAB]' >&2
  exit 2
fi
aab=$1 pin=$2 receipt=$3
[[ -f "$aab" && "$pin" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid AAB or certificate pin' >&2; exit 2; }
work="$(mktemp -d -t raft-aab-verify-XXXXXX)"
trap 'rm -rf "$work"' EXIT
args=("$aab" "$pin" "$work/signer.der" "$work/receipt.json")
if [[ $# == 4 ]]; then args+=("$4"); fi
java "$script_dir/VerifyAndroidAab.java" "${args[@]}"
# Trust only the pinned leaf for strict JAR validation. Android upload keys are
# normally self-signed; this public-cert store contains no private signing key.
export AAB_VERIFY_STORE_PASS=public-certificate-only
keytool -importcert -noprompt -alias upload -file "$work/signer.der" \
  -keystore "$work/trust.p12" -storepass:env AAB_VERIFY_STORE_PASS >"$work/keytool.log" 2>&1
jarsigner -verify -strict -keystore "$work/trust.p12" \
  -storepass:env AAB_VERIFY_STORE_PASS "$aab" upload >"$work/jarsigner.log" 2>&1 || {
  cat "$work/jarsigner.log" >&2; exit 1;
}
mkdir -p "$(dirname "$receipt")"
cp "$work/receipt.json" "$receipt"
echo "Verified AAB payload signatures and certificate $pin"
