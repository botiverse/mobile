#!/usr/bin/env bash
set -euo pipefail

# This builds the OHOS SQLite provider from a pinned SQLite amalgamation
# archive instead of using the host system or Homebrew sqlite library.
# The default version matches SQLiter's bundled sqlite3.h.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SQLITE_AMALGAMATION_VERSION="${SLOCK_OHOS_SQLITE_VERSION:-3310100}"
SQLITE_VERSION_LABEL="3.31.1"
SQLITE_ARCHIVE_NAME="sqlite-amalgamation-${SQLITE_AMALGAMATION_VERSION}.zip"
# Keep this pinned to SQLiter's bundled sqlite3.h (SQLITE_VERSION 3.31.1).
SQLITE_ARCHIVE_URL="${SLOCK_OHOS_SQLITE_URL:-https://www.sqlite.org/2020/${SQLITE_ARCHIVE_NAME}}"
SQLITE_ARCHIVE_SHA256="${SLOCK_OHOS_SQLITE_SHA256:-f3c79bc9f4162d0b06fa9fe09ee6ccd23bb99ce310b792c5145f87fbcc30efca}"

THIRD_PARTY_ROOT="${SLOCK_THIRD_PARTY_WORKDIR:-${REPO_ROOT}/third_party/.workdirs}"
WORKDIR="${SLOCK_OHOS_SQLITE_WORKDIR:-${THIRD_PARTY_ROOT}/ohos-sqlite}"
DOWNLOAD_DIR="${WORKDIR}/downloads"
SOURCE_DIR="${SLOCK_OHOS_SQLITE_SOURCE_DIR:-${WORKDIR}/sqlite-amalgamation-${SQLITE_AMALGAMATION_VERSION}}"
BUILD_DIR="${WORKDIR}/build/ohos-arm64"
PREFIX_DIR="${WORKDIR}/ohos-arm64"
LIB_DIR="${SLOCK_OHOS_SQLITE_LIB_DIR:-${PREFIX_DIR}/lib}"
INCLUDE_DIR="${SLOCK_OHOS_SQLITE_INCLUDE_DIR:-${PREFIX_DIR}/include}"

SDK_HOME="${SLOCK_DEVECO_SDK_HOME:-${DEVECO_SDK_HOME:-}}"
if [[ -z "${SDK_HOME}" ]]; then
  SDK_HOME="/Applications/DevEco-Studio.app/Contents/sdk"
fi

NATIVE_ROOT="${SLOCK_OHOS_NATIVE_ROOT:-${SDK_HOME}/default/openharmony/native}"
CLANG="${SLOCK_OHOS_CLANG:-${NATIVE_ROOT}/llvm/bin/clang}"
LLVM_AR="${SLOCK_OHOS_LLVM_AR:-${NATIVE_ROOT}/llvm/bin/llvm-ar}"
SYSROOT="${SLOCK_OHOS_SYSROOT:-${NATIVE_ROOT}/sysroot}"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

download_sqlite() {
  mkdir -p "${DOWNLOAD_DIR}"
  local archive_path="${DOWNLOAD_DIR}/${SQLITE_ARCHIVE_NAME}"
  if [[ ! -f "${archive_path}" ]]; then
    echo "Downloading SQLite ${SQLITE_VERSION_LABEL} amalgamation from ${SQLITE_ARCHIVE_URL}"
    curl --fail --location --show-error --silent "${SQLITE_ARCHIVE_URL}" --output "${archive_path}"
  fi

  local actual_sha
  actual_sha="$(sha256_file "${archive_path}")"
  if [[ "${actual_sha}" != "${SQLITE_ARCHIVE_SHA256}" ]]; then
    echo "SQLite archive checksum mismatch: expected ${SQLITE_ARCHIVE_SHA256}, got ${actual_sha}" >&2
    exit 3
  fi

  local extract_dir="${WORKDIR}/extract"
  rm -rf "${extract_dir}"
  mkdir -p "${extract_dir}"
  unzip -q "${archive_path}" -d "${extract_dir}"
  rm -rf "${SOURCE_DIR}"
  mkdir -p "$(dirname "${SOURCE_DIR}")"
  mv "${extract_dir}/sqlite-amalgamation-${SQLITE_AMALGAMATION_VERSION}" "${SOURCE_DIR}"
  rm -rf "${extract_dir}"
}

if [[ ! -f "${SOURCE_DIR}/sqlite3.c" || ! -f "${SOURCE_DIR}/sqlite3.h" ]]; then
  download_sqlite
fi

for path in "${CLANG}" "${LLVM_AR}"; do
  if [[ ! -x "${path}" ]]; then
    echo "Missing OHOS toolchain executable: ${path}" >&2
    exit 4
  fi
done

if [[ ! -d "${SYSROOT}" ]]; then
  echo "Missing OHOS sysroot: ${SYSROOT}" >&2
  exit 4
fi

mkdir -p "${BUILD_DIR}" "${LIB_DIR}" "${INCLUDE_DIR}"

"${CLANG}" \
  --target=aarch64-linux-ohos \
  --sysroot="${SYSROOT}" \
  -fPIC \
  -O2 \
  -DSQLITE_THREADSAFE=1 \
  -DSQLITE_OMIT_LOAD_EXTENSION=1 \
  -DSQLITE_TEMP_STORE=3 \
  -DSQLITE_DEFAULT_MEMSTATUS=0 \
  -c "${SOURCE_DIR}/sqlite3.c" \
  -o "${BUILD_DIR}/sqlite3.o"

"${LLVM_AR}" rcs "${LIB_DIR}/libsqlite3.a" "${BUILD_DIR}/sqlite3.o"
cp "${SOURCE_DIR}/sqlite3.h" "${INCLUDE_DIR}/sqlite3.h"
if [[ -f "${SOURCE_DIR}/sqlite3ext.h" ]]; then
  cp "${SOURCE_DIR}/sqlite3ext.h" "${INCLUDE_DIR}/sqlite3ext.h"
fi

cat <<EOF
Prepared OHOS SQLite provider.
SQLite: ${SQLITE_VERSION_LABEL} (${SQLITE_AMALGAMATION_VERSION})
Library: ${LIB_DIR}/libsqlite3.a
Headers: ${INCLUDE_DIR}
Toolchain: ${CLANG}
Sysroot: ${SYSROOT}
EOF
