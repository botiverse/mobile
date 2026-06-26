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
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.1").apply(false)
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackagesKuiklyBase"
            url = uri("https://maven.pkg.github.com/bytemain/KuiklyBase-components")
            content {
                includeModule("com.tencent.kuiklybase.resource.generator", "com.tencent.kuiklybase.resource.generator.gradle.plugin")
                includeModule("com.tencent.kuiklybase", "resource-generator")
            }
            credentials {
                username = providers.gradleProperty("githubPackagesUsername").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_PACKAGES_USERNAME")
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: ""
                password = providers.gradleProperty("githubPackagesToken").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_PACKAGES_TOKEN")
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
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
