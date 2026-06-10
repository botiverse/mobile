#!/usr/bin/env bash
set -euo pipefail

# Build and publish our KuiklyUI fork to mavenLocal.
# Run before building Slock to consume forked Kuikly fixes.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KUIKLY_DIR="$REPO_ROOT/third_party/kuikly-ui"

# This intentionally publishes the Slock fork under the selected Kuikly release
# line so mavenLocal overrides the public artifacts with our staging fixes.
export KUIKLY_VERSION="${KUIKLY_VERSION:-2.21.0}"

cd "$KUIKLY_DIR"

ARGS=(
  -c settings.2.1.21.gradle.kts
  --no-daemon
  --console=plain
  :core:publishToMavenLocal
  :core-annotations:publishToMavenLocal
  :core-ksp:publishToMavenLocal
  :core-render-android:publishToMavenLocal
  :compose:publishToMavenLocal
)

OHOS_ARGS=(
  -c settings.2.0.ohos.gradle.kts
  --no-daemon
  --console=plain
  :core:publishKotlinMultiplatformPublicationToMavenLocal
  :core:publishOhosArm64PublicationToMavenLocal
  :core-annotations:publishKotlinMultiplatformPublicationToMavenLocal
  :core-annotations:publishOhosArm64PublicationToMavenLocal
  :core-ksp:publishMavenPublicationToMavenLocal
  :core-render-android:publishMavenPublicationToMavenLocal
  :compose:publishKotlinMultiplatformPublicationToMavenLocal
  :compose:publishOhosArm64PublicationToMavenLocal
)

echo "==> Publishing KuiklyUI fork Android artifacts (staging, version $KUIKLY_VERSION) to mavenLocal..."
KUIKLY_KOTLIN_VERSION=2.1.21 ./gradlew "${ARGS[@]}"

echo "==> Publishing KuiklyUI fork OHOS artifacts (staging, version $KUIKLY_VERSION) to mavenLocal..."
KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010 ./gradlew "${OHOS_ARGS[@]}"

echo "==> Done. Slock can now consume the forked Kuikly Android and OHOS artifacts."
