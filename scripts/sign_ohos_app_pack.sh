#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: sign_ohos_app_pack.sh --app FILE --sign-tool JAR --cert FILE \
  --profile FILE --keystore FILE --key-alias ALIAS --key-password PASSWORD \
  --keystore-password PASSWORD [--compatible-version API]
USAGE
}

app=""
sign_tool=""
cert=""
profile=""
keystore=""
key_alias=""
key_password=""
keystore_password=""
compatible_version="23"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) app="$2"; shift 2 ;;
    --sign-tool) sign_tool="$2"; shift 2 ;;
    --cert) cert="$2"; shift 2 ;;
    --profile) profile="$2"; shift 2 ;;
    --keystore) keystore="$2"; shift 2 ;;
    --key-alias) key_alias="$2"; shift 2 ;;
    --key-password) key_password="$2"; shift 2 ;;
    --keystore-password) keystore_password="$2"; shift 2 ;;
    --compatible-version) compatible_version="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for value in "$app" "$sign_tool" "$cert" "$profile" "$keystore" "$key_alias" \
  "$key_password" "$keystore_password"; do
  [[ -n "$value" ]] || { usage >&2; exit 2; }
done
[[ "$compatible_version" =~ ^[0-9]+$ ]] || {
  echo "compatible version must be a non-negative integer" >&2
  exit 2
}
for file in "$app" "$sign_tool" "$cert" "$profile" "$keystore"; do
  [[ -f "$file" ]] || { echo "Required file not found: $file" >&2; exit 1; }
done

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
unpacked="$work_dir/unpacked"
mkdir -p "$unpacked"
unzip -q "$app" -d "$unpacked"

mapfile -d '' -t haps < <(find "$unpacked" -type f -name '*.hap' -print0)
if [[ ${#haps[@]} -eq 0 ]]; then
  echo "No HAP files found in $app" >&2
  exit 1
fi

for hap in "${haps[@]}"; do
  signed_hap="$hap.signed.hap"
  java -jar "$sign_tool" sign-app \
    -mode localSign \
    -keyAlias "$key_alias" \
    -keyPwd "$key_password" \
    -appCertFile "$cert" \
    -profileFile "$profile" \
    -profileSigned 1 \
    -inFile "$hap" \
    -signAlg SHA256withECDSA \
    -keystoreFile "$keystore" \
    -keystorePwd "$keystore_password" \
    -outFile "$signed_hap" \
    -compatibleVersion "$compatible_version" \
    -signCode 1 \
    -pwdInputMode 0
  test -s "$signed_hap"
  mv "$signed_hap" "$hap"
done

signed_app="$work_dir/$(basename "$app")"
(
  cd "$unpacked"
  zip -q -r "$signed_app" .
)
test -s "$signed_app"
mv "$signed_app" "$app"
echo "Signed ${#haps[@]} HAP(s) in $app"
