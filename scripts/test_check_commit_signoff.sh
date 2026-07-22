#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="${repo_root}/scripts/check-commit-signoff.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

fixture_repo="${tmp_dir}/repo"
ledger="${tmp_dir}/attestations.tsv"
output="${tmp_dir}/output.log"
signer="raft-mobile-test-signer@mail.build"

git init -q -b main "$fixture_repo"
git -C "$fixture_repo" config user.name "Sign-off Test"
git -C "$fixture_repo" config user.email "$signer"

printf 'base\n' > "${fixture_repo}/payload.txt"
git -C "$fixture_repo" add payload.txt
git -C "$fixture_repo" commit -q -m "test: base" -m "Signed-off-by: Sign-off Test <${signer}>"
base_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c signed-source
printf 'release payload\n' > "${fixture_repo}/payload.txt"
git -C "$fixture_repo" add payload.txt
git -C "$fixture_repo" commit -q -m "fix: signed source" -m "Signed-off-by: Sign-off Test <${signer}>"
source_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q main
git -C "$fixture_repo" merge -q --squash signed-source
git -C "$fixture_repo" commit -q -m "fix: immutable squash without trailer"
squash_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c other-source "$base_commit"
printf 'different payload\n' > "${fixture_repo}/payload.txt"
git -C "$fixture_repo" add payload.txt
git -C "$fixture_repo" commit -q -m "fix: different signed source" -m "Signed-off-by: Sign-off Test <${signer}>"
other_source_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

run_checker() {
  local ledger_path="$1"
  local range_base="$2"
  local range_head="$3"
  (
    cd "$fixture_repo"
    COMMIT_SIGNOFF_ATTESTATIONS_FILE="$ledger_path" "$checker" "$range_base" "$range_head"
  )
}

run_repository_checker() {
  local range_base="$1"
  local range_head="$2"
  (
    cd "$repo_root"
    "$checker" "$range_base" "$range_head"
  )
}

expect_pass() {
  local label="$1"
  shift
  if ! "$@" >"$output" 2>&1; then
    cat "$output" >&2
    echo "FAIL: expected pass: $label" >&2
    exit 1
  fi
  echo "PASS: $label"
}

expect_fail() {
  local label="$1"
  shift
  if "$@" >"$output" 2>&1; then
    cat "$output" >&2
    echo "FAIL: expected failure: $label" >&2
    exit 1
  fi
  echo "PASS: $label"
}

missing_ledger="${tmp_dir}/missing.tsv"
expect_pass "ordinary signed commit" run_checker "$missing_ledger" "$base_commit" "$source_commit"
expect_fail "unsigned unlisted squash" run_checker "$missing_ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" "$source_commit" "$signer" > "$ledger"
expect_pass "tree-identical exact attestation" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" "$source_commit" 'raft-mobile-wrong@mail.build' > "$ledger"
expect_fail "wrong exact signer" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$signer" > "$ledger"
expect_fail "missing source commit" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" "$other_source_commit" "$signer" > "$ledger"
expect_fail "different source tree" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "${squash_commit:0:12}" "$source_commit" "$signer" > "$ledger"
expect_fail "short immutable SHA" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "${squash_commit:0:39}*" "$source_commit" "$signer" > "$ledger"
expect_fail "wildcard immutable SHA" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\n' "$squash_commit" "$source_commit" > "$ledger"
expect_fail "malformed ledger row" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n%s\t%s\t%s\n' \
  "$squash_commit" "$source_commit" "$signer" \
  "$squash_commit" "$source_commit" "$signer" > "$ledger"
expect_fail "duplicate immutable SHA" run_checker "$ledger" "$base_commit" "$squash_commit"

for attested_commit in \
  7fdeb172ad03f484e64f66fe618b21d5b9a89250 \
  93295966bb3bc5ce945d2fc3e7dbd9b8ed1c6803; do
  attested_parent="$(git -C "$repo_root" rev-parse "${attested_commit}^")"
  expect_pass "repository attestation ${attested_commit}" \
    run_repository_checker "$attested_parent" "$attested_commit"
done

echo "All commit sign-off attestation tests passed."
