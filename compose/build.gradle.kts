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
