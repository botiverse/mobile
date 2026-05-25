---
name: kuikly-sdk-upgrade
description: Upgrade and validate Kuikly SDK/render versions in the Slock workspace. Use when Codex is asked to upgrade Kuikly, align @kuikly-open/render with Gradle/Maven Kuikly artifacts, enable new Kuikly runtime options such as useOhSharedPreferences, fix OHOS host build breakages after a Kuikly bump, or investigate mixed Kuikly versions across Android, OHOS, iOS, miniApp, and shared Compose.
---

# Kuikly SDK Upgrade

## Core Rule

Keep every Kuikly-facing layer on the same Kuikly release family:

- Gradle/Kotlin artifacts: `compose/buildSrc/src/main/java/KotlinBuildVar.kt`
- OHOS host render package: `compose/ohosApp/entry/oh-package.json5`
- OHOS lockfile: `compose/ohosApp/entry/oh-package-lock.json5`
- iOS host render pod: `compose/iosApp/Podfile`
- Any platform-specific generated or installed package state needed by the build

Do not solve missing SDK APIs by deleting the call site first. Verify whether the platform SDK/render package is stale, then upgrade the package and re-run the host build.

## Version Discovery

1. Inspect current pins:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
rg -n "KUIKLY_VERSION|@kuikly-open/render|OpenKuiklyIOSRender|useOhSharedPreferences|2\\.[0-9]+\\.[0-9]+" compose AGENTS.md -S
```

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

The Slock repo currently builds Android Kuikly artifacts as `<KUIKLY_VERSION>-2.1.21` and OHOS artifacts as `<KUIKLY_VERSION>-2.0.21-ohos`.

## Upgrade Steps

1. Update `KUIKLY_VERSION` in `compose/buildSrc/src/main/java/KotlinBuildVar.kt`.
2. Update `@kuikly-open/render` in `compose/ohosApp/entry/oh-package.json5`.
3. Update `OpenKuiklyIOSRender` in `compose/iosApp/Podfile` if the iOS host exists in the same release train.
4. Run `ohpm install --all` from `compose/ohosApp` to refresh the OHOS lockfile and installed render package.
5. Re-check installed OHOS render types before trusting the new call site:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
rg -n --hidden --follow "useOhSharedPreferences|SharedPreferences|KRMonitorType" compose/ohosApp/oh_modules/.ohpm/@kuikly-open+render@<version> -S
```

6. Restore or keep SDK-backed options once the upgraded types support them. For example, `useOhSharedPreferences: true` belongs in the OHOS `Kuikly({ ... })` entry config when the render type exposes it.

## Validation

Run the shared and host builds after every Kuikly upgrade:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileDebugKotlinAndroid :shared:publishAndroidReleasePublicationToMavenLocal
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64

cd /Users/artin/AndroidStudioProjects/Slock
./gradlew :app:compileDebugKotlin

cd /Users/artin/AndroidStudioProjects/Slock/compose/ohosApp
SDK_HOME=/Applications/DevEco-Studio.app/Contents
export DEVECO_SDK_HOME=$SDK_HOME/sdk
export PATH=$DEVECO_SDK_HOME:$SDK_HOME/jbr/Contents/Home/bin:$SDK_HOME/tools/node/bin:$SDK_HOME/tools/ohpm/bin:$SDK_HOME/tools/hvigor/bin:$DEVECO_SDK_HOME/default/openharmony/toolchains:$PATH
hvigorw --mode module -p module=entry@default -p product=default -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
```

Notes:

- The OHOS Gradle build may warn that `ksp-2.0.21-1.0.27 is too old for kotlin-2.0.21-KBA-010`; treat it as a warning if the task ends with `BUILD SUCCESSFUL`.
- Kotlin/Native OHOS link can emit very large generated C++ warning output. Wait for the actual Gradle/Hvigor result before diagnosing.
- `hvigorw` may be available from DevEco PATH rather than as a local `./hvigorw` file in `compose/ohosApp`.

## Known Failure Patterns

- **`useOhSharedPreferences` missing from types**: usually `@kuikly-open/render` is stale. Upgrade OHOS render and lockfile instead of removing the setting.
- **Maven Central says a new Kuikly version is missing**: check the Tencent Maven mirror configured by `compose/settings*.gradle.kts`.
- **HAP fails with `KuiklyViewDelegate.performanceMonitorTypes` / `KRMonitorType` incompatibility**: the host code may define a local `KRMonitorType` enum/type that no longer matches the render package. Import and return the render package's `KRMonitorType` instead of maintaining a local duplicate.
- **HAP fails parsing `entry/src/main/resources/base/profile/fonts.json`**: this is an OHOS resource JSON format issue surfaced during host packaging, not a Kuikly version resolution issue. Validate the file as strict JSON with `{}` at the root and no trailing commas.
- **Warnings from `@kuikly-open/render` `.d.ets` files about strict typing comments**: these can be warnings only. Do not downgrade solely for them unless Hvigor reports a real compile error.
- **Resource name conflicts such as `page_show`, `loading`, `network_error`**: these are usually warnings from merged dependencies. Track them, but fix the first fatal compiler/config error.

## Handoff Discipline

When reporting a Kuikly upgrade, include:

- Exact old and new versions for Gradle, OHPM render, and iOS Pod.
- Whether `ohpm install --all` refreshed the lockfile.
- Whether `useOhSharedPreferences` or other new SDK fields are confirmed in installed types.
- The validation commands that passed and the first failing command if any remains.
