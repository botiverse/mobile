import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "build.raft.sprout.compose"
        compileSdk = 37
        minSdk = 23
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
            api(project(":sprout-protocol"))
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
