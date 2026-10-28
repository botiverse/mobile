import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :sync-rules — SPIKE (task #53): the pure message-storage rules (segment algebra, coverage from a
 * page's messageWindow / a context page, legacy-window mirror) with no dependency beyond the Kotlin
 * stdlib, so the SAME source can ship to web as a JS/TS library. Copied from :shared, not moved yet.
 * Native (iOS) targets are declared for the production shape; they are not compiled on agent hosts.
 */
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "build.raft.syncrules"
        compileSdk = 37
        minSdk = 21
        compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    js {
        nodejs()
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions {
            target.set("es2015")
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
