package build.raft.designsystem.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import build.raft.designsystem.tokens.RaftPixelArt
import build.raft.designsystem.tokens.RaftThemeDecorArt
import build.raft.designsystem.tokens.RaftThemeId
import kotlin.math.roundToInt

// raft-ui theme decor (themes.css `--theme-decor-top` / `--theme-decor-rail`, painted by the
// `frame-decor-top` / `frame-decor-rail` utilities in styles.css): a decorated theme's pixel
// sprites on the frame, one strip on the top bar and one at the foot of the rail. They paint
// as a background: over the frame colour, under the frame's content, never laid out and never
// touched. Nothing draws for Raft Yellow or Boring Gray, or with the server's decor off.

/** Where a theme's decor strip goes. */
enum class RaftThemeDecorSlot {
    /** The top bar's 190x48 strip (`frame-decor-top`), from a 1024dp-wide window only. */
    Top,

    /** The rail's 48x112 strip (`frame-decor-rail`), under the rail's destinations. */
    Rail,
}

/** raft-ui `frame-decor-top` / `frame-decor-rail` metrics. */
object RaftThemeDecorDefaults {
    /**
     * The narrowest window the top bar's strip shows in (`@media (width >= 1024px)`): where the
     * centred search field leaves it room. Narrower windows (phones) draw none.
     */
    val TopMinWindowWidth: Dp = 1024.dp

    /** From the top bar's end to the strip's end (`background-position: right 72px center`). */
    val TopEndOffset: Dp = 72.dp
}

/**
 * The art [slot] draws for [theme] in [dark] mode, or null when it draws nothing: [decor] off,
 * a theme without art, or (for [RaftThemeDecorSlot.Top]) a [windowWidth] under
 * [RaftThemeDecorDefaults.TopMinWindowWidth] or unspecified. The rail's strip has no width gate
 * (raft-ui `frame-decor-rail` has no media query); the rail itself only shows on wide layouts.
 */
internal fun raftThemeDecorArt(
    theme: RaftThemeId,
    dark: Boolean,
    decor: Boolean,
    slot: RaftThemeDecorSlot,
    windowWidth: Dp = Dp.Unspecified,
): RaftPixelArt? {
    if (!decor) return null
    return when (slot) {
        RaftThemeDecorSlot.Top ->
            if (windowWidth.isSpecified && windowWidth >= RaftThemeDecorDefaults.TopMinWindowWidth) {
                RaftThemeDecorArt.top(theme, dark)
            } else {
                null
            }
        RaftThemeDecorSlot.Rail -> RaftThemeDecorArt.rail(theme, dark)
    }
}

/** Whether [slot] draws any decor for [theme] in [dark] mode in a [windowWidth]-wide window. */
fun raftThemeDecorShown(
    theme: RaftThemeId,
    dark: Boolean,
    decor: Boolean,
    slot: RaftThemeDecorSlot,
    windowWidth: Dp = Dp.Unspecified,
): Boolean = raftThemeDecorArt(theme, dark, decor, slot, windowWidth) != null

/**
 * Paints the enclosing [RaftTheme]'s decor for [slot] behind this element's content
 * ([RaftTheme.themeId] in its mode, while [RaftTheme.decor] is on), clipped to its bounds:
 * - [RaftThemeDecorSlot.Top]: the strip [RaftThemeDecorDefaults.TopEndOffset] from the end,
 *   centred vertically, when the window ([windowWidth]; this element's own width when
 *   unspecified) is at least [RaftThemeDecorDefaults.TopMinWindowWidth] wide;
 * - [RaftThemeDecorSlot.Rail]: the strip centred at the bottom, cut from the top when the
 *   element is shorter than the art.
 *
 * Only draws: no layout, no semantics, no input. Put it after the frame's background.
 */
fun Modifier.raftThemeDecor(slot: RaftThemeDecorSlot, windowWidth: Dp = Dp.Unspecified): Modifier =
    composed {
        val theme = RaftTheme.themeId
        val dark = RaftTheme.isDark
        val decor = RaftTheme.decor
        if (raftThemeDecorArt(theme, dark, decor, slot, Dp.Infinity) == null) {
            Modifier
        } else {
            Modifier.drawBehind {
                val window = if (windowWidth.isSpecified) windowWidth else size.width.toDp()
                val art = raftThemeDecorArt(theme, dark, decor, slot, window) ?: return@drawBehind
                val artWidth = art.width.dp.toPx()
                val artHeight = art.height.dp.toPx()
                when (slot) {
                    RaftThemeDecorSlot.Top ->
                        drawPixelArt(
                            art,
                            left = size.width - RaftThemeDecorDefaults.TopEndOffset.toPx() - artWidth,
                            top = (size.height - artHeight) / 2f,
                        )
                    RaftThemeDecorSlot.Rail ->
                        drawPixelArt(art, left = (size.width - artWidth) / 2f, top = size.height - artHeight)
                }
            }
        }
    }

/**
 * Draws [art] with its top-left at ([left], [top]), 1 art pixel = 1dp, every edge snapped to a
 * device pixel so the sprites stay crisp at any density (the SVG's `crispEdges`).
 */
private fun DrawScope.drawPixelArt(art: RaftPixelArt, left: Float, top: Float) {
    val unit = 1.dp.toPx()
    val x0 = left.roundToInt()
    val y0 = top.roundToInt()
    clipRect {
        art.layers.forEach { layer ->
            val r = layer.rects
            var i = 0
            while (i < r.size) {
                val l = x0 + (r[i] * unit).roundToInt()
                val t = y0 + (r[i + 1] * unit).roundToInt()
                val rr = x0 + ((r[i] + r[i + 2]) * unit).roundToInt()
                val b = y0 + ((r[i + 1] + r[i + 3]) * unit).roundToInt()
                drawRect(layer.color, Offset(l.toFloat(), t.toFloat()), Size((rr - l).toFloat(), (b - t).toFloat()))
                i += 4
            }
        }
    }
}
