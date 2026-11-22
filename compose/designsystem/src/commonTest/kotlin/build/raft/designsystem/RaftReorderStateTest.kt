package build.raft.designsystem

import build.raft.designsystem.components.RaftReorderState
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
    fun aServerComingOrGoingMidDragDropsTheDrag() {
        val s = state("a", "b", "c")
        s.lift("a")
        s.dragBy(30f)
        s.sync(listOf("a", "c"))
        assertFalse(s.dragging)
        assertEquals(listOf("a", "c"), s.order)
        assertNull(s.drop())
    }

    @Test
    fun theAppsOldOrderAfterACommitIsStaleUntilTheEcho() {
        val s = state("a", "b", "c")
        s.lift("a")
        s.dragBy(30f)
        assertEquals(listOf("b", "a", "c"), s.drop())
        s.sync(listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), s.order, "the app hasn't taken the drop yet: no flicker back")
        s.sync(listOf("b", "a", "c"))
        s.sync(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), s.order, "after the echo, the app's order rules again (a failed save)")

        assertEquals(listOf("b", "a", "c"), s.moveBy("b", -1))
        s.sync(listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), s.order, "a screen-reader move too")
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
}
