#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
KUIKLY_DIR="${KUIKLY_DIR:-${REPO_ROOT}/third_party/kuikly-ui}"
KUIKLY_OHOS_APP_DIR="${KUIKLY_DIR}/ohosApp"
SOURCE_HAR="${KUIKLY_DIR}/core-render-ohos/build/default/outputs/default/render.har"
TARGET_HAR="${TARGET_HAR:-${REPO_ROOT}/compose/ohosApp/entry/libs/kuikly-render-staging2-3509beef.har}"
DEVECO_STUDIO_HOME="${DEVECO_STUDIO_HOME:-/Applications/DevEco-Studio.app/Contents}"

if [[ ! -d "${KUIKLY_OHOS_APP_DIR}" ]]; then
  echo "Missing Kuikly OHOS project: ${KUIKLY_OHOS_APP_DIR}" >&2
  exit 1
fi

if [[ ! -d "${DEVECO_STUDIO_HOME}" ]]; then
  echo "Missing DevEco Studio home: ${DEVECO_STUDIO_HOME}" >&2
  exit 1
fi

export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-${DEVECO_STUDIO_HOME}/sdk}"
export PATH="${DEVECO_SDK_HOME}:${DEVECO_STUDIO_HOME}/jbr/Contents/Home/bin:${DEVECO_STUDIO_HOME}/tools/node/bin:${DEVECO_STUDIO_HOME}/tools/ohpm/bin:${DEVECO_STUDIO_HOME}/tools/hvigor/bin:${DEVECO_SDK_HOME}/default/openharmony/toolchains:${PATH}"

NODE_BIN="${DEVECO_STUDIO_HOME}/tools/node/bin/node"
HVIGOR_JS="${DEVECO_STUDIO_HOME}/tools/hvigor/bin/hvigorw.js"

if [[ ! -x "${NODE_BIN}" || ! -f "${HVIGOR_JS}" ]]; then
  echo "DevEco node or hvigorw.js is missing under ${DEVECO_STUDIO_HOME}" >&2
  exit 1
fi

echo "==> Installing Kuikly OHOS dependencies..."
(cd "${KUIKLY_OHOS_APP_DIR}" && ohpm install --all --strict_ssl true)

echo "==> Building Kuikly OHOS render HAR..."
(cd "${KUIKLY_OHOS_APP_DIR}" && "${NODE_BIN}" "${HVIGOR_JS}" \
  --mode module \
  -p module=render@default \
  -p product=default \
  -p buildMode=release \
  assembleHar \
  --analyze=normal \
  --parallel)

if [[ ! -f "${SOURCE_HAR}" ]]; then
  echo "Expected HAR was not produced: ${SOURCE_HAR}" >&2
  exit 1
fi

mkdir -p "$(dirname "${TARGET_HAR}")"
cp "${SOURCE_HAR}" "${TARGET_HAR}"

echo "==> Updated ${TARGET_HAR}"
shasum -a 256 "${TARGET_HAR}"
