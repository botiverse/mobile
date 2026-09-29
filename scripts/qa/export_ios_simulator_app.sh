#!/usr/bin/env bash
# Export a built iOS Simulator .app as the frozen-carrier artifact that
# qa-acceptance-ios.yml can pin through its `app_run_id` input.
#
# task #247 (task #232 follow-up): ios-simulator-app.yml published the zip but
# only a free-form PROVENANCE.txt next to it, so `app_run_id=<its run>` failed
# closed in the consumer — scripts/qa/prepare_pinned_ios_app.sh requires a
# metadata.json of an EXACT shape. This script is the single producer of that
# shape; both workflows' exports must stay byte-for-byte compatible with what
# prepare_pinned_ios_app.sh validates: platform "ios-simulator",
# artifact_sha256/artifact_size of the zip, 40-hex source_commit,
# github_run_id (must equal the run the consumer pins), bundle_id, version,
# version_code. Extra fields (hands_build_commit, binary_sha256,
# dt_platform_name) are informational.
#
# Usage: export_ios_simulator_app.sh <products-dir> <output-dir> <source-commit> <run-id>
# Overrides for tests: PLIST_BUDDY, DITTO_BIN, SHASUM_BIN.
set -euo pipefail

PRODUCTS_DIR="${1:?DerivedData products directory required}"
OUTPUT_DIR="${2:?output directory required}"
SOURCE_COMMIT="${3:?source commit required}"
RUN_ID="${4:?run id required}"
PLIST_BUDDY="${PLIST_BUDDY:-/usr/libexec/PlistBuddy}"
DITTO_BIN="${DITTO_BIN:-ditto}"

case "$SOURCE_COMMIT" in
  ????????????????????????????????????????) ;;
  *) echo "source commit must be a full 40-character sha (got: '$SOURCE_COMMIT')" >&2; exit 2 ;;
esac
case "$SOURCE_COMMIT" in *[!0-9a-f]*) echo "source commit must be lowercase hex" >&2; exit 2 ;; esac

app=$(find "$PRODUCTS_DIR" -maxdepth 1 -name '*.app' ! -name '*Runner*' -print -quit)
[ -n "$app" ] || { echo "no .app produced under $PRODUCTS_DIR" >&2; exit 1; }
echo "app: $app"

read_plist() { "$PLIST_BUDDY" -c "Print $1" "$app/Info.plist"; }

# A device build cannot be installed on a Simulator seat, and the two are
# indistinguishable by filename — only DTPlatformName separates them. Refuse
# rather than publish an artifact whose consumer discovers it at install.
platform=$(read_plist DTPlatformName 2>/dev/null || echo "")
if [ "$platform" != "iphonesimulator" ]; then
  echo "refusing to publish a non-simulator slice: DTPlatformName=${platform:-<absent>}" >&2
  exit 2
fi

# HandsBuildCommit is stamped by an unconditional Xcode build phase
# (compose/iosApp/scripts/stamp_hands_build_metadata.sh). Empty means the phase
# did not run; the literal "unknown" means it ran but resolved nothing. Both
# are refused by asserting the required SHAPE (allowlist, fails closed); a short
# sha is rejected by policy — pinning a carrier is a full-sha operation.
hands=$(read_plist :HandsBuildCommit 2>/dev/null || echo "")
case "$hands" in
  *[!0-9a-f]* | "") hands_ok=no ;;
  ????????????????????????????????????????) hands_ok=yes ;;
  *) hands_ok=no ;;
esac
if [ "$hands_ok" != "yes" ]; then
  echo "refusing to publish a carrier without a real HandsBuildCommit (got: '${hands:-<empty>}')" >&2
  echo "expected a full 40-character lowercase hex commit" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"
zip="$OUTPUT_DIR/raft-ios-simulator.app.zip"
# ditto, not `zip -r`: a .app is a bundle with symlinks and resource forks
# that plain zip flattens into an uninstallable archive.
"$DITTO_BIN" -c -k --sequesterRsrc --keepParent "$app" "$zip"

if command -v shasum >/dev/null 2>&1; then
  sha=$(shasum -a 256 "$zip" | awk '{print $1}')
else
  sha=$(sha256sum "$zip" | awk '{print $1}')
fi
if [ "$(uname -s)" = "Darwin" ]; then
  size=$(stat -f '%z' "$zip")
else
  size=$(stat -c '%s' "$zip")
fi
printf '%s  %s\n' "$sha" "$(basename "$zip")" > "$zip.sha256"

bundle_id=$(read_plist CFBundleIdentifier)
version=$(read_plist CFBundleShortVersionString)
code=$(read_plist CFBundleVersion)
binary=$(read_plist CFBundleExecutable)
if command -v shasum >/dev/null 2>&1; then
  binsha=$(shasum -a 256 "$app/$binary" | awk '{print $1}')
else
  binsha=$(sha256sum "$app/$binary" | awk '{print $1}')
fi
for field in "$bundle_id" "$version" "$code"; do
  case "$field" in
    "" | *['"\\']* | *$'\n'* | *$'\r'*) echo "refusing to render an unsafe plist field into JSON: '$field'" >&2; exit 2 ;;
  esac
done

printf '{\n  "platform": "ios-simulator",\n  "artifact_sha256": "%s",\n  "artifact_size": %s,\n  "source_commit": "%s",\n  "github_run_id": "%s",\n  "bundle_id": "%s",\n  "version": "%s",\n  "version_code": "%s",\n  "hands_build_commit": "%s",\n  "binary_sha256": "%s",\n  "dt_platform_name": "%s"\n}\n' \
  "$sha" "$size" "$SOURCE_COMMIT" "$RUN_ID" "$bundle_id" "$version" "$code" \
  "$hands" "$binsha" "$platform" > "$OUTPUT_DIR/metadata.json"
# Hand-rendered JSON: parse it next to its cause, not one run later in the consumer.
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$OUTPUT_DIR/metadata.json"
cat "$OUTPUT_DIR/metadata.json"
echo "export ready: zip=$zip sha256=$sha size=$size run=$RUN_ID"
