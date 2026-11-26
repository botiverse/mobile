package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenError
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Historical joint task participant read-only discussion parity (botiverse/slock #16739, #16748).
 */
class TaskConversationReadOnlyTest {
    @Test
    fun taskDiscussionAccessDistinguishesWritableReadOnlyAndElsewhere() {
        assertEquals(TaskDiscussionAccess.Writable, taskDiscussionAccess(readOnlyReason = null, discussionElsewhere = false))
        assertEquals(TaskDiscussionAccess.Writable, taskDiscussionAccess(readOnlyReason = "  ", discussionElsewhere = false))
        assertEquals(TaskDiscussionAccess.ReadOnly, taskDiscussionAccess(readOnlyReason = "historical_joint_task", discussionElsewhere = false))
        assertEquals(TaskDiscussionAccess.ReadOnly, taskDiscussionAccess(readOnlyReason = JointTaskReadOnlyErrorCode, discussionElsewhere = false))
        assertEquals(TaskDiscussionAccess.Elsewhere, taskDiscussionAccess(readOnlyReason = "historical_joint_task", discussionElsewhere = true))
        assertEquals(TaskDiscussionAccess.Elsewhere, taskDiscussionAccess(readOnlyReason = null, discussionElsewhere = true))
    }

    @Test
    fun jointTaskReadOnlyRefusalMatches403BlockerAndErrorServerCode() {
        val noPermReadOnly =
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "tasks_conversation_403",
                    serverMessage = "Read-only joint task",
                    serverCode = "joint_task_read_only",
                )
            )
        val errorReadOnly =
            DataResult.Error(
                ScreenError(
                    code = "http_403",
                    serverMessage = "Read-only joint task",
                    serverCode = "joint_task_read_only",
                )
            )
        val noPermOther =
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "tasks_conversation_403",
                    serverMessage = "Forbidden",
                    serverCode = "forbidden",
                )
            )
        assertTrue(noPermReadOnly.isJointTaskReadOnlyRefusal())
        assertTrue(errorReadOnly.isJointTaskReadOnlyRefusal())
        assertFalse(noPermOther.isJointTaskReadOnlyRefusal())
        assertFalse(DataResult.Content("ok").isJointTaskReadOnlyRefusal())
    }

    @Test
    fun taskConversationHeaderChromeCarriesReadOnlyReason() {
        val readOnlyTask = sampleJointTask(readOnlyReason = JointTaskReadOnlyErrorCode)
        val writableTask = sampleJointTask(readOnlyReason = null)
        val state = TaskConversationHeaderState(conversationChannelId = ChannelId("conv-1"))

        val readOnlyChrome =
            readOnlyTask.taskConversationHeaderChrome(
                state = state,
                summarized = false,
                headFloatOpen = false,
                onToggleHeadFloat = {},
                onViewInChannel = {},
                onToggleFollow = {},
            )
        val writableChrome =
            writableTask.taskConversationHeaderChrome(
                state = state,
                summarized = false,
                headFloatOpen = false,
                onToggleHeadFloat = {},
                onViewInChannel = {},
                onToggleFollow = {},
            )
        assertEquals(JointTaskReadOnlyErrorCode, readOnlyChrome.readOnlyReason)
        assertNull(writableChrome.readOnlyReason)
    }

    @Test
    fun taskPendingRouteParamsRoundTripDiscussionElsewhereFlag() {
        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-1", initialServerId = "server-1")
        val elsewhereParams =
            parseTaskPendingRouteParams(
                params = taskPendingPageData(taskId = "task-1", channelId = ChannelId("chan-1"), storeScope = scope, discussionElsewhere = true),
                storeScope = scope,
            )
        assertTrue(elsewhereParams.discussionElsewhere)

        val defaultParams =
            parseTaskPendingRouteParams(
                params = taskPendingPageData(taskId = "task-1", channelId = ChannelId("chan-1"), storeScope = scope),
                storeScope = scope,
            )
        assertFalse(defaultParams.discussionElsewhere)
    }

    @Test
    fun taskOpenSurfaceFlowKeepsElsewherePendingSurfaceOnReadOnlyJointRefresh() {
        val flow = TaskOpenSurfaceFlow()
        val initial = sampleJointTask(readOnlyReason = JointTaskReadOnlyErrorCode, revision = 1L)
        flow.open(TaskOpenSurface.Pending(initial, discussionElsewhere = true))

        val arrived = flow.onTaskChanged(initial.copy(revision = 2L, title = "Updated read-only title"))
        assertNull(arrived)
        val surface = assertIs<TaskOpenSurface.Pending>(flow.surface)
        assertTrue(surface.discussionElsewhere)
        assertEquals("Updated read-only title", surface.task.title)
    }

    private fun sampleJointTask(readOnlyReason: String?, revision: Long = 1L): TaskSummary =
        TaskSummary(
            id = TaskId("task-joint-1"),
            title = "Historical joint task",
            status = TaskStatus.InProgress,
            taskNumber = 12,
            messageId = null,
            parentMessageId = null,
            channelId = ChannelId("chan-joint-1"),
            channelName = "shared-ops",
            updatedAtLabel = "2026-11-25T10:00:00.000Z",
            channelType = "joint",
            conversationChannelId = ChannelId("conv-host-1"),
            conversationAnchored = true,
            readOnlyReason = readOnlyReason,
            revision = revision,
        )
}
