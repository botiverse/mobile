#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

UPSTREAM_URL="${SLOCK_STATELY_UPSTREAM_URL:-https://github.com/touchlab/Stately.git}"
BASE_TAG="${SLOCK_STATELY_BASE_TAG:-2.1.0}"
BRANCH_NAME="${SLOCK_STATELY_BRANCH:-slock-2.1.0-ohos}"
VERSION_NAME="${SLOCK_STATELY_VERSION:-2.1.0-KBA-001}"
WORKDIR="${SLOCK_STATELY_WORKDIR:-${REPO_ROOT}/../Slock-third-party/stately-ohos}"
PATCH_FILE="${REPO_ROOT}/third_party/stately-ohos/patches/0001-ohos-arm64-support.patch"

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
text = text.replace("VERSION_NAME=2.1.0", "VERSION_NAME=${VERSION_NAME}")
text = text.replace("RELEASE_SIGNING_ENABLED=true", "RELEASE_SIGNING_ENABLED=false")
path.write_text(text)
PY

echo "Prepared Stately OHOS fork at ${WORKDIR}"
echo "Branch: ${BRANCH_NAME}"
echo "Version: ${VERSION_NAME}"

if [[ "${PUBLISH}" != "true" ]]; then
  echo "Dry run complete. Pass --publish to publish Stately to mavenLocal."
  exit 0
fi

./gradlew \
  :stately-strict:publishKotlinMultiplatformPublicationToMavenLocal \
  :stately-strict:publishOhosArm64PublicationToMavenLocal \
  :stately-concurrency:publishKotlinMultiplatformPublicationToMavenLocal \
  :stately-concurrency:publishOhosArm64PublicationToMavenLocal
