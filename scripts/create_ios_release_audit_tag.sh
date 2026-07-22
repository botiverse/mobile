#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE="${SLOCK_RELEASE_REMOTE:-origin}"
VERSION=""
BUILD_NUMBER=""
SOURCE_COMMIT=""
SOURCE_REF=""
HANDS_BUILD=""
HANDS_RELEASE=""
IPA_SHA256=""
ASC_BUILD_UPLOAD=""
ASC_STATE=""
CONFIRM_PUSH=0

die() {
  echo "error: $*" >&2
  exit 64
}

usage() {
  cat <<'USAGE'
Usage: scripts/create_ios_release_audit_tag.sh [options]

Validates and optionally pushes an immutable annotated iOS delivery tag named
ios-v<marketing-version>-b<build-number>. The tag points at the exact source
commit recorded by the signed IPA, never at the current branch tip.

Required:
  --version <x.y.z>           CFBundleShortVersionString / marketing version.
  --build <n>                 CFBundleVersion / TestFlight build number.
  --source <40-char-sha>      Exact Hands/IPA provenance source commit.
  --source-ref <git-ref>      Ref that must contain the source commit, for
                              example origin/release-1.6.0.
  --hands-build <uuid>        Exact Hands iOS build id.
  --hands-release <uuid>      Exact Hands draft/release id.
  --ipa-sha256 <sha256>       Exact signed IPA SHA-256.
  --asc-build-upload <uuid>   App Store Connect Build Upload id.
  --asc-state COMPLETE        Terminal Apple processing state.

Options:
  --remote <name>             Git remote. Default: origin.
  --confirm-push              Create and push the tag after validation.
                              Without this flag the command is check-only.
  -h, --help                  Show this help.

The command never builds, archives, uploads, distributes, or publishes an app.
USAGE
}

require_uuid() {
  local flag="$1"
  local value="$2"
  [[ "${value}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] ||
    die "${flag} must be a UUID"
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
    --source-ref)
      [ "$#" -ge 2 ] || die "--source-ref requires a value"
      SOURCE_REF="$2"
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
    --ipa-sha256)
      [ "$#" -ge 2 ] || die "--ipa-sha256 requires a value"
      IPA_SHA256="$2"
      shift 2
      ;;
    --asc-build-upload)
      [ "$#" -ge 2 ] || die "--asc-build-upload requires a value"
      ASC_BUILD_UPLOAD="$2"
      shift 2
      ;;
    --asc-state)
      [ "$#" -ge 2 ] || die "--asc-state requires a value"
      ASC_STATE="$2"
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
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  die "--version must be x.y.z"
[[ "${BUILD_NUMBER}" =~ ^[0-9]+$ ]] ||
  die "--build must be a non-negative integer"
[[ "${SOURCE_COMMIT}" =~ ^[0-9a-fA-F]{40}$ ]] ||
  die "--source must be a full 40-character Git commit id"
[ -n "${SOURCE_REF}" ] || die "--source-ref is required"
require_uuid "--hands-build" "${HANDS_BUILD}"
require_uuid "--hands-release" "${HANDS_RELEASE}"
[[ "${IPA_SHA256}" =~ ^[0-9a-fA-F]{64}$ ]] ||
  die "--ipa-sha256 must be a 64-character SHA-256"
require_uuid "--asc-build-upload" "${ASC_BUILD_UPLOAD}"
[ "${ASC_STATE}" = "COMPLETE" ] ||
  die "--asc-state must be COMPLETE; do not tag an unprocessed or failed upload"

command -v git >/dev/null 2>&1 || die "git is required"
cd "${ROOT_DIR}"

git rev-parse --verify --quiet "${SOURCE_COMMIT}^{commit}" >/dev/null ||
  die "source commit not found: ${SOURCE_COMMIT}"
SOURCE_COMMIT="$(git rev-parse "${SOURCE_COMMIT}^{commit}")"
git rev-parse --verify --quiet "${SOURCE_REF}^{commit}" >/dev/null ||
  die "source ref not found: ${SOURCE_REF}"
SOURCE_REF_COMMIT="$(git rev-parse "${SOURCE_REF}^{commit}")"
git merge-base --is-ancestor "${SOURCE_COMMIT}" "${SOURCE_REF_COMMIT}" ||
  die "source ${SOURCE_COMMIT} is not contained by ${SOURCE_REF} (${SOURCE_REF_COMMIT})"

TAG_NAME="ios-v${VERSION}-b${BUILD_NUMBER}"
git show-ref --verify --quiet "refs/tags/${TAG_NAME}" &&
  die "local tag already exists: ${TAG_NAME}"

REMOTE_TAG="$(git ls-remote --tags "${REMOTE}" "refs/tags/${TAG_NAME}" "refs/tags/${TAG_NAME}^{}")"
[ -z "${REMOTE_TAG}" ] ||
  die "remote tag already exists; audit tags are immutable and must not be moved: ${REMOTE}/${TAG_NAME}"

echo "iOS release audit tag validated"
echo "  tag: ${TAG_NAME}"
echo "  source: ${SOURCE_COMMIT}"
echo "  contained by: ${SOURCE_REF}@${SOURCE_REF_COMMIT}"
echo "  version/build: ${VERSION} (${BUILD_NUMBER})"
echo "  Hands build/release: ${HANDS_BUILD} / ${HANDS_RELEASE}"
echo "  IPA SHA-256: ${IPA_SHA256}"
echo "  ASC Build Upload/state: ${ASC_BUILD_UPLOAD} / ${ASC_STATE}"

if [ "${CONFIRM_PUSH}" != "1" ]; then
  echo "  mode: check-only (rerun with --confirm-push to create the remote tag)"
  exit 0
fi

TAGGER_NAME="${GIT_COMMITTER_NAME:-$(git config user.name || true)}"
TAGGER_EMAIL="${GIT_COMMITTER_EMAIL:-$(git config user.email || true)}"
[ -n "${TAGGER_NAME}" ] || die "Git tagger name is not configured"
[ -n "${TAGGER_EMAIL}" ] || die "Git tagger email is not configured"

TAG_MESSAGE="$(mktemp)"
trap 'rm -f "${TAG_MESSAGE}"' EXIT
cat >"${TAG_MESSAGE}" <<TAGMSG
Raft iOS TestFlight ${VERSION} (${BUILD_NUMBER})

Source-Commit: ${SOURCE_COMMIT}
Source-Ref: ${SOURCE_REF}
Bundle-ID: build.raft.app
Hands-Build: ${HANDS_BUILD}
Hands-Release: ${HANDS_RELEASE}
IPA-SHA256: ${IPA_SHA256}
ASC-Build-Upload: ${ASC_BUILD_UPLOAD}
ASC-State: ${ASC_STATE}

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
