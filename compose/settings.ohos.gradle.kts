pluginManagement {
    // The OHOS SQLDelight fork (bytemain/sqldelight, branch slock-2.1.0-ohos) is
    // published to Raft Artifacts as a closed ohosArm64 graph (2.1.0-KBA-003).
    // exclusiveContent makes Raft the only repository Gradle may consult for
    // app.cash.sqldelight here, so neither mavenLocal nor a mirror can shadow it.
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsSqlDelight() {
        exclusiveContent {
            forRepository {
                maven {
                    name = "RaftArtifactsSqlDelight"
                    url = uri("https://maven.artifacts.botiverse.dev")
                }
            }
            filter {
                includeGroup("app.cash.sqldelight")
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
                includeModule("co.touchlab", "sqliter-driver")
                includeModule("co.touchlab", "sqliter-driver-ohosarm64")
                includeModule("co.touchlab", "stately-strict")
                includeModule("co.touchlab", "stately-strict-ohosarm64")
                includeModule("co.touchlab", "stately-concurrency")
                includeModule("co.touchlab", "stately-concurrency-ohosarm64")
            }
        }
    }

    repositories {
        raftArtifactsSqlDelight()
        google()
        gradlePluginPortal()
        mavenCentral()
        raftArtifactsKuiklyBase()
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
                // OHOS SQLDelight fork, 2.1.0-KBA-003: Raft Artifacts is the only
                // allowed source (see the pluginManagement block).
                includeGroup("app.cash.sqldelight")
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
                includeModule("co.touchlab", "sqliter-driver")
                includeModule("co.touchlab", "sqliter-driver-ohosarm64")
                includeModule("co.touchlab", "stately-strict")
                includeModule("co.touchlab", "stately-strict-ohosarm64")
                includeModule("co.touchlab", "stately-concurrency")
                includeModule("co.touchlab", "stately-concurrency-ohosarm64")
            }
        }
    }

    repositories {
        raftArtifactsKuiklyRelease()
        raftArtifactsKuiklyBase()
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
