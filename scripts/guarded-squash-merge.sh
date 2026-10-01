#!/usr/bin/env bash
set -euo pipefail

repo="${1:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha> <message-file|->}"
pr_number="${2:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha> <message-file|->}"
reviewed_head="${3:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha> <message-file|->}"
message_source="${4:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha> <message-file|->}"
[ "$#" -eq 4 ] || {
  echo "usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha> <message-file|->" >&2
  exit 2
}

[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
  echo "invalid repository: $repo" >&2
  exit 2
}
[[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || {
  echo "invalid PR number: $pr_number" >&2
  exit 2
}
[[ "$reviewed_head" =~ ^[0-9a-f]{40}$ ]] || {
  echo "reviewed head must be one full lowercase commit SHA" >&2
  exit 2
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
preflight="${GUARDED_MERGE_PREFLIGHT:-${script_dir}/guarded-merge-preflight.sh}"
validator="${MERGE_MESSAGE_VALIDATOR:-${script_dir}/validate-commit-message.sh}"
landing_validator="${LANDED_COMMIT_MESSAGE_VALIDATOR:-${script_dir}/validate-landed-commit-message.sh}"
gh_bin="${GH_BIN:-gh}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
candidate_file="${tmp_dir}/candidate-message.txt"
body_file="${tmp_dir}/body.txt"
landed_file="${tmp_dir}/landed-message.txt"
merge_view_file="${tmp_dir}/merge-view.json"
reviewed_commit_file="${tmp_dir}/reviewed-commit.json"
reviewed_message_file="${tmp_dir}/reviewed-message.txt"
landed_commit_file="${tmp_dir}/landed-commit.json"
pr_before_file="${tmp_dir}/pr-before.json"
compare_file="${tmp_dir}/review-compare.json"
receipt_file="${GUARDED_MERGE_RECEIPT_FILE:-}"
receipt_tmp="${tmp_dir}/receipt.txt"

receipt() {
  printf '%s\n' "$*" >> "$receipt_tmp"
}

prepare_receipt_destination() {
  local receipt_dir receipt_probe
  [ -n "$receipt_file" ] || return 0
  if [ -d "$receipt_file" ]; then
    echo "refusing guarded squash: receipt path is a directory: $receipt_file" >&2
    return 1
  fi
  receipt_dir="$(dirname -- "$receipt_file")"
  if ! mkdir -p -- "$receipt_dir"; then
    echo "refusing guarded squash: cannot create receipt directory: $receipt_dir" >&2
    return 1
  fi
  if ! receipt_probe="$(mktemp "${receipt_dir}/.guarded-squash-receipt.XXXXXX")"; then
    echo "refusing guarded squash: receipt directory is not writable: $receipt_dir" >&2
    return 1
  fi
  rm -f -- "$receipt_probe"
}

finish_receipt() {
  local status=0
  local receipt_dir receipt_stage=""
  if [ -n "$receipt_file" ]; then
    if [ -d "$receipt_file" ]; then
      echo "guarded squash receipt: cannot write receipt; path is a directory: $receipt_file" >&2
      status=1
    else
      receipt_dir="$(dirname -- "$receipt_file")"
      if ! mkdir -p -- "$receipt_dir"; then
        echo "guarded squash receipt: cannot create receipt directory: $receipt_dir" >&2
        status=1
      elif ! receipt_stage="$(mktemp "${receipt_dir}/.guarded-squash-receipt.XXXXXX")"; then
        echo "guarded squash receipt: receipt directory is not writable: $receipt_dir" >&2
        status=1
      elif ! cp -- "$receipt_tmp" "$receipt_stage" || ! mv -f -- "$receipt_stage" "$receipt_file"; then
        echo "guarded squash receipt: cannot atomically write receipt file: $receipt_file" >&2
        rm -f -- "$receipt_stage"
        status=1
      fi
    fi
  fi
  if ! cat "$receipt_tmp"; then
    status=1
  fi
  return "$status"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

# This is deliberately narrower than a generic "merge is blocked" rule.  It
# is bound to the currently observed botiverse/mobile `main` ruleset
# (18537125): the active rules are deletion/non_fast_forward/pull_request, and
# the pull_request rule currently requires neither status checks nor approvals.
# Re-run
# `gh api repos/botiverse/mobile/rulesets/18537125` and update this policy if
# task #453 changes those gates.  In particular, a red check or BLOCKED
# mergeStateStatus is not evidence here unless the ruleset makes it a required
# gate; mergeable=CONFLICTING is structural evidence independent of that rule.
classify_unmerged_readback() {
  local state="$1"
  local merged_at="$2"
  local merge_commit="$3"
  local mergeable="$4"

  if [[ "$blocking_evidence_policy" != ruleset-snapshot=* ]]; then
    printf '%s\t%s\t%s\n' "INDETERMINATE" "repository-specific blocking policy is not bound" "POLICY_UNBOUND"
  elif [ "$state" = "CLOSED" ] && [ -z "$merged_at" ] && [ -z "$merge_commit" ]; then
    printf '%s\t%s\t%s\n' "NOT_MERGED" "state=CLOSED,mergedAt=null,mergeCommit=null" "CLOSED_AFFIRMATIVE"
  elif [ "$mergeable" = "CONFLICTING" ] && [ -z "$merge_commit" ]; then
    printf '%s\t%s\t%s\n' "NOT_MERGED" "mergeable=CONFLICTING,mergeCommit=null" "CONFLICTING_AFFIRMATIVE"
  elif [ "$mergeable" = "UNKNOWN" ]; then
    # UNKNOWN is an explicit transient value from GitHub, not an unavailable
    # read. It is never affirmative conflict evidence. A current spot check
    # found UNKNOWN on most open PRs in this repository, so this branch is an
    # expected outcome rather than a reason to weaken the evidence policy.
    printf '%s\t%s\t%s\n' "INDETERMINATE" "mergeable=UNKNOWN,transient-value-no-conflict-evidence" "UNKNOWN_TRANSIENT"
  else
    printf '%s\t%s\t%s\n' "INDETERMINATE" "no affirmative merge or current blocking evidence" "NO_AFFIRMATIVE_EVIDENCE"
  fi
}

merged_outcome() {
  if [ "$merge_command_status" -ne 0 ]; then
    printf '%s\n' "MERGED_WITH_CLI_ERROR"
  else
    printf '%s\n' "MERGED"
  fi
}

finish_merged_readback_failure() {
  local reason="$1"
  receipt "guarded squash receipt: mergeOutcome=$(merged_outcome) state=${merge_state} mergedAt=${merged_at:-unknown} mergeable=${mergeable:-unknown} mergeStateStatus=${merge_state_status:-unknown} blockingEvidencePolicy=${blocking_evidence_policy} mergeCommit=${merge_commit} action=STOP_NO_AUTOMATIC_RETRY"
  receipt "guarded squash receipt: FAILED ${reason} (post-merge alert; no rollback)"
  finish_receipt || true
  exit 1
}

blocking_evidence_policy="UNBOUND_REPOSITORY_POLICY"
# Durable receipts need stable interpretation anchors. Bump these values when
# the wrapper's state machine or receipt field meanings change; do not infer a
# historical version from a comment that may be edited after a receipt exists.
tool_version="guarded-squash-merge.v1"
spec_version="guarded-squash-receipt.v1"

if [ "$message_source" = "-" ]; then
  cat > "$candidate_file"
else
  [ -f "$message_source" ] || {
    echo "refusing guarded squash: message file does not exist: $message_source" >&2
    exit 1
  }
  cp "$message_source" "$candidate_file"
fi

"$validator" "$candidate_file"
second_line="$(sed -n '2p' "$candidate_file")"
[ -z "$second_line" ] || {
  echo "refusing guarded squash: complete message file must separate subject and body with one blank line" >&2
  exit 1
}
subject="$(sed -n '1p' "$candidate_file")"
sed '1,2d' "$candidate_file" > "$body_file"

reconstructed="${tmp_dir}/reconstructed-message.txt"
{
  printf '%s\n\n' "$subject"
  cat "$body_file"
} > "$reconstructed"
cmp -s "$candidate_file" "$reconstructed" || {
  echo "refusing guarded squash: message file must be LF-only and end with a newline" >&2
  exit 1
}

# Keep the existing strict candidate check above, then validate the exact
# reconstructed squash payload with the landing rule. The latter is
# parsed-footer-only because a landed squash may carry historical source
# sign-offs.
if ! candidate_landing_error="$("$landing_validator" "$reconstructed" 2>&1)"; then
  echo "refusing guarded squash: reconstructed candidate failed landing trailer validation: ${candidate_landing_error}" >&2
  exit 1
fi
prepare_receipt_destination
receipt "guarded squash receipt: tool_version=${tool_version} spec_version=${spec_version}"

GH_BIN="$gh_bin" MERGE_MESSAGE_VALIDATOR="$validator" \
  "$preflight" "$repo" "$pr_number" "$reviewed_head"

# Capture the reviewed head's exact tree before --delete-branch. The SHA
# remains the verification anchor.
if ! "$gh_bin" api "repos/${repo}/git/commits/${reviewed_head}" > "$reviewed_commit_file"; then
  echo "refusing guarded squash: cannot read reviewed head object ${reviewed_head}" >&2
  exit 1
fi
observed_reviewed_head="$(jq -r '.sha // ""' "$reviewed_commit_file")"
reviewed_tree="$(jq -r '.tree.sha // ""' "$reviewed_commit_file")"
jq -r '.message // ""' "$reviewed_commit_file" > "$reviewed_message_file"
[ "$observed_reviewed_head" = "$reviewed_head" ] || {
  echo "refusing guarded squash: reviewed object SHA=${observed_reviewed_head:-missing}, expected ${reviewed_head}" >&2
  exit 1
}
[[ "$reviewed_tree" =~ ^[0-9a-f]{40}$ ]] || {
  echo "refusing guarded squash: reviewed head tree is missing or invalid" >&2
  exit 1
}

# Preserve the review-time base and its merge-base with the reviewed head
# before --delete-branch. This makes a later tree DIFFERENT result
# interpretable without relying on future pull-ref retention.
if ! "$gh_bin" api "repos/${repo}/pulls/${pr_number}" > "$pr_before_file"; then
  echo "refusing guarded squash: cannot read review-time PR base" >&2
  exit 1
fi
review_base_sha="$(jq -r '.base.sha // ""' "$pr_before_file")"
review_base_ref="$(jq -r '.base.ref // ""' "$pr_before_file")"
review_pr_head="$(jq -r '.head.sha // ""' "$pr_before_file")"
[[ "$review_base_sha" =~ ^[0-9a-f]{40}$ ]] || {
  echo "refusing guarded squash: review-time PR base SHA is missing or invalid" >&2
  exit 1
}
[ "$review_pr_head" = "$reviewed_head" ] || {
  echo "refusing guarded squash: review-time PR head=${review_pr_head:-missing}, expected ${reviewed_head}" >&2
  exit 1
}
if [ "$repo" = "botiverse/mobile" ] && [ "$review_base_ref" = "main" ]; then
  blocking_evidence_policy="ruleset-snapshot=18537125;required_status_checks=0;required_approvals=0"
fi
if ! "$gh_bin" api "repos/${repo}/compare/${review_base_sha}...${reviewed_head}" > "$compare_file"; then
  echo "refusing guarded squash: cannot calculate review-time merge-base" >&2
  exit 1
fi
base_at_review="$(jq -r '.merge_base_commit.sha // ""' "$compare_file")"
[[ "$base_at_review" =~ ^[0-9a-f]{40}$ ]] || {
  echo "refusing guarded squash: review-time merge-base is missing or invalid" >&2
  exit 1
}

merge_command_status=0
if "$gh_bin" pr merge "$pr_number" \
  --repo "$repo" \
  --squash \
  --match-head-commit "$reviewed_head" \
  --delete-branch \
  --subject "$subject" \
  --body-file "$body_file"; then
  :
else
  merge_command_status=$?
  # A CLI/network failure can race with a successful server-side merge. Keep
  # reading the authoritative PR state so an immutable landing is never left
  # without a receipt; the non-zero command status remains an alert.
  receipt "guarded squash receipt: merge command exit=${merge_command_status}; attempting post-merge readback"
fi

# The merge API has now created an immutable commit. Read the actual PR
# mergeCommit and its raw GitHub commit message before reporting success. This
# is a receipt/alert, not a rollback gate: a bad landing already exists and
# protected main must not be rewritten to repair it.
# This wrapper performs one authoritative readback. `UNKNOWN` is an explicit
# transient value, not an unavailable response; polling it again is deliberately
# outside this one-way-safe state machine and cannot upgrade an indeterminate
# result into a conclusion.
if ! "$gh_bin" pr view "$pr_number" \
  --repo "$repo" \
  --json state,mergedAt,mergeCommit,headRefOid,mergeable,mergeStateStatus,url > "$merge_view_file"; then
  receipt "guarded squash receipt: mergeOutcome=INDETERMINATE readback=UNAVAILABLE blockingEvidencePolicy=${blocking_evidence_policy} action=STOP_NO_AUTOMATIC_RETRY"
  receipt "guarded squash receipt: FAILED to read post-merge PR state for ${repo}#${pr_number}"
  finish_receipt || true
  exit 1
fi

merge_state="$(jq -r '.state // ""' "$merge_view_file" | tr '[:lower:]' '[:upper:]')"
merged_at="$(jq -r '.mergedAt // ""' "$merge_view_file")"
merge_commit="$(jq -r '.mergeCommit.oid // ""' "$merge_view_file")"
pr_head="$(jq -r '.headRefOid // ""' "$merge_view_file")"
mergeable="$(jq -r '.mergeable // ""' "$merge_view_file" | tr '[:lower:]' '[:upper:]')"
merge_state_status="$(jq -r '.mergeStateStatus // ""' "$merge_view_file" | tr '[:lower:]' '[:upper:]')"
merge_url="$(jq -r '.url // ""' "$merge_view_file")"

if [ "$merge_state" != "MERGED" ] || [[ ! "$merge_commit" =~ ^[0-9a-f]{40}$ ]]; then
  IFS=$'\t' read -r merge_outcome merge_evidence merge_evidence_class <<< "$(classify_unmerged_readback "$merge_state" "$merged_at" "$merge_commit" "$mergeable")"
  receipt "guarded squash receipt: mergeOutcome=${merge_outcome} state=${merge_state:-missing} mergedAt=${merged_at:-null} mergeCommit=${merge_commit:-null} mergeable=${mergeable:-unknown} mergeStateStatus=${merge_state_status:-unknown} blockingEvidencePolicy=${blocking_evidence_policy} evidenceClass=${merge_evidence_class} evidence=${merge_evidence} action=STOP_NO_AUTOMATIC_RETRY"
  receipt "guarded squash receipt: post-merge state was not treated as a successful landing"
  finish_receipt || true
  exit 1
fi

merge_outcome="$(merged_outcome)"

if ! "$gh_bin" api "repos/${repo}/git/commits/${merge_commit}" > "$landed_commit_file"; then
  finish_merged_readback_failure "to read landed commit object ${merge_commit}"
fi
observed_merge_commit="$(jq -r '.sha // ""' "$landed_commit_file")"
merge_tree="$(jq -r '.tree.sha // ""' "$landed_commit_file")"
base_at_merge="$(jq -r '.parents[0].sha // ""' "$landed_commit_file")"
jq -r '.message // ""' "$landed_commit_file" > "$landed_file"
[ -s "$landed_file" ] || {
  finish_merged_readback_failure "landed %B is empty for ${merge_commit}"
}
[[ "$merge_tree" =~ ^[0-9a-f]{40}$ ]] || {
  finish_merged_readback_failure "landed tree is missing or invalid for ${merge_commit}"
}

candidate_sha="$(sha256_file "$candidate_file")"
landed_sha="$(sha256_file "$landed_file")"
landing_status=0
receipt "guarded squash receipt: repo=${repo} pr=${pr_number} mergeOutcome=${merge_outcome} state=${merge_state} mergedAt=${merged_at:-unknown} mergeable=${mergeable:-unknown} mergeStateStatus=${merge_state_status:-unknown} blockingEvidencePolicy=${blocking_evidence_policy} baseRef=${review_base_ref:-unknown} baseAtReview=${base_at_review} baseAtMerge=${base_at_merge:-missing} reviewedHead=${reviewed_head} prHead=${pr_head:-missing} reviewedTree=${reviewed_tree} mergeCommit=${merge_commit} mergeTree=${merge_tree} url=${merge_url:-unknown}"
receipt "guarded squash receipt: candidate parsed-only validation=PASS"
receipt "guarded squash receipt: candidate_sha256=${candidate_sha} landed_message_sha256=${landed_sha}"
if cmp -s "$candidate_file" "$landed_file"; then
  receipt "guarded squash receipt: candidate/landed byte diff=IDENTICAL"
else
  receipt "guarded squash receipt: candidate/landed byte diff=DIFFERENT"
  diff -u --label candidate_file --label landed_%B "$candidate_file" "$landed_file" >> "$receipt_tmp" || true
fi

if [ "$merge_command_status" -ne 0 ]; then
  landing_status=1
  receipt "guarded squash receipt: merge command status=FAIL exit=${merge_command_status} (post-merge alert; no rollback)"
else
  receipt "guarded squash receipt: merge command status=PASS"
fi
if [ "$observed_merge_commit" != "$merge_commit" ]; then
  landing_status=1
  receipt "guarded squash receipt: landed commit identity=FAIL observed=${observed_merge_commit:-missing} expected=${merge_commit} (post-merge alert; no rollback)"
else
  receipt "guarded squash receipt: landed commit identity=PASS"
fi
if [ "$pr_head" != "$reviewed_head" ]; then
  landing_status=1
  receipt "guarded squash receipt: reviewed/PR head identity=FAIL (post-merge alert; no rollback)"
else
  receipt "guarded squash receipt: reviewed/PR head identity=PASS"
fi
if [ "$merge_tree" = "$reviewed_tree" ]; then
  receipt "guarded squash receipt: reviewed/landed tree comparison=IDENTICAL"
else
  # A base branch may advance without changing the reviewed PR head. Preserve
  # the result for audit, but do not treat tree inequality alone as proof that
  # the wrong head was merged; prHead above is the exact-head identity check.
  if [[ "$base_at_merge" =~ ^[0-9a-f]{40}$ ]] && [ "$base_at_merge" != "$base_at_review" ]; then
    tree_interpretation="EXPECTED_BASE_ADVANCED"
  elif [[ "$base_at_merge" =~ ^[0-9a-f]{40}$ ]] && [ "$base_at_merge" = "$base_at_review" ]; then
    tree_interpretation="SUSPICIOUS_BASE_UNCHANGED"
  else
    tree_interpretation="UNRESOLVED_BASE"
  fi
  receipt "guarded squash receipt: reviewed/landed tree comparison=DIFFERENT interpretation=${tree_interpretation}"
  case "$tree_interpretation" in
    EXPECTED_BASE_ADVANCED)
      receipt "guarded squash receipt: tree interpretation review=NOT_REQUIRED reason=base-advanced"
      ;;
    SUSPICIOUS_BASE_UNCHANGED|UNRESOLVED_BASE)
      receipt "guarded squash receipt: tree interpretation review=REQUIRED owner=independent-exact-head-reviewer action=TRACE_BASE_AND_TREE (post-merge alert; no rollback)"
      ;;
  esac
fi
if landing_error="$("$landing_validator" "$landed_file" 2>&1)"; then
  receipt "guarded squash receipt: landed parsed-only validation=PASS"
else
  landing_status=1
  receipt "guarded squash receipt: landed parsed-only validation=FAIL (post-merge alert; no rollback)"
  while IFS= read -r line; do
    receipt "  ${line}"
  done <<< "$landing_error"
fi

if ! finish_receipt; then
  exit 1
fi
exit "$landing_status"
