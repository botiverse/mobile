import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :realtime-socket — a Socket.IO v5 / Engine.IO v4 client in common Kotlin over Ktor WebSockets
 * (OkHttp on Android and desktop, Darwin on iOS). UI-free and app-free: it knows the Socket.IO
 * protocol, not Raft's events; the Raft handshake helpers (raftHandshakeAuth, the generated handshake
 * error strings) are the only Raft-specific parts. Design: docs/realtime-kmp-socket-client.md.
 *
 * Tests: commonTest (protocol, scripted fake server, virtual time) runs on the Android host JVM
 * (:realtime-socket:testAndroidHostTest); androidHostTest adds the conformance suite against a real
 * socket.io server (scripts/socketio-interop), which CI starts.
 */
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "build.raft.realtime.socket"
        compileSdk = 37
        minSdk = 21
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
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
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.okhttp.transport)
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
        }
        val androidHostTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
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

// The interop suite reads these (set by CI after starting scripts/socketio-interop/server.mjs). They are
// test inputs too, so a run with the server is never served from a cached run without it.
tasks.withType<Test>().configureEach {
    listOf("RAFT_SOCKETIO_INTEROP_URL", "RAFT_SOCKETIO_INTEROP_RECOVERY_URL").forEach { name ->
        val value = System.getenv(name).orEmpty()
        if (value.isNotEmpty()) environment(name, value)
        inputs.property(name, value)
    }
}

// commonTest runs on the Android host JVM (like :core); the desktop JVM run would only repeat it.
tasks.matching { it.name == "desktopTest" || it.name == "compileTestKotlinDesktop" }.configureEach {
    enabled = false
}
