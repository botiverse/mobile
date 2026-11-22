package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathHitTester
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentMigrationLogNode
import build.raft.app.data.AgentMigrationRouteCell
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.haptics.rememberRaftHaptics
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.PI

private const val MaxZoom = 4f

/** A press this close to a ribbon is on it: a one-move ribbon is about a pixel wide (web's invisible 8px edge). */
private const val RibbonReach = 4f

private class ChordRibbonShape(val ribbon: AgentMigrationChordRibbon, val path: Path) {
    val target = AgentMigrationChordTarget.Route(ribbon.cell.src, ribbon.cell.dst)
    val hit = PathHitTester(path)
}

/** What the chart says about one thing on it, to assistive tech. */
internal class AgentMigrationChordWords(
    val chart: String,
    val computer: (node: AgentMigrationLogNode, out: Int, into: Int) -> String,
    val route: (cell: AgentMigrationRouteCell) -> String,
)

/**
 * The server's migrations as a directed chord diagram (web `MigrationChord`). Each computer is an arc
 * on the ring, sized by its traffic and split into the moves out (solid) and in (outline); each route
 * is a ribbon, wide where it leaves and narrowing to an arrow where it arrives. Three outer rings: out
 * / in, the arrival success rate, and how many agents live there now.
 *
 * The ring is laid out from every move on the server, so a filter only changes which ribbons are lit;
 * the rest stays as a faint haze behind.
 *
 * A tap opens what is under it, a press and hold its menu, two fingers zoom; zoomed in, one finger
 * pans. At 1x one finger is the page's: it scrolls past the chart.
 */
@Composable
internal fun AgentMigrationChord(
    size: Dp,
    nodes: List<AgentMigrationLogNode>,
    /** Every route on the server: lays out the ring and draws the haze behind a filter. */
    allCells: List<AgentMigrationRouteCell>,
    /** The routes that pass the filters. */
    cells: List<AgentMigrationRouteCell>,
    filtered: Boolean,
    residents: Map<String, Int>,
    /** Keep only these routes lit (an opened item, a selected agent); null: all. */
    highlight: Set<AgentMigrationChordTarget.Route>?,
    /** One agent's completed moves in order, numbered at their arrows. */
    numbered: List<AgentMigrationChordTarget.Route>,
    words: AgentMigrationChordWords,
    onPress: (AgentMigrationChordTarget?) -> Unit,
    /** A press and hold, with where it was in the chart. */
    onHold: (AgentMigrationChordTarget, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val layout = remember(size, nodes, allCells) { AgentMigrationChordLayout(size.value, nodes, allCells) }
    val shapes =
        remember(layout, cells) {
            layout.ribbons(cells).sortedBy { it.cell.volume }.map { ChordRibbonShape(it, layout.ribbonPath(it)) }
        }
    val haze = remember(layout, allCells, filtered) { if (filtered) layout.ribbons(allCells).map(layout::ribbonPath) else emptyList() }
    val touched = remember(cells) { cells.flatMapTo(mutableSetOf()) { listOf(it.src, it.dst) } }
    var zoom by remember(layout) { mutableFloatStateOf(1f) }
    // Where the drawing's origin is, in dp, as the zoom moves it.
    var pan by remember(layout) { mutableStateOf(Offset.Zero) }
    // Labels re-space once a zoom has settled (a whole step), not on each of its frames.
    val labelled = remember(layout, (zoom * 4).toInt()) { layout.labelled(zoom) }
    val measurer = rememberTextMeasurer()
    val mono = RaftTheme.typography.monoCode
    val currentPress by rememberUpdatedState(onPress)
    val currentHold by rememberUpdatedState(onHold)
    val density = LocalDensity.current.density
    val haptics = rememberRaftHaptics()

    fun targetAt(position: Offset): AgentMigrationChordTarget? {
        val point = (position / density - pan) / zoom
        layout.computerAt(point)?.let { return AgentMigrationChordTarget.Computer(it) }
        val reach = RibbonReach / zoom
        val near = listOf(Offset.Zero, Offset(reach, 0f), Offset(-reach, 0f), Offset(0f, reach), Offset(0f, -reach))
        // The busiest ribbons are drawn last, on top.
        return near.firstNotNullOfOrNull { by -> shapes.lastOrNull { it.hit.contains(point + by) }?.target }
    }

    fun clamp(offset: Offset, scale: Float): Offset {
        val least = size.value * (1 - scale)
        return Offset(offset.x.coerceIn(least, 0f), offset.y.coerceIn(least, 0f))
    }

    val labelStyle = mono.copy(fontSize = layout.labelSize.sp, fontWeight = FontWeight.Bold)
    val drawComputer: DrawScope.(AgentMigrationLogNode) -> Unit = { node ->
        drawChordComputer(
            layout = layout,
            node = node,
            arrivals = cells.filter { it.dst == node.id },
            residents = residents[node.id] ?: 0,
            mostResidents = residents.values.maxOrNull() ?: 1,
            hairline = 1f / zoom,
            ink = colors.foreground,
            track = colors.fillMuted,
            good = RaftUiPalette.success,
            bad = colors.warningGraphic,
        )
    }
    val drawLabel: DrawScope.(AgentMigrationLogNode) -> Unit = { node ->
        // Codes keep their size through a zoom.
        val label = layout.label(node.id)
        val at = Offset((label.at.x * zoom + pan.x) * density, (label.at.y * zoom + pan.y) * density)
        drawChordText(measurer, node.code, labelStyle.copy(color = colors.foreground), at, label.anchor)
    }
    Box(modifier.size(size).clipToBounds()) {
        if (filtered) {
            // What the filter left out: the routes as a haze, the computers faint, both blurred.
            Canvas(Modifier.size(size).alpha(0.28f).blur(chordBlur(if (size < ChordCompact) 2.5f else 3.5f))) {
                chordTransform(pan, zoom) { haze.forEach { drawPath(it, colors.foreground) } }
            }
            Canvas(Modifier.size(size).alpha(0.4f).blur(chordBlur(1.2f))) {
                val off = layout.ring.filter { it.id !in touched }
                chordTransform(pan, zoom) { off.forEach { drawComputer(it) } }
                off.forEach { if (it.id in labelled) drawLabel(it) }
            }
        }
        Canvas(
            Modifier
                .size(size)
                .testTag(AgentMigrationLogTags.Chart)
                .semantics {
                    contentDescription = words.chart
                    // Every lit computer and route opens from here: the chart itself is one element.
                    customActions =
                        layout.ring.filter { !filtered || it.id in touched }.map { node ->
                            CustomAccessibilityAction(words.computer(node, layout.movesOut[node.id] ?: 0, layout.movesIn[node.id] ?: 0)) {
                                currentPress(AgentMigrationChordTarget.Computer(node.id))
                                true
                            }
                        } +
                            shapes.map { shape ->
                                CustomAccessibilityAction(words.route(shape.ribbon.cell)) {
                                    currentPress(shape.target)
                                    true
                                }
                            }
                }
                .pointerInput(layout, shapes) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val target = targetAt(start)
                        val holdAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        var last = down.uptimeMillis
                        var held = false
                        var moved = false
                        var pinched = false
                        var taken = false
                        while (true) {
                            val waits = target != null && !held && !moved && !pinched
                            val event =
                                try {
                                    if (waits) withTimeout((holdAt - last).coerceAtLeast(1)) { awaitPointerEvent() } else awaitPointerEvent()
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    held = true
                                    if (target != null) {
                                        haptics.longPress()
                                        currentHold(target, start / density)
                                    }
                                    continue
                                }
                            last = event.changes.firstOrNull()?.uptimeMillis ?: last
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (held) event.changes.forEach { it.consume() }
                                if (!held && !moved && !pinched && !taken) currentPress(target)
                                break
                            }
                            if (pressed.size >= 2) {
                                pinched = true
                                val by = event.calculateZoom()
                                val centroid = event.calculateCentroid() / density
                                val next = (zoom * by).coerceIn(1f, MaxZoom)
                                // The point under the fingers stays under them.
                                val anchor = (centroid - pan) / zoom
                                pan = clamp(centroid - anchor * next + event.calculatePan() / density, next)
                                zoom = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }
                            val change = pressed.single()
                            if (change.isConsumed) taken = true
                            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (held) {
                                change.consume()
                            } else if (zoom > 1f && (moved || pinched)) {
                                pan = clamp(pan + (change.position - change.previousPosition) / density, zoom)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            val unit = density
            val on = layout.ring.filter { !filtered || it.id in touched }
            chordTransform(pan, zoom) {
                // Lines keep their width through a zoom.
                val hairline = 1f / zoom
                shapes.forEach { shape ->
                    val cell = shape.ribbon.cell
                    val lit = highlight == null || shape.target in highlight
                    val alpha =
                        when {
                            !lit -> 0.12f
                            highlight != null -> 0.95f
                            filtered -> 0.8f
                            else -> 0.6f * (0.4f + 0.6f * minOf(1f, cell.volume / 4f))
                        }
                    // A route that failed at least half the time.
                    val failing = cell.failed * 2 >= cell.volume
                    drawPath(shape.path, if (failing) colors.warningGraphic else colors.foreground, alpha = alpha)
                    if (highlight != null && lit) {
                        drawPath(shape.path, colors.foreground, style = Stroke(1.5f * hairline, join = StrokeJoin.Round))
                    }
                }
                on.forEach { drawComputer(it) }
            }
            // Step numbers keep their size through a zoom, as codes do.
            val place: (Offset) -> Offset = { Offset((it.x * zoom + pan.x) * unit, (it.y * zoom + pan.y) * unit) }
            val ribbons = shapes.associateBy { it.target }
            numbered.forEachIndexed { index, route ->
                val ribbon = ribbons[route]?.ribbon ?: return@forEachIndexed
                val at = place(layout.stepNumberAt(ribbon, earlierArrivals = numbered.take(index).count { it.dst == route.dst }))
                drawCircle(colors.layerCard, radius = 9f * unit, center = at)
                drawCircle(colors.foreground, radius = 9f * unit, center = at, style = Stroke(unit))
                drawChordText(measurer, (index + 1).toString(), mono.copy(fontSize = 11.sp, color = colors.foreground), at, AgentMigrationChordAnchor.Middle)
            }
            on.forEach { if (filtered || it.id in labelled) drawLabel(it) }
        }
        if (zoom > 1f) {
            RaftIconButton(
                onClick = {
                    zoom = 1f
                    pan = Offset.Zero
                },
                tooltip = stringResource(Res.string.agent_migration_log_reset_zoom),
                modifier = Modifier.align(Alignment.TopEnd).testTag(AgentMigrationLogTags.ResetZoom),
                variant = RaftButtonVariant.Ghost,
            ) { RaftSlotIcon(RaftLucideIconName.ZoomOut) }
        }
    }
}

/** Web's `size < 520`: a phone's chart. */
private val ChordCompact = 520.dp

/** The blur radius whose standard deviation is [deviation] dp (a blur's sigma is 0.577 of its radius). */
private fun chordBlur(deviation: Float): Dp = (deviation / 0.577f).dp

/** Draws in the chart's own dp, as it is zoomed and panned. */
private inline fun DrawScope.chordTransform(pan: Offset, zoom: Float, block: DrawScope.() -> Unit) {
    val unit = density
    withTransform({
        translate(pan.x * unit, pan.y * unit)
        scale(zoom * unit, zoom * unit, pivot = Offset.Zero)
    }) { block() }
}

/** A computer's rings: moves out and in, how much of what arrived succeeded, and who lives there now. */
private fun DrawScope.drawChordComputer(
    layout: AgentMigrationChordLayout,
    node: AgentMigrationLogNode,
    arrivals: List<AgentMigrationRouteCell>,
    residents: Int,
    mostResidents: Int,
    hairline: Float,
    ink: Color,
    track: Color,
    good: Color,
    bad: Color,
) {
    val span = layout.spans.getValue(node.id)
    val inner = layout.radius
    val arcs = inner + 10
    if (span.split > span.start) drawPath(layout.arcPath(span.start, span.split, inner, arcs), ink)
    if (span.end > span.split) drawPath(layout.arcPath(span.split, span.end, inner, arcs), ink, style = Stroke(1.5f * hairline))
    val success = arcs + layout.ringGap
    drawPath(layout.arcPath(span.start, span.end, success, success + layout.ringThickness), track)
    val total = arrivals.sumOf { it.volume }
    if (total > 0) {
        val rate = arrivals.sumOf { it.ok }.toFloat() / total
        if (rate > 0f) {
            drawPath(layout.arcPath(span.start, span.end, success, success + layout.ringThickness * rate), if (rate < 0.6f) bad else good)
        }
    }
    if (residents > 0) {
        val lives = success + layout.ringThickness + layout.ringGap
        drawPath(
            layout.arcPath(span.start, span.end, lives, lives + layout.ringThickness * residents / maxOf(1, mostResidents)),
            ink,
            alpha = 0.7f,
        )
    }
}

private fun DrawScope.drawChordText(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset, anchor: AgentMigrationChordAnchor) {
    val measured = measurer.measure(text, style, maxLines = 1, softWrap = false)
    val x =
        when (anchor) {
            AgentMigrationChordAnchor.Start -> at.x
            AgentMigrationChordAnchor.Middle -> at.x - measured.size.width / 2f
            AgentMigrationChordAnchor.End -> at.x - measured.size.width
        }
    drawText(measured, topLeft = Offset(x, at.y - measured.size.height / 2f))
}

private fun AgentMigrationChordLayout.circle(radius: Float) =
    Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

private fun degrees(radians: Float): Float = radians * 180f / PI.toFloat()

/** The band between two radii over an arc. */
private fun AgentMigrationChordLayout.arcPath(from: Float, to: Float, inner: Float, outer: Float): Path =
    Path().apply {
        arcTo(circle(outer), degrees(from), degrees(to - from), forceMoveTo = true)
        arcTo(circle(inner), degrees(to), degrees(from - to), forceMoveTo = false)
        close()
    }

/** Wide along the ring at its source, through the middle to an arrow at its destination. */
private fun AgentMigrationChordLayout.ribbonPath(ribbon: AgentMigrationChordRibbon): Path =
    Path().apply {
        arcTo(circle(ribbonRadius), degrees(ribbon.from0), degrees(ribbon.from1 - ribbon.from0), forceMoveTo = true)
        val left = polar(ribbon.arrow - ribbon.arrowHalf, ribbonRadius - 16)
        quadraticTo(center.x, center.y, left.x, left.y)
        val tip = polar(ribbon.arrow, ribbonRadius - 2)
        lineTo(tip.x, tip.y)
        val right = polar(ribbon.arrow + ribbon.arrowHalf, ribbonRadius - 16)
        lineTo(right.x, right.y)
        val back = polar(ribbon.from0, ribbonRadius)
        quadraticTo(center.x, center.y, back.x, back.y)
        close()
    }
