plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    id("app.cash.sqldelight")
}

// App strings and bundled files: Compose Multiplatform resources (src/commonMain/composeResources),
// generated as `build.raft.app.resources.Res`. Public so :shared and host modules read `Res` without
// repackaging; read strings through the Raft string layer (`build.raft.app.resources.stringResource` /
// `StringDesc`), which follows the in-app language.
compose.resources {
    packageOfResClass = "build.raft.app.resources"
    publicResClass = true
    generateResClass = always
}

kotlin {
    // Kotlin 2.x marks expect/actual CLASSES (not functions) Beta and warns once per
    // declaration; this is the officially suggested suppression (KT-61573).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // :core and :shared-data declarations that are public only for :shared (see RaftCoreInternalApi).
        optIn.add("build.raft.app.RaftCoreInternalApi")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop (JVM, packaged for macOS by :desktopApp). Shares plain-JVM actuals with Android
    // through jvmCommonMain.
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/commonMain/kotlin"))
            dependencies {
                api(project(":core"))
                api(project(":realtime-socket"))
                api(project(":designsystem"))
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.ui)
                api(libs.cmp.lifecycle.viewmodel.compose)
                api(libs.cmp.components.resources)
                api(libs.sqldelight.runtime)
                api(libs.store5)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
                api(libs.atomicfu)
                api(libs.kotlinx.datetime)
                api(libs.ktor.client.core)
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir(rootProject.file("../third_party/markdown-parser/src/jvmMain/kotlin"))
            dependencies {
                api(libs.ktor.client.okhttp)
                api(libs.okhttp.transport)
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                api(libs.sqldelight.android.driver)
                implementation(libs.androidx.core.ktx)
                // Native passkey step-up (RaftNativePasskeyAssertion.android.kt): Credential Manager, and
                // its Play services provider for Google Password Manager passkeys before Android 14.
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                api(libs.sqldelight.sqlite.driver)
                implementation(libs.kotlinx.coroutines.swing)
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
                api(libs.sqldelight.native.driver)
                api(libs.ktor.client.darwin)
            }
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

tasks.matching { task ->
    task.name.startsWith("compile") && task.name.contains("Kotlin") && !task.name.contains("Test")
}.configureEach {
    dependsOn("generateCommonMainRaftDatabaseInterface")
}

android {
    namespace = "build.raft.app.shareddata"
    compileSdk = 37
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = 21
    }
    lint { targetSdk = 34 }
}

// TODO(desktop): commonTest is not yet run on the desktop JVM; desktop is compile-checked through :desktopApp.
tasks.matching { it.name == "desktopTest" || it.name == "compileTestKotlinDesktop" }.configureEach {
    enabled = false
}
