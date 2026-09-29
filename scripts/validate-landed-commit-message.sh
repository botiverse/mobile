#!/usr/bin/env bash
set -euo pipefail

message_file="${1:?usage: validate-landed-commit-message.sh <message-file>}"
[ "$#" -eq 1 ] || {
  echo "usage: validate-landed-commit-message.sh <message-file>" >&2
  exit 2
}

fail() {
  echo "landed commit message validation failed — $1" >&2
  exit 1
}

[ -f "$message_file" ] || fail "message file does not exist: $message_file"
[ -s "$message_file" ] || fail "message file is empty"

# A landing commit may contain the historical messages from several source
# commits; only byte hygiene and a subject line are checked here.
if LC_ALL=C grep -Fq '\n' "$message_file" || LC_ALL=C grep -Fq '\r' "$message_file"; then
  fail "merge command escaped newlines; use body file (literal \\n/\\r bytes are forbidden)"
fi
if LC_ALL=C grep -q $'\r' "$message_file"; then
  fail "carriage-return bytes are forbidden; use an LF-only message file"
fi

subject="$(sed -n '1p' "$message_file")"
[ -n "$subject" ] || fail "subject line is empty"

echo "landed commit message validation: OK — LF-only message with a subject line"
