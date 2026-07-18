#!/usr/bin/env bash
set -euo pipefail

APK=""
SYMBOLS=""

die() {
  echo "error: $*" >&2
  exit 64
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk)
      [ "$#" -ge 2 ] || die "--apk requires a value"
      APK="$2"
      shift 2
      ;;
    --symbols)
      [ "$#" -ge 2 ] || die "--symbols requires a value"
      SYMBOLS="$2"
      shift 2
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[ -f "$APK" ] || die "APK not found: $APK"
[ -f "$SYMBOLS" ] || die "native symbols archive not found: $SYMBOLS"

find_readelf() {
  local candidate
  for candidate in \
    "${SLOCK_LLVM_READELF:-}" \
    "${ANDROID_NDK_ROOT:-}/toolchains/llvm/prebuilt"/*/bin/llvm-readelf \
    "${ANDROID_HOME:-}/ndk"/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf \
    llvm-readelf \
    readelf; do
    [ -n "$candidate" ] || continue
    if [[ "$candidate" == */* ]]; then
      [ -x "$candidate" ] && printf '%s\n' "$candidate" && return 0
    elif command -v "$candidate" >/dev/null 2>&1; then
      command -v "$candidate"
      return 0
    fi
  done
  return 1
}

READELF="$(find_readelf)" || die "llvm-readelf/readelf not found"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$SYMBOLS" -d "$WORK/symbols"
[ -f "$WORK/symbols/manifest.json" ] || die "manifest.json missing from archive"
python3 - "$WORK/symbols/manifest.json" "$WORK/manifest.tsv" <<'PY'
import json
import sys

manifest_path, output_path = sys.argv[1:]
with open(manifest_path, encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("soname") != "libhandscrash.so":
    raise SystemExit("manifest soname must be libhandscrash.so")
if not payload.get("sdk_version"):
    raise SystemExit("manifest sdk_version is required")
source = payload.get("source") or {}
if source.get("repo") != "https://github.com/botiverse/hands" or not source.get("commit"):
    raise SystemExit("manifest source repo/commit is invalid")
with open(output_path, "w", encoding="utf-8") as handle:
    for arch, item in sorted((payload.get("abis") or {}).items()):
        handle.write(f"{arch}\t{item.get('build_id', '')}\t{item.get('sha256', '')}\n")
PY

count=0
while IFS= read -r apk_entry; do
  arch="$(printf '%s' "$apk_entry" | cut -d/ -f2)"
  apk_so="$WORK/apk/$arch/libhandscrash.so"
  symbol_so="$WORK/symbols/$arch/libhandscrash.so"
  mkdir -p "$(dirname "$apk_so")"
  unzip -qp "$APK" "$apk_entry" > "$apk_so"
  [ -f "$symbol_so" ] || die "symbols archive does not cover APK ABI $arch"

  apk_id="$($READELF -n "$apk_so" | awk '/Build ID:/ {print $3; exit}')"
  symbol_id="$($READELF -n "$symbol_so" | awk '/Build ID:/ {print $3; exit}')"
  manifest_id="$(awk -F '\t' -v arch="$arch" '$1 == arch {print $2}' "$WORK/manifest.tsv")"
  manifest_sha="$(awk -F '\t' -v arch="$arch" '$1 == arch {print $3}' "$WORK/manifest.tsv")"
  actual_sha="$(sha256sum "$symbol_so" | awk '{print $1}')"
  [ -n "$apk_id" ] || die "APK libhandscrash.so has no build ID for $arch"
  [ "$apk_id" = "$symbol_id" ] || die "build ID mismatch for $arch: apk=$apk_id symbols=$symbol_id"
  [ "$symbol_id" = "$manifest_id" ] || die "manifest build ID mismatch for $arch"
  [ "$actual_sha" = "$manifest_sha" ] || die "manifest sha256 mismatch for $arch"
  if ! "$READELF" -S "$symbol_so" | awk '/\.debug_info/ { found=1 } END { exit(found ? 0 : 1) }'; then
    die "symbol file is stripped for $arch"
  fi
  count=$((count + 1))
done < <(unzip -Z1 "$APK" | grep -E '^(lib|jni)/[^/]+/libhandscrash\.so$' | sort)

[ "$count" -gt 0 ] || die "APK does not contain libhandscrash.so"
echo "Verified $count Hands native symbol files against APK build IDs."
