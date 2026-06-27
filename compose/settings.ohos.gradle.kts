pluginManagement {
    val slockRoot = settingsDir.parentFile
    val mavenLocal = File(System.getProperty("user.home"), ".m2/repository")

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
            ?: "3.3.3-slock-ohos-001"
    val enableKtorOhosFork =
        System.getenv("SLOCK_ENABLE_KTOR_OHOS_FORK") == "1" ||
            providers.gradleProperty("slock.enableKtorOhosFork").orNull.toBoolean()
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
            "ktor-events",
            "ktor-sse",
            "ktor-websockets",
            "ktor-websocket-serialization",
            "ktor-serialization",
            "ktor-serialization-kotlinx",
            "ktor-serialization-kotlinx-json",
            "ktor-client-core",
            "ktor-client-content-negotiation",
            "ktor-client-cio",
            "ktor-client-json",
            "ktor-client-logging"
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
        mavenLocal()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-releases/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

dependencyResolutionManagement {
    repositories {
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
