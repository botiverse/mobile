#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
files=()
if [ "$#" -gt 0 ]; then
  files=("$@")
else
  while IFS= read -r file; do
    files+=("$file")
  done < <(
    find "${repo_root}/scripts" -maxdepth 1 -type f -name '*.sh' ! -name 'test_*' -print
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

  if grep -Eq -- '(^|[[:space:]\\])--merge([[:space:]\\]|$)' "$file"; then
    fail "ordinary merge mode is forbidden: $file"
  fi

  if grep -Eq 'pr[[:space:]]+merge' "$file" && grep -Eq -- '(^|[[:space:]\\])--squash([[:space:]\\]|$)' "$file"; then
    if grep -Eq -- '(^|[[:space:]\\])--body([[:space:]\\]|$)' "$file"; then
      fail "squash merge must not use inline --body: $file"
    fi
    grep -Fq -- '--body-file' "$file" ||
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
