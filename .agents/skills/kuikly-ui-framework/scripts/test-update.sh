#!/usr/bin/env bash

# Deterministic self-test. It never touches the bundled checkout or the network.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

ORIGIN="$TMP_DIR/origin.git"
SEED="$TMP_DIR/seed"
REFERENCE="$TMP_DIR/reference"
UPDATE_LOG="$TMP_DIR/last-update"

git init --bare -q "$ORIGIN"
git init -q "$SEED"
git -C "$SEED" config user.name test
git -C "$SEED" config user.email test@example.invalid
echo initial > "$SEED/reference.txt"
git -C "$SEED" add reference.txt
git -C "$SEED" commit -qm initial
git -C "$SEED" branch -M main
git -C "$SEED" remote add origin "$ORIGIN"
git -C "$SEED" push -q -u origin main
git --git-dir="$ORIGIN" symbolic-ref HEAD refs/heads/main
git clone -q "$ORIGIN" "$REFERENCE"

KUIKLY_REFERENCE_DIR="$REFERENCE" \
KUIKLY_REFERENCE_URL="$ORIGIN" \
KUIKLY_REFERENCE_UPDATE_LOG="$UPDATE_LOG" \
    "$SCRIPT_DIR/update-repository.sh" >/dev/null

KUIKLY_REFERENCE_UPDATE_LOG="$UPDATE_LOG" "$SCRIPT_DIR/check-update.sh" >/dev/null

echo dirty >> "$REFERENCE/reference.txt"
set +e
KUIKLY_REFERENCE_DIR="$REFERENCE" \
KUIKLY_REFERENCE_URL="$ORIGIN" \
KUIKLY_REFERENCE_UPDATE_LOG="$UPDATE_LOG" \
    "$SCRIPT_DIR/update-repository.sh" >/dev/null 2>&1
dirty_result=$?
set -e
[ "$dirty_result" -eq 3 ]

mkdir -p "$SEED/nested-reference"
set +e
KUIKLY_REFERENCE_DIR="$SEED/nested-reference" \
KUIKLY_REFERENCE_URL="$ORIGIN" \
KUIKLY_REFERENCE_UPDATE_LOG="$UPDATE_LOG" \
    "$SCRIPT_DIR/update-repository.sh" >/dev/null 2>&1
nested_result=$?
set -e
[ "$nested_result" -eq 2 ]

echo "Kuikly reference updater self-test passed"
