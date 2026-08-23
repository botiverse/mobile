#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LANDING_VALIDATOR="${LANDED_COMMIT_MESSAGE_VALIDATOR:-${SCRIPT_DIR}/validate-landed-commit-message.sh}"
CHECKER_TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$CHECKER_TMP_DIR"' EXIT

base="${1:?usage: check-landed-commit-signoff.sh <base-sha> <head-sha>}"
head="${2:?usage: check-landed-commit-signoff.sh <base-sha> <head-sha>}"
[ "$#" -eq 2 ] || {
  echo "usage: check-landed-commit-signoff.sh <base-sha> <head-sha>" >&2
  exit 2
}

range="${base}..${head}"
if ! commits="$(git rev-list --reverse "$range" 2>/dev/null)"; then
  echo "landed commit sign-off check failed — invalid commit range ${range}" >&2
  exit 1
fi

if [ -z "$commits" ]; then
  echo "landed commit sign-off: no commits in range ${range}; nothing to enforce."
  exit 0
fi

while IFS= read -r commit; do
  message_file="${CHECKER_TMP_DIR}/${commit}.txt"
  git log -1 --format='%B' "$commit" > "$message_file"
  if ! validation_error="$("$LANDING_VALIDATOR" "$message_file" 2>&1)"; then
    subject="$(git log -1 --format='%s' "$commit")"
    echo "landed commit sign-off check failed — ${commit} (${subject}): ${validation_error}" >&2
    exit 1
  fi
done <<< "$commits"

echo "landed commit sign-off: OK — every commit in ${range} has exactly one Git-parsed Signed-off-by trailer in its landing footer."
