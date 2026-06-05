#!/usr/bin/env bash
set -euo pipefail

# Build and publish our KuiklyUI fork to mavenLocal.
# Run before building the Slock Android app to consume forked Kuikly fixes.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KUIKLY_DIR="$REPO_ROOT/third_party/kuikly-ui"

cd "$KUIKLY_DIR"

ARGS=(
  -c settings.2.1.21.gradle.kts
  :core:publishToMavenLocal
  :core-annotations:publishToMavenLocal
  :core-ksp:publishToMavenLocal
  :core-render-android:publishToMavenLocal
  :compose:publishToMavenLocal
)

echo "==> Publishing KuiklyUI fork (staging) to mavenLocal..."
./gradlew "${ARGS[@]}"

echo "==> Done. Slock can now consume the forked Kuikly artifacts."
