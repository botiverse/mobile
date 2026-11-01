#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="$(mktemp -t raft-diagnostics-crash-build.XXXXXX)"
trap 'rm -f "$OUTPUT"' EXIT

clang -fobjc-arc -framework Foundation \
  "$SCRIPT_DIR/test_diagnostics_crash_build.m" \
  -o "$OUTPUT"
"$OUTPUT"
