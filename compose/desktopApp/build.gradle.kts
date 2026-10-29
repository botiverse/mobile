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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.socket.io.client)
    implementation(libs.gson)
    implementation(libs.okhttp.transport)
    testImplementation(kotlin("test"))
}

// Tests run on the packaged app's JDK 21 runtime: :shared's desktop classes can be JDK 21
// bytecode, which a JDK 17 test JVM cannot load.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// The embedded WebView's native engine bridge (io.github.kdroidfilter:wrywebview, via :shared
// desktopMain) is a frozen upstream release carrying native code, maintained by Lagoon (fork it if it
// needs a fix). This repo has no gradle/verification-metadata.xml, so its jar is pinned here: any
// other bytes fail the build before the app is run, tested or packaged. Bump together with
// libs.versions.toml `wrywebview`.
val wryWebviewSha256 = "513d30357154de4c5494abe4b133729fc1c111331cc18100ec3a9bf338b7947f"
val verifyWryWebviewChecksum by tasks.registering {
    val artifacts = configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
    inputs.files(artifacts.map { all -> all.map { it.file } })
    doLast {
        val wry = artifacts.get().filter { it.id.componentIdentifier.displayName.startsWith("io.github.kdroidfilter:wrywebview:") }
        check(wry.size == 1) { "expected one wrywebview artifact, found ${wry.map { it.id.displayName }}" }
        val bytes = wry.single().file.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha == wryWebviewSha256) { "wrywebview checksum mismatch: $sha (pinned $wryWebviewSha256)" }
    }
}
tasks.matching { it.name in setOf("test", "run", "createDistributable", "createReleaseDistributable", "packageDmg", "packageReleaseDmg") }
    .configureEach { dependsOn(verifyWryWebviewChecksum) }

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
