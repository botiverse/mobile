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
    alias(libs.plugins.google.services) apply false
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.3").apply(false)
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

val slockKuiklyAndroidRuntimeVersion =
    providers.gradleProperty("SLOCK_KUIKLY_ANDROID_RUNTIME_VERSION")
        .orElse(providers.environmentVariable("SLOCK_KUIKLY_ANDROID_RUNTIME_VERSION"))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

if (slockKuiklyAndroidRuntimeVersion != null) {
    val readOnlyResolutionTasks = setOf("dependencyInsight", "dependencies")
    gradle.projectsEvaluated {
        val explicitlyRequestedTaskNames =
            gradle.startParameter.taskNames.mapNotNull { token ->
                if (token.startsWith("--")) return@mapNotNull null
                val taskName = token.substringAfterLast(':')
                val resolvesToTask =
                    if (token.startsWith(":")) {
                        rootProject.tasks.findByPath(token) != null
                    } else {
                        allprojects.any { project -> project.tasks.findByName(taskName) != null }
                    }
                taskName.takeIf { resolvesToTask }
            }
        val nonAlphaRequestedTasks =
            explicitlyRequestedTaskNames.filterNot { taskName ->
                taskName.contains("alpha", ignoreCase = true) ||
                    taskName in readOnlyResolutionTasks
            }
        if (nonAlphaRequestedTasks.isNotEmpty()) {
            throw GradleException(
                "SLOCK_KUIKLY_ANDROID_RUNTIME_VERSION is alpha-only. " +
                    "Remove it before running non-alpha tasks: ${nonAlphaRequestedTasks.joinToString()}"
            )
        }
    }
    val kuiklyAndroidRuntimeModules =
        setOf(
            "core",
            "core-android",
            "core-annotations",
            "core-annotations-jvm",
            "core-ksp",
            "compose",
            "compose-android",
            "core-render-android"
        )
    allprojects {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (
                    requested.group == "com.tencent.kuikly-open" &&
                    requested.name in kuiklyAndroidRuntimeModules
                ) {
                    useVersion(slockKuiklyAndroidRuntimeVersion)
                    because("Use the explicitly requested Kuikly Android runtime for an isolated alpha build.")
                }
            }
        }
    }
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
