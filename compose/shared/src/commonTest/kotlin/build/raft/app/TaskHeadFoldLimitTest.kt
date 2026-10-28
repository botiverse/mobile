package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task v2 on a phone (#proj-mobile:#69, Kirby): the conversation stays bottom-anchored, so the head
 * folds only as far as the conversation has rows to show in its place; a short conversation keeps
 * the head in view instead of opening a blank gap above "Beginning of conversation".
 */
class TaskHeadFoldLimitTest {
    /** A phone's head, measured at 300px and shown whole, over a conversation overflowing by [overflowPx]. */
    private fun head(overflowPx: Float): TaskHeadScrollState =
        TaskHeadScrollState().apply {
            scrollsWithConversation = true
            updateHeight(300f)
            shownHeightPx = 300f
            minShownHeightPx = taskHeadMinShownHeightPx(shownPx = 300f, overflowPx = overflowPx)
        }

    /** The reader drags the messages up by [px] (towards newer ones); returns what the head took. */
    private fun TaskHeadScrollState.dragUp(px: Float): Float = 0f - connection.onPreScroll(Offset(0f, -px), NestedScrollSource.UserInput).y

    @Test
    fun `the head keeps what the conversation cannot fill and may fold the rest`() {
        // Rows out of view: the conversation fills any room, the head folds all the way.
        assertEquals(Float.NEGATIVE_INFINITY, taskHeadMinShownHeightPx(shownPx = 300f, overflowPx = Float.POSITIVE_INFINITY))
        assertEquals(300f, taskHeadFoldLimitPx(heightPx = 300f, minShownPx = Float.NEGATIVE_INFINITY))
        // Content 200px shorter than the viewport under the whole head: none of it may go.
        assertEquals(500f, taskHeadMinShownHeightPx(shownPx = 300f, overflowPx = -200f))
        assertEquals(0f, taskHeadFoldLimitPx(heightPx = 300f, minShownPx = 500f))
        // Overflowing by 120: the head folds 120.
        assertEquals(180f, taskHeadMinShownHeightPx(shownPx = 300f, overflowPx = 120f))
        assertEquals(120f, taskHeadFoldLimitPx(heightPx = 300f, minShownPx = 180f))
        // Measured with 80px already away and 40 still overflowing: the same 120.
        assertEquals(180f, taskHeadMinShownHeightPx(shownPx = 220f, overflowPx = 40f))
        // A head taller than its share of the page shows only its top: folding the hidden rest moves nothing.
        assertEquals(570f, taskHeadFoldLimitPx(heightPx = 1000f, minShownPx = taskHeadMinShownHeightPx(shownPx = 480f, overflowPx = 50f)))
        assertEquals(0f, taskHeadFoldLimitPx(heightPx = 0f, minShownPx = Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `a head that grows while folded keeps the same room for the conversation`() {
        // Folded 45px against a list overflowing by 45, then Show More appears and the head grows by 108.
        val state = head(overflowPx = 45f)
        state.collapse()
        assertEquals(45f, state.offsetPx)
        state.updateHeight(408f)
        assertEquals(153f, state.offsetPx)
        // The head measures again before the list does: the limit holds.
        state.shownHeightPx = 408f - 153f
        assertEquals(153f, state.offsetPx)
    }

    @Test
    fun `the overflow is exact once every row is laid out and unbounded before`() {
        assertEquals(
            Float.POSITIVE_INFINITY,
            taskHeadConversationOverflowPx(totalRows = 40, laidOutRowHeights = listOf(200, 200, 200), rowSpacingPx = 0, paddingPx = 0, viewportPx = 500),
        )
        // Three rows, two gaps of 8, 24 of padding, in a 1000px viewport: 400px short.
        assertEquals(
            -400f,
            taskHeadConversationOverflowPx(totalRows = 3, laidOutRowHeights = listOf(200, 200, 160), rowSpacingPx = 8, paddingPx = 24, viewportPx = 1000),
        )
        assertEquals(
            60f,
            taskHeadConversationOverflowPx(totalRows = 3, laidOutRowHeights = listOf(200, 200, 160), rowSpacingPx = 0, paddingPx = 0, viewportPx = 500),
        )
        assertEquals(-500f, taskHeadConversationOverflowPx(totalRows = 0, laidOutRowHeights = emptyList(), rowSpacingPx = 8, paddingPx = 0, viewportPx = 500))
    }

    @Test
    fun `a short conversation keeps the head in view through a swipe up and the keyboard`() {
        val state = head(overflowPx = -250f)
        assertEquals(0f, state.dragUp(120f))
        assertEquals(0f, state.offsetPx)
        state.collapse()
        assertEquals(0f, state.offsetPx)
        assertFalse(state.collapsed)
        // Nothing to pull down from the bar either: the head is right there.
        state.toggleFloat()
        assertFalse(state.floatOpen)
    }

    @Test
    fun `a conversation a little taller than its viewport folds the head only that far`() {
        val state = head(overflowPx = 90f)
        // The head takes 90px of the drag; the rest is the list's (it is at its newest row).
        assertEquals(90f, state.dragUp(200f))
        assertEquals(90f, state.offsetPx)
        assertFalse(state.collapsed)
        // A drag back down at the oldest row brings it back.
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
        assertEquals(50f, state.offsetPx)
    }

    @Test
    fun `a long conversation folds the head away as before`() {
        val state = head(overflowPx = Float.POSITIVE_INFINITY)
        assertEquals(300f, state.dragUp(400f))
        assertTrue(state.collapsed)
    }

    @Test
    fun `when the conversation stops filling the room the head comes back down and goes away again once it does`() {
        val state = head(overflowPx = Float.POSITIVE_INFINITY)
        state.collapse()
        assertTrue(state.collapsed)
        // The list laid out with the head away (none of it shown): its content now falls 100px short.
        state.minShownHeightPx = taskHeadMinShownHeightPx(shownPx = 0f, overflowPx = -100f)
        assertEquals(200f, state.offsetPx)
        assertFalse(state.collapsed)
        // New messages fill it again: the head, still asked away, folds the rest.
        state.minShownHeightPx = taskHeadMinShownHeightPx(shownPx = 100f, overflowPx = Float.POSITIVE_INFINITY)
        assertTrue(state.collapsed)
    }
}
