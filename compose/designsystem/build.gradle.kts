import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Raft design system on official Compose Multiplatform (raft-ui 0.8.0).
// Standalone: no Kuikly, no :shared dependency. See README.md.
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
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
            }
        }
    }
}

compose.resources {
    packageOfResClass = "build.raft.designsystem.resources"
    publicResClass = false
    generateResClass = always
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
