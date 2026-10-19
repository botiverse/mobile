package build.raft.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiThemeColors

/** The user's appearance choice. [System] follows the OS light/dark setting. */
enum class RaftAppearance {
    Light,
    Dark,
    System,
}

/**
 * Observable appearance, modelled on compose/shared `SlockThemeState`: a Compose state
 * holder, so changing [appearance] (or the OS setting under [RaftAppearance.System])
 * recomposes every [RaftTheme] that reads it.
 *
 * Unlike the Kuikly state, hosts do not have to report the OS setting: [RaftTheme]
 * reads `isSystemInDarkTheme()` itself. [App] is the process-wide instance app screens
 * share; fixtures and tests create their own.
 */
@Stable
class RaftThemeState(appearance: RaftAppearance = RaftAppearance.System) {
    var appearance: RaftAppearance by mutableStateOf(appearance)
        private set

    /** @return true when the stored choice changed. */
    fun apply(next: RaftAppearance): Boolean {
        if (next == appearance) return false
        appearance = next
        return true
    }

    /** Resolved dark flag for an OS setting of [systemDark]. */
    fun isDark(systemDark: Boolean): Boolean =
        when (appearance) {
            RaftAppearance.Light -> false
            RaftAppearance.Dark -> true
            RaftAppearance.System -> systemDark
        }

    companion object {
        /** Process-wide state app screens follow (the SlockThemeState counterpart). */
        val App: RaftThemeState = RaftThemeState()
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

/** Themed colours of the active appearance (generated from raft-ui foundation.css). */
val LocalRaftColors = staticCompositionLocalOf<RaftUiThemeColors> { RaftUiThemeColors.RaftLight }

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
 * Root of every Raft CMP surface. Resolves light/dark from [state] (and the OS setting
 * under [RaftAppearance.System]), loads the bundled fonts and provides colours,
 * typography, shapes and the content colour.
 *
 * This is not a Material theme: components read [RaftTheme], never `MaterialTheme`.
 */
@Composable
fun RaftTheme(
    state: RaftThemeState = RaftThemeState.App,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = state.isDark(systemDark)
    val colors = if (dark) RaftUiThemeColors.RaftDark else RaftUiThemeColors.RaftLight
    val fonts = rememberRaftFonts()
    val typography = remember(fonts) { RaftTypography.from(fonts) }
    CompositionLocalProvider(
        LocalRaftColors provides colors,
        LocalRaftTypography provides typography,
        LocalRaftShapes provides RaftShapes(),
        LocalRaftIsDark provides dark,
        LocalRaftContentColor provides colors.foreground,
        content = content,
    )
}

/** Pins an [appearance] for a subtree (previews, fixtures, screenshot tests). */
@Composable
fun RaftTheme(
    appearance: RaftAppearance,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val state = remember(appearance) { RaftThemeState(appearance) }
    RaftTheme(state = state, systemDark = systemDark, content = content)
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
}
