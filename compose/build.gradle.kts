plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.builtin.kotlin) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.roborazzi) apply false
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

// CMP-10674 (iOS crash on a re-entrant FlushCoroutineDispatcher.flush() while typing): every
// configuration of every project that resolves the Compose UI iOS platform klibs at the patch base
// version gets the patched build instead (third_party/compose-ui-1.12.2/README.md). Kotlin
// Multiplatform reaches these modules through the root `org.jetbrains.compose.ui:ui` module's
// `available-at` redirect; that redirect is an ordinary dependency edge in Gradle's graph, so this
// rule rewrites it like a direct dependency. The root `ui` module and everything else stay on the
// official release. The versions live in gradle.properties (raft.composeUiPatch.*);
// scripts/ci/check_compose_ui_patch_pin.py fails CI once composePlugin moves off the base.
val raftComposeUiPatchBase = providers.gradleProperty("raft.composeUiPatch.base").orNull
val raftComposeUiPatchVersion = providers.gradleProperty("raft.composeUiPatch.version").orNull
if (raftComposeUiPatchBase != null && raftComposeUiPatchVersion != null) {
    val patchedModules = setOf("ui-iosarm64", "ui-iossimulatorarm64")
    allprojects {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.compose.ui" &&
                    requested.name in patchedModules &&
                    requested.version == raftComposeUiPatchBase
                ) {
                    useVersion(raftComposeUiPatchVersion)
                    because("CMP-10674 fix, see third_party/compose-ui-1.12.2/README.md")
                }
            }
        }
    }
}

// Task #32: per-test hang watchdog for JVM unit tests. A hung test must fail
// loud and named (HANG DETECTED + thread dump + fork exit 73) instead of
// burning the job timeout in silence. Config via Gradle JVM -D properties:
// raft.test.watchdog.{timeout.ms,heartbeat.ms,enabled}.
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        addTestListener(build.raft.ci.HangWatchdogTestListener())
    }
}

// The :shared and :androidApp host unit tests (Robolectric) are the longest step of the Android
// CI job. Spread their test classes over up to 3 JVMs: 3 on the 4-vCPU GitHub runner, fewer on
// smaller machines. Override with -Praft.unitTest.maxParallelForks=N (1 = old serial run).
val raftUnitTestForks =
    providers.gradleProperty("raft.unitTest.maxParallelForks").map(String::toInt)
        .getOrElse((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3))
listOf(":shared", ":androidApp").forEach { path ->
    project(path) {
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java)
            .matching { it.name.endsWith("UnitTest") }
            .configureEach { maxParallelForks = raftUnitTestForks }
    }
}
