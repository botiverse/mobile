#!/usr/bin/env bash
set -euo pipefail

# Publish Slock's pinned Kuikly fork artifacts to GitHub Packages.
#
# Required:
#   GITHUB_PACKAGES_OWNER      Lowercase GitHub owner/org for maven.pkg.github.com
#   GITHUB_PACKAGES_REPOSITORY GitHub repository that owns the package namespace
#   GITHUB_TOKEN or GPR_KEY    Token with packages:write for the target package
#
# Optional:
#   GITHUB_ACTOR or GPR_USER   Package username. Defaults to git config user.
#   KUIKLY_VERSION             Kuikly base version. Defaults to 2.21.0.
#   KUIKLY_VERSION_SUFFIX      Internal suffix. Defaults to raft.2.
#   KUIKLY_FORK_REF            Expected third_party/kuikly-ui commit/ref.
#   SLOCK_PUBLISH_FAMILY       android, ohos, or all. Defaults to all.
#   SLOCK_PUBLISH_DRY_RUN      Set to 1 to run Gradle with --dry-run.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KUIKLY_DIR="$REPO_ROOT/third_party/kuikly-ui"
KUIKLY_DIR="${KUIKLY_DIR_OVERRIDE:-$KUIKLY_DIR}"

OWNER="${GITHUB_PACKAGES_OWNER:-}"
REPOSITORY="${GITHUB_PACKAGES_REPOSITORY:-}"
USERNAME="${GPR_USER:-${GITHUB_ACTOR:-}}"
TOKEN="${GPR_KEY:-${GITHUB_TOKEN:-}}"
BASE_VERSION="${KUIKLY_VERSION:-2.21.0}"
VERSION_SUFFIX="${KUIKLY_VERSION_SUFFIX:-raft.2}"
FAMILY="${SLOCK_PUBLISH_FAMILY:-all}"
EXPECTED_REF="${KUIKLY_FORK_REF:-}"
DRY_RUN="${SLOCK_PUBLISH_DRY_RUN:-0}"

if [[ -z "$OWNER" || -z "$REPOSITORY" ]]; then
  echo "GITHUB_PACKAGES_OWNER and GITHUB_PACKAGES_REPOSITORY are required." >&2
  exit 2
fi

LOWER_OWNER="$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]')"
if [[ "$OWNER" != "$LOWER_OWNER" ]]; then
  echo "GITHUB_PACKAGES_OWNER must be lowercase for GitHub Packages: $OWNER" >&2
  exit 2
fi

if [[ -z "$TOKEN" ]]; then
  echo "GITHUB_TOKEN or GPR_KEY is required." >&2
  exit 2
fi

if [[ -z "$USERNAME" ]]; then
  USERNAME="$(git config user.name || true)"
fi

if [[ -z "$USERNAME" ]]; then
  echo "GITHUB_ACTOR, GPR_USER, or git config user.name is required." >&2
  exit 2
fi

if [[ "$VERSION_SUFFIX" != raft.* ]]; then
  echo "Refusing to publish without a raft.* suffix: $VERSION_SUFFIX" >&2
  exit 2
fi

if [[ "$FAMILY" != "all" && "$FAMILY" != "android" && "$FAMILY" != "ohos" ]]; then
  echo "SLOCK_PUBLISH_FAMILY must be all, android, or ohos." >&2
  exit 2
fi

if [[ ! -x "$KUIKLY_DIR/gradlew" ]]; then
  echo "Missing Kuikly submodule checkout at $KUIKLY_DIR." >&2
  echo "Run: git submodule update --init third_party/kuikly-ui" >&2
  exit 2
fi

if [[ -n "$EXPECTED_REF" ]]; then
  actual_ref="$(git -C "$KUIKLY_DIR" rev-parse HEAD)"
  expected_ref="$(git -C "$KUIKLY_DIR" rev-parse "$EXPECTED_REF")"
  if [[ "$actual_ref" != "$expected_ref" ]]; then
    echo "Kuikly submodule ref mismatch." >&2
    echo "Expected: $expected_ref ($EXPECTED_REF)" >&2
    echo "Actual:   $actual_ref" >&2
    exit 2
  fi
fi

ANDROID_PUBLICATION_VERSION="${KUIKLY_ANDROID_PUBLICATION_VERSION:-${BASE_VERSION}-2.1.21-${VERSION_SUFFIX}}"
OHOS_PUBLICATION_VERSION="${KUIKLY_OHOS_PUBLICATION_VERSION:-${BASE_VERSION}-2.0.21-ohos-${VERSION_SUFFIX}}"

if [[ "$ANDROID_PUBLICATION_VERSION" != *"$VERSION_SUFFIX"* ]]; then
  echo "Android publication version must include $VERSION_SUFFIX." >&2
  exit 2
fi

if [[ "$OHOS_PUBLICATION_VERSION" != *"$VERSION_SUFFIX"* ]]; then
  echo "OHOS publication version must include $VERSION_SUFFIX." >&2
  exit 2
fi

INIT_SCRIPT="$(mktemp "${TMPDIR:-/tmp}/slock-kuikly-publish.XXXXXX.init.gradle.kts")"
trap 'rm -f "$INIT_SCRIPT"' EXIT

cat >"$INIT_SCRIPT" <<'GRADLE'
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

allprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_PACKAGES_OWNER")}/${System.getenv("GITHUB_PACKAGES_REPOSITORY")}")
                    credentials {
                        username = System.getenv("GPR_USER") ?: System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GPR_KEY") ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
            publications.withType<MavenPublication>().configureEach {
                val overrideVersion = System.getenv("SLOCK_KUIKLY_PUBLICATION_VERSION")
                if (!overrideVersion.isNullOrBlank()) {
                    version = overrideVersion
                }
            }
        }
    }
}
GRADLE

GRADLE_COMMON_ARGS=(
  --no-daemon
  --console=plain
  --init-script "$INIT_SCRIPT"
)

if [[ "$DRY_RUN" == "1" ]]; then
  GRADLE_COMMON_ARGS+=(--dry-run)
fi

ANDROID_TASKS=(
  -c settings.2.1.21.gradle.kts
  :core:publishAllPublicationsToGitHubPackagesRepository
  :core-annotations:publishAllPublicationsToGitHubPackagesRepository
  :core-ksp:publishAllPublicationsToGitHubPackagesRepository
  :core-render-android:publishAllPublicationsToGitHubPackagesRepository
  :compose:publishAllPublicationsToGitHubPackagesRepository
)

OHOS_TASKS=(
  -c settings.2.0.ohos.gradle.kts
  :core:publishKotlinMultiplatformPublicationToGitHubPackagesRepository
  :core:publishOhosArm64PublicationToGitHubPackagesRepository
  :core-annotations:publishKotlinMultiplatformPublicationToGitHubPackagesRepository
  :core-annotations:publishOhosArm64PublicationToGitHubPackagesRepository
  :core-ksp:publishMavenPublicationToGitHubPackagesRepository
  :core-render-android:publishMavenPublicationToGitHubPackagesRepository
  :compose:publishKotlinMultiplatformPublicationToGitHubPackagesRepository
  :compose:publishOhosArm64PublicationToGitHubPackagesRepository
)

cd "$KUIKLY_DIR"

echo "==> Kuikly fork: $(git describe --tags --always --dirty)"
echo "==> GitHub Packages target: https://maven.pkg.github.com/$OWNER/$REPOSITORY"

if [[ "$FAMILY" == "all" || "$FAMILY" == "android" ]]; then
  echo "==> Publishing Android/KMP Kuikly artifacts as $ANDROID_PUBLICATION_VERSION"
  GITHUB_PACKAGES_OWNER="$OWNER" \
    GITHUB_PACKAGES_REPOSITORY="$REPOSITORY" \
    GITHUB_ACTOR="$USERNAME" \
    GITHUB_TOKEN="$TOKEN" \
    GPR_USER="$USERNAME" \
    GPR_KEY="$TOKEN" \
    KUIKLY_VERSION="$BASE_VERSION" \
    KUIKLY_KOTLIN_VERSION=2.1.21 \
    SLOCK_KUIKLY_PUBLICATION_VERSION="$ANDROID_PUBLICATION_VERSION" \
    ./gradlew "${GRADLE_COMMON_ARGS[@]}" "${ANDROID_TASKS[@]}"
fi

if [[ "$FAMILY" == "all" || "$FAMILY" == "ohos" ]]; then
  echo "==> Publishing OHOS Kuikly artifacts as $OHOS_PUBLICATION_VERSION"
  GITHUB_PACKAGES_OWNER="$OWNER" \
    GITHUB_PACKAGES_REPOSITORY="$REPOSITORY" \
    GITHUB_ACTOR="$USERNAME" \
    GITHUB_TOKEN="$TOKEN" \
    GPR_USER="$USERNAME" \
    GPR_KEY="$TOKEN" \
    KUIKLY_VERSION="$BASE_VERSION" \
    KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010 \
    SLOCK_KUIKLY_PUBLICATION_VERSION="$OHOS_PUBLICATION_VERSION" \
    ./gradlew "${GRADLE_COMMON_ARGS[@]}" "${OHOS_TASKS[@]}"
fi

echo "==> Done."
