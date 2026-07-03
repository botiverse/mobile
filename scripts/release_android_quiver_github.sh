#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VERSION_X="${SLOCK_ANDROID_VERSION_MAJOR:-1}"
VERSION_Y="${SLOCK_ANDROID_VERSION_MINOR:-0}"
VERSION_Z="${SLOCK_ANDROID_VERSION_PATCH:-1}"
VERSION_BUILD="${SLOCK_ANDROID_VERSION_BUILD:-0}"
VERSION_SUFFIX="${SLOCK_ANDROID_VERSION_SUFFIX:-}"
CHANNEL="${SLOCK_QUIVER_CHANNEL:-main}"
VARIANT="${SLOCK_ANDROID_VARIANT:-release}"
PUBLISH_QUIVER="${SLOCK_PUBLISH_QUIVER:-true}"
CREATE_SHARE_PAGE="${SLOCK_CREATE_SHARE_PAGE:-true}"
SHARE_TTL_SECONDS="${SLOCK_SHARE_TTL_SECONDS:-86400}"
PUSH_REMOTE="${SLOCK_RELEASE_REMOTE:-origin}"
PUSH_BRANCH="${SLOCK_RELEASE_BRANCH:-main}"
KUIKLY_REMOTE="${SLOCK_KUIKLY_REMOTE:-origin}"
KUIKLY_BRANCH="${SLOCK_KUIKLY_BRANCH:-}"
WAIT_FOR_RUN="${SLOCK_RELEASE_WAIT:-0}"

die() {
  echo "error: $*" >&2
  exit 64
}

usage() {
  cat <<'USAGE'
Usage: scripts/release_android_quiver_github.sh [options]

Pushes local Slock Android release inputs, ensures the Kuikly submodule commit
is reachable by GitHub Actions, and dispatches .github/workflows/android-release.yml.

Defaults match the production Quiver release lane:
  versionName=1.0.1
  versionCode=1000100
  channel=main
  variant=release
  publish_quiver=true
  create_share_page=true

Options:
  --version <x.y.z>       Semantic version. Default: 1.0.1
  --build <n>             Version build component. Default: 0
  --suffix <value>        Version name suffix, e.g. rc1.
  --channel <slug>        Quiver channel. Default: main.
  --variant <name>        Android variant: release or debug. Default: release.
  --no-publish-quiver     Build only; do not publish to Quiver.
  --no-share-page         Publish to Quiver but skip share page creation.
  --share-ttl <seconds>   Share page TTL. Default: 86400.
  --wait                  Wait for the GitHub Actions run to finish.
  -h, --help              Show this help.

Environment overrides:
  SLOCK_RELEASE_REMOTE=origin
  SLOCK_RELEASE_BRANCH=main
  SLOCK_KUIKLY_REMOTE=origin
  SLOCK_KUIKLY_BRANCH=<defaults to current Kuikly branch>
  SLOCK_RELEASE_WAIT=1
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required"
}

parse_version() {
  local version="$1"
  IFS='.' read -r VERSION_X VERSION_Y VERSION_Z <<EOF
${version}
EOF
  [ -n "${VERSION_X}" ] && [ -n "${VERSION_Y}" ] && [ -n "${VERSION_Z}" ] ||
    die "--version must be x.y.z"
}

version_name() {
  if [ -n "${VERSION_SUFFIX}" ]; then
    printf '%s.%s.%s-%s\n' "${VERSION_X}" "${VERSION_Y}" "${VERSION_Z}" "${VERSION_SUFFIX}"
  else
    printf '%s.%s.%s\n' "${VERSION_X}" "${VERSION_Y}" "${VERSION_Z}"
  fi
}

version_code() {
  printf '%s\n' "$((VERSION_X * 1000000 + VERSION_Y * 10000 + VERSION_Z * 100 + VERSION_BUILD))"
}

json_bool() {
  case "$1" in
    true|false) printf '%s\n' "$1" ;;
    1) printf 'true\n' ;;
    0) printf 'false\n' ;;
    *) die "boolean value expected, got: $1" ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "--version requires value"
      parse_version "$2"
      shift 2
      ;;
    --build)
      [ "$#" -ge 2 ] || die "--build requires value"
      VERSION_BUILD="$2"
      shift 2
      ;;
    --suffix)
      [ "$#" -ge 2 ] || die "--suffix requires value"
      VERSION_SUFFIX="$2"
      shift 2
      ;;
    --channel)
      [ "$#" -ge 2 ] || die "--channel requires value"
      CHANNEL="$2"
      shift 2
      ;;
    --variant)
      [ "$#" -ge 2 ] || die "--variant requires value"
      VARIANT="$2"
      shift 2
      ;;
    --no-publish-quiver)
      PUBLISH_QUIVER=false
      shift
      ;;
    --no-share-page)
      CREATE_SHARE_PAGE=false
      shift
      ;;
    --share-ttl)
      [ "$#" -ge 2 ] || die "--share-ttl requires value"
      SHARE_TTL_SECONDS="$2"
      shift 2
      ;;
    --wait)
      WAIT_FOR_RUN=1
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

require_cmd git
require_cmd gh

cd "${ROOT_DIR}"

[ -d .git ] || die "not a git repository: ${ROOT_DIR}"
[ -f .github/workflows/android-release.yml ] || die "android-release.yml not found"
[ -d third_party/kuikly-ui/.git ] || die "third_party/kuikly-ui submodule is not initialized"

current_branch="$(git rev-parse --abbrev-ref HEAD)"
[ "${current_branch}" = "${PUSH_BRANCH}" ] ||
  die "expected branch ${PUSH_BRANCH}, current branch is ${current_branch}"

if ! git diff --quiet -- . ':!third_party/kuikly-ui'; then
  die "working tree has unstaged tracked changes; commit or stash before release"
fi
if ! git diff --cached --quiet -- . ':!third_party/kuikly-ui'; then
  die "working tree has staged changes; commit or unstage before release"
fi

kuikly_head="$(git -C third_party/kuikly-ui rev-parse HEAD)"
if [ -z "${KUIKLY_BRANCH}" ]; then
  KUIKLY_BRANCH="$(git -C third_party/kuikly-ui rev-parse --abbrev-ref HEAD)"
fi
[ "${KUIKLY_BRANCH}" != "HEAD" ] || die "Kuikly submodule is detached; set SLOCK_KUIKLY_BRANCH"

echo "==> Ensuring Kuikly commit is reachable"
echo "    commit: ${kuikly_head}"
echo "    target: ${KUIKLY_REMOTE}/${KUIKLY_BRANCH}"
git -C third_party/kuikly-ui fetch "${KUIKLY_REMOTE}" "${KUIKLY_BRANCH}" || true
if git -C third_party/kuikly-ui rev-parse --verify --quiet "refs/remotes/${KUIKLY_REMOTE}/${KUIKLY_BRANCH}" >/dev/null &&
   git -C third_party/kuikly-ui merge-base --is-ancestor "${kuikly_head}" "refs/remotes/${KUIKLY_REMOTE}/${KUIKLY_BRANCH}"; then
  echo "    already reachable"
else
  echo "    pushing ${kuikly_head} to ${KUIKLY_REMOTE}/${KUIKLY_BRANCH}"
  git -C third_party/kuikly-ui push "${KUIKLY_REMOTE}" "HEAD:${KUIKLY_BRANCH}"
fi

echo "==> Pushing Slock ${PUSH_BRANCH} to ${PUSH_REMOTE}/${PUSH_BRANCH}"
git push "${PUSH_REMOTE}" "${PUSH_BRANCH}"

release_version="$(version_name)"
release_code="$(version_code)"
publish_quiver="$(json_bool "${PUBLISH_QUIVER}")"
create_share_page="$(json_bool "${CREATE_SHARE_PAGE}")"

echo "==> Dispatching Android release"
echo "    version: ${release_version}"
echo "    versionCode: ${release_code}"
echo "    channel: ${CHANNEL}"
echo "    variant: ${VARIANT}"
echo "    publish_quiver: ${publish_quiver}"
echo "    create_share_page: ${create_share_page}"

run_url="$(
  gh workflow run android-release.yml \
    --repo botiverse/mobile \
    --ref "${PUSH_BRANCH}" \
    -f "version_major=${VERSION_X}" \
    -f "version_minor=${VERSION_Y}" \
    -f "version_patch=${VERSION_Z}" \
    -f "version_build=${VERSION_BUILD}" \
    -f "version_suffix=${VERSION_SUFFIX}" \
    -f "quiver_channel=${CHANNEL}" \
    -f "android_variant=${VARIANT}" \
    -f "publish_quiver=${publish_quiver}" \
    -f "create_share_page=${create_share_page}" \
    -f "share_ttl_seconds=${SHARE_TTL_SECONDS}"
)"
printf '%s\n' "${run_url}"

if [ "${WAIT_FOR_RUN}" = "1" ]; then
  run_id="${run_url##*/}"
  echo "==> Waiting for run ${run_id}"
  gh run watch "${run_id}" --repo botiverse/mobile --exit-status
fi
