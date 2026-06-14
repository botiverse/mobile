pluginManagement {
    val slockRoot = settingsDir.parentFile
    val mavenLocal = File(System.getProperty("user.home"), ".m2/repository")

    fun mavenArtifactExists(groupPath: String, artifactId: String, version: String): Boolean =
        File(mavenLocal, "$groupPath/$artifactId/$version/$artifactId-$version.pom").isFile ||
            File(mavenLocal, "$groupPath/$artifactId/$version/$artifactId-$version.module").isFile

    fun runChecked(workingDir: File, vararg command: String) {
        println("prepare shared dependency: ${command.joinToString(" ")}")
        val builder =
            ProcessBuilder(*command)
                .directory(workingDir)
                .inheritIO()
        if (command.firstOrNull() == "git") {
            builder.environment()["GIT_CONFIG_GLOBAL"] =
                System.getenv("SLOCK_GIT_CONFIG_GLOBAL") ?: "/dev/null"
        }
        val result = builder.start().waitFor()
        check(result == 0) {
            "Shared dependency preparation failed (${command.joinToString(" ")}), exit code $result"
        }
    }

    fun ensureSubmodule(path: String) {
        val dir = File(slockRoot, path)
        if (!File(dir, "gradlew").isFile) {
            runChecked(slockRoot, "git", "submodule", "update", "--init", path)
        }
        check(File(dir, "gradlew").isFile) {
            "Missing Gradle wrapper after submodule init: $path"
        }
    }

    val mokoResourcesVersion = "0.26.0-KBA-010"
    val mokoGraphicsVersion = "0.10.1-KBA-010"
    val needsMokoResources =
        !mavenArtifactExists(
            "dev/icerock/mobile/multiplatform-resources",
            "dev.icerock.mobile.multiplatform-resources.gradle.plugin",
            mokoResourcesVersion
        ) ||
            !mavenArtifactExists("dev/icerock/moko", "resources", mokoResourcesVersion) ||
            !mavenArtifactExists("dev/icerock/moko", "resources-android", mokoResourcesVersion) ||
            !mavenArtifactExists("dev/icerock/moko", "resources-generator", mokoResourcesVersion) ||
            !mavenArtifactExists("dev/icerock/moko", "graphics", mokoGraphicsVersion) ||
            !mavenArtifactExists("dev/icerock/moko", "graphics-android", mokoGraphicsVersion) ||
            !mavenArtifactExists("dev/icerock/moko", "graphics-android-debug", mokoGraphicsVersion)

    if (needsMokoResources) {
        ensureSubmodule("third_party/moko-graphics")
        ensureSubmodule("third_party/moko-resources")
        runChecked(
            slockRoot,
            "./scripts/prepare_moko_resources_ohos_kba.sh"
        )
    }

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

rootProject.name = "compose"
include(":androidApp")
include(":shared")
include(":miniApp")
