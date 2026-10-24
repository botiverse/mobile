import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core — the UI-free foundation shared by Kuikly pages (:shared) and future Compose Multiplatform
 * pages: models, network base, realtime protocol pieces, kotlinx JSON helpers, HostCapabilities and
 * the store dispatcher. It must never depend on Tencent Kuikly (`com.tencent.kuikly.*` /
 * `com.tencent.kuikly-open`); scripts/ci/test_kuikly_raft_consumer_contract.py enforces that in CI.
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

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.atomicfu)
                implementation("build.raft.kuiklybase:datetime:0.1.0-raft.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
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
