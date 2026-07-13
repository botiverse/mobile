#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
resolver="$repo_root/scripts/resolve_git_revision.sh"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

git init --bare "$fixture_root/remote.git" >/dev/null
git init --initial-branch=main "$fixture_root/source" >/dev/null
git -C "$fixture_root/source" config user.name "Revision Fixture"
git -C "$fixture_root/source" config user.email "revision-fixture@example.com"
printf 'main\n' > "$fixture_root/source/value.txt"
git -C "$fixture_root/source" add value.txt
git -C "$fixture_root/source" commit -m "fixture main" >/dev/null
main_sha="$(git -C "$fixture_root/source" rev-parse HEAD)"
git -C "$fixture_root/source" tag fixture-tag
git -C "$fixture_root/source" switch -c feature/ref >/dev/null
printf 'feature\n' > "$fixture_root/source/value.txt"
git -C "$fixture_root/source" commit -am "fixture feature" >/dev/null
feature_sha="$(git -C "$fixture_root/source" rev-parse HEAD)"
git -C "$fixture_root/source" remote add origin "$fixture_root/remote.git"
git -C "$fixture_root/source" push origin main feature/ref --tags >/dev/null
git --git-dir="$fixture_root/remote.git" symbolic-ref HEAD refs/heads/main
git clone "$fixture_root/remote.git" "$fixture_root/checkout" >/dev/null

assert_resolves() {
  local revision="$1"
  local expected="$2"
  local actual
  actual="$(cd "$fixture_root/checkout" && "$resolver" "$revision")"
  [[ "$actual" == "$expected" ]] || {
    echo "Expected '$revision' to resolve to '$expected', got '$actual'." >&2
    exit 1
  }
}

assert_resolves "$main_sha" "$main_sha"
assert_resolves "${main_sha:0:10}" "$main_sha"
assert_resolves fixture-tag "$main_sha"
assert_resolves feature/ref "$feature_sha"

if (cd "$fixture_root/checkout" && "$resolver" missing-ref >/dev/null 2>&1); then
  echo "Expected a missing revision to fail." >&2
  exit 1
fi

echo "resolve_git_revision: PASS"
