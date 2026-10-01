#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="$(mktemp -t raft-diagnostics-environment-rows.XXXXXX)"
trap 'rm -f "$OUTPUT"' EXIT

clang -fobjc-arc -framework Foundation \
  "$SCRIPT_DIR/test_diagnostics_environment_rows.m" \
  -o "$OUTPUT"
"$OUTPUT"
