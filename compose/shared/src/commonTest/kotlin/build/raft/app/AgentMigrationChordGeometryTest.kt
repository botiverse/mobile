package build.raft.app

import build.raft.app.data.AgentMigrationLogNode
import build.raft.app.data.AgentMigrationRouteCell
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMigrationChordGeometryTest {
    private fun node(id: String, group: String = "g") = AgentMigrationLogNode(id, id, id.uppercase(), group, known = true)

    private val cells = listOf(AgentMigrationRouteCell("a", "b", 3, 0), AgentMigrationRouteCell("b", "a", 1, 0), AgentMigrationRouteCell("a", "c", 0, 2))
    private val layout = AgentMigrationChordLayout(360f, listOf(node("a"), node("b"), node("c", "h"), node("idle")), cells)

    @Test
    fun theRingHoldsTheComputersThatMovedInTheirOrder() {
        assertEquals(listOf("a", "b", "c"), layout.ring.map { it.id })
        val spans = layout.ring.map { layout.spans.getValue(it.id) }
        assertEquals(-PI.toFloat() / 2, spans.first().start)
        // a: 5 out, 1 in; b: 1 out, 3 in; c: 2 in.
        assertTrue(spans[0].split > spans[0].start && spans[0].end > spans[0].split)
        assertEquals(spans[2].start, spans[2].split)
        // The whole turn, less a gap per computer and per group.
        val end = spans.last().end + 0.016f + 0.07f
        assertEquals(1.5f * PI.toFloat(), end, 0.001f)
    }

    @Test
    fun ribbonsFillTheirComputersOutAndInParts() {
        val ribbons = layout.ribbons(cells).associateBy { it.cell.src + it.cell.dst }
        val a = layout.spans.getValue("a")
        assertEquals(a.start, ribbons.getValue("ab").from0)
        assertEquals(ribbons.getValue("ab").from1, ribbons.getValue("ac").from0)
        assertEquals(a.split, ribbons.getValue("ac").from1, 0.001f)
        assertEquals(a.split, ribbons.getValue("ba").to0)
        assertEquals(a.end, ribbons.getValue("ba").to1, 0.001f)
        // A filter's ribbons sit on the same ring.
        assertEquals(1, layout.ribbons(cells.take(1)).size)
        assertTrue(layout.ribbons(listOf(AgentMigrationRouteCell("idle", "a", 1, 0))).isEmpty())
    }

    @Test
    fun aPointOnTheBandIsItsComputer() {
        for (node in layout.ring) {
            val mid = layout.spans.getValue(node.id).mid
            assertEquals(node.id, layout.computerAt(layout.polar(mid, layout.radius + 5)))
            assertEquals(node.id, layout.computerAt(layout.polar(mid, layout.ringOuter - 1)))
            assertNull(layout.computerAt(layout.polar(mid, layout.ringOuter + 1)))
        }
        assertNull(layout.computerAt(layout.center))
    }

    @Test
    fun theBusiestLabelsStayWhenComputersSitClose() {
        val many = (1..60).map { node("n$it") }
        val crowded = AgentMigrationChordLayout(360f, many, many.drop(1).map { AgentMigrationRouteCell("n1", it.id, 1, 0) })
        val shown = crowded.labelled(1f)
        assertTrue("n1" in shown && shown.size < 60)
        assertTrue(crowded.labelled(4f).size > shown.size)
    }

    @Test
    fun labelsSitBesideTheRingAndCentreAtItsTopAndBottom() {
        val four = listOf("a", "b", "c", "d").map { node(it) }
        val ring = AgentMigrationChordLayout(640f, four, listOf(AgentMigrationRouteCell("a", "b", 1, 0), AgentMigrationRouteCell("c", "d", 1, 0)))
        assertEquals(AgentMigrationChordAnchor.Start, ring.label("a").anchor)
        assertEquals(AgentMigrationChordAnchor.End, ring.label("d").anchor)
    }
}
