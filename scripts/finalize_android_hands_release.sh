#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HANDS_CLI="${HANDS_CLI:-hands}"
HANDS_CLI_PACKAGE="${HANDS_CLI_PACKAGE:-@botiverse/hands-cli@0.5.17}"
HANDS_NPM_REGISTRY="${HANDS_NPM_REGISTRY:-https://registry.npmjs.org/}"
HANDS_API="${HANDS_API:-https://hands.build}"
HANDS_APP_SLUG="${HANDS_APP_SLUG:-raft-android}"
HANDS_ARCH="${SLOCK_ANDROID_ARCH:-arm64-v8a}"
CHANNEL="${HANDS_CHANNEL:-${SLOCK_HANDS_CHANNEL:-main}}"
RELEASE_ID=""
VERSION=""
VERSION_BUILD=0
ZH_NOTES=""
EN_NOTES=""
JA_NOTES=""
CONFIRM_PUBLISH=0

die() {
  echo "error: $*" >&2
  exit 64
}

usage() {
  cat <<'USAGE'
Usage: scripts/finalize_android_hands_release.sh [options]

Writes the reviewed zh-CN, English, and Japanese notes to an existing Hands
Android draft, then
activates it only when --confirm-publish is supplied. This is the final owner-
approved step of docs/android-release-sop.md; it never builds an APK.

Required:
  --release <id>          Hands draft release id.
  --version <x.y.z>       Expected candidate semantic version.
  --zh-notes <path>       Reviewed zh-CN release notes.
  --en-notes <path>       Reviewed English release notes.
  --ja-notes <path>       Reviewed Japanese release notes.
  --confirm-publish       Explicit acknowledgement that the owner approved it.

Options:
  --build <n>             versionCode build component. Default: 0.
  --channel <slug>        Hands channel. Default: main.
  -h, --help              Show this help.
USAGE
}

run_hands() {
  if command -v "${HANDS_CLI}" >/dev/null 2>&1; then
    "${HANDS_CLI}" "$@"
  elif command -v npm >/dev/null 2>&1; then
    npm exec --yes --registry "${HANDS_NPM_REGISTRY}" --package "${HANDS_CLI_PACKAGE}" -- hands "$@"
  else
    die "Hands CLI or npm is required"
  fi
}

version_code() {
  local major minor patch
  IFS='.' read -r major minor patch <<<"${VERSION}"
  [[ "${major}" =~ ^[0-9]+$ && "${minor}" =~ ^[0-9]+$ && "${patch}" =~ ^[0-9]+$ ]] ||
    die "--version must be x.y.z"
  [[ "${VERSION_BUILD}" =~ ^[0-9]+$ ]] || die "--build must be a non-negative integer"
  scripts/ci/version_code.sh "${major}" "${minor}" "${patch}" "${VERSION_BUILD}"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --release)
      [ "$#" -ge 2 ] || die "--release requires a value"
      RELEASE_ID="$2"
      shift 2
      ;;
    --version)
      [ "$#" -ge 2 ] || die "--version requires a value"
      VERSION="$2"
      shift 2
      ;;
    --build)
      [ "$#" -ge 2 ] || die "--build requires a value"
      VERSION_BUILD="$2"
      shift 2
      ;;
    --channel)
      [ "$#" -ge 2 ] || die "--channel requires a value"
      CHANNEL="$2"
      shift 2
      ;;
    --zh-notes)
      [ "$#" -ge 2 ] || die "--zh-notes requires a path"
      ZH_NOTES="$2"
      shift 2
      ;;
    --en-notes)
      [ "$#" -ge 2 ] || die "--en-notes requires a path"
      EN_NOTES="$2"
      shift 2
      ;;
    --ja-notes)
      [ "$#" -ge 2 ] || die "--ja-notes requires a path"
      JA_NOTES="$2"
      shift 2
      ;;
    --confirm-publish)
      CONFIRM_PUBLISH=1
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

[ -n "${RELEASE_ID}" ] || die "--release is required"
[ -n "${VERSION}" ] || die "--version is required"
[ -s "${ZH_NOTES}" ] || die "--zh-notes must name a non-empty file"
[ -s "${EN_NOTES}" ] || die "--en-notes must name a non-empty file"
[ -s "${JA_NOTES}" ] || die "--ja-notes must name a non-empty file"
[ "${CONFIRM_PUBLISH}" = "1" ] || die "--confirm-publish is required; keep the draft until the owner explicitly approves activation"

EXPECTED_CODE="$(version_code)"
RELEASE_JSON="$(mktemp)"
PUBLIC_JSON="$(mktemp)"
trap 'rm -f "${RELEASE_JSON}" "${PUBLIC_JSON}"' EXIT

run_hands whoami
# `hands releases show --json` prints human text on CLI 0.5.17, so read the
# release through the raw API instead. Resolve the app slug to its id first.
APPS_JSON="$(mktemp)"
trap 'rm -f "${RELEASE_JSON}" "${PUBLIC_JSON}" "${APPS_JSON}"' EXIT
run_hands api GET /api/apps --json >"${APPS_JSON}"
APP_ID="$(python3 - "${APPS_JSON}" "${HANDS_APP_SLUG}" <<'PY'
import json
import sys

path, slug = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as source:
    payload = json.load(source)
matches = [app["id"] for app in payload.get("apps", []) if app.get("slug") == slug]
if len(matches) != 1:
    raise SystemExit(f"expected exactly one Hands app with slug {slug}; saw {len(matches)}")
print(matches[0])
PY
)"
run_hands api GET "/api/apps/${APP_ID}/releases/${RELEASE_ID}" --json >"${RELEASE_JSON}"
python3 - "${RELEASE_JSON}" "${EXPECTED_CODE}" <<'PY'
import json
import sys

path, expected = sys.argv[1], int(sys.argv[2])
with open(path, encoding="utf-8") as source:
    payload = json.load(source)

codes = []
statuses = []
def visit(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"version_code", "versionCode"} and str(child).isdigit():
                codes.append(int(child))
            if key in {"status", "release_status", "releaseStatus"} and isinstance(child, str):
                statuses.append(child.lower())
            visit(child)
    elif isinstance(value, list):
        for child in value:
            visit(child)
visit(payload)
if expected not in codes:
    raise SystemExit(f"Hands draft does not report expected versionCode {expected}; saw {codes or 'none'}")
if statuses and "draft" not in statuses:
    raise SystemExit(f"Hands release is not a draft; saw statuses {statuses}")
PY

run_hands releases update "${HANDS_APP_SLUG}" "${RELEASE_ID}" \
  --changelog-file "zh-CN=${ZH_NOTES}" \
  --changelog-file "en=${EN_NOTES}" \
  --changelog-file "ja=${JA_NOTES}"
run_hands releases publish "${HANDS_APP_SLUG}" "${RELEASE_ID}"

curl --fail --silent --show-error \
  --get "${HANDS_API}/public/v2/apps/${HANDS_APP_SLUG}/updates/check" \
  --data-urlencode "channel=${CHANNEL}" \
  --data-urlencode "product_type=android-apk" \
  --data-urlencode "current_version_code=0" \
  --data-urlencode "platform=android" \
  --data-urlencode "arch=${HANDS_ARCH}" \
  --data-urlencode "filetype=apk" >"${PUBLIC_JSON}"

python3 - "${PUBLIC_JSON}" "${EXPECTED_CODE}" <<'PY'
import json
import sys

path, expected = sys.argv[1], int(sys.argv[2])
with open(path, encoding="utf-8") as source:
    payload = json.load(source)

keys = {"version_code", "versionCode", "latest_version_code", "latestVersionCode"}
codes = []
def visit(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in keys and str(child).isdigit():
                codes.append(int(child))
            visit(child)
    elif isinstance(value, list):
        for child in value:
            visit(child)
visit(payload)
if max(codes, default=-1) != expected:
    raise SystemExit(f"public Hands latest versionCode is {max(codes, default='missing')}, expected {expected}")
PY

echo "Hands release ${RELEASE_ID} published and public update check reports ${EXPECTED_CODE}."
