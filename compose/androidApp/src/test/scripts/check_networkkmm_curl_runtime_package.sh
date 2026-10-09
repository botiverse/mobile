#!/usr/bin/env bash
set -euo pipefail

# network-android-curl-runtime:0.1.0-raft.41, published from
# botiverse/KuiklyBase-components@e898459dc704ff72a89c14e5d7d81d0c2a6290c1.
readonly EXPECTED_ARM64_SHA256='036a0f17f176dee6d5e76139903ac33953b350866643b4ccd19e05e7031d36e0'
readonly EXPECTED_X86_64_SHA256='c6bc1c3ccc948f899eebaeaff85211acf68b89e0e9c6ff026b78c1ca9f29afd0'
readonly EXPECTED_CA_SHA256='86a1f3366afac7c6f8ae9f3c779ac221129328c43f0ab2b8817eb2f362a5025c'

sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

archive_prefix() {
  local archive="$1"
  local entries
  entries="$(unzip -Z1 "$archive")"
  if grep -qx 'base/lib/arm64-v8a/libnetworkkmmcurl.so' <<<"$entries"; then
    printf 'base/'
  fi
}

verify_entry_hash() {
  local archive="$1"
  local entry="$2"
  local expected="$3"
  local actual
  actual="$(unzip -p "$archive" "$entry" | sha256_stream)"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch for $entry: expected=$expected actual=$actual" >&2
    return 1
  fi
}

verify_archive() {
  local archive="$1"
  if [[ -z "$archive" || ! -f "$archive" ]]; then
    echo "usage: $0 [--self-test] <release.apk|release.aab>" >&2
    return 2
  fi

  local entries prefix expected_native_entries actual_native_entries
  entries="$(unzip -Z1 "$archive")"
  prefix="$(archive_prefix "$archive")"
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
    return 1
  fi

  verify_entry_hash \
    "$archive" \
    "${prefix}lib/arm64-v8a/libnetworkkmmcurl.so" \
    "$EXPECTED_ARM64_SHA256" || return 1
  verify_entry_hash \
    "$archive" \
    "${prefix}lib/x86_64/libnetworkkmmcurl.so" \
    "$EXPECTED_X86_64_SHA256" || return 1
  verify_entry_hash \
    "$archive" \
    "${prefix}assets/network/cacert-2026-05-14.pem" \
    "$EXPECTED_CA_SHA256" || return 1

  echo "NetworkKMM curl runtime package gate passed: $archive"
}

self_test_archive() {
  local archive="$1"
  verify_archive "$archive"

  local prefix scratch mutated entry case_name
  prefix="$(archive_prefix "$archive")"
  scratch="$(mktemp -d)"
  trap 'rm -rf "$scratch"' RETURN
  mutated="${scratch}/mutated.apk"

  for entry in \
    "${prefix}lib/arm64-v8a/libnetworkkmmcurl.so" \
    "${prefix}lib/x86_64/libnetworkkmmcurl.so" \
    "${prefix}assets/network/cacert-2026-05-14.pem"; do
    rm -f "$mutated"
    python3 - "$archive" "$mutated" "$entry" <<'PY'
import sys
import zipfile

source, destination, target = sys.argv[1:]
with zipfile.ZipFile(source, "r") as source_zip:
    with zipfile.ZipFile(destination, "w") as destination_zip:
        for info in source_zip.infolist():
            payload = source_zip.read(info.filename)
            if info.filename == target:
                payload += b"\0"
            destination_zip.writestr(info, payload)
PY
    if verify_archive "$mutated" >/dev/null 2>&1; then
      echo "Negative fixture unexpectedly passed after mutating $entry" >&2
      return 1
    fi
    case_name="${entry#${prefix}}"
    echo "NetworkKMM negative fixture passed: mutation rejected for $case_name"
  done

  echo "NetworkKMM curl runtime package self-test passed: $archive"
}

mode='verify'
if [[ "${1:-}" == '--self-test' ]]; then
  mode='self-test'
  shift
fi
archive="${1:-}"

if [[ "$mode" == 'self-test' ]]; then
  self_test_archive "$archive"
else
  verify_archive "$archive"
fi
