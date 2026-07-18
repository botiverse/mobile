#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_ROOT="$(cd "${SCRIPT_DIR}/../../main/cpp" && pwd)"
HEADER="${CPP_ROOT}/include/networkkmm/curl_wrapper.h"
ADAPTER="${CPP_ROOT}/SlockOhosImageAdapter.cpp"
NAPI_INIT="${CPP_ROOT}/napi_init.cpp"
NAPI_TYPES="${CPP_ROOT}/types/libentry/index.d.ts"
ENTRY_ABILITY="$(cd "${CPP_ROOT}/../ets/entryability" && pwd)/EntryAbility.ets"
EXPECTED_HEADER_SHA256="2fedc16787beaa010073998c133f90c843d4e3b2f008229b9e2d8798cd83e487"

actual_header_sha256="$(shasum -a 256 "${HEADER}" | awk '{print $1}')"
if [[ "${actual_header_sha256}" != "${EXPECTED_HEADER_SHA256}" ]]; then
  echo "NetworkKMM curl_wrapper.h SHA mismatch: expected ${EXPECTED_HEADER_SHA256}, got ${actual_header_sha256}" >&2
  exit 1
fi

if grep -En '\bStartRequest[[:space:]]*\(' "${HEADER}" "${ADAPTER}"; then
  echo "Legacy StartRequest entrypoint must not be declared or referenced" >&2
  exit 1
fi

grep -Eq '\bStartRequestV27[[:space:]]*\(' "${ADAPTER}" || {
  echo "SlockOhosImageAdapter must call StartRequestV27" >&2
  exit 1
}

for symbol in SetCurlHttp3Enabled GetCurlNegotiatedProtocol; do
  grep -Eq "\\b${symbol}[[:space:]]*\\(" "${ADAPTER}" || {
    echo "SlockOhosImageAdapter must use ${symbol} for app-global HTTP/3 policy diagnostics" >&2
    exit 1
  }
done

for file in "${NAPI_INIT}" "${NAPI_TYPES}" "${ENTRY_ABILITY}"; do
  grep -q 'setImageHttp3Enabled' "${file}" || {
    echo "Missing OHOS image HTTP/3 policy bridge in ${file}" >&2
    exit 1
  }
done

clang++ -std=c++17 -Wall -Wextra -Werror -fsyntax-only -x c++ -I"${CPP_ROOT}" - <<'CPP'
#include "include/networkkmm/curl_wrapper.h"

static_assert(CURL_WRAPPER_ABI_VERSION == 27);
static_assert(sizeof(CurlRequest) == 80);
CPP

echo "NetworkKMM OHOS curl ABI-v27 source guard passed (${actual_header_sha256})"
