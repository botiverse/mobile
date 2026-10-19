import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :core — the UI-free foundation shared by Kuikly pages (:shared) and future Compose Multiplatform
 * pages: models, network base, realtime protocol pieces, kotlinx JSON helpers, HostCapabilities and
 * the store dispatcher. It must never depend on Tencent Kuikly (`com.tencent.kuikly.*` /
 * `com.tencent.kuikly-open`); verifyCoreHasNoKuikly enforces that on every build.
 *
 * KuiklyBase libraries that do not depend on the Kuikly UI runtime (NetworkKMM, datetime,
 * resource-core) are allowed.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // :core declarations that are public only for :shared (see SlockCoreInternalApi).
        optIn.add("build.raft.app.SlockCoreInternalApi")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
                implementation("build.raft.kuiklybase:datetime:0.1.0-raft.0")
                implementation("com.tencent.kuiklybase:network:0.1.0-raft.47")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
            iosX64Test.dependsOn(this)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }
    }
}

android {
    namespace = "build.raft.app.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
}

val verifyCoreHasNoKuikly by tasks.registering {
    description = "Fails if :core sources or (transitive) dependencies reference Tencent Kuikly."
    group = "verification"
    val sourceDir = layout.projectDirectory.dir("src")
    inputs.dir(sourceDir)
    // Android compile/runtime classpaths cover every common + android dependency transitively.
    val checkedClasspaths = listOf("debugCompileClasspath", "debugRuntimeClasspath")
    doLast {
        val pattern = Regex("""\bcom\.tencent\.kuikly\.""")
        val sourceViolations =
            sourceDir.asFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (pattern.containsMatchIn(line)) "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}" else null
                    }
                }
                .toList()
        val dependencyViolations =
            checkedClasspaths.flatMap { name ->
                configurations.getByName(name).incoming.resolutionResult.allComponents
                    .mapNotNull { component -> component.moduleVersion }
                    .filter { module -> module.group == "com.tencent.kuikly-open" || module.group.startsWith("com.tencent.kuikly-open.") }
                    .map { module -> "$name: ${module.group}:${module.name}:${module.version}" }
            }
        check(sourceViolations.isEmpty() && dependencyViolations.isEmpty()) {
            ":core must not depend on Kuikly.\n" + (sourceViolations + dependencyViolations).joinToString("\n")
        }
    }
}

tasks.named("check").configure { dependsOn(verifyCoreHasNoKuikly) }

tasks.matching { it.name == "preBuild" || it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn(verifyCoreHasNoKuikly)
}
