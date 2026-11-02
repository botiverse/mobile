package build.raft.app.cmp

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.LocalRaftPageArgs
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.designsystem.components.LocalRaftStatusBarBackdrop
import build.raft.designsystem.components.RaftStatusBarBackdrop
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.designsystem.components.RaftSystemBarsAppearance
import build.raft.designsystem.components.raftSystemBarsAppearance
import build.raft.designsystem.gesture.LocalRaftBackSwipeRegions
import build.raft.designsystem.gesture.RaftBackSwipeRegions
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing

/**
 * Root of every CMP page, on both hosts: provides the Raft theme, the route ([LocalRaftPageArgs])
 * and the native host ([LocalRaftHost]), paints the canvas edge to edge and renders the page
 * registered for [args]'s page name. Pages own their insets: shared pages read the host's
 * window-inset contract (`RaftAppSafeArea.kt`; the page host pads the left/right safe area for
 * every page), other pages use `WindowInsets.safeDrawing`.
 * [onSystemBarsResolved] reports the system bar icons after each change: the status bar's from
 * what the page paints under it (the frame a head claims through [RaftStatusBarBackdropClaim],
 * else the canvas), the navigation bar's from the canvas, each by luminance, so a themed frame
 * (dark in light mode for Space and Quest) gets light icons;
 * [onCanvasColorResolved] reports the canvas colour painted behind the page after each change, so
 * a host can give its native container the same colour (whatever Compose does not draw, e.g. the
 * area a keyboard offset uncovers, then shows the app surface instead of a platform default).
 * [backSwipeRegions] is the host's full-page back swipe probe (iOS); null on hosts without one.
 * The page draws under the window's toast host ([RaftCmpToastLayer]): one per window, this
 * root's own unless the host wrapped its roots in one.
 */
@Composable
fun RaftCmpRoot(
    args: RaftCmpPageArgs,
    host: RaftCmpHost,
    onSystemBarsResolved: ((RaftSystemBarsAppearance) -> Unit)? = null,
    onCanvasColorResolved: ((Color) -> Unit)? = null,
    backSwipeRegions: RaftBackSwipeRegions? = null,
) {
    if (backSwipeRegions != null) {
        val density = LocalDensity.current.density
        SideEffect { backSwipeRegions.density = density }
    }
    CompositionLocalProvider(
        LocalRaftHost provides host,
        LocalRaftPageArgs provides args,
        LocalRaftBackSwipeRegions provides backSwipeRegions,
    ) {
        RaftTheme {
            val canvas = RaftTheme.colors.layerCanvas
            val statusBarBackdrop = remember { RaftStatusBarBackdrop() }
            if (onSystemBarsResolved != null) {
                RaftSystemBarsReporter(statusBarBackdrop, canvas, onSystemBarsResolved)
            }
            if (onCanvasColorResolved != null) {
                LaunchedEffect(canvas) { onCanvasColorResolved(canvas) }
            }
            val pressWash = RaftTheme.colors.fillPressed
            val indication = remember(pressWash) { RaftPressIndication(pressWash) }
            CompositionLocalProvider(
                LocalIndication provides indication,
                LocalRaftStatusBarBackdrop provides statusBarBackdrop,
            ) {
                // The window's one toast host, over the page (unless a host already put one
                // round this root: Android's and the desktop's page stacks).
                RaftCmpToastLayer(origin = host.capabilities) {
                    Box(Modifier.fillMaxSize().background(canvas)) {
                        val content = RaftCmpRouter.content(args.pageName)
                        if (content != null) content() else UnknownCmpPage(args.pageName)
                    }
                }
            }
        }
    }
}

/**
 * Reports the bar icons whenever the status bar backdrop (a head's claim, else [canvas]) or the
 * canvas changes. Read in a snapshot flow, not in composition: the first value is taken after the
 * page's first composition is applied (its heads' claims registered), so the host never gets a
 * canvas-based value first, and a claim changing never recomposes anything.
 */
@Composable
private fun RaftSystemBarsReporter(
    statusBarBackdrop: RaftStatusBarBackdrop,
    canvas: Color,
    onSystemBarsResolved: (RaftSystemBarsAppearance) -> Unit,
) {
    val currentCanvas by rememberUpdatedState(canvas)
    val currentOnSystemBarsResolved by rememberUpdatedState(onSystemBarsResolved)
    LaunchedEffect(statusBarBackdrop) {
        snapshotFlow {
            raftSystemBarsAppearance(
                statusBarBackdrop = statusBarBackdrop.color ?: currentCanvas,
                navigationBarBackdrop = currentCanvas,
            )
        }.collect { bars -> currentOnSystemBarsResolved(bars) }
    }
}

/** Reached only if a host was opened for a page this build has no CMP implementation of. */
@Composable
private fun UnknownCmpPage(pageName: String) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(RaftSpacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(pageName, color = RaftTheme.colors.foregroundMuted, style = RaftTheme.typography.monoMeta)
    }
}
