#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$ROOT_DIR/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env.local"
  set +a
fi

export SLOCK_LIVE_TESTS="${SLOCK_LIVE_TESTS:-true}"

if [[ -z "${SLOCK_TEST_EMAIL:-}" || -z "${SLOCK_TEST_PASSWORD:-}" ]]; then
  echo "Missing SLOCK_TEST_EMAIL or SLOCK_TEST_PASSWORD." >&2
  echo "Set them in your shell or in .env.local, then rerun this script." >&2
  exit 1
fi

"$ROOT_DIR/compose/gradlew" \
  --project-dir "$ROOT_DIR/compose" \
  :androidApp:testDebugUnitTest \
  --tests build.raft.app.core.data.network.LiveRaftDbFlowTest
