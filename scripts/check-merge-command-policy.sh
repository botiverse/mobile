#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
policy_self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/$(basename "${BASH_SOURCE[0]}")"
squash_wrapper="${repo_root}/scripts/guarded-squash-merge.sh"
rebase_wrapper="${repo_root}/scripts/guarded-rebase-merge.sh"
files=()
if [ "$#" -gt 0 ]; then
  files=("$@")
else
  while IFS= read -r file; do
    files+=("$file")
  done < <(
    find "${repo_root}/scripts" -type f -name '*.sh' ! -name 'test_*' -print
    find "${repo_root}/.github/actions" -type f -name '*.sh' -print
    find "${repo_root}/.github/workflows" -type f \( -name '*.yml' -o -name '*.yaml' \) -print
  )
fi

failures=0
fail() {
  echo "merge command policy failed — $1" >&2
  failures=$((failures + 1))
}

for file in "${files[@]}"; do
  [ -f "$file" ] || {
    fail "not a file: $file"
    continue
  }

  # Join shell line continuations before looking for command shapes. This keeps
  # a repository-owned command split across lines from evading the policy.
  normalized="$(awk '
    {
      line = $0
      if (line ~ /\\[[:space:]]*$/) {
        sub(/[[:space:]]*$/, "", line)
        sub(/\\$/, "", line)
        printf "%s ", line
      } else {
        print line
      }
    }
  ' "$file")"

  if grep -Eq -- '(^|[[:space:]\\])--merge([[:space:]\\]|$)' <<< "$normalized"; then
    fail "ordinary merge mode is forbidden: $file"
  fi

  # Committed automation must not call `pr merge` directly through either the
  # literal `gh` command or an indirect command variable. Only the two exact
  # guarded-wrapper paths may contain that command shape. This source scan
  # cannot constrain a human typing it interactively.
  file_path="$(cd "$(dirname "$file")" && pwd -P)/$(basename "$file")"
  if [ "$file_path" != "$policy_self" ] &&
    grep -Eq '(^|[^[:alnum:]_])pr[[:space:]]+merge([^[:alnum:]_]|$)' <<< "$normalized"; then
    case "$file_path" in
      "$squash_wrapper"|"$rebase_wrapper")
        ;;
      *)
        fail "gh pr merge must be called through a guarded wrapper: $file"
        ;;
    esac
  fi

  if grep -Eq 'pr[[:space:]]+merge' <<< "$normalized" && grep -Eq -- '(^|[[:space:]\\])--squash([[:space:]\\]|$)' <<< "$normalized"; then
    if grep -Eq -- '(^|[[:space:]\\])--body([[:space:]\\]|$)' <<< "$normalized"; then
      fail "squash merge must not use inline --body: $file"
    fi
    grep -Fq -- '--body-file' <<< "$normalized" ||
      fail "squash merge must use --body-file: $file"
  fi

  case "$(basename "$file")" in
    guarded-squash-merge.sh)
      grep -Fq "\"\$validator\" \"\$candidate_file\"" "$file" ||
        fail "guarded squash must validate the exact candidate message file: $file"
      grep -Fq "\"\$preflight\" \"\$repo\" \"\$pr_number\" \"\$reviewed_head\"" "$file" ||
        fail "guarded squash must run shared preflight: $file"
      grep -Fq -- "--body-file \"\$body_file\"" "$file" ||
        fail "guarded squash must pass the validated body by file: $file"
      grep -Fq 'landing_validator=' "$file" ||
        fail "guarded squash must configure the landed-message validator: $file"
      grep -Fq "\"\$landing_validator\" \"\$reconstructed\"" "$file" ||
        fail "guarded squash must validate the reconstructed candidate with the landing rule: $file"
      grep -Fq 'mergeCommit' "$file" ||
        fail "guarded squash must read the post-merge mergeCommit receipt: $file"
      grep -Fq 'mergeable' "$file" ||
        fail "guarded squash must read post-merge mergeability evidence: $file"
      grep -Fq 'mergeOutcome=INDETERMINATE' "$file" ||
        fail "guarded squash must preserve an indeterminate readback state: $file"
      grep -Fq 'STOP_NO_AUTOMATIC_RETRY' "$file" ||
        fail "guarded squash must forbid automatic retry after uncertain readback: $file"
      grep -Fq 'MERGED_WITH_CLI_ERROR' "$file" ||
        fail "guarded squash must distinguish a merged landing from a CLI error: $file"
      grep -Fq '"NOT_MERGED"' "$file" ||
        fail "guarded squash must classify affirmative unmerged evidence: $file"
      grep -Fq 'CONFLICTING' "$file" ||
        fail "guarded squash must retain structural conflict evidence: $file"
      grep -Fq '"UNKNOWN"' "$file" ||
        fail "guarded squash must keep UNKNOWN mergeability indeterminate: $file"
      grep -Fq 'UNKNOWN_TRANSIENT' "$file" ||
        fail "guarded squash must record the UNKNOWN transient path: $file"
      grep -Fq 'blockingEvidencePolicy' "$file" ||
        fail "guarded squash must record the repository-specific blocking policy: $file"
      grep -Eq '^[[:space:]]*tool_version="guarded-squash-merge\.v[0-9]+"' "$file" ||
        fail "guarded squash receipt must record a formatted tool version: $file"
      grep -Eq '^[[:space:]]*spec_version="guarded-squash-receipt\.v[0-9]+"' "$file" ||
        fail "guarded squash receipt must record a formatted receipt/spec version: $file"
      grep -Fq 'reviewedHead=' "$file" ||
        fail "guarded squash receipt must record the reviewed head: $file"
      grep -Fq 'prHead=' "$file" ||
        fail "guarded squash receipt must record the observed PR head: $file"
      grep -Fq 'baseAtReview=' "$file" ||
        fail "guarded squash receipt must record the review-time merge base: $file"
      grep -Fq 'baseAtMerge=' "$file" ||
        fail "guarded squash receipt must record the landed first parent: $file"
      grep -Fq 'mergeTree=' "$file" ||
        fail "guarded squash receipt must record the landed tree: $file"
      grep -Fq 'candidate/landed byte diff' "$file" ||
        fail "guarded squash must record candidate/landed byte comparison: $file"
      ;;
    guarded-rebase-merge.sh)
      grep -Fq "\"\$preflight\" \"\$repo\" \"\$pr_number\" \"\$reviewed_head\"" "$file" ||
        fail "guarded rebase must run shared preflight: $file"
      grep -Fq -- '--rebase' "$file" ||
        fail "guarded rebase must select rebase mode: $file"
      ;;
  esac
done

[ "$failures" -eq 0 ] || exit 1
echo "merge command policy: OK — guarded preflight is present, rebase is default-capable, squash uses body-file, ordinary merge is absent"
