#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator="${repo_root}/scripts/validate-landed-commit-message.sh"
checker="${repo_root}/scripts/check-landed-commit-signoff.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
output="${tmp_dir}/output.log"

expect_pass() {
  local label="$1"
  local fixture="$2"
  if ! "$validator" "$fixture" >"$output" 2>&1; then
    cat "$output" >&2
    echo "FAIL: expected pass: $label" >&2
    exit 1
  fi
  echo "PASS: $label"
}

expect_fail() {
  local label="$1"
  local fixture="$2"
  local diagnostic="$3"
  if "$validator" "$fixture" >"$output" 2>&1; then
    cat "$output" >&2
    echo "FAIL: expected failure: $label" >&2
    exit 1
  fi
  grep -Fq "$diagnostic" "$output" || {
    cat "$output" >&2
    echo "FAIL: missing diagnostic for: $label" >&2
    exit 1
  }
  echo "PASS: $label"
}

cat >"${tmp_dir}/single.txt" <<'EOF'
fix(ui): landed single commit

Body paragraph.

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
expect_pass "one parsed landing trailer" "${tmp_dir}/single.txt"

cat >"${tmp_dir}/historical.txt" <<'EOF'
fix(ui): squash preserves the final trailer

Source commit A carried this historical text:
Signed-off-by: Source A <source-a@mail.build>

Source commit B carried this historical text:
Signed-off-by: Source B <source-b@mail.build>

The lines above are source history, not the landing footer.

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
whole_count="$(awk '
  tolower($0) ~ /^[[:space:]]*signed-off-by([[:space:]]|:|$)/ { count++ }
  END { print count + 0 }
' "${tmp_dir}/historical.txt")"
[ "$whole_count" -gt 1 ] || {
  echo "FAIL: historical fixture must contain multiple whole-message sign-offs" >&2
  exit 1
}
expect_pass "whole-message history may contain multiple sign-offs" "${tmp_dir}/historical.txt"

cat >"${tmp_dir}/marker.txt" <<'EOF'
fix(ui): landed audit marker is harmless

Raft-Guarded-Squash: receipt-v1
Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
expect_pass "optional audit marker does not change parsed sign-off count" "${tmp_dir}/marker.txt"

cat >"${tmp_dir}/missing.txt" <<'EOF'
fix(ui): missing landing trailer

Body only.
EOF
expect_fail "parsed zero" "${tmp_dir}/missing.txt" "parsed 0"

cat >"${tmp_dir}/duplicate.txt" <<'EOF'
fix(ui): duplicate landing trailers

Signed-off-by: First <first@mail.build>
Signed-off-by: Second <second@mail.build>
EOF
expect_fail "parsed two" "${tmp_dir}/duplicate.txt" "parsed 2"

cat >"${tmp_dir}/wrong-domain.txt" <<'EOF'
fix(ui): wrong landing signer domain

Signed-off-by: Merge Safety <merge-safety@example.com>
EOF
expect_fail "wrong signer domain" "${tmp_dir}/wrong-domain.txt" "@mail.build"

printf '%s\n' \
  'fix(ui): escaped landing message' \
  '' \
  'Signed-off-by: Merge Safety <merge-safety@mail.build>\n' \
  >"${tmp_dir}/escaped.txt"
expect_fail "literal escaped newline" "${tmp_dir}/escaped.txt" "escaped newlines"

git_dir="${tmp_dir}/repo"
git init -q "$git_dir"
git -C "$git_dir" config user.name "Landing Test"
git -C "$git_dir" config user.email "landing-test@mail.build"
printf 'base\n' >"${git_dir}/file"
git -C "$git_dir" add file
git -C "$git_dir" commit -q -m 'base'
base="$(git -C "$git_dir" rev-parse HEAD)"
git -C "$git_dir" commit --allow-empty -q -F "${tmp_dir}/historical.txt"
head="$(git -C "$git_dir" rev-parse HEAD)"
(cd "$git_dir" && "$checker" "$base" "$head") >"$output"
grep -Fq "landed commit sign-off: OK" "$output" || {
  cat "$output" >&2
  echo "FAIL: landed range checker did not accept a valid squash-style message" >&2
  exit 1
}

git -C "$git_dir" commit --allow-empty -q -F "${tmp_dir}/duplicate.txt"
bad_head="$(git -C "$git_dir" rev-parse HEAD)"
if (cd "$git_dir" && "$checker" "$head" "$bad_head") >"$output" 2>&1; then
  cat "$output" >&2
  echo "FAIL: landed range checker accepted parsed=2" >&2
  exit 1
fi
grep -Fq "parsed 2" "$output" || {
  cat "$output" >&2
  echo "FAIL: landed range checker lost parsed=2 diagnostic" >&2
  exit 1
}

echo "All landed commit-message validation tests passed."
