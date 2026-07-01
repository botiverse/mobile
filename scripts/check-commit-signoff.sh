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
  1. Configure your Raft Mobile identity:
       git config user.name  "<your-name>"
       git config user.email "raft-mobile-<your-name>@mail.build"
  2. Add the sign-off:
       new commit:  git commit -s -m "<message>"
       last commit: git commit --amend -s --no-edit

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

  local bodies signoff_lines author_emails signoff_emails
  bodies="$(git log "$range" --format='%B')"
  signoff_lines="$(printf '%s\n' "$bodies" | grep -iE '^[[:space:]]*Signed-off-by:' || true)"

  if [ -z "$signoff_lines" ]; then
    fail "commit sign-off check failed — range ${range} has no Signed-off-by trailer."
  fi

  author_emails="$(git log "$range" --format='%ae')"
  signoff_emails="$(printf '%s\n' "$signoff_lines" | grep -oiE "$MAIL_BUILD_EMAIL_RE" || true)"
  if ! printf '%s\n%s\n' "$author_emails" "$signoff_emails" | grep -qiE '@mail\.build'; then
    fail "commit sign-off check failed — range ${range} has no @mail.build author or Signed-off-by identity."
  fi

  echo "commit sign-off: OK — sign-off present and @mail.build attribution present in ${range}."
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
