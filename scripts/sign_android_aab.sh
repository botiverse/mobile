#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# != 4 ]]; then
  echo 'Usage: sign_android_aab.sh INPUT_AAB OUTPUT_AAB EXPECTED_CERT_SHA256 RECEIPT_JSON' >&2
  echo 'Requires SLOCK_UPLOAD_KEYSTORE_FILE, SLOCK_UPLOAD_KEYSTORE_PASS, SLOCK_UPLOAD_KEY_ALIAS, SLOCK_UPLOAD_KEY_PASS' >&2
  exit 2
fi
input=$1 output=$2 pin=$3 receipt=$4
[[ -f "$input" && "$pin" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid input AAB or certificate pin' >&2; exit 2; }
for name in SLOCK_UPLOAD_KEYSTORE_FILE SLOCK_UPLOAD_KEYSTORE_PASS SLOCK_UPLOAD_KEY_ALIAS SLOCK_UPLOAD_KEY_PASS; do
  [[ -n "${!name:-}" ]] || { echo "Missing signing value: $name" >&2; exit 2; }
done
# Do not replace the original artifact: it remains available for hash and
# application-payload comparison in the receipt.
python3 - "$input" "$output" <<'PY'
import os, sys
if os.path.realpath(sys.argv[1]) == os.path.realpath(sys.argv[2]):
    raise SystemExit('Input and signed output must be different files')
PY
work="$(mktemp -d -t raft-aab-sign-XXXXXX)"
trap 'rm -rf "$work"' EXIT
keytool -exportcert -alias "$SLOCK_UPLOAD_KEY_ALIAS" -keystore "$SLOCK_UPLOAD_KEYSTORE_FILE" \
  -storepass:env SLOCK_UPLOAD_KEYSTORE_PASS -file "$work/signer.der" >"$work/keytool.log" 2>&1
actual="$(shasum -a 256 "$work/signer.der" | awk '{print $1}')"
[[ "$actual" == "$pin" ]] || { echo 'Upload keystore certificate does not match expected pin' >&2; exit 1; }
# Debug Gradle bundles may carry a debug JAR signature. Replace signature
# metadata only; the verifier compares every application payload entry below.
python3 - "$input" "$work/unsigned.aab" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as source, zipfile.ZipFile(sys.argv[2], 'w') as target:
    seen = set()
    for info in source.infolist():
        if info.filename in seen:
            raise SystemExit('Duplicate ZIP entry')
        seen.add(info.filename)
        name = info.filename.upper()
        base = name[9:]
        signature = name.startswith('META-INF/') and '/' not in base and (
            base == 'MANIFEST.MF' or base.endswith(('.SF', '.RSA', '.DSA', '.EC')) or base.startswith('SIG-'))
        if not signature:
            target.writestr(info, source.read(info))
PY
jarsigner -keystore "$SLOCK_UPLOAD_KEYSTORE_FILE" \
  -storepass:env SLOCK_UPLOAD_KEYSTORE_PASS -keypass:env SLOCK_UPLOAD_KEY_PASS \
  -digestalg SHA-256 -signedjar "$work/signed.aab" "$work/unsigned.aab" \
  "$SLOCK_UPLOAD_KEY_ALIAS" >"$work/sign.log" 2>&1 || { cat "$work/sign.log" >&2; exit 1; }
"$script_dir/verify_android_aab.sh" "$work/signed.aab" "$pin" "$work/receipt.json" "$input"
mkdir -p "$(dirname "$output")" "$(dirname "$receipt")"
cp "$work/signed.aab" "$output"
cp "$work/receipt.json" "$receipt"
