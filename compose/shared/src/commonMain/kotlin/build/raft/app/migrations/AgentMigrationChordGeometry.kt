package build.raft.app

import androidx.compose.ui.geometry.Offset
import build.raft.app.data.AgentMigrationLogNode
import build.raft.app.data.AgentMigrationRouteCell
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Where the migration log's chord diagram puts things: web MigrationChord.tsx's layout (slock
 * staging 383e8553641a), in dp, with no drawing in it.
 */

/** What a tap, a press and hold or a menu acts on. */
internal sealed interface AgentMigrationChordTarget {
    data class Computer(val id: String) : AgentMigrationChordTarget

    data class Route(val src: String, val dst: String) : AgentMigrationChordTarget
}

/** A computer's arc, in radians clockwise from 3 o'clock: moves out up to [split], moves in after it. */
internal data class AgentMigrationChordSpan(val start: Float, val split: Float, val end: Float) {
    val mid: Float get() = (start + end) / 2
}

/** A ribbon's two ends on the ring: wide at its source, an arrow at its destination. */
internal data class AgentMigrationChordRibbon(val cell: AgentMigrationRouteCell, val from0: Float, val from1: Float, val to0: Float, val to1: Float) {
    val arrow: Float get() = (to0 + to1) / 2
    val arrowHalf: Float get() = minOf((to1 - to0) / 2, 0.02f)
}

internal enum class AgentMigrationChordAnchor { Start, Middle, End }

internal data class AgentMigrationChordLabel(val at: Offset, val anchor: AgentMigrationChordAnchor)

/**
 * The ring, laid out from every move on the server ([allCells]), so a filter only changes which
 * ribbons are lit; the computers never move. [size] is the chart's side in dp.
 */
internal class AgentMigrationChordLayout(
    val size: Float,
    nodes: List<AgentMigrationLogNode>,
    allCells: List<AgentMigrationRouteCell>,
) {
    val compact = size < 520
    val center = Offset(size / 2, size / 2)

    /** The inner edge of the out / in arcs, and where the ribbons end inside it. */
    val radius = size / 2 - if (compact) 70 else 118
    val ribbonRadius = radius - 6

    /** The two outer rings: thickness and gap, and the outermost edge. */
    val ringThickness = if (compact) 12f else 18f
    val ringGap = if (compact) 4f else 6f
    val ringOuter = radius + 10 + 2 * ringGap + 2 * ringThickness
    val labelSize = if (compact) 9f else 12f
    private val labelGap = if (compact) 6f else 10f

    /** Only computers with a drawn move take a place: one that never moved an agent would be a sliver. */
    val ring: List<AgentMigrationLogNode>
    val spans: Map<String, AgentMigrationChordSpan>
    val movesOut: Map<String, Int> = allCells.groupBy { it.src }.mapValues { (_, cells) -> cells.sumOf { it.volume } }
    val movesIn: Map<String, Int> = allCells.groupBy { it.dst }.mapValues { (_, cells) -> cells.sumOf { it.volume } }
    private val perMove: Float
    private val order: Map<String, Int>

    init {
        val busy = allCells.flatMapTo(mutableSetOf()) { listOf(it.src, it.dst) }
        ring = nodes.filter { it.id in busy }
        order = ring.withIndex().associate { (index, node) -> node.id to index }
        val groups = ring.map { it.group }.distinct()
        val total = ring.sumOf { weight(it.id).toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        perMove = (2 * PI.toFloat() - GAP * ring.size - GROUP_GAP * groups.size) / total
        val placed = mutableMapOf<String, AgentMigrationChordSpan>()
        var at = -PI.toFloat() / 2
        for (group in groups) {
            for (node in ring.filter { it.group == group }) {
                val width = weight(node.id) * perMove
                val out = (movesOut[node.id] ?: 0) / weight(node.id) * width
                placed[node.id] = AgentMigrationChordSpan(at, at + out, at + width)
                at += width + GAP
            }
            at += GROUP_GAP
        }
        spans = placed
    }

    private fun weight(id: String): Float = maxOf(0.5f, ((movesOut[id] ?: 0) + (movesIn[id] ?: 0)).toFloat())

    fun traffic(id: String): Int = (movesOut[id] ?: 0) + (movesIn[id] ?: 0)

    fun polar(angle: Float, radius: Float): Offset = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)

    /** Outgoing ribbons share the computer's "out" part in the order of their destinations, incoming its "in" part. */
    fun ribbons(cells: List<AgentMigrationRouteCell>): List<AgentMigrationChordRibbon> {
        val drawn = cells.filter { it.src in spans && it.dst in spans }
        val out = mutableMapOf<String, Float>()
        val into = mutableMapOf<String, Float>()
        val from = mutableMapOf<AgentMigrationRouteCell, Pair<Float, Float>>()
        for (cell in drawn.sortedBy { order.getValue(it.dst) }) {
            val start = out[cell.src] ?: spans.getValue(cell.src).start
            from[cell] = start to start + cell.volume * perMove
            out[cell.src] = from.getValue(cell).second
        }
        val to = mutableMapOf<AgentMigrationRouteCell, Pair<Float, Float>>()
        for (cell in drawn.sortedBy { order.getValue(it.src) }) {
            val start = into[cell.dst] ?: spans.getValue(cell.dst).split
            to[cell] = start to start + cell.volume * perMove
            into[cell.dst] = to.getValue(cell).second
        }
        return drawn.map { cell ->
            val (s0, s1) = from.getValue(cell)
            val (d0, d1) = to.getValue(cell)
            AgentMigrationChordRibbon(cell, s0, s1, d0, d1)
        }
    }

    /** Centred at the ring's top and bottom, else beginning or ending beside it; vertically centred on the point. */
    fun label(id: String): AgentMigrationChordLabel {
        val mid = spans.getValue(id).mid
        val anchor =
            when {
                abs(cos(mid)) < 0.3f -> AgentMigrationChordAnchor.Middle
                cos(mid) > 0 -> AgentMigrationChordAnchor.Start
                else -> AgentMigrationChordAnchor.End
            }
        val push = if (anchor == AgentMigrationChordAnchor.Middle) labelSize * 0.5f else abs(sin(mid)) * labelSize * 0.35f
        return AgentMigrationChordLabel(polar(mid, ringOuter + labelGap + push), anchor)
    }

    /**
     * Labels give way when computers sit close on the ring: busier ones are drawn first, and a label
     * closer than one label width to a drawn one is left out. [zoom] spreads them.
     */
    fun labelled(zoom: Float): Set<String> {
        val reach = (ringOuter + labelGap) * zoom
        val minGap = if (compact) 26f else 34f
        val drawn = mutableListOf<Float>()
        val keep = mutableSetOf<String>()
        for (node in ring.sortedByDescending { traffic(it.id) }) {
            val mid = spans.getValue(node.id).mid
            val near =
                drawn.any { other ->
                    val distance = abs(other - mid) % (2 * PI.toFloat())
                    minOf(distance, 2 * PI.toFloat() - distance) * reach < minGap
                }
            if (!near) {
                drawn += mid
                keep += node.id
            }
        }
        return keep
    }

    /** The computer whose whole arc band (out / in, success and residents) is under [point]. */
    fun computerAt(point: Offset): String? {
        val from = point - center
        val distance = hypot(from.x, from.y)
        if (distance < radius || distance > ringOuter) return null
        val angle = atan2(from.y, from.x)
        return spans.entries.firstOrNull { (_, span) ->
            // The spans run clockwise from the top; atan2 answers within half a turn of 3 o'clock.
            listOf(angle, angle + 2 * PI.toFloat()).any { it in span.start..span.end }
        }?.key
    }

    companion object {
        private const val GAP = 0.016f
        private const val GROUP_GAP = 0.07f
    }
}

/** Where [target] draws its step numbers: at its ribbon's arrow, later arrivals at one place staggered inward. */
internal fun AgentMigrationChordLayout.stepNumberAt(ribbon: AgentMigrationChordRibbon, earlierArrivals: Int): Offset =
    polar(ribbon.arrow - earlierArrivals * 0.13f, ribbonRadius - 30 - earlierArrivals * 26)
