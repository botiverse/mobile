#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${repo_root}/scripts/guarded-squash-merge.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
fake_gh="${tmp_dir}/gh"
record="${tmp_dir}/calls"

cat > "$fake_gh" <<'FAKEGH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GH_RECORD:?}"
if [ "${1:-}" = "api" ]; then
  printf '%s\n' "${FAKE_SQUASH_MESSAGE_MODE:-COMMIT_MESSAGES}"
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
GH_BIN="$fake_gh" GH_RECORD="$record" "$wrapper" botiverse/mobile 1459 "$head_sha"

grep -Fxq 'api repos/botiverse/mobile --jq .squash_merge_commit_message' "$record"
grep -Fxq "pr merge 1459 --repo botiverse/mobile --squash --match-head-commit ${head_sha} --delete-branch" "$record"
if grep -Eq -- '--body|--subject|\\n' "$record"; then
  echo "FAIL: guarded merge forwarded a custom body, subject, or literal backslash-n" >&2
  exit 1
fi
echo "PASS: exact guarded merge uses repository COMMIT_MESSAGES without custom text"

: > "$record"
expect_fail "short reviewed SHA" env GH_BIN="$fake_gh" GH_RECORD="$record" "$wrapper" botiverse/mobile 1459 aaaaaaaa
[ ! -s "$record" ] || { echo "FAIL: invalid SHA reached gh" >&2; exit 1; }

: > "$record"
expect_fail "custom arguments are rejected" env GH_BIN="$fake_gh" GH_RECORD="$record" "$wrapper" botiverse/mobile 1459 "$head_sha" --body bad
[ ! -s "$record" ] || { echo "FAIL: extra arguments reached gh" >&2; exit 1; }

: > "$record"
expect_fail "repository must use COMMIT_MESSAGES" env \
  GH_BIN="$fake_gh" GH_RECORD="$record" FAKE_SQUASH_MESSAGE_MODE=PR_BODY \
  "$wrapper" botiverse/mobile 1459 "$head_sha"
grep -Fxq 'api repos/botiverse/mobile --jq .squash_merge_commit_message' "$record"
if grep -q '^pr merge ' "$record"; then
  echo "FAIL: incompatible repository setting still reached merge" >&2
  exit 1
fi

echo "All guarded squash merge tests passed."
