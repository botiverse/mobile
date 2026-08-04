#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <libshared.so>" >&2
}

[[ $# -eq 1 ]] || {
  usage
  exit 64
}

shared_so="$1"
[[ -f "$shared_so" ]] || {
  echo "Kuikly provenance gate: missing shared library: $shared_so" >&2
  exit 2
}

# Hands 786dd9fe / task #55: Mobile once packaged the pinned selectable-text
# renderer HAR with an older same-coordinate Compose KLIB. The old KLIB linked
# successfully but lacked the lazy-layout measure policy and native-dispatch
# release path, then crashed in checkMeasuredSize on OHOS. These fork-only
# binary markers make that mixed artifact set fail closed before HAP packaging.
required_markers=(
  selectableTextMeasurePolicy
  selectableTextViewForMeasure
  nativeDispatchRelease
)

binary_strings="$(LC_ALL=C strings "$shared_so")"
missing=()
for marker in "${required_markers[@]}"; do
  if [[ "$binary_strings" != *"$marker"* ]]; then
    missing+=("$marker")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "Kuikly provenance gate: libshared.so is missing pinned-fork markers:" >&2
  printf '  - %s\n' "${missing[@]}" >&2
  echo "Re-publish the checked-out Kuikly fork and rebuild with refreshed dependencies." >&2
  exit 1
fi

echo "Kuikly provenance gate PASS: markers=${#required_markers[@]}"
