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

git -C "$fixture_repo" switch -q -c literal-squash "$base_commit"
git -C "$fixture_repo" merge -q --squash signed-source
git -C "$fixture_repo" commit -q \
  -m "fix: escaped squash\\n\\nSigned-off-by: Sign-off Test <${signer}>"
literal_squash_commit="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" switch -q main

git -C "$fixture_repo" switch -q -c other-source "$base_commit"
printf 'different payload\n' > "${fixture_repo}/payload.txt"
git -C "$fixture_repo" add payload.txt
git -C "$fixture_repo" commit -q -m "fix: different signed source" -m "Signed-off-by: Sign-off Test <${signer}>"
other_source_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c advanced-base "$base_commit"
printf 'advanced main context\n' > "${fixture_repo}/advanced-main.txt"
git -C "$fixture_repo" add advanced-main.txt
git -C "$fixture_repo" commit -q -m "test: signed advanced main" -m "Signed-off-by: Sign-off Test <${signer}>"
advanced_parent_commit="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" merge -q --squash signed-source
git -C "$fixture_repo" commit -q -m "fix: advanced-base immutable squash without trailer"
advanced_squash_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c advanced-extra "$advanced_parent_commit"
git -C "$fixture_repo" merge -q --squash signed-source
printf 'unexpected immutable-only payload\n' > "${fixture_repo}/advanced-extra.txt"
git -C "$fixture_repo" add advanced-extra.txt
git -C "$fixture_repo" commit -q -m "fix: advanced-base squash with extra path"
advanced_extra_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c unsigned-reviewed-range "$base_commit"
printf 'unsigned reviewed step\n' > "${fixture_repo}/unsigned-range.txt"
git -C "$fixture_repo" add unsigned-range.txt
git -C "$fixture_repo" commit -q -m "fix: unsigned reviewed step\\n\\nSigned-off-by: Sign-off Test <${signer}>"
printf 'signed reviewed head\n' >> "${fixture_repo}/unsigned-range.txt"
git -C "$fixture_repo" add unsigned-range.txt
git -C "$fixture_repo" commit -q -m "fix: signed reviewed head" -m "Signed-off-by: Sign-off Test <${signer}>"
unsigned_reviewed_head="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c positional-root "$base_commit"
printf 'same\nsame\n' > "${fixture_repo}/positional.txt"
git -C "$fixture_repo" add positional.txt
git -C "$fixture_repo" commit -q -m "test: signed positional root" -m "Signed-off-by: Sign-off Test <${signer}>"
positional_source_parent="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" switch -q -c positional-source
printf 'changed\nsame\n' > "${fixture_repo}/positional.txt"
git -C "$fixture_repo" add positional.txt
git -C "$fixture_repo" commit -q -m "fix: signed positional source" -m "Signed-off-by: Sign-off Test <${signer}>"
positional_source_head="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" switch -q -c positional-immutable-parent "$positional_source_parent"
printf 'positional main context\n' > "${fixture_repo}/positional-main.txt"
git -C "$fixture_repo" add positional-main.txt
git -C "$fixture_repo" commit -q -m "test: signed positional main" -m "Signed-off-by: Sign-off Test <${signer}>"
positional_immutable_parent="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" switch -q -c positional-immutable
printf 'same\nchanged\n' > "${fixture_repo}/positional.txt"
git -C "$fixture_repo" add positional.txt
git -C "$fixture_repo" commit -q -m "fix: positional squash with wrong synthetic tree"
positional_immutable_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c merge-source "$squash_commit"
printf 'reviewed merge source\n' > "${fixture_repo}/merge-source.txt"
git -C "$fixture_repo" add merge-source.txt
git -C "$fixture_repo" commit -q -m "fix: signed reviewed merge source" -m "Signed-off-by: Sign-off Test <${signer}>"
merge_source_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q main
printf 'main advanced\n' > "${fixture_repo}/main-side.txt"
git -C "$fixture_repo" add main-side.txt
git -C "$fixture_repo" commit -q -m "test: signed main advance" -m "Signed-off-by: Sign-off Test <${signer}>"
merge_base_commit="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" merge -q --no-ff merge-source -m "merge: immutable clean merge without trailer"
merge_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q -c tampered-merge-source "$merge_commit"
printf 'reviewed second source\n' > "${fixture_repo}/tampered-source.txt"
git -C "$fixture_repo" add tampered-source.txt
git -C "$fixture_repo" commit -q -m "fix: signed tampered merge source" -m "Signed-off-by: Sign-off Test <${signer}>"
tampered_source_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q main
git -C "$fixture_repo" reset -q --hard "$merge_commit"
printf 'next main advance\n' > "${fixture_repo}/next-main-side.txt"
git -C "$fixture_repo" add next-main-side.txt
git -C "$fixture_repo" commit -q -m "test: signed next main advance" -m "Signed-off-by: Sign-off Test <${signer}>"
tampered_base_commit="$(git -C "$fixture_repo" rev-parse HEAD)"
git -C "$fixture_repo" merge -q --no-ff --no-commit tampered-merge-source
printf 'unexpected merge-only payload\n' > "${fixture_repo}/merge-only.txt"
git -C "$fixture_repo" add merge-only.txt
git -C "$fixture_repo" commit -q -m "merge: immutable merge with extra payload"
tampered_merge_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" tag -a non-commit-source "$source_commit" -m "test tag object"
non_commit_source="$(git -C "$fixture_repo" rev-parse 'non-commit-source^{tag}')"

git -C "$fixture_repo" switch -q main
printf 'conflict base\n' > "${fixture_repo}/conflict.txt"
git -C "$fixture_repo" add conflict.txt
git -C "$fixture_repo" commit -q -m "test: signed conflict base" -m "Signed-off-by: Sign-off Test <${signer}>"
git -C "$fixture_repo" switch -q -c conflict-source
printf 'source side\n' > "${fixture_repo}/conflict.txt"
git -C "$fixture_repo" add conflict.txt
git -C "$fixture_repo" commit -q -m "fix: signed conflicting source" -m "Signed-off-by: Sign-off Test <${signer}>"
conflict_source_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

git -C "$fixture_repo" switch -q main
printf 'main side\n' > "${fixture_repo}/conflict.txt"
git -C "$fixture_repo" add conflict.txt
git -C "$fixture_repo" commit -q -m "test: signed conflicting main" -m "Signed-off-by: Sign-off Test <${signer}>"
conflict_base_commit="$(git -C "$fixture_repo" rev-parse HEAD)"
if git -C "$fixture_repo" merge -q --no-ff conflict-source -m "merge: conflicted merge without trailer"; then
  echo "FAIL: conflict fixture unexpectedly merged cleanly" >&2
  exit 1
fi
printf 'manual conflict resolution\n' > "${fixture_repo}/conflict.txt"
git -C "$fixture_repo" add conflict.txt
git -C "$fixture_repo" commit -q -m "merge: conflicted merge without trailer"
conflict_merge_commit="$(git -C "$fixture_repo" rev-parse HEAD)"

octopus_tree="$(git -C "$fixture_repo" rev-parse "${merge_commit}^{tree}")"
octopus_commit="$(printf 'merge: octopus without trailer\n' | git -C "$fixture_repo" commit-tree "$octopus_tree" \
  -p "$merge_base_commit" -p "$merge_source_commit" -p "$other_source_commit")"

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

run_commit_message_checker() {
  local message_file="$1"
  "$checker" --commit-msg "$message_file"
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

printf '%s\n' \
  "fix: escaped squash\\n\\nSigned-off-by: Sign-off Test <${signer}>" \
  > "${tmp_dir}/literal-message.txt"
expect_fail "commit-msg hook rejects literal escaped newline" \
  run_commit_message_checker "${tmp_dir}/literal-message.txt"
grep -Fq "merge command escaped newlines; use body file" "$output"

expect_fail "commit range rejects literal escaped newline" \
  run_checker "$missing_ledger" "$base_commit" "$literal_squash_commit"
grep -Fq "merge command escaped newlines; use body file" "$output"

printf '%s\t%s\t%s\n' "$literal_squash_commit" "$source_commit" "$signer" > "$ledger"
expect_fail "attestation cannot mask malformed literal escaped newline" \
  run_checker "$ledger" "$base_commit" "$literal_squash_commit"
grep -Fq "merge command escaped newlines; use body file" "$output"

printf '%s\t%s\t%s\n' "$squash_commit" "$source_commit" "$signer" > "$ledger"
expect_pass "tree-identical exact attestation" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\t%s\t%s\n' \
  "$advanced_squash_commit" "$source_commit" "$signer" "$base_commit" "$advanced_parent_commit" > "$ledger"
expect_pass "advanced-base squash exact attestation" run_checker "$ledger" "$advanced_parent_commit" "$advanced_squash_commit"

printf '%s\t%s\t%s\t%s\t%s\n' \
  "$advanced_squash_commit" "$source_commit" "$signer" "$squash_commit" "$advanced_parent_commit" > "$ledger"
expect_fail "advanced-base attestation requires exact merge-base source parent" run_checker "$ledger" "$advanced_parent_commit" "$advanced_squash_commit"

printf '%s\t%s\t%s\t%s\t%s\n' \
  "$advanced_squash_commit" "$source_commit" "$signer" "$base_commit" "$base_commit" > "$ledger"
expect_fail "advanced-base attestation requires actual immutable parent" run_checker "$ledger" "$advanced_parent_commit" "$advanced_squash_commit"

printf '%s\t%s\t%s\t%s\t%s\n' \
  "$advanced_extra_commit" "$source_commit" "$signer" "$base_commit" "$advanced_parent_commit" > "$ledger"
expect_fail "advanced-base attestation rejects immutable-only path" run_checker "$ledger" "$advanced_parent_commit" "$advanced_extra_commit"

printf '%s\t%s\t%s\t%s\t%s\n' \
  "$advanced_squash_commit" "$unsigned_reviewed_head" "$signer" "$base_commit" "$advanced_parent_commit" > "$ledger"
expect_fail "advanced-base attestation rejects literal backslash-n fake source trailer" run_checker "$ledger" "$advanced_parent_commit" "$advanced_squash_commit"

printf '%s\t%s\t%s\t%s\t%s\n' \
  "$positional_immutable_commit" "$positional_source_head" "$signer" \
  "$positional_source_parent" "$positional_immutable_parent" > "$ledger"
expect_fail "advanced-base attestation rejects wrong synthetic tree despite matching line delta" \
  run_checker "$ledger" "$positional_immutable_parent" "$positional_immutable_commit"

printf '%s\t%s\t%s\n' "$squash_commit" "$source_commit" 'raft-mobile-wrong@mail.build' > "$ledger"
expect_fail "wrong exact signer" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$signer" > "$ledger"
expect_fail "missing source commit" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" "$non_commit_source" "$signer" > "$ledger"
expect_fail "source must be an exact commit object" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$squash_commit" "$other_source_commit" "$signer" > "$ledger"
expect_fail "different source tree" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "${squash_commit:0:12}" "$source_commit" "$signer" > "$ledger"
expect_fail "short immutable SHA" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "${squash_commit:0:39}*" "$source_commit" "$signer" > "$ledger"
expect_fail "wildcard immutable SHA" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\n' "$squash_commit" "$source_commit" > "$ledger"
expect_fail "malformed ledger row" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\t%s\n' "$squash_commit" "$source_commit" "$signer" "$base_commit" > "$ledger"
expect_fail "four-field ledger row" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n%s\t%s\t%s\n' \
  "$squash_commit" "$source_commit" "$signer" \
  "$squash_commit" "$source_commit" "$signer" > "$ledger"
expect_fail "duplicate immutable SHA" run_checker "$ledger" "$base_commit" "$squash_commit"

printf '%s\t%s\t%s\n' "$merge_commit" "$merge_source_commit" "$signer" > "$ledger"
expect_pass "clean two-parent merge attestation" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

merge_tree="$(git -C "$fixture_repo" rev-parse "${merge_commit}^{tree}")"
merge_paths="$(git -C "$fixture_repo" diff --no-renames --name-only "$merge_base_commit" "$merge_commit" | awk 'NF { count++ } END { print count + 0 }')"
read -r merge_insertions merge_deletions <<< "$(
  git -C "$fixture_repo" diff --no-renames --numstat "$merge_base_commit" "$merge_commit" |
    awk 'NF { additions += $1; deletions += $2 } END { print additions + 0, deletions + 0 }'
)"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$squash_commit" "$merge_base_commit" \
  "$merge_tree" "$merge_paths" "$merge_insertions" "$merge_deletions" > "$ledger"
expect_pass "strict clean two-parent merge attestation" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$base_commit" "$merge_base_commit" \
  "$merge_tree" "$merge_paths" "$merge_insertions" "$merge_deletions" > "$ledger"
expect_fail "strict merge attestation requires exact source parent" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$squash_commit" "$advanced_parent_commit" \
  "$merge_tree" "$merge_paths" "$merge_insertions" "$merge_deletions" > "$ledger"
expect_fail "strict merge attestation requires actual first parent" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$squash_commit" "$merge_base_commit" \
  "$source_commit" "$merge_paths" "$merge_insertions" "$merge_deletions" > "$ledger"
expect_fail "strict merge attestation requires exact immutable tree" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$squash_commit" "$merge_base_commit" \
  "$merge_tree" "$((merge_paths + 1))" "$merge_insertions" "$merge_deletions" > "$ledger"
expect_fail "strict merge attestation requires exact path count" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$squash_commit" "$merge_base_commit" \
  "$merge_tree" "$merge_paths" "$((merge_insertions + 1))" "$merge_deletions" > "$ledger"
expect_fail "strict merge attestation requires exact insertion count" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$merge_commit" "$merge_source_commit" "$signer" "$squash_commit" "$merge_base_commit" \
  "$merge_tree" "$merge_paths" "$merge_insertions" "$((merge_deletions + 1))" > "$ledger"
expect_fail "strict merge attestation requires exact deletion count" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$tampered_merge_commit" "$tampered_source_commit" "$signer" "$merge_commit" "$tampered_base_commit" \
  "$(git -C "$fixture_repo" rev-parse "${tampered_merge_commit}^{tree}")" \
  "$(git -C "$fixture_repo" diff --no-renames --name-only "$tampered_base_commit" "$tampered_merge_commit" | awk 'NF { count++ } END { print count + 0 }')" \
  "$(git -C "$fixture_repo" diff --no-renames --numstat "$tampered_base_commit" "$tampered_merge_commit" | awk 'NF { additions += $1 } END { print additions + 0 }')" \
  "$(git -C "$fixture_repo" diff --no-renames --numstat "$tampered_base_commit" "$tampered_merge_commit" | awk 'NF { deletions += $2 } END { print deletions + 0 }')" > "$ledger"
expect_fail "strict merge attestation rejects merge-only payload" run_checker "$ledger" "$tampered_base_commit" "$tampered_merge_commit"

printf '%s\t%s\t%s\n' "$merge_commit" "$source_commit" "$signer" > "$ledger"
expect_fail "merge attestation requires reviewed second parent" run_checker "$ledger" "$merge_base_commit" "$merge_commit"

printf '%s\t%s\t%s\n' "$tampered_merge_commit" "$tampered_source_commit" "$signer" > "$ledger"
expect_fail "merge-only payload differs from clean merge tree" run_checker "$ledger" "$tampered_base_commit" "$tampered_merge_commit"

printf '%s\t%s\t%s\n' "$conflict_merge_commit" "$conflict_source_commit" "$signer" > "$ledger"
expect_fail "conflicted merge is not attestable" run_checker "$ledger" "$conflict_base_commit" "$conflict_merge_commit"

printf '%s\t%s\t%s\n' "$octopus_commit" "$merge_source_commit" "$signer" > "$ledger"
expect_fail "octopus merge is not attestable" run_checker "$ledger" "$merge_base_commit" "$octopus_commit"

for malformed_attested_commit in \
  7fdeb172ad03f484e64f66fe618b21d5b9a89250 \
  8d1eb30929bdde9e53c73c3eea4e9e112e9dde93; do
  attested_parent="$(git -C "$repo_root" rev-parse "${malformed_attested_commit}^")"
  expect_fail "repository attestation cannot mask malformed ${malformed_attested_commit}" \
    run_repository_checker "$attested_parent" "$malformed_attested_commit"
  grep -Fq "merge command escaped newlines; use body file" "$output"
done

attested_commit=93295966bb3bc5ce945d2fc3e7dbd9b8ed1c6803
attested_parent="$(git -C "$repo_root" rev-parse "${attested_commit}^")"
expect_pass "repository attestation ${attested_commit}" \
  run_repository_checker "$attested_parent" "$attested_commit"

attested_parent="$(git -C "$repo_root" rev-parse "63d87164e03bbc576b6ce5f8dcfdce4b8d291439^")"
expect_fail "repository attestation cannot mask duplicate source trailer history" \
  run_repository_checker "$attested_parent" 63d87164e03bbc576b6ce5f8dcfdce4b8d291439
grep -Fq "found 3" "$output"

attested_parent="$(git -C "$repo_root" rev-parse "1c13ae1631e038f1c71c36f4e5b18d263f2f4777^1")"
expect_pass "repository strict merge attestation 1c13ae1631e038f1c71c36f4e5b18d263f2f4777" \
  run_repository_checker "$attested_parent" 1c13ae1631e038f1c71c36f4e5b18d263f2f4777

echo "All commit sign-off attestation tests passed."
