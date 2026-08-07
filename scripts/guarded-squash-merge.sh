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
gh_bin="${GH_BIN:-gh}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
candidate_file="${tmp_dir}/candidate-message.txt"
body_file="${tmp_dir}/body.txt"

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

GH_BIN="$gh_bin" MERGE_MESSAGE_VALIDATOR="$validator" \
  "$preflight" "$repo" "$pr_number" "$reviewed_head"

"$gh_bin" pr merge "$pr_number" \
  --repo "$repo" \
  --squash \
  --match-head-commit "$reviewed_head" \
  --delete-branch \
  --subject "$subject" \
  --body-file "$body_file"
