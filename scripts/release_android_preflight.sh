#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_REMOTE="${SLOCK_RELEASE_REMOTE:-origin}"
RELEASE_BRANCH="${SLOCK_RELEASE_BRANCH:-main}"
HANDS_API="${HANDS_API:-https://hands.build}"
HANDS_APP_SLUG="${HANDS_APP_SLUG:-raft-android}"
HANDS_ARCH="${SLOCK_ANDROID_ARCH:-arm64-v8a}"
CHANNEL="${HANDS_CHANNEL:-${SLOCK_HANDS_CHANNEL:-main}}"
VERSION=""
VERSION_BUILD=0
REPORT_PATH=""
SKIP_HANDS_VERSION_CHECK=0
REQUIRED_PRS=()

die() {
  echo "error: $*" >&2
  exit 64
}

usage() {
  cat <<'USAGE'
Usage: scripts/release_android_preflight.sh --version X.Y.Z [options]

Checks that an Android release candidate can safely enter the draft-first Hands
lane. It performs no upload, no tag push, and no release activation.

Required:
  --version <x.y.z>       Candidate semantic version.

Options:
  --build <n>             versionCode build component. Default: 0.
  --channel <slug>        Hands channel. Default: main.
  --required-pr <number>  PR selected for this release; repeatable. Each must
                          be merged and be an ancestor of the candidate.
  --report <path>         Write a Markdown preflight report.
  --skip-hands-version-check
                          Do not call the public Hands update endpoint. Only
                          valid for an offline diagnostic; never use for a
                          main-channel release.
  -h, --help              Show this help.

Environment overrides:
  SLOCK_RELEASE_REMOTE=origin
  SLOCK_RELEASE_BRANCH=main
  HANDS_API=https://hands.build
  HANDS_APP_SLUG=raft-android
  SLOCK_ANDROID_ARCH=arm64-v8a
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required"
}

version_code() {
  local major minor patch
  IFS='.' read -r major minor patch <<<"${VERSION}"
  [[ "${major}" =~ ^[0-9]+$ && "${minor}" =~ ^[0-9]+$ && "${patch}" =~ ^[0-9]+$ ]] ||
    die "--version must be x.y.z"
  [[ "${VERSION_BUILD}" =~ ^[0-9]+$ ]] || die "--build must be a non-negative integer"
  scripts/ci/version_code.sh "${major}" "${minor}" "${patch}" "${VERSION_BUILD}"
}

json_field() {
  local file="$1"
  local field="$2"
  python3 - "${file}" "${field}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)
print(payload.get(sys.argv[2], ""))
PY
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
      VERSION_BUILD="$2"
      shift 2
      ;;
    --channel)
      [ "$#" -ge 2 ] || die "--channel requires a value"
      CHANNEL="$2"
      shift 2
      ;;
    --required-pr)
      [ "$#" -ge 2 ] || die "--required-pr requires a PR number"
      REQUIRED_PRS+=("$2")
      shift 2
      ;;
    --report)
      [ "$#" -ge 2 ] || die "--report requires a path"
      REPORT_PATH="$2"
      shift 2
      ;;
    --skip-hands-version-check)
      SKIP_HANDS_VERSION_CHECK=1
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

[ -n "${VERSION}" ] || die "--version is required"
RELEASE_CODE="$(version_code)"

require_cmd git
require_cmd gh
require_cmd curl
require_cmd python3

cd "${ROOT_DIR}"
[ -f .github/workflows/android-release.yml ] || die "android release workflow not found"
[ -f compose/ohosApp/entry/oh-package.json5 ] || die "OHOS package manifest is missing"
[ -f compose/ohosApp/entry/oh-package-lock.json5 ] || die "OHOS package lock is missing"

git fetch "${RELEASE_REMOTE}" "${RELEASE_BRANCH}" --prune
REMOTE_REF="refs/remotes/${RELEASE_REMOTE}/${RELEASE_BRANCH}"
git rev-parse --verify --quiet "${REMOTE_REF}" >/dev/null ||
  die "release branch not found: ${RELEASE_REMOTE}/${RELEASE_BRANCH}"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "${CURRENT_BRANCH}" = "${RELEASE_BRANCH}" ] ||
  die "expected ${RELEASE_BRANCH}, found ${CURRENT_BRANCH}; release only from the protected branch"

HEAD_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git rev-parse "${REMOTE_REF}")"
[ "${HEAD_SHA}" = "${REMOTE_SHA}" ] ||
  die "local ${RELEASE_BRANCH} (${HEAD_SHA}) does not equal ${RELEASE_REMOTE}/${RELEASE_BRANCH} (${REMOTE_SHA})"

git diff --quiet || die "working tree has unstaged tracked changes"
git diff --cached --quiet || die "working tree has staged changes"
[ -z "$(git status --porcelain --untracked-files=normal)" ] || die "working tree has untracked or submodule changes"

scripts/ci/verify_kuikly_release_manifest.sh ||
  die "immutable Kuikly release manifest admission is incomplete or conflicting"

HANDS_DEPENDENCY_METADATA="$(mktemp)"
trap 'rm -f "${HANDS_DEPENDENCY_METADATA}" "${HANDS_RESPONSE:-}"' EXIT
python3 scripts/check_ohos_hands_dependency.py \
  --manifest compose/ohosApp/entry/oh-package.json5 \
  --lock compose/ohosApp/entry/oh-package-lock.json5 \
  --legacy-har compose/ohosApp/entry/libs/hands.har \
  --fetch-registry >"${HANDS_DEPENDENCY_METADATA}" ||
    die "OHOS Hands dependency failed manifest/lock/artifact verification"
HANDS_DEPENDENCY_MODE="$(json_field "${HANDS_DEPENDENCY_METADATA}" mode)"
HANDS_DEPENDENCY_NAME="$(json_field "${HANDS_DEPENDENCY_METADATA}" name)"
HANDS_DEPENDENCY_VERSION="$(json_field "${HANDS_DEPENDENCY_METADATA}" version)"
HANDS_DEPENDENCY_SOURCE="$(json_field "${HANDS_DEPENDENCY_METADATA}" source)"
HANDS_DEPENDENCY_SHA256="$(json_field "${HANDS_DEPENDENCY_METADATA}" artifact_sha256)"
[ "${HANDS_DEPENDENCY_NAME}" = "@botiverse/hands" ] ||
  die "Hands dependency identifies as '${HANDS_DEPENDENCY_NAME:-missing}', expected @botiverse/hands"
[ -n "${HANDS_DEPENDENCY_VERSION}" ] || die "Hands dependency has no version"
[ -n "${HANDS_DEPENDENCY_SHA256}" ] || die "Hands dependency has no verified artifact digest"

trap 'rm -f "${HANDS_DEPENDENCY_METADATA}" "${HANDS_RESPONSE:-}"' EXIT

for pr in "${REQUIRED_PRS[@]}"; do
  [[ "${pr}" =~ ^[0-9]+$ ]] || die "invalid PR number: ${pr}"
  PR_JSON="$(gh pr view "${pr}" --repo botiverse/mobile --json state,mergedAt,mergeCommit,url,title)"
  PR_STATE="$(printf '%s' "${PR_JSON}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state", ""))')"
  PR_MERGE_SHA="$(printf '%s' "${PR_JSON}" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("mergeCommit") or {}).get("oid", ""))')"
  [ "${PR_STATE}" = "MERGED" ] || die "required PR #${pr} is not merged"
  [ -n "${PR_MERGE_SHA}" ] || die "required PR #${pr} has no merge commit"
  git merge-base --is-ancestor "${PR_MERGE_SHA}" HEAD ||
    die "required PR #${pr} is not included in ${HEAD_SHA}"
done

LIVE_CODE=""
if [ "${SKIP_HANDS_VERSION_CHECK}" = "1" ]; then
  [ "${CHANNEL}" != "main" ] ||
    die "--skip-hands-version-check is prohibited for the main channel"
else
  HANDS_RESPONSE="$(mktemp)"
  curl --fail --silent --show-error \
    --get "${HANDS_API}/public/v2/apps/${HANDS_APP_SLUG}/updates/check" \
    --data-urlencode "channel=${CHANNEL}" \
    --data-urlencode "product_type=android-apk" \
    --data-urlencode "current_version_code=0" \
    --data-urlencode "platform=android" \
    --data-urlencode "arch=${HANDS_ARCH}" \
    --data-urlencode "filetype=apk" >"${HANDS_RESPONSE}" ||
      die "Hands update check failed; do not release without a current active-version comparison"
  LIVE_CODE="$(python3 - "${HANDS_RESPONSE}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)

keys = {"version_code", "versionCode", "latest_version_code", "latestVersionCode"}
values = []
def visit(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in keys and isinstance(child, (int, float, str)) and str(child).isdigit():
                values.append(int(child))
            visit(child)
    elif isinstance(value, list):
        for child in value:
            visit(child)
visit(payload)
print(max(values) if values else "")
PY
)"
  if [ -n "${LIVE_CODE}" ] && [ "${RELEASE_CODE}" -le "${LIVE_CODE}" ]; then
    die "candidate versionCode ${RELEASE_CODE} is not newer than Hands active versionCode ${LIVE_CODE} on ${CHANNEL}"
  fi
fi

if [ -n "${REPORT_PATH}" ]; then
  mkdir -p "$(dirname "${REPORT_PATH}")"
  {
    echo "# Android Release Preflight"
    echo
    printf -- '- Candidate version: `%s` (versionCode `%s`)\n' "${VERSION}" "${RELEASE_CODE}"
    printf -- '- Channel: `%s`\n' "${CHANNEL}"
    printf -- '- Release head: `%s`\n' "${HEAD_SHA}"
    printf -- '- Kuikly release: immutable Raft manifest admission verified\n'
    printf -- '- OHOS Hands dependency: `%s@%s` via `%s`; source `%s`; verified artifact SHA-256 `%s`\n' \
      "${HANDS_DEPENDENCY_NAME}" "${HANDS_DEPENDENCY_VERSION}" "${HANDS_DEPENDENCY_MODE}" \
      "${HANDS_DEPENDENCY_SOURCE}" "${HANDS_DEPENDENCY_SHA256}"
    printf -- '- Required CI: Android Release is the candidate build/test gate\n'
    if [ "${#REQUIRED_PRS[@]}" -gt 0 ]; then
      echo "- Included PRs: $(printf '#%s ' "${REQUIRED_PRS[@]}")"
    else
      echo "- Included PRs: none explicitly supplied"
    fi
    if [ -n "${LIVE_CODE}" ]; then
      printf -- '- Hands active versionCode on `%s`: `%s`; candidate is newer\n' "${CHANNEL}" "${LIVE_CODE}"
    else
      printf -- '- Hands active versionCode on `%s`: none returned; candidate can establish the first active version\n' "${CHANNEL}"
    fi
    echo
    echo "Next: dispatch a **draft** Android Release, rewrite the raw changelog into reviewed zh-CN/en/ja notes, smoke the draft, then have the owner explicitly approve activation."
  } >"${REPORT_PATH}"
fi

echo "Android release preflight passed"
echo "  candidate: ${VERSION} (${RELEASE_CODE})"
echo "  head: ${HEAD_SHA}"
echo "  Kuikly: immutable Raft release manifest admission verified"
echo "  Hands dependency: ${HANDS_DEPENDENCY_NAME}@${HANDS_DEPENDENCY_VERSION} (${HANDS_DEPENDENCY_MODE})"
echo "  Hands artifact SHA-256: ${HANDS_DEPENDENCY_SHA256}"
if [ -n "${LIVE_CODE}" ]; then
  echo "  Hands active versionCode: ${LIVE_CODE}"
else
  echo "  Hands active versionCode: none returned"
fi
[ -n "${REPORT_PATH}" ] && echo "  report: ${REPORT_PATH}"
