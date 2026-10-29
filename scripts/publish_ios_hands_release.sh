#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HANDS_CLI="${HANDS_CLI:-hands}"
HANDS_CLI_PACKAGE="${HANDS_CLI_PACKAGE:-@botiverse/hands-cli@0.5.35}"
APP_SLUG="${HANDS_APP_SLUG:-raft-ios}"
CHANNEL="${HANDS_CHANNEL:-main}"
IPA_PATH="${SLOCK_IOS_IPA_PATH:-}"
DSYM_PATH="${SLOCK_IOS_DSYM_PATH:-}"
VERSION_NAME="${RAFT_IOS_VERSION_NAME:-}"
VERSION_CODE="${RAFT_IOS_VERSION_CODE:-}"
EXPORT_METHOD="${SLOCK_IOS_EXPORT_METHOD:-app-store-connect}"
DRAFT="${SLOCK_HANDS_DRAFT:-1}"
RELEASE_TYPE="${SLOCK_HANDS_RELEASE_TYPE:-stable}"
APPSTORE_BUILD_NUMBER="${SLOCK_IOS_APPSTORE_BUILD_NUMBER:-}"
TESTFLIGHT_STATUS="${SLOCK_IOS_TESTFLIGHT_STATUS:-}"
DRY_RUN="${SLOCK_HANDS_DRY_RUN:-0}"
# Optional toolchain record from ios-release.yml (scripts/ci/record_ios_toolchain.sh outputs).
XCODE_VERSION="${RAFT_IOS_XCODE_VERSION:-}"
IOS_SDK_VERSION="${RAFT_IOS_SDK_VERSION:-}"
XCODE_LANE="${RAFT_IOS_XCODE_LANE:-}"
# shellcheck source=lib/hands_retry.sh
. "${ROOT_DIR}/scripts/lib/hands_retry.sh"

die() {
  echo "error: $*" >&2
  exit 64
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_size() {
  wc -c <"$1" | tr -d '[:space:]'
}

json_string() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"
}

abs_path() {
  local path="$1"
  if [ -d "${path}" ]; then
    (cd "${path}" && pwd)
  else
    (cd "$(dirname "${path}")" && printf '%s/%s\n' "$(pwd)" "$(basename "${path}")")
  fi
}

hands_export_method() {
  case "$1" in
    app-store-connect)
      printf 'app-store\n'
      ;;
    release-testing|ad-hoc)
      printf 'ad-hoc\n'
      ;;
    debugging|development)
      printf 'development\n'
      ;;
    *)
      printf '%s\n' "$1"
      ;;
  esac
}

run_hands() {
  if [ -f "${HANDS_CLI}" ]; then
    case "${HANDS_CLI}" in
      *.js)
        node "${HANDS_CLI}" "$@"
        ;;
      *)
        "${HANDS_CLI}" "$@"
        ;;
    esac
  else
    if command -v "${HANDS_CLI}" >/dev/null 2>&1; then
      "${HANDS_CLI}" "$@"
    elif command -v npm >/dev/null 2>&1; then
      npm exec --yes --package "${HANDS_CLI_PACKAGE}" -- hands "$@"
    else
      die "Hands CLI not found: ${HANDS_CLI}; install with: npm install -g @botiverse/hands-cli"
    fi
  fi
}

usage() {
  cat <<'USAGE'
Usage: scripts/publish_ios_hands_release.sh [options]

Publishes a signed Slock iOS IPA plus dSYM archive to Hands.
The IPA must already be signed by macOS CI; Hands never receives Apple
certificates or provisioning profiles.

Environment overrides:
  HANDS_APP_SLUG=raft-ios
  HANDS_CHANNEL=main
  HANDS_API=https://hands.build
  HANDS_BEARER_TOKEN=<CI bearer token>
  HANDS_CLI=hands
  HANDS_CLI_PACKAGE=@botiverse/hands-cli@0.5.35
  SLOCK_IOS_IPA_PATH=dist/ios/iosApp.ipa
  SLOCK_IOS_DSYM_PATH=dist/ios/iosApp.dSYM.zip
  RAFT_IOS_VERSION_NAME=<versionName>
  RAFT_IOS_VERSION_CODE=<versionCode>
  SLOCK_IOS_EXPORT_METHOD=app-store-connect
  SLOCK_HANDS_RELEASE_TYPE=stable
  SLOCK_HANDS_DRAFT=1
  SLOCK_IOS_APPSTORE_BUILD_NUMBER=<buildNumber>
  SLOCK_IOS_TESTFLIGHT_STATUS=uploaded
  RAFT_IOS_XCODE_VERSION=<"27.1 (27A9275)">
  RAFT_IOS_SDK_VERSION=<iPhoneOS SDK>
  RAFT_IOS_XCODE_LANE=default|opt-in
  SLOCK_RELEASE_CHANGELOG_ZH_FILE=change-logs/1.6.0/ios/zh-CN.md
  SLOCK_RELEASE_CHANGELOG_EN_FILE=change-logs/1.6.0/ios/en.md
  SLOCK_RELEASE_CHANGELOG_JA_FILE=change-logs/1.6.0/ios/ja.md
  SLOCK_RELEASE_CHANGELOG_FILE=changelog.txt
  SLOCK_HANDS_DRY_RUN=1

Options:
  --channel <slug>          Hands channel. Default: $HANDS_CHANNEL or main.
  --app <slug>              Hands app slug. Default: raft-ios.
  --ipa <path>              Signed .ipa path.
  --dsym <path>             .dSYM.zip path.
  --version-name <value>    CFBundleShortVersionString.
  --version-code <value>    CFBundleVersion / release version_code.
  --export-method <value>   Xcode export method.
  --release-type <value>    Hands release type. Default: stable.
  --draft                   Create a draft release. Default unless SLOCK_HANDS_DRAFT=0.
  --publish                 Do not pass --draft.
  --dry-run                 Collect and print metadata without publishing.
  -h, --help                Show this help.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --channel)
      [ "$#" -ge 2 ] || die "--channel requires value"
      CHANNEL="$2"
      shift 2
      ;;
    --app)
      [ "$#" -ge 2 ] || die "--app requires value"
      APP_SLUG="$2"
      shift 2
      ;;
    --ipa)
      [ "$#" -ge 2 ] || die "--ipa requires value"
      IPA_PATH="$2"
      shift 2
      ;;
    --dsym)
      [ "$#" -ge 2 ] || die "--dsym requires value"
      DSYM_PATH="$2"
      shift 2
      ;;
    --version-name)
      [ "$#" -ge 2 ] || die "--version-name requires value"
      VERSION_NAME="$2"
      shift 2
      ;;
    --version-code)
      [ "$#" -ge 2 ] || die "--version-code requires value"
      VERSION_CODE="$2"
      shift 2
      ;;
    --export-method)
      [ "$#" -ge 2 ] || die "--export-method requires value"
      EXPORT_METHOD="$2"
      shift 2
      ;;
    --release-type)
      [ "$#" -ge 2 ] || die "--release-type requires value"
      RELEASE_TYPE="$2"
      shift 2
      ;;
    --draft)
      DRAFT=1
      shift
      ;;
    --publish)
      DRAFT=0
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[ -n "${IPA_PATH}" ] || die "IPA path is required"
[ -n "${DSYM_PATH}" ] || die "dSYM zip path is required"
[ -n "${VERSION_NAME}" ] || die "version name is required"
[ -n "${VERSION_CODE}" ] || die "version code is required"
[ -f "${IPA_PATH}" ] || die "IPA not found: ${IPA_PATH}"
[ -f "${DSYM_PATH}" ] || die "dSYM zip not found: ${DSYM_PATH}"

# CFBundleIdentifier straight from the signed IPA (Payload/*.app/Info.plist is
# a binary plist; python3 plistlib reads it natively). The TestFlight upload
# lane uses this to locate the App Store Connect app record, so it must be
# what the IPA actually carries — not a passed-in guess.
BUNDLE_ID="$(python3 - "${IPA_PATH}" <<'PY'
import plistlib, re, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    names = [n for n in z.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", n)]
    if not names:
        raise SystemExit("no Payload/*.app/Info.plist in IPA")
    with z.open(names[0]) as f:
        print(plistlib.load(f).get("CFBundleIdentifier", ""))
PY
)" || BUNDLE_ID=""
if [ -z "${BUNDLE_ID}" ]; then
  BUNDLE_ID="${SLOCK_IOS_BUNDLE_ID:-}"
fi
[ -n "${BUNDLE_ID}" ] || die "could not read CFBundleIdentifier from IPA (set SLOCK_IOS_BUNDLE_ID to override)"

GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
GIT_SHORT="$(git -C "${ROOT_DIR}" rev-parse --short=8 HEAD)"
GIT_BRANCH="$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD)"
BUILD_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
CI_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-botiverse/mobile}/actions/runs/${GITHUB_RUN_ID:-local}"
HANDS_EXPORT_METHOD="$(hands_export_method "${EXPORT_METHOD}")"
if [ "${DRAFT}" = "1" ]; then
  DRAFT_JSON=true
else
  DRAFT_JSON=false
fi
ARTIFACT_DIR="${ROOT_DIR}/artifacts/ios-hands/${VERSION_NAME}-${VERSION_CODE}-${GIT_SHORT}"
METADATA_PATH="${ARTIFACT_DIR}/build-metadata.json"
mkdir -p "${ARTIFACT_DIR}"

{
  printf '{\n'
  printf '  "app_slug": %s,\n' "$(json_string "${APP_SLUG}")"
  printf '  "channel": %s,\n' "$(json_string "${CHANNEL}")"
  printf '  "product_type": "ios-ipa",\n'
  printf '  "platform": "ios",\n'
  printf '  "bundle_id": %s,\n' "$(json_string "${BUNDLE_ID}")"
  printf '  "version_name": %s,\n' "$(json_string "${VERSION_NAME}")"
  printf '  "version_code": %s,\n' "$(json_string "${VERSION_CODE}")"
  printf '  "release_type": %s,\n' "$(json_string "${RELEASE_TYPE}")"
  printf '  "draft": %s,\n' "${DRAFT_JSON}"
  printf '  "xcode_export_method": %s,\n' "$(json_string "${EXPORT_METHOD}")"
  printf '  "export_method": %s,\n' "$(json_string "${HANDS_EXPORT_METHOD}")"
  printf '  "source_commit": %s,\n' "$(json_string "${GIT_COMMIT}")"
  printf '  "source_branch": %s,\n' "$(json_string "${GIT_BRANCH}")"
  printf '  "build_time": %s,\n' "$(json_string "${BUILD_TIME}")"
  printf '  "ci_url": %s,\n' "$(json_string "${CI_URL}")"
  if [ -n "${XCODE_VERSION}${IOS_SDK_VERSION}${XCODE_LANE}" ]; then
    printf '  "xcode_version": %s,\n' "$(json_string "${XCODE_VERSION}")"
    printf '  "ios_sdk_version": %s,\n' "$(json_string "${IOS_SDK_VERSION}")"
    printf '  "xcode_lane": %s,\n' "$(json_string "${XCODE_LANE}")"
  fi
  printf '  "ipa": { "path": %s, "sha256": %s, "size_bytes": %s },\n' \
    "$(json_string "$(abs_path "${IPA_PATH}")")" \
    "$(json_string "$(sha256_file "${IPA_PATH}")")" \
    "$(file_size "${IPA_PATH}")"
  printf '  "dsym": { "path": %s, "sha256": %s, "size_bytes": %s },\n' \
    "$(json_string "$(abs_path "${DSYM_PATH}")")" \
    "$(json_string "$(sha256_file "${DSYM_PATH}")")" \
    "$(file_size "${DSYM_PATH}")"
  if [ -n "${APPSTORE_BUILD_NUMBER}" ]; then
    printf '  "appstore_build_number": %s,\n' "$(json_string "${APPSTORE_BUILD_NUMBER}")"
  else
    printf '  "appstore_build_number": null,\n'
  fi
  if [ -n "${TESTFLIGHT_STATUS}" ]; then
    printf '  "testflight_status": %s\n' "$(json_string "${TESTFLIGHT_STATUS}")"
  else
    printf '  "testflight_status": null\n'
  fi
  printf '}\n'
} >"${METADATA_PATH}"

echo "Prepared Hands iOS release artifacts:"
echo "  IPA:      ${IPA_PATH}"
echo "  dSYM:     ${DSYM_PATH}"
echo "  Bundle:   ${BUNDLE_ID}"
echo "  Metadata: ${METADATA_PATH}"

if [ "${DRY_RUN}" = "1" ]; then
  echo "Dry run: not publishing to Hands."
  exit 0
fi

if ! run_hands builds --help >/tmp/slock-hands-builds-help.txt 2>&1; then
  cat /tmp/slock-hands-builds-help.txt >&2 || true
  die "could not inspect Hands CLI builds commands"
fi
if ! grep -q "publish-ios" /tmp/slock-hands-builds-help.txt; then
  cat /tmp/slock-hands-builds-help.txt >&2 || true
  die "installed Hands CLI does not expose 'builds publish-ios' yet; keep IPA/dSYM workflow artifacts and rerun publish after task #115 lands"
fi

HANDS_ARGS=(
  builds
  publish-ios
  "${APP_SLUG}"
  --channel "${CHANNEL}"
  --ipa "${IPA_PATH}"
  --dsym "${DSYM_PATH}"
  --version-name "${VERSION_NAME}"
  --version-code "${VERSION_CODE}"
  --release-type "${RELEASE_TYPE}"
  --source-commit "${GIT_COMMIT}"
  --source-branch "${GIT_BRANCH}"
  --build-time "${BUILD_TIME}"
  --ci-url "${CI_URL}"
  --export-method "${HANDS_EXPORT_METHOD}"
  --metadata "${METADATA_PATH}"
)
if [ -n "${APPSTORE_BUILD_NUMBER}" ]; then
  HANDS_ARGS+=(--appstore-build-number "${APPSTORE_BUILD_NUMBER}")
fi
if [ -n "${TESTFLIGHT_STATUS}" ]; then
  HANDS_ARGS+=(--testflight-status "${TESTFLIGHT_STATUS}")
fi
if [ -n "${SLOCK_RELEASE_CHANGELOG_ZH_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_ZH_FILE}" ] &&
  [ -n "${SLOCK_RELEASE_CHANGELOG_EN_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_EN_FILE}" ] &&
  [ -n "${SLOCK_RELEASE_CHANGELOG_JA_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_JA_FILE}" ]; then
  HANDS_ARGS+=(
    --changelog-file "zh-CN=${SLOCK_RELEASE_CHANGELOG_ZH_FILE}"
    --changelog-file "en=${SLOCK_RELEASE_CHANGELOG_EN_FILE}"
    --changelog-file "ja=${SLOCK_RELEASE_CHANGELOG_JA_FILE}"
  )
elif [ -n "${SLOCK_RELEASE_CHANGELOG_ZH_FILE:-}" ] ||
  [ -n "${SLOCK_RELEASE_CHANGELOG_EN_FILE:-}" ] ||
  [ -n "${SLOCK_RELEASE_CHANGELOG_JA_FILE:-}" ]; then
  die "reviewed changelog variables require non-empty zh-CN, en, and ja files"
elif [ -n "${SLOCK_RELEASE_CHANGELOG_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_FILE}" ]; then
  HANDS_ARGS+=(--changelog-file "${SLOCK_RELEASE_CHANGELOG_FILE}")
fi
if [ "${DRAFT}" = "1" ]; then
  HANDS_ARGS+=(--draft)
fi

# Retries only transient 5xx / network failures; see scripts/lib/hands_retry.sh.
hands_retry run_hands "${HANDS_ARGS[@]}"
