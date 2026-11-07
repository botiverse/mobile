package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task v2 on a phone (web `mobile-modal`; artin 2026-11-06, #proj-mobile:ec53b56a): the head is the
 * conversation's first row. While that row is in view the top bar has nothing to pull down; once it
 * is out of view the bar summarizes the task and pulls the head down over the messages (slock
 * #11722). Nothing opens on entry, each way of closing the panel (web `TaskHeadPullDown`) puts it
 * back up, and the row coming back into view drops it.
 */
class TaskHeadFloatStateTest {
    /** A phone's head as the conversation opens: its row in view until the list says otherwise. */
    private fun onAPhone(): TaskHeadScrollState = TaskHeadScrollState().apply { updatePhone(true) }

    /** A phone's head scrolled out of view (a long conversation at its newest message). */
    private fun outOfView(): TaskHeadScrollState = onAPhone().apply { updateHeadInView(false) }

    @Test
    fun `nothing is pulled down when the conversation opens`() {
        val state = onAPhone()
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
    fun `while the row is in view the bar has nothing to pull down`() {
        val state = onAPhone()
        state.toggleFloat()
        assertFalse(state.floatOpen)
        // It does not come down later on its own once the row goes out of view.
        state.updateHeadInView(false)
        assertFalse(state.floatOpen)
    }

    @Test
    fun `off a phone the head is pinned and the bar has nothing to pull down`() {
        val pinned = TaskHeadScrollState().apply { updatePhone(false) }
        pinned.updateHeadInView(false)
        assertFalse(pinned.collapsed)
        pinned.toggleFloat()
        assertFalse(pinned.floatOpen)
        // Pulled down on a phone, then the page widens: the head is pinned, the panel goes.
        val state = outOfView()
        state.toggleFloat()
        assertTrue(state.floatOpen)
        state.updatePhone(false)
        assertFalse(state.floatOpen)
        state.updatePhone(true)
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
    fun `the row coming into view drops it`() {
        val state = outOfView()
        state.toggleFloat()
        assertTrue(state.floatOpen)
        state.updateHeadInView(true)
        assertFalse(state.floatOpen)
        assertFalse(state.collapsed)
        // Out of view again: the bar summarizes, the panel stays up until asked for.
        state.updateHeadInView(false)
        assertTrue(state.collapsed)
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

    @Test
    fun `any of the head on screen is in view`() {
        // Forward terms: the viewport runs 0..1000; the head is the top 300px of a 340px row.
        fun inView(rowOffset: Int?) =
            taskHeadRowInView(rowOffsetPx = rowOffset, rowSizePx = 340, headHeightPx = 300, viewportStartPx = 0, viewportEndPx = 1000)
        assertTrue(inView(0))
        assertTrue(inView(-299))
        // Only the row's "Beginning of conversation" under the head is left on screen.
        assertFalse(inView(-300))
        assertFalse(inView(-320))
        assertTrue(inView(999))
        assertFalse(inView(1000))
        // Not laid out: scrolled far away (a long conversation at its newest message).
        assertFalse(inView(null))
        // The head not measured yet: the row stands for it.
        assertTrue(taskHeadRowInView(rowOffsetPx = -320, rowSizePx = 340, headHeightPx = 0, viewportStartPx = 0, viewportEndPx = 1000))
        // A top content padding: the viewport starts above the content.
        assertTrue(taskHeadRowInView(rowOffsetPx = -310, rowSizePx = 340, headHeightPx = 300, viewportStartPx = -40, viewportEndPx = 1000))
    }

    @Test
    fun `a press below the panel is outside it and one on it is not`() {
        assertTrue(taskHeadFloatPressIsOutside(rootY = 501f, floatBottomInRootPx = 500f))
        assertFalse(taskHeadFloatPressIsOutside(rootY = 500f, floatBottomInRootPx = 500f))
        assertFalse(taskHeadFloatPressIsOutside(rootY = 10f, floatBottomInRootPx = 500f))
    }

    @Test
    fun `the bar shows the assignee face and the panel only on a phone`() {
        val task =
            parseSharedServerTasksPayload(
                """
                {"tasks":[{"id":"task-1","messageId":"task-1","hostMessageId":null,"sourceMessageId":null,"conversationReplyCount":0,
                 "conversationChannelId":"conv-1","conversationAnchored":true,"channelId":"channel-1","channelName":"general",
                 "channelType":"channel","taskNumber":7,"title":"Ship the bar","description":null,"status":"in_progress",
                 "createdByType":"user","createdById":"user-1","createdByName":"Artin","claimedByType":"agent","claimedById":"agent-1",
                 "claimedByName":"Lagoon","createdAt":"2026-10-27T09:00:00.000Z","updatedAt":"2026-10-27T09:30:00.000Z","revision":1,
                 "isLegacy":false,"readOnlyReason":null}]}
                """.trimIndent()
            )?.tasks?.single() ?: error("task dropped")
        var toggled = 0
        val away =
            task.taskConversationHeaderChrome(
                state = TaskConversationHeaderState(ChannelId("conv-1")),
                summarized = true,
                headFloatOpen = true,
                onToggleHeadFloat = { toggled++ },
                onViewInChannel = {},
                onToggleFollow = {},
            )
        assertEquals("Lagoon", away.assigneeName)
        assertEquals(MemberKind.Agent, away.assigneeKind)
        assertTrue(away.headFloatOpen)
        away.onToggleHeadFloat()
        assertEquals(1, toggled)
        val inView = task.taskConversationHeaderChrome(TaskConversationHeaderState(ChannelId("conv-1")), summarized = false, headFloatOpen = true, onToggleHeadFloat = {}, onViewInChannel = {}, onToggleFollow = {})
        assertFalse(inView.headFloatOpen)
        val unassigned = task.copy(assignee = null).taskConversationHeaderChrome(TaskConversationHeaderState(ChannelId("conv-1")), true, false, {}, {}, {})
        assertNull(unassigned.assigneeName)
    }
}
