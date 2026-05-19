#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_REPO="${SOURCE_REPO:-https://github.com/Tencent-TDS/KuiklyUI-AI.git}"
SOURCE_REF="${SOURCE_REF:-main}"
WORK_DIR="${WORK_DIR:-${ROOT_DIR}/.agents/.tmp/KuiklyUI-AI}"

mkdir -p "$(dirname "${WORK_DIR}")"

if [[ -d "${WORK_DIR}/.git" ]]; then
  git -C "${WORK_DIR}" fetch --prune origin
else
  rm -rf "${WORK_DIR}"
  git clone "${SOURCE_REPO}" "${WORK_DIR}"
fi

git -C "${WORK_DIR}" checkout --detach "${SOURCE_REF}"

rm -rf "${ROOT_DIR}/.agents/skills"/kuikly-*
mkdir -p "${ROOT_DIR}/.agents/skills" "${ROOT_DIR}/.agents/rules"
cp -R "${WORK_DIR}/skills"/kuikly-* "${ROOT_DIR}/.agents/skills/"
cp "${WORK_DIR}/rules"/kuikly*.mdc "${ROOT_DIR}/.agents/rules/"

{
  echo "source=${SOURCE_REPO}"
  echo "ref=${SOURCE_REF}"
  echo "commit=$(git -C "${WORK_DIR}" rev-parse HEAD)"
  echo "synced_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "${ROOT_DIR}/.agents/kuikly-ui-ai-source.txt"

echo "Synced KuiklyUI-AI skills/rules to ${ROOT_DIR}/.agents"
