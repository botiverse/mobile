#!/usr/bin/env bash
# check-commit-signoff.sh — require commit sign-off attribution.
#
# Modes:
#   scripts/check-commit-signoff.sh --commit-msg <file>
#   scripts/check-commit-signoff.sh <base-sha> <head-sha>
set -euo pipefail

MAIL_BUILD_EMAIL_RE='[A-Za-z0-9._%+-]+@mail\.build'
SIGNOFF_RE="^[[:space:]]*Signed-off-by:[[:space:]].*<${MAIL_BUILD_EMAIL_RE}>[[:space:]]*$"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ATTESTATIONS_FILE="${COMMIT_SIGNOFF_ATTESTATIONS_FILE:-${SCRIPT_DIR}/commit-signoff-attestations.tsv}"

validate_attestation_ledger() {
  [ -e "$ATTESTATIONS_FILE" ] || return 0
  [ -f "$ATTESTATIONS_FILE" ] || fail "commit sign-off attestation ledger is not a file: $ATTESTATIONS_FILE"

  local error
  if ! error="$({
    awk -F '\t' '
      /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
      NF != 3 {
        printf "line %d must contain exactly 3 tab-separated fields", NR
        exit 1
      }
      length($1) != 40 || $1 ~ /[^0-9a-f]/ {
        printf "line %d has an invalid immutable commit SHA", NR
        exit 1
      }
      length($2) != 40 || $2 ~ /[^0-9a-f]/ {
        printf "line %d has an invalid signed source SHA", NR
        exit 1
      }
      $3 !~ /^[A-Za-z0-9._%+-]+@mail[.]build$/ {
        printf "line %d has an invalid source signer", NR
        exit 1
      }
      seen[$1]++ {
        printf "line %d duplicates immutable commit %s", NR, $1
        exit 1
      }
    ' "$ATTESTATIONS_FILE"
  } 2>&1)"; then
    fail "commit sign-off attestation ledger is invalid: $error"
  fi
}

attestation_for_commit() {
  local commit="$1"
  [ -f "$ATTESTATIONS_FILE" ] || return 1
  awk -F '\t' -v commit="$commit" '
    $0 !~ /^[[:space:]]*#/ && $0 !~ /^[[:space:]]*$/ && $1 == commit {
      print $2 "\t" $3
      found = 1
    }
    END { if (!found) exit 1 }
  ' "$ATTESTATIONS_FILE"
}

source_has_exact_signer() {
  local source_commit="$1"
  local signer="$2"
  git log -1 --format='%B' "$source_commit" |
    git interpret-trailers --parse |
    awk -v signer="$signer" '
      {
        key = $0
        sub(/:.*/, "", key)
        value = $0
        sub(/^[^:]*:[[:space:]]*/, "", value)
        suffix = "<" signer ">"
        if (tolower(key) == "signed-off-by" &&
            length(value) >= length(suffix) &&
            substr(value, length(value) - length(suffix) + 1) == suffix) {
          found = 1
        }
      }
      END { exit found ? 0 : 1 }
    '
}

verify_attestation() {
  local commit="$1"
  local subject="$2"
  local attestation="$3"
  local source_commit signer commit_tree source_tree parent_line parent_count
  IFS=$'\t' read -r source_commit signer <<< "$attestation"

  [ "$(git cat-file -t "$source_commit" 2>/dev/null || true)" = "commit" ] ||
    fail "commit sign-off attestation for ${commit} (${subject}) requires source ${source_commit} to be an exact commit object."
  source_has_exact_signer "$source_commit" "$signer" ||
    fail "commit sign-off attestation for ${commit} (${subject}) does not match an exact Signed-off-by trailer for <${signer}> on source ${source_commit}."

  commit_tree="$(git rev-parse "${commit}^{tree}")"
  parent_line="$(git rev-list --parents -n 1 "$commit")"
  parent_count="$(printf '%s\n' "$parent_line" | awk '{ print NF - 1 }')"
  case "$parent_count" in
    1)
      source_tree="$(git rev-parse "${source_commit}^{tree}")"
      [ "$commit_tree" = "$source_tree" ] ||
        fail "commit sign-off attestation for ${commit} (${subject}) does not match source tree ${source_commit}."
      echo "commit sign-off: immutable squash ${commit} (${subject}) is attested by tree-identical signed source ${source_commit} (<${signer}>)."
      ;;
    2)
      local first_parent second_parent clean_merge_tree
      first_parent="$(printf '%s\n' "$parent_line" | awk '{ print $2 }')"
      second_parent="$(printf '%s\n' "$parent_line" | awk '{ print $3 }')"
      [ "$source_commit" = "$second_parent" ] ||
        fail "commit sign-off attestation for ${commit} (${subject}) must reference its reviewed second parent ${second_parent}, not ${source_commit}."
      if ! clean_merge_tree="$(git merge-tree --write-tree "$first_parent" "$second_parent" 2>/dev/null)"; then
        fail "commit sign-off attestation for ${commit} (${subject}) is only valid for a clean two-parent merge."
      fi
      [ "$clean_merge_tree" = "$commit_tree" ] ||
        fail "commit sign-off attestation for ${commit} (${subject}) does not match the clean merge tree of ${first_parent} and ${second_parent}."
      echo "commit sign-off: immutable merge ${commit} (${subject}) is attested by signed reviewed second parent ${source_commit} (<${signer}>) with exact clean merge tree ${commit_tree}."
      ;;
    *)
      fail "commit sign-off attestation for ${commit} (${subject}) is only valid for a single-parent immutable squash or clean two-parent merge commit."
      ;;
  esac
}

print_fix_instructions() {
  cat <<'EOF'

How to fix:
  1. In the shared main checkout, pass identity per commit:
       git \
         -c user.name="<your-name>" \
         -c user.email="raft-mobile-<your-name>@mail.build" \
         commit -s -m "<message>"
  2. To add the sign-off to the previous commit:
       git \
         -c user.name="<your-name>" \
         -c user.email="raft-mobile-<your-name>@mail.build" \
         commit --amend -s --no-edit

Do not rely on repo-local user.name/user.email when multiple agents share the
same checkout. In an isolated private clone or worktree, local Git identity is
also fine.

Agents should use the raft-mobile- prefix, for example:
  raft-mobile-cindy@mail.build
EOF
}

fail() {
  echo "::error::$1" >&2
  print_fix_instructions >&2
  exit 1
}

check_commit_message_file() {
  local message_file="${1:?usage: check-commit-signoff.sh --commit-msg <file>}"
  if [ ! -f "$message_file" ]; then
    fail "commit sign-off check failed — commit message file does not exist: $message_file"
  fi

  if ! grep -qiE "$SIGNOFF_RE" "$message_file"; then
    fail "commit sign-off check failed — commit message must contain 'Signed-off-by: <name> <raft-mobile-...@mail.build>'."
  fi

  echo "commit sign-off: OK — commit message has Signed-off-by with @mail.build."
}

check_commit_range() {
  local base="${1:?usage: check-commit-signoff.sh <base-sha> <head-sha>}"
  local head="${2:?usage: check-commit-signoff.sh <base-sha> <head-sha>}"
  local range="${base}..${head}"

  if [ -z "$(git rev-list "$range" 2>/dev/null)" ]; then
    echo "commit sign-off: no commits in range ${range}; nothing to enforce."
    exit 0
  fi

  validate_attestation_ledger

  local commit body subject attestation
  while IFS= read -r commit; do
    body="$(git log -1 --format='%B' "$commit")"
    subject="$(git log -1 --format='%s' "$commit")"
    if ! printf '%s\n' "$body" | grep -qiE "$SIGNOFF_RE"; then
      if attestation="$(attestation_for_commit "$commit")"; then
        verify_attestation "$commit" "$subject" "$attestation"
      else
        fail "commit sign-off check failed — ${commit} (${subject}) lacks a Signed-off-by trailer with @mail.build."
      fi
    fi
  done < <(git rev-list --reverse "$range")

  echo "commit sign-off: OK — every commit in ${range} has Signed-off-by with @mail.build."
}

case "${1:-}" in
  --commit-msg)
    check_commit_message_file "${2:-}"
    ;;
  -h|--help|"")
    cat <<'EOF'
Usage:
  scripts/check-commit-signoff.sh --commit-msg <file>
  scripts/check-commit-signoff.sh <base-sha> <head-sha>
EOF
    ;;
  *)
    check_commit_range "$@"
    ;;
esac
