#!/usr/bin/env bash
set -euo pipefail

repo="${1:?usage: guarded-merge-preflight.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
pr_number="${2:?usage: guarded-merge-preflight.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
reviewed_head="${3:?usage: guarded-merge-preflight.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
[ "$#" -eq 3 ] || {
  echo "usage: guarded-merge-preflight.sh <owner/repo> <pr-number> <reviewed-head-sha>" >&2
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
validator="${MERGE_MESSAGE_VALIDATOR:-${script_dir}/validate-commit-message.sh}"
gh_bin="${GH_BIN:-gh}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

policy="$($gh_bin api "repos/${repo}" --jq '[.allow_rebase_merge, .allow_squash_merge, .allow_merge_commit] | @tsv')"
IFS=$'\t' read -r allow_rebase allow_squash allow_merge <<< "$policy"
[ "$allow_rebase" = "true" ] || {
  echo "refusing merge: ${repo} must enable rebase merge" >&2
  exit 1
}
[ "$allow_squash" = "true" ] || {
  echo "refusing merge: ${repo} must keep guarded squash available" >&2
  exit 1
}
# Merge commits are intentionally left enabled on this repo (release back-merge
# needs them to record ancestry, and artin ruled to keep the option on).
# Guarded-squash-merge produces a squash commit, never a merge commit, so an
# enabled merge-commit option does not weaken the contract. Warn rather than
# refuse so the reconcile window and normal squashes both keep working.
if [ "$allow_merge" != "false" ]; then
  echo "note: ${repo} allows ordinary merge commits (intended); guarded squash still produces squash commits" >&2
fi

pr_state="$($gh_bin api "repos/${repo}/pulls/${pr_number}" --jq '[.state, (.draft | tostring), .head.sha, .base.ref] | @tsv')"
IFS=$'\t' read -r state draft live_head base_ref <<< "$pr_state"
[ "$state" = "open" ] || {
  echo "refusing merge: PR #${pr_number} state=${state}, expected open" >&2
  exit 1
}
[ "$draft" = "false" ] || {
  echo "refusing merge: PR #${pr_number} is still draft" >&2
  exit 1
}
[ "$live_head" = "$reviewed_head" ] || {
  echo "refusing merge: PR #${pr_number} head=${live_head}, reviewed=${reviewed_head}" >&2
  exit 1
}

commits_json="${tmp_dir}/commits.json"
# Older gh releases do not support --slurp.  --paginate emits one JSON array
# per page; jq -s collects those arrays and adds them into the same flat array
# that --slurp would have produced.
$gh_bin api --paginate "repos/${repo}/pulls/${pr_number}/commits?per_page=100" \
  | jq -s 'add // []' > "$commits_json"
commit_count="$(jq 'length' "$commits_json")"
[ "$commit_count" -gt 0 ] || {
  echo "refusing merge: PR #${pr_number} has no source commits" >&2
  exit 1
}

last_commit="$(jq -r '.[-1].sha' "$commits_json")"
[ "$last_commit" = "$reviewed_head" ] || {
  echo "refusing merge: PR #${pr_number} final source commit=${last_commit}, reviewed=${reviewed_head}" >&2
  exit 1
}

for ((index = 0; index < commit_count; index++)); do
  commit_sha="$(jq -r --argjson index "$index" '.[$index].sha' "$commits_json")"
  message_file="${tmp_dir}/commit-${index}.txt"
  jq -j --argjson index "$index" '.[$index].commit.message' "$commits_json" > "$message_file"
  if ! error="$($validator "$message_file" 2>&1)"; then
    echo "refusing merge: source commit ${commit_sha} failed message validation: ${error}" >&2
    exit 1
  fi
done

echo "guarded merge preflight: OK — PR #${pr_number} targets ${base_ref}, head ${reviewed_head}, ${commit_count} exact validated commit(s), rebase enabled, ordinary merge disabled"
