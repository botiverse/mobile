pluginManagement {
    // D8 8.13.19 (bundled by AGP 8.13.2) truncates a high argument register
    // in MembersAgentProfileTab. Keep AGP; use the last verified stable dexer.
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:8.10.24") }
    }

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsKuiklyBase() {
        maven {
            name = "RaftArtifactsKuiklyBase"
            url = uri("https://maven.artifacts.botiverse.dev")
            content {
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "network.*")
                includeModule(
                    "com.tencent.kuiklybase.network.ohos-runtime",
                    "com.tencent.kuiklybase.network.ohos-runtime.gradle.plugin"
                )
                includeModule(
                    "com.tencent.kuiklybase.resource.generator",
                    "com.tencent.kuiklybase.resource.generator.gradle.plugin"
                )
                includeModule("com.tencent.kuiklybase", "resource-generator")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "resource-core.*")
                includeModuleByRegex("build\\.raft\\.kuiklybase", "datetime.*")
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
        raftArtifactsKuiklyBase()
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
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsKuiklyRelease() {
        exclusiveContent {
            forRepository {
                maven {
                    name = "RaftArtifactsKuiklyRelease"
                    url = uri("https://maven.artifacts.botiverse.dev")
                }
            }
            filter {
                includeGroup("com.tencent.kuikly-open")
                includeGroupByRegex("com\\.tencent\\.kuikly-open\\.compose\\..+")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21-KBA-003")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib-common", "2.0.21-KBA-003")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21-KBA-010")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib-common", "2.0.21-KBA-010")
                includeVersion("org.jetbrains.kotlinx", "atomicfu", "0.23.2-KBA-001")
                includeVersion("org.jetbrains.kotlinx", "atomicfu-ohosarm64", "0.23.2-KBA-001")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.8.0-KBA-002")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-core-ohosarm64", "1.8.0-KBA-002")
            }
        }
    }

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsKuiklyBase() {
        maven {
            name = "RaftArtifactsKuiklyBase"
            url = uri("https://maven.artifacts.botiverse.dev")
            content {
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "network.*")
                includeModule(
                    "com.tencent.kuiklybase.network.ohos-runtime",
                    "com.tencent.kuiklybase.network.ohos-runtime.gradle.plugin"
                )
                includeModule(
                    "com.tencent.kuiklybase.resource.generator",
                    "com.tencent.kuiklybase.resource.generator.gradle.plugin"
                )
                includeModule("com.tencent.kuiklybase", "resource-generator")
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "resource-core.*")
                includeModuleByRegex("build\\.raft\\.kuiklybase", "datetime.*")
                includeModuleByRegex("build\\.raft\\.mermaid", ".*")
            }
        }
    }

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsHands() {
        exclusiveContent {
            forRepository {
                maven {
                    name = "RaftArtifactsHands"
                    url = uri("https://maven.artifacts.botiverse.dev")
                }
            }
            filter {
                includeGroup("build.hands")
            }
        }
    }

    repositories {
        raftArtifactsKuiklyRelease()
        raftArtifactsKuiklyBase()
        raftArtifactsHands()
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
