import build.raft.ci.registerNoKuiklyGuard
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :ui-cmp — app UI on official Compose Multiplatform (see the CMP migration assessment, §3.2).
 *
 * Holds the CMP page registry and router split (which page names render on CMP instead of Kuikly),
 * the page root (`RaftCmpRoot`: theme, page args, host), the bridge-wire HostCapabilities adapter
 * and the CMP pages themselves. Hosts: androidApp `RaftCmpActivity`; iOS `RaftCmpIos` (exported
 * through the :shared framework, the app's only Kotlin framework).
 *
 * Depends on :core (data layer, HostCapabilities) and :designsystem. It must never depend on Kuikly;
 * verifyUiCmpHasNoKuikly enforces that on every build.
 */
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    // gradle.properties turns the default hierarchy off for :shared; this module uses the standard
    // one (commonMain -> iosMain -> ios*Main), like :designsystem.
    applyDefaultHierarchyTemplate()

    // Compose Multiplatform 1.12 Android artifacts are JVM 11 bytecode with inline functions.
    // AGP 9 Android-KMP library plugin (com.android.kotlin.multiplatform.library): the Android
    // target and its settings live here; there is no top-level `android {}` block.
    android {
        namespace = "build.raft.app.cmp"
        compileSdk = 37
        minSdk = 21
        // Compose resources (fonts, ...) ship as Android resources/assets.
        androidResources { enable = true }
        // Runs commonTest on the JVM as before (formerly testDebugUnitTest).
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop (JVM) for :desktopApp.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // HostCapabilities is part of this module's public API (RaftCmpHost).
            api(project(":core"))
            implementation(project(":designsystem"))
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Fails the build if :ui-cmp sources or its (transitive) Android/iOS dependency graph reference Kuikly.
registerNoKuiklyGuard("verifyUiCmpHasNoKuikly")

compose.resources {
    packageOfResClass = "build.raft.app.cmp.resources"
    publicResClass = false
}

/*
 * iOS: copy the Compose resources (fonts, the app strings and files, ...) of every CMP module into
 * the app bundle.
 *
 * compose/gradle.properties sets compose.ios.resources.sync=false (the CocoaPods sync would run for
 * :shared's Kuikly framework too), so nothing ships `composeResources` to iOS on its own. The
 * runtime reads `<App>.app/compose-resources/composeResources/<packageOfResClass>/...`; this task
 * lays the prepared (XML values already converted) resources out the same way. Called from the
 * Podfile script phase "Copy Compose resources to app" with
 * -Praft.cmp.resources.dir="$BUILT_PRODUCTS_DIR/$CONTENTS_FOLDER_PATH/compose-resources".
 */
val cmpComposeResourceModules =
    mapOf(
        ":designsystem" to "build.raft.designsystem.resources",
        ":ui-cmp" to "build.raft.app.cmp.resources",
        ":shared" to "build.raft.app.resources",
    )

val syncComposeResourcesForXcodeApp by tasks.registering(Sync::class) {
    description = "Copies CMP compose resources into the iOS app bundle (see comment above)."
    val destination = providers.gradleProperty("raft.cmp.resources.dir")
    cmpComposeResourceModules.forEach { (modulePath, resourcePackage) ->
        val module = project(modulePath)
        dependsOn("$modulePath:prepareComposeResourcesTaskForCommonMain")
        from(module.layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources")) {
            into("composeResources/$resourcePackage")
        }
    }
    into(destination.map { file(it) }.orElse(provider { error("Pass -Praft.cmp.resources.dir=<App>.app/compose-resources") }))
}
