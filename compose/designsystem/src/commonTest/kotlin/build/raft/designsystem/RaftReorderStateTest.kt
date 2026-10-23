package build.raft.designsystem

import build.raft.designsystem.components.RaftReorderState
import build.raft.designsystem.components.raftReorderAutoScrollDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hold-then-drag reorder of a keyed column (artin 10-23: the server panel's rows). */
class RaftReorderStateTest {
    private fun state(vararg keys: String, height: Int = 40) =
        RaftReorderState(keys.toList()).apply { keys.forEach { setHeight(it, height) } }

    @Test
    fun dragPastANeighboursMiddleTakesItsSlot() {
        val s = state("a", "b", "c", "d")
        assertTrue(s.lift("b"))
        s.dragBy(19f)
        assertEquals(listOf("a", "b", "c", "d"), s.order, "not past c's middle yet")
        s.dragBy(2f)
        assertEquals(listOf("a", "c", "b", "d"), s.order)
        assertEquals(-19f, s.liftedOffset, "the row stays under the finger in its new slot")
        s.dragBy(40f)
        assertEquals(listOf("a", "c", "d", "b"), s.order)
        s.dragBy(400f)
        assertEquals(listOf("a", "c", "d", "b"), s.order, "the last slot holds")
        s.dragBy(-500f)
        assertEquals(listOf("b", "a", "c", "d"), s.order, "and back up to the first")
    }

    @Test
    fun dropCommitsAChangedOrderOnce() {
        val s = state("a", "b", "c")
        s.lift("a")
        s.dragBy(30f)
        assertEquals(listOf("b", "a", "c"), s.drop())
        assertNull(s.liftedKey)
        assertEquals(0f, s.liftedOffset)
        assertEquals(listOf("b", "a", "c"), s.order)
        assertNull(s.drop(), "nothing is lifted any more")
    }

    @Test
    fun aHoldLetGoWithoutMovingCommitsNothing() {
        val s = state("a", "b")
        s.lift("b")
        s.dragBy(5f)
        s.dragBy(-5f)
        assertNull(s.drop())
        assertEquals(listOf("a", "b"), s.order)
    }

    @Test
    fun cancelPutsTheOrderBack() {
        val s = state("a", "b", "c")
        s.lift("c")
        s.dragBy(-80f)
        assertEquals(listOf("c", "a", "b"), s.order)
        s.cancel()
        assertEquals(listOf("a", "b", "c"), s.order)
        assertFalse(s.dragging)
    }

    @Test
    fun anIncomingOrderWaitsForTheDrop() {
        val s = state("a", "b", "c")
        s.lift("a")
        s.sync(listOf("c", "b", "a"))
        assertEquals(listOf("a", "b", "c"), s.order, "rows don't jump under the finger")
        assertNull(s.drop())
        assertEquals(listOf("c", "b", "a"), s.order, "an unchanged drop takes the newer order")

        s.lift("c")
        s.sync(listOf("a", "b", "c"))
        s.dragBy(30f)
        assertEquals(listOf("b", "c", "a"), s.drop(), "a changed drop wins; the app echoes it")
        assertEquals(listOf("b", "c", "a"), s.order)
    }

    @Test
    fun oneRowAtATimeAndOnlyKnownRows() {
        val s = state("a", "b")
        assertFalse(s.lift("z"))
        assertTrue(s.lift("a"))
        assertFalse(s.lift("b"))
        assertNull(s.moveBy("b", -1), "no screen-reader move while a row is lifted")
    }

    @Test
    fun unmeasuredRowsAreNeverSwappedPast() {
        val s = RaftReorderState(listOf("a", "b"))
        s.lift("a")
        s.dragBy(100f)
        assertEquals(listOf("a", "b"), s.order)
        assertEquals(100f, s.liftedOffset)
    }

    @Test
    fun screenReaderMovesUpAndDown() {
        val s = state("a", "b", "c")
        assertFalse(s.canMove("a", -1))
        assertTrue(s.canMove("a", 1))
        assertEquals(listOf("b", "a", "c"), s.moveBy("a", 1))
        assertEquals(listOf("b", "a", "c"), s.order)
        assertEquals(listOf("a", "b", "c"), s.moveBy("a", -1))
        assertNull(s.moveBy("c", 1))
        assertNull(s.moveBy("a", -1))
    }

    @Test
    fun aLiftedRowNearAnEdgeScrollsTheList() {
        fun delta(y: Float, back: Boolean = true, forward: Boolean = true) =
            raftReorderAutoScrollDelta(y, top = 100f, bottom = 500f, zone = 40f, step = 8f, canScrollBackward = back, canScrollForward = forward)
        assertEquals(-8f, delta(120f))
        assertEquals(8f, delta(480f))
        assertEquals(0f, delta(300f))
        assertEquals(0f, delta(120f, back = false), "already at the top")
        assertEquals(0f, delta(480f, forward = false), "already at the bottom")
    }
}
