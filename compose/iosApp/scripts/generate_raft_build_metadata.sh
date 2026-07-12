#!/bin/sh
set -eu

mkdir -p "${DERIVED_FILE_DIR}"

build_time="${RAFT_BUILD_TIME:-}"
if [ -z "${build_time}" ]; then
  build_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
fi

git_commit="${RAFT_GIT_COMMIT:-${GITHUB_SHA:-}}"
if [ -z "${git_commit}" ]; then
  git_commit="$(git -C "${SRCROOT:-.}" rev-parse HEAD 2>/dev/null || true)"
fi
if [ -z "${git_commit}" ]; then
  git_commit="unknown"
fi

escape_objc() {
  printf "%s" "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

cat > "${DERIVED_FILE_DIR}/RaftIosBuildMetadata.h" <<EOF
#define RAFT_IOS_BUILD_TIME @"$(escape_objc "${build_time}")"
#define RAFT_IOS_GIT_COMMIT @"$(escape_objc "${git_commit}")"
EOF
