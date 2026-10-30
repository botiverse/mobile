import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/*
 * :desktopApp — Raft for desktop (JVM, packaged for macOS as a .dmg).
 *
 * The UI is :shared's Compose Multiplatform pages rendered through :ui-cmp's RaftCmpRoot, as on
 * Android and iOS; this module is the host: window, page stack, bridge methods, storage paths and
 * the Socket.IO realtime client. See README.md (signing / notarization hooks for DevOps).
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
    // The page back stack (NavDisplay + a scene strategy). Desktop only: Android and iOS keep their
    // Activity / view-controller hosts until they move to the same back stack.
    implementation(libs.cmp.navigation3.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.socket.io.client)
    implementation(libs.gson)
    implementation(libs.okhttp.transport)
    // System open / save panels (RaftDesktopFileDialogs).
    implementation(libs.filekit.dialogs)
    testImplementation(kotlin("test"))
    // NavDisplay host tests (runComposeUiTest).
    testImplementation(libs.cmp.ui.test)
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
val pinnedDesktopJarSha256 =
    mapOf(
        "io.github.kdroidfilter:wrywebview" to "513d30357154de4c5494abe4b133729fc1c111331cc18100ec3a9bf338b7947f",
        "io.github.vinceglb:filekit-dialogs-jvm" to "1c2afa8001a9cc5f136c25a53856573c65334cdd7d2352a6c8da8bd91a983557",
        "io.github.vinceglb:filekit-core-jvm" to "d35a1c44b47b6566712e8f6e7340778c3bb6f49b61496152d9bc64125387e992",
        "io.github.kdroidfilter:composemediaplayer-jvm" to "ef8f7ab9a6fcb8a56052453f217432b978477ae15db03cccdc271354deaf5c24",
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
        nativeDistributions {
            // macOS dmg only for now (Linux packages would need their own data-dir and URL handler story).
            targetFormats(TargetFormat.Dmg)
            packageName = "Raft"
            packageVersion = raftDesktopVersion.get()
            description = "Raft"
            vendor = "Raft"
            // java.sql: SQLDelight's JDBC SQLite driver; jdk.unsupported: OkHttp / Skiko.
            modules("java.sql", "jdk.unsupported", "java.naming")
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
