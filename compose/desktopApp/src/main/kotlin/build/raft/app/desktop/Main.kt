package build.raft.app.desktop

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import build.raft.app.RaftWindowFocusScope
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.RaftDesktopComposerTemporaryFiles
import build.raft.app.data.raftClearLegacyFontPreference
import build.raft.app.network.installDesktopRaftAppIdentity
import build.raft.designsystem.focus.RaftFocusCoordinator
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.tao.LocalWindowExceptionHandlerFactory

/**
 * Desktop entry point: process setup as in RaftApplication (Android), then one window.
 *
 * The window is Nucleus's Tao backend (no AWT window): the Compose UI thread is the macOS main
 * thread, which AppKit (text input, window resizing) and the embedded web views run on too, so the UI
 * never waits on another thread that waits on it. Nothing before [nucleusApplication] may start AWT
 * (`java.awt.Desktop`, `Toolkit`): AWT and Tao would each set up the macOS application object.
 */
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
    // Pasted images: retired by the composer after send / removal; stale ones swept here.
    RaftDesktopComposerTemporaryFiles.install()

    nucleusApplication(
        backend = NucleusBackend.Tao,
        // RaftDesktopLoginCallbacks is the one second-launch hand-off (above); the launch arguments
        // stay with it, so a sign-in callback among them is taken once.
        enableSingleInstance = false,
    ) {
        // macOS hands a `raft://login/callback` opened while the app runs to the app (open URLs).
        LaunchedEffect(Unit) { onDeepLink { uri -> RaftDesktopLoginCallbacks.acceptDeepLink(uri.toString()) } }
        val navigator = remember { RaftDesktopNavigator() }
        // Default 1200x800: wide enough (>= 840dp) for the rail and split panes. Narrower windows
        // get the phone layout, down to a phone-sized minimum.
        val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
        val windowFocusCoordinator = remember { RaftFocusCoordinator() }
        val escapeBack = remember { RaftDesktopEscapeBack() }
        // A failure in the window is logged before Nucleus's error dialog ends the app.
        @OptIn(ExperimentalComposeUiApi::class)
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides RaftDesktopCrashReporting.windowExceptionHandlerFactory) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = windowState,
                title = "Raft",
                minimumSize = DpSize(MIN_WINDOW_WIDTH.dp, MIN_WINDOW_HEIGHT.dp),
                // ⌘K / Ctrl+K before the content: the shell opens search even from a text field.
                onPreviewKeyEvent = { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        windowFocusCoordinator.recordUserInteraction()
                        RaftDesktopInUse.onInput()
                    }
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
                // History shortcuts at the window level, after the content: a focused text field or
                // menu that handles the chord itself keeps it. Then an Esc nothing took is Back.
                onKeyEvent = { event ->
                    raftDesktopHistoryShortcut(
                        keyDown = event.type == KeyEventType.KeyDown,
                        key = event.key,
                        meta = event.isMetaPressed,
                        ctrl = event.isCtrlPressed,
                        alt = event.isAltPressed,
                        shift = event.isShiftPressed,
                        mac = RaftDesktopHistoryInput.isMac,
                    )?.let(navigator::history) ?: escapeBack.onKeyEvent(event)
                },
            ) {
                RaftDesktopWindowContent(navigator, windowState, windowFocusCoordinator, escapeBack)
            }
        }
    }
}

/**
 * The window's content. macOS: the content runs up under the title bar band
 * ([RaftDesktopTitleBarBand], laid over it), the traffic lights inside the top bar (web desktop
 * app). Elsewhere: Nucleus's title bar with its window controls, above the content.
 */
@Composable
private fun NucleusDecoratedWindowScope.RaftDesktopWindowContent(
    navigator: RaftDesktopNavigator,
    windowState: WindowState,
    windowFocusCoordinator: RaftFocusCoordinator,
    escapeBack: RaftDesktopEscapeBack,
) {
    RaftDesktopInUseTracker(focused = state.isActive)
    RaftDesktopEscapeBackRegistration(escapeBack)
    val body: @Composable () -> Unit = {
        RaftWindowFocusScope(modifier = Modifier.fillMaxSize().raftDesktopInUseInput(), coordinator = windowFocusCoordinator) {
            RaftDesktopHostLifecycle(focused = state.isActive, minimized = state.isMinimized) {
                RaftDesktopHost(
                    navigator = navigator,
                    fallbackWindowSize = { windowState.size },
                )
            }
        }
    }
    if (RaftDesktopHistoryInput.isMac) {
        WindowScaffold(
            titleBar = { RaftDesktopTitleBarBand() },
            // The band floats over the content and shares its presses with it: the top bar's
            // controls under it keep working (see RaftDesktopTitleBarBand). Hidden in fullscreen.
            titleBarPlacement = TitleBarPlacement.Overlay(autoHideInFullscreen = true, passThroughToContent = true),
        ) { _ ->
            val controlsWidth = LocalWindowChromeInsets.current.controlsInsets.calculateStartPadding(LocalLayoutDirection.current)
            RaftDesktopTitleBarProvider(raftDesktopTitleBarMetrics(fullscreen = state.isFullscreen, reportedControlsWidth = controlsWidth)) {
                body()
            }
        }
    } else {
        WindowScaffold(titleBar = { TitleBar() }) { _ -> body() }
    }
}

/** Window minimum, in logical points: a phone-sized window. */
private const val MIN_WINDOW_WIDTH = 360
private const val MIN_WINDOW_HEIGHT = 520
