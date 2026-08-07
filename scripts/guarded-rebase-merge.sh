#!/usr/bin/env bash
set -euo pipefail

repo="${1:?usage: guarded-rebase-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
pr_number="${2:?usage: guarded-rebase-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
reviewed_head="${3:?usage: guarded-rebase-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>}"
[ "$#" -eq 3 ] || {
  echo "usage: guarded-rebase-merge.sh <owner/repo> <pr-number> <reviewed-head-sha>" >&2
  exit 2
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
preflight="${GUARDED_MERGE_PREFLIGHT:-${script_dir}/guarded-merge-preflight.sh}"
gh_bin="${GH_BIN:-gh}"

GH_BIN="$gh_bin" "$preflight" "$repo" "$pr_number" "$reviewed_head"

"$gh_bin" pr merge "$pr_number" \
  --repo "$repo" \
  --rebase \
  --match-head-commit "$reviewed_head" \
  --delete-branch
