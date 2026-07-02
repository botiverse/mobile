#!/usr/bin/env bash
# check-commit-signoff.sh — require commit sign-off attribution.
#
# Modes:
#   scripts/check-commit-signoff.sh --commit-msg <file>
#   scripts/check-commit-signoff.sh <base-sha> <head-sha>
set -euo pipefail

MAIL_BUILD_EMAIL_RE='[A-Za-z0-9._%+-]+@mail\.build'
SIGNOFF_RE="^[[:space:]]*Signed-off-by:[[:space:]].*<${MAIL_BUILD_EMAIL_RE}>[[:space:]]*$"

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

  local commit body subject
  while IFS= read -r commit; do
    body="$(git log -1 --format='%B' "$commit")"
    subject="$(git log -1 --format='%s' "$commit")"
    if ! printf '%s\n' "$body" | grep -qiE "$SIGNOFF_RE"; then
      fail "commit sign-off check failed — ${commit} (${subject}) lacks a Signed-off-by trailer with @mail.build."
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
