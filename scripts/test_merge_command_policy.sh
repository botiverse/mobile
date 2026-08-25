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
echo "PASS: exact guarded-wrapper paths may use an indirect command variable"

make_wrapper_fixture() {
  local fixture_root="$1"
  mkdir -p "${fixture_root}/scripts"
  cp "$checker" "${fixture_root}/scripts/check-merge-command-policy.sh"
  cp "$wrapper" "${fixture_root}/scripts/guarded-squash-merge.sh"
  cp "$rebase_wrapper" "${fixture_root}/scripts/guarded-rebase-merge.sh"
}

make_wrapper_fixture "${tmp_dir}/no-preflight"
# The mutation targets literal shell source.
# shellcheck disable=SC2016
sed -i.bak '/"\$preflight" "\$repo" "\$pr_number" "\$reviewed_head"/d' \
  "${tmp_dir}/no-preflight/scripts/guarded-squash-merge.sh"
expect_fail "removing shared preflight is detected" \
  "${tmp_dir}/no-preflight/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/no-preflight/scripts/guarded-squash-merge.sh"

make_wrapper_fixture "${tmp_dir}/inline-body"
# The mutation targets literal shell source.
# shellcheck disable=SC2016
sed -i.bak 's/--body-file "\$body_file"/--body "\$body_file"/' \
  "${tmp_dir}/inline-body/scripts/guarded-squash-merge.sh"
expect_fail "replacing body-file with inline body is detected" \
  "${tmp_dir}/inline-body/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/inline-body/scripts/guarded-squash-merge.sh"

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

cat >"${tmp_dir}/direct-squash.sh" <<'EOF'
#!/usr/bin/env bash
gh pr merge 2008 --squash --body-file message.txt
EOF
expect_fail "direct squash merge is rejected outside a guarded wrapper" \
  "$checker" "${tmp_dir}/direct-squash.sh"

cat >"${tmp_dir}/continued-direct-squash.sh" <<'EOF'
#!/usr/bin/env bash
gh \
  pr \
  merge 2008 \
  --squash \
  --body-file message.txt
EOF
expect_fail "continued direct squash merge is rejected outside a guarded wrapper" \
  "$checker" "${tmp_dir}/continued-direct-squash.sh"

cat >"${tmp_dir}/indirect-direct-squash.sh" <<'EOF'
#!/usr/bin/env bash
merge_cli="${MERGE_CLI:?}"
"$merge_cli" pr merge 2008 --squash --body-file message.txt
EOF
expect_fail "indirect variable merge is rejected outside a guarded wrapper" \
  "$checker" "${tmp_dir}/indirect-direct-squash.sh"

cat >"${tmp_dir}/assigned-command-direct-squash.sh" <<'EOF'
#!/usr/bin/env bash
merge_command="gh pr merge"
$merge_command 2008 --squash --body-file message.txt
EOF
expect_fail "assigned command-string merge is rejected outside a guarded wrapper" \
  "$checker" "${tmp_dir}/assigned-command-direct-squash.sh"

make_wrapper_fixture "${tmp_dir}/no-landing-receipt"
# The mutation removes the post-merge receipt block while leaving the command
# itself intact; policy must require the landing readback contract.
sed -i.bak '/# The merge API has now created an immutable commit\./,$d' \
  "${tmp_dir}/no-landing-receipt/scripts/guarded-squash-merge.sh"
expect_fail "removing landing receipt is detected" \
  "${tmp_dir}/no-landing-receipt/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/no-landing-receipt/scripts/guarded-squash-merge.sh"

make_wrapper_fixture "${tmp_dir}/no-indeterminate-guard"
sed -i.bak '/mergeOutcome=INDETERMINATE/d; /STOP_NO_AUTOMATIC_RETRY/d' \
  "${tmp_dir}/no-indeterminate-guard/scripts/guarded-squash-merge.sh"
expect_fail "removing uncertain-readback stop is detected" \
  "${tmp_dir}/no-indeterminate-guard/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/no-indeterminate-guard/scripts/guarded-squash-merge.sh"

make_wrapper_fixture "${tmp_dir}/no-blocking-policy"
sed -i.bak '/blocking_evidence_policy=/d; /blockingEvidencePolicy=/d' \
  "${tmp_dir}/no-blocking-policy/scripts/guarded-squash-merge.sh"
expect_fail "removing repository-specific blocking policy is detected" \
  "${tmp_dir}/no-blocking-policy/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/no-blocking-policy/scripts/guarded-squash-merge.sh"

make_wrapper_fixture "${tmp_dir}/no-receipt-version"
sed -i.bak '/tool_version=/d; /spec_version=/d' \
  "${tmp_dir}/no-receipt-version/scripts/guarded-squash-merge.sh"
expect_fail "removing receipt interpretation versions is detected" \
  "${tmp_dir}/no-receipt-version/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/no-receipt-version/scripts/guarded-squash-merge.sh"

make_wrapper_fixture "${tmp_dir}/malformed-receipt-version"
sed -i.bak \
  's/tool_version="guarded-squash-merge\.v1"/tool_version=""/; s/spec_version="guarded-squash-receipt\.v1"/spec_version="v1"/' \
  "${tmp_dir}/malformed-receipt-version/scripts/guarded-squash-merge.sh"
expect_fail "malformed receipt interpretation versions are detected" \
  "${tmp_dir}/malformed-receipt-version/scripts/check-merge-command-policy.sh" \
  "${tmp_dir}/malformed-receipt-version/scripts/guarded-squash-merge.sh"

echo "All merge command policy tests passed."
