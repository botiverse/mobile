#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "warning: publish_android_quiver_release.sh is deprecated; use publish_android_hands_release.sh before 2026-08-31" >&2
exec "${SCRIPT_DIR}/publish_android_hands_release.sh" "$@"
