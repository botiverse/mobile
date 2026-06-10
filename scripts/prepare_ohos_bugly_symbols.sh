#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_JSON="${ROOT_DIR}/compose/ohosApp/AppScope/app.json5"

VARIANT="default"
ARCH="arm64-v8a"
VERSION=""
BUILD_NO=""
OUTPUT_DIR=""
SYMBOL_DIR=""
MAPPING_DIR=""
BUGLY_JAR=""
BUILD_LABEL=""
CREATE_ARCHIVE=1
MODULES=()
EXPECTS=()

usage() {
  cat <<'USAGE'
Usage: scripts/prepare_ohos_bugly_symbols.sh [options]

Collects Harmony/OHOS Debug SO files from the DevEco CMake intermediates
directory, records their build IDs and Bugly UUIDs, and prepares a Bugly
upload command template.

Defaults:
  Symbol dir: compose/ohosApp/entry/build/<variant>/intermediates/cmake/<variant>/obj/<arch>
  Version:    compose/ohosApp/AppScope/app.json5 versionName
  Build no:   compose/ohosApp/AppScope/app.json5 versionCode
  Platform:   Harmony

Options:
  --module <name>       Include only this SO module, e.g. libshared.so.
                        May be repeated. Default is every *.so in symbol dir.
  --expect <name=uuid>  Verify a Bugly UUID for a module. May be repeated.
  --symbol-dir <dir>    Override Debug SO source directory.
  --mapping-dir <dir>   Include a nameCache/sourceMaps directory in the
                        generated upload command via -inputMapping.
  --output-dir <dir>    Output directory. Default:
                        artifacts/ohos-bugly-symbols/<version>-<buildNo>-<git>
  --version <value>     Override version passed to Bugly.
  --build-no <value>    Override build number passed to Bugly.
  --build-label <value> Label used in the default output directory and archive
                        name. Default is the current git HEAD short hash.
  --variant <name>      DevEco variant name. Default: default.
  --arch <name>         Architecture directory. Default: arm64-v8a.
  --jar <path>          Path to buglyqq-upload-symbol.jar for the generated
                        command. If omitted, command uses BUGLY_SYMBOL_JAR env.
  --no-archive          Do not create the backup zip.
  -h, --help            Show this help.

Generated files:
  obj/<arch>/*.so             Staged Debug SO files for -inputSymbol
  symbols-manifest.tsv        Module/build-id/UUID/size/sha256 manifest
  bugly-upload-command.sh     Template command; expects BUGLY_APP_ID and
                              BUGLY_APP_KEY env vars and exports USE_LLVM=true
  *.zip                       Optional backup archive containing the above

The script never stores Bugly credentials.
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
      [ "$#" -ge 2 ] || die "--expect requires name=uuid"
      EXPECTS+=("$2")
      shift 2
      ;;
    --symbol-dir)
      [ "$#" -ge 2 ] || die "--symbol-dir requires a value"
      SYMBOL_DIR="$2"
      shift 2
      ;;
    --mapping-dir)
      [ "$#" -ge 2 ] || die "--mapping-dir requires a value"
      MAPPING_DIR="$2"
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
    --arch)
      [ "$#" -ge 2 ] || die "--arch requires a value"
      ARCH="$2"
      shift 2
      ;;
    --jar)
      [ "$#" -ge 2 ] || die "--jar requires a value"
      BUGLY_JAR="$2"
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

json5_string_field() {
  local field="$1"
  sed -nE "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"([^\"]+)\".*/\\1/p" "${APP_JSON}" | head -n 1
}

json5_number_field() {
  local field="$1"
  sed -nE "s/.*\"${field}\"[[:space:]]*:[[:space:]]*([0-9]+).*/\\1/p" "${APP_JSON}" | head -n 1
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

find_readelf() {
  local candidates=(
    "${SLOCK_LLVM_READELF:-}"
    "${DEVECO_SDK_HOME:-}/default/openharmony/native/llvm/bin/llvm-readelf"
    "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin/llvm-readelf"
    "/Applications/DevEco-Studio.app/Contents/sdk/default/hms/native/BiSheng/bin/llvm-readelf"
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

normalize_bugly_uuid() {
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
  local module="$1"
  local item key value
  for item in "${EXPECTS[@]}"; do
    key="${item%%=*}"
    value="${item#*=}"
    if [ "${key}" = "${module}" ]; then
      printf '%s' "$(normalize_bugly_uuid "${value}")"
      return 0
    fi
  done
  printf ''
}

quote_sh() {
  printf "%q" "$1"
}

VERSION="${VERSION:-$(json5_string_field versionName)}"
BUILD_NO="${BUILD_NO:-$(json5_number_field versionCode)}"
[ -n "${VERSION}" ] || die "could not read versionName from ${APP_JSON}; pass --version"
[ -n "${BUILD_NO}" ] || die "could not read versionCode from ${APP_JSON}; pass --build-no"

if [ -z "${SYMBOL_DIR}" ]; then
  SYMBOL_DIR="${ROOT_DIR}/compose/ohosApp/entry/build/${VARIANT}/intermediates/cmake/${VARIANT}/obj/${ARCH}"
fi
[ -d "${SYMBOL_DIR}" ] || die "missing Debug SO directory: ${SYMBOL_DIR}"
SYMBOL_DIR="$(abs_path "${SYMBOL_DIR}")"

GIT_SHORT="${BUILD_LABEL:-$(git -C "${ROOT_DIR}" rev-parse --short=8 HEAD 2>/dev/null || printf 'nogit')}"
if [ -z "${OUTPUT_DIR}" ]; then
  OUTPUT_DIR="${ROOT_DIR}/artifacts/ohos-bugly-symbols/${VERSION}-${BUILD_NO}-${GIT_SHORT}"
fi
mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(abs_path "${OUTPUT_DIR}")"

READELF="$(find_readelf)" || die "missing llvm-readelf/readelf; set SLOCK_LLVM_READELF"

STAGE_DIR="${OUTPUT_DIR}/obj/${ARCH}"
mkdir -p "${STAGE_DIR}"
MANIFEST="${OUTPUT_DIR}/symbols-manifest.tsv"
COMMAND_FILE="${OUTPUT_DIR}/bugly-upload-command.sh"

SO_FILES=()
if [ "${#MODULES[@]}" -eq 0 ]; then
  for so in "${SYMBOL_DIR}"/*.so; do
    [ -e "${so}" ] || continue
    SO_FILES+=("${so}")
  done
else
  for module in "${MODULES[@]}"; do
    if [[ "${module}" == */* ]]; then
      SO_FILES+=("${module}")
    else
      SO_FILES+=("${SYMBOL_DIR}/${module}")
    fi
  done
fi

[ "${#SO_FILES[@]}" -gt 0 ] || die "no SO files selected from ${SYMBOL_DIR}"

{
  printf 'module\tarch\traw_build_id\tbugly_uuid\texpected_uuid\tmatch\tdebug_info\tsize_bytes\tsha256\tsource_path\tstaged_path\n'
} >"${MANIFEST}"

MISMATCHES=0
for so in "${SO_FILES[@]}"; do
  [ -f "${so}" ] || die "missing SO file: ${so}"
  so="$(abs_path "${so}")"
  module="$(basename "${so}")"
  raw_build_id="$(read_build_id "${so}")"
  [ -n "${raw_build_id}" ] || die "could not read build ID from ${so}"
  bugly_uuid="$(normalize_bugly_uuid "${raw_build_id}")"
  expected_uuid="$(expected_uuid_for "${module}")"
  match=""
  if [ -n "${expected_uuid}" ]; then
    if [ "${bugly_uuid}" = "${expected_uuid}" ]; then
      match="yes"
    else
      match="no"
      MISMATCHES=$((MISMATCHES + 1))
    fi
  fi
  staged="${STAGE_DIR}/${module}"
  rm -f "${staged}"
  ln "${so}" "${staged}" 2>/dev/null || cp -p "${so}" "${staged}"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${module}" \
    "${ARCH}" \
    "${raw_build_id}" \
    "${bugly_uuid}" \
    "${expected_uuid}" \
    "${match}" \
    "$(has_debug_info "${so}")" \
    "$(file_size "${so}")" \
    "$(sha256_file "${so}")" \
    "${so}" \
    "${staged}" >>"${MANIFEST}"
done

STAGE_DIR_ABS="$(abs_path "${STAGE_DIR}")"
MAPPING_ARG=""
if [ -n "${MAPPING_DIR}" ]; then
  [ -d "${MAPPING_DIR}" ] || die "missing mapping directory: ${MAPPING_DIR}"
  MAPPING_DIR="$(abs_path "${MAPPING_DIR}")"
  MAPPING_ARG=" -inputMapping $(quote_sh "${MAPPING_DIR}")"
fi

{
  printf '#!/usr/bin/env bash\n'
  printf 'set -euo pipefail\n\n'
  printf ': "${BUGLY_APP_ID:?Set BUGLY_APP_ID}"\n'
  printf ': "${BUGLY_APP_KEY:?Set BUGLY_APP_KEY}"\n'
  if [ -n "${BUGLY_JAR}" ]; then
    printf 'BUGLY_SYMBOL_JAR=%s\n' "$(quote_sh "$(abs_path "${BUGLY_JAR}")")"
  else
    printf ': "${BUGLY_SYMBOL_JAR:?Set BUGLY_SYMBOL_JAR to buglyqq-upload-symbol.jar}"\n'
  fi
  printf 'export USE_LLVM=true\n\n'
  printf 'java -jar "${BUGLY_SYMBOL_JAR}" \\\n'
  printf '  -appid "${BUGLY_APP_ID}" \\\n'
  printf '  -appkey "${BUGLY_APP_KEY}" \\\n'
  printf '  -version %s \\\n' "$(quote_sh "${VERSION}")"
  printf '  -buildNo %s \\\n' "$(quote_sh "${BUILD_NO}")"
  printf '  -platform Harmony \\\n'
  printf '  -inputSymbol %s' "$(quote_sh "${STAGE_DIR_ABS}")"
  if [ -n "${MAPPING_ARG}" ]; then
    printf ' \\\n  %s' "${MAPPING_ARG# }"
  fi
  printf '\n'
} >"${COMMAND_FILE}"
chmod +x "${COMMAND_FILE}"

ARCHIVE_PATH=""
if [ "${CREATE_ARCHIVE}" -eq 1 ]; then
  if command -v zip >/dev/null 2>&1; then
    ARCHIVE_PATH="${OUTPUT_DIR}/slock-ohos-bugly-symbols-${VERSION}-${BUILD_NO}-${GIT_SHORT}-${ARCH}.zip"
    rm -f "${ARCHIVE_PATH}"
    (
      cd "${OUTPUT_DIR}"
      zip -qr "$(basename "${ARCHIVE_PATH}")" "obj/${ARCH}" "$(basename "${MANIFEST}")" "$(basename "${COMMAND_FILE}")"
    )
  else
    echo "warning: zip not found; skipping archive" >&2
  fi
fi

cat <<EOF
Prepared OHOS Bugly symbols.
Version: ${VERSION}
Build no: ${BUILD_NO}
Git: ${GIT_SHORT}
Symbol input directory: ${STAGE_DIR_ABS}
Manifest: ${MANIFEST}
Upload command template: ${COMMAND_FILE}
EOF

if [ -n "${ARCHIVE_PATH}" ]; then
  cat <<EOF
Archive: ${ARCHIVE_PATH}
Archive sha256: $(sha256_file "${ARCHIVE_PATH}")
EOF
fi

cat <<EOF

Bugly upload notes:
  export USE_LLVM=true
  platform must be Harmony
  pass -inputSymbol ${STAGE_DIR_ABS}
  set BUGLY_APP_ID / BUGLY_APP_KEY / BUGLY_SYMBOL_JAR before running:
    ${COMMAND_FILE}
EOF

if [ "${MISMATCHES}" -gt 0 ]; then
  echo "error: ${MISMATCHES} expected UUID check(s) failed; see ${MANIFEST}" >&2
  exit 5
fi
