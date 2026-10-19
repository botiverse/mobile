#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RELEASE="${KUIKLY_RELEASE_VERSION:-2.28.0-raft.16-diag1}"
REGISTRY="${RAFT_ARTIFACTS_MAVEN_URL:-https://maven.artifacts.botiverse.dev}"
CLOSURE="${KUIKLY_RELEASE_CLOSURE:-${REPO_ROOT}/compose/gradle/kuikly-release-closure.json}"

args=(
  --release "${RELEASE}"
  --registry-base "${REGISTRY}"
  --closure "${CLOSURE}"
)
if [ "${KUIKLY_FULL_READBACK:-0}" != "1" ]; then
  args+=(--admission-only)
fi

python3 "${SCRIPT_DIR}/kuikly_release_manifest_gate.py" "${args[@]}"
