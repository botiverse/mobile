#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "warning: release_android_quiver_github.sh is deprecated; use release_android_hands_github.sh before 2026-08-31" >&2
exec "${SCRIPT_DIR}/release_android_hands_github.sh" "$@"
