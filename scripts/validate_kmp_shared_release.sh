#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_GRADLE="$ROOT_DIR/compose/gradlew"
RUN_OHOS=0

usage() {
  cat <<'USAGE'
Usage: scripts/validate_kmp_shared_release.sh [options]

Validates compose/shared and the merged compose/androidApp host.

Default gate:
  1. Generate the commonMain SQLDelight interface.
  2. Run focused Thread SQLDelight/write-through/store tests.
  3. Compile the Android app against :shared as a direct project dependency.

Options:
  --ohos      Also compile shared OHOS Kotlin with settings.ohos.gradle.kts.
              The OHOS settings file prepares required SQLDelight submodule
              artifacts automatically when MavenLocal is missing them.
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
  :androidApp:compileDebugKotlin

if [ "$RUN_OHOS" -eq 1 ]; then
  run_in "$ROOT_DIR/compose" "$COMPOSE_GRADLE" \
    --no-daemon \
    --console=plain \
    -c settings.ohos.gradle.kts \
    -Dkotlin.compiler.execution.strategy=in-process \
    :shared:compileKotlinOhosArm64
fi

echo
echo "KMP shared release validation complete."
