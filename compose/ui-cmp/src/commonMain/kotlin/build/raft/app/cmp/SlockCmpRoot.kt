package build.raft.app.cmp

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.cmp.host.LocalSlockHost
import build.raft.app.cmp.host.LocalSlockPageArgs
import build.raft.app.cmp.host.SlockCmpHost
import build.raft.app.cmp.host.SlockCmpPageArgs
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing

/**
 * Root of every CMP page, on both hosts: provides the Raft theme, the route ([LocalSlockPageArgs])
 * and the native host ([LocalSlockHost]), paints the canvas edge to edge and renders the page
 * registered for [args]'s page name. Pages own their insets (`WindowInsets.safeDrawing`).
 * [onDarkThemeResolved] reports the resolved appearance after each change (system bar icons).
 */
@Composable
fun SlockCmpRoot(
    args: SlockCmpPageArgs,
    host: SlockCmpHost,
    onDarkThemeResolved: ((Boolean) -> Unit)? = null,
) {
    CompositionLocalProvider(
        LocalSlockHost provides host,
        LocalSlockPageArgs provides args,
    ) {
        RaftTheme {
            val dark = RaftTheme.isDark
            if (onDarkThemeResolved != null) {
                LaunchedEffect(dark) { onDarkThemeResolved(dark) }
            }
            Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                val page = SlockCmpRouter.page(args.pageName)
                if (page != null) {
                    page.content()
                } else {
                    UnknownCmpPage(args.pageName)
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
