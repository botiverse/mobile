package build.raft.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.RaftDesktopComposerTemporaryFiles
import build.raft.app.data.raftClearLegacyFontPreference
import build.raft.app.network.installDesktopRaftAppIdentity
import java.awt.Dimension

/** Desktop entry point: process setup as in RaftApplication (Android), then one window. */
fun main(args: Array<String>) {
    // First: a second launch only forwards its sign-in callback to the running app.
    RaftDesktopLoginCallbacks.install(args)
    val debugBuild = System.getProperty("raft.desktop.debug") == "true"
    RaftCmpRouter.configure(debugBuild = debugBuild)
    RaftPageRegistry.install()
    installDesktopRaftAppIdentity(
        version = System.getProperty("raft.desktop.version") ?: "1.0.0",
        buildNumber = System.getProperty("raft.desktop.build") ?: "0",
        buildType = if (debugBuild) "debug" else "release",
    )
    RaftAppAppearanceRuntime.ensureLoaded()
    raftClearLegacyFontPreference()
    RaftDesktopRealtimeHost.install()
    // Pasted images: retired by the composer after send / removal; stale ones swept here.
    RaftDesktopComposerTemporaryFiles.install()

    application {
        val navigator = RaftDesktopNavigator(onEmpty = ::exitApplication)
        val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
        // Default 1200x800: wide enough (>= 840dp) for the rail and split panes. Narrower windows
        // get the phone layout, down to a phone-sized minimum.
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "Raft") {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT) }
            RaftDesktopHost(navigator)
        }
    }
}

/** Window minimum, in AWT (logical) points: a phone-sized window. */
private const val MIN_WINDOW_WIDTH = 360
private const val MIN_WINDOW_HEIGHT = 520
