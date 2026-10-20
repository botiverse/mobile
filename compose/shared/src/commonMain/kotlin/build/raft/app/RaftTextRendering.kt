package build.raft.app

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import build.raft.designsystem.theme.RaftTypography

/**
 * How Raft text is laid out and rasterised, independent of size and weight: the parts of a
 * [TextStyle] that make the same Space Grotesk files look like the Web.
 *
 * Line height: CSS centres the glyphs in the line box (half-leading above and below) and never
 * trims it. Compose's default is [LineHeightStyle.Alignment.Proportional] with
 * [LineHeightStyle.Trim.Both], which trims the first line's top and the last line's bottom, so a
 * single line sits low and is ~2dp shorter than the Web's box. Every Raft style uses
 * [RaftTypography.CssLineHeightStyle] (Center, Trim.None) instead.
 */
internal val RaftCssLineHeightStyle: LineHeightStyle get() = RaftTypography.CssLineHeightStyle

/**
 * Glyph positioning. Android's default [TextMotion.Static] hints glyphs and snaps every advance
 * to a whole pixel, so Space Grotesk's spacing drifts from the browser's (which positions glyphs
 * at subpixel offsets with no hinting). Android returns [TextMotion.Animated] (linear text,
 * subpixel positioning); other platforms return null and keep their default.
 */
internal expect fun raftPlatformTextMotion(): TextMotion?

/**
 * Fills in the rendering attributes a style left unspecified. Attributes a call site set
 * explicitly (a different [LineHeightStyle], say) win.
 */
internal fun TextStyle.withRaftTextRendering(): TextStyle {
    val motion = raftPlatformTextMotion()
    val needsLineBox = lineHeightStyle == null
    val needsMotion = textMotion == null && motion != null
    if (!needsLineBox && !needsMotion) return this
    return copy(
        lineHeightStyle = if (needsLineBox) RaftCssLineHeightStyle else lineHeightStyle,
        textMotion = if (needsMotion) motion else textMotion,
    )
}

/** The rendering defaults as a style, for [LocalTextStyle] and merges. */
internal fun raftTextRenderingStyle(): TextStyle =
    TextStyle(lineHeightStyle = RaftCssLineHeightStyle, textMotion = raftPlatformTextMotion())

/**
 * Provides the rendering defaults through [LocalTextStyle], so `Text` calls whose style was not
 * built from a [RaftType] token (an ad-hoc `TextStyle(...)`, or no style at all) render the same
 * way. `material3.Text` merges its `style` over [LocalTextStyle]; unspecified attributes fall
 * back to these.
 */
@Composable
internal fun RaftProvideTextRendering(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.merge(raftTextRenderingStyle()),
        content = content,
    )
}
