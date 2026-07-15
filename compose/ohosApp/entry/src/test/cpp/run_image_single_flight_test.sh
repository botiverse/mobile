#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="${TMPDIR:-/tmp}/slock-ohos-image-single-flight-test"

clang++ \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -pthread \
  "$SCRIPT_DIR/SlockOhosImageRequestSingleFlightTest.cpp" \
  -o "$OUTPUT"

"$OUTPUT"
rm -f "$OUTPUT"
