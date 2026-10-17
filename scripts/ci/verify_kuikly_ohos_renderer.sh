#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RELEASE="${KUIKLY_RELEASE_VERSION:-2.28.0-raft.13}"
VERSION="${RELEASE}-2.0.21-ohos"
COORDINATE="com.tencent.kuikly-open:core-render-ohos:${VERSION}"
RENDERER="${REPO_ROOT}/compose/build/kuikly/core-render-ohos-${VERSION}.har"
SCRATCH="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kuikly-ohos-renderer.XXXXXX")"
trap 'rm -rf "${SCRATCH}"' EXIT

if command -v node >/dev/null 2>&1; then
  NODE_BIN="$(command -v node)"
elif [ -x "${DEVECO_SDK_HOME:-}/../tool/node/bin/node" ]; then
  NODE_BIN="${DEVECO_SDK_HOME}/../tool/node/bin/node"
else
  echo "Node.js is required; neither PATH nor the DevEco command-line-tools bundle provides it" >&2
  exit 1
fi

python3 "${SCRIPT_DIR}/fetch_kuikly_release_artifact.py" \
  --release "${RELEASE}" \
  --coordinate "${COORDINATE}" \
  --kind har \
  --output "${RENDERER}"
python3 "${SCRIPT_DIR}/fetch_kuikly_release_artifact.py" \
  --release "${RELEASE}" \
  --coordinate "${COORDINATE}" \
  --kind provenance-json \
  --output "${SCRATCH}/provenance.json"

KUIKLY_OHOS_HAR="${RENDERER}" \
KUIKLY_OHOS_PROVENANCE="${SCRATCH}/provenance.json" \
  "${NODE_BIN}" "${REPO_ROOT}/compose/ohosApp/scripts/test_image_decode_policy_source_contract.mjs"
