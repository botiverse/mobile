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
    testImplementation(kotlin("test"))
    // NavDisplay host tests (runComposeUiTest).
    testImplementation(libs.cmp.ui.test)
}

// Tests run on the packaged app's JDK 21 runtime: :shared's desktop classes can be JDK 21
// bytecode, which a JDK 17 test JVM cannot load.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// macOS requires MAJOR.MINOR.PATCH with MAJOR > 0 for the bundle version.
val raftDesktopVersion = providers.gradleProperty("raft.desktop.version").orElse("1.0.0")

compose.desktop {
    application {
        mainClass = "build.raft.app.desktop.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.name=Raft")
        // macOS swipe events (RaftDesktopInputDiagnostics.installSwipes): the JDK's gesture API
        // lives in a package java.desktop does not export.
        jvmArgs += listOf("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
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
