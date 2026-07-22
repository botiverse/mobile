#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${SLOCK_RELEASE_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
REMOTE="${SLOCK_RELEASE_REMOTE:-origin}"
RAFT_CLI="${RAFT_CLI:-raft}"
HANDS_SERVICE="${HANDS_SERVICE:-hands-4cc7a2}"
HANDS_APP_SLUG="${HANDS_APP_SLUG:-raft-ios}"
VERSION=""
BUILD_NUMBER=""
SOURCE_COMMIT=""
RELEASE_BRANCH=""
HANDS_BUILD=""
HANDS_RELEASE=""
ASC_BUILD_UPLOAD=""
CONFIRM_PUSH=0

die() {
  echo "error: $*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/create_ios_release_audit_tag.sh [options]

Refreshes authoritative Hands and App Store Connect receipts, validates the
exact remote release source, and optionally pushes an immutable annotated iOS
delivery tag:

  ios-v<marketing-version>-b<build-number>

Required:
  --version <x.y.z>           CFBundleShortVersionString / marketing version.
  --build <n>                 CFBundleVersion / TestFlight build number.
  --source <40-char-sha>      Exact Hands/IPA provenance source commit.
  --release-branch <name>     Canonical remote release-X.Y.Z branch containing
                              the source commit.
  --hands-build <uuid>        Exact Hands iOS build id.
  --hands-release <uuid>      Exact Hands draft/release id.
  --asc-build-upload <uuid>   Exact App Store Connect Build Upload id.

Options:
  --remote <name>             Git remote. Default: origin.
  --confirm-push              Create and push the tag after validation.
                              Without this flag the command is check-only.
  -h, --help                  Show this help.

Authentication:
  The helper uses Raft Agent Login and the registered Hands read-only actions:
  list-apps, get-release, get-testflight-upload-status, and
  get-testflight-publish-status. Configure the selected Raft profile before
  running it. No Hands or App Store Connect credential is exported.

The command never builds, archives, uploads, distributes, or publishes an app.
USAGE
}

require_uuid() {
  local flag="$1"
  local value="$2"
  [[ "${value}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]] ||
    die "${flag} must be a UUID"
}

run_hands_action() {
  local action="$1"
  shift
  "${RAFT_CLI}" integration invoke \
    --json \
    --service "${HANDS_SERVICE}" \
    --action "${action}" \
    "$@"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "--version requires a value"
      VERSION="$2"
      shift 2
      ;;
    --build)
      [ "$#" -ge 2 ] || die "--build requires a value"
      BUILD_NUMBER="$2"
      shift 2
      ;;
    --source)
      [ "$#" -ge 2 ] || die "--source requires a value"
      SOURCE_COMMIT="$2"
      shift 2
      ;;
    --release-branch)
      [ "$#" -ge 2 ] || die "--release-branch requires a value"
      RELEASE_BRANCH="$2"
      shift 2
      ;;
    --hands-build)
      [ "$#" -ge 2 ] || die "--hands-build requires a value"
      HANDS_BUILD="$2"
      shift 2
      ;;
    --hands-release)
      [ "$#" -ge 2 ] || die "--hands-release requires a value"
      HANDS_RELEASE="$2"
      shift 2
      ;;
    --asc-build-upload)
      [ "$#" -ge 2 ] || die "--asc-build-upload requires a value"
      ASC_BUILD_UPLOAD="$2"
      shift 2
      ;;
    --remote)
      [ "$#" -ge 2 ] || die "--remote requires a value"
      REMOTE="$2"
      shift 2
      ;;
    --confirm-push)
      CONFIRM_PUSH=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  die "--version must be x.y.z"
[[ "${BUILD_NUMBER}" =~ ^[0-9]+$ ]] ||
  die "--build must be a non-negative integer"
[[ "${SOURCE_COMMIT}" =~ ^[0-9a-fA-F]{40}$ ]] ||
  die "--source must be a full 40-character Git commit id"
[[ "${RELEASE_BRANCH}" =~ ^release-[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  die "--release-branch must be a canonical release-X.Y.Z branch name"
require_uuid "--hands-build" "${HANDS_BUILD}"
require_uuid "--hands-release" "${HANDS_RELEASE}"
require_uuid "--asc-build-upload" "${ASC_BUILD_UPLOAD}"

command -v git >/dev/null 2>&1 || die "git is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
command -v "${RAFT_CLI}" >/dev/null 2>&1 || die "Raft CLI is required: ${RAFT_CLI}"
cd "${ROOT_DIR}"

REMOTE_RELEASE_REF="refs/remotes/${REMOTE}/${RELEASE_BRANCH}"
git fetch --no-tags "${REMOTE}" \
  "refs/heads/${RELEASE_BRANCH}:${REMOTE_RELEASE_REF}" \
  --prune
git rev-parse --verify --quiet "${SOURCE_COMMIT}^{commit}" >/dev/null ||
  die "source commit not found: ${SOURCE_COMMIT}"
SOURCE_COMMIT="$(git rev-parse "${SOURCE_COMMIT}^{commit}")"
RELEASE_HEAD="$(git rev-parse "${REMOTE_RELEASE_REF}^{commit}")"
git merge-base --is-ancestor "${SOURCE_COMMIT}" "${RELEASE_HEAD}" ||
  die "source ${SOURCE_COMMIT} is not contained by ${REMOTE}/${RELEASE_BRANCH} (${RELEASE_HEAD})"

TAG_NAME="ios-v${VERSION}-b${BUILD_NUMBER}"
git show-ref --verify --quiet "refs/tags/${TAG_NAME}" &&
  die "local tag already exists: ${TAG_NAME}"
REMOTE_TAG="$(git ls-remote --tags "${REMOTE}" "refs/tags/${TAG_NAME}" "refs/tags/${TAG_NAME}^{}")"
[ -z "${REMOTE_TAG}" ] ||
  die "remote tag already exists; audit tags are immutable and must not be moved: ${REMOTE}/${TAG_NAME}"

APPS_JSON="$(mktemp)"
RELEASE_JSON="$(mktemp)"
UPLOAD_JSON="$(mktemp)"
TESTFLIGHT_JSON="$(mktemp)"
TAG_MESSAGE="$(mktemp)"
trap 'rm -f "${APPS_JSON}" "${RELEASE_JSON}" "${UPLOAD_JSON}" "${TESTFLIGHT_JSON}" "${TAG_MESSAGE}"' EXIT

"${RAFT_CLI}" integration login --service "${HANDS_SERVICE}" >/dev/null
run_hands_action list-apps >"${APPS_JSON}"

HANDS_APP_ID="$({
  python3 - "${APPS_JSON}" "${HANDS_APP_SLUG}" <<'PY'
import json
import sys

path, expected_slug = sys.argv[1:]


def load_action_result(source_path, label):
    with open(source_path, encoding="utf-8") as source:
        payload = json.load(source)
    if not isinstance(payload, dict) or payload.get("ok") is not True:
        raise SystemExit(f"{label} action failed")
    data = payload.get("data")
    if not isinstance(data, dict) or data.get("status") != 200:
        raise SystemExit(f"{label} action did not return HTTP 200")
    result = data.get("result")
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except json.JSONDecodeError as error:
            raise SystemExit(f"{label} result is not valid JSON: {error}") from error
    if not isinstance(result, dict):
        raise SystemExit(f"{label} result is missing or not an object")
    return result


result = load_action_result(path, "Hands list-apps")
apps = result.get("apps")
if not isinstance(apps, list):
    raise SystemExit("Hands list-apps result is missing apps")
matches = [app for app in apps if isinstance(app, dict) and app.get("slug") == expected_slug]
if len(matches) != 1:
    raise SystemExit(f"expected one Hands app with slug {expected_slug!r}, saw {len(matches)}")
app_id = str(matches[0].get("id") or "")
if not app_id:
    raise SystemExit("Hands app id is missing")
print(app_id)
PY
} 2>&1)" || die "authoritative Hands app resolution failed: ${HANDS_APP_ID}"

run_hands_action get-release \
  --param "app_id=${HANDS_APP_ID}" \
  --param "release_id=${HANDS_RELEASE}" >"${RELEASE_JSON}"
run_hands_action get-testflight-upload-status \
  --param "app_id=${HANDS_APP_ID}" \
  --param "build_upload_id=${ASC_BUILD_UPLOAD}" >"${UPLOAD_JSON}"
run_hands_action get-testflight-publish-status \
  --param "app_id=${HANDS_APP_ID}" \
  --param "build_id=${HANDS_BUILD}" >"${TESTFLIGHT_JSON}"

VERIFIED="$({
  python3 - \
    "${RELEASE_JSON}" \
    "${UPLOAD_JSON}" \
    "${TESTFLIGHT_JSON}" \
    "${HANDS_APP_ID}" \
    "${HANDS_RELEASE}" \
    "${HANDS_BUILD}" \
    "${VERSION}" \
    "${BUILD_NUMBER}" \
    "${SOURCE_COMMIT}" \
    "${RELEASE_BRANCH}" \
    "${ASC_BUILD_UPLOAD}" <<'PY'
import json
import sys
import uuid

(
    release_path,
    upload_path,
    status_path,
    expected_app_id,
    expected_release_id,
    expected_build_id,
    expected_version,
    expected_build_number,
    expected_source,
    expected_branch,
    expected_upload_id,
) = sys.argv[1:]


def load_action_result(path, label):
    with open(path, encoding="utf-8") as source:
        payload = json.load(source)
    if not isinstance(payload, dict) or payload.get("ok") is not True:
        raise SystemExit(f"{label} action failed")
    data = payload.get("data")
    if not isinstance(data, dict) or data.get("status") != 200:
        raise SystemExit(f"{label} action did not return HTTP 200")
    result = data.get("result")
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except json.JSONDecodeError as error:
            raise SystemExit(f"{label} result is not valid JSON: {error}") from error
    if not isinstance(result, dict):
        raise SystemExit(f"{label} result is missing or not an object")
    return result


def decode(value, label):
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError as error:
            raise SystemExit(f"{label} is not valid JSON: {error}") from error
        if isinstance(decoded, dict):
            return decoded
    raise SystemExit(f"{label} is missing or not an object")


def require(actual, expected, label):
    if str(actual) != str(expected):
        raise SystemExit(f"{label} mismatch: expected {expected!r}, got {actual!r}")


release_receipt = load_action_result(release_path, "Hands get-release")
release = release_receipt.get("release")
build = release_receipt.get("build")
assets = release_receipt.get("assets")
if not isinstance(release, dict) or not isinstance(build, dict) or not isinstance(assets, list):
    raise SystemExit("Hands release receipt must contain release, build, and assets")

require(release.get("id"), expected_release_id, "Hands release id")
require(release.get("app_id"), expected_app_id, "Hands release app id")
require(release.get("build_id"), expected_build_id, "Hands release build id")
require(release.get("product_type"), "ios-ipa", "Hands release product type")
if release.get("status") not in {"draft", "active"}:
    raise SystemExit(f"Hands release status is not taggable: {release.get('status')!r}")
require(build.get("id"), expected_build_id, "Hands build id")
require(build.get("app_id"), expected_app_id, "Hands build app id")
require(build.get("product_type"), "ios-ipa", "Hands build product type")
require(build.get("status"), "succeeded", "Hands build status")
require(build.get("version_name"), expected_version, "Hands version")
require(build.get("version_code"), expected_build_number, "Hands build number")

provenance = decode(build.get("provenance_json"), "Hands build provenance_json")
metadata = decode(build.get("build_metadata_json"), "Hands build build_metadata_json")
require(provenance.get("source_commit"), expected_source, "Hands provenance source")
require(provenance.get("source_branch"), expected_branch, "Hands provenance branch")
require(metadata.get("source_commit"), expected_source, "Hands metadata source")
require(metadata.get("source_branch"), expected_branch, "Hands metadata branch")
require(metadata.get("bundle_id"), "build.raft.app", "Hands bundle id")
require(metadata.get("version_name"), expected_version, "Hands metadata version")
require(metadata.get("version_code"), expected_build_number, "Hands metadata build number")
ios_metadata = metadata.get("ios")
if not isinstance(ios_metadata, dict) or ios_metadata.get("signed") is not True:
    raise SystemExit("Hands build metadata does not attest a signed iOS artifact")

ipa_assets = [
    asset
    for asset in assets
    if isinstance(asset, dict)
    and asset.get("artifact_kind") == "installable"
    and asset.get("platform") == "ios"
    and asset.get("filetype") == "ipa"
]
if len(ipa_assets) != 1:
    raise SystemExit(f"expected one signed IPA asset, saw {len(ipa_assets)}")
ipa_asset = ipa_assets[0]
require(ipa_asset.get("build_id"), expected_build_id, "Hands IPA build id")
ipa_asset_metadata = decode(ipa_asset.get("metadata_json"), "IPA asset metadata_json")
if ipa_asset_metadata.get("signed") is not True:
    raise SystemExit("Hands IPA asset is not marked signed")
ipa_sha256 = str(ipa_asset.get("file_hash") or "").lower()
if len(ipa_sha256) != 64 or any(character not in "0123456789abcdef" for character in ipa_sha256):
    raise SystemExit("Hands IPA asset SHA-256 is invalid")
metadata_ipa = metadata.get("ipa")
if not isinstance(metadata_ipa, dict):
    raise SystemExit("Hands build metadata is missing ipa identity")
require(str(metadata_ipa.get("sha256") or "").lower(), ipa_sha256, "Hands IPA SHA-256")

upload = load_action_result(upload_path, "Hands get-testflight-upload-status")
require(upload.get("build_upload_id"), expected_upload_id, "ASC Build Upload id")
require(upload.get("version"), expected_version, "ASC upload version")
require(upload.get("build_number"), expected_build_number, "ASC upload build number")
upload_state = upload.get("state")
if not isinstance(upload_state, dict):
    raise SystemExit("ASC Build Upload state is missing")
require(upload_state.get("state"), "COMPLETE", "ASC Build Upload state")
for field in ("errors", "warnings", "infos"):
    messages = upload_state.get(field) or []
    if not isinstance(messages, list):
        raise SystemExit(f"ASC Build Upload {field} is not a list")
    if messages:
        raise SystemExit(f"ASC Build Upload contains {len(messages)} {field}")

status = load_action_result(status_path, "Hands get-testflight-publish-status")
require(status.get("hands_build_id"), expected_build_id, "TestFlight Hands build id")
require(status.get("bundle_id"), "build.raft.app", "TestFlight bundle id")
require(status.get("version"), expected_version, "TestFlight version")
require(status.get("build_number"), expected_build_number, "TestFlight build number")
require(status.get("processing_state"), "VALID", "ASC processing state")
if status.get("expired") is not False:
    raise SystemExit(f"ASC build expiration state is not false: {status.get('expired')!r}")
asc_build_id = str(status.get("asc_build_id") or "")
try:
    uuid.UUID(asc_build_id)
except (ValueError, AttributeError) as error:
    raise SystemExit("ASC processed build id is missing or invalid") from error

print(
    "\t".join(
        [
            ipa_sha256,
            str(release.get("status")),
            str(upload_state.get("state")),
            asc_build_id,
            str(status.get("processing_state")),
        ]
    )
)
PY
} 2>&1)" || die "authoritative Hands/App Store Connect receipt validation failed: ${VERIFIED}"

IFS=$'\t' read -r IPA_SHA256 HANDS_RELEASE_STATUS ASC_UPLOAD_STATE ASC_BUILD_ID ASC_PROCESSING_STATE <<<"${VERIFIED}"
[ -n "${IPA_SHA256}" ] || die "authoritative receipt did not return an IPA SHA-256"

echo "iOS release audit tag validated"
echo "  tag: ${TAG_NAME}"
echo "  source: ${SOURCE_COMMIT}"
echo "  remote release: ${REMOTE}/${RELEASE_BRANCH}@${RELEASE_HEAD}"
echo "  version/build: ${VERSION} (${BUILD_NUMBER})"
echo "  Hands app/build/release: ${HANDS_APP_ID} / ${HANDS_BUILD} / ${HANDS_RELEASE} (${HANDS_RELEASE_STATUS})"
echo "  signed IPA SHA-256: ${IPA_SHA256}"
echo "  ASC Build Upload/state: ${ASC_BUILD_UPLOAD} / ${ASC_UPLOAD_STATE}"
echo "  ASC processed build/state: ${ASC_BUILD_ID} / ${ASC_PROCESSING_STATE}"

if [ "${CONFIRM_PUSH}" != "1" ]; then
  echo "  mode: check-only (rerun with --confirm-push to create the remote tag)"
  exit 0
fi

TAGGER_NAME="${GIT_COMMITTER_NAME:-$(git config user.name || true)}"
TAGGER_EMAIL="${GIT_COMMITTER_EMAIL:-$(git config user.email || true)}"
[ -n "${TAGGER_NAME}" ] || die "Git tagger name is not configured"
[ -n "${TAGGER_EMAIL}" ] || die "Git tagger email is not configured"

cat >"${TAG_MESSAGE}" <<TAGMSG
Raft iOS TestFlight ${VERSION} (${BUILD_NUMBER})

Source-Commit: ${SOURCE_COMMIT}
Source-Branch: ${RELEASE_BRANCH}
Bundle-ID: build.raft.app
Hands-Build: ${HANDS_BUILD}
Hands-Release: ${HANDS_RELEASE}
Hands-Release-State: ${HANDS_RELEASE_STATUS}
IPA-SHA256: ${IPA_SHA256}
ASC-Build-Upload: ${ASC_BUILD_UPLOAD}
ASC-Upload-State: ${ASC_UPLOAD_STATE}
ASC-Build-ID: ${ASC_BUILD_ID}
ASC-Processing-State: ${ASC_PROCESSING_STATE}

Post-delivery audit tag. It does not trigger a build, assign TestFlight groups,
notify testers, activate a Hands release, or publish to the App Store.
TAGMSG

git tag -a "${TAG_NAME}" "${SOURCE_COMMIT}" -F "${TAG_MESSAGE}"
LOCAL_TARGET="$(git rev-parse "${TAG_NAME}^{}")"
[ "${LOCAL_TARGET}" = "${SOURCE_COMMIT}" ] ||
  die "local tag target mismatch: expected ${SOURCE_COMMIT}, got ${LOCAL_TARGET}"

git push "${REMOTE}" "refs/tags/${TAG_NAME}"
REMOTE_TARGET="$(git ls-remote --tags "${REMOTE}" "refs/tags/${TAG_NAME}^{}" | awk '{print $1}')"
[ "${REMOTE_TARGET}" = "${SOURCE_COMMIT}" ] ||
  die "remote peeled tag mismatch: expected ${SOURCE_COMMIT}, got ${REMOTE_TARGET:-none}"

echo "iOS release audit tag pushed"
echo "  remote/tag: ${REMOTE} ${TAG_NAME}"
echo "  peeled target: ${REMOTE_TARGET}"
