#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SOURCE_URL="${SLOCK_KTOR_OHOS_SOURCE_URL:-https://github.com/blackbbc/ktor.git}"
SOURCE_REF="${SLOCK_KTOR_OHOS_SOURCE_REF:-3.0.3-ohos}"
BRANCH_NAME="${SLOCK_KTOR_OHOS_BRANCH:-slock-3.0.3-ohos}"
VERSION_NAME="${SLOCK_KTOR_OHOS_VERSION:-3.0.3-slock-ohos-001}"
WORKDIR="${SLOCK_KTOR_OHOS_WORKDIR:-${REPO_ROOT}/../Slock-third-party/ktor-slock-ohos}"

PUBLISH=false
if [[ "${1:-}" == "--publish" ]]; then
  PUBLISH=true
fi

mkdir -p "$(dirname "${WORKDIR}")"

if [[ ! -d "${WORKDIR}/.git" ]]; then
  git clone "${SOURCE_URL}" "${WORKDIR}"
fi

cd "${WORKDIR}"
git fetch origin "${SOURCE_REF}" --tags
git checkout --force FETCH_HEAD
git clean -fdx
git checkout -B "${BRANCH_NAME}"

python3 - <<PY
from pathlib import Path

version = "${VERSION_NAME}"
path = Path("gradle.properties")
lines = path.read_text().splitlines()
updated = []
for line in lines:
    if line.startswith("version="):
        updated.append(f"version={version}")
    else:
        updated.append(line)
path.write_text("\\n".join(updated) + "\\n")
PY

echo "Prepared Ktor OHOS fork at ${WORKDIR}"
echo "Source: ${SOURCE_URL} ${SOURCE_REF}"
echo "Branch: ${BRANCH_NAME}"
echo "Version: ${VERSION_NAME}"
echo "Dry run complete. Pass --publish to publish required artifacts to mavenLocal."

if [[ "${PUBLISH}" != "true" ]]; then
  exit 0
fi

./gradlew \
  :ktor-utils:publishAllPublicationsToMavenLocal \
  :ktor-io:publishAllPublicationsToMavenLocal \
  :ktor-http:publishAllPublicationsToMavenLocal \
  :ktor-http:ktor-http-cio:publishAllPublicationsToMavenLocal \
  :ktor-client:ktor-client-core:publishAllPublicationsToMavenLocal \
  :ktor-client:ktor-client-ohos:publishAllPublicationsToMavenLocal \
  :ktor-client:ktor-client-mock:publishAllPublicationsToMavenLocal \
  :ktor-client:ktor-client-plugins:ktor-client-content-negotiation:publishAllPublicationsToMavenLocal \
  :ktor-shared:ktor-serialization:publishAllPublicationsToMavenLocal \
  :ktor-shared:ktor-serialization:ktor-serialization-kotlinx:publishAllPublicationsToMavenLocal \
  :ktor-shared:ktor-serialization:ktor-serialization-kotlinx:ktor-serialization-kotlinx-json:publishAllPublicationsToMavenLocal
