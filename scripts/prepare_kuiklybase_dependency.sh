#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

COMPONENT="${1:-}"
PUBLISH=false
if [[ "${2:-}" == "--publish" ]]; then
  PUBLISH=true
fi

if [[ -z "${COMPONENT}" || "${COMPONENT}" == "--help" || "${COMPONENT}" == "-h" ]]; then
  cat <<'EOF'
Usage:
  scripts/prepare_kuiklybase_dependency.sh <component> [--publish]

Components:
  serialization  org.jetbrains.kotlinx:kotlinx-serialization-*:1.7.1-KBA-003
  coroutines     org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-001
  atomicfu       org.jetbrains.kotlinx:atomicfu:0.23.2-KBA-001
  okio           com.squareup.okio:okio:3.9.10-KBA-001
  datetime       RETIRED: use build.raft.kuiklybase:datetime:0.1.0-raft.0(-ohos)

The script clones Tencent-TDS/KuiklyBase-platform, clones the matching
upstream dependency, checks out the documented baseline, applies the KuiklyBase
OHOS patch, and leaves the fork ready for local inspection. Pass --publish to
run publishToMavenLocal in the prepared dependency checkout.
EOF
  exit 0
fi

KUIKLYBASE_URL="${SLOCK_KUIKLYBASE_PLATFORM_URL:-https://github.com/Tencent-TDS/KuiklyBase-platform.git}"
THIRD_PARTY_ROOT="${SLOCK_THIRD_PARTY_WORKDIR:-${REPO_ROOT}/third_party/.workdirs}"
KUIKLYBASE_WORKDIR="${SLOCK_KUIKLYBASE_PLATFORM_WORKDIR:-${THIRD_PARTY_ROOT}/KuiklyBase-platform}"

case "${COMPONENT}" in
  serialization)
    UPSTREAM_URL="https://github.com/Kotlin/kotlinx.serialization.git"
    UPSTREAM_REF="d2f7316e"
    PATCH_PATH="kotlinx.serialization/serialization.patch"
    BRANCH_NAME="${SLOCK_KUIKLYBASE_DEP_BRANCH:-slock-serialization-1.7.1-ohos}"
    WORKDIR_DEFAULT="${THIRD_PARTY_ROOT}/kotlinx-serialization-ohos"
    EXPECTED_VERSION="1.7.1-KBA-003"
    ;;
  coroutines)
    UPSTREAM_URL="https://github.com/Kotlin/kotlinx.coroutines.git"
    UPSTREAM_REF="1.8.0"
    PATCH_PATH="kotlinx.coroutines/kotlinx-coroutines.patch"
    BRANCH_NAME="${SLOCK_KUIKLYBASE_DEP_BRANCH:-slock-coroutines-1.8.0-ohos}"
    WORKDIR_DEFAULT="${THIRD_PARTY_ROOT}/kotlinx-coroutines-ohos"
    EXPECTED_VERSION="1.8.0-KBA-001"
    ;;
  atomicfu)
    UPSTREAM_URL="https://github.com/Kotlin/kotlinx.atomicfu.git"
    UPSTREAM_REF="0.23.2"
    PATCH_PATH="kotlinx.atomicfu/kotlinx.atomicfu.patch"
    BRANCH_NAME="${SLOCK_KUIKLYBASE_DEP_BRANCH:-slock-atomicfu-0.23.2-ohos}"
    WORKDIR_DEFAULT="${THIRD_PARTY_ROOT}/kotlinx-atomicfu-ohos"
    EXPECTED_VERSION="0.23.2-KBA-001"
    ;;
  okio)
    UPSTREAM_URL="https://github.com/square/okio.git"
    UPSTREAM_REF="c43c9a61"
    PATCH_PATH="okio/okio.patch"
    BRANCH_NAME="${SLOCK_KUIKLYBASE_DEP_BRANCH:-slock-okio-3.9.10-ohos}"
    WORKDIR_DEFAULT="${THIRD_PARTY_ROOT}/okio-ohos"
    EXPECTED_VERSION="3.9.10-KBA-001"
    ;;
  datetime)
    echo "The legacy kotlinx-datetime OHOS preparation is retired." >&2
    echo "Use build.raft.kuiklybase:datetime:0.1.0-raft.0 (normal) or 0.1.0-raft.0-ohos." >&2
    exit 4
    ;;
  *)
    echo "Unknown component: ${COMPONENT}" >&2
    exit 2
    ;;
esac

DEPENDENCY_WORKDIR="${SLOCK_KUIKLYBASE_DEPENDENCY_WORKDIR:-${WORKDIR_DEFAULT}}"

mkdir -p "${THIRD_PARTY_ROOT}"

if [[ ! -d "${KUIKLYBASE_WORKDIR}/.git" ]]; then
  git clone "${KUIKLYBASE_URL}" "${KUIKLYBASE_WORKDIR}"
fi

cd "${KUIKLYBASE_WORKDIR}"
git fetch origin main --tags
git checkout --force origin/main
git clean -fdx

if [[ ! -f "${PATCH_PATH}" ]]; then
  echo "Missing patch in KuiklyBase-platform: ${PATCH_PATH}" >&2
  exit 3
fi

if [[ ! -d "${DEPENDENCY_WORKDIR}/.git" ]]; then
  git clone "${UPSTREAM_URL}" "${DEPENDENCY_WORKDIR}"
fi

cd "${DEPENDENCY_WORKDIR}"
git fetch origin --tags
git checkout --force "${UPSTREAM_REF}"
git clean -fdx
git checkout -B "${BRANCH_NAME}"
git apply "${KUIKLYBASE_WORKDIR}/${PATCH_PATH}"

cat <<EOF
Prepared KuiklyBase ${COMPONENT} dependency fork.
Workdir: ${DEPENDENCY_WORKDIR}
Upstream: ${UPSTREAM_URL} ${UPSTREAM_REF}
Patch: ${KUIKLYBASE_WORKDIR}/${PATCH_PATH}
Branch: ${BRANCH_NAME}
Expected KBA version: ${EXPECTED_VERSION}
Dry run complete. Pass --publish to run publishToMavenLocal.
EOF

if [[ "${PUBLISH}" != "true" ]]; then
  exit 0
fi

./gradlew publishToMavenLocal
