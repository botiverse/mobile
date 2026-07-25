#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "warning: prepare_android_quiver_symbols.sh is deprecated; use prepare_android_hands_symbols.sh before 2026-08-31" >&2
exec "${SCRIPT_DIR}/prepare_android_hands_symbols.sh" "$@"
