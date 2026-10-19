#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VERSION_X="${SLOCK_ANDROID_VERSION_MAJOR:-1}"
VERSION_Y="${SLOCK_ANDROID_VERSION_MINOR:-0}"
VERSION_Z="${SLOCK_ANDROID_VERSION_PATCH:-1}"
VERSION_BUILD="${SLOCK_ANDROID_VERSION_BUILD:-0}"
VERSION_SUFFIX="${SLOCK_ANDROID_VERSION_SUFFIX:-}"
CHANNEL="${SLOCK_HANDS_CHANNEL:-main}"
VARIANT="${SLOCK_ANDROID_VARIANT:-release}"
DEX_PACKAGING="${SLOCK_ANDROID_DEX_PACKAGING:-compressed}"
JNI_PACKAGING="${SLOCK_ANDROID_JNI_PACKAGING:-current}"
CREATE_HANDS_DRAFT="${SLOCK_CREATE_HANDS_DRAFT:-true}"
RELEASE_MODE="${SLOCK_RELEASE_MODE:-draft}"
CREATE_SHARE_PAGE="${SLOCK_CREATE_SHARE_PAGE:-true}"
# Public release shares live until explicitly revoked. Set SLOCK_SHARE_TTL_SECONDS
# (or pass --share-ttl) only for an intentionally temporary review share.
SHARE_TTL_SECONDS="${SLOCK_SHARE_TTL_SECONDS:-}"
PUSH_REMOTE="${SLOCK_RELEASE_REMOTE:-origin}"
PUSH_BRANCH="${SLOCK_RELEASE_BRANCH:-main}"
WAIT_FOR_RUN="${SLOCK_RELEASE_WAIT:-0}"
PARALLEL_PREFLIGHT="${SLOCK_RELEASE_PARALLEL_PREFLIGHT:-1}"
PREFLIGHT_REPORT="${SLOCK_RELEASE_PREFLIGHT_REPORT:-}"
PREFLIGHT_REQUIRED_PRS=()
PREFLIGHT_SKIP_HANDS_VERSION_CHECK=0

die() {
  echo "error: $*" >&2
  exit 64
}

usage() {
  cat <<'USAGE'
Usage: scripts/release_android_hands_github.sh [options]

Verifies local Slock Android release inputs are already present on the protected
remote release branch and dispatches .github/workflows/android-release.yml.

Defaults create a Hands **draft** release with release preflight enforced:
  versionName=1.0.1
  versionCode=1000100
  channel=main
  variant=release
  dex_packaging=compressed
  jni_packaging=current
  release_mode=draft
  create_hands_draft=true
  create_share_page=true

This script never activates a release. Review the generated draft, replace the
raw changelog with the approved zh-CN, English, and Japanese notes, smoke it,
and use the explicit
Hands release publish step only after the release owner says to publish.

By default, the draft workflow is dispatched first and the full preflight runs
at the same time. A failed preflight cancels that dispatched workflow. This
reduces release latency without weakening the draft-only or final-publish gates.

Options:
  --version <x.y.z>       Semantic version. Default: 1.0.1
  --build <n>             Version build component. Default: 0
  --suffix <value>        Version name suffix, e.g. rc1.
  --channel <slug>        Hands channel. Default: main.
  --variant <name>        Hands variant: release or debug. Default: release.
  --dex-packaging <mode>  current, compressed, or mmap. Default: compressed.
  --jni-packaging <mode>  current, legacy, or modern. Default: current.
  --required-pr <number>  Candidate PR included in this release; repeatable.
  --preflight-report <p>  Write the release-preflight Markdown report to p.
  --parallel-preflight    Dispatch the draft workflow, then run preflight and
                          cancel the run if it fails. Default.
  --preflight-first       Run preflight before dispatching. Use only when
                          diagnosing the release lane.
  --skip-hands-version-check
                          Offline diagnostic only; prohibited for main.
  --no-share-page         Create the Hands draft but skip share page creation.
  --share-ttl <seconds>   Optional temporary share TTL. Default: no expiry.
  --wait                  Wait for the GitHub Actions run to finish.
  -h, --help              Show this help.

Environment overrides:
  SLOCK_RELEASE_REMOTE=origin
  SLOCK_RELEASE_BRANCH=main
  SLOCK_HANDS_CHANNEL=main
  SLOCK_ANDROID_DEX_PACKAGING=compressed
  SLOCK_ANDROID_JNI_PACKAGING=current
  SLOCK_CREATE_HANDS_DRAFT=true
  SLOCK_RELEASE_MODE=draft
  SLOCK_RELEASE_WAIT=1
  SLOCK_RELEASE_PARALLEL_PREFLIGHT=1
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
  scripts/ci/version_code.sh "${VERSION_X}" "${VERSION_Y}" "${VERSION_Z}" "${VERSION_BUILD}"
}

json_bool() {
  case "$1" in
    true|false) printf '%s\n' "$1" ;;
    1) printf 'true\n' ;;
    0) printf 'false\n' ;;
    *) die "boolean value expected, got: $1" ;;
  esac
}


find_dispatched_run() {
  local dispatch_started_at="$1"
  local expected_head="$2"
  local attempts=0
  local runs_json
  local run_id

  while [ "${attempts}" -lt 30 ]; do
    runs_json="$(gh run list \
      --repo botiverse/mobile \
      --workflow android-release.yml \
      --branch "${PUSH_BRANCH}" \
      --event workflow_dispatch \
      --limit 20 \
      --json databaseId,createdAt,headSha,url)"
    run_id="$(printf '%s' "${runs_json}" | python3 -c '
import datetime as dt
import json
import sys

started_at = dt.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
expected_head = sys.argv[2]
runs = json.load(sys.stdin)

def parse_created_at(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))

candidates = [
    run for run in runs
    if run.get("headSha") == expected_head
    and parse_created_at(run["createdAt"]) >= started_at
]
if not candidates:
    recent_runs = [
        run for run in runs
        if parse_created_at(run["createdAt"]) >= started_at
    ]
    candidates = recent_runs if len(recent_runs) == 1 else []
if candidates:
    candidates.sort(key=lambda run: run["createdAt"], reverse=True)
    print(candidates[0]["databaseId"])
' "${dispatch_started_at}" "${expected_head}")"
    if [ -n "${run_id}" ]; then
      printf '%s\n' "${run_id}"
      return 0
    fi
    attempts="$((attempts + 1))"
    sleep 2
  done

  return 1
}

cancel_dispatched_run() {
  local run_id="$1"

  echo "==> Preflight failed; cancelling draft workflow run ${run_id}" >&2
  if gh run cancel "${run_id}" --repo botiverse/mobile; then
    echo "    cancellation requested" >&2
  else
    echo "warning: could not cancel run ${run_id}; cancel it in GitHub immediately" >&2
  fi
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
    --dex-packaging)
      [ "$#" -ge 2 ] || die "--dex-packaging requires value"
      DEX_PACKAGING="$2"
      shift 2
      ;;
    --jni-packaging)
      [ "$#" -ge 2 ] || die "--jni-packaging requires value"
      JNI_PACKAGING="$2"
      shift 2
      ;;
    --required-pr)
      [ "$#" -ge 2 ] || die "--required-pr requires value"
      PREFLIGHT_REQUIRED_PRS+=("$2")
      shift 2
      ;;
    --preflight-report)
      [ "$#" -ge 2 ] || die "--preflight-report requires value"
      PREFLIGHT_REPORT="$2"
      shift 2
      ;;
    --parallel-preflight)
      PARALLEL_PREFLIGHT=1
      shift
      ;;
    --preflight-first)
      PARALLEL_PREFLIGHT=0
      shift
      ;;
    --skip-hands-version-check)
      PREFLIGHT_SKIP_HANDS_VERSION_CHECK=1
      shift
      ;;
    --build-only|--no-publish-hands)
      die "$1 is no longer supported: release dispatches always upload assets and create a Hands draft"
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
require_cmd python3

cd "${ROOT_DIR}"

[ -e .git ] || die "not a git repository: ${ROOT_DIR}"
[ -f .github/workflows/android-release.yml ] || die "android-release.yml not found"
[ "${RELEASE_MODE}" = "draft" ] ||
  die "release_mode must be draft; active Hands publication is an explicit post-smoke owner action"

case "${PARALLEL_PREFLIGHT}" in
  0|1) ;;
  *) die "SLOCK_RELEASE_PARALLEL_PREFLIGHT must be 0 or 1" ;;
esac

case "${DEX_PACKAGING}" in
  current|compressed|mmap) ;;
  *) die "dex packaging must be current, compressed, or mmap" ;;
esac

case "${JNI_PACKAGING}" in
  current|legacy|modern) ;;
  *) die "JNI packaging must be current, legacy, or modern" ;;
esac

case "${VARIANT}" in
  release|debug) ;;
  play)
    die "the Hands release wrapper does not dispatch the Play carrier; use android-release.yml with android_variant=play and upload_android_artifacts=true"
    ;;
  *)
    die "Hands variant must be release or debug"
    ;;
esac

current_branch="$(git rev-parse --abbrev-ref HEAD)"
[ "${current_branch}" = "${PUSH_BRANCH}" ] ||
  die "expected branch ${PUSH_BRANCH}, current branch is ${current_branch}"

if ! git diff --quiet; then
  die "working tree has unstaged tracked changes; commit or stash before release"
fi
if ! git diff --cached --quiet; then
  die "working tree has staged changes; commit or unstage before release"
fi

PREFLIGHT_ARGS=(
  --version "${VERSION_X}.${VERSION_Y}.${VERSION_Z}"
  --build "${VERSION_BUILD}"
  --channel "${CHANNEL}"
)
if [ "${#PREFLIGHT_REQUIRED_PRS[@]}" -gt 0 ]; then
  for pr in "${PREFLIGHT_REQUIRED_PRS[@]}"; do
    PREFLIGHT_ARGS+=(--required-pr "${pr}")
  done
fi
if [ -n "${PREFLIGHT_REPORT}" ]; then
  PREFLIGHT_ARGS+=(--report "${PREFLIGHT_REPORT}")
fi
if [ "${PREFLIGHT_SKIP_HANDS_VERSION_CHECK}" = "1" ]; then
  PREFLIGHT_ARGS+=(--skip-hands-version-check)
fi

release_version="$(version_name)"
release_code="$(version_code)"
create_hands_draft="$(json_bool "${CREATE_HANDS_DRAFT}")"
create_share_page="$(json_bool "${CREATE_SHARE_PAGE}")"

echo "==> Preparing Android draft release request"
echo "    version: ${release_version}"
echo "    versionCode: ${release_code}"
echo "    channel: ${CHANNEL}"
echo "    variant: ${VARIANT}"
echo "    dex_packaging: ${DEX_PACKAGING}"
echo "    jni_packaging: ${JNI_PACKAGING}"
echo "    release_mode: ${RELEASE_MODE}"
echo "    create_hands_draft: ${create_hands_draft}"
echo "    create_share_page: ${create_share_page}"

if [ "${PARALLEL_PREFLIGHT}" = "0" ]; then
  echo "==> Running release preflight before dispatch"
  "${ROOT_DIR}/scripts/release_android_preflight.sh" "${PREFLIGHT_ARGS[@]}"
fi

local_head="$(git rev-parse HEAD)"
dispatch_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

echo "==> Dispatching Android release"
workflow_fields=(
  -f "version_major=${VERSION_X}"
  -f "version_minor=${VERSION_Y}"
  -f "version_patch=${VERSION_Z}"
  -f "version_build=${VERSION_BUILD}"
  -f "version_suffix=${VERSION_SUFFIX}"
  -f "hands_channel=${CHANNEL}"
  -f "android_variant=${VARIANT}"
  -f "dex_packaging=${DEX_PACKAGING}"
  -f "jni_packaging=${JNI_PACKAGING}"
  -f "release_mode=${RELEASE_MODE}"
  -f "create_share_page=${create_share_page}"
)
if [ -n "${SHARE_TTL_SECONDS}" ]; then
  workflow_fields+=( -f "share_ttl_seconds=${SHARE_TTL_SECONDS}" )
fi
gh workflow run android-release.yml \
    --repo botiverse/mobile \
    --ref "${PUSH_BRANCH}" \
    "${workflow_fields[@]}"

preflight_log="$(mktemp)"
trap 'rm -f "${preflight_log}"' EXIT

if [ "${PARALLEL_PREFLIGHT}" = "1" ]; then
  echo "==> Running release preflight in parallel with draft workflow"
  "${ROOT_DIR}/scripts/release_android_preflight.sh" "${PREFLIGHT_ARGS[@]}" >"${preflight_log}" 2>&1 &
  preflight_pid="$!"
fi

echo "==> Resolving dispatched Android Release run"
if ! run_id="$(find_dispatched_run "${dispatch_started_at}" "${local_head}")"; then
  if [ "${PARALLEL_PREFLIGHT}" = "1" ]; then
    wait "${preflight_pid}" || true
    cat "${preflight_log}" >&2
  fi
  die "could not identify the dispatched Android Release run; inspect GitHub Actions before continuing"
fi
run_url="https://github.com/botiverse/mobile/actions/runs/${run_id}"
echo "    run: ${run_url}"

if [ "${PARALLEL_PREFLIGHT}" = "1" ]; then
  if wait "${preflight_pid}"; then
    cat "${preflight_log}"
  else
    cat "${preflight_log}" >&2
    cancel_dispatched_run "${run_id}"
    die "release preflight failed; dispatched draft workflow was cancelled"
  fi
fi

if [ "${WAIT_FOR_RUN}" = "1" ]; then
  echo "==> Waiting for run ${run_id}"
  gh run watch "${run_id}" --repo botiverse/mobile --exit-status
fi
