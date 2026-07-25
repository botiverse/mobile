#!/usr/bin/env bash
set -euo pipefail

# Build and publish our KuiklyUI fork to mavenLocal.
# Run before building Slock to consume forked Kuikly fixes.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KUIKLY_DIR="$REPO_ROOT/third_party/kuikly-ui"
MODE="all"

usage() {
  cat <<'USAGE'
Usage: scripts/build_kuikly_fork.sh [--all|--android-only|--ohos-only]

Build and publish the checked-out KuiklyUI fork into mavenLocal so Slock
consumes forked artifacts instead of public/stale release artifacts.

  --all           publish Android/foundation and OHOS artifacts (default)
  --android-only  publish Android/foundation artifacts used by Android/iOS shared builds
  --ohos-only     publish OHOS KLIB artifacts used by OHOS shared builds
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)
      MODE="all"
      ;;
    --android-only)
      MODE="android"
      ;;
    --ohos-only)
      MODE="ohos"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
  shift
done

# This intentionally publishes the Slock fork under the selected Kuikly release
# line so mavenLocal overrides the public artifacts with our staging fixes.
# Keep KUIKLY_KOTLIN_VERSION explicit below: bare publishToMavenLocal from the
# Kuikly checkout can otherwise publish a snapshot coordinate that Slock does
# not consume.
export KUIKLY_VERSION="${KUIKLY_VERSION:-2.21.0}"

cd "$KUIKLY_DIR"

ARGS=(
  -c settings.2.1.21.gradle.kts
  --no-daemon
  --console=plain
  --stacktrace
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
  --stacktrace
  :core:publishKotlinMultiplatformPublicationToMavenLocal
  :core:publishOhosArm64PublicationToMavenLocal
  :core-annotations:publishKotlinMultiplatformPublicationToMavenLocal
  :core-annotations:publishOhosArm64PublicationToMavenLocal
  :core-ksp:publishMavenPublicationToMavenLocal
  :core-render-android:publishMavenPublicationToMavenLocal
  :compose:publishKotlinMultiplatformPublicationToMavenLocal
  :compose:publishOhosArm64PublicationToMavenLocal
)

if [[ "$MODE" == "all" || "$MODE" == "android" ]]; then
  echo "==> Publishing KuiklyUI fork Android artifacts (staging, version $KUIKLY_VERSION) to mavenLocal..."
  KUIKLY_KOTLIN_VERSION=2.1.21 ./gradlew "${ARGS[@]}"
fi

if [[ "$MODE" == "all" || "$MODE" == "ohos" ]]; then
  echo "==> Publishing KuiklyUI fork OHOS artifacts (staging, version $KUIKLY_VERSION) to mavenLocal..."
  KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010 ./gradlew "${OHOS_ARGS[@]}"
fi

echo "==> Done. Slock can now consume the selected forked Kuikly artifacts."
