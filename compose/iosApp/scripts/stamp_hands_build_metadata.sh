#!/bin/sh
set -eu

plist_path="${TARGET_BUILD_DIR:-}/${INFOPLIST_PATH:-}"
if [ -z "${TARGET_BUILD_DIR:-}" ] || [ -z "${INFOPLIST_PATH:-}" ] || [ ! -f "${plist_path}" ]; then
  echo "warning: processed Info.plist not found; skipping Hands build metadata stamp"
  exit 0
fi

git_commit="${RAFT_GIT_COMMIT:-${GITHUB_SHA:-}}"
if [ -z "${git_commit}" ]; then
  git_commit="$(git -C "${SRCROOT:-.}" rev-parse HEAD 2>/dev/null || true)"
fi
if [ -z "${git_commit}" ]; then
  git_commit="unknown"
fi

plist_buddy="/usr/libexec/PlistBuddy"
# HandsBuildCommit is what the Hands iOS SDK (HandsFeedbackClient) reads to
# attribute feedback/crash reports to a build commit. (Renamed from the legacy
# the obsolete pre-Hands build-commit key, which nothing reads.)
"${plist_buddy}" -c "Set :HandsBuildCommit ${git_commit}" "${plist_path}" 2>/dev/null \
  || "${plist_buddy}" -c "Add :HandsBuildCommit string ${git_commit}" "${plist_path}"

if [ -n "${RAFT_IOS_VERSION_NAME:-}" ]; then
  "${plist_buddy}" -c "Set :CFBundleShortVersionString ${RAFT_IOS_VERSION_NAME}" "${plist_path}"
fi
if [ -n "${RAFT_IOS_VERSION_CODE:-}" ]; then
  "${plist_buddy}" -c "Set :CFBundleVersion ${RAFT_IOS_VERSION_CODE}" "${plist_path}"
fi
