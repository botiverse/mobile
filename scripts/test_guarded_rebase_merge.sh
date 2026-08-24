#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${repo_root}/scripts/guarded-rebase-merge.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
fake_gh="${tmp_dir}/gh"
record="${tmp_dir}/calls"

cat > "$fake_gh" <<'FAKEGH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GH_RECORD:?}"
case "$*" in
  "api repos/botiverse/mobile --jq "*)
    printf 'true\ttrue\tfalse\n'
    ;;
  "api repos/botiverse/mobile/pulls/1459 --jq "*)
    printf 'open\tfalse\t%s\tmain\n' "${FAKE_HEAD_SHA:?}"
    ;;
  "api --paginate repos/botiverse/mobile/pulls/1459/commits?per_page=100")
    printf '%s\n' "${FAKE_COMMITS_JSON:?}"
    ;;
  "pr merge 1459 --repo botiverse/mobile --rebase --match-head-commit "*)
    ;;
  *)
    echo "unexpected fake gh call: $*" >&2
    exit 1
    ;;
esac
FAKEGH
chmod +x "$fake_gh"

head_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
message="$(cat <<'EOF'
fix(ui): signed source

Signed-off-by: Merge Safety <merge-safety@mail.build>
EOF
)"
commits_json="$(jq -cn --arg sha "$head_sha" --arg message "$message" \
  '[{sha: $sha, commit: {message: $message}}]')"

GH_BIN="$fake_gh" GH_RECORD="$record" FAKE_HEAD_SHA="$head_sha" \
  FAKE_COMMITS_JSON="$commits_json" \
  "$wrapper" botiverse/mobile 1459 "$head_sha"

grep -Fxq \
  "pr merge 1459 --repo botiverse/mobile --rebase --match-head-commit ${head_sha} --delete-branch" \
  "$record"
if grep -Eq -- '(^|[[:space:]])--squash([[:space:]]|$)|(^|[[:space:]])--body([[:space:]]|$)' "$record"; then
  echo "FAIL: guarded rebase used squash or inline body arguments" >&2
  exit 1
fi

echo "All guarded rebase merge tests passed."
