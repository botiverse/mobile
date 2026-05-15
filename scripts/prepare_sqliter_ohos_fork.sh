#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

UPSTREAM_URL="${SLOCK_SQLITER_UPSTREAM_URL:-https://github.com/touchlab/SQLiter.git}"
BASE_TAG="${SLOCK_SQLITER_BASE_TAG:-1.3.3}"
BRANCH_NAME="${SLOCK_SQLITER_BRANCH:-slock-1.3.3-ohos}"
VERSION_NAME="${SLOCK_SQLITER_VERSION:-1.3.3-KBA-001}"
WORKDIR="${SLOCK_SQLITER_WORKDIR:-${REPO_ROOT}/../Slock-third-party/sqliter-ohos}"
PATCH_FILE="${REPO_ROOT}/third_party/sqliter-ohos/patches/0001-ohos-arm64-support.patch"

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
text = text.replace("VERSION_NAME=1.3.3", "VERSION_NAME=${VERSION_NAME}")
text = text.replace("RELEASE_SIGNING_ENABLED=true", "RELEASE_SIGNING_ENABLED=false")
path.write_text(text)
PY

echo "Prepared SQLiter OHOS fork at ${WORKDIR}"
echo "Branch: ${BRANCH_NAME}"
echo "Version: ${VERSION_NAME}"

if [[ "${PUBLISH}" != "true" ]]; then
  echo "Dry run complete. Pass --publish to publish SQLiter to mavenLocal."
  exit 0
fi

./gradlew \
  :sqliter-driver:publishKotlinMultiplatformPublicationToMavenLocal \
  :sqliter-driver:publishOhosArm64PublicationToMavenLocal
