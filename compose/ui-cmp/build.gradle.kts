import build.raft.ci.registerNoKuiklyGuard
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :ui-cmp — app UI on official Compose Multiplatform (see the CMP migration assessment, §3.2).
 *
 * Holds the CMP page registry and router split (which page names render on CMP instead of Kuikly),
 * the page root (`SlockCmpRoot`: theme, page args, host), the bridge-wire HostCapabilities adapter
 * and the CMP pages themselves. Hosts: androidApp `SlockCmpActivity`; iOS `SlockCmpIos` (exported
 * through the :shared framework, the app's only Kotlin framework).
 *
 * Depends on :core (data layer, HostCapabilities) and :designsystem. It must never depend on Kuikly;
 * verifyUiCmpHasNoKuikly enforces that on every build.
 */
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    // gradle.properties turns the default hierarchy off for :shared; this module uses the standard
    // one (commonMain -> iosMain -> ios*Main), like :designsystem.
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // HostCapabilities is part of this module's public API (SlockCmpHost).
            api(project(":core"))
            implementation(project(":designsystem"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
        }
    }
}

android {
    namespace = "build.raft.app.cmp"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Fails the build if :ui-cmp sources or its (transitive) Android/iOS dependency graph reference Kuikly.
registerNoKuiklyGuard("verifyUiCmpHasNoKuikly")
