#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${repo_root}/scripts/guarded-squash-merge.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
fake_gh="${tmp_dir}/gh"
record="${tmp_dir}/calls"
body_record="${tmp_dir}/body"
message_file="${tmp_dir}/message.txt"
receipt_file="${tmp_dir}/receipt.txt"

cat > "$fake_gh" <<'FAKEGH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GH_RECORD:?}"
if [ "${1:-}" = "api" ]; then
  case "$*" in
    "api repos/botiverse/mobile --jq "*)
      printf '%b\n' "${FAKE_MERGE_POLICY:-true\\ttrue\\tfalse}"
      ;;
    "api repos/botiverse/mobile/pulls/1459 --jq "*)
      printf '%b\n' "${FAKE_PR_STATE:-open\\tfalse\\t${FAKE_HEAD_SHA:?}\\tmain}"
      ;;
    "api repos/botiverse/mobile/pulls/1459")
      jq -cn \
        --arg base "${FAKE_REVIEW_BASE_SHA:?}" \
        --arg ref "${FAKE_REVIEW_BASE_REF:-main}" \
        --arg head "${FAKE_HEAD_SHA:?}" \
        '{base: {sha: $base, ref: $ref}, head: {sha: $head}}'
      ;;
    "api --paginate repos/botiverse/mobile/pulls/1459/commits?per_page=100")
      printf '%s\n' "${FAKE_COMMITS_JSON:?}"
      ;;
    "api repos/botiverse/mobile/compare/"*)
      jq -cn --arg sha "${FAKE_BASE_AT_REVIEW:?}" '{merge_base_commit: {sha: $sha}}'
      ;;
    "api repos/botiverse/mobile/git/commits/${FAKE_HEAD_SHA:?}")
      reviewed_message="$(jq -r '.[-1].commit.message' <<< "${FAKE_COMMITS_JSON:?}")"
      jq -cn \
        --arg sha "${FAKE_HEAD_SHA:?}" \
        --arg tree "${FAKE_REVIEWED_TREE:?}" \
        --arg message "$reviewed_message" \
        '{sha: $sha, tree: {sha: $tree}, message: $message}'
      ;;
    "api repos/botiverse/mobile/git/commits/${FAKE_MERGE_SHA:?}")
      if [ "${FAKE_LANDED_API_EXIT:-0}" -ne 0 ]; then
        exit "${FAKE_LANDED_API_EXIT}"
      fi
      jq -cn \
        --arg sha "${FAKE_OBSERVED_MERGE_SHA:-${FAKE_MERGE_SHA:?}}" \
        --arg parent "${FAKE_BASE_AT_MERGE-}" \
        --arg tree "${FAKE_MERGE_TREE:?}" \
        --arg message "${FAKE_LANDED_MESSAGE:?}" \
        '{sha: $sha, tree: {sha: $tree}, parents: [{sha: $parent}], message: $message}'
      ;;
    *)
      echo "unexpected fake gh api call: $*" >&2
      exit 1
      ;;
  esac
elif [ "${1:-}" = "pr" ] && [ "${2:-}" = "view" ]; then
  if [ "${FAKE_PR_VIEW_EXIT:-0}" -ne 0 ]; then
    exit "${FAKE_PR_VIEW_EXIT}"
  fi
  if [ "${FAKE_MERGED_AT_NULL:-0}" = 1 ]; then
    fake_merged_at_json=null
  else
    fake_merged_at_json="$(jq -cn --arg value "${FAKE_MERGED_AT:-2026-08-22T00:00:00Z}" '$value')"
  fi
  if [ "${FAKE_MERGE_COMMIT_EMPTY:-0}" = 1 ]; then
    fake_merge_commit_json=null
  else
    fake_merge_commit_json="$(jq -cn --arg value "${FAKE_MERGE_SHA:?}" '{oid: $value}')"
  fi
  jq -cn \
    --arg state "${FAKE_MERGE_STATE:-MERGED}" \
    --arg head "${FAKE_PR_HEAD_SHA:-${FAKE_HEAD_SHA:?}}" \
    --arg mergeable "${FAKE_MERGEABLE:-MERGEABLE}" \
    --arg merge_state_status "${FAKE_MERGE_STATE_STATUS:-CLEAN}" \
    --arg url "https://github.com/botiverse/mobile/pull/1459" \
    --argjson merged_at "$fake_merged_at_json" \
    --argjson merge_commit "$fake_merge_commit_json" \
    '{state: $state, mergedAt: $merged_at, mergeCommit: $merge_commit, headRefOid: $head, mergeable: $mergeable, mergeStateStatus: $merge_state_status, url: $url}'
elif [ "${1:-}" = "pr" ] && [ "${2:-}" = "merge" ]; then
  while [ "$#" -gt 0 ]; do
    if [ "$1" = "--body-file" ]; then
      cat "$2" > "${GH_BODY_RECORD:?}"
      break
    fi
    shift
  done
  if [ "${FAKE_MERGE_EXIT:-0}" -ne 0 ]; then
    exit "${FAKE_MERGE_EXIT}"
  fi
fi
FAKEGH
chmod +x "$fake_gh"

expect_fail() {
  local label="$1"
  shift
  if "$@" >"${tmp_dir}/output" 2>&1; then
    cat "${tmp_dir}/output" >&2
    echo "FAIL: expected failure: $label" >&2
    exit 1
  fi
  echo "PASS: $label"
}

head_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
merge_sha="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
reviewed_tree="cccccccccccccccccccccccccccccccccccccccc"
merge_tree="$reviewed_tree"
base_at_review="ffffffffffffffffffffffffffffffffffffffff"
cat >"$message_file" <<'EOF'
fix(ui): guarded squash subject

Body paragraph.

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
commits_json="$(jq -cn \
  --arg sha "$head_sha" \
  --arg message "$(cat "$message_file")" \
  '[{sha: $sha, commit: {message: $message}}]')"

GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" FAKE_HEAD_SHA="$head_sha" \
  GUARDED_MERGE_RECEIPT_FILE="$receipt_file" FAKE_MERGE_SHA="$merge_sha" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_MERGE_STATE=MERGED \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file"

grep -Eq "^pr merge 1459 --repo botiverse/mobile --squash --match-head-commit ${head_sha} --delete-branch --subject fix\(ui\): guarded squash subject --body-file .+" "$record"
if grep -Eq -- '(^|[[:space:]])--body([[:space:]]|$)|\\\\n|\\\\r' "$record"; then
  echo "FAIL: guarded merge used inline body bytes or literal escaped newlines" >&2
  exit 1
fi
cat >"${tmp_dir}/expected-body" <<'EOF'
Body paragraph.

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
cmp "${tmp_dir}/expected-body" "$body_record"
grep -Fq "candidate/landed byte diff=IDENTICAL" "$receipt_file"
grep -Fq "landed parsed-only validation=PASS" "$receipt_file"
grep -Fq "merge command status=PASS" "$receipt_file"
grep -Fq "mergeOutcome=MERGED" "$receipt_file"
grep -Fq "tool_version=guarded-squash-merge.v1 spec_version=guarded-squash-receipt.v1" "$receipt_file"
grep -Fq "baseAtReview=${base_at_review} baseAtMerge=${base_at_review} reviewedHead=${head_sha} prHead=${head_sha} reviewedTree=${reviewed_tree} mergeCommit=${merge_sha} mergeTree=${merge_tree}" "$receipt_file"
grep -Fq "reviewed/landed tree comparison=IDENTICAL" "$receipt_file"
echo "PASS: exact guarded merge validates a complete message and uses body-file"

cat >"${tmp_dir}/different-landed.txt" <<'EOF'
fix(ui): landed subject was rewritten

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
: > "$record"
: > "$receipt_file"
GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "${tmp_dir}/different-landed.txt")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/different-output"
grep -Fq "candidate/landed byte diff=DIFFERENT" "$receipt_file"
grep -Fq "landed parsed-only validation=PASS" "$receipt_file"
echo "PASS: post-merge byte mismatch is recorded without rollback"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_MERGE_EXIT=1 FAKE_LANDED_MESSAGE="$(cat "$message_file")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/merge-command-failure-output" 2>&1; then
  cat "${tmp_dir}/merge-command-failure-output" >&2
  echo "FAIL: merge command failure was not surfaced after post-merge readback" >&2
  exit 1
fi
grep -Fq "merge command status=FAIL exit=1" "$receipt_file"
grep -Fq "mergeOutcome=MERGED_WITH_CLI_ERROR" "$receipt_file"
grep -Fq "landed parsed-only validation=PASS" "$receipt_file"
echo "PASS: merge command failure still performs authoritative post-merge readback"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_MERGE_EXIT=1 FAKE_LANDED_API_EXIT=1 \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/merged-readback-failure-output" 2>&1; then
  cat "${tmp_dir}/merged-readback-failure-output" >&2
  echo "FAIL: landed-object readback failure was not surfaced" >&2
  exit 1
fi
grep -Fq "mergeOutcome=MERGED_WITH_CLI_ERROR" "$receipt_file"
grep -Fq "action=STOP_NO_AUTOMATIC_RETRY" "$receipt_file"
grep -Fq "FAILED to read landed commit object" "$receipt_file"
echo "PASS: merged-but-unreadable landing remains an explicit alert"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_MERGE_EXIT=1 \
  FAKE_MERGE_STATE=OPEN FAKE_MERGED_AT_NULL=1 FAKE_MERGE_COMMIT_EMPTY=1 \
  FAKE_MERGEABLE=MERGEABLE FAKE_MERGE_STATE_STATUS=CLEAN \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/indeterminate-open-output" 2>&1; then
  cat "${tmp_dir}/indeterminate-open-output" >&2
  echo "FAIL: OPEN+CLEAN readback was treated as a successful merge" >&2
  exit 1
fi
grep -Fq "mergeOutcome=INDETERMINATE" "$receipt_file"
grep -Fq "action=STOP_NO_AUTOMATIC_RETRY" "$receipt_file"
grep -Fq "mergeable=MERGEABLE mergeStateStatus=CLEAN" "$receipt_file"
[ "$(grep -c '^pr merge ' "$record")" -eq 1 ]
echo "PASS: OPEN+CLEAN no-merge readback is indeterminate and never retried"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_MERGE_EXIT=1 FAKE_PR_VIEW_EXIT=1 \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/indeterminate-unavailable-output" 2>&1; then
  cat "${tmp_dir}/indeterminate-unavailable-output" >&2
  echo "FAIL: unavailable post-merge readback was treated as success" >&2
  exit 1
fi
grep -Fq "mergeOutcome=INDETERMINATE readback=UNAVAILABLE" "$receipt_file"
grep -Fq "action=STOP_NO_AUTOMATIC_RETRY" "$receipt_file"
[ "$(grep -c '^pr merge ' "$record")" -eq 1 ]
echo "PASS: unavailable post-merge readback stays indeterminate and never retried"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" \
  FAKE_MERGE_STATE=OPEN FAKE_MERGED_AT_NULL=1 FAKE_MERGE_COMMIT_EMPTY=1 \
  FAKE_MERGEABLE=MERGEABLE FAKE_MERGE_STATE_STATUS=BLOCKED \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/indeterminate-check-output" 2>&1; then
  cat "${tmp_dir}/indeterminate-check-output" >&2
  echo "FAIL: red-check-shaped readback was treated as a successful merge" >&2
  exit 1
fi
grep -Fq "mergeOutcome=INDETERMINATE" "$receipt_file"
grep -Fq "mergeable=MERGEABLE mergeStateStatus=BLOCKED" "$receipt_file"
if grep -Fq "mergeOutcome=NOT_MERGED" "$receipt_file"; then
  echo "FAIL: non-required check status became NOT_MERGED evidence" >&2
  exit 1
fi
echo "PASS: OPEN check-red-shaped readback stays indeterminate under current ruleset"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" \
  FAKE_MERGE_STATE=OPEN FAKE_MERGED_AT_NULL=1 FAKE_MERGE_COMMIT_EMPTY=1 \
  FAKE_MERGEABLE=UNKNOWN FAKE_MERGE_STATE_STATUS=UNKNOWN \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/indeterminate-unknown-output" 2>&1; then
  cat "${tmp_dir}/indeterminate-unknown-output" >&2
  echo "FAIL: mergeable=UNKNOWN was treated as a successful merge" >&2
  exit 1
fi
grep -Fq "mergeOutcome=INDETERMINATE" "$receipt_file"
grep -Fq "mergeable=UNKNOWN" "$receipt_file"
grep -Fq "evidenceClass=UNKNOWN_TRANSIENT" "$receipt_file"
if grep -Fq "mergeOutcome=NOT_MERGED" "$receipt_file" || grep -Fq "evidence=mergeable=CONFLICTING" "$receipt_file"; then
  echo "FAIL: mergeable=UNKNOWN entered the conflicting/NOT_MERGED path" >&2
  exit 1
fi
echo "PASS: mergeable=UNKNOWN is an indeterminate transient, not conflict evidence"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" \
  FAKE_MERGE_STATE=CLOSED FAKE_MERGED_AT_NULL=1 FAKE_MERGE_COMMIT_EMPTY=1 \
  FAKE_MERGEABLE=MERGEABLE FAKE_MERGE_STATE_STATUS=UNKNOWN \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/not-merged-closed-output" 2>&1; then
  cat "${tmp_dir}/not-merged-closed-output" >&2
  echo "FAIL: closed PR was not classified as NOT_MERGED" >&2
  exit 1
fi
grep -Fq "mergeOutcome=NOT_MERGED" "$receipt_file"
grep -Fq "evidence=state=CLOSED,mergedAt=null,mergeCommit=null" "$receipt_file"
echo "PASS: closed unmerged PR is classified from affirmative evidence"

: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" \
  FAKE_MERGE_STATE=OPEN FAKE_MERGED_AT_NULL=1 FAKE_MERGE_COMMIT_EMPTY=1 \
  FAKE_MERGEABLE=CONFLICTING FAKE_MERGE_STATE_STATUS=DIRTY \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_LANDED_MESSAGE="$(cat "$message_file")" FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/not-merged-conflict-output" 2>&1; then
  cat "${tmp_dir}/not-merged-conflict-output" >&2
  echo "FAIL: conflicting PR was not classified as NOT_MERGED" >&2
  exit 1
fi
grep -Fq "mergeOutcome=NOT_MERGED" "$receipt_file"
grep -Fq "evidence=mergeable=CONFLICTING,mergeCommit=null" "$receipt_file"
echo "PASS: conflicting PR is classified from affirmative structural evidence"

printf '%s\n' \
  'fix(ui): landing carried escaped newlines' \
  '' \
  'Body line\n' \
  >"${tmp_dir}/bad-landed.txt"
: > "$record"
: > "$receipt_file"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "${tmp_dir}/bad-landed.txt")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/bad-output" 2>&1; then
  cat "${tmp_dir}/bad-output" >&2
  echo "FAIL: bad landed message did not produce a post-merge failure" >&2
  exit 1
fi
grep -Fq "landed parsed-only validation=FAIL (post-merge alert; no rollback)" "$receipt_file"
grep -Eq '^pr merge ' "$record"
echo "PASS: bad landed message is reported after merge without rollback"

: > "$record"
: > "$receipt_file"
GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "$message_file")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/tree-mismatch-output" 2>&1
grep -Fq "reviewed/landed tree comparison=DIFFERENT" "$receipt_file"
grep -Fq "interpretation=SUSPICIOUS_BASE_UNCHANGED" "$receipt_file"
echo "PASS: unchanged-base tree mismatch is recorded as suspicious without rollback"

: > "$record"
: > "$receipt_file"
advanced_base="9999999999999999999999999999999999999999"
GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "$message_file")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$advanced_base" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/advanced-base-output" 2>&1
grep -Fq "baseAtReview=${base_at_review} baseAtMerge=${advanced_base}" "$receipt_file"
grep -Fq "reviewed/landed tree comparison=DIFFERENT interpretation=EXPECTED_BASE_ADVANCED" "$receipt_file"
echo "PASS: advanced-base tree mismatch is recorded as expected without rollback"

: > "$record"
: > "$receipt_file"
GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" GUARDED_MERGE_RECEIPT_FILE="$receipt_file" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "$message_file")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/unresolved-base-output" 2>&1
grep -Fq "baseAtReview=${base_at_review} baseAtMerge=missing" "$receipt_file"
grep -Fq "reviewed/landed tree comparison=DIFFERENT interpretation=UNRESOLVED_BASE" "$receipt_file"
grep -Fq "review=REQUIRED owner=independent-exact-head-reviewer action=TRACE_BASE_AND_TREE" "$receipt_file"
echo "PASS: unresolved-base tree mismatch requires independent human review"

mkdir -p "${tmp_dir}/receipt-directory"
: > "${record}"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" \
  GUARDED_MERGE_RECEIPT_FILE="${tmp_dir}/receipt-directory" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "$message_file")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/receipt-directory-output" 2>&1; then
  cat "${tmp_dir}/receipt-directory-output" >&2
  echo "FAIL: receipt directory path was accepted" >&2
  exit 1
fi
grep -Fq "path is a directory" "${tmp_dir}/receipt-directory-output"
if grep -Eq '^pr merge ' "$record"; then
  echo "FAIL: invalid receipt path reached gh merge" >&2
  exit 1
fi
echo "PASS: receipt directory path fails clearly before merge"

cat >"${tmp_dir}/reject-candidate-landing.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$1" > "${CANDIDATE_LANDING_INPUT:?}"
echo "synthetic candidate landing rejection" >&2
exit 1
EOF
chmod +x "${tmp_dir}/reject-candidate-landing.sh"
: > "$record"
if GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" \
  LANDED_COMMIT_MESSAGE_VALIDATOR="${tmp_dir}/reject-candidate-landing.sh" \
  CANDIDATE_LANDING_INPUT="${tmp_dir}/candidate-landing-input" \
  FAKE_HEAD_SHA="$head_sha" FAKE_MERGE_SHA="$merge_sha" FAKE_LANDED_MESSAGE="$(cat "$message_file")" \
  FAKE_REVIEWED_TREE="$reviewed_tree" FAKE_MERGE_TREE="$merge_tree" \
  FAKE_REVIEW_BASE_SHA="$base_at_review" FAKE_BASE_AT_REVIEW="$base_at_review" FAKE_BASE_AT_MERGE="$base_at_review" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" >"${tmp_dir}/candidate-rejection-output" 2>&1; then
  cat "${tmp_dir}/candidate-rejection-output" >&2
  echo "FAIL: reconstructed candidate landing validator was not enforced" >&2
  exit 1
fi
grep -Fq "reconstructed candidate failed landing trailer validation" "${tmp_dir}/candidate-rejection-output"
if grep -Eq '^pr merge ' "$record"; then
  echo "FAIL: candidate landing rejection reached gh merge" >&2
  exit 1
fi
echo "PASS: reconstructed candidate landing validation blocks before merge"

printf '%s\n' \
  'Bind mounted avatar cache sessions (#2008)' \
  '' \
  'Bind shared mounted avatar state to native conditional loading and committed render identities.\n\nSigned-off-by: KMP-专家 <raft-mobile-kmp-expert@mail.build>' \
  >"${tmp_dir}/incident.txt"
: > "$record"
expect_fail "literal-newline incident stops before gh merge" env \
  GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" FAKE_HEAD_SHA="$head_sha" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "${tmp_dir}/incident.txt"
grep -Fq "merge command escaped newlines; use body file" "${tmp_dir}/output"
if grep -q '^pr merge ' "$record"; then
  echo "FAIL: invalid candidate reached gh merge" >&2
  exit 1
fi

: > "$record"
expect_fail "short reviewed SHA" env GH_BIN="$fake_gh" GH_RECORD="$record" "$wrapper" botiverse/mobile 1459 aaaaaaaa
[ ! -s "$record" ] || { echo "FAIL: invalid SHA reached gh" >&2; exit 1; }

: > "$record"
expect_fail "custom arguments are rejected" env GH_BIN="$fake_gh" GH_RECORD="$record" "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file" extra
[ ! -s "$record" ] || { echo "FAIL: extra arguments reached gh" >&2; exit 1; }

: > "$record"
expect_fail "ordinary merge must be disabled" env \
  GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" FAKE_HEAD_SHA="$head_sha" \
  FAKE_COMMITS_JSON="$commits_json" \
  FAKE_MERGE_POLICY='true\ttrue\ttrue' \
  "$wrapper" botiverse/mobile 1459 "$head_sha" "$message_file"
if grep -q '^pr merge ' "$record"; then
  echo "FAIL: unsafe repository policy still reached merge" >&2
  exit 1
fi

echo "All guarded squash merge tests passed."
