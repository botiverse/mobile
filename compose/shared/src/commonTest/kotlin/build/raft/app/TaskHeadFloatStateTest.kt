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
 * Task v2 on a phone (slock #11722): once the head has scrolled away the top bar pulls it down over
 * the messages, and each way of closing it (web `TaskHeadPullDown`) puts it back up.
 */
class TaskHeadFloatStateTest {
    /** A phone's head, measured at 300px and scrolled out of view. */
    private fun scrolledAway(): TaskHeadScrollState =
        TaskHeadScrollState().apply {
            scrollsWithConversation = true
            updateHeight(300f)
            collapse()
        }

    @Test
    fun `while the head is in view the bar has nothing to pull down`() {
        val state = TaskHeadScrollState().apply { scrollsWithConversation = true; updateHeight(300f) }
        state.toggleFloat()
        assertFalse(state.floatOpen)
        // Off a phone the head never leaves: no panel either.
        val pinned = TaskHeadScrollState().apply { updateHeight(300f); collapse() }
        pinned.toggleFloat()
        assertFalse(pinned.floatOpen)
    }

    @Test
    fun `a tap on the bar opens the head and a second tap closes it`() {
        val state = scrolledAway()
        assertTrue(state.collapsed)
        state.toggleFloat()
        assertTrue(state.floatOpen)
        state.toggleFloat()
        assertFalse(state.floatOpen)
    }

    @Test
    fun `scrolling the messages closes it`() {
        val state = scrolledAway()
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
    }

    @Test
    fun `back closes it first and says so and then leaves the page`() {
        val state = scrolledAway()
        state.toggleFloat()
        assertTrue(state.closeFloat())
        assertFalse(state.floatOpen)
        assertFalse(state.closeFloat())
    }

    @Test
    fun `once the head is back in view the panel is gone and does not come back on the next scroll away`() {
        val state = scrolledAway()
        state.toggleFloat()
        // A drag down at the oldest message brings the head back.
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 80f), NestedScrollSource.UserInput)
        assertFalse(state.collapsed)
        assertFalse(state.floatOpen)
        state.collapse()
        assertTrue(state.collapsed)
        assertFalse(state.floatOpen)
    }

    @Test
    fun `a press below the panel is outside it and one on it is not`() {
        assertTrue(taskHeadFloatTapIsOutside(y = 501f, floatHeightPx = 500))
        assertFalse(taskHeadFloatTapIsOutside(y = 500f, floatHeightPx = 500))
        assertFalse(taskHeadFloatTapIsOutside(y = 10f, floatHeightPx = 500))
    }

    @Test
    fun `the bar shows the assignee face and the panel only while the head is away`() {
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
