#!/usr/bin/env bash
set -euo pipefail

REMOTE="${SLOCK_RELEASE_REMOTE:-origin}"
MAIN_BRANCH="${SLOCK_MAIN_BRANCH:-main}"
VERSION=""
CANDIDATE_SHA=""

die() {
  echo "error: $*" >&2
  exit 64
}

usage() {
  cat <<'USAGE'
Usage: scripts/create_android_release_branch.sh --version X.Y.Z --candidate SHA

Creates release-X.Y.Z at an exact commit reachable from origin/main. The
operation is idempotent when the remote branch already points at that commit
and fails closed when the branch exists at a different commit. It never
force-pushes or changes main.

Environment overrides:
  SLOCK_RELEASE_REMOTE=origin
  SLOCK_MAIN_BRANCH=main
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "--version requires a value"
      VERSION="$2"
      shift 2
      ;;
    --candidate)
      [ "$#" -ge 2 ] || die "--candidate requires a value"
      CANDIDATE_SHA="$2"
      shift 2
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
[[ "${CANDIDATE_SHA}" =~ ^[0-9a-fA-F]{7,40}$ ]] ||
  die "--candidate must be a Git commit id"

command -v git >/dev/null 2>&1 || die "git is required"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

RELEASE_BRANCH="release-${VERSION}"

git fetch "${REMOTE}" "${MAIN_BRANCH}" --prune
git rev-parse --verify --quiet "${CANDIDATE_SHA}^{commit}" >/dev/null ||
  die "candidate commit not found: ${CANDIDATE_SHA}"

CANDIDATE_SHA="$(git rev-parse "${CANDIDATE_SHA}^{commit}")"
MAIN_SHA="$(git rev-parse "refs/remotes/${REMOTE}/${MAIN_BRANCH}")"
git merge-base --is-ancestor "${CANDIDATE_SHA}" "${MAIN_SHA}" ||
  die "candidate ${CANDIDATE_SHA} is not reachable from ${REMOTE}/${MAIN_BRANCH}"

EXISTING_SHA="$(git ls-remote --heads "${REMOTE}" "refs/heads/${RELEASE_BRANCH}" | awk '{print $1}')"
if [ -n "${EXISTING_SHA}" ]; then
  [ "${EXISTING_SHA}" = "${CANDIDATE_SHA}" ] ||
    die "${REMOTE}/${RELEASE_BRANCH} already exists at ${EXISTING_SHA}; refusing to rewrite it to ${CANDIDATE_SHA}"
  echo "Release branch already exists at the requested commit"
  echo "  branch: ${REMOTE}/${RELEASE_BRANCH}"
  echo "  head: ${CANDIDATE_SHA}"
  exit 0
fi

git push "${REMOTE}" "${CANDIDATE_SHA}:refs/heads/${RELEASE_BRANCH}"

echo "Release branch created"
echo "  branch: ${REMOTE}/${RELEASE_BRANCH}"
echo "  head: ${CANDIDATE_SHA}"
echo "  next: branch blocker fixes from ${REMOTE}/${RELEASE_BRANCH} and target PRs to ${RELEASE_BRANCH}"
