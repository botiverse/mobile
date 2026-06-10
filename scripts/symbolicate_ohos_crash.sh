#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INPUT_FILE=""
ADDR2LINE=""
READELF=""
SYMBOL_DIRS=()

usage() {
  cat <<'USAGE'
Usage: scripts/symbolicate_ohos_crash.sh [options] [crash.txt]

Annotates OHOS native crash stacks with local llvm-addr2line output. The
script matches stack modules by build-id when the crash line contains
libname.so(<build-id>), then resolves pc offsets against unstripped local SOs.

Options:
  --symbol-dir <dir>  Add a directory to search recursively for *.so files.
                      May be repeated.
  --addr2line <path>  Override llvm-addr2line path.
  --readelf <path>    Override llvm-readelf/readelf path.
  -h, --help          Show this help.

Default symbol search roots:
  artifacts/ohos-bugly-symbols/*/obj/*/
  compose/shared/build/bin/ohosArm64/debugShared/
  compose/ohosApp/entry/build/default/intermediates/cmake/default/obj/*/
  compose/ohosApp/entry/libs/*/

Examples:
  scripts/symbolicate_ohos_crash.sh crash.txt
  pbpaste | scripts/symbolicate_ohos_crash.sh
USAGE
}

die() {
  echo "error: $*" >&2
  exit 64
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --symbol-dir)
      [ "$#" -ge 2 ] || die "--symbol-dir requires a value"
      SYMBOL_DIRS+=("$2")
      shift 2
      ;;
    --addr2line)
      [ "$#" -ge 2 ] || die "--addr2line requires a value"
      ADDR2LINE="$2"
      shift 2
      ;;
    --readelf)
      [ "$#" -ge 2 ] || die "--readelf requires a value"
      READELF="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      die "unknown option $1"
      ;;
    *)
      [ -z "${INPUT_FILE}" ] || die "multiple input files provided"
      INPUT_FILE="$1"
      shift
      ;;
  esac
done

find_tool() {
  local override="$1"
  shift
  if [ -n "${override}" ]; then
    [ -x "${override}" ] || die "tool is not executable: ${override}"
    printf '%s\n' "${override}"
    return
  fi
  local candidate
  for candidate in "$@"; do
    [ -n "${candidate}" ] || continue
    if command -v "${candidate}" >/dev/null 2>&1; then
      command -v "${candidate}"
      return
    fi
    if [ -x "${candidate}" ]; then
      printf '%s\n' "${candidate}"
      return
    fi
  done
  return 1
}

ADDR2LINE="$(find_tool "${ADDR2LINE}" \
  "${DEVECO_SDK_HOME:-}/default/openharmony/native/llvm/bin/llvm-addr2line" \
  "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin/llvm-addr2line" \
  "llvm-addr2line" \
  "addr2line")" || die "missing llvm-addr2line; pass --addr2line"

READELF="$(find_tool "${READELF}" \
  "${DEVECO_SDK_HOME:-}/default/openharmony/native/llvm/bin/llvm-readelf" \
  "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin/llvm-readelf" \
  "llvm-readelf" \
  "readelf")" || die "missing llvm-readelf/readelf; pass --readelf"

if [ "${#SYMBOL_DIRS[@]}" -eq 0 ]; then
  for dir in \
    "${ROOT_DIR}"/artifacts/ohos-bugly-symbols/*/obj/* \
    "${ROOT_DIR}/compose/shared/build/bin/ohosArm64/debugShared" \
    "${ROOT_DIR}"/compose/ohosApp/entry/build/default/intermediates/cmake/default/obj/* \
    "${ROOT_DIR}"/compose/ohosApp/entry/libs/*; do
    [ -d "${dir}" ] && SYMBOL_DIRS+=("${dir}")
  done
fi

read_build_id() {
  "${READELF}" -n "$1" 2>/dev/null | sed -n 's/.*Build ID: //p' | head -1 | tr '[:upper:]' '[:lower:]'
}

normalize_hex() {
  printf '%s' "$1" | sed 's/^0x//' | tr '[:upper:]' '[:lower:]'
}

find_symbol_so() {
  local module="$1"
  local expected_build_id
  expected_build_id="$(normalize_hex "$2")"
  local dir so build_id fallback=""
  for dir in "${SYMBOL_DIRS[@]}"; do
    [ -d "${dir}" ] || continue
    while IFS= read -r so; do
      [ -n "${fallback}" ] || fallback="${so}"
      if [ -n "${expected_build_id}" ]; then
        build_id="$(read_build_id "${so}")"
        if [ "${build_id}" = "${expected_build_id}" ]; then
          printf '%s\n' "${so}"
          return 0
        fi
      fi
    done < <(find "${dir}" -name "${module}" -type f 2>/dev/null)
  done
  if [ -z "${expected_build_id}" ] && [ -n "${fallback}" ]; then
    printf '%s\n' "${fallback}"
    return 0
  fi
  return 1
}

symbolicate_line() {
  local line="$1"
  printf '%s\n' "${line}"

  local parsed pc module build_id so resolved actual_build_id
  parsed="$(
    printf '%s\n' "${line}" |
      sed -nE 's/.*pc[[:space:]]+0*([0-9a-fA-Fx]+).*\/([^\/[:space:]]+\.so)(\(([0-9a-fA-F]+)\))?.*/\1	\2	\4/p'
  )"
  [ -n "${parsed}" ] || return 0

  pc="$(printf '%s' "${parsed}" | cut -f1)"
  module="$(printf '%s' "${parsed}" | cut -f2)"
  build_id="$(printf '%s' "${parsed}" | cut -f3)"
  pc="$(normalize_hex "${pc}")"

  if ! so="$(find_symbol_so "${module}" "${build_id}")"; then
    printf '  => unresolved: no local symbol file for %s build-id=%s\n' "${module}" "${build_id:-unknown}"
    return 0
  fi

  actual_build_id="$(read_build_id "${so}")"
  resolved="$("${ADDR2LINE}" -f -C -p -e "${so}" "0x${pc}" 2>/dev/null || true)"
  printf '  => %s build-id=%s symbols=%s\n' "${module}" "${actual_build_id:-unknown}" "${so}"
  printf '     %s\n' "${resolved:-?? at ??:0}"
}

if [ -n "${INPUT_FILE}" ]; then
  [ -f "${INPUT_FILE}" ] || die "missing input file: ${INPUT_FILE}"
  while IFS= read -r line || [ -n "${line}" ]; do
    symbolicate_line "${line}"
  done <"${INPUT_FILE}"
else
  while IFS= read -r line || [ -n "${line}" ]; do
    symbolicate_line "${line}"
  done
fi
