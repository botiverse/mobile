import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/*
 * :desktopApp — Raft for desktop (JVM, packaged for macOS as a .dmg).
 *
 * The UI is :shared's Compose Multiplatform pages rendered through :ui-cmp's RaftCmpRoot, as on
 * Android and iOS; this module is the host: window, page stack, bridge methods, storage paths and
 * the realtime host (the shared client over :realtime-socket). See README.md (signing / notarization hooks for DevOps).
 */
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui-cmp"))
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.lifecycle.viewmodel.compose)
    implementation(libs.cmp.lifecycle.runtime.compose)
    // NavDisplay over :shared's page stack (build.raft.app.nav). Android and iOS keep their
    // Activity / view-controller hosts until they move to the same back stack.
    implementation(libs.cmp.navigation3.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // System open / save panels (RaftDesktopFileDialogs).
    implementation(libs.filekit.dialogs)
    // The window: Nucleus's Tao backend (nucleusApplication + DecoratedWindow, Main.kt), no AWT
    // window. Its Dispatchers.Main is the Tao (macOS main) thread and wins over coroutines-swing's.
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.decorated.window.tao)
    testImplementation(kotlin("test"))
    // RaftDesktopSqliteDriverTest (the shared desktop driver over SQLDelight's JDBC driver).
    testImplementation(libs.sqldelight.sqlite.driver)
    // RaftDesktopImageLoaderTest (the shared loader's desktop decode cap).
    testImplementation(libs.coil.compose)
    // NavDisplay host tests (runComposeUiTest).
    testImplementation(libs.cmp.ui.test)
    // Dispatchers.setMain for those tests (RaftDesktopComposeUiTestMain).
    testImplementation(libs.kotlinx.coroutines.test)
}

// Tests run on the packaged app's JDK 21 runtime: :shared's desktop classes can be JDK 21
// bytecode, which a JDK 17 test JVM cannot load.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// Desktop-only third-party jars that drive native code are pinned by SHA-256: this repo has no
// gradle/verification-metadata.xml, so any other bytes fail the build before the app is run,
// tested or packaged. Bump each together with its libs.versions.toml version.
// - io.github.vinceglb:filekit-*: the system open / save panels (macOS through JNA); filekit-core
//   is also ComposeMediaPlayer's file type, resolved to FileKit's version.
// - io.github.kdroidfilter:composemediaplayer-jvm (via :shared desktopMain): video playback; its jar
//   carries the JNI bridge to AVFoundation (libNativeVideoPlayer.dylib, macOS arm64 / x86_64).
// - dev.nucleusframework:composewebview-jvm (via :shared desktopMain): the embedded web view's JNI
//   bridge to WKWebView (libcompose_webview_macos.dylib).
// - dev.nucleusframework:nucleus.* (Nucleus 2.5.18, the Tao window): decorated-window-tao carries
//   the window and event loop (libnucleus_tao*.dylib); core-runtime extracts and loads every Nucleus
//   native; decorated-window-core, darkmode-detector, energy-manager, graalvm-runtime, menu-macos and
//   spellcheck carry their own macOS dylibs, linux-hidpi a Linux one. All arrive with
//   nucleus-application / decorated-window-tao.
val pinnedDesktopJarSha256 =
    mapOf(
        "io.github.vinceglb:filekit-dialogs-jvm" to "1c2afa8001a9cc5f136c25a53856573c65334cdd7d2352a6c8da8bd91a983557",
        "io.github.vinceglb:filekit-core-jvm" to "d35a1c44b47b6566712e8f6e7340778c3bb6f49b61496152d9bc64125387e992",
        "io.github.kdroidfilter:composemediaplayer-jvm" to "ef8f7ab9a6fcb8a56052453f217432b978477ae15db03cccdc271354deaf5c24",
        "dev.nucleusframework:composewebview-jvm" to "abddd6bdfa5561a12a9af8aced16ce5d90ffced472e0e7f36b96ac7dff0070b2",
        "dev.nucleusframework:nucleus.decorated-window-tao" to "762357b549391fe08220dafb79f103a1f6700f175bd6b419e4f51166acb3c5fe",
        "dev.nucleusframework:nucleus.decorated-window-core" to "1fa63077a113d757a0b9d4a5f5b1a752417952e49a2bee16996fc46bde3bc129",
        "dev.nucleusframework:nucleus.core-runtime" to "0fd2edf2d7b8ae327b4798aabf7ed808a94d3fd4004181a1753bcd9115dbdf88",
        "dev.nucleusframework:nucleus.darkmode-detector" to "3ecbcc50d1572bad807bb83211d04086dd9ee6ecde0f17c59d89db7b07258007",
        "dev.nucleusframework:nucleus.energy-manager" to "3cdc20f8ab6c77d1d15363581e0f2d3cff5b63f95f2168006bc51df4eb91ab04",
        "dev.nucleusframework:nucleus.graalvm-runtime" to "fcef2b963d8d3023d4b2044fe45e0039dd9589838c228573ade46b71ac691528",
        "dev.nucleusframework:nucleus.linux-hidpi" to "f7b3b13044d391ae369fdf934798d7a340f7ccf5cdfb3f9a843f13a1172830a4",
        "dev.nucleusframework:nucleus.menu-macos" to "ea95361817a79c0146ad27314746c73e10976c333776ce4e5ec68eaeab3e84a7",
        "dev.nucleusframework:nucleus.spellcheck" to "2d802d35146ccbdecb56fd79e54a12b2286c42232bbca948fef6d833bcab9fad",
    )
val verifyPinnedDesktopJars by tasks.registering {
    val artifacts = configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
    inputs.files(artifacts.map { all -> all.map { it.file } })
    doLast {
        pinnedDesktopJarSha256.forEach { (module, pinned) ->
            val jars = artifacts.get().filter { it.id.componentIdentifier.displayName.startsWith("$module:") }
            check(jars.size == 1) { "expected one $module artifact, found ${jars.map { it.id.displayName }}" }
            val bytes = jars.single().file.readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(sha == pinned) { "$module checksum mismatch: $sha (pinned $pinned)" }
        }
    }
}
tasks.matching { it.name in setOf("test", "run", "createDistributable", "createReleaseDistributable", "packageDmg", "packageReleaseDmg") }
    .configureEach { dependsOn(verifyPinnedDesktopJars) }

// macOS requires MAJOR.MINOR.PATCH with MAJOR > 0 for the bundle version.
val raftDesktopVersion = providers.gradleProperty("raft.desktop.version").orElse("1.0.0")

compose.desktop {
    application {
        mainClass = "build.raft.app.desktop.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.name=Raft")
        // Skiko calls System.gc() every 30 s while frames are drawn (skiko.gc.auto, to release native
        // Skia memory); on G1 that is a stop-the-world full collection of the whole heap, which the
        // freeze's sample caught (VM_G1CollectFull). Run those as concurrent cycles instead.
        jvmArgs += listOf("-XX:+ExplicitGCInvokesConcurrent")
        // The Tao window sends a trackpad's two-finger scroll to Compose as Pan events by default;
        // the app's own pointer handlers (hover cards closing on scroll, the image editor's wheel
        // zoom) and its tuned list scrolling expect the wheel-shaped Scroll events the AWT window
        // sent. This Nucleus switch keeps them (TaoSceneScrollRouter, nucleus 2.5.18).
        jvmArgs += listOf("-Dnucleus.tao.trackpadPanEvents=false")
        nativeDistributions {
            // macOS dmg only for now (Linux packages would need their own data-dir and URL handler story).
            targetFormats(TargetFormat.Dmg)
            packageName = "Raft"
            packageVersion = raftDesktopVersion.get()
            description = "Raft"
            vendor = "Raft"
            // java.sql: SQLDelight's JDBC SQLite driver; jdk.unsupported: OkHttp / Skiko;
            // java.management + jdk.management: the garbage-collection log lines (RaftDesktopCrashReporting).
            modules("java.sql", "jdk.unsupported", "java.naming", "java.management", "jdk.management")
            macOS {
                // Not the iOS app's build.raft.app: on Apple Silicon the iOS app can be installed too.
                bundleID = "build.raft.app.desktop"
                appCategory = "public.app-category.productivity"
                // Browser sign-in returns to raft://login/callback (an app-login return URI the
                // server allows); macOS hands it to the app's open-URI handler. The Electron "Raft
                // Desktop" app registers `raft` too and macOS gives a scheme to one app, so the two
                // don't sign in side by side (artin 10-27: Electron uninstalled, `raft-mac` too ugly).
                // Keep in sync with RaftDesktopLoginCallbacks.SCHEME.
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>CFBundleURLTypes</key>
                        <array>
                          <dict>
                            <key>CFBundleURLName</key>
                            <string>build.raft.app.desktop.login</string>
                            <key>CFBundleURLSchemes</key>
                            <array><string>raft</string></array>
                          </dict>
                        </array>
                        """.trimIndent()
                }
                // TODO(desktop): add an .icns app icon (iconFile).
                // Signing and notarization run on macOS CI (DevOps): pass
                // -Pcompose.desktop.mac.sign=true plus the identity / notarization properties
                // documented in README.md.
            }
        }
    }
}
