pluginManagement {
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
        githubPackagesKuiklyBase()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
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

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.githubPackagesHands() {
        maven {
            name = "GitHubPackagesHands"
            url = uri("https://maven.pkg.github.com/botiverse/hands")
            content {
                includeModuleByRegex("build\\.hands", ".*")
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
        githubPackagesHands()
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

rootProject.name = "compose"
include(":androidApp")
include(":shared")
