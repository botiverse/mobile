#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

UPSTREAM_URL="${SLOCK_SQLDELIGHT_UPSTREAM_URL:-https://github.com/sqldelight/sqldelight.git}"
BASE_TAG="${SLOCK_SQLDELIGHT_BASE_TAG:-2.1.0}"
BRANCH_NAME="${SLOCK_SQLDELIGHT_BRANCH:-slock-2.1.0-ohos}"
VERSION_NAME="${SLOCK_SQLDELIGHT_VERSION:-2.1.0-slock-ohos-001}"
WORKDIR="${SLOCK_SQLDELIGHT_WORKDIR:-${REPO_ROOT}/../Slock-third-party/sqldelight-slock-ohos}"
PATCH_FILE="${REPO_ROOT}/third_party/sqldelight-ohos/patches/0001-ohos-arm64-support.patch"

PUBLISH=false
if [[ "${1:-}" == "--publish" ]]; then
  PUBLISH=true
fi

mkdir -p "$(dirname "${WORKDIR}")"

if [[ ! -d "${WORKDIR}/.git" ]]; then
  git clone "${UPSTREAM_URL}" "${WORKDIR}"
fi

cd "${WORKDIR}"
git fetch --tags origin
git checkout --force "${BASE_TAG}"
git clean -fdx
git checkout -B "${BRANCH_NAME}"
git apply "${PATCH_FILE}"

python3 - <<PY
from pathlib import Path

path = Path("gradle.properties")
text = path.read_text()
text = text.replace("VERSION_NAME=2.1.0-KBA-001", "VERSION_NAME=${VERSION_NAME}")
path.write_text(text)
PY

echo "Prepared SQLDelight OHOS fork at ${WORKDIR}"
echo "Branch: ${BRANCH_NAME}"
echo "Version: ${VERSION_NAME}"

if [[ "${PUBLISH}" != "true" ]]; then
  echo "Dry run complete. Pass --publish to publish required artifacts to mavenLocal."
  exit 0
fi

./gradlew \
  :runtime:publishAllPublicationsToMavenLocal \
  :sqldelight-compiler:publishAllPublicationsToMavenLocal \
  :sqldelight-compiler:dialect:publishAllPublicationsToMavenLocal \
  :sqldelight-compiler:environment:publishAllPublicationsToMavenLocal \
  :sqlite-migrations:publishAllPublicationsToMavenLocal \
  :sqlite-migrations:environment:publishAllPublicationsToMavenLocal \
  :sqldelight-gradle-plugin:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-18:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-24:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-25:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-30:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-33:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-35:publishAllPublicationsToMavenLocal \
  :dialects:sqlite-3-38:publishAllPublicationsToMavenLocal \
  :drivers:native-driver:publishAllPublicationsToMavenLocal \
  :drivers:android-driver:publishAllPublicationsToMavenLocal \
  :drivers:sqlite-driver:publishAllPublicationsToMavenLocal \
  :extensions:async-extensions:publishAllPublicationsToMavenLocal \
  :extensions:coroutines-extensions:publishAllPublicationsToMavenLocal
