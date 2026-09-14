pluginManagement {
    val slockRoot = settingsDir.parentFile
    val mavenLocal = File(System.getProperty("user.home"), ".m2/repository")

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
    val needsSqlDelight =
        !mavenArtifactExists("app/cash/sqldelight", "app.cash.sqldelight.gradle.plugin", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "gradle-plugin", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "core", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "migrations", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "sqlite-3-18-dialect", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "runtime", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "runtime-ohosarm64", sqldelightVersion) ||
            !mavenArtifactExists("app/cash/sqldelight", "native-driver-ohosarm64", sqldelightVersion)
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
