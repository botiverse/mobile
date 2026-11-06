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
 * Task v2 on a phone (slock #11722; artin 2026-11-06, #proj-mobile:01b96d5b): the head is only ever
 * pulled down from the top bar over the messages. It is open when the conversation opens, each way
 * of closing it (web `TaskHeadPullDown`) puts it back up, and after that only the bar brings it back.
 */
class TaskHeadFloatStateTest {
    /** A phone's head, as the conversation opens. */
    private fun onAPhone(): TaskHeadScrollState = TaskHeadScrollState().apply { updatePhone(true) }

    @Test
    fun `on a phone the head is pulled down when the conversation opens`() {
        val state = onAPhone()
        assertTrue(state.collapsed)
        assertTrue(state.floatOpen)
    }

    @Test
    fun `off a phone the head is pinned and the bar has nothing to pull down`() {
        val pinned = TaskHeadScrollState().apply { updatePhone(false) }
        assertFalse(pinned.collapsed)
        assertFalse(pinned.floatOpen)
        pinned.toggleFloat()
        assertFalse(pinned.floatOpen)
        // The panel asked for on entry does not open on a later resize to a phone's width.
        pinned.updatePhone(true)
        assertFalse(pinned.floatOpen)
    }

    @Test
    fun `a tap on the bar closes it and a second tap opens it again`() {
        val state = onAPhone()
        state.toggleFloat()
        assertFalse(state.floatOpen)
        state.toggleFloat()
        assertTrue(state.floatOpen)
    }

    @Test
    fun `scrolling the messages closes it`() {
        val state = onAPhone()
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
        val state = onAPhone()
        assertTrue(state.closeFloat())
        assertFalse(state.floatOpen)
        assertFalse(state.closeFloat())
    }

    @Test
    fun `once closed it stays closed until the bar opens it`() {
        val state = onAPhone()
        state.closeFloat()
        // The page lays out again (a rotation, the keyboard): the head is not pulled down again.
        state.updatePhone(true)
        assertFalse(state.floatOpen)
        // A new conversation is a new state: it opens again.
        assertTrue(onAPhone().floatOpen)
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
