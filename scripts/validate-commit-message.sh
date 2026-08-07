#!/usr/bin/env bash
set -euo pipefail

message_file="${1:?usage: validate-commit-message.sh <message-file>}"
[ "$#" -eq 1 ] || {
  echo "usage: validate-commit-message.sh <message-file>" >&2
  exit 2
}

fail() {
  echo "commit message validation failed — $1" >&2
  exit 1
}

[ -f "$message_file" ] || fail "message file does not exist: $message_file"
[ -s "$message_file" ] || fail "message file is empty"

if LC_ALL=C grep -Fq '\n' "$message_file" || LC_ALL=C grep -Fq '\r' "$message_file"; then
  fail "merge command escaped newlines; use body file (literal \\n/\\r bytes are forbidden)"
fi
if LC_ALL=C grep -q $'\r' "$message_file"; then
  fail "carriage-return bytes are forbidden; use an LF-only message file"
fi

subject="$(sed -n '1p' "$message_file")"
[ -n "$subject" ] || fail "subject line is empty"

raw_signoff_count="$(awk '
  tolower($0) ~ /^[[:space:]]*signed-off-by([[:space:]]|:|$)/ { count++ }
  END { print count + 0 }
' "$message_file")"
if [ "$raw_signoff_count" -eq 0 ]; then
  fail "message must contain exactly one valid Signed-off-by trailer under @mail.build"
fi
if [ "$raw_signoff_count" -ne 1 ]; then
  fail "message must contain exactly one valid Signed-off-by trailer under @mail.build; found ${raw_signoff_count}"
fi

raw_signoff="$(awk '
  tolower($0) ~ /^[[:space:]]*signed-off-by([[:space:]]|:|$)/ { print }
' "$message_file")"
if ! printf '%s\n' "$raw_signoff" | grep -Eq \
  '^[[:space:]]*Signed-off-by:[[:space:]]+[^<>[:space:]][^<>]*[[:space:]]+<[A-Za-z0-9._%+-]+@mail\.build>[[:space:]]*$'; then
  if printf '%s\n' "$raw_signoff" | grep -qiE '^[[:space:]]*Signed-off-by:'; then
    fail "Signed-off-by trailer must use exactly one named <...@mail.build> identity"
  fi
  fail "malformed Signed-off-by trailer"
fi

parsed="$(git interpret-trailers --parse "$message_file")"
parsed_signoff_count="$(printf '%s\n' "$parsed" | awk '
  tolower($0) ~ /^signed-off-by:[[:space:]]/ { count++ }
  END { print count + 0 }
')"
if [ "$parsed_signoff_count" -ne 1 ]; then
  fail "message must contain exactly one valid Signed-off-by trailer in the Git trailer footer; parsed ${parsed_signoff_count}"
fi
parsed_signoff="$(printf '%s\n' "$parsed" | awk '
  tolower($0) ~ /^signed-off-by:[[:space:]]/ { print }
')"
printf '%s\n' "$parsed_signoff" | grep -Eq \
  '^Signed-off-by:[[:space:]]+[^<>[:space:]][^<>]*[[:space:]]+<[A-Za-z0-9._%+-]+@mail\.build>[[:space:]]*$' ||
  fail "parsed Signed-off-by trailer is malformed or does not use @mail.build"

echo "commit message validation: OK — exactly one Git-parsed Signed-off-by trailer under @mail.build"
