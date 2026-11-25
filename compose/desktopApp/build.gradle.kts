import java.security.MessageDigest
import org.gradle.api.tasks.Sync
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
    // The JetBrains Runtime's API: its macOS custom title bar (RaftDesktopTitleBar). The packaged
    // runtime is JBR (the Gradle daemon JVM, gradle/gradle-daemon-jvm.properties); on any other
    // JDK the API reports itself unavailable and the window takes the apple.awt fallback.
    implementation(libs.jbr.api)
    testImplementation(kotlin("test"))
    // RaftDesktopSqliteDriverTest (the shared desktop driver over SQLDelight's JDBC driver).
    testImplementation(libs.sqldelight.sqlite.driver)
    // RaftDesktopImageLoaderTest (the shared loader's desktop decode cap).
    testImplementation(libs.coil.compose)
    // NavDisplay host tests (runComposeUiTest).
    testImplementation(libs.cmp.ui.test)
    // Desktop WebView container bridge tests (RaftDesktopWebContainerEngineTest).
    testImplementation(project(":web-container"))
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
// - io.github.kdroidfilter:wrywebview (via :shared desktopMain): the embedded WebView's native
//   engine bridge, a frozen upstream release maintained by Lagoon (fork it if it needs a fix).
// - io.github.vinceglb:filekit-*: the system open / save panels (macOS through JNA); filekit-core
//   is also ComposeMediaPlayer's file type, resolved to FileKit's version.
// - io.github.kdroidfilter:composemediaplayer-jvm (via :shared desktopMain): video playback; its jar
//   carries the JNI bridge to AVFoundation (libNativeVideoPlayer.dylib, macOS arm64 / x86_64).
// - org.jetbrains.runtime:jbr-api: binds to the JetBrains Runtime's window decorations (the macOS
//   title bar, RaftDesktopTitleBar).
val pinnedDesktopJarSha256 =
    mapOf(
        "io.github.kdroidfilter:wrywebview" to "513d30357154de4c5494abe4b133729fc1c111331cc18100ec3a9bf338b7947f",
        "io.github.vinceglb:filekit-dialogs-jvm" to "0d499d8d9c5b1de84942547615e4edeca9293e9c88288f5a8b672b58cafa231d",
        "io.github.vinceglb:filekit-core-jvm" to "2ca385693dbb5960fb80e2c5e67f83e79aa8ca9f2d49263976f3026ebf34f916",
        "io.github.kdroidfilter:composemediaplayer-jvm" to "ef8f7ab9a6fcb8a56052453f217432b978477ae15db03cccdc271354deaf5c24",
        "org.jetbrains.runtime:jbr-api" to "7820798e5325dbd515dd2eea035a897e63db3871e1aaaa515c361bde52851425",
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
val raftDesktopBuild =
    providers.gradleProperty("raft.desktop.build").orElse(
        raftDesktopVersion.map { version ->
            val parts = version.trim().split('.')
            val major = parts.getOrNull(0)?.toLongOrNull()
            val minor = parts.getOrNull(1)?.toLongOrNull()
            val patch = parts.getOrNull(2)?.toLongOrNull()
            if (major != null && minor != null && patch != null && major > 0 && minor in 0..99 && patch in 0..99) {
                (major * 10_000_000L + minor * 100_000L + patch * 1_000L).toString()
            } else {
                "0"
            }
        },
    )

val isMacOsBuildHost: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }

val macOsBuildArch: String =
    when (System.getProperty("os.arch").orEmpty().trim().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x86_64"
        else -> System.getProperty("os.arch").orEmpty().trim().lowercase()
    }

val generatedAppResourcesRoot = layout.buildDirectory.dir("generated/raft-app-resources")

// On macOS builds, stage :web-container's compiled native WKWebView bridge inside Compose Desktop's
// appResourcesRootDir so jpackage places it at Raft.app/Contents/app/resources/libraft_webcontainer_macos.dylib
// where desktop-macos-dmg.yml signs and verifies it with `codesign --force --deep`. Linux CI never
// invokes macOS native compilation or staging.
val stageMacOsWebContainerNative by tasks.registering(Sync::class) {
    onlyIf { isMacOsBuildHost }
    if (isMacOsBuildHost) {
        dependsOn(":web-container:compileMacOsWebContainerNative")
        from(
            project(":web-container").layout.buildDirectory.file(
                "generated/macos-native-resources/native/darwin-$macOsBuildArch/libraft_webcontainer_macos.dylib",
            ),
        )
    }
    into(generatedAppResourcesRoot.map { it.dir("macos") })
}

if (isMacOsBuildHost) {
    tasks.matching { it.name == "prepareAppResources" || it.name == "prepareReleaseAppResources" }.configureEach {
        dependsOn(stageMacOsWebContainerNative)
    }
}

compose.desktop {
    application {
        mainClass = "build.raft.app.desktop.MainKt"
        jvmArgs +=
            listOf(
                "-Dapple.awt.application.name=Raft",
                "-Draft.desktop.version=${raftDesktopVersion.get()}",
                "-Draft.desktop.build=${raftDesktopBuild.get()}",
            )
        // Skiko calls System.gc() every 30 s while frames are drawn (skiko.gc.auto, to release native
        // Skia memory); on G1 that is a stop-the-world full collection of the whole heap, which the
        // freeze's sample caught (VM_G1CollectFull). Run those as concurrent cycles instead.
        jvmArgs += listOf("-XX:+ExplicitGCInvokesConcurrent")
        nativeDistributions {
            if (isMacOsBuildHost) {
                appResourcesRootDir.set(generatedAppResourcesRoot)
            }
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
