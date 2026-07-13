#!/usr/bin/env bash

set -euo pipefail

revision="${1:-}"

if [[ -z "$revision" ]]; then
  echo "Usage: $0 <branch|tag|full-sha|unique-short-sha>" >&2
  exit 2
fi

resolve_commit() {
  git rev-parse --verify --quiet "${1}^{commit}" 2>/dev/null
}

if resolved="$(resolve_commit "$revision")"; then
  printf '%s\n' "$resolved"
  exit 0
fi

# A full fetch stores non-checked-out branches under refs/remotes/origin. Keep
# branch names ergonomic without weakening tag or SHA resolution.
if [[ "$revision" != refs/* && "$revision" != origin/* ]] &&
  resolved="$(resolve_commit "origin/$revision")"; then
  printf '%s\n' "$resolved"
  exit 0
fi

echo "Revision '$revision' does not resolve to a unique fetched commit." >&2
exit 1
