package build.raft.app.components

import build.raft.app.RaftAppThemeState
import build.raft.app.raftMultiWeightFontFamily
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftTheme

/**
 * Hosts compose/designsystem (raft-ui 0.8.0) components and type tokens inside an app
 * screen: the designsystem [RaftTheme] on the app's resolved light/dark appearance, with
 * its type scale (raft-design Typography: body 16/24, subhead 15/20, callout 14/20,
 * footnote 13/18, caption 12/16, mono-meta 12/16) set in the app's bundled Space Grotesk /
 * Space Mono faces, so designsystem text matches the `RaftType` text around it. The app
 * faces go straight into [RaftTheme], so the designsystem's own copies are never loaded.
 *
 * The designsystem theme provides its own `brand-soft` text selection colours; the app's
 * [LocalTextSelectionColors] are put back for the subtree so a text field in here selects
 * the same way as every other field in the app.
 *
 * Everything that reads `RaftTheme.typography` (the search result parts included) must sit
 * inside this wrapper: outside any designsystem theme that read throws.
 */
@Composable
internal fun RaftDesignSystemTheme(content: @Composable () -> Unit) {
    val appearance = if (RaftAppThemeState.isDark) RaftAppearance.Dark else RaftAppearance.Light
    val appSelectionColors = LocalTextSelectionColors.current
    val fonts =
        remember {
            RaftFonts(
                sans = raftMultiWeightFontFamily("sans"),
                mono = raftMultiWeightFontFamily("mono")
            )
        }
    RaftTheme(appearance = appearance, fonts = fonts) {
        CompositionLocalProvider(LocalTextSelectionColors provides appSelectionColors, content = content)
    }
}
