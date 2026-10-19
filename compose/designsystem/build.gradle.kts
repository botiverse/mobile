import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Raft design system on official Compose Multiplatform (raft-ui 0.8.0).
// Standalone: no Kuikly, no :shared dependency. See README.md.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    // gradle.properties turns the default hierarchy off for :shared; this module
    // uses the standard one (commonMain -> iosMain -> ios*Main, ...).
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Desktop JVM exists only to render components offscreen (skiko) for the
    // screenshot tests; nothing ships on it.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(kotlin("test-junit"))
            }
        }
    }
}

compose.resources {
    packageOfResClass = "build.raft.designsystem.resources"
    publicResClass = false
    generateResClass = always
}

android {
    namespace = "build.raft.designsystem"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Screenshot tests (src/desktopTest): verify against the committed goldens by default;
// `-Praft.screenshot.record=true` rewrites them instead. See README.md.
val screenshotGoldenDir = layout.projectDirectory.dir("screenshots")
val screenshotRecord =
    providers.gradleProperty("raft.screenshot.record").map { it.equals("true", ignoreCase = true) }.orElse(false)

tasks.named<Test>("desktopTest") {
    val record = screenshotRecord.get()
    inputs.files(fileTree(screenshotGoldenDir)).withPropertyName("screenshotGoldens")
    inputs.property("screenshotRecord", record)
    if (record) outputs.upToDateWhen { false }
    systemProperty("raft.screenshot.record", record.toString())
    systemProperty("raft.screenshot.goldenDir", screenshotGoldenDir.asFile.absolutePath)
    systemProperty(
        "raft.screenshot.reportDir",
        layout.buildDirectory.dir("reports/screenshots").get().asFile.absolutePath
    )
    systemProperty("java.awt.headless", "true")
}
