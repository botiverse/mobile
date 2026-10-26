import build.raft.ci.registerNoKuiklyGuard
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core — the UI-free foundation shared by Kuikly pages (:shared) and future Compose Multiplatform
 * pages: models, network base, realtime protocol pieces, kotlinx JSON helpers, HostCapabilities and
 * the store dispatcher. It must never depend on Tencent Kuikly (`com.tencent.kuikly.*` /
 * `com.tencent.kuikly-open`); verifyCoreHasNoKuikly enforces that on every build.
 *
 * KuiklyBase libraries that do not depend on the Kuikly UI runtime (datetime,
 * resource-core) are allowed.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // :core declarations that are public only for :shared (see RaftCoreInternalApi).
        optIn.add("build.raft.app.RaftCoreInternalApi")
    }

    // AGP 9 Android-KMP library plugin (com.android.kotlin.multiplatform.library): the Android
    // target and its settings live here; there is no top-level `android {}` block.
    android {
        namespace = "build.raft.app.core"
        compileSdk = 37
        minSdk = 21
        // Runs commonTest on the JVM as before (formerly testDebugUnitTest).
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop (JVM) for :desktopApp; plain-JVM actuals are shared with Android via jvmCommonMain.
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.atomicfu)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
        }
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }
    }
}

// Fails the build if :core sources or its (transitive) Android/iOS dependency graph reference Kuikly.
registerNoKuiklyGuard("verifyCoreHasNoKuikly")

// TODO(desktop): commonTest runs on Android host tests; not yet on the desktop JVM.
tasks.matching { it.name == "desktopTest" || it.name == "compileTestKotlinDesktop" }.configureEach {
    enabled = false
}
