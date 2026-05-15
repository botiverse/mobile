#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORK_DIR="$ROOT_DIR/third_party/kuikly-compose-markdown"
VERSION="${KUIKLY_MARKDOWN_VERSION:-0.4.0}"
MAVEN_BASE="${KUIKLY_MARKDOWN_MAVEN_BASE:-https://mirrors.tencent.com/nexus/repository/maven-public}"
ARTIFACT_URL="$MAVEN_BASE/com/tencent/kuiklybase/markdown-android/$VERSION/markdown-android-$VERSION-sources.jar"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

curl -fsSL "$ARTIFACT_URL" -o "$TMP_DIR/markdown-android-$VERSION-sources.jar"
(
  cd "$TMP_DIR"
  jar xf "markdown-android-$VERSION-sources.jar"
)

rm -rf "$FORK_DIR/src/commonMain/kotlin"
mkdir -p "$FORK_DIR/src/commonMain/kotlin"
cp -R "$TMP_DIR/commonMain/com" "$FORK_DIR/src/commonMain/kotlin/"

if command -v rg >/dev/null 2>&1; then
  rg -l "kotlinx\\.collections\\.immutable" "$FORK_DIR/src/commonMain/kotlin" |
    xargs perl -0pi -e 's/kotlinx\.collections\.immutable/com.tencent.kuiklybase.markdown.shaded.immutable/g'
else
  find "$FORK_DIR/src/commonMain/kotlin" -type f -name '*.kt' -print0 |
    xargs -0 perl -0pi -e 's/kotlinx\.collections\.immutable/com.tencent.kuiklybase.markdown.shaded.immutable/g'
fi

echo "Prepared Kuikly markdown fork from $ARTIFACT_URL"
