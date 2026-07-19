#!/usr/bin/env bash
# Replace the app product built alongside the XCUITest runner with an exact
# iOS Simulator .app.zip while keeping the runner/xctestrun bundle intact.
set -euo pipefail

ZIP_PATH="${1:?zip path required}"
METADATA_PATH="${2:?metadata path required}"
PRODUCTS_DIR="${3:?DerivedData products directory required}"
APP_RUN_ID="${4:?app run id required}"
EXPECTED_SHA="${5:-}"
EXPECTED_SOURCE="${6:-}"
EXPECTED_BUNDLE="${7:-}"
EXPECTED_VERSION="${8:-}"
EXPECTED_CODE="${9:-}"
DITTO_BIN="${DITTO_BIN:-ditto}"
PLIST_BUDDY="${PLIST_BUDDY:-/usr/libexec/PlistBuddy}"

test -s "$ZIP_PATH"
test -s "$METADATA_PATH"
if command -v shasum >/dev/null 2>&1; then
  ACTUAL_SHA=$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')
else
  ACTUAL_SHA=$(sha256sum "$ZIP_PATH" | awk '{print $1}')
fi
if [ -n "$EXPECTED_SHA" ] && [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  echo "pinned iOS app SHA mismatch: expected=$EXPECTED_SHA actual=$ACTUAL_SHA" >&2
  exit 2
fi

WORK=$(mktemp -d "${RUNNER_TEMP:-/tmp}/pinned-ios-app.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
if [ "$(uname -s)" = "Darwin" ]; then
  ACTUAL_SIZE=$(stat -f '%z' "$ZIP_PATH")
else
  ACTUAL_SIZE=$(stat -c '%s' "$ZIP_PATH")
fi
VERIFIED_ENV="$WORK/verified.env"
ACTUAL_SHA="$ACTUAL_SHA" ACTUAL_SIZE="$ACTUAL_SIZE" APP_RUN_ID="$APP_RUN_ID" \
  EXPECTED_SHA="$EXPECTED_SHA" EXPECTED_SOURCE="$EXPECTED_SOURCE" EXPECTED_BUNDLE="$EXPECTED_BUNDLE" \
  EXPECTED_VERSION="$EXPECTED_VERSION" EXPECTED_CODE="$EXPECTED_CODE" \
  node - "$METADATA_PATH" "$VERIFIED_ENV" <<'NODE'
const fs = require("fs");
const [metadataPath, outputPath] = process.argv.slice(2);
const value = JSON.parse(fs.readFileSync(metadataPath, "utf8"));
if (value.platform !== "ios-simulator") throw new Error("metadata platform mismatch");
const e = process.env;
const exact = {
  sha: String(value.artifact_sha256 || ""),
  source: String(value.source_commit || ""),
  bundle: String(value.bundle_id || ""),
  version: String(value.version || ""),
  code: String(value.version_code ?? ""),
  run: String(value.github_run_id || ""),
  size: String(value.artifact_size ?? ""),
};
for (const [name, actual, expected] of [
  ["artifact sha", exact.sha, e.ACTUAL_SHA], ["artifact size", exact.size, e.ACTUAL_SIZE],
  ["build run", exact.run, e.APP_RUN_ID], ["expected sha", exact.sha, e.EXPECTED_SHA],
  ["source", exact.source, e.EXPECTED_SOURCE], ["bundle", exact.bundle, e.EXPECTED_BUNDLE],
  ["version", exact.version, e.EXPECTED_VERSION], ["version code", exact.code, e.EXPECTED_CODE],
]) {
  if (expected && actual !== expected) throw new Error(`${name} mismatch: expected=${expected} actual=${actual}`);
}
if (!/^[0-9a-f]{64}$/.test(exact.sha) || !/^[0-9a-f]{40}$/.test(exact.source)) throw new Error("invalid verified digest/source shape");
for (const field of [exact.bundle, exact.version, exact.code]) if (!field || /[\r\n]/.test(field)) throw new Error("invalid verified metadata field");
fs.writeFileSync(outputPath, [
  `VERIFIED_ARTIFACT_SHA256=${exact.sha}`, `VERIFIED_ARTIFACT_SOURCE_COMMIT=${exact.source}`,
  `VERIFIED_ARTIFACT_BUILD_RUN_ID=${exact.run}`, `VERIFIED_ARTIFACT_VERSION=${exact.version}`,
  `VERIFIED_ARTIFACT_VERSION_CODE=${exact.code}`, `VERIFIED_ARTIFACT_PACKAGE_ID=${exact.bundle}`,
  `STAMP_VERIFIED_ARTIFACT_SHA256=${exact.sha}`, `STAMP_VERIFIED_SOURCE_COMMIT=${exact.source}`,
  `STAMP_VERIFIED_APP_VERSION=${exact.version}`, `STAMP_VERIFIED_VERSION_CODE=${exact.code}`,
  `STAMP_VERIFIED_PACKAGE_ID=${exact.bundle}`,
].join("\n") + "\n");
NODE

"$DITTO_BIN" -x -k "$ZIP_PATH" "$WORK/extracted"

PINNED_APP=$(find "$WORK/extracted" -maxdepth 1 -name '*.app' -print -quit)
TARGET_APP=$(find "$PRODUCTS_DIR" -maxdepth 1 -name '*.app' ! -name '*Runner*' -print -quit)
test -n "$PINNED_APP" && test -f "$PINNED_APP/Info.plist"
test -n "$TARGET_APP" && test -d "$TARGET_APP"

read_plist() { "$PLIST_BUDDY" -c "Print $1" "$PINNED_APP/Info.plist"; }
BUNDLE=$(read_plist CFBundleIdentifier)
VERSION=$(read_plist CFBundleShortVersionString)
CODE=$(read_plist CFBundleVersion)
ACTUAL_BUNDLE="$BUNDLE" ACTUAL_VERSION="$VERSION" ACTUAL_CODE="$CODE" node - "$METADATA_PATH" <<'NODE'
const fs = require("fs");
const value = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const pairs = [
  ["bundle", String(value.bundle_id || ""), process.env.ACTUAL_BUNDLE],
  ["version", String(value.version || ""), process.env.ACTUAL_VERSION],
  ["version code", String(value.version_code ?? ""), process.env.ACTUAL_CODE],
];
for (const [name, metadata, plist] of pairs) {
  if (metadata !== plist) throw new Error(`metadata/plist ${name} mismatch: metadata=${metadata} plist=${plist}`);
}
NODE
[ -z "$EXPECTED_BUNDLE" ] || test "$BUNDLE" = "$EXPECTED_BUNDLE"
[ -z "$EXPECTED_VERSION" ] || test "$VERSION" = "$EXPECTED_VERSION"
[ -z "$EXPECTED_CODE" ] || test "$CODE" = "$EXPECTED_CODE"

rm -rf "$TARGET_APP"
"$DITTO_BIN" "$PINNED_APP" "$TARGET_APP"
test -f "$TARGET_APP/Info.plist"
echo "pinned iOS app ready: target=$TARGET_APP sha256=$ACTUAL_SHA bundle=$BUNDLE version=$VERSION code=$CODE"
if [ -n "${GITHUB_ENV:-}" ]; then
  cat "$VERIFIED_ENV" >> "$GITHUB_ENV"
fi
