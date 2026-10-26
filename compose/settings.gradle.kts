pluginManagement {
    // The D8 8.10.24 pin (D8 8.13.19 from AGP 8.13.2 truncated a high argument register in
    // MembersAgentProfileTab) is gone: AGP 9.1 cannot dex with an 8.x R8. AGP 9.1.1 ships R8 9.1.
    // R8 9.1.31 (bundled by AGP 9.1.1) and the latest 9.1 patch (9.1.57) both emit
    // unverifiable code when minifying the very large RaftAppShellReadyContent composable: a
    // parallel move overwrites a >255 register holding an object with an int, then
    // move-object reads it -> VerifyError at Home launch. R8 9.5.28 verifies cleanly
    // (`cmd package compile -m verify`). This also replaces D8 for debug/alpha dexing.
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.5.28") }
    }

    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsKuiklyBase() {
        maven {
            name = "RaftArtifactsKuiklyBase"
            url = uri("https://maven.artifacts.botiverse.dev")
            content {
                includeModuleByRegex("com\\.tencent\\.kuiklybase", "network.*")
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
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsKbaMetadata() {
        exclusiveContent {
            forRepository {
                maven {
                    name = "RaftArtifactsKbaMetadata"
                    url = uri("https://maven.artifacts.botiverse.dev")
                }
            }
            filter {
                // Kuikly UI (com.tencent.kuikly-open) left the Gradle build with the CMP
                // switch; this repo now only serves the KBA metadata below.
                // KuiklyBase resource-core 0.1.0-raft.6 / knoi 0.0.4 still declare these
                // KuiklyBase (KBA) versions; conflict resolution upgrades them to the
                // upstream Kotlin/atomicfu pins, but their metadata must stay resolvable.
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21-KBA-003")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib-common", "2.0.21-KBA-003")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21-KBA-010")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib-common", "2.0.21-KBA-010")
                includeVersion("org.jetbrains.kotlinx", "atomicfu", "0.23.2-KBA-001")
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
        raftArtifactsKbaMetadata()
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
include(":core")
include(":shared")
include(":designsystem")
include(":ui-cmp")
include(":desktopApp")
