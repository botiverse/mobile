#!/usr/bin/env bash

# Explicit, fail-closed refresh for the bundled upstream KuiklyUI reference.
# Never stash, reset, delete, move, or overwrite local changes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="${KUIKLY_REFERENCE_DIR:-$SKILL_DIR/references/KuiklyUI}"
REPO_URL="${KUIKLY_REFERENCE_URL:-https://github.com/Tencent-TDS/KuiklyUI.git}"
UPDATE_LOG="${KUIKLY_REFERENCE_UPDATE_LOG:-$SCRIPT_DIR/.last-update}"

is_exact_worktree() {
    local candidate root
    candidate="$(cd "$1" 2>/dev/null && pwd -P)" || return 1
    root="$(git -C "$candidate" rev-parse --show-toplevel 2>/dev/null)" || return 1
    root="$(cd "$root" 2>/dev/null && pwd -P)" || return 1
    [ "$candidate" = "$root" ]
}

if [ ! -e "$REPO_DIR" ]; then
    mkdir -p "$(dirname "$REPO_DIR")"
    git clone --filter=blob:none "$REPO_URL" "$REPO_DIR"
elif ! is_exact_worktree "$REPO_DIR"; then
    echo "Refusing to replace non-Git reference directory: $REPO_DIR" >&2
    exit 2
else
    if [ -n "$(git -C "$REPO_DIR" status --porcelain)" ]; then
        echo "Refusing to update a dirty KuiklyUI reference checkout." >&2
        exit 3
    fi

    git -C "$REPO_DIR" fetch --prune origin
    upstream="$(git -C "$REPO_DIR" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"
    if [ -n "$upstream" ]; then
        git -C "$REPO_DIR" merge --ff-only "$upstream"
    else
        echo "Fetched origin; current checkout has no upstream, so HEAD was left unchanged."
    fi
fi

mkdir -p "$(dirname "$UPDATE_LOG")"
date '+%Y-%m-%d %H:%M:%S' > "$UPDATE_LOG"
git -C "$REPO_DIR" log -1 --format='Updated reference: %H %cI %s'
