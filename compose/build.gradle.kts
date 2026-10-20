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
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.5").apply(false)
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
