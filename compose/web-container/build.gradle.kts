import java.io.File
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :web-container — standalone cross-platform Hybrid WebView Container and Kotlin JSB runtime.
 *
 * Zero internal dependencies on :shared, :core, :ui-cmp, or :designsystem so the module can be
 * tested independently, published as a Maven artifact, or extracted to a standalone repository
 * without refactoring callers. iOS links statically through :shared's existing framework.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    // compose/gradle.properties disables the default hierarchy for :shared; this standalone module
    // uses the standard hierarchy (commonMain -> iosMain -> ios*Main, etc.).
    applyDefaultHierarchyTemplate()

    android {
        namespace = "build.raft.webcontainer"
        compileSdk = 37
        minSdk = 21
        withHostTest {
            isIncludeAndroidResources = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.core.ktx)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
    }
}

val isMacOsBuildHost: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }

val macOsBuildArch: String =
    when (System.getProperty("os.arch").orEmpty().trim().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x86_64"
        else -> System.getProperty("os.arch").orEmpty().trim().lowercase()
    }

val macOsNativeBridgeSource = layout.projectDirectory.file("native/macos/RaftMacWebContainerBridge.m")
val macOsNativeResourcesRoot = layout.buildDirectory.dir("generated/macos-native-resources")
val macOsNativeDylibOutput =
    macOsNativeResourcesRoot.map { root ->
        root.file("native/darwin-$macOsBuildArch/libraft_webcontainer_macos.dylib")
    }

// Builds the thin ARC Objective-C JNI/JAWT WKWebView bridge exclusively on macOS hosts (zero
// macOS toolchain assumption on Linux/Windows CI). Ad-hoc signs the .dylib so Apple Silicon
// tests and dev runs load it cleanly; :desktopApp stages the same binary into Raft.app resources
// where desktop-macos-dmg.yml's `codesign --force --deep` signs and verifies it.
val compileMacOsWebContainerNative by tasks.registering {
    onlyIf { isMacOsBuildHost }
    inputs.file(macOsNativeBridgeSource)
    inputs.property("macOsBuildArch", macOsBuildArch)
    outputs.file(macOsNativeDylibOutput)

    val sourceFileProvider = macOsNativeBridgeSource
    val outputFileProvider = macOsNativeDylibOutput
    val arch = macOsBuildArch
    val jdkHome = File(System.getProperty("java.home"))

    doLast {
        val srcFile = sourceFileProvider.asFile
        val outFile = outputFileProvider.get().asFile
        outFile.parentFile.mkdirs()

        val jniInclude = jdkHome.resolve("include")
        val darwinInclude = jniInclude.resolve("darwin")
        check(jniInclude.resolve("jni.h").isFile && darwinInclude.resolve("jawt_md.h").isFile) {
            "JDK JNI/JAWT headers not found under ${jdkHome.absolutePath}"
        }

        val clangCmd =
            listOf(
                "xcrun",
                "--sdk",
                "macosx",
                "clang",
                "-fobjc-arc",
                "-shared",
                "-fPIC",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-mmacosx-version-min=12.0",
                "-arch",
                arch,
                "-isystem",
                jniInclude.absolutePath,
                "-isystem",
                darwinInclude.absolutePath,
                "-framework",
                "Cocoa",
                "-framework",
                "WebKit",
                "-framework",
                "QuartzCore",
                "-o",
                outFile.absolutePath,
                srcFile.absolutePath,
            )
        val clangProc = ProcessBuilder(clangCmd).inheritIO().start()
        val clangExit = clangProc.waitFor()
        check(clangExit == 0) { "clang failed compiling ${srcFile.name} (exit=$clangExit)" }

        val signCmd = listOf("codesign", "--force", "--sign", "-", outFile.absolutePath)
        val signProc = ProcessBuilder(signCmd).inheritIO().start()
        val signExit = signProc.waitFor()
        check(signExit == 0) { "codesign failed for ${outFile.name} (exit=$signExit)" }
    }
}

if (isMacOsBuildHost) {
    tasks.named<Copy>("desktopProcessResources") {
        dependsOn(compileMacOsWebContainerNative)
        from(macOsNativeResourcesRoot)
    }
}

tasks.named<Test>("desktopTest") {
    systemProperty("java.awt.headless", "true")
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
