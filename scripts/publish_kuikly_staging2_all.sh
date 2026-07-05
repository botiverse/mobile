#!/usr/bin/env bash
set -euo pipefail

# Pull a Kuikly fork ref and refresh all local Slock consumers:
# - MavenLocal artifacts for Android/iOS shared and OHOS shared
# - iOS CocoaPods lock/install state
# - OHOS render HAR plus the Slock OHOS entry package/lockfile

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
KUIKLY_DIR="${KUIKLY_DIR:-${REPO_ROOT}/third_party/kuikly-ui}"

REMOTE="${KUIKLY_REMOTE:-origin}"
BRANCH="${KUIKLY_BRANCH:-staging2}"
TARGET_REF="${KUIKLY_REF:-}"
KUIKLY_VERSION="${KUIKLY_VERSION:-2.21.0}"
DEVECO_STUDIO_HOME="${DEVECO_STUDIO_HOME:-/Applications/DevEco-Studio.app/Contents}"

RUN_CHECKOUT=1
RUN_MAVEN=1
RUN_IOS=1
RUN_OHOS_HAR=1

usage() {
  cat <<'USAGE'
Usage: scripts/publish_kuikly_staging2_all.sh [options]

Fetches a Kuikly fork ref, checks it out, then refreshes all local Slock
consumers: MavenLocal, iOS Pods, and OHOS render HAR.

Options:
  --remote <name>       Git remote to fetch. Default: origin
  --branch <name>       Branch to fetch from remote. Default: staging2
  --ref <ref>           Ref to checkout after fetch. Default: <remote>/<branch>
  --no-checkout         Use the current Kuikly checkout without fetch/checkout
  --skip-maven          Skip MavenLocal publishing
  --skip-ios            Skip iOS dummy framework + pod install
  --skip-ohos-har       Skip OHOS render HAR build/update
  -h, --help            Show this help

Environment:
  KUIKLY_VERSION        Version line published to MavenLocal. Default: 2.21.0
  KUIKLY_DIR            Kuikly checkout path. Default: third_party/kuikly-ui
  DEVECO_STUDIO_HOME    DevEco Studio home for OHOS HAR build.
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote)
      [[ $# -ge 2 ]] || die "--remote requires a value"
      REMOTE="$2"
      shift
      ;;
    --branch)
      [[ $# -ge 2 ]] || die "--branch requires a value"
      BRANCH="$2"
      shift
      ;;
    --ref)
      [[ $# -ge 2 ]] || die "--ref requires a value"
      TARGET_REF="$2"
      shift
      ;;
    --no-checkout)
      RUN_CHECKOUT=0
      ;;
    --skip-maven)
      RUN_MAVEN=0
      ;;
    --skip-ios)
      RUN_IOS=0
      ;;
    --skip-ohos-har)
      RUN_OHOS_HAR=0
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
  shift
done

ensure_kuikly_submodule() {
  if ! git -C "${KUIKLY_DIR}" rev-parse --git-dir >/dev/null 2>&1; then
    echo "==> Initializing Kuikly submodule..."
    git -C "${REPO_ROOT}" submodule update --init third_party/kuikly-ui
  fi
  git -C "${KUIKLY_DIR}" rev-parse --git-dir >/dev/null 2>&1 || \
    die "Kuikly checkout is not available: ${KUIKLY_DIR}"
}

require_clean_kuikly() {
  git -C "${KUIKLY_DIR}" diff --quiet || \
    die "Kuikly checkout has unstaged changes; commit/stash them first"
  git -C "${KUIKLY_DIR}" diff --cached --quiet || \
    die "Kuikly checkout has staged changes; commit/stash them first"
}

setup_deveco_env() {
  [[ -d "${DEVECO_STUDIO_HOME}" ]] || die "Missing DevEco Studio home: ${DEVECO_STUDIO_HOME}"
  export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-${DEVECO_STUDIO_HOME}/sdk}"
  export PATH="${DEVECO_SDK_HOME}:${DEVECO_STUDIO_HOME}/jbr/Contents/Home/bin:${DEVECO_STUDIO_HOME}/tools/node/bin:${DEVECO_STUDIO_HOME}/tools/ohpm/bin:${DEVECO_STUDIO_HOME}/tools/hvigor/bin:${DEVECO_SDK_HOME}/default/openharmony/toolchains:${PATH}"
}

checkout_kuikly_ref() {
  [[ "${RUN_CHECKOUT}" -eq 1 ]] || return 0
  require_clean_kuikly
  echo "==> Fetching Kuikly ${REMOTE}/${BRANCH}..."
  git -C "${KUIKLY_DIR}" fetch "${REMOTE}" "${BRANCH}"
  local ref="${TARGET_REF:-${REMOTE}/${BRANCH}}"
  echo "==> Checking out Kuikly ${ref} detached..."
  git -C "${KUIKLY_DIR}" checkout --detach "${ref}"
}

publish_maven_local() {
  [[ "${RUN_MAVEN}" -eq 1 ]] || return 0
  echo "==> Publishing Kuikly MavenLocal artifacts..."
  KUIKLY_VERSION="${KUIKLY_VERSION}" "${SCRIPT_DIR}/build_kuikly_fork.sh" --all
}

refresh_ios_pods() {
  [[ "${RUN_IOS}" -eq 1 ]] || return 0
  command -v pod >/dev/null 2>&1 || die "CocoaPods 'pod' command is not available"
  echo "==> Generating iOS dummy framework for CocoaPods..."
  (cd "${REPO_ROOT}/compose" && \
    KUIKLY_VERSION="${KUIKLY_VERSION}" ./gradlew --no-daemon --console=plain :shared:generateDummyFramework)
  echo "==> Refreshing iOS Pods..."
  (cd "${REPO_ROOT}/compose/iosApp" && KUIKLY_VERSION="${KUIKLY_VERSION}" pod install)
}

refresh_ohos_har() {
  [[ "${RUN_OHOS_HAR}" -eq 1 ]] || return 0
  setup_deveco_env
  command -v ohpm >/dev/null 2>&1 || die "ohpm is not available in PATH"

  local short_sha safe_branch har_name target_har entry_package
  short_sha="$(git -C "${KUIKLY_DIR}" rev-parse --short=8 HEAD)"
  safe_branch="${BRANCH//\//-}"
  har_name="kuikly-render-${safe_branch}-${short_sha}.har"
  target_har="${REPO_ROOT}/compose/ohosApp/entry/libs/${har_name}"
  entry_package="${REPO_ROOT}/compose/ohosApp/entry/oh-package.json5"

  echo "==> Building and copying OHOS render HAR ${har_name}..."
  TARGET_HAR="${target_har}" KUIKLY_DIR="${KUIKLY_DIR}" DEVECO_STUDIO_HOME="${DEVECO_STUDIO_HOME}" \
    "${SCRIPT_DIR}/update_kuikly_render_har.sh"

  echo "==> Pointing Slock OHOS entry at ${har_name}..."
  sed -i.bak -E "s#(\"@kuikly-open/render\"[[:space:]]*:[[:space:]]*)['\"]file:\\./libs/kuikly-render-[^'\"]+\\.har['\"]#\\1'file:./libs/${har_name}'#" "${entry_package}"
  rm -f "${entry_package}.bak"

  echo "==> Refreshing Slock OHOS lockfiles..."
  (cd "${REPO_ROOT}/compose/ohosApp" && ohpm install --all --strict_ssl true)
}

ensure_kuikly_submodule
checkout_kuikly_ref

KUIKLY_SHA="$(git -C "${KUIKLY_DIR}" rev-parse --short=10 HEAD)"
KUIKLY_DESCRIBE="$(git -C "${KUIKLY_DIR}" describe --tags --always --dirty)"
echo "==> Kuikly checkout: ${KUIKLY_SHA} (${KUIKLY_DESCRIBE})"

publish_maven_local
refresh_ios_pods
refresh_ohos_har

echo "==> Kuikly all-platform local publish complete."
echo "    Kuikly: ${KUIKLY_SHA} (${KUIKLY_DESCRIBE})"
echo "    MavenLocal version line: ${KUIKLY_VERSION}"
