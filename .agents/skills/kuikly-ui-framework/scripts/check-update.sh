#!/usr/bin/env bash

# Exit 1 when the explicit reference refresh is older than seven days.
# This check is advisory; ordinary skill use does not refresh automatically.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPDATE_LOG="${KUIKLY_REFERENCE_UPDATE_LOG:-$SCRIPT_DIR/.last-update}"
INTERVAL_SECONDS=$((7 * 24 * 60 * 60))

if [ ! -f "$UPDATE_LOG" ]; then
    echo "Reference refresh has never been recorded."
    exit 1
fi

if modified="$(stat -f '%m' "$UPDATE_LOG" 2>/dev/null)"; then
    :
elif modified="$(stat -c '%Y' "$UPDATE_LOG" 2>/dev/null)"; then
    :
else
    echo "Cannot read refresh timestamp." >&2
    exit 2
fi

age=$(( $(date '+%s') - modified ))
if [ "$age" -ge "$INTERVAL_SECONDS" ]; then
    echo "Reference refresh is $((age / 86400)) days old."
    exit 1
fi

echo "Reference refresh is current ($((age / 86400)) days old)."
