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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.LocalRaftPageArgs
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing

/**
 * Root of every CMP page, on both hosts: provides the Raft theme, the route ([LocalRaftPageArgs])
 * and the native host ([LocalRaftHost]), paints the canvas edge to edge and renders the page
 * registered for [args]'s page name. Pages own their insets (`WindowInsets.safeDrawing`).
 * [onDarkThemeResolved] reports the resolved appearance after each change (system bar icons);
 * [onCanvasColorResolved] reports the canvas colour painted behind the page after each change, so
 * a host can give its native container the same colour (whatever Compose does not draw, e.g. the
 * area a keyboard offset uncovers, then shows the app surface instead of a platform default).
 */
@Composable
fun RaftCmpRoot(
    args: RaftCmpPageArgs,
    host: RaftCmpHost,
    onDarkThemeResolved: ((Boolean) -> Unit)? = null,
    onCanvasColorResolved: ((Color) -> Unit)? = null,
) {
    CompositionLocalProvider(
        LocalRaftHost provides host,
        LocalRaftPageArgs provides args,
    ) {
        RaftTheme {
            val dark = RaftTheme.isDark
            if (onDarkThemeResolved != null) {
                LaunchedEffect(dark) { onDarkThemeResolved(dark) }
            }
            val canvas = RaftTheme.colors.layerCanvas
            if (onCanvasColorResolved != null) {
                LaunchedEffect(canvas) { onCanvasColorResolved(canvas) }
            }
            val pressWash = RaftTheme.colors.fillPressed
            val indication = remember(pressWash) { RaftPressIndication(pressWash) }
            CompositionLocalProvider(LocalIndication provides indication) {
                Box(Modifier.fillMaxSize().background(canvas)) {
                    val content = RaftCmpRouter.content(args.pageName)
                    if (content != null) content() else UnknownCmpPage(args.pageName)
                }
            }
        }
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
