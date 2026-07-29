pluginManagement {
    val slockRoot = settingsDir.parentFile
    val mavenLocal = File(System.getProperty("user.home"), ".m2/repository")

    fun githubPackagesCredentials(): Pair<String, String> {
        val localProperties =
            listOf(
                File(settingsDir, "github-packages.properties"),
                File(settingsDir.parentFile, "github-packages.properties")
            ).firstOrNull { it.isFile }
                ?.let { propertiesFile ->
                    java.util.Properties().apply {
                        propertiesFile.inputStream().use(::load)
                    }
                }

        fun property(name: String): String? =
            localProperties?.getProperty(name)?.takeIf { it.isNotBlank() }

        val username =
            property("githubPackagesUsername")
                ?: property("gpr.user")
                ?: providers.gradleProperty("githubPackagesUsername").orNull
                ?: providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_PACKAGES_USERNAME")
                ?: System.getenv("GITHUB_ACTOR")
                ?: ""
        val token =
            property("githubPackagesToken")
                ?: property("gpr.key")
                ?: providers.gradleProperty("githubPackagesToken").orNull
                ?: providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_PACKAGES_TOKEN")
                ?: System.getenv("GITHUB_TOKEN")
                ?: ""
        return username to token
    }

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.githubPackagesKuiklyBase() {
        maven {
            name = "GitHubPackagesKuiklyBase"
            url = uri("https://maven.pkg.github.com/bytemain/KuiklyBase-components")
            content {
                includeModule(
                    "com.tencent.kuiklybase.resource.generator",
                    "com.tencent.kuiklybase.resource.generator.gradle.plugin"
                )
                includeModule("com.tencent.kuiklybase", "resource-generator")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "resource-core.*")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "network.*")
                includeModule(
                    "com.tencent.kuiklybase.network.ohos-runtime",
                    "com.tencent.kuiklybase.network.ohos-runtime.gradle.plugin"
                )
            }
            credentials {
                val (user, token) = githubPackagesCredentials()
                username = user
                password = token
            }
        }
    }

    fun mavenArtifactExists(groupPath: String, artifactId: String, version: String): Boolean =
        File(mavenLocal, "$groupPath/$artifactId/$version/$artifactId-$version.pom").isFile ||
            File(mavenLocal, "$groupPath/$artifactId/$version/$artifactId-$version.module").isFile

    fun runChecked(workingDir: File, vararg command: String) {
        println("prepare OHOS dependency: ${command.joinToString(" ")}")
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
            "OHOS dependency preparation failed (${command.joinToString(" ")}), exit code $result"
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

    val sqldelightVersion = "2.1.0-KBA-001"
    val sqliterVersion = "1.3.3-KBA-001"
    fun String?.enabledByDefault(defaultValue: Boolean): Boolean =
        when (this?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            null, "" -> defaultValue
            else -> defaultValue
        }
    val skipOhosLocalDependencyPrep =
        System.getenv("SLOCK_SKIP_OHOS_LOCAL_DEP_PREP")
            .enabledByDefault(
                providers.gradleProperty("slock.skipOhosLocalDependencyPrep").orNull
                    .enabledByDefault(false)
            )
    val needsStately =
        !mavenArtifactExists("co/touchlab", "stately-strict", sqldelightVersion) ||
            !mavenArtifactExists("co/touchlab", "stately-strict-ohosarm64", sqldelightVersion) ||
            !mavenArtifactExists("co/touchlab", "stately-concurrency", sqldelightVersion) ||
            !mavenArtifactExists("co/touchlab", "stately-concurrency-ohosarm64", sqldelightVersion)
    val needsSqliter =
        !mavenArtifactExists("co/touchlab", "sqliter-driver", sqliterVersion) ||
            !mavenArtifactExists("co/touchlab", "sqliter-driver-ohosarm64", sqliterVersion)
    val needsSqlDelight =
        !mavenArtifactExists("app/cash/sqldelight", "app.cash.sqldelight.gradle.plugin", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "gradle-plugin", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "core", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "migrations", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "sqlite-3-18-dialect", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "runtime", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "runtime-ohosarm64", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "native-driver-ohosarm64", sqldelightVersion)
    if (!skipOhosLocalDependencyPrep && needsStately) {
        ensureSubmodule("third_party/stately")
        runChecked(
            File(slockRoot, "third_party/stately"),
            "./gradlew",
            "--no-daemon",
            "--console=plain",
            ":stately-strict:publishKotlinMultiplatformPublicationToMavenLocal",
            ":stately-strict:publishOhosArm64PublicationToMavenLocal",
            ":stately-concurrency:publishKotlinMultiplatformPublicationToMavenLocal",
            ":stately-concurrency:publishOhosArm64PublicationToMavenLocal"
        )
    }

    if (!skipOhosLocalDependencyPrep && needsSqliter) {
        ensureSubmodule("third_party/sqliter")
        runChecked(
            File(slockRoot, "third_party/sqliter"),
            "./gradlew",
            "--no-daemon",
            "--console=plain",
            ":sqliter-driver:publishKotlinMultiplatformPublicationToMavenLocal",
            ":sqliter-driver:publishOhosArm64PublicationToMavenLocal"
        )
    }

    if (!skipOhosLocalDependencyPrep && needsSqlDelight) {
        ensureSubmodule("third_party/sqldelight")
        runChecked(
            File(slockRoot, "third_party/sqldelight"),
            "./gradlew",
            "--no-daemon",
            "--console=plain",
            ":sqldelight-compiler:environment:publishMavenPublicationToMavenLocal",
            ":sqldelight-compiler:dialect:publishMavenPublicationToMavenLocal",
            ":sqldelight-compiler:publishMavenPublicationToMavenLocal",
            ":sqlite-migrations:environment:publishMavenPublicationToMavenLocal",
            ":sqlite-migrations:publishMavenPublicationToMavenLocal",
            ":dialects:sqlite-3-18:publishMavenPublicationToMavenLocal",
            ":sqldelight-gradle-plugin:publishToMavenLocal",
            ":runtime:publishKotlinMultiplatformPublicationToMavenLocal",
            ":runtime:publishOhosArm64PublicationToMavenLocal",
            ":drivers:native-driver:publishOhosArm64PublicationToMavenLocal"
        )
    }

    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        githubPackagesKuiklyBase()
        mavenLocal()
        // eazytec 404s the KBA artifacts it was meant to mirror (verified: both
        // the kotlin-native-prebuilt .module and its -linux-x86_64.tar.gz return
        // 404). Because it sits ahead of the tencent mirror, Gradle's split
        // module-vs-artifact resolution stalls on it instead of falling through
        // to tencent, which serves both. Drop it; tencent is the real source.
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

dependencyResolutionManagement {
    fun githubPackagesCredentials(): Pair<String, String> {
        val localProperties =
            listOf(
                File(settingsDir, "github-packages.properties"),
                File(settingsDir.parentFile, "github-packages.properties")
            ).firstOrNull { it.isFile }
                ?.let { propertiesFile ->
                    java.util.Properties().apply {
                        propertiesFile.inputStream().use(::load)
                    }
                }

        fun property(name: String): String? =
            localProperties?.getProperty(name)?.takeIf { it.isNotBlank() }

        val username =
            property("githubPackagesUsername")
                ?: property("gpr.user")
                ?: providers.gradleProperty("githubPackagesUsername").orNull
                ?: providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_PACKAGES_USERNAME")
                ?: System.getenv("GITHUB_ACTOR")
                ?: ""
        val token =
            property("githubPackagesToken")
                ?: property("gpr.key")
                ?: providers.gradleProperty("githubPackagesToken").orNull
                ?: providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_PACKAGES_TOKEN")
                ?: System.getenv("GITHUB_TOKEN")
                ?: ""
        return username to token
    }

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.githubPackagesKuiklyBase() {
        maven {
            name = "GitHubPackagesKuiklyBase"
            url = uri("https://maven.pkg.github.com/bytemain/KuiklyBase-components")
            content {
                includeModule("com.tencent.kuiklybase", "resource-core")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "resource-core.*")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "network.*")
                includeModuleByRegex("build\\.raft\\.kuiklybase", "datetime.*")
                includeModule(
                    "com.tencent.kuiklybase.network.ohos-runtime",
                    "com.tencent.kuiklybase.network.ohos-runtime.gradle.plugin"
                )
            }
            credentials {
                val (user, token) = githubPackagesCredentials()
                username = user
                password = token
            }
        }
    }

    repositories {
        githubPackagesKuiklyBase()
        google()
        mavenCentral()
        mavenLocal()
        // See the pluginManagement block: eazytec 404s the KBA artifacts and
        // stalls Gradle's resolution ahead of tencent, which actually serves
        // them. Drop it.
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

rootProject.name = "compose"

val buildFileName = "build.ohos.gradle.kts"
rootProject.buildFileName = buildFileName

include(":shared")
project(":shared").buildFileName = buildFileName
include(":ohosBridge")
project(":ohosBridge").buildFileName = buildFileName
