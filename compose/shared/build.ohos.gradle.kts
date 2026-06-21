plugins {
    kotlin("multiplatform")
    // kotlin("native.cocoapods")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    id("app.cash.sqldelight") version "2.1.0-KBA-001"
    id("com.tencent.kuiklybase.resource.generator")
}

val KEY_PAGE_NAME = "pageName"

multiplatformResources {
    multiplatformResourcesPackage = "ai.slock.compose.resources"
    multiplatformResourcesPrefix = "slock_"
    multiplatformResourcesClassName = "MR"
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
        val sqliteLibDir = ohosSqliteProviderLibDir()
        binaries.sharedLib {
            // Preserve lightweight K/N debug metadata so OHOS crash stacks can be symbolicated.
            freeCompilerArgs += "-Xadd-light-debug=enable"
            linkerOpts("--build-id=sha1")
            linkerOpts("-L${sqliteLibDir.absolutePath}")
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/kuikly-compose-markdown/src/commonMain/kotlin"))
            dependencies {
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:compose:${Version.getKuiklyOhosVersion()}")

                implementation("com.tencent.kuiklybase:markdown-parser:0.7.4-ohos")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-002")
                implementation("app.cash.sqldelight:runtime:2.1.0-KBA-001")
                implementation("com.tencent.kuiklybase:resource-core:0.1.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                api("com.tencent.kuikly-open:core-render-android:${Version.getKuiklyOhosVersion()}")
            }
        }
        val ohosArm64Main by getting {
            dependencies {
                implementation("app.cash.sqldelight:native-driver-ohosarm64:2.1.0-KBA-001")
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

group = "ai.slock.compose"
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
            packageName.set("ai.slock.shared.data.local")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

ksp {
    arg(KEY_PAGE_NAME, getPageName())
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
    namespace = "ai.slock.compose.shared"
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
