import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("maven-publish")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    id("app.cash.sqldelight")
    id("com.tencent.kuiklybase.resource.generator")
}

val generatedTmmResourceDir = layout.buildDirectory.dir("generated/tmm-res")
val generatedTmmCommonSourceDir = layout.buildDirectory.dir("generated/tmm-res-common/commonMain")
val generatedSlockDatetimeConfigDir = layout.buildDirectory.dir("generated/slock-datetime/commonMain/kotlin")
val commonTmmResourceInputDir = layout.projectDirectory.dir("src/commonMain/resources/MR")
val slockKuiklyBaseDatetimeEnabled =
    providers.gradleProperty("slock.kuiklyBaseDatetime.enabled")
        .orElse(providers.environmentVariable("SLOCK_KUIKLYBASE_DATETIME_ENABLED"))
        .map { value ->
            value.trim().lowercase() !in setOf("0", "false", "no", "off")
        }
        .orElse(true)
val generateSlockDatetimeBuildConfig by tasks.registering {
    val outputFile =
        generatedSlockDatetimeConfigDir.map {
            it.file("build/raft/app/data/SlockDatetimeBuildConfig.kt")
        }
    inputs.property("kuiklyBaseDatetimeEnabled", slockKuiklyBaseDatetimeEnabled)
    outputs.file(outputFile)
    doLast {
        val enabled = slockKuiklyBaseDatetimeEnabled.get()
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                package build.raft.app.data

                internal const val SlockKuiklyBaseDatetimeEnabledByBuild: Boolean = $enabled
                """.trimIndent() + "\n"
            )
        }
    }
}
val pinnedNetworkCurlCaBundle =
    layout.projectDirectory.file("src/commonMain/assets/network/cacert-2026-05-14.pem")
val pinnedNetworkCurlCaSha256 =
    "86a1f3366afac7c6f8ae9f3c779ac221129328c43f0ab2b8817eb2f362a5025c"
val slockVisualFixturesEnabled =
    providers.gradleProperty("slockVisualFixtures")
        .orElse(providers.environmentVariable("SLOCK_VISUAL_FIXTURES"))
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)
val prepareTmmCommonResources by tasks.registering(Sync::class) {
    from(commonTmmResourceInputDir)
    into(generatedTmmResourceDir.map { it.dir("commonMain/res") })
}
val stageTmmCommonSources by tasks.registering(Sync::class) {
    dependsOn("generateMRcommonMain")
    outputs.upToDateWhen { false }
    from(generatedTmmResourceDir.map { it.dir("commonMain/src") })
    into(generatedTmmCommonSourceDir.map { it.dir("src") })
}

multiplatformResources {
    multiplatformResourcesPackage = "build.raft.app.resources"
    multiplatformResourcesPrefix = "slock_"
    multiplatformResourcesClassName = "MR"
}

kotlin {
    // kmmresource generates MR as expect/actual classes; Kotlin 2.x marks
    // expect/actual CLASSES (not functions) Beta and warns once per generated
    // declaration, flooding CI logs. This is the officially suggested
    // suppression (KT-61573) — zero behavior change.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // :core declarations that are public only for :shared (see SlockCoreInternalApi).
        optIn.add("build.raft.app.SlockCoreInternalApi")
    }

    androidTarget {
        compilerOptions {
            // Coil 3 ships JVM 11 bytecode with inline functions :shared calls.
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "15.0"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "shared"
            // Kotlin 2.1.21 builds an unbounded full-program call graph for this
            // optimization and exhausts the release runner heap. Keeping the
            // initializer calls is semantically conservative and bounds linking.
            freeCompilerArgs =
                freeCompilerArgs +
                    getCommonCompilerArgs() +
                    "-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase"
            isStatic = true
            license = "MIT"
            // Compose Multiplatform pages: Swift/ObjC reach SlockCmpIos (view controller factory,
            // routing, bridge/navigator protocols). Not transitive: :core stays unexported.
            export(project(":ui-cmp"))
        }
        extraSpecAttributes["libraries"] = "['c++', 'sqlite3']"
        extraSpecAttributes["resources"] = "['src/commonMain/assets/**', 'src/commonMain/resources/MR/assets/**']"
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/kuikly-compose-markdown/src/commonMain/kotlin"))
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/commonMain/kotlin"))
            kotlin.srcDir(generatedTmmCommonSourceDir.map { it.dir("src") })
            kotlin.srcDir(generatedSlockDatetimeConfigDir)
            if (slockVisualFixturesEnabled) {
                kotlin.srcDir("src/visualFixtures/kotlin")
                // Visual fixture DATA follows the gated host into commonMain, so the visual
                // @Page can read the fixtures it renders. When the flag is off it goes to
                // commonTest instead (see below) — exactly one source set per build, so the
                // same declarations are never compiled twice.
                kotlin.srcDir("src/visualFixtureData/kotlin")
            } else {
                // Empty RaftVisualFixturePages (the generated one lives in src/visualFixtures).
                kotlin.srcDir("src/visualFixturesOff/kotlin")
            }
            dependencies {
                // UI-free foundation (models, network base, JSON helpers, HostCapabilities, store
                // dispatcher). Not exported to the iOS framework: Swift uses none of it yet.
                api(project(":core"))
                // CMP switch (Phase A): :shared's UI renders on upstream Compose Multiplatform
                // through the :ui-cmp hosts (SlockCmpRoot). The Kuikly compose fork, Kuikly core
                // (Pager/Module/nvi JSON) and core-render-android are gone; their remaining API
                // surface lives in the build.raft.app.compat package (TODO(cmp-switch) shims).
                api(project(":ui-cmp"))
                implementation(project(":designsystem"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation("org.jetbrains.compose.ui:ui-backhandler:${libs.versions.composePlugin.get()}")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                // Coil 3.2.0 is the last line built for Kotlin 2.1 / CMP 1.8.0 (3.3+ needs Kotlin 2.2).
                implementation("io.coil-kt.coil3:coil-compose:3.2.0")
                // CMP switch: SVG decoding for the Raft image loader (assets://*.svg icons, inline
                // SVG, svg avatars) — the Kuikly adapters decoded SVG natively.
                implementation("io.coil-kt.coil3:coil-svg:3.2.0")

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                // Common platform-safe monitor used by the synchronous shell-store cache.
                implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
                implementation("app.cash.sqldelight:runtime:2.1.0")
                implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.5")
                implementation("build.raft.kuiklybase:datetime:0.1.0-raft.0")
                // Unified network infra (task #12): all platforms' HTTP goes
                // through NetworkKMM behind the SlockHttpExecutor seam.
                implementation("com.tencent.kuiklybase:network:0.1.0-raft.47")
            }
        }
        val commonTest by getting {
            if (!slockVisualFixturesEnabled) {
                // Production builds must ship zero fixture code, but fixture-contract unit tests
                // still have to compile on the ordinary unit gate, which passes no flag. Wiring the
                // data here (and only here) keeps both true without a second gated test source set.
                kotlin.srcDir("src/visualFixtureData/kotlin")
            }
            dependencies {
                implementation(kotlin("test"))
                implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.5")
                // Virtual-time coroutine testing (runTest/advanceTimeBy) — the
                // required default for new async tests; see AGENTS.md unit-test
                // rules (task #18). Version tracks kotlinx-coroutines-core above.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
        val androidMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/jvmMain/kotlin"))
            dependencies {
                implementation("app.cash.sqldelight:android-driver:2.1.0")
                // CMP switch: RaftSandboxWebView (document-start scripts, web message listener).
                implementation(libs.androidx.webkit)
                implementation("build.raft.mermaid:mermaid-core:0.1.11")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.11")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.11")
                // TODO(cmp-switch): mermaid-kuikly rendered through Kuikly CanvasView; needs a CMP renderer.
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
                implementation("org.robolectric:robolectric:4.14.1")
                // Native scroll arbitration tests exercise the renderer’s compile-only host APIs.
                implementation(libs.androidx.recyclerview)
                implementation(libs.androidx.core.ktx)
                implementation("build.raft.mermaid:mermaid-core:0.1.11")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.11")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.11")
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.junit)
                implementation(libs.androidx.espresso.core)
            }
        }

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/nativeMain/kotlin"))
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.1.0")
                implementation("build.raft.mermaid:mermaid-core:0.1.11")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.11")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.11")
            }
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

group = "build.raft.app"
version = System.getenv("kuiklyBizVersion") ?: "1.0.0"

publishing {
    repositories {
        mavenLocal()
        maven {
            credentials {
                username = System.getenv("mavenUserName") ?: ""
                password = System.getenv("mavenPassword") ?: ""
            }
            rootProject.properties["mavenUrl"]?.toString()?.let { url = uri(it) }
        }
    }
}

sqldelight {
    databases {
        create("SlockDatabase") {
            packageName.set("build.raft.app.shared.data.local")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

fun org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet.removeGeneratedTmmPluginCommonSourceDir() {
    kotlin.setSrcDirs(
        kotlin.srcDirs.filterNot { sourceDir ->
            sourceDir.path.replace(File.separatorChar, '/')
                .endsWith("/build/generated/tmm-res/commonMain/src")
        }
    )
}

afterEvaluate {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>("kotlin") {
        sourceSets.named("commonMain").configure {
            removeGeneratedTmmPluginCommonSourceDir()
        }
    }
}

tasks.matching { it.name == "compileDebugKotlinAndroid" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRandroidMain")
}

tasks.matching { it.name == "compileReleaseKotlinAndroid" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRandroidMain")
}

// The afterEvaluate setSrcDirs above drops the SQLDelight source dir's task dependency; the
// Kuikly KSP tasks used to carry it. Kotlin compilations now depend on the generator directly.
tasks.matching { task ->
    task.name.startsWith("compile") && task.name.contains("Kotlin") && !task.name.contains("Test")
}.configureEach {
    dependsOn("generateCommonMainSlockDatabaseInterface")
}

tasks.matching { it.name == "kspDebugKotlinAndroid" || it.name == "kspReleaseKotlinAndroid" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRandroidMain", "generateCommonMainSlockDatabaseInterface")
}

if (slockVisualFixturesEnabled) {
    tasks.matching { task ->
        task.name in setOf(
            "bundleLibRuntimeToJarDebug",
            "bundleLibRuntimeToDirDebug"
        )
    }.configureEach {
        outputs.upToDateWhen { false }
    }
}

tasks.matching { it.name.startsWith("kspKotlinIos") }.configureEach {
    val generatedResourcesTask =
        when (name) {
            "kspKotlinIosArm64" -> "generateMRiosArm64Main"
            "kspKotlinIosSimulatorArm64" -> "generateMRiosSimulatorArm64Main"
            "kspKotlinIosX64" -> "generateMRiosX64Main"
            else -> null
        }
    if (generatedResourcesTask != null) {
        dependsOn(stageTmmCommonSources, generatedResourcesTask, "generateCommonMainSlockDatabaseInterface")
    }
}

tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
    dependsOn(stageTmmCommonSources)
}

tasks.matching { it.name == "generateMRcommonMain" }.configureEach {
    dependsOn(prepareTmmCommonResources)
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateSlockDatetimeBuildConfig)
}

tasks.matching { it.name == "generateMRandroidMain" }.configureEach {
    mustRunAfter(stageTmmCommonSources)
}

tasks.matching { task ->
    task.name.startsWith("process") && task.name.endsWith("NavigationResources")
}.configureEach {
    dependsOn("generateMRandroidMain")
}

gradle.taskGraph.whenReady {
    tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
        // The bundled TMM resource plugin attaches PackResourcesToKLibAction after task
        // configuration. It expects file-backed KLIBs, while Kotlin 2.1 produces directory
        // KLIBs here, so leave Kotlin compilation intact and drop only the late wrapper.
        actions.removeIf { action ->
            action.javaClass.name == "org.gradle.api.internal.AbstractTask\$TaskActionWrapper"
        }
    }
}

android {
    namespace = "build.raft.app.shared"
    // Upstream Compose 1.8 (CMP 1.8.2) requires compileSdk 35, same as :ui-cmp.
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = 21
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        named("main") {
            assets.srcDirs("src/commonMain/assets")
        }
        named("test") {
            resources.srcDir("src/commonMain/sqldelight/databases")
        }
        named("androidTest") {
            assets.srcDir("src/commonMain/sqldelight/databases")
        }
    }
}

val verifyNoProductionVisualPages by tasks.registering {
    val productionSourceDirs =
        listOf(
            layout.projectDirectory.dir("src/commonMain/kotlin"),
            layout.projectDirectory.dir("src/androidMain/kotlin"),
            rootProject.layout.projectDirectory.dir("androidApp/src/main/java")
        )
    inputs.files(productionSourceDirs)

    doLast {
        val visualPagePatterns =
            listOf(
                Regex("""@Page\s*\(\s*[A-Za-z0-9_]*_VISUAL_PAGE\b"""),
                Regex("""@Page\s*\(\s*"[^"]*_visual"""")
            )
        val violations =
            productionSourceDirs
                .flatMap { sourceDir ->
                    sourceDir.asFile.walkTopDown()
                        .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
                        .flatMap { file ->
                            file.readLines().mapIndexedNotNull { index, line ->
                                if (visualPagePatterns.any { pattern -> pattern.containsMatchIn(line) }) {
                                    "${file.relativeTo(rootProject.projectDir)}:${index + 1}: ${line.trim()}"
                                } else {
                                    null
                                }
                            }
                        }
                }
        if (violations.isNotEmpty()) {
            error(
                "Visual fixture @Page registrations must live under " +
                    "compose/shared/src/visualFixtures/kotlin:\n" +
                    violations.joinToString("\n")
            )
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyNoProductionVisualPages)
}

val verifyNetworkCurlCaBundle by tasks.registering {
    inputs.file(pinnedNetworkCurlCaBundle)
    doLast {
        val file = pinnedNetworkCurlCaBundle.asFile
        check(file.isFile) { "Missing pinned NetworkKMM CA bundle: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(file.readBytes()).joinToString("") { byte: Byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == pinnedNetworkCurlCaSha256) {
            "Pinned NetworkKMM CA bundle hash mismatch: expected=$pinnedNetworkCurlCaSha256 actual=$actual"
        }
    }
}

val verifyNetworkTransportClientWiring by tasks.registering {
    val clientSources =
        listOf(
            layout.projectDirectory.file("src/commonMain/kotlin/build/raft/app/network/NetworkKmmSlockHttpExecutor.kt"),
            layout.projectDirectory.file("src/commonMain/kotlin/build/raft/app/network/SharedBinaryDownloader.kt"),
            layout.projectDirectory.file("src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadCoordinator.kt")
        )
    inputs.files(clientSources)
    doLast {
        clientSources.forEach { source ->
            val text = source.asFile.readText()
            check("slockNetworkClientConfig(" in text) {
                "${source.asFile.name} must use the app-global NetworkKMM selector/diagnostics helper"
            }
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyNetworkCurlCaBundle, verifyNetworkTransportClientWiring)
}

tasks.matching { task ->
    task.name == "preBuild" ||
        task.name.startsWith("compileKotlinIos")
}.configureEach {
    dependsOn(verifyNetworkCurlCaBundle)
}

fun getCommonCompilerArgs(): List<String> {
    return listOf(
        "-Xallocator=std"
    )
}

fun getLinkerArgs(): List<String> {
    return listOf()
}
