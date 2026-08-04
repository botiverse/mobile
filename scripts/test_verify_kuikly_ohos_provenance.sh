#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
verifier="$script_dir/verify_kuikly_ohos_provenance.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kuikly-ohos-provenance-test.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

printf '%s\n' \
  selectableTextMeasurePolicy \
  selectableTextViewForMeasure \
  nativeDispatchRelease > "$tmp_dir/complete.bin"

"$verifier" "$tmp_dir/complete.bin" >/dev/null

printf '%s\n' \
  selectableTextMeasurePolicy \
  selectableTextViewForMeasure > "$tmp_dir/stale.bin"

if "$verifier" "$tmp_dir/stale.bin" >"$tmp_dir/stale.out" 2>&1; then
  echo "Expected stale mixed-artifact fixture to fail" >&2
  exit 1
fi
grep -F 'nativeDispatchRelease' "$tmp_dir/stale.out" >/dev/null

if "$verifier" "$tmp_dir/missing.bin" >"$tmp_dir/missing.out" 2>&1; then
  echo "Expected missing binary fixture to fail" >&2
  exit 1
fi
grep -F 'missing shared library' "$tmp_dir/missing.out" >/dev/null

echo "Kuikly OHOS provenance verifier self-test: PASS"
