pluginManagement {
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.githubPackagesKuiklyBase() {
        maven {
            name = "GitHubPackagesKuiklyBase"
            url = uri("https://maven.pkg.github.com/bytemain/KuiklyBase-components")
            content {
                includeModule("com.tencent.kuiklybase.resource.generator", "com.tencent.kuiklybase.resource.generator.gradle.plugin")
                includeModule("com.tencent.kuiklybase", "resource-generator")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "resource-core.*")
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
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.githubPackagesKuiklyBase() {
        maven {
            name = "GitHubPackagesKuiklyBase"
            url = uri("https://maven.pkg.github.com/bytemain/KuiklyBase-components")
            content {
                includeModule("com.tencent.kuiklybase", "resource-core")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "resource-core.*")
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
    }

    repositories {
        githubPackagesKuiklyBase()
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
include(":miniApp")
