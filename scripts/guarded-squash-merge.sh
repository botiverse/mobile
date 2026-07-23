#!/usr/bin/env bash
set -euo pipefail

repo="${1:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
pr_number="${2:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
reviewed_head="${3:?usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
[ "$#" -eq 3 ] || {
  echo "usage: guarded-squash-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>" >&2
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

gh_bin="${GH_BIN:-gh}"
squash_message_mode="$($gh_bin api "repos/${repo}" --jq .squash_merge_commit_message)"
[ "$squash_message_mode" = "COMMIT_MESSAGES" ] || {
  echo "refusing guarded squash: ${repo} squash_merge_commit_message=${squash_message_mode}, expected COMMIT_MESSAGES" >&2
  exit 1
}

# Intentionally do not accept or pass --body / --subject. The repository-owned
# COMMIT_MESSAGES generator preserves real source commit trailers without shell
# escaping. Provenance receipts belong in the PR and Raft task thread.
exec "$gh_bin" pr merge "$pr_number" \
  --repo "$repo" \
  --squash \
  --match-head-commit "$reviewed_head" \
  --delete-branch
