#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
preflight="${repo_root}/scripts/guarded-merge-preflight.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
fake_gh="${tmp_dir}/gh"
record="${tmp_dir}/calls"
output="${tmp_dir}/output"

cat > "$fake_gh" <<'FAKEGH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GH_RECORD:?}"
case "$*" in
  "api repos/botiverse/mobile --jq "*)
    printf '%b\n' "${FAKE_MERGE_POLICY:-true\\ttrue\\tfalse}"
    ;;
  "api repos/botiverse/mobile/pulls/1459 --jq "*)
    printf '%b\n' "${FAKE_PR_STATE:-open\\tfalse\\t${FAKE_HEAD_SHA:?}\\tmain}"
    ;;
  "api --paginate repos/botiverse/mobile/pulls/1459/commits?per_page=100")
    printf '%s\n' "${FAKE_COMMITS_JSON:?}"
    ;;
  *)
    echo "unexpected fake gh call: $*" >&2
    exit 1
    ;;
esac
FAKEGH
chmod +x "$fake_gh"

message_json() {
  local sha="$1"
  local message="$2"
  jq -cn --arg sha "$sha" --arg message "$message" \
    '[{sha: $sha, commit: {message: $message}}]'
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
  local diagnostic="$2"
  shift 2
  if "$@" >"$output" 2>&1; then
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

head_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
other_sha="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
valid_message="$(cat <<'EOF'
fix(ui): signed source

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
)"
valid_json="$(message_json "$head_sha" "$valid_message")"
base_env=(
  GH_BIN="$fake_gh"
  GH_RECORD="$record"
  FAKE_HEAD_SHA="$head_sha"
  FAKE_COMMITS_JSON="$valid_json"
)

expect_pass "safe settings and exact signed source" env "${base_env[@]}" \
  "$preflight" botiverse/mobile 1459 "$head_sha"
if grep -Fq -- '--slurp' "$record"; then
  echo "FAIL: preflight still requires gh --slurp" >&2
  exit 1
fi
echo "PASS: gh versions without --slurp"

expect_fail "rebase disabled" "must enable rebase merge" env "${base_env[@]}" \
  FAKE_MERGE_POLICY='false\ttrue\tfalse' \
  "$preflight" botiverse/mobile 1459 "$head_sha"
expect_fail "squash disabled" "must keep guarded squash available" env "${base_env[@]}" \
  FAKE_MERGE_POLICY='true\tfalse\tfalse' \
  "$preflight" botiverse/mobile 1459 "$head_sha"
# Merge commits are intentionally left enabled on this repo (release back-merge
# needs them and artin ruled to keep the option on). The preflight tolerates
# allow_merge=true — it warns instead of refusing, because guarded-squash-merge
# still produces a squash commit.
expect_pass "ordinary merge enabled tolerated" env "${base_env[@]}" \
  FAKE_MERGE_POLICY='true\ttrue\ttrue' \
  "$preflight" botiverse/mobile 1459 "$head_sha"
expect_fail "draft PR" "is still draft" env "${base_env[@]}" \
  FAKE_PR_STATE="open\ttrue\t${head_sha}\tmain" \
  "$preflight" botiverse/mobile 1459 "$head_sha"
expect_fail "closed PR" "state=closed" env "${base_env[@]}" \
  FAKE_PR_STATE="closed\tfalse\t${head_sha}\tmain" \
  "$preflight" botiverse/mobile 1459 "$head_sha"
expect_fail "stale reviewed head" "reviewed=${head_sha}" env "${base_env[@]}" \
  FAKE_PR_STATE="open\tfalse\t${other_sha}\tmain" \
  "$preflight" botiverse/mobile 1459 "$head_sha"

literal_json="$(message_json "$head_sha" \
  'fix(ui): incident\\n\\nSigned-off-by: Merge Safety <merge-safety@mail.build>')"
expect_fail "literal escaped-newline source commit" "merge command escaped newlines; use body file" \
  env "${base_env[@]}" FAKE_COMMITS_JSON="$literal_json" \
  "$preflight" botiverse/mobile 1459 "$head_sha"

wrong_last_json="$(message_json "$other_sha" "$valid_message")"
expect_fail "final source commit differs from reviewed head" "final source commit=${other_sha}" \
  env "${base_env[@]}" FAKE_COMMITS_JSON="$wrong_last_json" \
  "$preflight" botiverse/mobile 1459 "$head_sha"

echo "All guarded merge preflight tests passed."
