package build.raft.designsystem.screenshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import build.raft.designsystem.theme.RaftTheme

/**
 * The app's own tab and More-row icons, for screenshots only: app icons are not this module's
 * business (components take them through slots), but a golden of the phone bar and the server
 * popover should show the icons the app passes. Path data copied from the app's Lucide catalog
 * (compose/shared RaftLucideIconPaths.kt): the icons the old More rows and the tab bar use.
 */
internal enum class AppIcon(vararg val paths: String) {
    House(
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    ),
    Activity("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"),
    SquareCheckBig("M21 10.656V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12.344", "m9 11 3 3L22 4"),
    Ellipsis(
        "M 11.0 12.0 A 1.0 1.0 0 1 0 13.0 12.0 A 1.0 1.0 0 1 0 11.0 12.0 Z",
        "M 18.0 12.0 A 1.0 1.0 0 1 0 20.0 12.0 A 1.0 1.0 0 1 0 18.0 12.0 Z",
        "M 4.0 12.0 A 1.0 1.0 0 1 0 6.0 12.0 A 1.0 1.0 0 1 0 4.0 12.0 Z",
    ),
    Users(
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        "M16 3.128a4 4 0 0 1 0 7.744",
        "M22 21v-2a4 4 0 0 0-3-3.87",
        "M 5.0 7.0 A 4.0 4.0 0 1 0 13.0 7.0 A 4.0 4.0 0 1 0 5.0 7.0 Z",
    ),
    Monitor(
        "M 4.0 3.0 H 20.0 A 2.0 2.0 0 0 1 22.0 5.0 V 15.0 A 2.0 2.0 0 0 1 20.0 17.0 H 4.0 A 2.0 2.0 0 0 1 2.0 15.0 V 5.0 A 2.0 2.0 0 0 1 4.0 3.0 Z",
        "M 8.0 21.0 L 16.0 21.0",
        "M 12.0 17.0 L 12.0 21.0",
    ),
    Settings(
        "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
        "M 9.0 12.0 A 3.0 3.0 0 1 0 15.0 12.0 A 3.0 3.0 0 1 0 9.0 12.0 Z",
    ),
    Globe(
        "M 2.0 12.0 A 10.0 10.0 0 1 0 22.0 12.0 A 10.0 10.0 0 1 0 2.0 12.0 Z",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    ),
    Plus("M5 12h14", "M12 5v14"),
    UserPlus(
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        "M 5.0 7.0 A 4.0 4.0 0 1 0 13.0 7.0 A 4.0 4.0 0 1 0 5.0 7.0 Z",
        "M 19.0 8.0 L 19.0 14.0",
        "M 22.0 11.0 L 16.0 11.0",
    ),
    Bot(
        "M12 8V4H8",
        "M 6.0 8.0 H 18.0 A 2.0 2.0 0 0 1 20.0 10.0 V 18.0 A 2.0 2.0 0 0 1 18.0 20.0 H 6.0 A 2.0 2.0 0 0 1 4.0 18.0 V 10.0 A 2.0 2.0 0 0 1 6.0 8.0 Z",
        "M2 14h2",
        "M20 14h2",
        "M15 13v2",
        "M9 13v2",
    ),
    Bell(
        "M10.268 21a2 2 0 0 0 3.464 0",
        "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326",
    ),
    // Lucide panel-left-close / panel-left-open / circle-question-mark: web LeftRail's footer tools.
    PanelLeftClose(
        "M 5.0 3.0 H 19.0 A 2.0 2.0 0 0 1 21.0 5.0 V 19.0 A 2.0 2.0 0 0 1 19.0 21.0 H 5.0 A 2.0 2.0 0 0 1 3.0 19.0 V 5.0 A 2.0 2.0 0 0 1 5.0 3.0 Z",
        "M9 3v18",
        "m16 15-3-3 3-3",
    ),
    PanelLeftOpen(
        "M 5.0 3.0 H 19.0 A 2.0 2.0 0 0 1 21.0 5.0 V 19.0 A 2.0 2.0 0 0 1 19.0 21.0 H 5.0 A 2.0 2.0 0 0 1 3.0 19.0 V 5.0 A 2.0 2.0 0 0 1 5.0 3.0 Z",
        "M9 3v18",
        "m14 9 3 3-3 3",
    ),
    CircleHelp(
        "M 2.0 12.0 A 10.0 10.0 0 1 0 22.0 12.0 A 10.0 10.0 0 1 0 2.0 12.0 Z",
        "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3",
        "M12 17h.01",
    ),
    // Lucide smile-plus / text-quote: the message hover toolbar's Add Reaction and the
    // text-selection toolbar's Quote (RaftMessageItemToolbarScreenshotTest).
    SmilePlus(
        "M22 11v1a10 10 0 1 1-9-10",
        "M8 14s1.5 2 4 2 4-2 4-2",
        "M9 9h.01",
        "M15 9h.01",
        "M16 5h6",
        "M19 2v6",
    ),
    TextQuote("M17 6H3", "M21 12H8", "M21 18H8", "M3 12v6"),
    ;

    val parsed by lazy { paths.map { PathParser().parsePathString(it).toPath() } }
}

/** Draws [icon] like the app's RaftLucideIcon: the 24-unit outline with a 2-unit round stroke. */
@Composable
internal fun AppIconImage(icon: AppIcon, size: Dp, color: Color = RaftTheme.colors.foregroundMuted) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            icon.parsed.forEach { drawPath(it, color, style = stroke) }
        }
    }
}
