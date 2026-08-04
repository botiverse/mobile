#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf '::error::git-safe-workspace: %s\n' "$*" >&2
  exit 64
}

test -n "${GITHUB_WORKSPACE:-}" || fail "GITHUB_WORKSPACE is required"
test -d "$GITHUB_WORKSPACE" || fail "GITHUB_WORKSPACE is not a directory"

case "${GIT_SAFE_WORKSPACE_SCOPE:-workspace}" in
  workspace) safe_directory="$GITHUB_WORKSPACE" ;;
  all) safe_directory='*' ;;
  *) fail "scope must be workspace or all" ;;
esac

git config --global --add safe.directory "$safe_directory"
git -C "$GITHUB_WORKSPACE" rev-parse --git-dir >/dev/null
