import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Raft design system on official Compose Multiplatform (raft-ui 0.8.0).
// Standalone: no Kuikly, no :shared dependency. See README.md.
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    // Screenshot tests: recordRoborazziDesktop / verifyRoborazziDesktop / compareRoborazziDesktop.
    id("io.github.takahirom.roborazzi")
}

kotlin {
    // gradle.properties turns the default hierarchy off for :shared; this module
    // uses the standard one (commonMain -> iosMain -> ios*Main, ...).
    applyDefaultHierarchyTemplate()

    // Compose Multiplatform 1.12 Android artifacts are JVM 11 bytecode with inline functions.
    // AGP 9 Android-KMP library plugin (com.android.kotlin.multiplatform.library): the Android
    // target and its settings live here; there is no top-level `android {}` block.
    android {
        namespace = "build.raft.designsystem"
        compileSdk = 37
        minSdk = 21
        // Compose resources (fonts, ...) ship as Android resources/assets.
        androidResources { enable = true }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop JVM exists only to render components offscreen (skiko) for the
    // screenshot tests; nothing ships on it.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            // TooltipBox / PlainTooltip only (the icon button's long-press / hover label);
            // everything else is drawn from raft-ui tokens, not Material.
            implementation(libs.cmp.material3)
            implementation(libs.cmp.components.resources)
        }
        // RaftDialogWindowPolicy's Android actual: the dialog window's system bars.
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Virtual time for the located highlight's frame loop (RaftLocatedTest).
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(kotlin("test-junit"))
                // Semantics and input tests (runComposeUiTest).
                implementation(libs.cmp.ui.test)
                // Screenshot compare / record / report (RaftScreenshot); the capture stays ours.
                // Its runtime pins CMP 1.10.3's macOS arm64 desktop natives: the host's come from
                // compose.desktop.currentOs, and ui-test-junit4 is lifted to composePlugin.
                implementation("io.github.takahirom.roborazzi:roborazzi-compose-desktop:${libs.versions.roborazzi.get()}") {
                    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
                }
                // AwtRoboCanvas (runtime-only in roborazzi-compose-desktop).
                implementation(libs.roborazzi.painter)
                implementation(libs.cmp.ui.test.junit4)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "build.raft.designsystem.resources"
    publicResClass = false
    generateResClass = always
}

// Screenshot tests (src/desktopTest), compared and recorded by Roborazzi. See README.md.
// - desktopTest / verifyRoborazziDesktop: verify against the committed goldens.
// - recordRoborazziDesktop: write goldens whose render changed (or are new); others stay as is.
// - compareRoborazziDesktop: write the comparison images without failing.
// Goldens stay at screenshots/<name>.png (RaftScreenshot passes that path to Roborazzi);
// actual and comparison images go to build/reports/screenshots, the HTML report (Roborazzi
// tasks only) to build/reports/roborazzi/desktop.
val screenshotGoldenDir = layout.projectDirectory.dir("screenshots")
val screenshotReportDir = layout.buildDirectory.dir("reports/screenshots")
// A roborazzi.test.* Gradle property picks the mode itself; otherwise a bare desktopTest verifies.
val roborazziModeProperty =
    providers.gradlePropertiesPrefixedBy("roborazzi.test.").map { props -> props.values.any { it == "true" } }

roborazzi {
    compare {
        outputDir.set(screenshotReportDir)
    }
}

tasks.named<Test>("desktopTest") {
    inputs.files(fileTree(screenshotGoldenDir)).withPropertyName("screenshotGoldens")
    // The Roborazzi tasks overwrite this (record / compare / verify) when the test starts.
    if (!roborazziModeProperty.get()) systemProperty("roborazzi.test.verify", "true")
    systemProperty("raft.screenshot.goldenDir", screenshotGoldenDir.asFile.absolutePath)
    systemProperty("raft.screenshot.reportDir", screenshotReportDir.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
