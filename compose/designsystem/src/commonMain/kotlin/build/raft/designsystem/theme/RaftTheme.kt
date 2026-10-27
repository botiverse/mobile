package build.raft.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.intl.LocaleList
import build.raft.designsystem.components.LocalRaftPulseClock
import build.raft.designsystem.components.rememberRaftPulseClock
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors

/** The user's appearance choice. [System] follows the OS light/dark setting. */
enum class RaftAppearance {
    Light,
    Dark,
    System,
}

/**
 * Observable appearance, modelled on compose/shared `RaftAppThemeState`: a Compose state
 * holder, so changing [appearance] (or the OS setting under [RaftAppearance.System]) or the
 * server theme ([raftTheme], the Web's `data-theme`) recomposes every [RaftTheme] that reads it.
 *
 * A new state defaults to [RaftAppearance.Light] (fixtures and tests create their own);
 * [App], the process-wide instance app screens share, starts on [RaftAppearance.System]
 * like the Web and the app applies the user's stored choice to it at startup. [RaftTheme]
 * reports the OS setting through [onSystemDarkChanged]; hosts that know it before the first
 * composition seed it too, so token reads outside [RaftTheme] resolve right on frame one.
 */
@Stable
class RaftThemeState(
    appearance: RaftAppearance = RaftAppearance.Light,
    raftTheme: RaftThemeId = RaftThemeId.Raft,
    decor: Boolean = false,
) {
    var appearance: RaftAppearance by mutableStateOf(appearance)
        private set

    /**
     * The raft-ui theme (the Web's `data-theme`, chosen per server): which [RaftUiThemeColors]
     * variant the mode resolves to. [RaftThemeId.Raft] (the default) is the look without a theme.
     */
    var raftTheme: RaftThemeId by mutableStateOf(raftTheme)
        private set

    /**
     * Whether the theme's decor (the Web's `--theme-decor-top` / `--theme-decor-rail` art) is
     * drawn; the Web's `data-theme-decor` on/off. Off by default; nothing draws decor yet.
     */
    var decor: Boolean by mutableStateOf(decor)
        private set

    /** The OS light/dark setting, used under [RaftAppearance.System]. */
    var systemDark: Boolean by mutableStateOf(false)
        private set

    /** Whether the resolved theme is dark. */
    val isDark: Boolean
        get() =
            when (appearance) {
                RaftAppearance.Light -> false
                RaftAppearance.Dark -> true
                RaftAppearance.System -> systemDark
            }

    /** The colours of the resolved mode in [raftTheme], what [RaftTheme] provides for this state. */
    val colors: RaftUiThemeColors
        get() = RaftUiThemeColors.of(raftTheme, isDark)

    /** @return true when the resolved theme changed, as `RaftAppThemeState.apply` does. */
    fun apply(next: RaftAppearance): Boolean {
        val wasDark = isDark
        appearance = next
        return wasDark != isDark
    }

    /** Sets the raft-ui theme (and its decor); @return true when either changed. */
    fun applyRaftTheme(next: RaftThemeId, decor: Boolean = this.decor): Boolean {
        val changed = next != raftTheme || decor != this.decor
        raftTheme = next
        this.decor = decor
        return changed
    }

    /** Records the OS setting; @return true when the resolved theme changed. */
    fun onSystemDarkChanged(dark: Boolean): Boolean {
        val wasDark = isDark
        systemDark = dark
        return wasDark != isDark
    }

    companion object {
        /** Process-wide state app screens follow (the RaftAppThemeState counterpart). */
        val App: RaftThemeState = RaftThemeState(RaftAppearance.System)
    }
}

/** raft-ui shape scale (`rounded-*`) as Compose shapes. */
@Immutable
data class RaftShapes(
    val pixel: Shape = RoundedCornerShape(RaftRadius.Pixel),
    val sticker: Shape = RoundedCornerShape(RaftRadius.Sticker),
    val chip: Shape = RoundedCornerShape(RaftRadius.Chip),
    val control: Shape = RoundedCornerShape(RaftRadius.Control),
    val container: Shape = RoundedCornerShape(RaftRadius.Container),
    val dialog: Shape = RoundedCornerShape(RaftRadius.Dialog),
)

/** Themed colours of the active appearance and theme (generated from raft-ui foundation.css, themes.css). */
val LocalRaftColors = staticCompositionLocalOf<RaftUiThemeColors> { RaftUiThemeColors.RaftLight }

/** The raft-ui theme of the enclosing [RaftTheme] ([RaftThemeState.raftTheme]). */
val LocalRaftThemeId = staticCompositionLocalOf { RaftThemeId.Raft }

/**
 * Whether the enclosing [RaftTheme] draws its theme's decor ([RaftThemeState.decor]); read by
 * the top bar and rail once they draw it. False by default.
 */
val LocalRaftThemeDecor = staticCompositionLocalOf { false }

val LocalRaftTypography =
    staticCompositionLocalOf<RaftTypography> {
        error("No RaftTypography: wrap the content in RaftTheme { }")
    }

val LocalRaftShapes = staticCompositionLocalOf { RaftShapes() }

/** Resolved dark flag of the enclosing [RaftTheme]. */
val LocalRaftIsDark = staticCompositionLocalOf { false }

/**
 * CSS `currentColor`: the ink text, icons and spinners take when the caller gives none.
 * [RaftTheme] provides `--foreground`; components that set their own ink (a badge)
 * provide it to their content.
 */
val LocalRaftContentColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * The opaque colour of the surface the content sits on: what a cut-out (an avatar's
 * presence light, the ring round each face of an avatar stack) is cut out of. [RaftTheme]
 * provides `layer-canvas`; every component with a fill of its own provides that fill to
 * its content ([build.raft.designsystem.components.RaftCard] and
 * [build.raft.designsystem.components.RaftListRowGroup] `layer-card`, a selected row its
 * `info-soft` wash over the card). A screen that paints its own surface (a `layer-panel`
 * side panel) provides it too. Read it through [RaftTheme.surfaceColor].
 */
val LocalRaftSurfaceColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * Root of every Raft CMP surface. Resolves light/dark from [state] (and the OS setting
 * under [RaftAppearance.System]) in the state's [RaftThemeState.raftTheme], loads the
 * bundled fonts (unless the host passes its own [fonts]) and provides colours, typography, shapes, the content colour and the text
 * selection colours (`brand-soft`).
 *
 * [localeList] is the app's UI language for every [RaftTypography] style (`LocaleList("ja")`,
 * `"ko"`, `"zh-Hant"`): the platform picks CJK fallback glyphs from the text's locale, so Han
 * characters take that language's forms. Null (the default) sets none and leaves the
 * device locale in charge. A span's own `localeList` (a user's name) still wins.
 *
 * This is not a Material theme: components read [RaftTheme], never `MaterialTheme`.
 */
@Composable
fun RaftTheme(
    state: RaftThemeState = RaftThemeState.App,
    systemDark: Boolean = isSystemInDarkTheme(),
    fonts: RaftFonts? = null,
    localeList: LocaleList? = null,
    content: @Composable () -> Unit,
) {
    SideEffect { state.onSystemDarkChanged(systemDark) }
    val dark = if (state.appearance == RaftAppearance.System) systemDark else state.isDark
    val raftTheme = state.raftTheme
    val colors = RaftUiThemeColors.of(raftTheme, dark)
    // A host with its own faces (the app's Settings > Appearance face) passes them in, so the
    // bundled Space Grotesk / Space Mono are never loaded just to be replaced.
    val resolvedFonts = fonts ?: rememberRaftFonts()
    val typography = remember(resolvedFonts, localeList) { RaftTypography.from(resolvedFonts, localeList) }
    CompositionLocalProvider(
        LocalRaftColors provides colors,
        LocalRaftTypography provides typography,
        LocalRaftShapes provides RaftShapes(),
        LocalRaftIsDark provides dark,
        LocalRaftThemeId provides raftTheme,
        LocalRaftThemeDecor provides state.decor,
        LocalRaftContentColor provides colors.foreground,
        LocalRaftSurfaceColor provides colors.layerCanvas,
        LocalRaftPulseClock provides rememberRaftPulseClock(),
        // raft-web `::selection { background-color: var(--selection) }` (raft-ui 0.23 #730:
        // brand-soft in light, brand at 22% in dark); the handles take the caret's ink.
        LocalTextSelectionColors provides remember(colors) { TextSelectionColors(handleColor = colors.foreground, backgroundColor = colors.selection) },
        content = content,
    )
}

/**
 * Pins an [appearance] for a subtree (previews, fixtures, screenshot tests, an app screen
 * hosting designsystem components). [fonts] replaces the bundled faces when given;
 * [localeList] is the UI language of the type styles, as in the state overload.
 * [raftTheme] and [decor] pin the raft-ui theme ([RaftThemeId.Raft], no decor, by default).
 */
@Composable
fun RaftTheme(
    appearance: RaftAppearance,
    systemDark: Boolean = isSystemInDarkTheme(),
    fonts: RaftFonts? = null,
    localeList: LocaleList? = null,
    raftTheme: RaftThemeId = RaftThemeId.Raft,
    decor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state = remember(appearance, raftTheme, decor) { RaftThemeState(appearance, raftTheme, decor) }
    RaftTheme(state = state, systemDark = systemDark, fonts = fonts, localeList = localeList, content = content)
}

/** Read access to the enclosing [RaftTheme]. */
object RaftTheme {
    val colors: RaftUiThemeColors
        @Composable @ReadOnlyComposable
        get() = LocalRaftColors.current

    val typography: RaftTypography
        @Composable @ReadOnlyComposable
        get() = LocalRaftTypography.current

    val shapes: RaftShapes
        @Composable @ReadOnlyComposable
        get() = LocalRaftShapes.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalRaftIsDark.current

    /** [LocalRaftThemeId]: the raft-ui theme the colours come from. */
    val themeId: RaftThemeId
        @Composable @ReadOnlyComposable
        get() = LocalRaftThemeId.current

    /** [LocalRaftThemeDecor]: whether the theme's decor is drawn. */
    val decor: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalRaftThemeDecor.current

    /**
     * The one scrim behind every modal window (raft `layer-backdrop`: black 65% in light, 60%
     * in dark). [build.raft.designsystem.components.RaftWindowDialog] draws it; an in-window
     * overlay (a drawer over the page) uses the same colour.
     */
    val scrim: Color
        @Composable @ReadOnlyComposable
        get() = LocalRaftColors.current.layerBackdrop

    /** [LocalRaftStrings]: the components' own words, English unless the app provides them. */
    val strings: RaftStrings
        @Composable @ReadOnlyComposable
        get() = LocalRaftStrings.current

    /** [LocalRaftSurfaceColor], or `layer-canvas` outside any surface. */
    val surfaceColor: Color
        @Composable @ReadOnlyComposable
        get() = LocalRaftSurfaceColor.current.takeIf { it.isSpecified } ?: colors.layerCanvas
}
