#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: validate_ohos_app_pack.sh \
  --app <signed.app> \
  --bundle <bundle-name> \
  --version-name <version-name> \
  --version-code <version-code> \
  --sign-tool <hap-sign-tool.jar> \
  --output-dir <directory>
USAGE
}

APP_FILE=""
EXPECTED_BUNDLE=""
EXPECTED_VERSION_NAME=""
EXPECTED_VERSION_CODE=""
SIGN_TOOL=""
OUTPUT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) APP_FILE="${2:-}"; shift 2 ;;
    --bundle) EXPECTED_BUNDLE="${2:-}"; shift 2 ;;
    --version-name) EXPECTED_VERSION_NAME="${2:-}"; shift 2 ;;
    --version-code) EXPECTED_VERSION_CODE="${2:-}"; shift 2 ;;
    --sign-tool) SIGN_TOOL="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

for required in APP_FILE EXPECTED_BUNDLE EXPECTED_VERSION_NAME EXPECTED_VERSION_CODE SIGN_TOOL OUTPUT_DIR; do
  if [[ -z "${!required}" ]]; then
    echo "Missing required argument: ${required}" >&2
    usage
    exit 2
  fi
done

[[ -f "$APP_FILE" ]] || { echo "OHOS .app not found: $APP_FILE" >&2; exit 1; }
[[ -f "$SIGN_TOOL" ]] || { echo "hap-sign-tool.jar not found: $SIGN_TOOL" >&2; exit 1; }
command -v unzip >/dev/null
command -v node >/dev/null
command -v java >/dev/null

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$OUTPUT_DIR"

unzip -t "$APP_FILE" >/dev/null
unzip -q "$APP_FILE" -d "$WORK_DIR/app"

PACK_INFO="$(find "$WORK_DIR/app" -type f -name pack.info -print -quit)"
[[ -n "$PACK_INFO" ]] || { echo "The .app package does not contain pack.info" >&2; exit 1; }

node - "$PACK_INFO" "$EXPECTED_BUNDLE" "$EXPECTED_VERSION_NAME" "$EXPECTED_VERSION_CODE" <<'NODE'
const fs = require("node:fs");

const [file, expectedBundle, expectedName, expectedCode] = process.argv.slice(2);
const pack = JSON.parse(fs.readFileSync(file, "utf8"));
const app = pack?.summary?.app;
if (!app) throw new Error("pack.info is missing summary.app");
if (app.bundleName !== expectedBundle) {
  throw new Error(`Unexpected bundleName: ${app.bundleName}`);
}
if (app.version?.name !== expectedName) {
  throw new Error(`Unexpected versionName: ${app.version?.name}`);
}
if (String(app.version?.code) !== expectedCode) {
  throw new Error(`Unexpected versionCode: ${app.version?.code}`);
}
if (!Array.isArray(pack?.summary?.modules) || pack.summary.modules.length === 0) {
  throw new Error("pack.info does not describe any modules");
}
NODE

HAPS=()
while IFS= read -r -d '' hap; do
  HAPS+=("$hap")
done < <(find "$WORK_DIR/app" -type f -name '*.hap' -print0)
if [[ ${#HAPS[@]} -eq 0 ]]; then
  echo "The .app package does not contain a HAP" >&2
  exit 1
fi

mkdir -p "$WORK_DIR/verified"
for hap in "${HAPS[@]}"; do
  name="$(basename "$hap" .hap)"
  java -jar "$SIGN_TOOL" verify-app \
    -inFile "$hap" \
    -outCertChain "$WORK_DIR/verified/${name}.cer" \
    -outProfile "$WORK_DIR/verified/${name}.p7b" >/dev/null
  [[ -s "$WORK_DIR/verified/${name}.cer" ]]
  [[ -s "$WORK_DIR/verified/${name}.p7b" ]]
done

OUTPUT_APP="$OUTPUT_DIR/raft-ohos-${EXPECTED_VERSION_NAME}-${EXPECTED_VERSION_CODE}.app"
cp "$APP_FILE" "$OUTPUT_APP"
cp "$PACK_INFO" "$OUTPUT_DIR/pack.info"

APP_SHA256="$(shasum -a 256 "$OUTPUT_APP" | awk '{print $1}')"
APP_SIZE="$(wc -c < "$OUTPUT_APP" | tr -d '[:space:]')"
HAP_COUNT="${#HAPS[@]}"

node - \
  "$OUTPUT_DIR/ohos-release-metadata.json" \
  "$EXPECTED_BUNDLE" \
  "$EXPECTED_VERSION_NAME" \
  "$EXPECTED_VERSION_CODE" \
  "${GITHUB_SHA:-unknown}" \
  "$(basename "$OUTPUT_APP")" \
  "$APP_SHA256" \
  "$APP_SIZE" \
  "$HAP_COUNT" <<'NODE'
const fs = require("node:fs");

const [
  output,
  bundleName,
  versionName,
  versionCode,
  gitCommit,
  appFile,
  appSha256,
  appSize,
  signedHapCount,
] = process.argv.slice(2);
const metadata = {
  platform: "ohos",
  bundle_name: bundleName,
  version_name: versionName,
  version_code: Number(versionCode),
  git_commit: gitCommit,
  app_file: appFile,
  app_sha256: appSha256,
  app_size: Number(appSize),
  signed_hap_count: Number(signedHapCount),
  generated_at: new Date().toISOString(),
};
fs.writeFileSync(output, `${JSON.stringify(metadata, null, 2)}\n`);
NODE

printf 'app_path=%s\n' "$OUTPUT_APP"
printf 'app_sha256=%s\n' "$APP_SHA256"
printf 'app_size=%s\n' "$APP_SIZE"
printf 'signed_hap_count=%s\n' "$HAP_COUNT"
