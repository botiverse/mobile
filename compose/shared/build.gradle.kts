import com.tencent.kuikly.gradle.config.KuiklyConfig

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
}

val KEY_PAGE_NAME = "pageName"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
        publishLibraryVariants("release")
    }

    js(IR) {
        browser {
            webpackTask {
                mainOutputFileName = "nativevue2.js" // 最后输出的名字
            }

            commonWebpackConfig {
                output?.library = null // 不导出全局对象，只导出必要的入口函数
                devtool = "source-map" // 不使用默认的 eval 执行方式构建出 source-map，而是构建单独的 sourceMap 文件
            }
        }
        binaries.executable() //将kotlin.js与kotlin代码打包成一份可直接运行的js文件
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
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:2.3.7")
            }
        }
        val androidMain by getting {
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

dependencies {
    compileOnly("com.tencent.kuikly-open:core-ksp:${Version.getKuiklyVersion()}") {
        add("kspAndroid", this)
//        add("kspIosArm64", this)
//        add("kspIosX64", this)
//        add("kspIosSimulatorArm64", this)
        add("kspJs", this)
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

// Kuikly 插件配置
configure<KuiklyConfig> {
    // JS 产物配置
    js {
        // 构建产物名，与 KMM 插件 webpackTask#mainOutputFileName 一致
        outputName("nativevue2")
        // 可选：分包构建时的页面列表，如果为空则构建全部页面
        // addSplitPage("route","home")
    }
}
