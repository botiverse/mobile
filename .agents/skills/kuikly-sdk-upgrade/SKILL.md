---
name: kuikly-sdk-upgrade
description: Upgrade and validate Slock's Kuikly fork release family across Gradle/Kotlin artifacts, Android render artifacts, OHOS KLIB/render HAR/lockfile, and iOS Pod pins. Use when Codex is asked to upgrade Kuikly, publish/consume the bytemain KuiklyUI fork from staging, align @kuikly-open/render with Maven artifacts, fix OHOS host build/runtime breakages after a Kuikly bump, or investigate mixed Kuikly versions across Android, OHOS, iOS, miniApp, and shared Compose.
---

# Kuikly SDK Upgrade

## Core Rule

Keep every Kuikly-facing layer on the same Kuikly release family:

- Fork source: `third_party/kuikly-ui` on branch `staging`
- Gradle/Kotlin artifacts: `compose/buildSrc/src/main/java/KotlinBuildVar.kt`
- OHOS host render package: `compose/ohosApp/entry/oh-package.json5`
- OHOS lockfile: `compose/ohosApp/entry/oh-package-lock.json5`
- iOS host render pod: `compose/iosApp/Podfile`
- Any platform-specific generated or installed package state needed by the build

Do not solve missing SDK APIs by deleting the call site first. Verify whether the platform SDK/render package or Maven/KLIB artifact is stale, then upgrade/publish the matching package set and re-run the host build.

For Slock, the canonical upgrade target is usually **the bytemain fork staging state**, not the old version currently pinned in Slock. First discover the fork version, then update Slock pins to that version family.

## Current Slock Pattern

At the time this skill was updated, Slock pins:

- `compose/buildSrc/src/main/java/KotlinBuildVar.kt`: `KUIKLY_VERSION = "2.21.0"`
- Android Gradle artifacts: `<KUIKLY_VERSION>-2.1.21`
- OHOS Gradle/KLIB artifacts: `<KUIKLY_VERSION>-2.0.21-ohos`
- OHOS render package: `@kuikly-open/render` in `compose/ohosApp/entry/oh-package.json5`
- iOS render pod: `OpenKuiklyIOSRender` in `compose/iosApp/Podfile`

The fork submodule may be newer than these pins. For example, `third_party/kuikly-ui` `staging` may describe as `2.21.0-33-gea0788c8`; Slock's current local-build strategy is to publish that fork into mavenLocal under the selected `2.21.0` release line, then let mavenLocal override the public artifacts. Always include the fork `git describe` in the handoff so this override is visible.

## Version Discovery

1. Inspect the fork version and current pins:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
git -C third_party/kuikly-ui status --short --branch
git -C third_party/kuikly-ui describe --tags --always --dirty
git -C third_party/kuikly-ui tag --sort=-creatordate | sed -n '1,10p'
rg -n "KUIKLY_VERSION|@kuikly-open/render|OpenKuiklyIOSRender|useOhSharedPreferences|2\\.[0-9]+\\.[0-9]+" compose AGENTS.md -S
```

Interpretation:

- If `git describe` is exactly a tag such as `2.21.0`, use that Kuikly version.
- If it is tag-plus-commits such as `2.21.0-33-gea0788c8`, Slock can either publish an internal fork version (for example `2.21.0-slock-ea0788c8`) or, for local builds, publish the fork under the selected public release line so mavenLocal overrides Tencent artifacts. Do not make that override implicit; report both the public line and the fork `git describe`.
- Keep Android and OHOS Kotlin suffixes separate: Android currently uses `2.1.21`; OHOS currently uses `2.0.21-KBA-010` in the fork build and consumes it as `2.0.21-ohos`.

2. Query OHPM for the OHOS host render package:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose/ohosApp
SDK_HOME=/Applications/DevEco-Studio.app/Contents
export DEVECO_SDK_HOME=$SDK_HOME/sdk
export PATH=$DEVECO_SDK_HOME:$SDK_HOME/jbr/Contents/Home/bin:$SDK_HOME/tools/node/bin:$SDK_HOME/tools/ohpm/bin:$SDK_HOME/tools/hvigor/bin:$DEVECO_SDK_HOME/default/openharmony/toolchains:$PATH
ohpm info @kuikly-open/render
ohpm info @kuikly-open/render@<version>
```

3. Verify Maven artifacts on the Tencent mirror, not only Maven Central. Recent Kuikly releases may exist on Tencent Maven before Maven Central metadata looks current:

```bash
curl -fsSL "https://mirrors.tencent.com/nexus/repository/maven-tencent/com/tencent/kuikly-open/core/<kuikly>-2.1.21/core-<kuikly>-2.1.21.pom" >/tmp/kuikly-core.pom
curl -fsSL "https://mirrors.tencent.com/nexus/repository/maven-tencent/com/tencent/kuikly-open/core/<kuikly>-2.0.21-ohos/core-<kuikly>-2.0.21-ohos.pom" >/tmp/kuikly-core-ohos.pom
curl -fsSL "https://mirrors.tencent.com/nexus/repository/maven-tencent/com/tencent/kuikly-open/core-render-android/<kuikly>-2.1.21/core-render-android-<kuikly>-2.1.21.pom" >/tmp/kuikly-render-android.pom
```

4. Inspect local/published KLIB consistency when a runtime crash reports `IrLinkageError`:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
unzip -p ~/.gradle/caches/modules-2/files-2.1/com.tencent.kuikly-open/core-ohosarm64/<version>/*/core.klib default/ir/strings.knt \
  | strings | rg "TextAreaAttr|fontFamily"
```

If `compose-ohosarm64` calls a symbol that `core-ohosarm64` lacks, this is a mixed artifact problem. Publish/consume a matching artifact set instead of patching the call site first.

## Publishing The Fork Locally

For local proof, publish all Kuikly modules that Slock consumes from the same fork commit. Do not publish only `compose`.

Android/foundation line:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/third_party/kuikly-ui
KUIKLY_VERSION=<fork-version> \
KUIKLY_KOTLIN_VERSION=2.1.21 \
./gradlew --no-daemon --console=plain -c settings.2.1.21.gradle.kts \
  :core:publishToMavenLocal \
  :core-annotations:publishToMavenLocal \
  :core-ksp:publishToMavenLocal \
  :core-render-android:publishToMavenLocal \
  :compose:publishToMavenLocal
```

OHOS line:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/third_party/kuikly-ui
KUIKLY_VERSION=<fork-version> \
KUIKLY_KOTLIN_VERSION=2.0.21-KBA-010 \
./gradlew --no-daemon --console=plain -c settings.2.0.ohos.gradle.kts \
  :core:publishKotlinMultiplatformPublicationToMavenLocal \
  :core:publishOhosArm64PublicationToMavenLocal \
  :core-annotations:publishKotlinMultiplatformPublicationToMavenLocal \
  :core-annotations:publishOhosArm64PublicationToMavenLocal \
  :core-ksp:publishMavenPublicationToMavenLocal \
  :core-render-android:publishMavenPublicationToMavenLocal \
  :compose:publishKotlinMultiplatformPublicationToMavenLocal \
  :compose:publishOhosArm64PublicationToMavenLocal
```

Note: `scripts/build_kuikly_fork.sh` is the canonical local helper. It should publish both Android and OHOS artifacts for the selected release line. Check it before relying on it; override `KUIKLY_VERSION` explicitly or update the script as part of the upgrade.

## Upgrade Steps

1. Discover the fork target version from `third_party/kuikly-ui` and decide the exact public/internal version string.
2. Publish or verify the matching Maven artifacts for Android and OHOS using the same fork source commit.
3. Update `KUIKLY_VERSION` in `compose/buildSrc/src/main/java/KotlinBuildVar.kt`.
4. Update `@kuikly-open/render` in `compose/ohosApp/entry/oh-package.json5`.
5. Update `OpenKuiklyIOSRender` in `compose/iosApp/Podfile` if the iOS host exists in the same release train.
6. Run `ohpm install --all` from `compose/ohosApp` to refresh the OHOS lockfile and installed render package.
7. Re-check installed OHOS render types before trusting the new call site:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
rg -n --hidden --follow "useOhSharedPreferences|SharedPreferences|KRMonitorType" compose/ohosApp/oh_modules/.ohpm/@kuikly-open+render@<version> -S
```

8. Restore or keep SDK-backed options once the upgraded types support them. For example, `useOhSharedPreferences: true` belongs in the OHOS `Kuikly({ ... })` entry config when the render type exposes it.

## Validation

Run the shared and host builds after every Kuikly upgrade:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileDebugKotlinAndroid :shared:publishAndroidReleasePublicationToMavenLocal
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64
./gradlew :androidApp:compileDebugKotlin

cd /Users/artin/AndroidStudioProjects/Slock/compose/ohosApp
SDK_HOME=/Applications/DevEco-Studio.app/Contents
export DEVECO_SDK_HOME=$SDK_HOME/sdk
export PATH=$DEVECO_SDK_HOME:$SDK_HOME/jbr/Contents/Home/bin:$SDK_HOME/tools/node/bin:$SDK_HOME/tools/ohpm/bin:$SDK_HOME/tools/hvigor/bin:$DEVECO_SDK_HOME/default/openharmony/toolchains:$PATH
ohpm install --all
$SDK_HOME/tools/node/bin/node $SDK_HOME/tools/hvigor/bin/hvigorw.js --sync -p product=default --analyze=normal --parallel
$SDK_HOME/tools/node/bin/node $SDK_HOME/tools/hvigor/bin/hvigorw.js --mode module -p module=entry@default -p product=default -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
```

Notes:

- The OHOS Gradle build may warn that `ksp-2.0.21-1.0.27 is too old for kotlin-2.0.21-KBA-010`; treat it as a warning if the task ends with `BUILD SUCCESSFUL`.
- Kotlin/Native OHOS link can emit very large generated C++ warning output. Wait for the actual Gradle/Hvigor result before diagnosing.
- `hvigorw` may be available from DevEco PATH rather than as a local `./hvigorw` file in `compose/ohosApp`.

## Known Failure Patterns

- **`kotlin.native.internal.IrLinkageError: Function ... No function found for symbol ...` at app startup**: usually mixed Kuikly KLIB artifacts. Example: `compose-ohosarm64` calls `TextAreaAttr.fontFamily`, but `core-ohosarm64` does not contain that symbol. Publish/consume `core`, `core-annotations`, `core-ksp`, `core-render-android`, and `compose` from the same fork commit/version family.
- **`useOhSharedPreferences` missing from types**: usually `@kuikly-open/render` is stale. Upgrade OHOS render and lockfile instead of removing the setting.
- **Maven Central says a new Kuikly version is missing**: check the Tencent Maven mirror configured by `compose/settings*.gradle.kts`.
- **HAP fails with `KuiklyViewDelegate.performanceMonitorTypes` / `KRMonitorType` incompatibility**: the host code may define a local `KRMonitorType` enum/type that no longer matches the render package. Import and return the render package's `KRMonitorType` instead of maintaining a local duplicate.
- **HAP fails parsing `entry/src/main/resources/base/profile/fonts.json`**: this is an OHOS resource JSON format issue surfaced during host packaging, not a Kuikly version resolution issue. Validate the file as strict JSON with `{}` at the root and no trailing commas.
- **Warnings from `@kuikly-open/render` `.d.ets` files about strict typing comments**: these can be warnings only. Do not downgrade solely for them unless Hvigor reports a real compile error.
- **Resource name conflicts such as `page_show`, `loading`, `network_error`**: these are usually warnings from merged dependencies. Track them, but fix the first fatal compiler/config error.

## Handoff Discipline

When reporting a Kuikly upgrade, include:

- Exact old and new versions for fork source (`git describe`), Gradle/Maven artifacts, OHPM render, and iOS Pod.
- Whether artifacts were only consumed from public Maven/OHPM or locally published from `third_party/kuikly-ui`.
- Whether `ohpm install --all` refreshed the lockfile.
- Whether `useOhSharedPreferences` or other new SDK fields are confirmed in installed types.
- The validation commands that passed and the first failing command if any remains.
