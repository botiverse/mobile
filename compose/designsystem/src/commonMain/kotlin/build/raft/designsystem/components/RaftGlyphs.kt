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
 * the check of a checkbox and of a menu's current value, the dialog's close and a menu's search. Each is
 * the 24x24 outline drawn with Lucide's 2-unit round stroke. App icons are not this
 * module's business: pass them through the components' icon slots.
 */
enum class RaftGlyph(internal vararg val paths: String) {
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
    Copy(
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
    ),
    /** Lucide `ellipsis-vertical`: the More trigger of a panel head. */
    EllipsisVertical(circle(12f, 12f, 1f), circle(12f, 5f, 1f), circle(12f, 19f, 1f)),
    /** Lucide `circle-help` (`HelpCircle`). */
    CircleHelp(circle(12f, 12f, 10f), "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01"),
    /** raft-ui `DirectMessageIcon` (an 18-unit, 1.5-stroke drawing, scaled onto this 24 grid). */
    DirectMessage(
        "M21.6667 6.6667V5.6667C21.6667 4.2 20.4667 3 19 3H5C3.5333 3 2.3333 4.2 2.3333 5.6667V15" +
            "C2.3333 16.4667 3.5333 17.6667 5 17.6667H7.6667V21.6667L10 19.8",
        circle(19f, 10.6667f, 2.3333f),
        "M14.6667 21.6667C15.0667 18.2 16.6667 16 19 16S22.9333 18.2 23.3333 21.6667",
    ),
    /** Lucide `cpu`: the runtime chip. */
    Cpu(
        "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M10 9h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z",
        "M15 2v2", "M15 20v2", "M2 15h2", "M2 9h2", "M20 15h2", "M20 9h2", "M9 2v2", "M9 20v2",
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
