#!/usr/bin/env bash
# The app version a source tree is heading to: its newest change-logs/<X.Y.Z> directory. Each
# release keeps its notes and changesets there, so a release branch's (or tag's) newest one is its
# own version and main's is the next release.
#
# Builds that are not release builds stamp it (the on-demand iOS Simulator app,
# ios-simulator-app.yml), so the app's About reads e.g. "1.28.0" instead of the Xcode
# project's "1.0" placeholder. Release lanes keep passing their exact version (ios-release.yml).
#
# Usage: scripts/ci/source_app_version.sh [source-root]   -> prints X.Y.Z
# Portable (macOS bash 3.2 / BSD userland and Linux): no find -printf, no sort -V.
set -euo pipefail
root="${1:-.}"
dir="$root/change-logs"
if [ ! -d "$dir" ]; then
  echo "source_app_version: no change-logs/ directory under $root" >&2
  exit 1
fi
version=$(
  for path in "$dir"/*/; do
    [ -d "$path" ] || continue
    name=$(basename "$path")
    if [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then printf '%s\n' "$name"; fi
  done | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1
)
if [ -z "$version" ]; then
  echo "source_app_version: no X.Y.Z directory in $dir" >&2
  exit 1
fi
printf '%s\n' "$version"
