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
val generatedRaftDatetimeConfigDir = layout.buildDirectory.dir("generated/slock-datetime/commonMain/kotlin")
val commonTmmResourceInputDir = layout.projectDirectory.dir("src/commonMain/resources/MR")
val raftKuiklyBaseDatetimeEnabled =
    providers.gradleProperty("slock.kuiklyBaseDatetime.enabled")
        .orElse(providers.environmentVariable("SLOCK_KUIKLYBASE_DATETIME_ENABLED"))
        .map { value ->
            value.trim().lowercase() !in setOf("0", "false", "no", "off")
        }
        .orElse(true)
val generateRaftDatetimeBuildConfig by tasks.registering {
    val outputFile =
        generatedRaftDatetimeConfigDir.map {
            it.file("build/raft/app/data/RaftDatetimeBuildConfig.kt")
        }
    inputs.property("kuiklyBaseDatetimeEnabled", raftKuiklyBaseDatetimeEnabled)
    outputs.file(outputFile)
    doLast {
        val enabled = raftKuiklyBaseDatetimeEnabled.get()
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                package build.raft.app.data

                internal const val RaftKuiklyBaseDatetimeEnabledByBuild: Boolean = $enabled
                """.trimIndent() + "\n"
            )
        }
    }
}
val raftVisualFixturesEnabled =
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
        // :core declarations that are public only for :shared (see RaftCoreInternalApi).
        optIn.add("build.raft.app.RaftCoreInternalApi")
    }

    androidTarget {
        compilerOptions {
            // Coil 3 ships JVM 11 bytecode with inline functions :shared calls.
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

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
            // Compose Multiplatform pages: Swift/ObjC reach RaftCmpIos (view controller factory,
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
            kotlin.srcDir(generatedRaftDatetimeConfigDir)
            if (raftVisualFixturesEnabled) {
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
                // through the :ui-cmp hosts (RaftCmpRoot). The Kuikly compose fork, Kuikly core
                // (Pager/Module/nvi JSON) and core-render-android are gone; their remaining API
                // surface lives in the build.raft.app.compat package (TODO(cmp-switch) shims).
                api(project(":ui-cmp"))
                implementation(project(":designsystem"))
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.material3)
                implementation(libs.cmp.ui.backhandler)
                implementation(libs.cmp.lifecycle.viewmodel.compose)
                implementation(libs.cmp.lifecycle.runtime.compose)
                // Coil 3.6.x is built against Kotlin 2.4 / CMP foundation 1.12 (compileSdk 37 + AGP 9.1).
                implementation(libs.coil.compose)
                // CMP switch: SVG decoding for the Raft image loader (assets://*.svg icons, inline
                // SVG, svg avatars) — the Kuikly adapters decoded SVG natively.
                implementation(libs.coil.svg)
                // Message math (task #775): KaTeX-style layout in pure Kotlin; the KaTeX fonts
                // come from :designsystem, so the library's optional font module is not used.
                implementation(libs.hrm.latex.renderer)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                // Common platform-safe monitor used by the synchronous shell-store cache.
                implementation(libs.atomicfu)
                implementation("app.cash.sqldelight:runtime:2.1.0")
                implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.6")
                implementation("build.raft.kuiklybase:datetime:0.1.0-raft.0")
                // Unified network infra (task #12): all platforms' HTTP goes
                // through NetworkKMM behind the RaftHttpExecutor seam.
                // App HTTP transport (artin 2026-10-23): Ktor client behind the RaftHttpExecutor seam,
                // OkHttp engine on Android and Darwin (NSURLSession) on iOS. API calls, uploads and
                // downloads all use it; NetworkKMM / curl are gone.
                implementation(libs.ktor.client.core)
                // Mermaid parse + layout (Kotlin Multiplatform, android + ios). The scene is drawn
                // by the library's Compose Multiplatform renderer (mermaid-compose, built against
                // CMP 1.8; the app's newer CMP wins resolution) inside RaftMermaidViewport.
                implementation("build.raft.mermaid:mermaid-core:0.1.14")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.14")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.14")
                implementation("build.raft.mermaid:mermaid-compose:0.1.14")
            }
        }
        val commonTest by getting {
            if (!raftVisualFixturesEnabled) {
                // Production builds must ship zero fixture code, but fixture-contract unit tests
                // still have to compile on the ordinary unit gate, which passes no flag. Wiring the
                // data here (and only here) keeps both true without a second gated test source set.
                kotlin.srcDir("src/visualFixtureData/kotlin")
            }
            dependencies {
                implementation(kotlin("test"))
                implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.6")
                // Virtual-time coroutine testing (runTest/advanceTimeBy) — the
                // required default for new async tests; see AGENTS.md unit-test
                // rules (task #18). Version tracks kotlinx-coroutines-core above.
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/jvmMain/kotlin"))
            dependencies {
                implementation("app.cash.sqldelight:android-driver:2.1.0")
                // RaftVideoPlayer (Media3 PlayerView inside AndroidView; replaces the Kuikly
                // VideoView adapter that lived in androidApp).
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.ui)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                // CMP switch: RaftSandboxWebView (document-start scripts, web message listener).
                implementation(libs.androidx.webkit)
                // Animated GIF / WebP in the image viewer (AnimatedImageDecoder on API 28+, GifDecoder
                // below). Android-only artifact; iOS plays them through Skia's Codec instead. The
                // loader turns Coil's service loader off so this never decodes outside the viewer.
                implementation(libs.coil.gif)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.okhttp.transport)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.okhttp.mockwebserver3)
                implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
                implementation(libs.robolectric)
                // Native scroll arbitration tests exercise the renderer’s compile-only host APIs.
                implementation(libs.androidx.recyclerview)
                implementation(libs.androidx.core.ktx)
                implementation("build.raft.mermaid:mermaid-core:0.1.14")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.14")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.14")
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.junit)
                implementation(libs.androidx.espresso.core)
            }
        }

        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/nativeMain/kotlin"))
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.1.0")
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
            dependencies {
                // Compose UI tests on the simulator (runComposeUiTest), e.g. ActivitySwipeTabPagerIosTest.
                implementation(libs.cmp.ui.test)
            }
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
        create("RaftDatabase") {
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
    dependsOn("generateCommonMainRaftDatabaseInterface")
}

tasks.matching { it.name == "kspDebugKotlinAndroid" || it.name == "kspReleaseKotlinAndroid" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRandroidMain", "generateCommonMainRaftDatabaseInterface")
}

if (raftVisualFixturesEnabled) {
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
            else -> null
        }
    if (generatedResourcesTask != null) {
        dependsOn(stageTmmCommonSources, generatedResourcesTask, "generateCommonMainRaftDatabaseInterface")
    }
}

tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
    dependsOn(stageTmmCommonSources)
}

tasks.matching { it.name == "generateMRcommonMain" }.configureEach {
    dependsOn(prepareTmmCommonResources)
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateRaftDatetimeBuildConfig)
}

tasks.matching { it.name == "generateMRandroidMain" }.configureEach {
    mustRunAfter(stageTmmCommonSources)
}

tasks.matching { task ->
    task.name.startsWith("process") && task.name.endsWith("NavigationResources")
}.configureEach {
    dependsOn("generateMRandroidMain")
}

// The MR generator adds build/generated/tmm-res/androidMain to the Android source sets without a
// task dependency; Gradle 9 rejects every Android variant task (resources, assets, source-set
// path maps, ...) that reads it undeclared.
tasks.matching { task ->
    task.name.contains("Debug") || task.name.contains("Release")
}.configureEach {
    dependsOn("generateMRandroidMain")
}

// Xcode runs Gradle twice per iOS build: the CocoaPods "Build shared" phase
// (:shared:syncFramework) or a CI step links the framework, then the app's
// "[CP-User] Copy kmmresource MR bundle to app" phase runs
// :shared:copyFrameworkResourcesToApp, which depends on the same link task.
// When that second invocation sees the link as out of date (cold ~/.konan
// snapshot, see visual-testing.yml), it relinks the whole release framework a
// second time: ~24 of the ~51 min of the iOS release archive (run 39403554694).
// With RAFT_IOS_REUSE_LINKED_FRAMEWORK=YES (set as an xcodebuild build setting,
// which Xcode exports to script phases) a link task whose framework binary
// already exists is skipped; the resource tasks still run against it. Unset
// (local Xcode / IDE builds) nothing changes.
val raftReuseLinkedIosFramework =
    providers.environmentVariable("RAFT_IOS_REUSE_LINKED_FRAMEWORK")
        .map { it.trim().uppercase() in setOf("YES", "TRUE", "1") }
        .orElse(false)
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink::class.java).configureEach {
    val framework = outputFile
    val reuse = raftReuseLinkedIosFramework
    onlyIf("RAFT_IOS_REUSE_LINKED_FRAMEWORK is set and the framework is already linked") {
        val dir = framework.get()
        !(reuse.get() && File(dir, dir.nameWithoutExtension).isFile)
    }
}

// test.yml's iOS job passes the simulator it picked (UDID) so :shared:iosSimulatorArm64Test does
// not depend on the plugin's default device name existing on the hosted runner image.
providers.gradleProperty("raft.iosSimulatorTestDevice").orNull?.takeIf { it.isNotBlank() }?.let { simulator ->
    tasks.withType(org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest::class.java).configureEach {
        device.set(simulator)
    }
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
    // AndroidX Compose 1.12 (CMP 1.12.x) requires compileSdk 37 (and AGP 9.1).
    compileSdk = 37
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // AGP 9 removed the library-level defaultConfig.targetSdk; it only ever applied to this
    // module's tests and lint, which keep 34.
    testOptions {
        targetSdk = 34
        // Robolectric Compose tests (HomeSidebarWebMetricsTest) render moko strings and fonts.
        unitTests.isIncludeAndroidResources = true
    }
    lint { targetSdk = 34 }
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

fun getCommonCompilerArgs(): List<String> {
    return listOf(
        "-Xallocator=std"
    )
}

fun getLinkerArgs(): List<String> {
    return listOf()
}
