package build.raft.app.components

import build.raft.app.RaftAppThemeState
import build.raft.app.RaftUiTextLocaleState
import build.raft.app.raftMultiWeightFontFamily
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.tencent.tmm.kmmresource.compose.stringResource
import build.raft.app.resources.MR
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.host.HostFeedbackCapability
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.RaftCopyHandler
import build.raft.designsystem.theme.LocalRaftMisuse
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftStrings
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
 * The type styles carry the UI language ([RaftUiTextLocaleState]), so Android draws Han
 * characters in the ja / ko / zh-Hant glyph forms the UI language asks for.
 *
 * The components' own words ([LocalRaftStrings]: the optional-field marker, the close, clear
 * and loading names) come from the app's `MR.strings`, so they follow the UI language.
 *
 * Debug builds fail on design-system misuse ([RaftProvideDesignSystemMisuse]); release stays
 * silent.
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
    val strings = raftAppDesignSystemStrings()
    RaftTheme(appearance = appearance, fonts = fonts, localeList = RaftUiTextLocaleState.localeList) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides appSelectionColors,
            LocalRaftStrings provides strings,
        ) {
            RaftProvideDesignSystemMisuse(content = content)
        }
    }
}

/** What a debug build (or a test) does on design-system misuse: fail, with the component's message. */
internal val RaftFailOnDesignSystemMisuse: (String) -> Unit = { message -> error(message) }

/**
 * Makes [LocalRaftMisuse] (e.g. a [build.raft.designsystem.components.RaftIconButton] with no
 * name) fail in [content] when [failOnMisuse]: by default the host's debug flag
 * ([RaftCmpRouter.isDebugBuild]), so a debug build crashes on the spot and a release build keeps
 * the design system's silent default. When [failOnMisuse] is false the parent's reporter is kept,
 * so a test that provides a failing one is not silenced by a theme nested inside it.
 *
 * Provided round every page (the pager host) and again inside [RaftDesignSystemTheme]; tests and
 * visual fixtures that draw without either pass `failOnMisuse = true`.
 */
@Composable
internal fun RaftProvideDesignSystemMisuse(
    failOnMisuse: Boolean = RaftCmpRouter.isDebugBuild,
    content: @Composable () -> Unit,
) {
    if (failOnMisuse) {
        CompositionLocalProvider(LocalRaftMisuse provides RaftFailOnDesignSystemMisuse, content = content)
    } else {
        content()
    }
}

/**
 * The design-system components' own words ([LocalRaftStrings]) in the UI language, from the
 * app's `MR.strings`. Provided round every page ([RaftProvideDesignSystemStrings] in the pager
 * host) and again inside [RaftDesignSystemTheme], whose [RaftTheme] sits below that.
 */
@Composable
internal fun raftAppDesignSystemStrings(): RaftStrings =
    RaftStrings(
        optional = stringResource(MR.strings.common_optional_suffix),
        close = stringResource(MR.strings.common_close),
        clear = stringResource(MR.strings.common_clear),
        loading = stringResource(MR.strings.common_loading),
        cancel = stringResource(MR.strings.common_cancel),
        dismiss = stringResource(MR.strings.common_dismiss),
        copy = stringResource(MR.strings.common_copy),
    )

/** Provides [raftAppDesignSystemStrings] as [LocalRaftStrings] to [content]. */
@Composable
internal fun RaftProvideDesignSystemStrings(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRaftStrings provides raftAppDesignSystemStrings(), content = content)
}

/**
 * The design system's long-press copy ([build.raft.designsystem.components.raftCopyable]) through
 * the native host: [HostFeedbackCapability.copyToPasteboard], then the host toast "Copied"
 * (`common_copied`), the same pair every other Copy in the app uses. Provided round every page
 * (the pager host).
 */
@Composable
internal fun RaftProvideDesignSystemCopy(feedback: HostFeedbackCapability, content: @Composable () -> Unit) {
    val copied = stringResource(MR.strings.common_copied)
    val handler =
        remember(feedback, copied) {
            RaftCopyHandler { text ->
                feedback.copyToPasteboard(text)
                feedback.toast(copied)
            }
        }
    CompositionLocalProvider(LocalRaftCopyHandler provides handler, content = content)
}
