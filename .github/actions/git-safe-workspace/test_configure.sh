#!/usr/bin/env bash

set -euo pipefail

ACTION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
CONFIGURE="$ACTION_DIR/configure.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

repo="$TMP_ROOT/repo"
git init -q "$repo"

workspace_home="$TMP_ROOT/home-workspace"
mkdir -p "$workspace_home"
HOME="$workspace_home" \
  GITHUB_WORKSPACE="$repo" \
  GIT_SAFE_WORKSPACE_SCOPE=workspace \
  "$CONFIGURE"
workspace_safe="$(HOME="$workspace_home" git config --global --get-all safe.directory)"
test "$workspace_safe" = "$repo" || fail "workspace scope configured $workspace_safe"
printf 'ok workspace-scope\n'

all_home="$TMP_ROOT/home-all"
mkdir -p "$all_home"
HOME="$all_home" \
  GITHUB_WORKSPACE="$repo" \
  GIT_SAFE_WORKSPACE_SCOPE=all \
  "$CONFIGURE"
all_safe="$(HOME="$all_home" git config --global --get-all safe.directory)"
test "$all_safe" = '*' || fail "all scope configured $all_safe"
printf 'ok all-scope\n'

invalid_rc=0
HOME="$TMP_ROOT/home-invalid" \
  GITHUB_WORKSPACE="$repo" \
  GIT_SAFE_WORKSPACE_SCOPE=invalid \
  "$CONFIGURE" >/dev/null 2>&1 || invalid_rc=$?
test "$invalid_rc" -eq 64 || fail "invalid scope expected rc=64, got rc=$invalid_rc"
printf 'ok invalid-scope rc=%s\n' "$invalid_rc"

missing_workspace_rc=0
env -u GITHUB_WORKSPACE \
  HOME="$TMP_ROOT/home-missing" \
  GIT_SAFE_WORKSPACE_SCOPE=workspace \
  "$CONFIGURE" >/dev/null 2>&1 || missing_workspace_rc=$?
test "$missing_workspace_rc" -eq 64 ||
  fail "missing workspace expected rc=64, got rc=$missing_workspace_rc"
printf 'ok missing-workspace rc=%s\n' "$missing_workspace_rc"

not_repo="$TMP_ROOT/not-repo"
not_repo_home="$TMP_ROOT/home-not-repo"
mkdir -p "$not_repo" "$not_repo_home"
not_repo_rc=0
HOME="$not_repo_home" \
  GITHUB_WORKSPACE="$not_repo" \
  GIT_SAFE_WORKSPACE_SCOPE=workspace \
  "$CONFIGURE" >/dev/null 2>&1 || not_repo_rc=$?
test "$not_repo_rc" -gt 1 || fail "non-repository expected rc>1, got rc=$not_repo_rc"
printf 'ok non-repository rc=%s\n' "$not_repo_rc"

printf 'git safe workspace action self-test: PASS\n'
