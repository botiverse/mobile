package build.raft.app.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.raftClearLegacyFontPreference
import build.raft.app.network.installDesktopRaftAppIdentity

/** Desktop entry point: process setup as in RaftApplication (Android), then one window. */
fun main(args: Array<String>) {
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
    RaftDesktopLoginCallbacks.install(args)

    application {
        val navigator = RaftDesktopNavigator(onEmpty = ::exitApplication)
        val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "Raft") {
            RaftDesktopHost(navigator)
        }
    }
}
