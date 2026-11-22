package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task v2 (web `mobile-modal`; artin 2026-11-06, #proj-mobile:ec53b56a): the head is the
 * conversation's first row, at every page size (never pinned, artin 2026-11-19). While that row is in view the top bar has nothing to pull down; once it
 * is out of view the bar summarizes the task and pulls the head down over the messages (slock
 * #11722). Nothing opens on entry, each way of closing the panel (web `TaskHeadPullDown`) puts it
 * back up, and the row coming back into view drops it.
 */
class TaskHeadFloatStateTest {
    /** The head as the conversation opens (any page size): its row in view until the list says otherwise. */
    private fun opened(): TaskHeadScrollState = TaskHeadScrollState()

    /** The head scrolled out of view (a long conversation at its newest message). */
    private fun outOfView(): TaskHeadScrollState = opened().apply { updateHeadInView(false) }

    @Test
    fun `nothing is pulled down when the conversation opens`() {
        val state = opened()
        assertTrue(state.headInView)
        assertFalse(state.collapsed)
        assertFalse(state.floatOpen)
        // A long conversation opens at its newest message: the row is out of view, the bar
        // summarizes the task, and still nothing is pulled down until the reader asks.
        state.updateHeadInView(false)
        assertTrue(state.collapsed)
        assertFalse(state.floatOpen)
    }

    @Test
    fun `out of view a tap on the bar opens it and a second tap closes it`() {
        val state = outOfView()
        state.toggleFloat()
        assertTrue(state.floatOpen)
        state.toggleFloat()
        assertFalse(state.floatOpen)
    }

    @Test
    fun `scrolling the messages closes it`() {
        val state = outOfView()
        state.toggleFloat()
        state.connection.onPreScroll(Offset(0f, -12f), NestedScrollSource.UserInput)
        assertFalse(state.floatOpen)
        state.toggleFloat()
        state.connection.onPreScroll(Offset(0f, 12f), NestedScrollSource.UserInput)
        assertFalse(state.floatOpen)
        // A scroll the list makes itself (a fling settling, a new message) is not the reader's.
        state.toggleFloat()
        state.connection.onPreScroll(Offset(0f, -12f), NestedScrollSource.SideEffect)
        assertTrue(state.floatOpen)
        // The connection never takes any of the scroll: the messages scroll as before.
        assertEquals(Offset.Zero, state.connection.onPreScroll(Offset(0f, -12f), NestedScrollSource.UserInput))
    }

    @Test
    fun `back closes it first and says so and then leaves the page`() {
        val state = outOfView()
        assertFalse(state.closeFloat())
        state.toggleFloat()
        assertTrue(state.closeFloat())
        assertFalse(state.floatOpen)
        assertFalse(state.closeFloat())
    }
}
