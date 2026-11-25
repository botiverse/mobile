import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :web-container — standalone cross-platform Hybrid WebView Container and Kotlin JSB runtime.
 *
 * Zero internal dependencies on :shared, :core, :ui-cmp, or :designsystem so the module can be
 * tested independently, published as a Maven artifact, or extracted to a standalone repository
 * without refactoring callers. iOS links statically through :shared's existing framework.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    // compose/gradle.properties disables the default hierarchy for :shared; this standalone module
    // uses the standard hierarchy (commonMain -> iosMain -> ios*Main, etc.).
    applyDefaultHierarchyTemplate()

    android {
        namespace = "build.raft.webcontainer"
        compileSdk = 37
        minSdk = 21
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.core.ktx)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

tasks.named<Test>("desktopTest") {
    systemProperty("java.awt.headless", "true")
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
