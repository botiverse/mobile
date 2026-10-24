#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="$(mktemp -t raft-diagnostics-snapshot-names.XXXXXX)"
trap 'rm -f "$OUTPUT"' EXIT

clang -fobjc-arc -framework Foundation \
  "$SCRIPT_DIR/test_diagnostics_snapshot_names.m" \
  -o "$OUTPUT"
"$OUTPUT"
