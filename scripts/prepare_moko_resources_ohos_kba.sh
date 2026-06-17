#!/usr/bin/env bash
set -euo pipefail

# Builds local moko-resources artifacts for the current Slock OHOS line.
# Public CPF moko-resources 0.26.0-0.3.0 is compiled with Kotlin/Native
# 2.2.21-0.3.0 (KLIB ABI 2.2.0), while Slock currently uses
# 2.0.21-KBA-010 (KLIB ABI 1.8.0). This script pins the OHOS forks,
# applies the minimal KBA compatibility patches from vendored third_party
# sources, and publishes the Android/OHOS resources and resources-compose
# artifacts needed by the shared module.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

KBA_KOTLIN_VERSION="2.0.21-KBA-010"
MOKO_GRAPHICS_VERSION="0.10.1-KBA-010"
MOKO_RESOURCES_VERSION="0.26.0-KBA-010"

GRAPHICS_COMMIT="${SLOCK_MOKO_GRAPHICS_COMMIT:-c0e7ef83a243d17772a89be732ef64fb3463ed82}"
RESOURCES_COMMIT="${SLOCK_MOKO_RESOURCES_COMMIT:-df9c1a8dc2c6c9c53fc3d608b6e987079995b534}"

THIRD_PARTY_ROOT="${SLOCK_THIRD_PARTY_WORKDIR:-${REPO_ROOT}/third_party/.workdirs}"
GRAPHICS_SOURCE_DIR="${SLOCK_MOKO_GRAPHICS_SOURCE_DIR:-${REPO_ROOT}/third_party/moko-graphics}"
RESOURCES_SOURCE_DIR="${SLOCK_MOKO_RESOURCES_SOURCE_DIR:-${REPO_ROOT}/third_party/moko-resources}"
GRAPHICS_WORKDIR="${SLOCK_MOKO_GRAPHICS_WORKDIR:-${THIRD_PARTY_ROOT}/moko-graphics-kba}"
RESOURCES_WORKDIR="${SLOCK_MOKO_RESOURCES_WORKDIR:-${THIRD_PARTY_ROOT}/moko-resources-kba}"
MAVEN_LOCAL="${SLOCK_MAVEN_LOCAL:-${HOME}/.m2/repository}"

force=0
if [[ "${1:-}" == "--force" ]]; then
  force=1
fi

maven_file_exists() {
  local path="$1"
  [[ -f "${MAVEN_LOCAL}/${path}" ]]
}

graphics_artifacts_ready() {
    maven_file_exists "dev/icerock/moko/graphics/${MOKO_GRAPHICS_VERSION}/graphics-${MOKO_GRAPHICS_VERSION}.module" &&
    maven_file_exists "dev/icerock/moko/graphics-ohosarm64/${MOKO_GRAPHICS_VERSION}/graphics-ohosarm64-${MOKO_GRAPHICS_VERSION}.klib" &&
    maven_file_exists "dev/icerock/moko/graphics-android/${MOKO_GRAPHICS_VERSION}/graphics-android-${MOKO_GRAPHICS_VERSION}.aar" &&
    maven_file_exists "dev/icerock/moko/graphics-android-debug/${MOKO_GRAPHICS_VERSION}/graphics-android-debug-${MOKO_GRAPHICS_VERSION}.aar"
}

resources_artifacts_ready() {
    maven_file_exists "dev/icerock/moko/resources/${MOKO_RESOURCES_VERSION}/resources-${MOKO_RESOURCES_VERSION}.module" &&
    maven_file_exists "dev/icerock/moko/resources-ohosarm64/${MOKO_RESOURCES_VERSION}/resources-ohosarm64-${MOKO_RESOURCES_VERSION}.klib" &&
    maven_file_exists "dev/icerock/moko/resources-android/${MOKO_RESOURCES_VERSION}/resources-android-${MOKO_RESOURCES_VERSION}.aar" &&
    maven_file_exists "dev/icerock/moko/resources-generator/${MOKO_RESOURCES_VERSION}/resources-generator-${MOKO_RESOURCES_VERSION}.jar" &&
    maven_file_exists "dev/icerock/mobile/multiplatform-resources/dev.icerock.mobile.multiplatform-resources.gradle.plugin/${MOKO_RESOURCES_VERSION}/dev.icerock.mobile.multiplatform-resources.gradle.plugin-${MOKO_RESOURCES_VERSION}.pom"
}

resources_compose_artifacts_ready() {
    maven_file_exists "dev/icerock/moko/resources-compose/${MOKO_RESOURCES_VERSION}/resources-compose-${MOKO_RESOURCES_VERSION}.module" &&
    maven_file_exists "dev/icerock/moko/resources-compose-ohosarm64/${MOKO_RESOURCES_VERSION}/resources-compose-ohosarm64-${MOKO_RESOURCES_VERSION}.klib" &&
    maven_file_exists "dev/icerock/moko/resources-compose-android/${MOKO_RESOURCES_VERSION}/resources-compose-android-${MOKO_RESOURCES_VERSION}.aar"
}

resources_stack_artifacts_ready() {
    resources_artifacts_ready && resources_compose_artifacts_ready
}

artifacts_ready() {
    graphics_artifacts_ready && resources_stack_artifacts_ready
}

copy_cached_kotlin_stdlib_to_maven_local() {
  local version_dir="${HOME}/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/${KBA_KOTLIN_VERSION}"
  local target_dir="${MAVEN_LOCAL}/org/jetbrains/kotlin/kotlin-stdlib/${KBA_KOTLIN_VERSION}"
  local all_jar="${target_dir}/kotlin-stdlib-${KBA_KOTLIN_VERSION}-all.jar"

  if [[ -f "${all_jar}" ]]; then
    return
  fi

  if [[ ! -d "${version_dir}" ]]; then
    cat >&2 <<EOF
Missing cached Kotlin stdlib ${KBA_KOTLIN_VERSION}.
Run the normal Slock OHOS Gradle build once so the KBA Kotlin artifacts exist in Gradle cache.
EOF
    exit 4
  fi

  mkdir -p "${target_dir}"
  local suffix source_file
  for suffix in ".pom" ".module" ".jar" "-all.jar" "-sources.jar"; do
    source_file="$(
      find "${version_dir}" -type f -name "kotlin-stdlib-${KBA_KOTLIN_VERSION}${suffix}" -print -quit
    )"
    if [[ -n "${source_file}" ]]; then
      cp "${source_file}" "${target_dir}/"
    fi
  done

  if [[ ! -f "${all_jar}" ]]; then
    cat >&2 <<EOF
Missing kotlin-stdlib-${KBA_KOTLIN_VERSION}-all.jar in Gradle cache.
The moko graphics build requests this local Maven artifact and the eazytec mirror does not provide it.
EOF
    exit 4
  fi
}

prepare_workdir_from_vendor() {
  local source_dir="$1"
  local workdir="$2"
  local expected_commit="$3"

  if ! git -C "${source_dir}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    cat >&2 <<EOF
Missing vendored moko source: ${source_dir}
Run git submodule update --init ${source_dir#"${REPO_ROOT}/"} first.
EOF
    exit 4
  fi

  local actual_commit
  actual_commit="$(git -C "${source_dir}" rev-parse HEAD)"
  if [[ "${actual_commit}" != "${expected_commit}" ]]; then
    cat >&2 <<EOF
Vendored moko source is at ${actual_commit}, expected ${expected_commit}: ${source_dir}
Run git submodule update --init ${source_dir#"${REPO_ROOT}/"} to restore the pinned source.
EOF
    exit 4
  fi

  chmod -R u+w "${workdir}" 2>/dev/null || true
  for cleanup_attempt in 1 2 3; do
    rm -rf "${workdir}" 2>/dev/null || true
    if [[ ! -e "${workdir}" ]]; then
      break
    fi
    chmod -R u+w "${workdir}" 2>/dev/null || true
    sleep "${cleanup_attempt}"
  done
  if [[ -e "${workdir}" ]]; then
    echo "Failed to remove existing moko workdir: ${workdir}" >&2
    exit 4
  fi
  mkdir -p "$(dirname "${workdir}")"
  git clone --no-hardlinks "${source_dir}" "${workdir}"
  git -C "${workdir}" checkout "${expected_commit}"
  git -C "${workdir}" reset --hard "${expected_commit}"
  git -C "${workdir}" clean -fdx
}

patch_graphics() {
  git -C "${GRAPHICS_WORKDIR}" apply --whitespace=nowarn <<'PATCH_GRAPHICS'
diff --git a/build.gradle.kts b/build.gradle.kts
index ec0dda5..573ac3a 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -6,8 +6,12 @@ import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
  */
 buildscript {
     repositories {
-        mavenCentral()
+        mavenLocal()
+        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
+        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
+        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
         google()
+        mavenCentral()
         gradlePluginPortal()
     }
     dependencies {
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index d3f16da..85c1acd 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -1,7 +1,7 @@
 [versions]
 androidAppCompatVersion = "1.6.1"
 androidAnnotationVersion = "1.8.0"
-mokoGraphicsVersion = "0.10.1"
+mokoGraphicsVersion = "0.10.1-KBA-010"

 [libraries]
 appCompat = { module = "androidx.appcompat:appcompat", version.ref = "androidAppCompatVersion" }
diff --git a/graphics-build-logic/build.gradle.kts b/graphics-build-logic/build.gradle.kts
index 8081d65..ed9759f 100644
--- a/graphics-build-logic/build.gradle.kts
+++ b/graphics-build-logic/build.gradle.kts
@@ -2,7 +2,19 @@ plugins {
     `kotlin-dsl`
 }

+java {
+    sourceCompatibility = JavaVersion.VERSION_21
+    targetCompatibility = JavaVersion.VERSION_21
+}
+
+tasks.withType<JavaCompile>().configureEach {
+    options.release.set(21)
+}
+
 repositories {
+    mavenLocal()
+    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
+    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
     mavenCentral()
     google()

@@ -11,7 +23,7 @@ repositories {

 dependencies {
     api("dev.icerock:mobile-multiplatform:0.14.2")
-    api("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
+    api("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21-KBA-010")
     api("com.android.tools.build:gradle:8.2.2")
     api("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.6")
 }
diff --git a/graphics-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts b/graphics-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts
index c24c34b..c0d4797 100644
--- a/graphics-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts
+++ b/graphics-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts
@@ -35,10 +35,11 @@ kotlin {
     linuxArm64()
     linuxX64()
     mingwX64()
+    ohosArm64()
     @OptIn(ExperimentalWasmDsl::class)
     wasmJs {
         nodejs()
         browser()
     }
     applyDefaultHierarchyTemplate()
-}
\ No newline at end of file
+}
diff --git a/settings.gradle.kts b/settings.gradle.kts
index 2073ed1..394cd48 100644
--- a/settings.gradle.kts
+++ b/settings.gradle.kts
@@ -6,8 +6,12 @@ enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

 dependencyResolutionManagement {
     repositories {
-        mavenCentral()
+        mavenLocal()
+        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
+        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
         google()
+        mavenCentral()
+        gradlePluginPortal()
     }
 }

PATCH_GRAPHICS
}

patch_resources() {
  sed 's/^@BLANK_CONTEXT@$/ /' <<'PATCH_RESOURCES' | git -C "${RESOURCES_WORKDIR}" apply --recount --whitespace=nowarn
diff --git a/build.gradle.kts b/build.gradle.kts
index 2798964..212a436 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -4,8 +4,10 @@

 buildscript {
     repositories {
+        mavenLocal()
         maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
         maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
+        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
         mavenCentral()
         google()
         maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index b5285c2..9c9ef6d 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -3,7 +3,7 @@
 # Minimum compatibility baseline for library modules (resources, compose, etc.).
 # Allows users to use moko-resources WITHOUT forcing them to update their projects.
 #kotlinVersion = "2.1.0"
-kotlinVersion = "2.2.21-0.3.0"
+kotlinVersion = "2.0.21-KBA-010"
 androidGradleVersion = "8.3.0"
 androidSdkCommonVersion = "31.13.2"

@@ -13,7 +13,7 @@ androidSdkCommonVersion = "31.13.2"
 # the widest range of AGP versions: from the minimum (8.3)
 # to the current (9.0+ at this time).
 #pluginKotlinVersion = "2.3.0"
-pluginKotlinVersion = "2.2.21-0.3.0"
+pluginKotlinVersion = "2.0.21-KBA-010"
 pluginAndroidGradleVersion = "8.13.2"


@@ -32,7 +32,7 @@ composeUiVersion = "1.9.2"
 # jetbrains compose
 #composeJetbrainsVersion = "1.7.0"
 #composeJetbrainsVersion = "1.9.2-OH.0.1.2-31"
-composeJetbrainsVersion = "1.9.2-0.3.0"
+composeJetbrainsVersion = "1.7.3"

 # jvm
 apacheCommonsTextVersion = "1.10.0"
@@ -45,7 +45,7 @@ batikVersion = "1.17"
 # moko
 #mokoGraphicsVersion = "0.10.0"
 #mokoGraphicsVersion = "0.10.1-ohos-001"
-mokoGraphicsVersion = "0.10.1-0.3.0"
+mokoGraphicsVersion = "0.10.1-KBA-010"
 mokoTestVersion = "0.6.1"
 mokoMultiplatformPluginVersion = "0.14.2"

@@ -88,4 +88,4 @@ composeJetBrainsPlugin = { module = "org.jetbrains.compose:compose-gradle-plugin
 nexusPublishing = { module = "io.github.gradle-nexus:publish-plugin", version = "2.0.0" }

 [plugins]
-composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlinVersion" }
\ No newline at end of file
+composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlinVersion" }
diff --git a/gradle/moko.versions.toml b/gradle/moko.versions.toml
index 528d068..71bc16d 100644
--- a/gradle/moko.versions.toml
+++ b/gradle/moko.versions.toml
@@ -1,7 +1,7 @@
 [versions]
 #resourcesVersion = "0.26.0-ohos-001"
 #resourcesVersion = "0.26.0-ohos-local" #local test
-resourcesVersion = "0.26.0-0.3.0" #local test
+resourcesVersion = "0.26.0-KBA-010" #local test

 [libraries]
 resources = { module = "dev.icerock.moko:resources", version.ref = "resourcesVersion" }
diff --git a/resources-build-logic/build.gradle.kts b/resources-build-logic/build.gradle.kts
index a5514a5..5d22a7b 100644
--- a/resources-build-logic/build.gradle.kts
+++ b/resources-build-logic/build.gradle.kts
@@ -2,10 +2,21 @@ plugins {
     `kotlin-dsl`
 }

+java {
+    sourceCompatibility = JavaVersion.VERSION_21
+    targetCompatibility = JavaVersion.VERSION_21
+}
+
+tasks.withType<JavaCompile>().configureEach {
+    options.release.set(21)
+}
+
 repositories {

+    mavenLocal()
     maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
     maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
+    maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
     mavenCentral()
     google()

diff --git a/resources-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts b/resources-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts
index d35063b..b29de73 100644
--- a/resources-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts
+++ b/resources-build-logic/src/main/kotlin/multiplatform-library-convention.gradle.kts
@@ -18,9 +18,6 @@ kotlin {
     androidTarget {
         publishAllLibraryVariants()
     }
-    iosX64()
-    iosArm64()
-    iosSimulatorArm64()
 //    macosX64()
 //    macosArm64()
 //    js {
@@ -35,7 +32,6 @@ kotlin {
 //    }

     // 鸿蒙化适配：添加 ohosArm64 编译目标
-    ohosX64{}
     ohosArm64 {
 //        compilations.getByName("main") {
 //            cinterops {
@@ -61,32 +57,10 @@ kotlin {
         val commonMain by getting

         val ohosArm64Main by getting
-        val ohosX64Main by getting
         val ohosMain by creating {
             dependsOn(commonMain)
         }
         ohosArm64Main.dependsOn(ohosMain)
-        ohosX64Main.dependsOn(ohosMain)
-
-        val iosX64Main by getting
-        val iosArm64Main by getting
-        val iosSimulatorArm64Main by getting
-
-        val iosMain by creating {
-            iosX64Main.dependsOn(this)
-            iosArm64Main.dependsOn(this)
-            iosSimulatorArm64Main.dependsOn(this)
-        }
-
-        val iosX64Test by getting
-        val iosArm64Test by getting
-        val iosSimulatorArm64Test by getting
-
-        val iosTest by creating {
-            iosX64Test.dependsOn(this)
-            iosArm64Test.dependsOn(this)
-            iosSimulatorArm64Test.dependsOn(this)
-        }

 //        val macosArm64Main by getting
 //        val macosX64Main by getting
@@ -125,8 +99,6 @@ kotlin {
 //            dependsOn(commonTest)
 //        }
     }
-
-    jvmToolchain(11)
 }

 tasks.withType<AbstractTestTask> {
diff --git a/resources-generator/build.gradle.kts b/resources-generator/build.gradle.kts
index c5820a8..ea9d989 100644
--- a/resources-generator/build.gradle.kts
+++ b/resources-generator/build.gradle.kts
@@ -7,12 +7,12 @@ import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
 import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

 plugins {
-    id("org.jetbrains.kotlin.jvm") version ("2.3.0")
+    id("org.jetbrains.kotlin.jvm") version ("2.0.21-KBA-010")
     id("detekt-convention")
     id("publication-convention")
     id("com.gradle.plugin-publish") version ("1.2.0")
     id("java-gradle-plugin")
-    kotlin("plugin.serialization") version ("2.3.0")
+    kotlin("plugin.serialization") version ("2.0.21-KBA-010")
     id("nexus-publication-convention")
     `maven-publish`
 }
@@ -43,10 +43,6 @@ java {
     withSourcesJar()
 }

-kotlin {
-    jvmToolchain(11)
-}
-
 tasks.withType<KotlinJvmCompile>().configureEach {
     compilerOptions {
         jvmTarget.set(JvmTarget.JVM_11)
diff --git a/resources-generator/src/main/kotlin/dev/icerock/gradle/utils/KotlinMultiplatformAndroidLibraryExt.kt b/resources-generator/src/main/kotlin/dev/icerock/gradle/utils/KotlinMultiplatformAndroidLibraryExt.kt
index 5551847..3c454c1 100644
--- a/resources-generator/src/main/kotlin/dev/icerock/gradle/utils/KotlinMultiplatformAndroidLibraryExt.kt
+++ b/resources-generator/src/main/kotlin/dev/icerock/gradle/utils/KotlinMultiplatformAndroidLibraryExt.kt
@@ -10,7 +10,6 @@ import org.gradle.api.Project
 import org.gradle.kotlin.dsl.findByType
 import org.gradle.kotlin.dsl.withType
 import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
-import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

 /**
  * Resolves the Android R-class package name (namespace) specifically for
@@ -24,10 +23,8 @@ import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
  * extension is missing or the namespace property has not been set.
  */
 internal fun getAndroidKmpRClassPackage(project: Project): String? {
-    val kotlinProjectExtension = project.extensions.findByType<KotlinProjectExtension>()
-    val androidLibraryExtension: KotlinMultiplatformAndroidLibraryExtension = kotlinProjectExtension
-        ?.extensions
-        ?.findByType<KotlinMultiplatformAndroidLibraryExtension>()
+    val androidLibraryExtension: KotlinMultiplatformAndroidLibraryExtension = project.extensions
+        .findByType<KotlinMultiplatformAndroidLibraryExtension>()
         ?: return null

     return androidLibraryExtension.namespace
diff --git a/resources/build.gradle.kts b/resources/build.gradle.kts
index 44660c3..fa80842 100644
--- a/resources/build.gradle.kts
+++ b/resources/build.gradle.kts
@@ -3,14 +3,11 @@
  */
@BLANK_CONTEXT@
 import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
+import org.jetbrains.kotlin.gradle.dsl.JvmTarget
@BLANK_CONTEXT@
 plugins {
-    id("multiplatform-library-extended-convention")
+    id("multiplatform-library-convention")
     id("multiplatform-android-publish-convention")
-    if(org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
-        id("apple-main-convention")
-        id("apple-bundle-searcher-convention")
-    }
     id("detekt-convention")
     id("javadoc-stub-convention")
     id("publication-convention")
@@ -24,10 +21,9 @@ kotlin {
             includeDirs("$projectDir/src/nativeInterop/cinterop/cpp")
         }
     }
-    targets.named<KotlinNativeTarget>("ohosX64") {
-        compilations.getByName("main").cinterops.create("ohosUtils") {
-            defFile(file("src/nativeInterop/cinterop/ohosUtilsOhosX64.def"))
-            includeDirs("$projectDir/src/nativeInterop/cinterop/cpp")
+    androidTarget {
+        compilerOptions {
+            jvmTarget.set(JvmTarget.JVM_11)
         }
     }
@BLANK_CONTEXT@
@@ -61,7 +57,7 @@ dependencies {

     androidMainImplementation(libs.appCompatResources)

-    iosTestImplementation(libs.mokoTestCore)
+//    iosTestImplementation(libs.mokoTestCore)
 }

 tasks.named("publishToMavenLocal") {
@@ -76,20 +72,6 @@ tasks.named("publishToMavenLocal") {
 //    dependsOn(pluginPublish)
 //}

-val copyIosX64TestResources = tasks.register<Copy>("copyIosX64TestResources") {
-    from("src/iosTest/resources")
-    into("build/bin/iosX64/debugTest")
-}
-
-tasks.findByName("iosX64Test")!!.dependsOn(copyIosX64TestResources)
-
-val copyIosArm64TestResources = tasks.register<Copy>("copyIosArm64TestResources") {
-    from("src/iosTest/resources")
-    into("build/bin/iosSimulatorArm64/debugTest")
-}
-
-tasks.findByName("iosSimulatorArm64Test")!!.dependsOn(copyIosArm64TestResources)
-
 // cinterop 前需按目标架构产出 libresource_utils.a（arm64 真机 / x86_64 模拟器不可混用）
 //afterEvaluate {
 //    tasks.matching { it.name.startsWith("cinteropOhosUtils") }.configureEach {
@@ -131,12 +113,6 @@ val buildCppLibraryOhosArm64 = registerOhosCppBuild(
     ohosArch = "arm64-v8a",
     buildRelDir = "build/arm64-v8a"
 )
-val buildCppLibraryOhosX64 = registerOhosCppBuild(
-    "buildCppLibraryOhosX64",
-    ohosArch = "x86_64",
-    buildRelDir = "build/x86_64"
-)
-
 tasks.register("buildCppLibrary") {
-    dependsOn(buildCppLibraryOhosArm64, buildCppLibraryOhosX64) //
-}
\ No newline at end of file
+    dependsOn(buildCppLibraryOhosArm64)
+}
diff --git a/resources-compose/build.gradle.kts b/resources-compose/build.gradle.kts
--- a/resources-compose/build.gradle.kts
+++ b/resources-compose/build.gradle.kts
@@ -2,6 +2,8 @@
  * Copyright 2021 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
  */
@BLANK_CONTEXT@
+import org.jetbrains.kotlin.gradle.dsl.JvmTarget
+
 plugins {
     id("com.android.library")
     id("org.jetbrains.kotlin.multiplatform")
@@ -9,9 +11,6 @@ plugins {
     id("android-base-convention")
     id("dev.icerock.mobile.multiplatform.android-manifest")
     id("multiplatform-android-publish-convention")
-    if(org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
-        id("apple-main-convention")
-    }
     id("detekt-convention")
     id("org.jetbrains.compose")
     id("javadoc-stub-convention")
@@ -28,15 +27,15 @@ android {
@BLANK_CONTEXT@
 kotlin {
 //    jvm()
-    androidTarget()
-    iosX64()
-    iosArm64()
-    iosSimulatorArm64()
+    androidTarget {
+        compilerOptions {
+            jvmTarget.set(JvmTarget.JVM_11)
+        }
+    }
 //    macosX64()
 //    macosArm64()
 //    js { browser() }
 //    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
 //    wasmJs { browser() }
     // 鸿蒙化适配：添加 ohosArm64 编译目标
     ohosArm64()
-    ohosX64()
@BLANK_CONTEXT@
     sourceSets {
         // Mac 上 apple-main 会套用 multiplatform-library-convention，已创建 ohosMain；非 Mac 需自建
@@ -45,13 +44,11 @@ kotlin {
             }
         }
         getByName("ohosArm64Main").dependsOn(getByName("ohosMain"))
-        getByName("ohosX64Main").dependsOn(getByName("ohosMain"))
@BLANK_CONTEXT@
         commonMain {
             dependencies {
                 api(projects.resources)
-                api(compose.runtime)
-                api(compose.foundation)
+                api("com.tencent.kuikly-open.compose.runtime:runtime:1.7.3-kuikly1")
             }
         }
         androidMain {
@@ -61,10 +58,3 @@ kotlin {
         }
     }
 }
-
-compose {
-    ohos {
-        skia("0.9.22.2-0.1.1")
-    }
-}
diff --git a/resources/src/ohosMain/kotlin/dev/icerock/moko/resources/utils/OhosUtils.ohosArm64.kt b/resources/src/ohosMain/kotlin/dev/icerock/moko/resources/utils/OhosUtils.ohosArm64.kt
index 8f57f99..cca351f 100644
--- a/resources/src/ohosMain/kotlin/dev/icerock/moko/resources/utils/OhosUtils.ohosArm64.kt
+++ b/resources/src/ohosMain/kotlin/dev/icerock/moko/resources/utils/OhosUtils.ohosArm64.kt
@@ -1,7 +1,7 @@
 package dev.icerock.moko.resources.utils
+import cnames.structs.napi_env__
 import dev.icerock.moko.resources.interop.ohosutils.*
 import kotlinx.cinterop.*
-import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env
 import platform.posix.free
 import platform.posix.memcpy
 import platform.posix.size_t
@@ -10,7 +10,7 @@ import platform.posix.uint32_tVar
 import platform.posix.uint64_tVar

 @OptIn(ExperimentalForeignApi::class)
-fun InitOhosResourcesUtils(env: napi_env)
+fun InitOhosResourcesUtils(env: CPointer<napi_env__>?)
 {
     dev.icerock.moko.resources.interop.ohosutils.InitOhosResourcesUtils(env)
 }
@@ -214,4 +214,4 @@ internal object OhosResourceUtils {
     fun getFont(resName: String): ByteArray? {
         return getFile(resName)
     }
-}
\ No newline at end of file
+}
diff --git a/settings.gradle.kts b/settings.gradle.kts
index 78073ce..a8e4492 100644
--- a/settings.gradle.kts
+++ b/settings.gradle.kts
@@ -8,6 +8,7 @@ enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

 pluginManagement {
     repositories {
+        mavenLocal()
         maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
         maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
         mavenCentral()
@@ -19,6 +20,7 @@ pluginManagement {
 dependencyResolutionManagement {
     repositories {
         // 鸿蒙化适配：mavenCentral 优先，避免标准库（如 icu4j）因 Nexus 代理缓存缺失 JAR 而失败
+        mavenLocal()
         mavenCentral()
         google()
         maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
@@ -40,5 +42,3 @@ if (gradle.parent == null) {
 }

 include(":resources")
 include(":resources-compose")
-include(":resources-test")
PATCH_RESOURCES

  # Slock only consumes the string/plural/StringDesc Compose helpers. The
  # upstream color/font/image helpers require the full Compose UI/skia stack,
  # which is not available on Slock's pinned Kuikly OHOS Compose 1.7.3 line.
  rm -f \
    "${RESOURCES_WORKDIR}/resources-compose/src/commonMain/kotlin/dev/icerock/moko/resources/compose/ColorResource.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/commonMain/kotlin/dev/icerock/moko/resources/compose/FontResource.common.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/commonMain/kotlin/dev/icerock/moko/resources/compose/ImageResource.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/androidMain/kotlin/dev/icerock/moko/resources/compose/ColorResource.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/androidMain/kotlin/dev/icerock/moko/resources/compose/FontResource.android.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/androidMain/kotlin/dev/icerock/moko/resources/compose/ImageResource.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/ohosMain/kotlin/dev/icerock/moko/resources/compose/ColorResource.ohosArm64.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/ohosMain/kotlin/dev/icerock/moko/resources/compose/FontResource.common.ohosArm64.kt" \
    "${RESOURCES_WORKDIR}/resources-compose/src/ohosMain/kotlin/dev/icerock/moko/resources/compose/ImageResource.ohosArm64.kt"
  rm -rf "${RESOURCES_WORKDIR}/resources-compose/src/ohosMain/kotlin/dev/icerock/moko/resources/compose/internal"
}

if [[ "${force}" -eq 0 ]] && artifacts_ready; then
  echo "moko-resources/resources-compose Android/OHOS KBA artifacts already exist in ${MAVEN_LOCAL}."
  exit 0
fi

copy_cached_kotlin_stdlib_to_maven_local

if [[ "${force}" -eq 1 ]] || ! graphics_artifacts_ready; then
  prepare_workdir_from_vendor "${GRAPHICS_SOURCE_DIR}" "${GRAPHICS_WORKDIR}" "${GRAPHICS_COMMIT}"
  patch_graphics
  "${GRAPHICS_WORKDIR}/gradlew" \
    --no-daemon \
    --no-build-cache \
    --no-parallel \
    --console=plain \
    -Dorg.gradle.workers.max=1 \
    -Dkotlin.compiler.execution.strategy=in-process \
    -Dkotlin.incremental=false \
    -p "${GRAPHICS_WORKDIR}" \
    :graphics:publishKotlinMultiplatformPublicationToMavenLocal \
    :graphics:publishOhosArm64PublicationToMavenLocal \
    :graphics:publishAndroidReleasePublicationToMavenLocal \
    :graphics:publishAndroidDebugPublicationToMavenLocal
else
  echo "moko-graphics KBA artifacts already exist in ${MAVEN_LOCAL}."
fi

if [[ "${force}" -eq 1 ]] || ! resources_stack_artifacts_ready; then
  prepare_workdir_from_vendor "${RESOURCES_SOURCE_DIR}" "${RESOURCES_WORKDIR}" "${RESOURCES_COMMIT}"
  patch_resources
  "${RESOURCES_WORKDIR}/gradlew" \
    --no-daemon \
    --no-build-cache \
    --no-parallel \
    --console=plain \
    -Dorg.gradle.workers.max=1 \
    -Dkotlin.compiler.execution.strategy=in-process \
    -Dkotlin.incremental=false \
    -p "${RESOURCES_WORKDIR}" \
    :resources:publishKotlinMultiplatformPublicationToMavenLocal \
    :resources:publishOhosArm64PublicationToMavenLocal \
    :resources:publishAndroidPublicationToMavenLocal \
    :resources-generator:publishToMavenLocal \
    :resources-compose:publishKotlinMultiplatformPublicationToMavenLocal \
    :resources-compose:publishOhosArm64PublicationToMavenLocal \
    :resources-compose:publishAndroidPublicationToMavenLocal
else
  echo "moko-resources/resources-compose KBA artifacts already exist in ${MAVEN_LOCAL}."
fi

if ! artifacts_ready; then
  echo "moko-resources/resources-compose KBA artifact preparation finished but required MavenLocal artifacts are still missing." >&2
  exit 5
fi

cat <<EOF
Prepared moko-resources/resources-compose Android/OHOS KBA artifacts.
Kotlin: ${KBA_KOTLIN_VERSION}
graphics: ${MOKO_GRAPHICS_VERSION}
resources: ${MOKO_RESOURCES_VERSION}
MavenLocal: ${MAVEN_LOCAL}
EOF
