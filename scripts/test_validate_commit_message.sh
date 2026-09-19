#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator="${repo_root}/scripts/validate-commit-message.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
output="${tmp_dir}/output.log"
signer="Merge Safety <merge-safety@mail.build>"

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

cat >"${tmp_dir}/valid.txt" <<EOF
fix(ui): preserve real newlines

Body paragraph.

Signed-off-by: ${signer}
EOF
expect_pass "real newline footer" "${tmp_dir}/valid.txt"

printf '%s\n' \
  'Bind mounted avatar cache sessions (#2008)' \
  '' \
  'Bind shared mounted avatar state to native conditional loading and committed render identities.\n\nSigned-off-by: KMP-专家 <raft-mobile-kmp-expert@mail.build>' \
  >"${tmp_dir}/literal-newline.txt"
expect_fail \
  "PR #2008 literal-newline incident" \
  "${tmp_dir}/literal-newline.txt" \
  "merge command escaped newlines; use body file"

cat >"${tmp_dir}/missing.txt" <<'EOF'
fix(ui): no trailer at all

Body only.
EOF
expect_pass "message without any sign-off is accepted" "${tmp_dir}/missing.txt"

cat >"${tmp_dir}/multi-body.txt" <<EOF
fix(ui): preserve a structured body

First paragraph.

Second paragraph with details.

Tested-by: CI <ci@example.com>
Signed-off-by: ${signer}
EOF
expect_pass "multi-paragraph body with valid footer" "${tmp_dir}/multi-body.txt"

echo "All commit-message validation tests passed."
