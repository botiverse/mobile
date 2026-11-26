pluginManagement {
    // The D8 8.10.24 pin (D8 8.13.19 from AGP 8.13.2 truncated a high argument register in
    // MembersAgentProfileTab) is gone: AGP 9.1 cannot dex with an 8.x R8. AGP 9.1.1 ships R8 9.1.
    // R8 9.1.31 (bundled by AGP 9.1.1) and the latest 9.1 patch (9.1.57) both emit
    // unverifiable code when minifying the very large RaftAppShellReadyContent composable: a
    // parallel move overwrites a >255 register holding an object with an int, then
    // move-object reads it -> VerifyError at Home launch. R8 9.5.28 verifies cleanly
    // (`cmd package compile -m verify`). This also replaces D8 for debug/alpha dexing.
    // AGP 9.5.1 bundles R8 9.5.29 (com/android/tools/r8/Version in builder-9.5.1.jar), so the pin
    // can go once a release build is re-verified on device with the bundled R8; until then 9.5.28
    // (the verified one) stays.
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
                includeGroup("build.raft.vcase")
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

    // CMP-10674: the patched Compose UI iOS klibs (third_party/compose-ui-1.12.2/README.md). Only
    // these two modules at the patched version come from here; every other Compose artifact,
    // including ui-iosarm64 / ui-iossimulatorarm64 at any other version, stays on Maven Central.
    // The version is raft.composeUiPatch.version in gradle.properties (root build.gradle.kts
    // pins the two modules to it); without that property this repository is not added.
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsComposeUiPatch() {
        val patchedVersion = providers.gradleProperty("raft.composeUiPatch.version").orNull ?: return
        exclusiveContent {
            forRepository {
                maven {
                    name = "RaftArtifactsComposeUiPatch"
                    url = uri("https://maven.artifacts.botiverse.dev")
                }
            }
            filter {
                includeVersion("org.jetbrains.compose.ui", "ui-iosarm64", patchedVersion)
                includeVersion("org.jetbrains.compose.ui", "ui-iossimulatorarm64", patchedVersion)
            }
        }
    }

    // Android only: the patched androidx Compose foundation AAR, two isAttached guards for the
    // detached-node crash (third_party/compose-foundation-1.12.1/README.md). Only this module at the
    // patched version comes from here; foundation-android at any other version and every other
    // androidx artifact stay on Google Maven. The version is raft.composeFoundationPatch.version in
    // gradle.properties (root build.gradle.kts pins the module to it); without that property this
    // repository is not added.
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftArtifactsComposeFoundationPatch() {
        val patchedVersion = providers.gradleProperty("raft.composeFoundationPatch.version").orNull ?: return
        exclusiveContent {
            forRepository {
                maven {
                    name = "RaftArtifactsComposeFoundationPatch"
                    url = uri("https://maven.artifacts.botiverse.dev")
                }
            }
            filter {
                includeVersion("androidx.compose.foundation", "foundation-android", patchedVersion)
            }
        }
    }

    // LiveKit Android SDK depends on com.github.davidliu:audioswitch (hosted exclusively on JitPack).
    // Scope JitPack strictly to that group so no other dependency resolves through it.
    fun org.gradle.api.artifacts.dsl.RepositoryHandler.raftJitpackAudioSwitch() {
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPackLiveKitAudioSwitch"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.davidliu")
            }
        }
    }

    repositories {
        raftArtifactsKbaMetadata()
        raftArtifactsKuiklyBase()
        raftArtifactsHands()
        raftArtifactsComposeUiPatch()
        raftArtifactsComposeFoundationPatch()
        raftJitpackAudioSwitch()
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

apply(from = "gradle/build-cache-epoch.settings.gradle.kts")

rootProject.name = "compose"
include(":androidApp")
include(":core")
include(":realtime-socket")
include(":shared-data")
include(":shared")
include(":designsystem")
include(":ui-cmp")
include(":web-container")
include(":desktopApp")
