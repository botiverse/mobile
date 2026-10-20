#!/usr/bin/env bash
# Shared versionCode computation for Android/iOS releases.
#
# Formula (task #321, authoritative single source):
#   versionCode = major*10_000_000 + minor*100_000 + patch*1_000 + build
#   build domain: 0..999 (overflow rejected)
#
# Why three digits for build: the previous layout (patch*100) let build>=100
# silently alias into the patch digit (1.12.0 build 131 = 1.12.1 build 31 =
# versionCode 1120131). Three-digit build keeps each component disjoint.
#
# Usage: VERSION_CODE=$(scripts/ci/version_code.sh MAJOR MINOR PATCH BUILD)
set -euo pipefail
major="${1:?major required}"
minor="${2:?minor required}"
patch="${3:?patch required}"
build="${4:?build required}"
for v in "$major" "$minor" "$patch" "$build"; do
  case "$v" in (*[!0-9]*|'') echo "version component must be a non-negative integer: $v" >&2; exit 1;; esac
done
if (( minor > 99 )); then
  echo "version_minor must be between 0 and 99 (got $minor)" >&2
  exit 1
fi
if (( patch > 99 )); then
  echo "version_patch must be between 0 and 99 (got $patch)" >&2
  exit 1
fi
if (( build > 999 )); then
  echo "version_build must be between 0 and 999 (got $build)" >&2
  exit 1
fi
echo $((major * 10000000 + minor * 100000 + patch * 1000 + build))
