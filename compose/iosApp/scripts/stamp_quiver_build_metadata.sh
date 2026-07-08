#!/bin/sh
set -eu

plist_path="${TARGET_BUILD_DIR:-}/${INFOPLIST_PATH:-}"
if [ -z "${TARGET_BUILD_DIR:-}" ] || [ -z "${INFOPLIST_PATH:-}" ] || [ ! -f "${plist_path}" ]; then
  echo "warning: processed Info.plist not found; skipping Quiver build metadata stamp"
  exit 0
fi

git_commit="${SLOCK_GIT_COMMIT:-${GITHUB_SHA:-}}"
if [ -z "${git_commit}" ]; then
  git_commit="$(git -C "${SRCROOT:-.}" rev-parse HEAD 2>/dev/null || true)"
fi
if [ -z "${git_commit}" ]; then
  git_commit="unknown"
fi

plist_buddy="/usr/libexec/PlistBuddy"
"${plist_buddy}" -c "Set :QuiverBuildCommit ${git_commit}" "${plist_path}" 2>/dev/null \
  || "${plist_buddy}" -c "Add :QuiverBuildCommit string ${git_commit}" "${plist_path}"

if [ -n "${SLOCK_IOS_VERSION_NAME:-}" ]; then
  "${plist_buddy}" -c "Set :CFBundleShortVersionString ${SLOCK_IOS_VERSION_NAME}" "${plist_path}"
fi
if [ -n "${SLOCK_IOS_VERSION_CODE:-}" ]; then
  "${plist_buddy}" -c "Set :CFBundleVersion ${SLOCK_IOS_VERSION_CODE}" "${plist_path}"
fi
