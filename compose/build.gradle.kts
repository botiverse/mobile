plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    id("dev.icerock.mobile.multiplatform-resources").version("0.26.0-KBA-010").apply(false)
}

buildscript {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
    dependencies {
        classpath(BuildPlugin.kuikly)
    }
}

val slockRoot = layout.projectDirectory.dir("..")

val validateKmpSharedRelease by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates compose/shared SQLDelight Thread cache and compiles the merged Android app."

    commandLine(
        slockRoot.file("scripts/validate_kmp_shared_release.sh").asFile.absolutePath,
    )
}
