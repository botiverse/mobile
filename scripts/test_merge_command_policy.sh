#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="${repo_root}/scripts/check-merge-command-policy.sh"
wrapper="${repo_root}/scripts/guarded-squash-merge.sh"
rebase_wrapper="${repo_root}/scripts/guarded-rebase-merge.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
output="${tmp_dir}/output"

expect_fail() {
  local label="$1"
  shift
  if "$@" >"$output" 2>&1; then
    cat "$output" >&2
    echo "FAIL: expected policy failure: $label" >&2
    exit 1
  fi
  echo "PASS: $label"
}

"$checker" "$wrapper" "$rebase_wrapper"
echo "PASS: repository guarded wrappers satisfy merge command policy"

mkdir -p "${tmp_dir}/no-preflight" "${tmp_dir}/inline-body"
cp "$wrapper" "${tmp_dir}/no-preflight/guarded-squash-merge.sh"
# The mutation targets literal shell source.
# shellcheck disable=SC2016
sed -i.bak '/"\$preflight" "\$repo" "\$pr_number" "\$reviewed_head"/d' \
  "${tmp_dir}/no-preflight/guarded-squash-merge.sh"
expect_fail "removing shared preflight is detected" \
  "$checker" "${tmp_dir}/no-preflight/guarded-squash-merge.sh"

cp "$wrapper" "${tmp_dir}/inline-body/guarded-squash-merge.sh"
# The mutation targets literal shell source.
# shellcheck disable=SC2016
sed -i.bak 's/--body-file "\$body_file"/--body "\$body_file"/' \
  "${tmp_dir}/inline-body/guarded-squash-merge.sh"
expect_fail "replacing body-file with inline body is detected" \
  "$checker" "${tmp_dir}/inline-body/guarded-squash-merge.sh"

cat >"${tmp_dir}/naked-squash.sh" <<'EOF'
#!/usr/bin/env bash
gh pr merge 2008 --squash --body 'subject\n\nSigned-off-by: Bad <bad@mail.build>'
EOF
expect_fail "naked squash inline body is rejected" \
  "$checker" "${tmp_dir}/naked-squash.sh"

cat >"${tmp_dir}/ordinary-merge.sh" <<'EOF'
#!/usr/bin/env bash
gh pr merge 2008 --merge
EOF
expect_fail "ordinary merge mode is rejected" \
  "$checker" "${tmp_dir}/ordinary-merge.sh"

echo "All merge command policy tests passed."
