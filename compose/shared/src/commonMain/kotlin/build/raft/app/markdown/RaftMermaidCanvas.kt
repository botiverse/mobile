package build.raft.app.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.mermaid.layout.DrawCommand
import build.raft.mermaid.layout.DrawEllipse
import build.raft.mermaid.layout.DrawLine
import build.raft.mermaid.layout.DrawPolygon
import build.raft.mermaid.layout.DrawPolyline
import build.raft.mermaid.layout.DrawRect
import build.raft.mermaid.layout.DrawText
import build.raft.mermaid.layout.LayoutScene
import build.raft.mermaid.layout.SceneColor
import build.raft.mermaid.layout.StrokePattern
import build.raft.mermaid.layout.TextAnchor

/**
 * Compose Canvas renderer for a Mermaid [LayoutScene]; a port of the Kuikly-only
 * `build.raft.mermaid.kuikly.MermaidKuiklyRenderer` (mermaid-kuikly 0.1.11). Scene units are dp;
 * the canvas is `scene size * scale` and every command is drawn in scene coordinates.
 *
 * Text is laid out with the host [textStyle] (app font) at the scene's font size/weight/style, the
 * scene origin is the alphabetic baseline (HTML canvas `fillText` semantics, as in Kuikly).
 * TODO(cmp-switch): the scene's `fontFamily` string is ignored (the app UI font is used) — the
 * same as Kuikly in practice, which could not resolve web font stacks either.
 */
@Composable
internal fun RaftMermaidCanvas(
    scene: LayoutScene,
    scale: Float,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val texts = remember(scene, textMeasurer, textStyle) { raftMermaidMeasureTexts(scene, textMeasurer, textStyle) }
    val description = scene.accessibilityTitle ?: scene.accessibilityDescription
    val drawScale = raftMermaidInlineScale(scene.width.toFloat(), scene.height.toFloat(), scale, LocalDensity.current.density)
    Canvas(
        modifier =
            modifier
                .width((scene.width * drawScale).dp)
                .height((scene.height * drawScale).dp)
                .then(
                    if (description.isNullOrBlank()) Modifier
                    else Modifier.semantics { contentDescription = description }
                )
    ) {
        val unit = density * drawScale
        if (unit <= 0f) return@Canvas
        scale(scale = unit, pivot = Offset.Zero) {
            scene.commands.forEachIndexed { index, command ->
                drawRaftMermaidCommand(command, texts[index])
            }
        }
    }
}

/**
 * Largest inline size (px, per side) a diagram may take. Compose packs a node's constraints into
 * one Long: both sides up to 2^15-1 px are always representable, larger ones only if the other
 * side is small, and an unrepresentable size throws during layout. A huge diagram (or a large
 * zoom) is scaled down to fit instead.
 */
internal const val RaftMermaidMaxInlineSidePx: Float = 32_000f

/** [scale] clamped so the inline diagram stays within [RaftMermaidMaxInlineSidePx] on both sides. */
internal fun raftMermaidInlineScale(sceneWidth: Float, sceneHeight: Float, scale: Float, density: Float): Float {
    if (!scale.isFinite() || scale <= 0f || !density.isFinite() || density <= 0f) return 0f
    val longestSide = maxOf(sceneWidth, sceneHeight)
    if (!longestSide.isFinite() || longestSide <= 0f) return 0f
    val maxScale = RaftMermaidMaxInlineSidePx / (longestSide * density)
    return minOf(scale, maxScale)
}

/** One measured layout per [DrawText] command (null for every other command), index-aligned. */
private fun raftMermaidMeasureTexts(
    scene: LayoutScene,
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
): List<TextLayoutResult?> =
    scene.commands.map { command ->
        if (command !is DrawText || command.text.isEmpty()) return@map null
        val style = command.style
        val color = raftMermaidParseSceneColor(style.color) ?: return@map null
        textMeasurer.measure(
            text = command.text,
            style =
                baseStyle.copy(
                    color = color,
                    fontSize = style.fontSize.toFloat().sp,
                    lineHeight = (style.fontSize.toFloat() * 1.2f).sp,
                    fontWeight = if (style.fontWeight >= 700) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    textAlign = TextAlign.Start,
                    background = Color.Unspecified,
                ),
            softWrap = false,
            constraints = Constraints(),
            // Measured in scene units: the draw scope is already scaled by density * zoom.
            density = Density(1f, 1f),
        )
    }

private fun DrawScope.drawRaftMermaidCommand(command: DrawCommand, text: TextLayoutResult?) {
    when (command) {
        is DrawRect -> drawRaftMermaidRect(command)
        is DrawEllipse -> drawRaftMermaidEllipse(command)
        is DrawLine -> drawRaftMermaidLine(command)
        is DrawPolyline -> drawRaftMermaidPolyline(command)
        is DrawPolygon -> drawRaftMermaidPolygon(command)
        is DrawText -> if (text != null) drawRaftMermaidText(command, text)
        else -> Unit
    }
}

private fun raftMermaidPathEffect(pattern: StrokePattern): PathEffect? =
    when (pattern) {
        StrokePattern.DASHED -> PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
        else -> null
    }

private fun DrawScope.drawRaftMermaidRect(command: DrawRect) {
    val rect = command.rect
    val x = rect.x.toFloat()
    val y = rect.y.toFloat()
    val w = rect.width.toFloat()
    val h = rect.height.toFloat()
    val r = command.cornerRadius.toFloat()
    val corner = if (r > 0f && r <= w / 2f && r <= h / 2f) CornerRadius(r) else CornerRadius.Zero
    raftMermaidParseSceneColor(command.fill)?.let { fill ->
        drawRoundRect(color = fill, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = corner)
    }
    val strokeWidth = command.strokeWidth.toFloat()
    val stroke = raftMermaidParseSceneColor(command.stroke)
    if (stroke != null && strokeWidth > 0f) {
        drawRoundRect(
            color = stroke,
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = corner,
            style = Stroke(width = strokeWidth),
        )
    }
}

private fun DrawScope.drawRaftMermaidEllipse(command: DrawEllipse) {
    val cx = command.center.x.toFloat()
    val cy = command.center.y.toFloat()
    val rx = command.radiusX.toFloat()
    val ry = command.radiusY.toFloat()
    val topLeft = Offset(cx - rx, cy - ry)
    val size = Size(rx * 2f, ry * 2f)
    raftMermaidParseSceneColor(command.fill, command.fillOpacity.toFloat())?.let { fill ->
        drawOval(color = fill, topLeft = topLeft, size = size)
    }
    val strokeWidth = command.strokeWidth.toFloat()
    val stroke = raftMermaidParseSceneColor(command.stroke)
    if (stroke != null && strokeWidth > 0f) {
        drawOval(color = stroke, topLeft = topLeft, size = size, style = Stroke(width = strokeWidth))
    }
}

private fun DrawScope.drawRaftMermaidLine(command: DrawLine) {
    val stroke = raftMermaidParseSceneColor(command.stroke) ?: return
    val strokeWidth = command.strokeWidth.toFloat()
    if (strokeWidth <= 0f) return
    drawLine(
        color = stroke,
        start = Offset(command.from.x.toFloat(), command.from.y.toFloat()),
        end = Offset(command.to.x.toFloat(), command.to.y.toFloat()),
        strokeWidth = strokeWidth,
        pathEffect = raftMermaidPathEffect(command.pattern),
    )
}

private fun DrawScope.drawRaftMermaidPolyline(command: DrawPolyline) {
    if (command.points.size < 2) return
    val stroke = raftMermaidParseSceneColor(command.stroke) ?: return
    val strokeWidth = command.strokeWidth.toFloat()
    if (strokeWidth <= 0f) return
    val path = Path()
    command.points.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x.toFloat(), point.y.toFloat())
        else path.lineTo(point.x.toFloat(), point.y.toFloat())
    }
    drawPath(
        path = path,
        color = stroke,
        style = Stroke(width = strokeWidth, pathEffect = raftMermaidPathEffect(command.pattern)),
    )
}

private fun DrawScope.drawRaftMermaidPolygon(command: DrawPolygon) {
    if (command.points.size < 3) return
    val fill = raftMermaidParseSceneColor(command.fill)
    val gradient = command.gradient
    if (fill == null && gradient == null) return
    val path = Path()
    command.points.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x.toFloat(), point.y.toFloat())
        else path.lineTo(point.x.toFloat(), point.y.toFloat())
    }
    path.close()
    if (gradient == null) {
        drawPath(path = path, color = fill ?: return)
        return
    }
    val opacity = gradient.opacity.toFloat()
    val fallback = fill ?: Color.Transparent
    drawPath(
        path = path,
        brush =
            Brush.linearGradient(
                colors =
                    listOf(
                        raftMermaidParseSceneColor(gradient.startColor, opacity) ?: fallback,
                        raftMermaidParseSceneColor(gradient.endColor, opacity) ?: fallback,
                    ),
                start = Offset(gradient.from.x.toFloat(), gradient.from.y.toFloat()),
                end = Offset(gradient.to.x.toFloat(), gradient.to.y.toFloat()),
            ),
    )
}

private fun DrawScope.drawRaftMermaidText(command: DrawText, layout: TextLayoutResult) {
    val width = layout.size.width.toFloat()
    val x = command.origin.x.toFloat()
    val left =
        when (command.anchor) {
            TextAnchor.START -> x
            TextAnchor.MIDDLE -> x - width / 2f
            TextAnchor.END -> x - width
            else -> x
        }
    val top = command.origin.y.toFloat() - layout.firstBaseline
    translate(left = left, top = top) {
        drawText(layout)
    }
}

/**
 * Parses a scene colour: `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa`, multiplied by [alpha].
 * `none` (and anything unparseable) means "do not paint", like the Kuikly renderer.
 */
internal fun raftMermaidParseSceneColor(color: SceneColor, alpha: Float = 1f): Color? {
    val value = color.value.trim()
    if (value.equals("none", ignoreCase = true) || !value.startsWith("#")) return null
    val hex = value.substring(1)
    fun channel(start: Int, length: Int): Int? {
        val part = hex.substring(start, start + length)
        return (if (length == 1) part.repeat(2) else part).toIntOrNull(16)
    }
    val rgba: List<Int?> =
        when (hex.length) {
            3 -> listOf(channel(0, 1), channel(1, 1), channel(2, 1), 255)
            4 -> listOf(channel(0, 1), channel(1, 1), channel(2, 1), channel(3, 1))
            6 -> listOf(channel(0, 2), channel(2, 2), channel(4, 2), 255)
            8 -> listOf(channel(0, 2), channel(2, 2), channel(4, 2), channel(6, 2))
            else -> return null
        }
    if (rgba.any { it == null }) return null
    val a = (rgba[3]!! / 255f) * alpha.coerceIn(0f, 1f)
    return Color(red = rgba[0]!!, green = rgba[1]!!, blue = rgba[2]!!, alpha = (a * 255f).toInt().coerceIn(0, 255))
}
