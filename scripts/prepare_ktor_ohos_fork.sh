#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SOURCE_URL="${SLOCK_KTOR_OHOS_SOURCE_URL:-https://github.com/ktorio/ktor.git}"
SOURCE_REF="${SLOCK_KTOR_OHOS_SOURCE_REF:-2.3.7}"
BRANCH_NAME="${SLOCK_KTOR_OHOS_BRANCH:-slock-2.3.7-ohos-kba}"
VERSION_NAME="${SLOCK_KTOR_OHOS_VERSION:-2.3.7-slock-ohos-001}"
WORKDIR="${SLOCK_KTOR_OHOS_WORKDIR:-${REPO_ROOT}/third_party/.workdirs/ktor-slock-ohos}"
PATCH_DIR="${SLOCK_KTOR_OHOS_PATCH_DIR:-${REPO_ROOT}/third_party/ktor-ohos/patches}"
GRADLE_RUNNER="${SLOCK_KTOR_OHOS_GRADLE:-${REPO_ROOT}/compose/gradlew}"

PUBLISH=false
for arg in "$@"; do
  case "${arg}" in
    --publish)
      PUBLISH=true
      ;;
    --help|-h)
      cat <<EOF
Usage: scripts/prepare_ktor_ohos_fork.sh [--publish]

Prepares Slock's local Ktor OHOS fork checkout and optionally publishes the
minimal Ktor KMP/OHOS artifacts to mavenLocal.

Environment overrides:
  SLOCK_KTOR_OHOS_SOURCE_URL    source repository
  SLOCK_KTOR_OHOS_SOURCE_REF    branch/tag/commit to fork from
  SLOCK_KTOR_OHOS_BRANCH        local branch name in the workdir
  SLOCK_KTOR_OHOS_VERSION       local publication version
  SLOCK_KTOR_OHOS_WORKDIR       checkout directory
  SLOCK_KTOR_OHOS_PATCH_DIR     directory containing *.patch files
  SLOCK_KTOR_OHOS_GRADLE        Gradle runner used for publish
  SLOCK_KTOR_OHOS_MODULES       whitespace-separated Gradle project paths
EOF
      exit 0
      ;;
    *)
      echo "Unknown argument: ${arg}" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$(dirname "${WORKDIR}")"

if [[ ! -d "${WORKDIR}/.git" ]]; then
  git clone "${SOURCE_URL}" "${WORKDIR}"
fi

cd "${WORKDIR}"
git remote set-url origin "${SOURCE_URL}"
git fetch origin "${SOURCE_REF}" --tags
git checkout --force FETCH_HEAD
git clean -fdx
git checkout -B "${BRANCH_NAME}"

if [[ -d "${PATCH_DIR}" ]]; then
  while IFS= read -r patch; do
    [[ -n "${patch}" ]] || continue
    echo "Applying patch: ${patch}"
    git apply --unidiff-zero "${patch}"
  done < <(find "${PATCH_DIR}" -maxdepth 1 -type f -name '*.patch' | sort)
fi

python3 - <<PY
from pathlib import Path

version = "${VERSION_NAME}"

properties = Path("gradle.properties")
lines = properties.read_text().splitlines()
updated = []
found = False
for line in lines:
    if line.startswith("version="):
        updated.append(f"version={version}")
        found = True
    else:
        updated.append(line)
if not found:
    updated.append(f"version={version}")
properties.write_text("\\n".join(updated) + "\\n")

version_file = Path("VERSION")
if version_file.exists():
    version_file.write_text(version + "\\n")
PY

echo "Prepared Ktor OHOS fork at ${WORKDIR}"
echo "Source: ${SOURCE_URL} ${SOURCE_REF}"
echo "Branch: ${BRANCH_NAME}"
echo "Version: ${VERSION_NAME}"
echo "Patch dir: ${PATCH_DIR}"
echo "Dry run complete. Pass --publish to publish required artifacts to mavenLocal."

if [[ "${PUBLISH}" != "true" ]]; then
  exit 0
fi

read -r -a modules <<< "${SLOCK_KTOR_OHOS_MODULES:-:ktor-io :ktor-utils :ktor-http :ktor-http:ktor-http-cio :ktor-network :ktor-network:ktor-network-tls :ktor-shared:ktor-events :ktor-shared:ktor-websockets :ktor-shared:ktor-websocket-serialization :ktor-shared:ktor-serialization :ktor-shared:ktor-serialization:ktor-serialization-kotlinx :ktor-shared:ktor-serialization:ktor-serialization-kotlinx:ktor-serialization-kotlinx-json :ktor-client:ktor-client-core :ktor-client:ktor-client-plugins:ktor-client-content-negotiation :ktor-client:ktor-client-cio}"
tasks=()
for module in "${modules[@]}"; do
  tasks+=("${module}:publishKotlinMultiplatformPublicationToMavenLocal")
  tasks+=("${module}:publishOhosArm64PublicationToMavenLocal")
done

"${GRADLE_RUNNER}" -p "${WORKDIR}" --no-daemon --no-configuration-cache --console=plain --stacktrace -PreleaseVersion="${VERSION_NAME}" "${tasks[@]}"
