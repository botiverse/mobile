#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <libshared.so> <libkuikly.so>" >&2
}

[[ $# -eq 2 ]] || {
  usage
  exit 64
}

shared_so="$1"
render_so="$2"

[[ -f "$shared_so" ]] || {
  echo "Kuikly ABI gate: missing shared library: $shared_so" >&2
  exit 2
}
[[ -f "$render_so" ]] || {
  echo "Kuikly ABI gate: missing render library: $render_so" >&2
  exit 2
}

deveco_sdk_home="${DEVECO_SDK_HOME:-/Applications/DevEco-Studio.app/Contents/sdk}"
llvm_nm="${LLVM_NM:-${deveco_sdk_home}/default/openharmony/native/llvm/bin/llvm-nm}"
llvm_readelf="${LLVM_READELF:-${deveco_sdk_home}/default/openharmony/native/llvm/bin/llvm-readelf}"

[[ -x "$llvm_nm" ]] || {
  echo "Kuikly ABI gate: llvm-nm is not executable: $llvm_nm" >&2
  exit 2
}
[[ -x "$llvm_readelf" ]] || {
  echo "Kuikly ABI gate: llvm-readelf is not executable: $llvm_readelf" >&2
  exit 2
}

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kuikly-ohos-abi.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

"$llvm_nm" -D --undefined-only "$shared_so" \
  | awk '$NF ~ /^com_tencent_kuikly_/ { print $NF }' \
  | LC_ALL=C sort -u > "$tmp_dir/required.txt"

"$llvm_nm" -D --defined-only "$render_so" \
  | awk '$NF ~ /^com_tencent_kuikly_/ { print $NF }' \
  | LC_ALL=C sort -u > "$tmp_dir/exported.txt"

LC_ALL=C comm -23 "$tmp_dir/required.txt" "$tmp_dir/exported.txt" > "$tmp_dir/missing.txt"
if [[ -s "$tmp_dir/missing.txt" ]]; then
  echo "Kuikly ABI gate: libshared.so requires symbols missing from final libkuikly.so:" >&2
  sed 's/^/  - /' "$tmp_dir/missing.txt" >&2
  exit 1
fi

shared_build_id="$($llvm_readelf -n "$shared_so" | awk '/Build ID:/ { print $3; exit }')"
render_build_id="$($llvm_readelf -n "$render_so" | awk '/Build ID:/ { print $3; exit }')"
required_count="$(wc -l < "$tmp_dir/required.txt" | tr -d ' ')"

echo "Kuikly ABI gate PASS: required=${required_count} sharedBuildId=${shared_build_id:-unknown} renderBuildId=${render_build_id:-unknown}"
