#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator="${repo_root}/scripts/validate-landed-commit-message.sh"
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
fix(ui): landing message without a trailer

Body only.
EOF
expect_pass "landed message without any sign-off is accepted" "${tmp_dir}/missing.txt"

printf '%s\n' \
  'fix(ui): escaped landing message' \
  '' \
  'Signed-off-by: Merge Safety <merge-safety@mail.build>\n' \
  >"${tmp_dir}/escaped.txt"
expect_fail "literal escaped newline" "${tmp_dir}/escaped.txt" "escaped newlines"

echo "All landed commit-message validation tests passed."
