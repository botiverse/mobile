#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_GRADLE="$ROOT_DIR/compose/gradlew"
ROOT_GRADLE="$ROOT_DIR/gradlew"
RUN_OHOS=0

usage() {
  cat <<'USAGE'
Usage: scripts/validate_kmp_shared_release.sh [options]

Validates and publishes compose/shared before Android app packaging.

Default gate:
  1. Generate the commonMain SQLDelight interface.
  2. Run focused Thread SQLDelight/write-through/store tests.
  3. Publish shared Android release artifact to MavenLocal.
  4. Compile the Android app against the freshly published shared artifact.

Options:
  --ohos      Also compile shared OHOS Kotlin with settings.ohos.gradle.kts.
  -h, --help  Show this help.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ohos)
      RUN_OHOS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

run_in() {
  local cwd="$1"
  shift
  echo
  echo "cwd: $cwd"
  printf '+'
  printf ' %q' "$@"
  echo
  (cd "$cwd" && "$@")
}

run_in "$ROOT_DIR/compose" "$COMPOSE_GRADLE" \
  --no-daemon \
  --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process \
  :shared:generateCommonMainSlockDatabaseInterface \
  :shared:testDebugUnitTest \
  --tests ai.slock.compose.data.SqlDelightComposeLocalDataStoreTest \
  --tests ai.slock.compose.data.ComposeLocalWriteThroughTest \
  --tests ai.slock.compose.SharedThreadStoreTest

run_in "$ROOT_DIR/compose" "$COMPOSE_GRADLE" \
  --no-daemon \
  --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process \
  :shared:publishAndroidReleasePublicationToMavenLocal

run_in "$ROOT_DIR" "$ROOT_GRADLE" \
  --no-daemon \
  --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process \
  :app:compileDebugKotlin

if [ "$RUN_OHOS" -eq 1 ]; then
  SQDELIGHT_PLUGIN_DIR="$HOME/.m2/repository/app/cash/sqldelight/app.cash.sqldelight.gradle.plugin/2.1.0-KBA-001"
  if [ ! -d "$SQDELIGHT_PLUGIN_DIR" ]; then
    echo "error: missing SQLDelight OHOS Gradle plugin in MavenLocal: $SQDELIGHT_PLUGIN_DIR" >&2
    echo "hint: publish the patched SQLDelight fork artifact app.cash.sqldelight:app.cash.sqldelight.gradle.plugin:2.1.0-KBA-001, then rerun with --ohos." >&2
    exit 2
  fi
  run_in "$ROOT_DIR/compose" "$COMPOSE_GRADLE" \
    --no-daemon \
    --console=plain \
    -c settings.ohos.gradle.kts \
    -Dkotlin.compiler.execution.strategy=in-process \
    :shared:compileKotlinOhosArm64
fi

echo
echo "KMP shared release validation complete."
