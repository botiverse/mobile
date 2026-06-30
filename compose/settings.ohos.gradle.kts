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
    val ktorOhosVersion =
        System.getenv("SLOCK_KTOR_OHOS_VERSION")
            ?.takeIf { it.isNotBlank() }
            ?: "2.3.7-slock-ohos-001"
    fun String?.enabledByDefault(defaultValue: Boolean): Boolean =
        when (this?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            null, "" -> defaultValue
            else -> defaultValue
        }
    val enableKtorOhosFork =
        System.getenv("SLOCK_ENABLE_KTOR_OHOS_FORK")
            .enabledByDefault(
                providers.gradleProperty("slock.enableKtorOhosFork").orNull
                    .enabledByDefault(true)
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
    val ktorOhosModules =
        listOf(
            "ktor-io",
            "ktor-utils",
            "ktor-http",
            "ktor-http-cio",
            "ktor-network",
            "ktor-network-tls",
            "ktor-events",
            "ktor-websockets",
            "ktor-websocket-serialization",
            "ktor-serialization",
            "ktor-serialization-kotlinx",
            "ktor-serialization-kotlinx-json",
            "ktor-client-core",
            "ktor-client-content-negotiation",
            "ktor-client-cio"
        )
    val needsKtorOhos =
        enableKtorOhosFork &&
            ktorOhosModules.any { module ->
                !mavenArtifactExists("io/ktor", module, ktorOhosVersion) ||
                    !mavenArtifactExists("io/ktor", "${module}-ohosarm64", ktorOhosVersion)
            }

    if (needsStately) {
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

    if (needsSqliter) {
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

    if (needsSqlDelight) {
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

    if (needsKtorOhos) {
        runChecked(
            slockRoot,
            "scripts/prepare_ktor_ohos_fork.sh",
            "--publish"
        )
    }

    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        githubPackagesKuiklyBase()
        mavenLocal()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
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
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
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
