plugins {
    kotlin("multiplatform")
//    kotlin("native.cocoapods")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("com.tencent.kuikly-open.kuikly")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    id("app.cash.sqldelight")
    id("com.tencent.kuiklybase.resource.generator")
}

val KEY_PAGE_NAME = "pageName"
val generatedTmmResourceDir = layout.buildDirectory.dir("generated/tmm-res")
val generatedTmmCommonSourceDir = layout.buildDirectory.dir("generated/tmm-res-common/commonMain")
val commonTmmResourceInputDir = layout.projectDirectory.dir("src/commonMain/resources/MR")
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
    multiplatformResourcesPackage = "ai.slock.compose.resources"
    multiplatformResourcesPrefix = "slock_"
    multiplatformResourcesClassName = "MR"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
        publishLibraryVariants("release")
    }

//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()

//    cocoapods {
//        summary = "Some description for the Shared Module"
//        homepage = "Link to the Shared Module homepage"
//        version = "1.0"
//        ios.deploymentTarget = "14.1"
//        podfile = project.file("../iosApp/Podfile")
//        framework {
//            baseName = "shared"
//            freeCompilerArgs = freeCompilerArgs + getCommonCompilerArgs()
//            isStatic = true
//            license = "MIT"
//        }
//        extraSpecAttributes["resources"] = "['src/commonMain/assets/**']"
//    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/kuikly-compose-markdown/src/commonMain/kotlin"))
            kotlin.srcDir(generatedTmmCommonSourceDir.map { it.dir("src") })
            dependencies {
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyVersion()}")
                implementation("com.tencent.kuikly-open:compose:${Version.getKuiklyVersion()}")

                implementation("org.jetbrains:markdown:0.7.3")
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("app.cash.sqldelight:runtime:2.1.0")
                implementation("com.tencent.kuiklybase:resource-core:0.1.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:2.3.7")
                implementation("com.tencent.kuiklybase:resource-core:0.1.0")
            }
        }
        val androidMain by getting {
            kotlin.srcDir(generatedTmmResourceDir.map { it.dir("androidMain/src") })
            dependencies {
                api("com.tencent.kuikly-open:core-render-android:${Version.getKuiklyVersion()}")
                implementation("io.ktor:ktor-client-okhttp:2.3.7")
                implementation("app.cash.sqldelight:android-driver:2.1.0")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
            }
        }

//        val iosX64Main by getting
//        val iosArm64Main by getting
//        val iosSimulatorArm64Main by getting
//        val iosMain by creating {
//            dependsOn(commonMain)
//            iosX64Main.dependsOn(this)
//            iosArm64Main.dependsOn(this)
//            iosSimulatorArm64Main.dependsOn(this)
//        }
//        val iosX64Test by getting
//        val iosArm64Test by getting
//        val iosSimulatorArm64Test by getting
//        val iosTest by creating {
//            dependsOn(commonTest)
//            iosX64Test.dependsOn(this)
//            iosArm64Test.dependsOn(this)
//            iosSimulatorArm64Test.dependsOn(this)
//        }
    }
}

group = "ai.slock.compose"
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
            packageName.set("ai.slock.shared.data.local")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

ksp {
    arg(KEY_PAGE_NAME, getPageName())
}

tasks.matching { it.name == "compileDebugKotlinAndroid" || it.name == "compileReleaseKotlinAndroid" }.configureEach {
    dependsOn(stageTmmCommonSources, "generateMRandroidMain")
}

tasks.matching { it.name == "kspDebugKotlinAndroid" || it.name == "kspReleaseKotlinAndroid" }.configureEach {
    dependsOn(stageTmmCommonSources)
}

tasks.matching { it.name == "generateMRcommonMain" }.configureEach {
    dependsOn(prepareTmmCommonResources)
}

tasks.matching { task ->
    task.name.startsWith("process") && task.name.endsWith("NavigationResources")
}.configureEach {
    dependsOn("generateMRandroidMain")
}

dependencies {
    compileOnly("com.tencent.kuikly-open:core-ksp:${Version.getKuiklyVersion()}") {
        add("kspAndroid", this)
//        add("kspIosArm64", this)
//        add("kspIosX64", this)
//        add("kspIosSimulatorArm64", this)
    }
}

android {
    namespace = "ai.slock.compose.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
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
