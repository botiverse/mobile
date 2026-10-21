#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_APP_DIR="${ROOT_DIR}/compose/androidApp"
ANDROID_BUILD_FILE="${ANDROID_APP_DIR}/build.gradle.kts"

VARIANT="release"
VERSION=""
BUILD_NO=""
OUTPUT_DIR=""
SYMBOL_ROOT=""
MAPPING_FILE=""
SYMBOL_UPLOAD_JAR=""
BUILD_LABEL=""
CREATE_ARCHIVE=1
ARCH_FILTERS=()
MODULES=()
EXPECTS=()

usage() {
  cat <<'USAGE'
Usage: scripts/prepare_android_hands_symbols.sh [options]

Collects Android Debug SO files and/or mapping.txt, records native build IDs
and symbol UUIDs, and prepares a symbol upload command template.

Defaults:
  Symbol root: compose/androidApp/build/intermediates/merged_native_libs/<variant>/merge<Variant>NativeLibs/out/lib
  Mapping:     compose/androidApp/build/outputs/mapping/<variant>/mapping.txt, if present
  Version:     compose/androidApp/build.gradle.kts defaultConfig.versionName
  Build no:    compose/androidApp/build.gradle.kts defaultConfig.versionCode
  Platform:    Android

Options:
  --module <name>       Include only this SO module, e.g. libfoo.so.
                        May be repeated. Default is every *.so.
  --expect <key=uuid>   Verify a symbol UUID. Key may be module name or
                        arch/module, e.g. arm64-v8a/libfoo.so. May repeat.
  --arch <name>         Include only this ABI directory. May be repeated.
                        If --symbol-dir points directly at SO files, the first
                        --arch value is used as the staged ABI name.
  --symbol-dir <dir>    Override SO source root. The directory may contain ABI
                        subdirectories or *.so files directly.
  --mapping <file>      Include a mapping.txt file in the generated command.
                        If omitted, the default mapping path is used when it
                        exists. Missing default mapping is not an error.
  --output-dir <dir>    Output directory. Default:
                        artifacts/android-hands-symbols/<version>-<buildNo>-<git>
  --version <value>     Override version passed to the symbol uploader.
  --build-no <value>    Override build number passed to the symbol uploader.
  --build-label <value> Label used in the default output directory and archive
                        name. Default is the current git HEAD short hash.
  --variant <name>      Android build variant/build type. Default: release.
  --jar <path>          Path to symbol upload jar for the generated
                        command. If omitted, command uses SYMBOL_UPLOAD_JAR env.
  --no-archive          Do not create the backup zip.
  -h, --help            Show this help.

Generated files:
  obj/<abi>/*.so              Staged SO files for -inputSymbol
  mapping/mapping.txt         Staged mapping file, when present
  symbols-manifest.tsv        Module/build-id/UUID/size/sha256 manifest
  hands-upload-command.sh      Template command; expects SYMBOL_UPLOAD_APP_ID and
                               SYMBOL_UPLOAD_APP_KEY env vars
  *.zip                       Optional backup archive containing the above

The script never stores symbol upload credentials.
USAGE
}

die() {
  echo "error: $*" >&2
  exit 64
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --module)
      [ "$#" -ge 2 ] || die "--module requires a value"
      MODULES+=("$2")
      shift 2
      ;;
    --expect)
      [ "$#" -ge 2 ] || die "--expect requires key=uuid"
      EXPECTS+=("$2")
      shift 2
      ;;
    --arch)
      [ "$#" -ge 2 ] || die "--arch requires a value"
      ARCH_FILTERS+=("$2")
      shift 2
      ;;
    --symbol-dir)
      [ "$#" -ge 2 ] || die "--symbol-dir requires a value"
      SYMBOL_ROOT="$2"
      shift 2
      ;;
    --mapping)
      [ "$#" -ge 2 ] || die "--mapping requires a value"
      MAPPING_FILE="$2"
      shift 2
      ;;
    --output-dir)
      [ "$#" -ge 2 ] || die "--output-dir requires a value"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --version)
      [ "$#" -ge 2 ] || die "--version requires a value"
      VERSION="$2"
      shift 2
      ;;
    --build-no)
      [ "$#" -ge 2 ] || die "--build-no requires a value"
      BUILD_NO="$2"
      shift 2
      ;;
    --build-label)
      [ "$#" -ge 2 ] || die "--build-label requires a value"
      BUILD_LABEL="$2"
      shift 2
      ;;
    --variant)
      [ "$#" -ge 2 ] || die "--variant requires a value"
      VARIANT="$2"
      shift 2
      ;;
    --jar)
      [ "$#" -ge 2 ] || die "--jar requires a value"
      SYMBOL_UPLOAD_JAR="$2"
      shift 2
      ;;
    --no-archive)
      CREATE_ARCHIVE=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option $1"
      ;;
  esac
done

gradle_string_field() {
  local field="$1"
  sed -nE "s/^[[:space:]]*${field}[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\\1/p" "${ANDROID_BUILD_FILE}" | head -n 1
}

gradle_number_field() {
  local field="$1"
  sed -nE "s/^[[:space:]]*${field}[[:space:]]*=[[:space:]]*([0-9]+).*/\\1/p" "${ANDROID_BUILD_FILE}" | head -n 1
}

abs_path() {
  local path="$1"
  if [ -d "${path}" ]; then
    (cd "${path}" && pwd)
  else
    (cd "$(dirname "${path}")" && printf '%s/%s\n' "$(pwd)" "$(basename "${path}")")
  fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_size() {
  wc -c <"$1" | tr -d '[:space:]'
}

capitalize_variant() {
  local first rest
  first="$(printf '%s' "${1:0:1}" | tr '[:lower:]' '[:upper:]')"
  rest="${1:1}"
  printf '%s%s' "${first}" "${rest}"
}

find_default_symbol_root() {
  local variant_cap
  variant_cap="$(capitalize_variant "${VARIANT}")"
  local candidates=(
    "${ANDROID_APP_DIR}/build/intermediates/merged_native_libs/${VARIANT}/merge${variant_cap}NativeLibs/out/lib"
    "${ANDROID_APP_DIR}/build/intermediates/cmake/${VARIANT}/obj"
    "${ANDROID_APP_DIR}/build/intermediates/ndk/${VARIANT}/obj"
    "${ANDROID_APP_DIR}/build/intermediates/stripped_native_libs/${VARIANT}/strip${variant_cap}DebugSymbols/out/lib"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [ -d "${candidate}" ]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

find_readelf() {
  local candidates=(
    "${SLOCK_LLVM_READELF:-}"
    "${ANDROID_HOME:-}/ndk-bundle/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
    "${ANDROID_HOME:-}/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
    "llvm-readelf"
    "readelf"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    [ -n "${candidate}" ] || continue
    if [[ "${candidate}" == */* ]]; then
      if [ -x "${candidate}" ]; then
        printf '%s\n' "${candidate}"
        return 0
      fi
    elif command -v "${candidate}" >/dev/null 2>&1; then
      command -v "${candidate}"
      return 0
    fi
  done
  return 1
}

read_build_id() {
  local file="$1"
  "${READELF}" -n "${file}" | awk '/Build ID:/ {print $3; exit}'
}

has_debug_info() {
  if "${READELF}" -S "$1" | awk '/\.debug_info/ { found = 1 } END { exit(found ? 0 : 1) }'; then
    printf 'yes'
  else
    printf 'no'
  fi
}

normalize_symbol_uuid() {
  local raw
  raw="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  local len="${#raw}"
  if [ "${len}" -eq 0 ]; then
    printf ''
  elif [ "${len}" -lt 32 ]; then
    while [ "${#raw}" -lt 32 ]; do
      raw="${raw}0"
    done
    printf '%s' "${raw}"
  elif [ "${len}" -gt 32 ]; then
    printf '%s' "${raw:$((${len} - 32)):32}"
  else
    printf '%s' "${raw}"
  fi
}

expected_uuid_for() {
  local arch="$1"
  local module="$2"
  local item key value
  for item in "${EXPECTS[@]}"; do
    key="${item%%=*}"
    value="${item#*=}"
    if [ "${key}" = "${arch}/${module}" ] || [ "${key}" = "${module}" ]; then
      printf '%s' "$(normalize_symbol_uuid "${value}")"
      return 0
    fi
  done
  printf ''
}

quote_sh() {
  printf "%q" "$1"
}

has_direct_so_files() {
  local dir="$1"
  local so
  for so in "${dir}"/*.so; do
    [ -e "${so}" ] && return 0
  done
  return 1
}

arch_selected() {
  local arch="$1"
  local filter
  if [ "${#ARCH_FILTERS[@]}" -eq 0 ]; then
    return 0
  fi
  for filter in "${ARCH_FILTERS[@]}"; do
    [ "${filter}" = "${arch}" ] && return 0
  done
  return 1
}

module_selected() {
  local module="$1"
  local filter
  if [ "${#MODULES[@]}" -eq 0 ]; then
    return 0
  fi
  for filter in "${MODULES[@]}"; do
    [ "${filter}" = "${module}" ] && return 0
  done
  return 1
}

VERSION="${VERSION:-$(gradle_string_field versionName)}"
BUILD_NO="${BUILD_NO:-$(gradle_number_field versionCode)}"
[ -n "${VERSION}" ] || die "could not read versionName from ${ANDROID_BUILD_FILE}; pass --version"
[ -n "${BUILD_NO}" ] || die "could not read versionCode from ${ANDROID_BUILD_FILE}; pass --build-no"

if [ -z "${SYMBOL_ROOT}" ]; then
  SYMBOL_ROOT="$(find_default_symbol_root || true)"
fi

if [ -n "${SYMBOL_ROOT}" ]; then
  [ -d "${SYMBOL_ROOT}" ] || die "missing SO directory: ${SYMBOL_ROOT}"
  SYMBOL_ROOT="$(abs_path "${SYMBOL_ROOT}")"
fi

if [ -z "${MAPPING_FILE}" ]; then
  DEFAULT_MAPPING="${ANDROID_APP_DIR}/build/outputs/mapping/${VARIANT}/mapping.txt"
  if [ -f "${DEFAULT_MAPPING}" ]; then
    MAPPING_FILE="${DEFAULT_MAPPING}"
  fi
else
  [ -f "${MAPPING_FILE}" ] || die "missing mapping file: ${MAPPING_FILE}"
fi

GIT_SHORT="${BUILD_LABEL:-$(git -C "${ROOT_DIR}" rev-parse --short=8 HEAD 2>/dev/null || printf 'nogit')}"
if [ -z "${OUTPUT_DIR}" ]; then
  OUTPUT_DIR="${ROOT_DIR}/artifacts/android-hands-symbols/${VERSION}-${BUILD_NO}-${GIT_SHORT}"
fi
mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(abs_path "${OUTPUT_DIR}")"

READELF="$(find_readelf)" || die "missing llvm-readelf/readelf; set SLOCK_LLVM_READELF"

STAGE_ROOT="${OUTPUT_DIR}/obj"
MANIFEST="${OUTPUT_DIR}/symbols-manifest.tsv"
COMMAND_FILE="${OUTPUT_DIR}/hands-upload-command.sh"

{
  printf 'module\tarch\traw_build_id\tsymbol_uuid\texpected_uuid\tmatch\tdebug_info\tsize_bytes\tsha256\tsource_path\tstaged_path\n'
} >"${MANIFEST}"

MISMATCHES=0
STAGED_SO_COUNT=0
STAGED_ARCHES=()

stage_so() {
  local arch="$1"
  local so="$2"
  local module raw_build_id symbol_uuid expected_uuid match staged stage_dir
  [ -f "${so}" ] || die "missing SO file: ${so}"
  so="$(abs_path "${so}")"
  module="$(basename "${so}")"
  module_selected "${module}" || return 0
  raw_build_id="$(read_build_id "${so}")"
  [ -n "${raw_build_id}" ] || die "could not read build ID from ${so}"
  symbol_uuid="$(normalize_symbol_uuid "${raw_build_id}")"
  expected_uuid="$(expected_uuid_for "${arch}" "${module}")"
  match=""
  if [ -n "${expected_uuid}" ]; then
    if [ "${symbol_uuid}" = "${expected_uuid}" ]; then
      match="yes"
    else
      match="no"
      MISMATCHES=$((MISMATCHES + 1))
    fi
  fi
  stage_dir="${STAGE_ROOT}/${arch}"
  mkdir -p "${stage_dir}"
  staged="${stage_dir}/${module}"
  rm -f "${staged}"
  ln "${so}" "${staged}" 2>/dev/null || cp -p "${so}" "${staged}"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${module}" \
    "${arch}" \
    "${raw_build_id}" \
    "${symbol_uuid}" \
    "${expected_uuid}" \
    "${match}" \
    "$(has_debug_info "${so}")" \
    "$(file_size "${so}")" \
    "$(sha256_file "${so}")" \
    "${so}" \
    "${staged}" >>"${MANIFEST}"
  STAGED_SO_COUNT=$((STAGED_SO_COUNT + 1))
}

if [ -n "${SYMBOL_ROOT}" ]; then
  if has_direct_so_files "${SYMBOL_ROOT}"; then
    DIRECT_ARCH="$(basename "${SYMBOL_ROOT}")"
    if [ "${#ARCH_FILTERS[@]}" -gt 0 ]; then
      DIRECT_ARCH="${ARCH_FILTERS[0]}"
    fi
    if arch_selected "${DIRECT_ARCH}"; then
      STAGED_ARCHES+=("${DIRECT_ARCH}")
      for so in "${SYMBOL_ROOT}"/*.so; do
        [ -e "${so}" ] || continue
        stage_so "${DIRECT_ARCH}" "${so}"
      done
    fi
  else
    while IFS= read -r arch_dir; do
      [ -n "${arch_dir}" ] || continue
      arch="$(basename "${arch_dir}")"
      arch_selected "${arch}" || continue
      has_direct_so_files "${arch_dir}" || continue
      STAGED_ARCHES+=("${arch}")
      for so in "${arch_dir}"/*.so; do
        [ -e "${so}" ] || continue
        stage_so "${arch}" "${so}"
      done
    done < <(find "${SYMBOL_ROOT}" -mindepth 1 -maxdepth 1 -type d | sort)
  fi
fi

MAPPING_STAGED=""
if [ -n "${MAPPING_FILE}" ]; then
  MAPPING_FILE="$(abs_path "${MAPPING_FILE}")"
  mkdir -p "${OUTPUT_DIR}/mapping"
  MAPPING_STAGED="${OUTPUT_DIR}/mapping/$(basename "${MAPPING_FILE}")"
  rm -f "${MAPPING_STAGED}"
  cp -p "${MAPPING_FILE}" "${MAPPING_STAGED}"
fi

if [ "${STAGED_SO_COUNT}" -eq 0 ] && [ -z "${MAPPING_STAGED}" ]; then
  die "no SO files or mapping.txt found; pass --symbol-dir and/or --mapping"
fi

SYMBOL_ARG=""
SYMBOL_INPUT=""
if [ "${STAGED_SO_COUNT}" -gt 0 ]; then
  if [ "${#STAGED_ARCHES[@]}" -eq 1 ]; then
    SYMBOL_INPUT="${STAGE_ROOT}/${STAGED_ARCHES[0]}"
  else
    SYMBOL_INPUT="${STAGE_ROOT}"
  fi
  SYMBOL_INPUT="$(abs_path "${SYMBOL_INPUT}")"
  SYMBOL_ARG=" -inputSymbol $(quote_sh "${SYMBOL_INPUT}")"
fi

MAPPING_ARG=""
if [ -n "${MAPPING_STAGED}" ]; then
  MAPPING_STAGED="$(abs_path "${MAPPING_STAGED}")"
  MAPPING_ARG=" -inputMapping $(quote_sh "${MAPPING_STAGED}")"
fi

{
  printf '#!/usr/bin/env bash\n'
  printf 'set -euo pipefail\n\n'
  printf ': "${SYMBOL_UPLOAD_APP_ID:?Set SYMBOL_UPLOAD_APP_ID}"\n'
  printf ': "${SYMBOL_UPLOAD_APP_KEY:?Set SYMBOL_UPLOAD_APP_KEY}"\n'
  if [ -n "${SYMBOL_UPLOAD_JAR}" ]; then
    printf 'SYMBOL_UPLOAD_JAR=%s\n' "$(quote_sh "$(abs_path "${SYMBOL_UPLOAD_JAR}")")"
  else
    printf ': "${SYMBOL_UPLOAD_JAR:?Set SYMBOL_UPLOAD_JAR to symbol upload jar}"\n'
  fi
  printf '\n'
  printf 'java -jar "${SYMBOL_UPLOAD_JAR}" \\\n'
  printf '  -appid "${SYMBOL_UPLOAD_APP_ID}" \\\n'
  printf '  -appkey "${SYMBOL_UPLOAD_APP_KEY}" \\\n'
  printf '  -version %s \\\n' "$(quote_sh "${VERSION}")"
  printf '  -buildNo %s \\\n' "$(quote_sh "${BUILD_NO}")"
  printf '  -platform Android'
  if [ -n "${SYMBOL_ARG}" ]; then
    printf ' \\\n  %s' "${SYMBOL_ARG# }"
  fi
  if [ -n "${MAPPING_ARG}" ]; then
    printf ' \\\n  %s' "${MAPPING_ARG# }"
  fi
  printf '\n'
} >"${COMMAND_FILE}"
chmod +x "${COMMAND_FILE}"

ARCHIVE_PATH=""
if [ "${CREATE_ARCHIVE}" -eq 1 ]; then
  if command -v zip >/dev/null 2>&1; then
    ARCHIVE_PATH="${OUTPUT_DIR}/slock-android-hands-symbols-${VERSION}-${BUILD_NO}-${GIT_SHORT}.zip"
    rm -f "${ARCHIVE_PATH}"
    ZIP_ITEMS=("$(basename "${MANIFEST}")" "$(basename "${COMMAND_FILE}")")
    [ -d "${OUTPUT_DIR}/obj" ] && ZIP_ITEMS+=("obj")
    [ -d "${OUTPUT_DIR}/mapping" ] && ZIP_ITEMS+=("mapping")
    (
      cd "${OUTPUT_DIR}"
      zip -qr "$(basename "${ARCHIVE_PATH}")" "${ZIP_ITEMS[@]}"
    )
  else
    echo "warning: zip not found; skipping archive" >&2
  fi
fi

cat <<EOF
Prepared Android Hands symbols.
Version: ${VERSION}
Build no: ${BUILD_NO}
Git: ${GIT_SHORT}
SO source root: ${SYMBOL_ROOT:-none}
SO files staged: ${STAGED_SO_COUNT}
Mapping: ${MAPPING_STAGED:-none}
Manifest: ${MANIFEST}
Upload command template: ${COMMAND_FILE}
EOF

if [ -n "${SYMBOL_INPUT}" ]; then
  cat <<EOF
Symbol input: ${SYMBOL_INPUT}
EOF
fi

if [ -n "${ARCHIVE_PATH}" ]; then
  cat <<EOF
Archive: ${ARCHIVE_PATH}
Archive sha256: $(sha256_file "${ARCHIVE_PATH}")
EOF
fi

cat <<EOF

symbol upload notes:
  platform must be Android
EOF

if [ -n "${SYMBOL_UPLOAD_JAR}" ]; then
  cat <<EOF
  set SYMBOL_UPLOAD_APP_ID / SYMBOL_UPLOAD_APP_KEY before running:
    ${COMMAND_FILE}
EOF
else
  cat <<EOF
  set SYMBOL_UPLOAD_APP_ID / SYMBOL_UPLOAD_APP_KEY / SYMBOL_UPLOAD_JAR before running:
    ${COMMAND_FILE}
EOF
fi

if [ "${MISMATCHES}" -gt 0 ]; then
  echo "error: ${MISMATCHES} expected UUID check(s) failed; see ${MANIFEST}" >&2
  exit 5
fi
