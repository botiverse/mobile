#!/usr/bin/env bash
set -euo pipefail

archive="${1:-}"
if [[ -z "$archive" || ! -f "$archive" ]]; then
  echo "usage: $0 <release.apk|release.aab>" >&2
  exit 2
fi

sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

entries="$(unzip -Z1 "$archive")"
if grep -qx 'base/lib/arm64-v8a/libnetworkkmmcurl.so' <<<"$entries"; then
  prefix='base/'
else
  prefix=''
fi

expected_native_entries="${prefix}lib/arm64-v8a/libnetworkkmmcurl.so
${prefix}lib/x86_64/libnetworkkmmcurl.so"
actual_native_entries="$(
  grep -E '(^|/)libnetworkkmmcurl\.so$' <<<"$entries" | LC_ALL=C sort
)"
if [[ "$actual_native_entries" != "$expected_native_entries" ]]; then
  echo "Unexpected NetworkKMM curl runtime entries in $archive" >&2
  echo "Expected:" >&2
  printf '%s\n' "$expected_native_entries" >&2
  echo "Actual:" >&2
  printf '%s\n' "$actual_native_entries" >&2
  exit 1
fi

verify_entry_hash() {
  local entry="$1"
  local expected="$2"
  local actual
  actual="$(unzip -p "$archive" "$entry" | sha256_stream)"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch for $entry: expected=$expected actual=$actual" >&2
    exit 1
  fi
}

verify_entry_hash \
  "${prefix}lib/arm64-v8a/libnetworkkmmcurl.so" \
  'cade582ae10233c3e6e2a9321b8117f334fe7ebc23e36051a93d6f4acd5dd0e3'
verify_entry_hash \
  "${prefix}lib/x86_64/libnetworkkmmcurl.so" \
  '47f473900f3d25c10f8cfa45fbd3298aa0836719624444ca97adec15e1f93b02'
verify_entry_hash \
  "${prefix}assets/network/cacert-2026-05-14.pem" \
  '86a1f3366afac7c6f8ae9f3c779ac221129328c43f0ab2b8817eb2f362a5025c'

echo "NetworkKMM curl runtime package gate passed: $archive"
