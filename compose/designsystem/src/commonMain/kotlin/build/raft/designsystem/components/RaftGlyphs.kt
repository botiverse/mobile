package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/**
 * The few Lucide icons (ISC licence, lucide.dev; the same SVGs the app ships) that the
 * components themselves draw: the banner intents, the list and select chevrons, the @ marker,
 * the check of a checkbox and of a menu's current value, the dialog's close, a menu's search,
 * the top bar's Back arrow, the jump pills' arrows, a sidebar channel's `hash` / `lock`, a group header's sort
 * arrows and an action card's default `zap`. Each is the 24x24 outline drawn with Lucide's
 * 2-unit round stroke. App icons are not this module's business: pass them through the components' icon
 * slots.
 */
enum class RaftGlyph(internal vararg val paths: String) {
    ArrowLeft("m12 19-7-7 7-7", "M19 12H5"),

    /** Lucide `arrow-down`: the jump pills that go down the list. */
    ArrowDown("M12 5v14", "m19 12-7 7-7-7"),

    /** Lucide `arrow-up`: the jump pill that goes up the list. */
    ArrowUp("m5 12 7-7 7 7", "M12 19V5"),
    ChevronRight("m9 18 6-6-6-6"),
    ChevronDown("m6 9 6 6 6-6"),
    Check("M20 6 9 17l-5-5"),
    Minus("M5 12h14"),
    X("M18 6 6 18", "m6 6 12 12"),
    Search("m21 21-4.34-4.34", circle(11f, 11f, 8f)),
    Info(circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01"),
    TriangleAlert(
        "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
        "M12 9v4",
        "M12 17h.01",
    ),
    OctagonAlert(
        "M12 16h.01",
        "M12 8v4",
        "M15.312 2a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414" +
            "l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586l-4.688-4.688" +
            "A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2z",
    ),
    CircleCheck(circle(12f, 12f, 10f), "m9 12 2 2 4-4"),
    AtSign(circle(12f, 12f, 4f), "M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8"),
    Plus("M5 12h14", "M12 5v14"),
    Hash("M4 9h16", "M4 15h16", "M10 3 8 21", "M16 3l-2 18"),
    Lock("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4"),
    Copy(
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
    ),

    /** Lucide `arrow-down-up` (sort): the down arrow on the left, the up arrow on the right. */
    ArrowDownUp("m3 16 4 4 4-4", "M7 20V4", "m21 8-4-4-4 4", "M17 4v16"),

    /** Lucide `arrow-up-down` (sort): the up arrow on the left; the app's sort controls draw this one. */
    ArrowUpDown("m21 16-4 4-4-4", "M17 20V4", "m3 8 4-4 4 4", "M7 4v16"),

    /** Lucide `zap`: an action card's default header icon (raft-ui MessageActionCardHeaderIcon). */
    Zap(
        "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7" +
            "a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z",
    ),
    ;

    internal val parsed: List<Path> by lazy { paths.map { PathParser().parsePathString(it).toPath() } }
}

/** An SVG `<circle>` as path data (two arcs). */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/**
 * Draws a [RaftGlyph] at [size] in [color], else the current ink
 * ([LocalRaftContentColor], CSS `currentColor`). Decorative: it has no semantics.
 */
@Composable
fun RaftGlyphIcon(
    glyph: RaftGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = Color.Unspecified,
) {
    val ink =
        when {
            color.isSpecified -> color
            else -> LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
        }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            glyph.parsed.forEach { drawPath(it, ink, style = stroke) }
        }
    }
}
