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
    "api --paginate --slurp repos/botiverse/mobile/pulls/1459/commits?per_page=100")
      printf '%s\n' "${FAKE_COMMITS_JSON:?}"
      ;;
    *)
      echo "unexpected fake gh api call: $*" >&2
      exit 1
      ;;
  esac
elif [ "${1:-}" = "pr" ] && [ "${2:-}" = "merge" ]; then
  while [ "$#" -gt 0 ]; do
    if [ "$1" = "--body-file" ]; then
      cat "$2" > "${GH_BODY_RECORD:?}"
      break
    fi
    shift
  done
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
cat >"$message_file" <<'EOF'
fix(ui): guarded squash subject

Body paragraph.

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
commits_json="$(jq -cn \
  --arg sha "$head_sha" \
  --arg message "$(cat "$message_file")" \
  '[[{sha: $sha, commit: {message: $message}}]]')"

GH_BIN="$fake_gh" GH_RECORD="$record" GH_BODY_RECORD="$body_record" FAKE_HEAD_SHA="$head_sha" \
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
echo "PASS: exact guarded merge validates a complete message and uses body-file"

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
