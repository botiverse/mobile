#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail() {
  echo "Android Beta workflow contract failed: $*" >&2
  exit 1
}

workflow=.github/workflows/android-beta.yml
gradle_file=compose/androidApp/build.gradle.kts

[[ -f "$workflow" ]] || fail "dedicated workflow is missing"
grep -Fq 'name: Android Beta' "$workflow" || fail "dedicated workflow name is missing"
grep -Fq 'source_sha:' "$workflow" || fail "immutable main source input is missing"
for input in version_major version_minor version_patch version_build; do
  grep -Fq "${input}:" "$workflow" || fail "${input} input is missing"
done
grep -Fq 'git merge-base --is-ancestor "$sha" refs/remotes/origin/main' "$workflow" ||
  fail "manual dispatch is not restricted to commits contained in main"
grep -Fq ':androidApp:assembleBeta' "$workflow" || fail "workflow does not build the Beta variant"
for property in versionX versionY versionZ versionBuild; do
  grep -Fq -- "-P${property}=\${BETA_VERSION_" "$workflow" ||
    fail "workflow does not pass the ${property} coordinate"
done
grep -Fq -- '-PversionSuffix=beta' "$workflow" || fail "workflow does not apply the Beta version suffix"
grep -Fq 'EXPECTED_VERSION_NAME="${BETA_VERSION_MAJOR}.${BETA_VERSION_MINOR}.${BETA_VERSION_PATCH}-beta"' "$workflow" ||
  fail "built Beta versionName is not checked against the requested coordinate"
grep -Fq 'test "${VERSION_CODE}" = "${EXPECTED_VERSION_CODE}"' "$workflow" ||
  fail "built Beta versionCode is not checked against the requested coordinate"
grep -Fq "package: name='build.raft.app.beta'" "$workflow" || fail "package identity is not verified"
grep -Fq "application-label:'Raft Beta'" "$workflow" || fail "application label is not verified"
grep -Fq 'scripts/verify_android_apk_with_rotation.sh' "$workflow" ||
  fail "canonical rotated-signature verifier is not wired"
grep -Fq 'scripts/verify_android_native_symbols.sh' "$workflow" ||
  fail "native-symbol binding is not verified"
grep -Fq 'android-beta-workflow-files.tar' "$workflow" ||
  fail "workflow-owned verifier staging is not wired"

if grep -Eq 'publish_hands|create_share_page|hands release (create|publish)' "$workflow"; then
  fail "Beta build workflow contains a Hands publish/share path"
fi

grep -Fq 'create("beta")' "$gradle_file" || fail "Beta build type is missing"
grep -Fq 'initWith(getByName("release"))' "$gradle_file" || fail "Beta is not release-derived"
grep -Fq 'applicationIdSuffix = ".beta"' "$gradle_file" || fail "Beta applicationId suffix drifted"
grep -Fq 'buildConfigField("Boolean", "SHOW_TEST_BUILD_BADGE", "true")' "$gradle_file" ||
  fail "Beta test-build badge is disabled"
grep -Fq 'buildConfigField("String", "HANDS_CHANNEL", "\"debug\"")' "$gradle_file" ||
  fail "Beta variant is not isolated from the main update channel"

for strings in \
  compose/androidApp/src/beta/res/values/strings.xml \
  compose/androidApp/src/beta/res/values-zh-rCN/strings.xml; do
  [[ -f "$strings" ]] || fail "missing Beta label resource: $strings"
  grep -Fq '<string name="app_name">Raft Beta</string>' "$strings" ||
    fail "Beta label drifted in $strings"
done

echo "Android Beta workflow contract: PASS"
