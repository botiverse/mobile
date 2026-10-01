#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$SCRIPT_DIR/../iosApp/KuiklyExpand"
OUTPUT="$(mktemp -t raft-adaptive-navigation.XXXXXX)"
trap 'rm -f "$OUTPUT"' EXIT
clang -fobjc-arc -Wall -Wextra -Werror -framework Foundation -I"$SOURCE_DIR" \
  "$SOURCE_DIR/SlockAdaptiveNavigationSession.m" "$SCRIPT_DIR/test_adaptive_navigation_session.m" -o "$OUTPUT"
"$OUTPUT"
python3 "$SCRIPT_DIR/test_adaptive_navigation_registration.py"
