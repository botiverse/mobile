plugins {
    kotlin("multiplatform")
    // kotlin("native.cocoapods")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    id("app.cash.sqldelight") version "2.1.0-KBA-003"
    id("com.tencent.kuiklybase.resource.generator")
    id("com.tencent.kuiklybase.knoi.plugin")
}

val KEY_PAGE_NAME = "pageName"
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
val prepareTmmCommonResources by tasks.registering(Sync::class) {
    from(commonTmmResourceInputDir)
    into(generatedTmmResourceDir.map { it.dir("commonMain/res") })
}
val stageTmmCommonSources by tasks.registering(Sync::class) {
    dependsOn("generateMRcommonMain")
    from(generatedTmmResourceDir.map { it.dir("commonMain/src") })
    into(generatedTmmCommonSourceDir.map { it.dir("src") })
}

multiplatformResources {
    multiplatformResourcesPackage = "build.raft.app.resources"
    multiplatformResourcesPrefix = "slock_"
    multiplatformResourcesClassName = "MR"
}

knoi {
    ignoreTypeAssert = true
    moduleName = "slockshared"
    tsGenDir = layout.buildDirectory.dir("generated/knoi-ts").get().asFile.absolutePath
}

fun ohosSqliteProviderLibDir(): File {
    providers.environmentVariable("SLOCK_OHOS_SQLITE_LIB_DIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return file(it) }
    providers.environmentVariable("SLOCK_OHOS_SQLITE_WORKDIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return file("$it/ohos-arm64/lib") }
    providers.environmentVariable("SLOCK_THIRD_PARTY_WORKDIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return file("$it/ohos-sqlite/ohos-arm64/lib") }
    return rootProject.file("../third_party/.workdirs/ohos-sqlite/ohos-arm64/lib")
}

fun String.enabledByDefault(defaultValue: Boolean): Boolean =
    when (trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        "" -> defaultValue
        else -> defaultValue
    }

val prepareOhosSqliteProvider by tasks.registering(Exec::class) {
    val providerScript = rootProject.file("../scripts/prepare_ohos_sqlite_provider.sh")
    val sqliteLibDir = ohosSqliteProviderLibDir()
    val sqliteLib = sqliteLibDir.resolve("libsqlite3.a")

    description = "Builds the pinned OHOS arm64 sqlite3 static library for SQLiter."
    group = "build setup"
    commandLine(providerScript.absolutePath)
    inputs.file(providerScript)
    outputs.file(sqliteLib)
}

kotlin {
    // Mirrors build.gradle.kts: suppress the per-declaration Beta warning for
    // the kmmresource-generated expect/actual MR classes (KT-61573).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
        publishLibraryVariants("release")
    }

    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    // cocoapods {
    //     summary = "Some description for the Shared Module"
    //     homepage = "Link to the Shared Module homepage"
    //     version = "1.0"
    //     ios.deploymentTarget = "14.1"
    //     podfile = project.file("../iosApp/Podfile")
    //     framework {
    //         baseName = "shared"
    //         freeCompilerArgs = freeCompilerArgs + getCommonCompilerArgs()
    //         isStatic = true
    //         license = "MIT"
    //     }
    // }

    ohosArm64 {
        val mmkvWrapperLibDir = project.file("src/libs/arm64-v8a")
        val main by compilations.getting
        main.cinterops.create("slockMmkv") {
            definitionFile = file("src/nativeInterop/cinterop/slock_mmkv.def")
            includeDirs(file("src/nativeInterop/cinterop"))
            extraOpts("-libraryPath", mmkvWrapperLibDir.absolutePath)
        }
        main.cinterops.create("slockIcuGrapheme") {
            definitionFile = file("src/nativeInterop/cinterop/slock_icu_grapheme.def")
            includeDirs(file("src/nativeInterop/cinterop"))
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/kuikly-compose-markdown/src/commonMain/kotlin"))
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/commonMain/kotlin"))
            kotlin.srcDir(generatedTmmCommonSourceDir.map { it.dir("src") })
            kotlin.srcDir(generatedSlockDatetimeConfigDir)
            dependencies {
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:compose:${Version.getKuiklyOhosVersion()}")

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-002")
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.2-KBA-001")
                implementation("app.cash.sqldelight:runtime:2.1.0-KBA-003")
                implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.5")
                implementation("build.raft.kuiklybase:datetime:0.1.0-raft.0-ohos")
                // Unified network infra (task #12): all platforms' HTTP goes
                // through NetworkKMM behind the SlockHttpExecutor seam.
                implementation("com.tencent.kuiklybase:network:0.1.0-raft.47-ohos")
            }
        }
        val commonTest by getting {
            // The OHOS leg builds from THIS file (settings.ohos.gradle.kts sets buildFileName), so
            // the visual fixture data wiring in build.gradle.kts does not apply here and has to be
            // repeated. This build has no slockVisualFixtures flag and never compiles the gated
            // visual host, so the data belongs in commonTest unconditionally: it keeps
            // fixture-contract tests compiling for compileTestKotlinOhosArm64 while the OHOS
            // production klib stays free of fixture code. Because the flag is not read here at all,
            // passing -PslockVisualFixtures=true to this leg changes nothing.
            //
            // SOURCE-SET CHANGES MUST BE MADE IN BOTH FILES: this one and
            // compose/shared/build.gradle.kts. Editing only one silently skips a platform — that is
            // exactly how the task #189 spike burned three CI rounds.
            kotlin.srcDir("src/visualFixtureData/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/jvmMain/kotlin"))
            kotlin.srcDir(generatedTmmResourceDir.map { it.dir("androidMain/src") })
            dependencies {
                api("com.tencent.kuikly-open:core-render-android:${Version.getKuiklyVersion()}")
            }
        }
        val ohosArm64Main by getting {
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/nativeMain/kotlin"))
            kotlin.srcDir(generatedTmmResourceDir.map { it.dir("ohosArm64Main/src") })
            dependencies {
                implementation("app.cash.sqldelight:native-driver-ohosarm64:2.1.0-KBA-003")
                implementation("build.raft.mermaid:mermaid-core:0.1.11-ohos")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.11-ohos")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.11-ohos")
                implementation("build.raft.mermaid:mermaid-kuikly:0.1.11-ohos")
            }
        }
        val ohosArm64Test by getting {
            dependencies {
                implementation("build.raft.mermaid:mermaid-core:0.1.11-ohos")
                implementation("build.raft.mermaid:mermaid-layout-api:0.1.11-ohos")
                implementation("build.raft.mermaid:mermaid-layout-simple:0.1.11-ohos")
            }
        }

        // val iosX64Main by getting
        // val iosArm64Main by getting
        // val iosSimulatorArm64Main by getting
        // val iosMain by creating {
        //     dependsOn(commonMain)
        //     iosX64Main.dependsOn(this)
        //     iosArm64Main.dependsOn(this)
        //     iosSimulatorArm64Main.dependsOn(this)
        // }
        // val iosX64Test by getting
        // val iosArm64Test by getting
        // val iosSimulatorArm64Test by getting
        // val iosTest by creating {
        //     dependsOn(commonTest)
        //     iosX64Test.dependsOn(this)
        //     iosArm64Test.dependsOn(this)
        //     iosSimulatorArm64Test.dependsOn(this)
        // }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    if (name.endsWith("OhosArm64")) {
        dependsOn(prepareOhosSqliteProvider)
    }
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateSlockDatetimeBuildConfig)
}

group = "build.raft.app"
version = System.getenv("kuiklyBizVersion") ?: "1.0.0"

publishing {
    repositories {
        maven {
            credentials {
                username = System.getenv("mavenUserName") ?: ""
                password = System.getenv("mavenPassword") ?: ""
            }
            rootProject.properties["mavenUr?"]?.toString()?.let { url = uri(it) }
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

ksp {
    arg(KEY_PAGE_NAME, getPageName())
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

tasks.matching { it.name == "compileKotlinOhosArm64" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRohosArm64Main")
}

tasks.matching { it.name == "kspKotlinOhosArm64" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRohosArm64Main", "generateCommonMainSlockDatabaseInterface")
}

tasks.matching { it.name == "generateMRcommonMain" }.configureEach {
    dependsOn(prepareTmmCommonResources)
}

tasks.matching { it.name == "generateMRandroidMain" || it.name == "generateMRohosArm64Main" }.configureEach {
    mustRunAfter(stageTmmCommonSources)
}

dependencies {
    compileOnly("com.tencent.kuikly-open:core-ksp:${Version.getKuiklyOhosVersion()}") {
        add("kspAndroid", this)
        // add("kspIosArm64", this)
        // add("kspIosX64", this)
        // add("kspIosSimulatorArm64", this)
        add("kspOhosArm64", this)
    }
}

android {
    namespace = "build.raft.app.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        targetSdk = 30
    }
    sourceSets {
        named("main") {
            assets.srcDirs("src/commonMain/assets")
        }
    }
}

fun getPageName(): String {
    return (project.properties[KEY_PAGE_NAME] as? String) ?: ""
}

fun getCommonCompilerArgs(): List<String> {
    return listOf(
        "-Xallocator=std"
    )
}

fun getLinkerArgs(): List<String> {
    return listOf()
}
