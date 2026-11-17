import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

val generateSproutEmbeddedBundles by tasks.registering {
    val bundlesDir = project.layout.projectDirectory.dir("bundles")
    val outDir = project.layout.buildDirectory.dir("generated/sproutBundles/kotlin")
    inputs.dir(bundlesDir)
    outputs.dir(outDir)

    doLast {
        val compilerJs = bundlesDir.file("compiler.iife.js").asFile.readText(Charsets.UTF_8)
        val guestJs = bundlesDir.file("guest-runtime.js").asFile.readText(Charsets.UTF_8)
        val manifestJson = bundlesDir.file("guest-runtime.manifest.json").asFile.readText(Charsets.UTF_8)

        fun escapeKotlinLiteral(s: String): String = buildString(s.length + 128) {
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '$' -> append("\\$")
                    else -> if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }

        fun emitChunkedBuilder(prefix: String, content: String): String {
            val chunkSize = 14_000
            val chunks = content.chunked(chunkSize)
            val sb = StringBuilder()
            chunks.forEachIndexed { idx, chunk ->
                sb.append("    private fun ").append(prefix).append("Part").append(idx)
                    .append("(): String = \"").append(escapeKotlinLiteral(chunk)).append("\"\n")
            }
            sb.append("    fun ").append(prefix).append("(): String = buildString(").append(content.length).append(") {\n")
            for (idx in chunks.indices) {
                sb.append("        append(").append(prefix).append("Part").append(idx).append("())\n")
            }
            sb.append("    }\n")
            return sb.toString()
        }

        val pkgDir = outDir.get().asFile.resolve("build/raft/sprout/runtime")
        pkgDir.mkdirs()
        pkgDir.resolve("SproutEmbeddedBundles.kt").writeText(
            buildString {
                appendLine("package build.raft.sprout.runtime")
                appendLine()
                appendLine("internal object SproutEmbeddedBundles {")
                append(emitChunkedBuilder("compilerSource", compilerJs))
                appendLine()
                append(emitChunkedBuilder("guestRuntimeSource", guestJs))
                appendLine()
                append(emitChunkedBuilder("guestManifestJson", manifestJson))
                appendLine("}")
            },
            Charsets.UTF_8,
        )
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "build.raft.sprout.runtime"
        compileSdk = 37
        minSdk = 23
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSproutEmbeddedBundles)
            dependencies {
                api(project(":sprout-protocol"))
                implementation("io.github.dokar3:quickjs-kt:1.0.18")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.atomicfu)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
