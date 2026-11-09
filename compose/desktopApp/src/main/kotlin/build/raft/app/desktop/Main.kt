package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
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
    // Then the log file and crash recording (~/Library/Logs/Raft), before anything else can fail.
    RaftDesktopCrashReporting.install()
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
    // macOS Hide (Cmd-H) puts the pages in the background like minimizing; losing focus does not.
    RaftDesktopAppVisibility.install()
    // What is on screen is read only while the window is in use (focused, input in the last 3 min).
    RaftDesktopInUse.install()
    // Pasted images: retired by the composer after send / removal; stale ones swept here.
    RaftDesktopComposerTemporaryFiles.install()

    application {
        val navigator = RaftDesktopNavigator()
        val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
        // Default 1200x800: wide enough (>= 840dp) for the rail and split panes. Narrower windows
        // get the phone layout, down to a phone-sized minimum.
        // History shortcuts at the window level, after the content: a focused text field or menu that
        // handles the chord itself keeps it.
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Raft",
            // ⌘K / Ctrl+K before the content: the shell opens search even from a text field.
            onPreviewKeyEvent = { event ->
                raftDesktopSearchShortcut(
                    keyDown = event.type == KeyEventType.KeyDown,
                    key = event.key,
                    meta = event.isMetaPressed,
                    ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    mac = RaftDesktopHistoryInput.isMac,
                ) && navigator.searchShortcut()
            },
            onKeyEvent = { event ->
                raftDesktopHistoryShortcut(
                    keyDown = event.type == KeyEventType.KeyDown,
                    key = event.key,
                    meta = event.isMetaPressed,
                    ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    mac = RaftDesktopHistoryInput.isMac,
                )?.let(navigator::history) ?: false
            },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT) }
            RaftDesktopInUseTracker(window)
            // A composition failure is logged before Compose's error dialog closes the window.
            @OptIn(ExperimentalComposeUiApi::class)
            LaunchedEffect(Unit) { window.exceptionHandler = RaftDesktopCrashReporting.wrapWindowExceptionHandler(window.exceptionHandler) }
            // macOS: the title bar over the app's top bar, traffic lights inside it (web desktop app).
            val titleBar = remember { RaftDesktopTitleBar.install(window) }
            RaftDesktopTitleBarProvider(titleBar, fullscreen = windowState.placement == WindowPlacement.Fullscreen) { hitTest ->
                Box(Modifier.fillMaxSize().then(hitTest)) {
                    RaftDesktopHostLifecycle { RaftDesktopHost(navigator) }
                }
            }
        }
    }
}

/** Window minimum, in AWT (logical) points: a phone-sized window. */
private const val MIN_WINDOW_WIDTH = 360
private const val MIN_WINDOW_HEIGHT = 520
